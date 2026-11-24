package sip

import (
	"context"
	"crypto/rand"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"net"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/YubaiNya/SmartVoWifiCall/engine/internal/diag"
	"github.com/YubaiNya/SmartVoWifiCall/engine/internal/ipsec"
	"github.com/YubaiNya/SmartVoWifiCall/engine/internal/simauth"
)

// ClientConfig configures the 3GPP TS 24.229 IMS SIP UA running inside the IPsec ESP tunnel.
type ClientConfig struct {
	Tunnel       *ipsec.Tunnel
	PCSCF        net.IP
	PCSCFPort    uint16
	IMSRealm     string
	IMPI         string
	IMPU         string
	Slot         int
	ICCID        string
	SMSC         string
	SimAuth      simauth.Provider
	IncludeAMR   bool
	Logger       *diag.Logger
	OnEvent      func(name string, data map[string]any)
	OnMediaStart func(callID string, localRTPPort uint16, remote *MediaAnswer)
	OnMediaStop  func(callID string)
}

type callDialog struct {
	id           string
	sipCallID    string
	direction    string // OUTGOING / INCOMING
	stage        string // DIALING / CONNECTING / RINGING / IN_CALL / ENDING / CANCELING / ENDED
	peer         string
	localTag     string
	remoteTag    string
	remoteURI    string
	localRTPPort uint16
	remoteMedia  *MediaAnswer
	inviteReq    *Message // for incoming INVITE 200/486 reply or outgoing CANCEL
	inviteBranch string
	inviteCSeq   int
	doneCh       chan struct{}
	resultStage  string
	resultErr    error
}

// Client implements IMS SIP REGISTER (with AKA Digest + AUTS resync), INVITE/PRACK/BYE/CANCEL calls,
// and 3GPP SIP MESSAGE SMS-over-IP over the userspace IPsec ESP tunnel.
type Client struct {
	cfg      ClientConfig
	sock     *ipsec.UDPSocket
	localIP  net.IP
	pcscf    net.IP
	pcscfPrt uint16

	mu            sync.Mutex
	registered    bool
	regState      string
	regCallID     string
	regCSeq       int
	associatedURI string
	serviceRoute  string
	epoch         string
	revision      int64
	activeCall    *callDialog
	pendingTx     map[string]chan *Message // keyed by Via branch
	rpRef         atomic.Uint32
	cseq          atomic.Uint32
	closed        bool
	stopCh        chan struct{}
	wg            sync.WaitGroup
}

func NewClient(cfg ClientConfig) (*Client, error) {
	if cfg.Tunnel == nil || cfg.PCSCF == nil || cfg.SimAuth == nil {
		return nil, errors.New("SIP_CLIENT_CONFIG_INVALID")
	}
	if cfg.PCSCFPort == 0 {
		cfg.PCSCFPort = 5060
	}
	sock, err := cfg.Tunnel.BindUDP(5060)
	if err != nil {
		sock, err = cfg.Tunnel.BindUDP(0)
		if err != nil {
			return nil, err
		}
	}
	c := &Client{
		cfg:       cfg,
		sock:      sock,
		localIP:   cfg.Tunnel.LocalIP(),
		pcscf:     cfg.PCSCF.To4(),
		pcscfPrt:  cfg.PCSCFPort,
		regState:  "IDLE",
		regCallID: randHex(12) + "@" + cfg.Tunnel.LocalIP().String(),
		epoch:     "ep-" + randHex(6),
		pendingTx: make(map[string]chan *Message),
		stopCh:    make(chan struct{}),
	}
	c.cseq.Store(1)
	c.wg.Add(1)
	go c.rxLoop()
	return c, nil
}

func (c *Client) RegState() string {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.regState
}

func (c *Client) Registered() bool {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.registered
}

func (c *Client) CallSnapshot() map[string]any {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.activeCall == nil {
		return map[string]any{
			"epoch":     c.epoch,
			"revision":  c.revision,
			"stage":     "IDLE",
			"callId":    "",
			"direction": "NONE",
			"peer":      "",
		}
	}
	return map[string]any{
		"epoch":     c.epoch,
		"revision":  c.revision,
		"stage":     c.activeCall.stage,
		"callId":    c.activeCall.id,
		"direction": c.activeCall.direction,
		"peer":      c.activeCall.peer,
	}
}

func (c *Client) contactURI() string {
	user := strings.TrimPrefix(c.cfg.IMPU, "sip:")
	if at := strings.IndexByte(user, '@'); at > 0 {
		user = user[:at]
	}
	return fmt.Sprintf("<sip:%s@%s:%d;transport=udp>", user, c.localIP.String(), c.sock.LocalPort())
}

// Register performs IMS SIP REGISTER with 401 AKA Digest authentication (including AUTS resync).
func (c *Client) Register(ctx context.Context) error {
	c.mu.Lock()
	c.regState = "REGISTERING"
	c.mu.Unlock()

	authHeader := ""
	for attempt := 0; attempt < 4; attempt++ {
		c.mu.Lock()
		c.regCSeq++
		cseq := c.regCSeq
		callID := c.regCallID
		c.mu.Unlock()

		branch := "z9hG4bK" + randHex(8)
		fromTag := randHex(6)
		reqURI := "sip:" + c.cfg.IMSRealm

		msg := &Message{Method: "REGISTER", RequestURI: reqURI}
		msg.AddHeader("Via", fmt.Sprintf("SIP/2.0/UDP %s:%d;branch=%s;rport", c.localIP.String(), c.sock.LocalPort(), branch))
		msg.AddHeader("Max-Forwards", "70")
		msg.AddHeader("From", fmt.Sprintf("<%s>;tag=%s", c.cfg.IMPU, fromTag))
		msg.AddHeader("To", fmt.Sprintf("<%s>", c.cfg.IMPU))
		msg.AddHeader("Call-ID", callID)
		msg.AddHeader("CSeq", fmt.Sprintf("%d REGISTER", cseq))
		msg.AddHeader("Contact", c.contactURI()+";+g.3gpp.icsi-ref=\"urn%3Aurn-7%3A3gpp-service.ims.icsi.mmtel\";+g.3gpp.smsip")
		msg.AddHeader("Expires", "3600")
		msg.AddHeader("P-Access-Network-Info", "IEEE-802.11;i-wlan-node-id=000000000000")
		msg.AddHeader("Supported", "path, gruu, sec-agree")
		if authHeader != "" {
			msg.AddHeader("Authorization", authHeader)
		} else {
			msg.AddHeader("Authorization", fmt.Sprintf("Digest username=\"%s\", realm=\"%s\", nonce=\"\", uri=\"%s\", response=\"\"",
				c.cfg.IMPI, c.cfg.IMSRealm, reqURI))
		}

		resp, err := c.sendRequestWaitFinal(ctx, branch, msg)
		if err != nil {
			c.setRegState("DISCONNECTED", false)
			return fmt.Errorf("SIP_REGISTER_TX: %w", err)
		}
		if resp.StatusCode == 200 {
			c.mu.Lock()
			c.registered = true
			c.regState = "REGISTERED"
			if pau := resp.GetHeader("P-Associated-URI"); pau != "" {
				c.associatedURI = strings.Trim(strings.Split(pau, ",")[0], " <>")
			}
			if sr := resp.GetHeader("Service-Route"); sr != "" {
				c.serviceRoute = sr
			}
			c.mu.Unlock()
			if c.cfg.Logger != nil {
				c.cfg.Logger.Info("sip", "IMS REGISTER 200 OK", nil)
			}
			return nil
		}
		if resp.StatusCode == 401 {
			www := resp.GetHeader("WWW-Authenticate")
			params := ParseDigestParams(www)
			rand16, autn16, err := DecodeAKANonce(params["nonce"])
			if err != nil {
				c.setRegState("DISCONNECTED", false)
				return err
			}
			authResp, err := c.cfg.SimAuth.Authenticate(ctx, simauth.AuthRequest{
				Slot:    c.cfg.Slot,
				ICCID:   c.cfg.ICCID,
				AppType: simauth.AppISIM,
				RAND:    rand16,
				AUTN:    autn16,
			})
			if err != nil {
				// Fallback to USIM application when card has no ISIM applet
				authResp, err = c.cfg.SimAuth.Authenticate(ctx, simauth.AuthRequest{
					Slot:    c.cfg.Slot,
					ICCID:   c.cfg.ICCID,
					AppType: simauth.AppUSIM,
					RAND:    rand16,
					AUTN:    autn16,
				})
			}
			if err != nil {
				c.setRegState("DISCONNECTED", false)
				return fmt.Errorf("SIP_AKA_AUTH_FAILED: %w", err)
			}
			realm := params["realm"]
			if realm == "" {
				realm = c.cfg.IMSRealm
			}
			alg := params["algorithm"]
			if alg == "" {
				alg = "AKAv1-MD5"
			}
			if authResp.Status == simauth.StatusSyncFailure {
				autsB64 := base64.StdEncoding.EncodeToString(authResp.AUTS)
				authHeader = fmt.Sprintf("Digest username=\"%s\", realm=\"%s\", nonce=\"%s\", uri=\"%s\", response=\"\", algorithm=%s, auts=\"%s\"",
					c.cfg.IMPI, realm, params["nonce"], reqURI, alg, autsB64)
				continue
			}
			qop := ""
			if strings.Contains(params["qop"], "auth") {
				qop = "auth"
			}
			nc := "00000001"
			cnonce := randHex(8)
			digest := ComputeAKADigest(c.cfg.IMPI, realm, "REGISTER", reqURI, params["nonce"], nc, cnonce, qop, alg, authResp.RES, authResp.IK, authResp.CK)
			if qop != "" {
				authHeader = fmt.Sprintf("Digest username=\"%s\", realm=\"%s\", nonce=\"%s\", uri=\"%s\", response=\"%s\", algorithm=%s, qop=%s, nc=%s, cnonce=\"%s\"",
					c.cfg.IMPI, realm, params["nonce"], reqURI, digest, alg, qop, nc, cnonce)
			} else {
				authHeader = fmt.Sprintf("Digest username=\"%s\", realm=\"%s\", nonce=\"%s\", uri=\"%s\", response=\"%s\", algorithm=%s",
					c.cfg.IMPI, realm, params["nonce"], reqURI, digest, alg)
			}
			if opq := params["opaque"]; opq != "" {
				authHeader += fmt.Sprintf(", opaque=\"%s\"", opq)
			}
			continue
		}
		c.setRegState("DISCONNECTED", false)
		return fmt.Errorf("SIP_REGISTER_HTTP_%d", resp.StatusCode)
	}
	c.setRegState("DISCONNECTED", false)
	return errors.New("SIP_REGISTER_EXHAUSTED")
}

func (c *Client) setRegState(st string, reg bool) {
	c.mu.Lock()
	c.regState = st
	c.registered = reg
	c.mu.Unlock()
}

// Dial initiates an outgoing IMS voice call via SIP INVITE with SDP offer.
func (c *Client) Dial(ctx context.Context, recipient string) (map[string]any, error) {
	c.mu.Lock()
	if !c.registered {
		c.mu.Unlock()
		return nil, errors.New("IMS_NOT_REGISTERED")
	}
	if c.activeCall != nil && c.activeCall.stage != "IDLE" && c.activeCall.stage != "ENDED" {
		c.mu.Unlock()
		return nil, errors.New("CALL_ALREADY_ACTIVE")
	}
	callID := "call-" + randHex(6)
	sipCID := randHex(10) + "@" + c.localIP.String()
	branch := "z9hG4bK" + randHex(8)
	cseq := int(c.cseq.Add(1))
	localTag := randHex(6)
	rtpPort := uint16(40000 + (cseq%1000)*2)
	peer := strings.TrimPrefix(recipient, "tel:")
	reqURI := recipient
	if !strings.HasPrefix(reqURI, "tel:") && !strings.HasPrefix(reqURI, "sip:") {
		reqURI = "tel:" + reqURI
	}

	dlg := &callDialog{
		id:           callID,
		sipCallID:    sipCID,
		direction:    "OUTGOING",
		stage:        "DIALING",
		peer:         peer,
		localTag:     localTag,
		remoteURI:    reqURI,
		localRTPPort: rtpPort,
		inviteBranch: branch,
		inviteCSeq:   cseq,
		doneCh:       make(chan struct{}),
	}
	c.activeCall = dlg
	c.revision++
	rev := c.revision
	c.mu.Unlock()

	c.emitCallEvent("callState", dlg, rev)

	sdp := BuildSDP(MediaOffer{
		LocalIP:    c.localIP,
		LocalPort:  rtpPort,
		SessionID:  uint64(time.Now().Unix()),
		IncludeAMR: c.cfg.IncludeAMR,
	})
	fromURI := c.cfg.IMPU
	if c.associatedURI != "" {
		fromURI = c.associatedURI
	}
	inv := &Message{Method: "INVITE", RequestURI: reqURI, Body: sdp}
	inv.AddHeader("Via", fmt.Sprintf("SIP/2.0/UDP %s:%d;branch=%s;rport", c.localIP.String(), c.sock.LocalPort(), branch))
	inv.AddHeader("Max-Forwards", "70")
	inv.AddHeader("From", fmt.Sprintf("<%s>;tag=%s", fromURI, localTag))
	inv.AddHeader("To", fmt.Sprintf("<%s>", reqURI))
	inv.AddHeader("Call-ID", sipCID)
	inv.AddHeader("CSeq", fmt.Sprintf("%d INVITE", cseq))
	inv.AddHeader("Contact", c.contactURI())
	inv.AddHeader("P-Access-Network-Info", "IEEE-802.11;i-wlan-node-id=000000000000")
	inv.AddHeader("Content-Type", "application/sdp")
	inv.AddHeader("Allow", "INVITE, ACK, CANCEL, BYE, PRACK, UPDATE, MESSAGE")

	txCh := c.registerTx(branch)
	defer c.unregisterTx(branch)
	if err := c.sendSIP(inv); err != nil {
		c.endDialog(dlg, "SEND_FAILED")
		return nil, err
	}

	for {
		select {
		case resp := <-txCh:
			if resp.StatusCode >= 100 && resp.StatusCode < 200 {
				if resp.StatusCode == 180 || resp.StatusCode == 183 {
					c.mu.Lock()
					if toTag := extractTag(resp.GetHeader("To")); toTag != "" {
						dlg.remoteTag = toTag
					}
					dlg.stage = "CONNECTING"
					c.revision++
					rev = c.revision
					c.mu.Unlock()
					c.emitCallEvent("callState", dlg, rev)
					// Support 100rel PRACK if required by carrier
					if strings.Contains(strings.ToLower(resp.GetHeader("Require")), "100rel") {
						c.sendPRACK(dlg, resp)
					}
				}
				continue
			}
			if resp.StatusCode >= 200 && resp.StatusCode < 300 {
				c.mu.Lock()
				if toTag := extractTag(resp.GetHeader("To")); toTag != "" {
					dlg.remoteTag = toTag
				}
				if ans, err := ParseSDP(resp.Body); err == nil {
					dlg.remoteMedia = ans
				}
				dlg.stage = "IN_CALL"
				c.revision++
				rev = c.revision
				c.mu.Unlock()

				c.sendACK(dlg, resp)
				if dlg.remoteMedia != nil && c.cfg.OnMediaStart != nil {
					c.cfg.OnMediaStart(dlg.id, dlg.localRTPPort, dlg.remoteMedia)
				}
				c.emitCallEvent("callState", dlg, rev)
				return map[string]any{"callId": dlg.id, "state": "IN_CALL"}, nil
			}
			c.sendACK(dlg, resp)
			c.endDialog(dlg, fmt.Sprintf("SIP_%d", resp.StatusCode))
			return nil, fmt.Errorf("SIP_INVITE_%d", resp.StatusCode)

		case <-dlg.doneCh:
			return map[string]any{"callId": dlg.id, "state": "ENDED"}, nil
		case <-ctx.Done():
			_, _ = c.CancelCall(context.Background(), dlg.id)
			return nil, ctx.Err()
		}
	}
}

func (c *Client) Answer(ctx context.Context, callID string) (map[string]any, error) {
	c.mu.Lock()
	dlg := c.activeCall
	if dlg == nil || dlg.id != callID || dlg.inviteReq == nil {
		c.mu.Unlock()
		return nil, errors.New("CALL_NOT_FOUND")
	}
	sdp := BuildSDP(MediaOffer{
		LocalIP:    c.localIP,
		LocalPort:  dlg.localRTPPort,
		SessionID:  uint64(time.Now().Unix()),
		IncludeAMR: c.cfg.IncludeAMR,
	})
	resp := c.buildReply(dlg.inviteReq, 200, "OK", dlg.localTag, sdp, "application/sdp")
	dlg.stage = "IN_CALL"
	c.revision++
	rev := c.revision
	remote := dlg.remoteMedia
	localPort := dlg.localRTPPort
	c.mu.Unlock()

	if err := c.sendSIP(resp); err != nil {
		return nil, err
	}
	if remote != nil && c.cfg.OnMediaStart != nil {
		c.cfg.OnMediaStart(dlg.id, localPort, remote)
	}
	c.emitCallEvent("callState", dlg, rev)
	return map[string]any{"callId": callID, "state": "IN_CALL"}, nil
}

func (c *Client) Reject(ctx context.Context, callID string) (map[string]any, error) {
	c.mu.Lock()
	dlg := c.activeCall
	if dlg == nil || dlg.id != callID || dlg.inviteReq == nil {
		c.mu.Unlock()
		return map[string]any{"callId": callID, "state": "ENDED"}, nil
	}
	resp := c.buildReply(dlg.inviteReq, 486, "Busy Here", dlg.localTag, nil, "")
	c.mu.Unlock()
	_ = c.sendSIP(resp)
	c.endDialog(dlg, "REJECTED")
	return map[string]any{"callId": callID, "state": "ENDED"}, nil
}

func (c *Client) CancelCall(ctx context.Context, callID string) (map[string]any, error) {
	c.mu.Lock()
	dlg := c.activeCall
	if dlg == nil || dlg.id != callID {
		c.mu.Unlock()
		return map[string]any{"callId": callID, "state": "ENDED"}, nil
	}
	cancelMsg := &Message{Method: "CANCEL", RequestURI: dlg.remoteURI}
	cancelMsg.AddHeader("Via", fmt.Sprintf("SIP/2.0/UDP %s:%d;branch=%s;rport", c.localIP.String(), c.sock.LocalPort(), dlg.inviteBranch))
	cancelMsg.AddHeader("Max-Forwards", "70")
	cancelMsg.AddHeader("From", fmt.Sprintf("<%s>;tag=%s", c.cfg.IMPU, dlg.localTag))
	cancelMsg.AddHeader("To", fmt.Sprintf("<%s>", dlg.remoteURI))
	cancelMsg.AddHeader("Call-ID", dlg.sipCallID)
	cancelMsg.AddHeader("CSeq", fmt.Sprintf("%d CANCEL", dlg.inviteCSeq))
	c.mu.Unlock()

	_ = c.sendSIP(cancelMsg)
	c.endDialog(dlg, "CANCELED")
	return map[string]any{"callId": callID, "state": "ENDED"}, nil
}

func (c *Client) Hangup(ctx context.Context, callID string) (map[string]any, error) {
	c.mu.Lock()
	dlg := c.activeCall
	if dlg == nil || dlg.id != callID {
		c.mu.Unlock()
		return map[string]any{"callId": callID, "state": "ENDED"}, nil
	}
	branch := "z9hG4bK" + randHex(8)
	cseq := int(c.cseq.Add(1))
	bye := &Message{Method: "BYE", RequestURI: dlg.remoteURI}
	bye.AddHeader("Via", fmt.Sprintf("SIP/2.0/UDP %s:%d;branch=%s;rport", c.localIP.String(), c.sock.LocalPort(), branch))
	bye.AddHeader("Max-Forwards", "70")
	bye.AddHeader("From", fmt.Sprintf("<%s>;tag=%s", c.cfg.IMPU, dlg.localTag))
	toHdr := fmt.Sprintf("<%s>", dlg.remoteURI)
	if dlg.remoteTag != "" {
		toHdr += ";tag=" + dlg.remoteTag
	}
	bye.AddHeader("To", toHdr)
	bye.AddHeader("Call-ID", dlg.sipCallID)
	bye.AddHeader("CSeq", fmt.Sprintf("%d BYE", cseq))
	c.mu.Unlock()

	_ = c.sendSIP(bye)
	c.endDialog(dlg, "HANGUP")
	return map[string]any{"callId": callID, "state": "ENDED"}, nil
}

// SendSMS sends an outbound 3GPP SMS-SUBMIT wrapped in RP-DATA over SIP MESSAGE.
func (c *Client) SendSMS(ctx context.Context, number, text string) (map[string]any, error) {
	if !c.Registered() {
		return nil, errors.New("IMS_NOT_REGISTERED")
	}
	rpRef := byte(c.rpRef.Add(1))
	body, err := EncodeRPDataSubmit(rpRef, c.cfg.SMSC, number, text)
	if err != nil {
		return nil, err
	}
	branch := "z9hG4bK" + randHex(8)
	cseq := int(c.cseq.Add(1))
	smsID := "sms-" + randHex(6)
	reqURI := "sip:" + c.cfg.IMSRealm
	msg := &Message{Method: "MESSAGE", RequestURI: reqURI, Body: body}
	msg.AddHeader("Via", fmt.Sprintf("SIP/2.0/UDP %s:%d;branch=%s;rport", c.localIP.String(), c.sock.LocalPort(), branch))
	msg.AddHeader("Max-Forwards", "70")
	msg.AddHeader("From", fmt.Sprintf("<%s>;tag=%s", c.cfg.IMPU, randHex(6)))
	msg.AddHeader("To", fmt.Sprintf("<%s>", reqURI))
	msg.AddHeader("Call-ID", smsID+"@"+c.localIP.String())
	msg.AddHeader("CSeq", fmt.Sprintf("%d MESSAGE", cseq))
	msg.AddHeader("P-Access-Network-Info", "IEEE-802.11;i-wlan-node-id=000000000000")
	msg.AddHeader("Content-Type", "application/vnd.3gpp.sms")

	resp, err := c.sendRequestWaitFinal(ctx, branch, msg)
	if err != nil {
		return nil, err
	}
	return map[string]any{
		"statusCode": resp.StatusCode,
		"smsId":      smsID,
	}, nil
}

func (c *Client) rxLoop() {
	defer c.wg.Done()
	for {
		pkt, err := c.sock.ReadFrom(time.Time{})
		if err != nil {
			return
		}
		msg, err := ParseMessage(pkt.Payload)
		if err != nil {
			continue
		}
		if msg.IsResponse {
			branch := extractBranch(msg.GetHeader("Via"))
			c.mu.Lock()
			ch := c.pendingTx[branch]
			c.mu.Unlock()
			if ch != nil {
				select {
				case ch <- msg:
				default:
				}
			}
			continue
		}
		c.handleIncomingRequest(msg)
	}
}

func (c *Client) handleIncomingRequest(req *Message) {
	switch req.Method {
	case "INVITE":
		c.mu.Lock()
		if c.activeCall != nil && c.activeCall.stage != "IDLE" && c.activeCall.stage != "ENDED" {
			c.mu.Unlock()
			_ = c.sendSIP(c.buildReply(req, 486, "Busy Here", randHex(6), nil, ""))
			return
		}
		callID := "call-" + randHex(6)
		localTag := randHex(6)
		rtpPort := uint16(42000 + (c.cseq.Add(1)%1000)*2)
		peer := ExtractCallerNumber(req)
		ans, _ := ParseSDP(req.Body)
		dlg := &callDialog{
			id:           callID,
			sipCallID:    req.GetHeader("Call-ID"),
			direction:    "INCOMING",
			stage:        "RINGING",
			peer:         peer,
			localTag:     localTag,
			remoteTag:    extractTag(req.GetHeader("From")),
			remoteURI:    extractURI(req.GetHeader("From")),
			localRTPPort: rtpPort,
			remoteMedia:  ans,
			inviteReq:    req,
			doneCh:       make(chan struct{}),
		}
		c.activeCall = dlg
		c.revision++
		rev := c.revision
		c.mu.Unlock()

		_ = c.sendSIP(c.buildReply(req, 180, "Ringing", localTag, nil, ""))
		c.emitCallEvent("incomingCall", dlg, rev)

	case "BYE", "CANCEL":
		_ = c.sendSIP(c.buildReply(req, 200, "OK", randHex(6), nil, ""))
		cid := req.GetHeader("Call-ID")
		c.mu.Lock()
		dlg := c.activeCall
		c.mu.Unlock()
		if dlg != nil && dlg.sipCallID == cid {
			c.endDialog(dlg, "REMOTE_"+req.Method)
		}

	case "MESSAGE":
		_ = c.sendSIP(c.buildReply(req, 200, "OK", randHex(6), nil, ""))
		if strings.Contains(strings.ToLower(req.GetHeader("Content-Type")), "3gpp.sms") {
			sms, err := DecodeRPDataDeliver(req.Body)
			if err == nil {
				// Send RP-ACK back to network
				c.sendRPAck(sms.RPMessageRef)
				if c.cfg.OnEvent != nil {
					c.cfg.OnEvent("smsReceived", map[string]any{
						"smsId":     "sms-" + randHex(6),
						"number":    sms.Sender,
						"text":      sms.Text,
						"timestamp": sms.Timestamp.UnixMilli(),
					})
				}
			}
		}

	case "OPTIONS":
		_ = c.sendSIP(c.buildReply(req, 200, "OK", randHex(6), nil, ""))
	}
}

func (c *Client) sendRPAck(rpRef byte) {
	branch := "z9hG4bK" + randHex(8)
	cseq := int(c.cseq.Add(1))
	reqURI := "sip:" + c.cfg.IMSRealm
	ack := &Message{Method: "MESSAGE", RequestURI: reqURI, Body: EncodeRPAck(rpRef)}
	ack.AddHeader("Via", fmt.Sprintf("SIP/2.0/UDP %s:%d;branch=%s;rport", c.localIP.String(), c.sock.LocalPort(), branch))
	ack.AddHeader("Max-Forwards", "70")
	ack.AddHeader("From", fmt.Sprintf("<%s>;tag=%s", c.cfg.IMPU, randHex(6)))
	ack.AddHeader("To", fmt.Sprintf("<%s>", reqURI))
	ack.AddHeader("Call-ID", "rpack-"+randHex(6)+"@"+c.localIP.String())
	ack.AddHeader("CSeq", fmt.Sprintf("%d MESSAGE", cseq))
	ack.AddHeader("Content-Type", "application/vnd.3gpp.sms")
	_ = c.sendSIP(ack)
}

func (c *Client) sendPRACK(dlg *callDialog, prov *Message) {
	rseq := strings.TrimSpace(prov.GetHeader("RSeq"))
	if rseq == "" {
		return
	}
	branch := "z9hG4bK" + randHex(8)
	cseq := int(c.cseq.Add(1))
	prack := &Message{Method: "PRACK", RequestURI: dlg.remoteURI}
	prack.AddHeader("Via", fmt.Sprintf("SIP/2.0/UDP %s:%d;branch=%s;rport", c.localIP.String(), c.sock.LocalPort(), branch))
	prack.AddHeader("Max-Forwards", "70")
	prack.AddHeader("From", fmt.Sprintf("<%s>;tag=%s", c.cfg.IMPU, dlg.localTag))
	prack.AddHeader("To", prov.GetHeader("To"))
	prack.AddHeader("Call-ID", dlg.sipCallID)
	prack.AddHeader("CSeq", fmt.Sprintf("%d PRACK", cseq))
	prack.AddHeader("RAck", rseq+" "+prov.GetHeader("CSeq"))
	_ = c.sendSIP(prack)
}

func (c *Client) sendACK(dlg *callDialog, resp *Message) {
	branch := "z9hG4bK" + randHex(8)
	ack := &Message{Method: "ACK", RequestURI: dlg.remoteURI}
	ack.AddHeader("Via", fmt.Sprintf("SIP/2.0/UDP %s:%d;branch=%s;rport", c.localIP.String(), c.sock.LocalPort(), branch))
	ack.AddHeader("Max-Forwards", "70")
	ack.AddHeader("From", fmt.Sprintf("<%s>;tag=%s", c.cfg.IMPU, dlg.localTag))
	ack.AddHeader("To", resp.GetHeader("To"))
	ack.AddHeader("Call-ID", dlg.sipCallID)
	ack.AddHeader("CSeq", fmt.Sprintf("%d ACK", dlg.inviteCSeq))
	_ = c.sendSIP(ack)
}

func (c *Client) buildReply(req *Message, code int, reason, localTag string, body []byte, contentType string) *Message {
	r := &Message{IsResponse: true, StatusCode: code, Reason: reason, Body: body}
	for _, v := range req.GetAllHeaders("Via") {
		r.AddHeader("Via", v)
	}
	r.AddHeader("From", req.GetHeader("From"))
	to := req.GetHeader("To")
	if !strings.Contains(to, "tag=") && localTag != "" {
		to += ";tag=" + localTag
	}
	r.AddHeader("To", to)
	r.AddHeader("Call-ID", req.GetHeader("Call-ID"))
	r.AddHeader("CSeq", req.GetHeader("CSeq"))
	r.AddHeader("Contact", c.contactURI())
	if contentType != "" {
		r.AddHeader("Content-Type", contentType)
	}
	return r
}

func (c *Client) endDialog(dlg *callDialog, reason string) {
	c.mu.Lock()
	if dlg.stage == "ENDED" {
		c.mu.Unlock()
		return
	}
	dlg.stage = "ENDED"
	c.revision++
	rev := c.revision
	if c.activeCall == dlg {
		c.activeCall = nil
	}
	select {
	case <-dlg.doneCh:
	default:
		close(dlg.doneCh)
	}
	c.mu.Unlock()

	if c.cfg.OnMediaStop != nil {
		c.cfg.OnMediaStop(dlg.id)
	}
	if c.cfg.OnEvent != nil {
		c.cfg.OnEvent("callEnded", map[string]any{
			"epoch":    c.epoch,
			"revision": rev,
			"callId":   dlg.id,
			"stage":    "ENDED",
			"state":    "ENDED",
			"reason":   reason,
		})
	}
}

func (c *Client) emitCallEvent(eventName string, dlg *callDialog, rev int64) {
	if c.cfg.OnEvent == nil {
		return
	}
	c.cfg.OnEvent(eventName, map[string]any{
		"epoch":     c.epoch,
		"revision":  rev,
		"callId":    dlg.id,
		"direction": dlg.direction,
		"stage":     dlg.stage,
		"state":     dlg.stage,
		"peer":      dlg.peer,
	})
}

func (c *Client) registerTx(branch string) chan *Message {
	ch := make(chan *Message, 8)
	c.mu.Lock()
	c.pendingTx[branch] = ch
	c.mu.Unlock()
	return ch
}

func (c *Client) unregisterTx(branch string) {
	c.mu.Lock()
	delete(c.pendingTx, branch)
	c.mu.Unlock()
}

func (c *Client) sendSIP(msg *Message) error {
	return c.sock.WriteTo(c.pcscf, c.pcscfPrt, msg.Encode())
}

func (c *Client) sendRequestWaitFinal(ctx context.Context, branch string, msg *Message) (*Message, error) {
	ch := c.registerTx(branch)
	defer c.unregisterTx(branch)
	for attempt := 0; attempt < 3; attempt++ {
		if err := c.sendSIP(msg); err != nil {
			return nil, err
		}
		timer := time.NewTimer(3 * time.Second)
		for {
			select {
			case resp := <-ch:
				if resp.StatusCode >= 100 && resp.StatusCode < 200 {
					continue
				}
				timer.Stop()
				return resp, nil
			case <-ctx.Done():
				timer.Stop()
				return nil, ctx.Err()
			case <-c.stopCh:
				timer.Stop()
				return nil, net.ErrClosed
			case <-timer.C:
				goto retry
			}
		}
	retry:
	}
	return nil, errors.New("SIP_TX_TIMEOUT")
}

func (c *Client) Close() error {
	c.mu.Lock()
	if c.closed {
		c.mu.Unlock()
		return nil
	}
	c.closed = true
	close(c.stopCh)
	c.mu.Unlock()
	err := c.sock.Close()
	c.wg.Wait()
	return err
}

func randHex(nBytes int) string {
	b := make([]byte, nBytes)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}

func extractBranch(via string) string {
	for _, part := range strings.Split(via, ";") {
		p := strings.TrimSpace(part)
		if strings.HasPrefix(strings.ToLower(p), "branch=") {
			return p[7:]
		}
	}
	return ""
}

func extractTag(hdr string) string {
	for _, part := range strings.Split(hdr, ";") {
		p := strings.TrimSpace(part)
		if strings.HasPrefix(strings.ToLower(p), "tag=") {
			return p[4:]
		}
	}
	return ""
}

func extractURI(hdr string) string {
	if s := strings.IndexByte(hdr, '<'); s >= 0 {
		if e := strings.IndexByte(hdr[s+1:], '>'); e >= 0 {
			return hdr[s+1 : s+1+e]
		}
	}
	return strings.TrimSpace(strings.Split(hdr, ";")[0])
}

// ParseCSeqNumber extracts the integer sequence number from a CSeq header.
func ParseCSeqNumber(cseq string) int {
	fields := strings.Fields(cseq)
	if len(fields) == 0 {
		return 0
	}
	n, _ := strconv.Atoi(fields[0])
	return n
}

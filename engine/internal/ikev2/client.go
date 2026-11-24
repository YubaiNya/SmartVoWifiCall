package ikev2

import (
	"bytes"
	"context"
	"crypto/rand"
	"crypto/sha1"
	"crypto/subtle"
	"encoding/binary"
	"errors"
	"fmt"
	"net"
	"sync"
	"sync/atomic"
	"time"

	"github.com/YubaiNya/SmartVoWifiCall/engine/internal/diag"
	"github.com/YubaiNya/SmartVoWifiCall/engine/internal/ipsec"
	"github.com/YubaiNya/SmartVoWifiCall/engine/internal/simauth"
)

// SessionConfig holds parameters for establishing an RFC 7296 + EAP-AKA ePDG tunnel.
type SessionConfig struct {
	EPDGAddr     string // host:port (default port 500 or 4500 for NAT-T)
	NATTAddr     string // optional explicit NAT-T port (default same host port 4500)
	EAPIdentity  string // 0<IMSI>@wlan.mnc<MNC3>.mcc<MCC>.3gppnetwork.org
	APN          string // usually "ims"
	Slot         int
	ICCID        string
	SimAuth      simauth.Provider
	DPDInterval  time.Duration
	Logger       *diag.Logger
}

// Client manages an active IKEv2 SA + userspace IPsec ESP Tunnel over NAT-T UDP.
type Client struct {
	cfg      SessionConfig
	conn     *net.UDPConn
	nattMode bool

	mu        sync.Mutex
	spii      uint64
	spir      uint64
	msgID     uint32
	keys      *IKEKeys
	tunnel    *ipsec.Tunnel
	cfgResult *CFGResult
	pending   map[uint32]chan []byte
	closed    bool
	stopCh    chan struct{}
	deadCh    chan struct{}
	deadOnce  sync.Once
	lastRecv  atomic.Int64
	lastSend  atomic.Int64
	wg        sync.WaitGroup
}

func Connect(ctx context.Context, cfg SessionConfig) (*Client, error) {
	if cfg.SimAuth == nil {
		return nil, errors.New("IKE_SIM_AUTH_REQUIRED")
	}
	if cfg.APN == "" {
		cfg.APN = "ims"
	}
	if cfg.DPDInterval <= 0 {
		cfg.DPDInterval = 35 * time.Second
	}
	raddr, err := net.ResolveUDPAddr("udp4", cfg.EPDGAddr)
	if err != nil {
		return nil, fmt.Errorf("IKE_RESOLVE_EPDG: %w", err)
	}
	conn, err := net.DialUDP("udp4", nil, raddr)
	if err != nil {
		return nil, fmt.Errorf("IKE_DIAL_UDP: %w", err)
	}

	var spib [8]byte
	_, _ = rand.Read(spib[:])
	now := time.Now().UnixNano()
	c := &Client{
		cfg:      cfg,
		conn:     conn,
		nattMode: raddr.Port == 4500,
		spii:     binary.BigEndian.Uint64(spib[:]),
		pending:  make(map[uint32]chan []byte),
		stopCh:   make(chan struct{}),
		deadCh:   make(chan struct{}),
	}
	c.lastRecv.Store(now)
	c.lastSend.Store(now)

	c.wg.Add(1)
	go c.readLoop(conn)

	if err := c.handshake(ctx); err != nil {
		_ = c.Close()
		return nil, err
	}

	c.wg.Add(1)
	go c.dpdLoop()
	return c, nil
}

func (c *Client) Tunnel() *ipsec.Tunnel { return c.tunnel }
func (c *Client) Config() *CFGResult    { return c.cfgResult }
func (c *Client) Dead() <-chan struct{} { return c.deadCh }

func (c *Client) markDead(reason string) {
	c.deadOnce.Do(func() {
		if c.cfg.Logger != nil {
			c.cfg.Logger.Warn("ikev2", "IKEv2 session dead: "+reason, nil)
		}
		close(c.deadCh)
	})
}

func (c *Client) SendESP(esp []byte) error {
	c.mu.Lock()
	closed := c.closed
	conn := c.conn
	c.mu.Unlock()
	if closed || conn == nil {
		return net.ErrClosed
	}
	_, err := conn.Write(esp)
	if err == nil {
		c.lastSend.Store(time.Now().UnixNano())
	}
	return err
}

func (c *Client) sendNATKeepalive() {
	c.mu.Lock()
	closed := c.closed
	natt := c.nattMode
	conn := c.conn
	c.mu.Unlock()
	if closed || !natt || conn == nil {
		return
	}
	// RFC 3948 §4: single 0xFF byte keeps NAT/proxy UDP mapping alive without touching IKE msgID.
	if _, err := conn.Write([]byte{0xFF}); err == nil {
		c.lastSend.Store(time.Now().UnixNano())
	}
}

func (c *Client) sendIKE(pkt []byte) error {
	c.mu.Lock()
	natt := c.nattMode
	conn := c.conn
	c.mu.Unlock()
	var err error
	if natt {
		framed := make([]byte, 4+len(pkt))
		copy(framed[4:], pkt)
		_, err = conn.Write(framed)
	} else {
		_, err = conn.Write(pkt)
	}
	if err == nil {
		c.lastSend.Store(time.Now().UnixNano())
	}
	return err
}

func (c *Client) exchange(ctx context.Context, msgID uint32, pkt []byte) ([]byte, error) {
	ch := make(chan []byte, 1)
	c.mu.Lock()
	c.pending[msgID] = ch
	c.mu.Unlock()
	defer func() {
		c.mu.Lock()
		delete(c.pending, msgID)
		c.mu.Unlock()
	}()

	for attempt := 0; attempt < 3; attempt++ {
		if err := c.sendIKE(pkt); err != nil {
			return nil, err
		}
		timer := time.NewTimer(3 * time.Second)
		select {
		case resp := <-ch:
			timer.Stop()
			return resp, nil
		case <-ctx.Done():
			timer.Stop()
			return nil, ctx.Err()
		case <-c.stopCh:
			timer.Stop()
			return nil, net.ErrClosed
		case <-timer.C:
		}
	}
	return nil, errors.New("IKE_EXCHANGE_TIMEOUT")
}

func (c *Client) readLoop(conn *net.UDPConn) {
	defer c.wg.Done()
	buf := make([]byte, 4096)
	for {
		n, err := conn.Read(buf)
		if err != nil {
			c.mu.Lock()
			isCurrent := c.conn == conn && !c.closed
			c.mu.Unlock()
			if isCurrent {
				c.markDead("UDP_READ_ERROR")
			}
			return
		}
		if n < 4 {
			continue
		}
		c.lastRecv.Store(time.Now().UnixNano())
		data := make([]byte, n)
		copy(data, buf[:n])

		c.mu.Lock()
		natt := c.nattMode
		tun := c.tunnel
		c.mu.Unlock()

		// NAT-T demux (RFC 3948): 4 zero bytes = IKE packet; non-zero SPI = ESP packet.
		if natt && binary.BigEndian.Uint32(data[0:4]) != 0 {
			if tun != nil {
				_ = tun.HandleESP(data)
			}
			continue
		}
		if natt && binary.BigEndian.Uint32(data[0:4]) == 0 {
			data = data[4:]
		}
		if len(data) < 28 {
			continue
		}
		msgID := binary.BigEndian.Uint32(data[20:24])
		flags := data[19]
		if (flags & FlagResponse) != 0 {
			c.mu.Lock()
			ch := c.pending[msgID]
			c.mu.Unlock()
			if ch != nil {
				select {
				case ch <- data:
				default:
				}
			}
		} else if data[18] == ExchangeInformational {
			// Reply to peer DPD / INFORMATIONAL request.
			go c.replyInformational(data)
		}
	}
}

func (c *Client) replyInformational(req []byte) {
	c.mu.Lock()
	keys := c.keys
	spii, spir := c.spii, c.spir
	c.mu.Unlock()
	if keys == nil {
		return
	}
	h, inner, err := DecodeEncryptedPacket(req, keys, false)
	if err != nil {
		return
	}
	resp, err := EncodeEncryptedPacket(Header{
		SPIi:      spii,
		SPIr:      spir,
		Exchange:  ExchangeInformational,
		Flags:     FlagInitiator | FlagResponse,
		MessageID: h.MessageID,
	}, nil, keys, true)
	if err == nil {
		_ = c.sendIKE(resp)
	}
	if FindPayload(inner, PayloadDelete) != nil {
		c.markDead("PEER_DELETE")
	}
}

func (c *Client) handshake(ctx context.Context) error {
	// 1. IKE_SA_INIT (handles COOKIE=16390 and INVALID_KE_PAYLOAD=17 automatic retries)
	dhGroup := DHGroupMODP2048
	var cookie []byte
	var dh *DHKeyPair
	var ni, nr, initReq []byte
	var respHdr Header
	var respPayloads []Payload

	for initTry := 0; initTry < 3; initTry++ {
		var err error
		dh, err = GenerateDH(dhGroup)
		if err != nil {
			return err
		}
		ni = make([]byte, 32)
		_, _ = rand.Read(ni)

		natSrc := natHash(c.spii, 0, c.conn.LocalAddr().(*net.UDPAddr))
		natDst := natHash(c.spii, 0, c.conn.RemoteAddr().(*net.UDPAddr))
		initPayloads := make([]Payload, 0, 6)
		if len(cookie) > 0 {
			initPayloads = append(initPayloads, Payload{Type: PayloadNotify, Body: buildNotify(16390, cookie)})
		}
		initPayloads = append(initPayloads,
			Payload{Type: PayloadSA, Body: BuildMultiIKESAProposal(dhGroup)},
			Payload{Type: PayloadKE, Body: BuildKEPayload(dhGroup, dh.Public)},
			Payload{Type: PayloadNonce, Body: ni},
			Payload{Type: PayloadNotify, Body: buildNotify(NotifyNATDetectionSourceIP, natSrc)},
			Payload{Type: PayloadNotify, Body: buildNotify(NotifyNATDetectionDestIP, natDst)},
		)
		initReq = EncodePacket(Header{
			SPIi:      c.spii,
			Exchange:  ExchangeIKESAInit,
			Flags:     FlagInitiator,
			MessageID: 0,
		}, initPayloads)

		initRespRaw, err := c.exchange(ctx, 0, initReq)
		if err != nil {
			return fmt.Errorf("IKE_SA_INIT_FAILED: %w", err)
		}
		respHdr, respPayloads, err = DecodePacket(initRespRaw)
		if err != nil {
			return fmt.Errorf("IKE_SA_INIT_DECODE: %w", err)
		}
		if FindPayload(respPayloads, PayloadKE) != nil && FindPayload(respPayloads, PayloadNonce) != nil {
			break
		}
		retried := false
		var notifies []uint16
		for _, p := range respPayloads {
			if p.Type == PayloadNotify {
				if nt, ndata, ok := ParseNotify(p.Body); ok {
					notifies = append(notifies, nt)
					if nt == 16390 && len(ndata) > 0 {
						cookie = append([]byte(nil), ndata...)
						retried = true
					} else if nt == 17 && len(ndata) >= 2 {
						dhGroup = binary.BigEndian.Uint16(ndata[0:2])
						retried = true
					}
				}
			}
		}
		if retried {
			if c.cfg.Logger != nil {
				c.cfg.Logger.Info("ikev2", fmt.Sprintf("IKE_SA_INIT retrying dh=%d cookieLen=%d notifies=%v", dhGroup, len(cookie), notifies), nil)
			}
			continue
		}
		return fmt.Errorf("IKE_SA_INIT_REJECTED_NOTIFIES_%v", notifies)
	}

	c.spir = respHdr.SPIr
	saRespP := FindPayload(respPayloads, PayloadSA)
	keP := FindPayload(respPayloads, PayloadKE)
	nrP := FindPayload(respPayloads, PayloadNonce)
	if keP == nil || len(keP.Body) < 4 || nrP == nil || len(nrP.Body) < 16 {
		return errors.New("IKE_SA_INIT_MISSING_KE_OR_NONCE")
	}
	ikeSuite := DefaultIKESuite()
	if saRespP != nil {
		ikeSuite = ParseSelectedIKESuite(saRespP.Body)
	}
	nr = nrP.Body
	secret, err := dh.SharedSecret(keP.Body[4:])
	if err != nil {
		return fmt.Errorf("IKE_DH_FAILED: %w", err)
	}
	c.keys = DeriveIKEKeysWithSuite(ni, nr, secret, c.spii, c.spir, ikeSuite)
	if c.cfg.Logger != nil {
		c.cfg.Logger.Info("ikev2", fmt.Sprintf("IKE_SA_INIT established dh=%d encBytes=%d prf=%d auth=%d",
			dhGroup, ikeSuite.EncKeyLen, ikeSuite.PRFID, ikeSuite.AuthID), nil)
	}

	// Switch to UDP 4500 NAT-T if configured or responder sent NAT notifications while on port 500.
	if !c.nattMode && c.cfg.NATTAddr != "" {
		if nattAddr, err := net.ResolveUDPAddr("udp4", c.cfg.NATTAddr); err == nil {
			if nattConn, err := net.DialUDP("udp4", nil, nattAddr); err == nil {
				c.mu.Lock()
				old := c.conn
				c.conn = nattConn
				c.nattMode = true
				c.mu.Unlock()
				_ = old.Close()
				c.wg.Add(1)
				go c.readLoop(nattConn)
			}
		}
	}

	// 2. IKE_AUTH #1 (no AUTH -> triggers EAP-AKA / EAP-AKA' per RFC 7296 §2.16)
	var childSPIBuf [4]byte
	_, _ = rand.Read(childSPIBuf[:])
	localChildSPI := binary.BigEndian.Uint32(childSPIBuf[:])
	if localChildSPI == 0 {
		localChildSPI = 0x10203040
	}
	idiBody := BuildIDPayload(3, c.cfg.EAPIdentity) // ID_RFC822_ADDR = 3
	idrBody := BuildIDPayload(2, c.cfg.APN)         // ID_FQDN = 2

	auth1Payloads := []Payload{
		{Type: PayloadIDi, Body: idiBody},
		{Type: PayloadIDr, Body: idrBody},
		{Type: PayloadCP, Body: BuildCFGRequest()},
		{Type: PayloadSA, Body: BuildMultiChildESPProposal(localChildSPI)},
		{Type: PayloadTSi, Body: BuildTSAllIPv4()},
		{Type: PayloadTSr, Body: BuildTSAllIPv4()},
	}
	c.msgID = 1
	auth1Req, err := EncodeEncryptedPacket(Header{
		SPIi:      c.spii,
		SPIr:      c.spir,
		Exchange:  ExchangeIKEAuth,
		Flags:     FlagInitiator,
		MessageID: c.msgID,
	}, auth1Payloads, c.keys, true)
	if err != nil {
		return err
	}
	respRaw, err := c.exchange(ctx, c.msgID, auth1Req)
	if err != nil {
		return fmt.Errorf("IKE_AUTH_1_FAILED: %w", err)
	}
	_, respInner, err := DecodeEncryptedPacket(respRaw, c.keys, false)
	if err != nil {
		return fmt.Errorf("IKE_AUTH_1_DECRYPT: %w", err)
	}

	// 3. EAP-AKA / EAP-AKA' loop (supports AT_AUTS resync retries)
	var msk []byte
	for round := 0; round < 6; round++ {
		eapP := FindPayload(respInner, PayloadEAP)
		if eapP == nil {
			var notifies []uint16
			for _, p := range respInner {
				if p.Type == PayloadNotify {
					if nt, _, ok := ParseNotify(p.Body); ok {
						notifies = append(notifies, nt)
					}
				}
			}
			return fmt.Errorf("IKE_AUTH_MISSING_EAP_NOTIFIES_%v", notifies)
		}
		if len(eapP.Body) < 4 {
			return errors.New("IKE_EAP_SHORT")
		}
		code := eapP.Body[0]
		eapID := eapP.Body[1]
		if code == EAPCodeSuccess {
			break
		}
		if code == EAPCodeFailure {
			return errors.New("IKE_EAP_FAILURE")
		}
		if code != EAPCodeRequest || len(eapP.Body) < 5 {
			return fmt.Errorf("IKE_EAP_UNEXPECTED_CODE_%d", code)
		}
		eapType := eapP.Body[4]
		var eapReply []byte
		if eapType == 1 {
			// Standard RFC 3748 EAP-Request/Identity
			idBytes := []byte(c.cfg.EAPIdentity)
			eapReply = make([]byte, 5+len(idBytes))
			eapReply[0] = EAPCodeResponse
			eapReply[1] = eapID
			binary.BigEndian.PutUint16(eapReply[2:4], uint16(len(eapReply)))
			eapReply[4] = 1
			copy(eapReply[5:], idBytes)
			if c.cfg.Logger != nil {
				c.cfg.Logger.Info("ikev2", "answered EAP-Identity request", nil)
			}
		} else {
			if len(eapP.Body) < 8 {
				return fmt.Errorf("IKE_EAP_SHORT_TYPE_%d", eapType)
			}
			subtype := eapP.Body[5]
			if eapType != EAPTypeAKA && eapType != EAPTypeAKAPrime {
				return fmt.Errorf("IKE_EAP_UNSUPPORTED_TYPE_%d_%d", eapType, subtype)
			}
			switch subtype {
			case 5: // RFC 4187 AKA-Identity
				idBytes := []byte(c.cfg.EAPIdentity)
				pad := (4 - ((4 + len(idBytes)) % 4)) % 4
				attrLenBytes := 4 + len(idBytes) + pad
				var buf bytes.Buffer
				buf.Write([]byte{EAPCodeResponse, eapID, 0, 0, eapType, 5, 0, 0})
				buf.WriteByte(14) // AT_IDENTITY
				buf.WriteByte(byte(attrLenBytes / 4))
				var idLen [2]byte
				binary.BigEndian.PutUint16(idLen[:], uint16(len(idBytes)))
				buf.Write(idLen[:])
				buf.Write(idBytes)
				if pad > 0 {
					buf.Write(make([]byte, pad))
				}
				eapReply = buf.Bytes()
				binary.BigEndian.PutUint16(eapReply[2:4], uint16(len(eapReply)))
				if c.cfg.Logger != nil {
					c.cfg.Logger.Info("ikev2", fmt.Sprintf("answered EAP-AKA Identity (type=%d)", eapType), nil)
				}
			case AKAChallenge:
				var derivedMSK []byte
				eapReply, derivedMSK, err = c.handleEAPAKAChallenge(ctx, eapID, eapType, eapP.Body)
				if err != nil {
					return err
				}
				if len(derivedMSK) > 0 {
					msk = derivedMSK
				}
				if c.cfg.Logger != nil {
					c.cfg.Logger.Info("ikev2", fmt.Sprintf("answered EAP-AKA Challenge (type=%d mskLen=%d)", eapType, len(msk)), nil)
				}
			case 12: // AKA-Notification
				notifCode := uint16(0)
				off := 8
				for off+4 <= len(eapP.Body) {
					alen := int(eapP.Body[off+1]) * 4
					if alen < 4 || off+alen > len(eapP.Body) {
						break
					}
					if eapP.Body[off] == 12 { // AT_NOTIFICATION
						notifCode = binary.BigEndian.Uint16(eapP.Body[off+2 : off+4])
					}
					off += alen
				}
				return fmt.Errorf("IKE_EAP_AKA_NOTIFICATION_%d", notifCode)
			default:
				return fmt.Errorf("IKE_EAP_UNSUPPORTED_TYPE_%d_%d", eapType, subtype)
			}
		}

		c.msgID++
		nextReq, err := EncodeEncryptedPacket(Header{
			SPIi:      c.spii,
			SPIr:      c.spir,
			Exchange:  ExchangeIKEAuth,
			Flags:     FlagInitiator,
			MessageID: c.msgID,
		}, []Payload{{Type: PayloadEAP, Body: eapReply}}, c.keys, true)
		if err != nil {
			return err
		}
		respRaw, err = c.exchange(ctx, c.msgID, nextReq)
		if err != nil {
			return fmt.Errorf("IKE_AUTH_EAP_FAILED: %w", err)
		}
		_, respInner, err = DecodeEncryptedPacket(respRaw, c.keys, false)
		if err != nil {
			return fmt.Errorf("IKE_AUTH_EAP_DECRYPT: %w", err)
		}
	}
	if len(msk) == 0 {
		return errors.New("IKE_EAP_NO_MSK")
	}

	// 4. Final IKE_AUTH with AUTH signed by full 64-byte MSK (RFC 7296 §2.16)
	authData := ComputeAuthPayloadWithPRF(c.keys.Suite.PRFID, msk, initReq, nr, idiBody, c.keys.SKpi)
	authBody := append([]byte{2, 0, 0, 0}, authData...) // Auth Method 2 = Shared Key MIC
	c.msgID++
	finalReq, err := EncodeEncryptedPacket(Header{
		SPIi:      c.spii,
		SPIr:      c.spir,
		Exchange:  ExchangeIKEAuth,
		Flags:     FlagInitiator,
		MessageID: c.msgID,
	}, []Payload{{Type: PayloadAUTH, Body: authBody}}, c.keys, true)
	if err != nil {
		return err
	}
	finalRespRaw, err := c.exchange(ctx, c.msgID, finalReq)
	if err != nil {
		return fmt.Errorf("IKE_AUTH_FINAL_FAILED: %w", err)
	}
	_, finalInner, err := DecodeEncryptedPacket(finalRespRaw, c.keys, false)
	if err != nil {
		return fmt.Errorf("IKE_AUTH_FINAL_DECRYPT: %w", err)
	}

	cpP := FindPayload(finalInner, PayloadCP)
	saP := FindPayload(finalInner, PayloadSA)
	if cpP == nil || saP == nil {
		var notifies []uint16
		for _, p := range finalInner {
			if p.Type == PayloadNotify {
				if nt, _, ok := ParseNotify(p.Body); ok {
					notifies = append(notifies, nt)
				}
			}
		}
		return fmt.Errorf("IKE_AUTH_FINAL_MISSING_CP_OR_SA_NOTIFIES_%v", notifies)
	}
	cfgRes, err := ParseCFGReply(cpP.Body)
	if err != nil || cfgRes.LocalIP == nil {
		return errors.New("IKE_AUTH_NO_LOCAL_IP")
	}
	remoteChildSPI, childEncLen, childAuthID, ok := ParseSelectedChildSA(saP.Body)
	if !ok {
		return errors.New("IKE_AUTH_NO_CHILD_SPI")
	}
	childAuthLen := 32
	childSuite := ipsec.SuiteAESCBC128HMACSHA256
	if childAuthID == AuthAlgHMACSHA196 {
		childAuthLen = 20
		if childEncLen == 32 {
			childSuite = ipsec.SuiteAESCBC256HMACSHA1
		} else {
			childSuite = ipsec.SuiteAESCBC128HMACSHA1
		}
	} else if childEncLen == 32 {
		childSuite = ipsec.SuiteAESCBC256HMACSHA256
	}

	// Derive Child SA KEYMAT: Enc_i || Auth_i || Enc_r || Auth_r using negotiated IKE PRF
	km := c.keys.DeriveChildKeymat(ni, nr, 2*(childEncLen+childAuthLen))
	off := 0
	encI := km[off : off+childEncLen]; off += childEncLen
	authI := km[off : off+childAuthLen]; off += childAuthLen
	encR := km[off : off+childEncLen]; off += childEncLen
	authR := km[off : off+childAuthLen]
	outSA, err := ipsec.NewSA(remoteChildSPI, childSuite, encI, authI, nil)
	if err != nil {
		return err
	}
	inSA, err := ipsec.NewSA(localChildSPI, childSuite, encR, authR, nil)
	if err != nil {
		return err
	}
	tun, err := ipsec.NewTunnel(cfgRes.LocalIP, outSA, inSA, c)
	if err != nil {
		return err
	}
	c.mu.Lock()
	c.cfgResult = cfgRes
	c.tunnel = tun
	c.mu.Unlock()
	if c.cfg.Logger != nil {
		c.cfg.Logger.Info("ikev2", "IKE_AUTH established ESP tunnel", map[string]any{
			"pcscfCount": len(cfgRes.PCSCF),
		})
	}
	return nil
}

func (c *Client) handleEAPAKAChallenge(ctx context.Context, eapID, eapType byte, pkt []byte) ([]byte, []byte, error) {
	// Parse EAP-AKA attributes starting at byte 8 (code[1] id[1] len[2] type[1] subtype[1] reserved[2])
	var rand16, autn16, recvMAC []byte
	var kdfInput string
	macOffset := -1
	off := 8
	for off+2 <= len(pkt) {
		atype := pkt[off]
		alen := int(pkt[off+1]) * 4
		if alen < 4 || off+alen > len(pkt) {
			break
		}
		val := pkt[off+2 : off+alen]
		switch atype {
		case ATRand:
			if len(val) >= 18 {
				rand16 = append([]byte(nil), val[2:18]...)
			}
		case ATAutn:
			if len(val) >= 18 {
				autn16 = append([]byte(nil), val[2:18]...)
			}
		case ATMac:
			if len(val) >= 18 {
				recvMAC = append([]byte(nil), val[2:18]...)
				macOffset = off + 4
			}
		case ATKdfInput:
			if len(val) >= 2 {
				nlen := int(binary.BigEndian.Uint16(val[0:2]))
				if 2+nlen <= len(val) {
					kdfInput = string(val[2 : 2+nlen])
				}
			}
		}
		off += alen
	}
	if len(rand16) != 16 || len(autn16) != 16 {
		return nil, nil, errors.New("EAP_AKA_MISSING_RAND_AUTN")
	}

	authResp, err := c.cfg.SimAuth.Authenticate(ctx, simauth.AuthRequest{
		Slot:    c.cfg.Slot,
		ICCID:   c.cfg.ICCID,
		AppType: simauth.AppUSIM,
		RAND:    rand16,
		AUTN:    autn16,
	})
	if err != nil {
		return nil, nil, fmt.Errorf("SIM_AKA_AUTH_ERROR: %w", err)
	}

	// Synchronization Failure -> send EAP-Response/AKA-Synchronization-Failure with AT_AUTS
	if authResp.Status == simauth.StatusSyncFailure {
		var buf bytes.Buffer
		buf.Write([]byte{EAPCodeResponse, eapID, 0, 0, eapType, AKASyncFailure, 0, 0})
		buf.Write([]byte{ATAuts, 4})
		buf.Write(authResp.AUTS[:14])
		out := buf.Bytes()
		binary.BigEndian.PutUint16(out[2:4], uint16(len(out)))
		return out, nil, nil
	}

	prime := eapType == EAPTypeAKAPrime
	var akaKeys *EAPAKAKeys
	if prime {
		if kdfInput == "" {
			kdfInput = "WLAN"
		}
		akaKeys = DeriveEAPAKAPrimeKeys(c.cfg.EAPIdentity, kdfInput, authResp.IK, authResp.CK, autn16)
	} else {
		akaKeys = DeriveEAPAKAKeys(c.cfg.EAPIdentity, authResp.IK, authResp.CK)
	}

	// Verify server AT_MAC if present
	if macOffset >= 0 && len(recvMAC) == 16 {
		zeroed := append([]byte(nil), pkt...)
		for i := 0; i < 16; i++ {
			zeroed[macOffset+i] = 0
		}
		expected := ComputeEAPATMAC(akaKeys.Kaut, zeroed, prime)
		if subtle.ConstantTimeCompare(recvMAC, expected) != 1 {
			return nil, nil, errors.New("EAP_AKA_SERVER_MAC_MISMATCH")
		}
	}

	// Build EAP-Response/AKA-Challenge with AT_RES + AT_MAC
	resPad := (4 - ((4 + len(authResp.RES)) % 4)) % 4
	resAttrLen := 4 + len(authResp.RES) + resPad
	var buf bytes.Buffer
	buf.Write([]byte{EAPCodeResponse, eapID, 0, 0, eapType, AKAChallenge, 0, 0})
	buf.WriteByte(ATRes)
	buf.WriteByte(byte(resAttrLen / 4))
	var resBits [2]byte
	binary.BigEndian.PutUint16(resBits[:], uint16(len(authResp.RES)*8))
	buf.Write(resBits[:])
	buf.Write(authResp.RES)
	for i := 0; i < resPad; i++ {
		buf.WriteByte(0)
	}
	replyMacPos := buf.Len() + 4
	buf.Write([]byte{ATMac, 5, 0, 0})
	buf.Write(make([]byte, 16))
	out := buf.Bytes()
	binary.BigEndian.PutUint16(out[2:4], uint16(len(out)))
	mac := ComputeEAPATMAC(akaKeys.Kaut, out, prime)
	copy(out[replyMacPos:replyMacPos+16], mac)
	return out, akaKeys.MSK, nil
}

func (c *Client) dpdLoop() {
	defer c.wg.Done()
	// Lightweight 15s tick handles RFC 3948 NAT-T keepalives and RFC 7296 §2.4 idle-only DPD.
	tickInterval := 15 * time.Second
	if c.cfg.DPDInterval < tickInterval {
		tickInterval = c.cfg.DPDInterval
	}
	ticker := time.NewTicker(tickInterval)
	defer ticker.Stop()
	for {
		select {
		case <-c.stopCh:
			return
		case <-c.deadCh:
			return
		case <-ticker.C:
			now := time.Now()
			// Keep NAT/proxy UDP binding warm without consuming an IKE MessageID.
			if now.Sub(time.Unix(0, c.lastSend.Load())) >= 12*time.Second {
				c.sendNATKeepalive()
			}
			// RFC 7296 §2.4: If any ESP or IKE packet arrived recently (e.g. active voice RTP),
			// the tunnel is proven alive — never fire DPD or disrupt an active call.
			if now.Sub(time.Unix(0, c.lastRecv.Load())) < c.cfg.DPDInterval {
				continue
			}

			c.mu.Lock()
			c.msgID++
			id := c.msgID
			keys := c.keys
			spii, spir := c.spii, c.spir
			c.mu.Unlock()
			if keys == nil {
				continue
			}
			dpd, err := EncodeEncryptedPacket(Header{
				SPIi:      spii,
				SPIr:      spir,
				Exchange:  ExchangeInformational,
				Flags:     FlagInitiator,
				MessageID: id,
			}, nil, keys, true)
			if err != nil {
				continue
			}

			// RFC 7296 §2.3 (window size = 1): retransmit the EXACT SAME msgID packet
			// with exponential backoff; NEVER increment msgID on retry.
			if !c.runDPDExchange(id, dpd) {
				select {
				case <-c.stopCh:
					return
				default:
				}
				c.markDead("DPD_TIMEOUT")
				return
			}
		}
	}
}

func (c *Client) runDPDExchange(msgID uint32, pkt []byte) bool {
	ch := make(chan []byte, 1)
	c.mu.Lock()
	c.pending[msgID] = ch
	c.mu.Unlock()
	defer func() {
		c.mu.Lock()
		delete(c.pending, msgID)
		c.mu.Unlock()
	}()

	startRecv := c.lastRecv.Load()
	waits := []time.Duration{2 * time.Second, 3 * time.Second, 4 * time.Second, 6 * time.Second}
	for _, w := range waits {
		if err := c.sendIKE(pkt); err != nil {
			return false
		}
		timer := time.NewTimer(w)
		select {
		case <-ch:
			timer.Stop()
			return true
		case <-c.stopCh:
			timer.Stop()
			return true
		case <-c.deadCh:
			timer.Stop()
			return false
		case <-timer.C:
			// If ESP/IKE traffic resumed while probing, peer is alive; keep waiting cleanly on next tick
			// ONLY if the DPD response also arrives, otherwise keep retransmitting same msgID to keep window synced.
			if c.lastRecv.Load() > startRecv {
				// Peer is still sending ESP; try one more retransmit of same msgID or continue loop.
			}
		}
	}
	return c.lastRecv.Load() > startRecv
}

func (c *Client) Close() error {
	c.mu.Lock()
	if c.closed {
		c.mu.Unlock()
		return nil
	}
	c.closed = true
	keys := c.keys
	spii, spir := c.spii, c.spir
	c.msgID++
	delID := c.msgID
	close(c.stopCh)
	c.mu.Unlock()

	if keys != nil {
		// Best-effort IKE_SA DELETE notification
		delBody := []byte{1, 0, 0, 0} // Protocol=IKE(1), SPISize=0, NumSPIs=0
		if pkt, err := EncodeEncryptedPacket(Header{
			SPIi:      spii,
			SPIr:      spir,
			Exchange:  ExchangeInformational,
			Flags:     FlagInitiator,
			MessageID: delID,
		}, []Payload{{Type: PayloadDelete, Body: delBody}}, keys, true); err == nil {
			_ = c.sendIKE(pkt)
		}
	}
	err := c.conn.Close()
	c.wg.Wait()
	return err
}

func natHash(spii, spir uint64, addr *net.UDPAddr) []byte {
	h := sha1.New()
	var b [18]byte
	binary.BigEndian.PutUint64(b[0:8], spii)
	binary.BigEndian.PutUint64(b[8:16], spir)
	binary.BigEndian.PutUint16(b[16:18], uint16(addr.Port))
	h.Write(b[0:16])
	h.Write(addr.IP.To4())
	h.Write(b[16:18])
	return h.Sum(nil)
}

func buildNotify(ntype uint16, data []byte) []byte {
	out := make([]byte, 4+len(data))
	binary.BigEndian.PutUint16(out[2:4], ntype)
	copy(out[4:], data)
	return out
}

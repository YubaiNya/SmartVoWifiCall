package sip

import (
	"bytes"
	"context"
	"encoding/base64"
	"fmt"
	"net"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/YubaiNya/SmartVoWifiCall/engine/internal/ipsec"
	"github.com/YubaiNya/SmartVoWifiCall/engine/internal/simauth"
)

type espPipe struct {
	peer **ipsec.Tunnel
}

func (p *espPipe) SendESP(esp []byte) error {
	cp := append([]byte(nil), esp...)
	go func() {
		_ = (*p.peer).HandleESP(cp)
	}()
	return nil
}

func makeTestTunnelPair(t *testing.T) (*ipsec.Tunnel, *ipsec.Tunnel) {
	t.Helper()
	k1 := bytes.Repeat([]byte{0x11}, 16)
	k2 := bytes.Repeat([]byte{0x22}, 16)
	s1 := []byte{1, 2, 3, 4}
	s2 := []byte{5, 6, 7, 8}
	outA, _ := ipsec.NewSA(0x100, ipsec.SuiteAESGCM128, k1, nil, s1)
	inA, _ := ipsec.NewSA(0x200, ipsec.SuiteAESGCM128, k2, nil, s2)
	outB, _ := ipsec.NewSA(0x200, ipsec.SuiteAESGCM128, k2, nil, s2)
	inB, _ := ipsec.NewSA(0x100, ipsec.SuiteAESGCM128, k1, nil, s1)
	var a, b *ipsec.Tunnel
	a, _ = ipsec.NewTunnel(net.IPv4(10, 99, 0, 2), outA, inA, &espPipe{peer: &b})
	b, _ = ipsec.NewTunnel(net.IPv4(10, 99, 0, 1), outB, inB, &espPipe{peer: &a})
	return a, b
}

func TestSMSRPDataRoundTrip(t *testing.T) {
	// Test outbound Chinese UCS-2 SMS-SUBMIT
	rp, err := EncodeRPDataSubmit(7, "", "+447700900123", "你好 SmartVoWifiCall")
	if err != nil {
		t.Fatal(err)
	}
	if rp[0] != RPDataMS2N || rp[1] != 7 {
		t.Fatalf("unexpected RP header: %x", rp[:4])
	}

	// Build a valid inbound RP-DATA(SMS-DELIVER) with GSM 7-bit text "Hello!"
	text := "Hello!"
	var tpdu bytes.Buffer
	tpdu.WriteByte(0x04) // SMS-DELIVER
	tpdu.WriteByte(12)   // 12 digits
	tpdu.WriteByte(0x91) // International
	tpdu.Write(packSemiOctets("447700900123"))
	tpdu.WriteByte(0x00) // PID
	tpdu.WriteByte(0x00) // GSM 7-bit
	tpdu.Write([]byte{0x62, 0x11, 0x42, 0x21, 0x00, 0x00, 0x00}) // SCTS
	tpdu.WriteByte(byte(len(text)))
	tpdu.Write(packGSM7([]byte(text)))

	var deliverRP bytes.Buffer
	deliverRP.WriteByte(RPDataN2MS)
	deliverRP.WriteByte(9)
	deliverRP.WriteByte(0x00) // empty OA
	deliverRP.WriteByte(0x00) // empty DA
	deliverRP.WriteByte(byte(tpdu.Len()))
	deliverRP.Write(tpdu.Bytes())

	got, err := DecodeRPDataDeliver(deliverRP.Bytes())
	if err != nil {
		t.Fatalf("DecodeRPDataDeliver failed: %v", err)
	}
	if got.Sender != "+447700900123" || got.Text != "Hello!" || got.RPMessageRef != 9 {
		t.Fatalf("unexpected decoded SMS: %+v", got)
	}
}

func TestFullIMSSIPRegisterCallAndSMSOverESP(t *testing.T) {
	cliTun, srvTun := makeTestTunnelPair(t)
	pcscfSock, err := srvTun.BindUDP(5060)
	if err != nil {
		t.Fatal(err)
	}
	defer pcscfSock.Close()

	sim := &simauth.MockProvider{ForceSyncFailOnce: true, KeyMaterial: []byte("ims-key")}
	srvSim := &simauth.MockProvider{KeyMaterial: []byte("ims-key")}

	var evMu sync.Mutex
	var events []string
	var lastSMS map[string]any
	var incomingID string

	ua, err := NewClient(ClientConfig{
		Tunnel:   cliTun,
		PCSCF:    net.IPv4(10, 99, 0, 1),
		IMSRealm: "ims.mnc003.mcc454.3gppnetwork.org",
		IMPI:     "454030000000001@ims.mnc003.mcc454.3gppnetwork.org",
		IMPU:     "sip:454030000000001@ims.mnc003.mcc454.3gppnetwork.org",
		Slot:     1,
		ICCID:    "8985203000000000001",
		SimAuth:  sim,
		OnEvent: func(name string, data map[string]any) {
			evMu.Lock()
			events = append(events, name)
			if name == "smsReceived" {
				lastSMS = data
			}
			if name == "incomingCall" {
				incomingID, _ = data["callId"].(string)
			}
			evMu.Unlock()
		},
	})
	if err != nil {
		t.Fatal(err)
	}
	defer ua.Close()

	// Mock IMS P-CSCF server over ESP tunnel
	go func() {
		regRound := 0
		for {
			pkt, err := pcscfSock.ReadFrom(time.Time{})
			if err != nil {
				return
			}
			req, err := ParseMessage(pkt.Payload)
			if err != nil || req.IsResponse {
				continue
			}
			reply := func(code int, reason string, extra []HeaderKV, body []byte, ct string) {
				r := &Message{IsResponse: true, StatusCode: code, Reason: reason, Body: body}
				for _, v := range req.GetAllHeaders("Via") {
					r.AddHeader("Via", v)
				}
				r.AddHeader("From", req.GetHeader("From"))
				to := req.GetHeader("To")
				if !strings.Contains(to, "tag=") {
					to += ";tag=srvtag"
				}
				r.AddHeader("To", to)
				r.AddHeader("Call-ID", req.GetHeader("Call-ID"))
				r.AddHeader("CSeq", req.GetHeader("CSeq"))
				for _, kv := range extra {
					r.AddHeader(kv.Name, kv.Value)
				}
				if ct != "" {
					r.AddHeader("Content-Type", ct)
				}
				_ = pcscfSock.WriteTo(pkt.SrcIP, pkt.SrcPort, r.Encode())
			}

			switch req.Method {
			case "REGISTER":
				auth := ParseDigestParams(req.GetHeader("Authorization"))
				if auth["response"] == "" {
					regRound++
					r16 := bytes.Repeat([]byte{byte(0x30 + regRound)}, 16)
					a16 := bytes.Repeat([]byte{byte(0x40 + regRound)}, 16)
					nonce := base64.StdEncoding.EncodeToString(append(r16, a16...))
					www := fmt.Sprintf("Digest realm=\"ims.mnc003.mcc454.3gppnetwork.org\", nonce=\"%s\", algorithm=AKAv1-MD5, qop=\"auth\"", nonce)
					reply(401, "Unauthorized", []HeaderKV{{Name: "WWW-Authenticate", Value: www}}, nil, "")
				} else {
					// Verify AKAv1-MD5 digest matches server-side SIM auth
					r16, a16, _ := DecodeAKANonce(auth["nonce"])
					exp, _ := srvSim.Authenticate(context.Background(), simauth.AuthRequest{RAND: r16, AUTN: a16})
					want := ComputeAKADigest(auth["username"], auth["realm"], "REGISTER", auth["uri"], auth["nonce"], auth["nc"], auth["cnonce"], auth["qop"], auth["algorithm"], exp.RES, exp.IK, exp.CK)
					if want != auth["response"] {
						reply(403, "Forbidden", nil, nil, "")
						continue
					}
					reply(200, "OK", []HeaderKV{{Name: "P-Associated-URI", Value: "<sip:454030000000001@ims.mnc003.mcc454.3gppnetwork.org>"}}, nil, "")
				}
			case "INVITE":
				reply(180, "Ringing", nil, nil, "")
				ansSDP := BuildSDP(MediaOffer{LocalIP: net.IPv4(10, 99, 0, 1), LocalPort: 45000, SessionID: 1})
				reply(200, "OK", nil, ansSDP, "application/sdp")
			case "BYE", "MESSAGE":
				reply(200, "OK", nil, nil, "")
			}
		}
	}()

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	// 1. Register with 401 + AUTS resync + AKAv1-MD5 verification
	if err := ua.Register(ctx); err != nil {
		t.Fatalf("Register failed: %v", err)
	}
	if !ua.Registered() {
		t.Fatal("expected Registered()==true")
	}

	// 2. Outgoing Dial -> IN_CALL -> Hangup
	dialRes, err := ua.Dial(ctx, "tel:+85291234567")
	if err != nil {
		t.Fatalf("Dial failed: %v", err)
	}
	if dialRes["state"] != "IN_CALL" {
		t.Fatalf("expected IN_CALL, got %+v", dialRes)
	}
	callID := dialRes["callId"].(string)
	if _, err := ua.Hangup(ctx, callID); err != nil {
		t.Fatalf("Hangup failed: %v", err)
	}

	// 3. Outbound SMS
	smsRes, err := ua.SendSMS(ctx, "+85291234567", "测试短信")
	if err != nil {
		t.Fatalf("SendSMS failed: %v", err)
	}
	if smsRes["statusCode"].(int) != 200 {
		t.Fatalf("unexpected SMS status: %+v", smsRes)
	}

	// 4. Inbound SIP MESSAGE (SMS-DELIVER) -> smsReceived event
	var tpdu bytes.Buffer
	tpdu.Write([]byte{0x04, 11, 0x91})
	tpdu.Write(packSemiOctets("85291234567"))
	tpdu.Write([]byte{0x00, 0x00, 0x62, 0x11, 0x42, 0x21, 0x00, 0x00, 0x00, 2})
	tpdu.Write(packGSM7([]byte("OK")))
	var rp bytes.Buffer
	rp.Write([]byte{RPDataN2MS, 3, 0, 0, byte(tpdu.Len())})
	rp.Write(tpdu.Bytes())

	inMsg := &Message{Method: "MESSAGE", RequestURI: "sip:454030000000001@10.99.0.2:5060", Body: rp.Bytes()}
	inMsg.AddHeader("Via", "SIP/2.0/UDP 10.99.0.1:5060;branch=z9hG4bKin1")
	inMsg.AddHeader("From", "<sip:smsc@ims.mnc003.mcc454.3gppnetwork.org>;tag=s1")
	inMsg.AddHeader("To", "<sip:454030000000001@ims.mnc003.mcc454.3gppnetwork.org>")
	inMsg.AddHeader("Call-ID", "in-sms-1@10.99.0.1")
	inMsg.AddHeader("CSeq", "1 MESSAGE")
	inMsg.AddHeader("Content-Type", "application/vnd.3gpp.sms")
	_ = pcscfSock.WriteTo(net.IPv4(10, 99, 0, 2), 5060, inMsg.Encode())

	// 5. Inbound INVITE -> incomingCall -> Answer
	inInv := &Message{Method: "INVITE", RequestURI: "sip:454030000000001@10.99.0.2:5060",
		Body: BuildSDP(MediaOffer{LocalIP: net.IPv4(10, 99, 0, 1), LocalPort: 46000, SessionID: 2})}
	inInv.AddHeader("Via", "SIP/2.0/UDP 10.99.0.1:5060;branch=z9hG4bKin2")
	inInv.AddHeader("From", "<tel:+447700900123>;tag=caller1")
	inInv.AddHeader("To", "<sip:454030000000001@ims.mnc003.mcc454.3gppnetwork.org>")
	inInv.AddHeader("Call-ID", "in-call-1@10.99.0.1")
	inInv.AddHeader("CSeq", "1 INVITE")
	inInv.AddHeader("Content-Type", "application/sdp")
	_ = pcscfSock.WriteTo(net.IPv4(10, 99, 0, 2), 5060, inInv.Encode())

	time.Sleep(150 * time.Millisecond)
	evMu.Lock()
	gotSMS := lastSMS
	inID := incomingID
	evMu.Unlock()
	if gotSMS == nil || gotSMS["text"] != "OK" || gotSMS["number"] != "+85291234567" {
		t.Fatalf("expected smsReceived event, got %+v", gotSMS)
	}
	if inID == "" {
		t.Fatal("expected incomingCall event")
	}
	ansRes, err := ua.Answer(ctx, inID)
	if err != nil || ansRes["state"] != "IN_CALL" {
		t.Fatalf("Answer failed: %v %+v", err, ansRes)
	}
}

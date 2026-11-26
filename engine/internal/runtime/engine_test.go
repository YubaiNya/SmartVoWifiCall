package runtime

import (
	"bufio"
	"bytes"
	"context"
	"crypto/rand"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/YubaiNya/SmartVoWifiCall/engine/internal/config"
	"github.com/YubaiNya/SmartVoWifiCall/engine/internal/ikev2"
	"github.com/YubaiNya/SmartVoWifiCall/engine/internal/ipsec"
	"github.com/YubaiNya/SmartVoWifiCall/engine/internal/media"
	"github.com/YubaiNya/SmartVoWifiCall/engine/internal/rpc"
	"github.com/YubaiNya/SmartVoWifiCall/engine/internal/simauth"
	"github.com/YubaiNya/SmartVoWifiCall/engine/internal/sip"
)

type udpESPSender struct {
	pc  *net.UDPConn
	dst *net.UDPAddr
}

func (u *udpESPSender) SendESP(esp []byte) error {
	_, err := u.pc.WriteToUDP(esp, u.dst)
	return err
}

func TestEndToEndNativeEngineStack(t *testing.T) {
	// 1. Spin up combined Mock ePDG (IKEv2+ESP) + Mock IMS P-CSCF (SIP/SMS) + Mock RTP echo peer
	pc, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 0})
	if err != nil {
		t.Fatal(err)
	}
	defer pc.Close()

	cfg := &config.Config{
		Enabled:   true,
		Transport: "iface:wfctun0",
		Slot:      1,
		ICCID:     "8985203000000000001",
		MCC:       "454",
		MNC:       "03",
		MNCLen:    2,
		IMSRealm:  "ims.mnc003.mcc454.3gppnetwork.org",
		DNS:       "1.1.1.1",
		IMSI:      "454030000000001",
		IMEI:      "351234567890123",
		EPDGAddr:  pc.LocalAddr().String(),
	}
	clientSim := &simauth.MockProvider{KeyMaterial: []byte("e2e-usim-key")}
	serverSim := &simauth.MockProvider{KeyMaterial: []byte("e2e-usim-key")}

	var registrations atomic.Int32
	go runMockNetwork(t, pc, cfg, serverSim, &registrations)

	dir, err := os.MkdirTemp("", "svw*")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = os.RemoveAll(dir) })
	eng, err := Start(Options{
		Config:    cfg,
		SimMode:   "mock",
		SocketDir: dir,
		LogDir:    dir,
		SimAuth:   clientSim,
	})
	if err != nil {
		t.Fatalf("Engine Start failed: %v", err)
	}
	defer eng.Stop()

	ctrlSock := filepath.Join(dir, cfg.ICCID+".sock")
	audioSock := filepath.Join(dir, cfg.ICCID+".audio.sock")

	conn, err := net.Dial("unix", ctrlSock)
	if err != nil {
		t.Fatalf("dial control socket: %v", err)
	}
	defer conn.Close()
	r := bufio.NewReader(conn)

	callRPC := func(id int, method string, params map[string]any) map[string]any {
		t.Helper()
		b, _ := json.Marshal(map[string]any{"id": id, "method": method, "params": params})
		_, err := conn.Write(append(b, '\n'))
		if err != nil {
			t.Fatalf("write %s: %v", method, err)
		}
		for {
			_ = conn.SetReadDeadline(time.Now().Add(5 * time.Second))
			line, err := r.ReadString('\n')
			if err != nil {
				t.Fatalf("read %s: %v", method, err)
			}
			var frame map[string]any
			if err := json.Unmarshal([]byte(line), &frame); err != nil {
				t.Fatalf("unmarshal: %v", err)
			}
			if _, isEv := frame["event"]; isEv {
				continue // skip async events during synchronous RPC wait
			}
			return frame
		}
	}

	// Verify hello + wait until IMS == REGISTERED
	hello := callRPC(1, "hello", map[string]any{"version": 1})
	if hello["result"].(map[string]any)["version"].(float64) != 1 {
		t.Fatalf("unexpected hello: %+v", hello)
	}
	deadline := time.Now().Add(5 * time.Second)
	for {
		st := callRPC(2, "getStatus", map[string]any{})["result"].(map[string]any)
		if st["ims"] == "REGISTERED" && st["tunnel"] == "CONNECTED" {
			break
		}
		if time.Now().After(deadline) {
			t.Fatalf("engine did not reach REGISTERED; last status: %+v", st)
		}
		time.Sleep(50 * time.Millisecond)
	}

	// Dial -> IN_CALL
	dialRes := callRPC(3, "dial", map[string]any{"recipient": "tel:+85291234567"})["result"].(map[string]any)
	if dialRes["state"] != "IN_CALL" {
		t.Fatalf("expected IN_CALL, got %+v", dialRes)
	}
	callID := dialRes["callId"].(string)
	// A recovery request must never tear down an established call.
	busyReconnect := eng.Reconnect()
	if busyReconnect["ok"] != false || busyReconnect["reason"] != "CALL_ACTIVE" {
		t.Fatalf("reconnect accepted during a call: %+v", busyReconnect)
	}

	// Attach Audio PCM socket -> audioStart -> verify RTP echo loopback!
	ac, err := net.Dial("unix", audioSock)
	if err != nil {
		t.Fatalf("dial audio socket: %v", err)
	}
	defer ac.Close()
	_ = callRPC(4, "audioStart", map[string]any{"callId": callID, "sampleRate": 16000, "channels": 1})

	upFrame, _ := rpc.EncodePCMFrame(bytes.Repeat([]byte{0x00, 0x20}, 320))
	for i := 0; i < 3; i++ {
		_, _ = ac.Write(upFrame)
		time.Sleep(20 * time.Millisecond)
	}
	_ = ac.SetReadDeadline(time.Now().Add(3 * time.Second))
	downPCM := make([]byte, rpc.PCMPayloadBytes)
	if err := rpc.ReadPCMFrame(ac, downPCM); err != nil {
		t.Fatalf("expected echoed downlink PCM over ESP/RTP: %v", err)
	}
	// A two-second registrar lease must refresh during the call, not 25 minutes
	// later. The existing dialog and media session must survive that refresh.
	refreshDeadline := time.Now().Add(4 * time.Second)
	for registrations.Load() < 2 && time.Now().Before(refreshDeadline) {
		time.Sleep(20 * time.Millisecond)
	}
	if registrations.Load() < 2 {
		t.Fatal("short SIP lease was not refreshed")
	}
	if snapshot, _ := eng.GetCallSnapshot(); snapshot["stage"] != "IN_CALL" {
		t.Fatal("refresh interrupted the call")
	}

	// Send DTMF + Hangup + SMS
	dtmfRes := callRPC(5, "sendDtmf", map[string]any{"callId": callID, "digit": "5", "durationMs": 160})["result"].(map[string]any)
	if dtmfRes["ok"] != true {
		t.Fatalf("DTMF failed: %+v", dtmfRes)
	}
	_ = callRPC(6, "audioStop", map[string]any{"callId": callID})
	hangRes := callRPC(7, "hangup", map[string]any{"callId": callID})["result"].(map[string]any)
	if hangRes["state"] != "ENDED" {
		t.Fatalf("Hangup failed: %+v", hangRes)
	}
	smsRes := callRPC(8, "sendSms", map[string]any{"number": "+85291234567", "text": "Hi"})["result"].(map[string]any)
	if smsRes["statusCode"].(float64) != 200 {
		t.Fatalf("SendSMS failed: %+v", smsRes)
	}

	// Fast network-change reconnect -> re-reaches REGISTERED rapidly
	recRes := callRPC(9, "reconnect", map[string]any{})["result"].(map[string]any)
	if recRes["ok"] != true {
		t.Fatalf("Reconnect failed: %+v", recRes)
	}
	time.Sleep(150 * time.Millisecond)
	reDeadline := time.Now().Add(5 * time.Second)
	for {
		st := callRPC(10, "getStatus", map[string]any{})["result"].(map[string]any)
		if st["ims"] == "REGISTERED" && st["tunnel"] == "CONNECTED" {
			break
		}
		if time.Now().After(reDeadline) {
			t.Fatalf("engine did not re-register after fast reconnect; last status: %+v", st)
		}
		time.Sleep(50 * time.Millisecond)
	}
}

func runMockNetwork(t *testing.T, pc *net.UDPConn, cfg *config.Config, serverSim simauth.Provider, registrations *atomic.Int32) {
	dh, _ := ikev2.GenerateDH(ikev2.DHGroupMODP2048)
	nr := make([]byte, 32)
	_, _ = rand.Read(nr)
	var spirBuf [8]byte
	_, _ = rand.Read(spirBuf[:])
	spir := binary.BigEndian.Uint64(spirBuf[:])
	var spii uint64
	var ni, initResponse, serverMSK []byte
	var keys *ikev2.IKEKeys
	var clientChildSPI uint32
	serverChildSPI := uint32(0xCAFEBABE)
	var serverTun *ipsec.Tunnel
	var peerAddr *net.UDPAddr

	sendNAT := func(pkt []byte) {
		framed := make([]byte, 4+len(pkt))
		copy(framed[4:], pkt)
		_, _ = pc.WriteToUDP(framed, peerAddr)
	}

	startPCSCF := func(tun *ipsec.Tunnel) {
		sipSock, err := tun.BindUDP(5060)
		if err != nil {
			return
		}
		rtpSock, err := tun.BindUDP(45000)
		if err != nil {
			return
		}
		// RTP echo loop inside IPsec tunnel
		go func() {
			for {
				pkt, err := rtpSock.ReadFrom(time.Time{})
				if err != nil {
					return
				}
				if r, err := media.DecodeRTP(pkt.Payload); err == nil && r.PayloadType != 101 {
					_ = rtpSock.WriteTo(pkt.SrcIP, pkt.SrcPort, pkt.Payload)
				}
			}
		}()
		// SIP P-CSCF loop inside IPsec tunnel
		go func() {
			for {
				pkt, err := sipSock.ReadFrom(time.Time{})
				if err != nil {
					return
				}
				req, err := sip.ParseMessage(pkt.Payload)
				if err != nil || req.IsResponse {
					continue
				}
				reply := func(code int, reason string, extra []sip.HeaderKV, body []byte, ct string) {
					r := &sip.Message{IsResponse: true, StatusCode: code, Reason: reason, Body: body}
					for _, v := range req.GetAllHeaders("Via") {
						r.AddHeader("Via", v)
					}
					r.AddHeader("From", req.GetHeader("From"))
					to := req.GetHeader("To")
					if !strings.Contains(to, "tag=") {
						to += ";tag=pcscf"
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
					_ = sipSock.WriteTo(pkt.SrcIP, pkt.SrcPort, r.Encode())
				}
				switch req.Method {
				case "REGISTER":
					auth := sip.ParseDigestParams(req.GetHeader("Authorization"))
					if auth["response"] == "" {
						r16 := bytes.Repeat([]byte{0x51}, 16)
						a16 := bytes.Repeat([]byte{0x61}, 16)
						nonce := base64.StdEncoding.EncodeToString(append(r16, a16...))
						www := fmt.Sprintf("Digest realm=\"%s\", nonce=\"%s\", algorithm=AKAv1-MD5, qop=\"auth\"", cfg.IMSRealm, nonce)
						reply(401, "Unauthorized", []sip.HeaderKV{{Name: "WWW-Authenticate", Value: www}}, nil, "")
					} else {
						reply(200, "OK", []sip.HeaderKV{{Name: "Contact", Value: req.GetHeader("Contact") + ";expires=2"}}, nil, "")
						registrations.Add(1)
					}
				case "INVITE":
					reply(180, "Ringing", nil, nil, "")
					ans := sip.BuildSDP(sip.MediaOffer{LocalIP: net.IPv4(10, 88, 0, 1), LocalPort: 45000, SessionID: 1})
					reply(200, "OK", nil, ans, "application/sdp")
				case "BYE", "MESSAGE", "CANCEL":
					reply(200, "OK", nil, nil, "")
				}
			}
		}()
	}

	buf := make([]byte, 4096)
	for {
		n, addr, err := pc.ReadFromUDP(buf)
		if err != nil {
			return
		}
		peerAddr = addr
		raw := append([]byte(nil), buf[:n]...)
		if len(raw) >= 4 && binary.BigEndian.Uint32(raw[0:4]) == serverChildSPI && serverTun != nil {
			_ = serverTun.HandleESP(raw)
			continue
		}
		if len(raw) >= 4 && binary.BigEndian.Uint32(raw[0:4]) == 0 {
			raw = raw[4:]
		}
		h, payloads, err := ikev2.DecodePacket(raw)
		if err != nil {
			continue
		}
		switch h.Exchange {
		case ikev2.ExchangeIKESAInit:
			spii = h.SPIi
			ni = ikev2.FindPayload(payloads, ikev2.PayloadNonce).Body
			secret, _ := dh.SharedSecret(ikev2.FindPayload(payloads, ikev2.PayloadKE).Body[4:])
			keys = ikev2.DeriveIKEKeys(ni, nr, secret, spii, spir, false)
			resp := ikev2.EncodePacket(ikev2.Header{
				SPIi: spii, SPIr: spir, Exchange: ikev2.ExchangeIKESAInit, Flags: ikev2.FlagResponse, MessageID: h.MessageID,
			}, []ikev2.Payload{
				{Type: ikev2.PayloadSA, Body: ikev2.BuildIKESAProposal(ikev2.DHGroupMODP2048)},
				{Type: ikev2.PayloadKE, Body: ikev2.BuildKEPayload(ikev2.DHGroupMODP2048, dh.Public)},
				{Type: ikev2.PayloadNonce, Body: nr},
			})
			initResponse = append([]byte(nil), resp...)
			_, _ = pc.WriteToUDP(resp, peerAddr)
		case ikev2.ExchangeIKEAuth:
			_, inner, err := ikev2.DecodeEncryptedPacket(raw, keys, true)
			if err != nil {
				continue
			}
			if saP := ikev2.FindPayload(inner, ikev2.PayloadSA); saP != nil {
				clientChildSPI, _ = ikev2.ParseChildSASPI(saP.Body)
			}
			if ikev2.FindPayload(inner, ikev2.PayloadAUTH) != nil {
				var cp bytes.Buffer
				cp.Write([]byte{ikev2.CFGReply, 0, 0, 0})
				cp.Write([]byte{0, byte(ikev2.AttrInternalIP4Addr), 0, 4, 10, 88, 0, 2})
				cp.Write([]byte{0, byte(ikev2.AttrPCSCFIP4Addr), 0, 4, 10, 88, 0, 1})
				km := ikev2.DeriveChildKeymat(keys.SKd, ni, nr, 16+32+16+32)
				inSA, _ := ipsec.NewSA(serverChildSPI, ipsec.SuiteAESCBC128HMACSHA256, km[0:16], km[16:48], nil)
				outSA, _ := ipsec.NewSA(clientChildSPI, ipsec.SuiteAESCBC128HMACSHA256, km[48:64], km[64:96], nil)
				serverTun, _ = ipsec.NewTunnel(net.IPv4(10, 88, 0, 1), outSA, inSA, &udpESPSender{pc: pc, dst: peerAddr})
				startPCSCF(serverTun)
				resp, _ := ikev2.EncodeEncryptedPacket(ikev2.Header{
					SPIi: spii, SPIr: spir, Exchange: ikev2.ExchangeIKEAuth, Flags: ikev2.FlagResponse, MessageID: h.MessageID,
				}, []ikev2.Payload{
					{Type: ikev2.PayloadIDr, Body: ikev2.BuildIDPayload(2, "ims")},
					{Type: ikev2.PayloadAUTH, Body: append([]byte{2, 0, 0, 0}, ikev2.ComputeAuthPayloadWithPRF(keys.Suite.PRFID, serverMSK, initResponse, ni, ikev2.BuildIDPayload(2, "ims"), keys.SKpr)...)},
					{Type: ikev2.PayloadCP, Body: cp.Bytes()},
					{Type: ikev2.PayloadSA, Body: ikev2.BuildChildESPProposal(serverChildSPI, false)},
					{Type: ikev2.PayloadTSi, Body: ikev2.BuildTSAllIPv4()},
					{Type: ikev2.PayloadTSr, Body: ikev2.BuildTSAllIPv4()},
				}, keys, false)
				sendNAT(resp)
				continue
			}
			if eapResp := ikev2.FindPayload(inner, ikev2.PayloadEAP); eapResp != nil {
				resp, _ := ikev2.EncodeEncryptedPacket(ikev2.Header{
					SPIi: spii, SPIr: spir, Exchange: ikev2.ExchangeIKEAuth, Flags: ikev2.FlagResponse, MessageID: h.MessageID,
				}, []ikev2.Payload{{Type: ikev2.PayloadEAP, Body: []byte{ikev2.EAPCodeSuccess, eapResp.Body[1], 0, 4}}}, keys, false)
				sendNAT(resp)
				continue
			}
			r16 := bytes.Repeat([]byte{0x11}, 16)
			a16 := bytes.Repeat([]byte{0x22}, 16)
			exp, _ := serverSim.Authenticate(context.Background(), simauth.AuthRequest{RAND: r16, AUTN: a16})
			akaKeys := ikev2.DeriveEAPAKAKeys(cfg.EffectiveEAPIdentity(), exp.IK, exp.CK)
			serverMSK = akaKeys.MSK
			var eap bytes.Buffer
			eap.Write([]byte{ikev2.EAPCodeRequest, 1, 0, 0, ikev2.EAPTypeAKA, ikev2.AKAChallenge, 0, 0})
			eap.Write([]byte{ikev2.ATRand, 5, 0, 0})
			eap.Write(r16)
			eap.Write([]byte{ikev2.ATAutn, 5, 0, 0})
			eap.Write(a16)
			macPos := eap.Len() + 4
			eap.Write([]byte{ikev2.ATMac, 5, 0, 0})
			eap.Write(make([]byte, 16))
			eapBytes := eap.Bytes()
			binary.BigEndian.PutUint16(eapBytes[2:4], uint16(len(eapBytes)))
			copy(eapBytes[macPos:macPos+16], ikev2.ComputeEAPATMAC(akaKeys.Kaut, eapBytes, false))
			resp, _ := ikev2.EncodeEncryptedPacket(ikev2.Header{
				SPIi: spii, SPIr: spir, Exchange: ikev2.ExchangeIKEAuth, Flags: ikev2.FlagResponse, MessageID: h.MessageID,
			}, []ikev2.Payload{{Type: ikev2.PayloadEAP, Body: eapBytes}}, keys, false)
			sendNAT(resp)
		}
	}
}

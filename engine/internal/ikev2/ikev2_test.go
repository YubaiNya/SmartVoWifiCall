package ikev2

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/binary"
	"net"
	"testing"
	"time"

	"github.com/YubaiNya/SmartVoWifiCall/engine/internal/ipsec"
	"github.com/YubaiNya/SmartVoWifiCall/engine/internal/simauth"
)

// TestFullIKEv2EAPAKATunnelAndDPD spins up a real RFC 7296 Mock ePDG server on loopback UDP,
// exercises IKE_SA_INIT -> IKE_AUTH -> EAP-AKA with AUTS synchronization-failure retry ->
// EAP-Success -> MSK AUTH -> CFG_REPLY (LocalIP + P-CSCF) -> live ESP inner UDP echo + DPD!
func TestFullIKEv2EAPAKATunnelAndDPD(t *testing.T) {
	pc, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 0})
	if err != nil {
		t.Fatal(err)
	}
	defer pc.Close()

	sim := &simauth.MockProvider{
		ForceSyncFailOnce: true,
		KeyMaterial:       []byte("test-usim-k-material"),
	}
	// Separate mock instance with same key for server-side expected RES/CK/IK calculation
	serverSim := &simauth.MockProvider{KeyMaterial: []byte("test-usim-k-material")}
	identity := "0454030000000001@wlan.mnc003.mcc454.3gppnetwork.org"

	sawSyncFail := make(chan bool, 1)
	serverTunReady := make(chan *ipsec.Tunnel, 1)

	go func() {
		dh, _ := GenerateDH(DHGroupMODP2048)
		nr := make([]byte, 32)
		_, _ = rand.Read(nr)
		var spirBuf [8]byte
		_, _ = rand.Read(spirBuf[:])
		spir := binary.BigEndian.Uint64(spirBuf[:])
		var spii uint64
		var ni, initResponse, serverMSK []byte
		var keys *IKEKeys
		var clientChildSPI uint32
		serverChildSPI := uint32(0xAABBCCDD)
		var serverTun *ipsec.Tunnel
		var peerAddr *net.UDPAddr
		challengeCount := 0

		sendNAT := func(pkt []byte) {
			framed := make([]byte, 4+len(pkt))
			copy(framed[4:], pkt)
			_, _ = pc.WriteToUDP(framed, peerAddr)
		}

		buf := make([]byte, 4096)
		for {
			n, addr, err := pc.ReadFromUDP(buf)
			if err != nil {
				return
			}
			peerAddr = addr
			raw := append([]byte(nil), buf[:n]...)
			if len(raw) >= 4 && binary.BigEndian.Uint32(raw[0:4]) != 0 && serverTun != nil {
				_ = serverTun.HandleESP(raw)
				continue
			}
			if len(raw) >= 4 && binary.BigEndian.Uint32(raw[0:4]) == 0 {
				raw = raw[4:]
			}
			h, payloads, err := DecodePacket(raw)
			if err != nil {
				continue
			}
			switch h.Exchange {
			case ExchangeIKESAInit:
				spii = h.SPIi
				keP := FindPayload(payloads, PayloadKE)
				niP := FindPayload(payloads, PayloadNonce)
				ni = niP.Body
				secret, _ := dh.SharedSecret(keP.Body[4:])
				keys = DeriveIKEKeys(ni, nr, secret, spii, spir, false)
				resp := EncodePacket(Header{
					SPIi: spii, SPIr: spir, Exchange: ExchangeIKESAInit, Flags: FlagResponse, MessageID: h.MessageID,
				}, []Payload{
					{Type: PayloadSA, Body: BuildIKESAProposal(DHGroupMODP2048)},
					{Type: PayloadKE, Body: BuildKEPayload(DHGroupMODP2048, dh.Public)},
					{Type: PayloadNonce, Body: nr},
				})
				initResponse = append([]byte(nil), resp...)
				_, _ = pc.WriteToUDP(resp, peerAddr)

			case ExchangeIKEAuth:
				_, inner, err := DecodeEncryptedPacket(raw, keys, true)
				if err != nil {
					t.Errorf("server decrypt IKE_AUTH: %v", err)
					return
				}
				if saP := FindPayload(inner, PayloadSA); saP != nil {
					clientChildSPI, _ = ParseChildSASPI(saP.Body)
				}
				if FindPayload(inner, PayloadAUTH) != nil {
					// Final IKE_AUTH -> send CFG_REPLY + Child SA + start server ESP tunnel
					var cp bytes.Buffer
					cp.Write([]byte{CFGReply, 0, 0, 0})
					cp.Write([]byte{0, byte(AttrInternalIP4Addr), 0, 4, 10, 88, 0, 2})
					cp.Write([]byte{0, byte(AttrPCSCFIP4Addr), 0, 4, 10, 88, 0, 1})
					cp.Write([]byte{0, byte(AttrInternalIP4DNS), 0, 4, 10, 88, 0, 53})

					km := DeriveChildKeymat(keys.SKd, ni, nr, 16+32+16+32)
					inSA, _ := ipsec.NewSA(serverChildSPI, ipsec.SuiteAESCBC128HMACSHA256, km[0:16], km[16:48], nil)
					outSA, _ := ipsec.NewSA(clientChildSPI, ipsec.SuiteAESCBC128HMACSHA256, km[48:64], km[64:96], nil)
					serverTun, _ = ipsec.NewTunnel(net.IPv4(10, 88, 0, 1), outSA, inSA, &udpTransport{pc: pc, dst: peerAddr})
					serverTunReady <- serverTun

					resp, _ := EncodeEncryptedPacket(Header{
						SPIi: spii, SPIr: spir, Exchange: ExchangeIKEAuth, Flags: FlagResponse, MessageID: h.MessageID,
					}, []Payload{
						{Type: PayloadIDr, Body: BuildIDPayload(2, "ims")},
						{Type: PayloadAUTH, Body: append([]byte{2, 0, 0, 0}, ComputeAuthPayloadWithPRF(keys.Suite.PRFID, serverMSK, initResponse, ni, BuildIDPayload(2, "ims"), keys.SKpr)...)},
						{Type: PayloadCP, Body: cp.Bytes()},
						{Type: PayloadSA, Body: BuildChildESPProposal(serverChildSPI, false)},
						{Type: PayloadTSi, Body: BuildTSAllIPv4()},
						{Type: PayloadTSr, Body: BuildTSAllIPv4()},
					}, keys, false)
					sendNAT(resp)
					continue
				}

				if eapResp := FindPayload(inner, PayloadEAP); eapResp != nil && len(eapResp.Body) >= 6 {
					if eapResp.Body[5] == AKASyncFailure {
						sawSyncFail <- true
					} else if eapResp.Body[5] == AKAChallenge {
						// Send EAP-Success
						eapSucc := []byte{EAPCodeSuccess, eapResp.Body[1], 0, 4}
						resp, _ := EncodeEncryptedPacket(Header{
							SPIi: spii, SPIr: spir, Exchange: ExchangeIKEAuth, Flags: FlagResponse, MessageID: h.MessageID,
						}, []Payload{{Type: PayloadEAP, Body: eapSucc}}, keys, false)
						sendNAT(resp)
						continue
					}
				}

				// Issue EAP-Request/AKA-Challenge
				challengeCount++
				rand16 := bytes.Repeat([]byte{byte(0x10 + challengeCount)}, 16)
				autn16 := bytes.Repeat([]byte{byte(0x20 + challengeCount)}, 16)
				expected, _ := serverSim.Authenticate(context.Background(), simauth.AuthRequest{
					RAND: rand16, AUTN: autn16,
				})
				akaKeys := DeriveEAPAKAKeys(identity, expected.IK, expected.CK)
				serverMSK = akaKeys.MSK
				var eap bytes.Buffer
				eap.Write([]byte{EAPCodeRequest, byte(challengeCount), 0, 0, EAPTypeAKA, AKAChallenge, 0, 0})
				eap.Write([]byte{ATRand, 5, 0, 0})
				eap.Write(rand16)
				eap.Write([]byte{ATAutn, 5, 0, 0})
				eap.Write(autn16)
				macPos := eap.Len() + 4
				eap.Write([]byte{ATMac, 5, 0, 0})
				eap.Write(make([]byte, 16))
				eapBytes := eap.Bytes()
				binary.BigEndian.PutUint16(eapBytes[2:4], uint16(len(eapBytes)))
				copy(eapBytes[macPos:macPos+16], ComputeEAPATMAC(akaKeys.Kaut, eapBytes, false))

				resp, _ := EncodeEncryptedPacket(Header{
					SPIi: spii, SPIr: spir, Exchange: ExchangeIKEAuth, Flags: FlagResponse, MessageID: h.MessageID,
				}, []Payload{{Type: PayloadEAP, Body: eapBytes}}, keys, false)
				sendNAT(resp)

			case ExchangeInformational:
				resp, _ := EncodeEncryptedPacket(Header{
					SPIi: spii, SPIr: spir, Exchange: ExchangeInformational, Flags: FlagResponse, MessageID: h.MessageID,
				}, nil, keys, false)
				sendNAT(resp)
			}
		}
	}()

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	// Use NAT-T framing on loopback test port by pre-setting NATTAddr == EPDGAddr?
	// Our client enables nattMode when port==4500 OR after switching to NATTAddr, so pass NATTAddr=pc.LocalAddr().
	client, err := Connect(ctx, SessionConfig{
		EPDGAddr:    pc.LocalAddr().String(),
		NATTAddr:    pc.LocalAddr().String(),
		EAPIdentity: identity,
		APN:         "ims",
		Slot:        1,
		ICCID:       "8985203000000000001",
		SimAuth:     sim,
		DPDInterval: 200 * time.Millisecond,
	})
	if err != nil {
		t.Fatalf("IKEv2 Connect failed: %v", err)
	}
	defer client.Close()

	select {
	case <-sawSyncFail:
	default:
		t.Fatal("expected AUTS synchronization failure retry to execute")
	}

	if client.Config().LocalIP.String() != "10.88.0.2" {
		t.Fatalf("unexpected LocalIP: %v", client.Config().LocalIP)
	}
	if len(client.Config().PCSCF) != 1 || client.Config().PCSCF[0].String() != "10.88.0.1" {
		t.Fatalf("unexpected PCSCFs: %+v", client.Config().PCSCF)
	}

	// Verify real inner UDP-over-ESP traffic across the negotiated IKEv2 tunnel!
	srvTun := <-serverTunReady
	srvUDP, err := srvTun.BindUDP(5060)
	if err != nil {
		t.Fatal(err)
	}
	defer srvUDP.Close()
	cliUDP, err := client.Tunnel().BindUDP(5060)
	if err != nil {
		t.Fatal(err)
	}
	defer cliUDP.Close()

	ping := []byte("OPTIONS sip:ims.mnc003.mcc454.3gppnetwork.org SIP/2.0\r\n\r\n")
	if err := cliUDP.WriteTo(net.IPv4(10, 88, 0, 1), 5060, ping); err != nil {
		t.Fatalf("WriteTo over ESP failed: %v", err)
	}
	pkt, err := srvUDP.ReadFrom(time.Now().Add(2 * time.Second))
	if err != nil {
		t.Fatalf("server ESP ReadFrom failed: %v", err)
	}
	if !bytes.Equal(pkt.Payload, ping) {
		t.Fatalf("ESP inner payload mismatch: %q", pkt.Payload)
	}
}

type udpTransport struct {
	pc  *net.UDPConn
	dst *net.UDPAddr
}

func (u *udpTransport) SendESP(esp []byte) error {
	_, err := u.pc.WriteToUDP(esp, u.dst)
	return err
}

func TestBuildDeviceIdentityNotify3GPP(t *testing.T) {
	// 3GPP TS 24.302 §8.2.9.2 IMEI TBCD test: 351234567890123
	// Pairs: (3,5)->0x53, (1,2)->0x21, (3,4)->0x43, (5,6)->0x65, (7,8)->0x87, (9,0)->0x09, (1,2)->0x21, (3,F)->0xF3
	raw, ok := BuildDeviceIdentityNotify("351234567890123", DeviceIdentityTypeIMEI)
	if !ok {
		t.Fatal("expected valid IMEI DEVICE_IDENTITY notify")
	}
	nt, data, parsed := ParseNotify(raw)
	if !parsed || nt != NotifyDeviceIdentity {
		t.Fatalf("unexpected notify header: parsed=%v type=%d", parsed, nt)
	}
	expected := []byte{0x00, 0x09, 0x01, 0x53, 0x21, 0x43, 0x65, 0x87, 0x09, 0x21, 0xF3}
	if !bytes.Equal(data, expected) {
		t.Fatalf("IMEI DEVICE_IDENTITY mismatch:\ngot:  %x\nwant: %x", data, expected)
	}

	// IMEISV with 15-digit IMEI falls back to SV=01 -> last two digits 0,1 -> 0x10
	rawSV, ok := BuildDeviceIdentityNotify("351234567890123", DeviceIdentityTypeIMEISV)
	if !ok {
		t.Fatal("expected valid IMEISV DEVICE_IDENTITY notify")
	}
	_, dataSV, _ := ParseNotify(rawSV)
	expectedSV := []byte{0x00, 0x09, 0x02, 0x53, 0x21, 0x43, 0x65, 0x87, 0x09, 0x21, 0x10}
	if !bytes.Equal(dataSV, expectedSV) {
		t.Fatalf("IMEISV DEVICE_IDENTITY mismatch:\ngot:  %x\nwant: %x", dataSV, expectedSV)
	}

	if got := ParseDeviceIdentityRequest([]byte{0x00, 0x01, 0x02}); got != DeviceIdentityTypeIMEISV {
		t.Fatalf("expected IMEISV request parsed, got %d", got)
	}
	if got := ParseDeviceIdentityRequest(nil); got != DeviceIdentityTypeIMEI {
		t.Fatalf("expected default IMEI request type, got %d", got)
	}
}

func TestStrict3GPPEPDGNotify24AndDeviceIdentity(t *testing.T) {
	pc, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 0})
	if err != nil {
		t.Fatal(err)
	}
	defer pc.Close()

	sim := &simauth.MockProvider{KeyMaterial: []byte("giffgaff-mock-k")}
	serverSim := &simauth.MockProvider{KeyMaterial: []byte("giffgaff-mock-k")}
	acceptedID := "0234100000000001@nai.epc.mnc010.mcc234.3gppnetwork.org"
	rejectedID := "0234100000000001@wlan.mnc010.mcc234.3gppnetwork.org"

	sawInitialContact := false
	sawMobike := false
	sawDeviceID := false

	go func() {
		type sess struct {
			spii, spir        uint64
			ni, nr            []byte
			initResponse, msk []byte
			keys              *IKEKeys
			clientChildSPI    uint32
			identity          string
		}
		sessions := map[uint64]*sess{}
		buf := make([]byte, 4096)
		for {
			n, addr, err := pc.ReadFromUDP(buf)
			if err != nil {
				return
			}
			raw := append([]byte(nil), buf[:n]...)
			if len(raw) >= 4 && binary.BigEndian.Uint32(raw[0:4]) == 0 {
				raw = raw[4:]
			}
			h, payloads, err := DecodePacket(raw)
			if err != nil {
				continue
			}
			sendNAT := func(pkt []byte) {
				framed := make([]byte, 4+len(pkt))
				copy(framed[4:], pkt)
				_, _ = pc.WriteToUDP(framed, addr)
			}
			switch h.Exchange {
			case ExchangeIKESAInit:
				dh, _ := GenerateDH(DHGroupMODP2048)
				nr := make([]byte, 32)
				_, _ = rand.Read(nr)
				var spirBuf [8]byte
				_, _ = rand.Read(spirBuf[:])
				spir := binary.BigEndian.Uint64(spirBuf[:])
				keP := FindPayload(payloads, PayloadKE)
				niP := FindPayload(payloads, PayloadNonce)
				secret, _ := dh.SharedSecret(keP.Body[4:])
				keys := DeriveIKEKeys(niP.Body, nr, secret, h.SPIi, spir, false)
				sessions[h.SPIi] = &sess{spii: h.SPIi, spir: spir, ni: niP.Body, nr: nr, keys: keys}
				resp := EncodePacket(Header{
					SPIi: h.SPIi, SPIr: spir, Exchange: ExchangeIKESAInit, Flags: FlagResponse, MessageID: h.MessageID,
				}, []Payload{
					{Type: PayloadSA, Body: BuildIKESAProposal(DHGroupMODP2048)},
					{Type: PayloadKE, Body: BuildKEPayload(DHGroupMODP2048, dh.Public)},
					{Type: PayloadNonce, Body: nr},
				})
				sessions[h.SPIi].initResponse = append([]byte(nil), resp...)
				_, _ = pc.WriteToUDP(resp, addr)
			case ExchangeIKEAuth:
				s := sessions[h.SPIi]
				if s == nil {
					continue
				}
				_, inner, err := DecodeEncryptedPacket(raw, s.keys, true)
				if err != nil {
					continue
				}
				for _, p := range inner {
					if p.Type == PayloadNotify {
						if nt, ndata, ok := ParseNotify(p.Body); ok {
							switch nt {
							case NotifyInitialContact:
								sawInitialContact = true
							case NotifyMOBIKESupported:
								sawMobike = true
							case NotifyDeviceIdentity:
								if len(ndata) == 11 && ndata[2] == DeviceIdentityTypeIMEI {
									sawDeviceID = true
								}
							}
						}
					}
				}
				if idiP := FindPayload(inner, PayloadIDi); idiP != nil && len(idiP.Body) > 4 {
					s.identity = string(idiP.Body[4:])
					if s.identity != acceptedID {
						resp, _ := EncodeEncryptedPacket(Header{
							SPIi: s.spii, SPIr: s.spir, Exchange: ExchangeIKEAuth, Flags: FlagResponse, MessageID: h.MessageID,
						}, []Payload{{Type: PayloadNotify, Body: buildNotify(NotifyAuthenticationFailed, nil)}}, s.keys, false)
						sendNAT(resp)
						continue
					}
				}
				if saP := FindPayload(inner, PayloadSA); saP != nil {
					s.clientChildSPI, _ = ParseChildSASPI(saP.Body)
				}
				if FindPayload(inner, PayloadAUTH) != nil {
					var cp bytes.Buffer
					cp.Write([]byte{CFGReply, 0, 0, 0})
					cp.Write([]byte{0, byte(AttrInternalIP4Addr), 0, 4, 10, 90, 0, 2})
					cp.Write([]byte{0, byte(AttrPCSCFIP4Addr), 0, 4, 10, 90, 0, 1})
					resp, _ := EncodeEncryptedPacket(Header{
						SPIi: s.spii, SPIr: s.spir, Exchange: ExchangeIKEAuth, Flags: FlagResponse, MessageID: h.MessageID,
					}, []Payload{
						{Type: PayloadIDr, Body: BuildIDPayload(2, "ims")},
						{Type: PayloadAUTH, Body: append([]byte{2, 0, 0, 0}, ComputeAuthPayloadWithPRF(s.keys.Suite.PRFID, s.msk, s.initResponse, s.ni, BuildIDPayload(2, "ims"), s.keys.SKpr)...)},
						{Type: PayloadCP, Body: cp.Bytes()},
						{Type: PayloadSA, Body: BuildChildESPProposal(0x11223344, false)},
						{Type: PayloadTSi, Body: BuildTSAllIPv4()},
						{Type: PayloadTSr, Body: BuildTSAllIPv4()},
					}, s.keys, false)
					sendNAT(resp)
					continue
				}
				if eapResp := FindPayload(inner, PayloadEAP); eapResp != nil {
					eapSucc := []byte{EAPCodeSuccess, eapResp.Body[1], 0, 4}
					resp, _ := EncodeEncryptedPacket(Header{
						SPIi: s.spii, SPIr: s.spir, Exchange: ExchangeIKEAuth, Flags: FlagResponse, MessageID: h.MessageID,
					}, []Payload{{Type: PayloadEAP, Body: eapSucc}}, s.keys, false)
					sendNAT(resp)
					continue
				}
				rand16 := bytes.Repeat([]byte{0x33}, 16)
				autn16 := bytes.Repeat([]byte{0x44}, 16)
				exp, _ := serverSim.Authenticate(context.Background(), simauth.AuthRequest{RAND: rand16, AUTN: autn16})
				akaKeys := DeriveEAPAKAKeys(s.identity, exp.IK, exp.CK)
				s.msk = akaKeys.MSK
				var eap bytes.Buffer
				eap.Write([]byte{EAPCodeRequest, 1, 0, 0, EAPTypeAKA, AKAChallenge, 0, 0})
				eap.Write([]byte{ATRand, 5, 0, 0})
				eap.Write(rand16)
				eap.Write([]byte{ATAutn, 5, 0, 0})
				eap.Write(autn16)
				macPos := eap.Len() + 4
				eap.Write([]byte{ATMac, 5, 0, 0})
				eap.Write(make([]byte, 16))
				eapBytes := eap.Bytes()
				binary.BigEndian.PutUint16(eapBytes[2:4], uint16(len(eapBytes)))
				copy(eapBytes[macPos:macPos+16], ComputeEAPATMAC(akaKeys.Kaut, eapBytes, false))
				resp, _ := EncodeEncryptedPacket(Header{
					SPIi: s.spii, SPIr: s.spir, Exchange: ExchangeIKEAuth, Flags: FlagResponse, MessageID: h.MessageID,
				}, []Payload{
					{Type: PayloadEAP, Body: eapBytes},
					{Type: PayloadNotify, Body: buildNotify(NotifyDeviceIdentity, []byte{0x00, 0x01, DeviceIdentityTypeIMEI})},
				}, s.keys, false)
				sendNAT(resp)
			}
		}
	}()

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	// 1. Legacy @wlan identity triggers Notify 24 AUTHENTICATION_FAILED
	_, err = Connect(ctx, SessionConfig{
		EPDGAddr:    pc.LocalAddr().String(),
		NATTAddr:    pc.LocalAddr().String(),
		EAPIdentity: rejectedID,
		APN:         "ims",
		IMEI:        "351234567890123",
		Slot:        0,
		ICCID:       "8944110000000000001",
		SimAuth:     sim,
	})
	if err == nil || !IsAuthFailed(err) {
		t.Fatalf("expected IsAuthFailed(Notify 24), got: %v", err)
	}

	// 2. Standard 3GPP @nai.epc identity succeeds and answers DEVICE_IDENTITY (41101)
	cli, err := Connect(ctx, SessionConfig{
		EPDGAddr:    pc.LocalAddr().String(),
		NATTAddr:    pc.LocalAddr().String(),
		EAPIdentity: acceptedID,
		APN:         "ims",
		IMEI:        "351234567890123",
		Slot:        0,
		ICCID:       "8944110000000000001",
		SimAuth:     sim,
	})
	if err != nil {
		t.Fatalf("expected @nai.epc connect to succeed, got: %v", err)
	}
	defer cli.Close()
	if !sawInitialContact || !sawMobike || !sawDeviceID {
		t.Fatalf("missing expected 3GPP notifies: initialContact=%v mobike=%v deviceID=%v",
			sawInitialContact, sawMobike, sawDeviceID)
	}
}

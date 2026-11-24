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
		var ni []byte
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

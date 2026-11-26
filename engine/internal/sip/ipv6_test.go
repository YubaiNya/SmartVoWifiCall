package sip

import (
	"bytes"
	"context"
	"encoding/base64"
	"fmt"
	"github.com/YubaiNya/SmartVoWifiCall/engine/internal/ipsec"
	"github.com/YubaiNya/SmartVoWifiCall/engine/internal/simauth"
	"net"
	"strconv"
	"strings"
	"testing"
	"time"
)

func TestIPv6RegistrationOverESP(t *testing.T) {
	t.Run("plain-SIP", func(t *testing.T) { testIPv6Registration(t, false) })
	t.Run("IMS-ESP", func(t *testing.T) { testIPv6Registration(t, true) })
}
func testIPv6Registration(t *testing.T, protected bool) {
	clientIP, serverIP := net.ParseIP("2001:db8:1234:5678:abcd:ef01:2345:6789"), net.ParseIP("2001:db8:2345:6789:abcd:ef01:3456:789a")
	var a, b *ipsec.Tunnel
	var err error
	k1, k2 := bytes.Repeat([]byte{0x11}, 16), bytes.Repeat([]byte{0x22}, 16)
	outA, _ := ipsec.NewSA(0x100, ipsec.SuiteAESGCM128, k1, nil, []byte{1, 2, 3, 4})
	inA, _ := ipsec.NewSA(0x200, ipsec.SuiteAESGCM128, k2, nil, []byte{5, 6, 7, 8})
	outB, _ := ipsec.NewSA(0x200, ipsec.SuiteAESGCM128, k2, nil, []byte{5, 6, 7, 8})
	inB, _ := ipsec.NewSA(0x100, ipsec.SuiteAESGCM128, k1, nil, []byte{1, 2, 3, 4})
	a, err = ipsec.NewTunnel(clientIP, outA, inA, &registrationMTUPipe{peer: &b, t: t})
	if err != nil {
		t.Fatal(err)
	}
	b, err = ipsec.NewTunnel(serverIP, outB, inB, &espPipe{peer: &a})
	if err != nil {
		t.Fatal(err)
	}
	server, err := b.BindUDP(5060)
	if err != nil {
		t.Fatal(err)
	}
	defer server.Close()
	sim := &simauth.MockProvider{KeyMaterial: []byte("ipv6-test")}
	client, err := NewClient(ClientConfig{Tunnel: a, PCSCF: serverIP, IMSRealm: "ims.mnc033.mcc234.3gppnetwork.org", IMPI: "234330000000001@ims.mnc033.mcc234.3gppnetwork.org", IMPU: "sip:234330000000001@ims.mnc033.mcc234.3gppnetwork.org", SimAuth: sim, RequireIPSec: protected})
	if err != nil {
		t.Fatal(err)
	}
	defer client.Close()
	done := make(chan error, 1)
	protectedSocket, err := b.BindUDP(5063)
	if err != nil {
		t.Fatal(err)
	}
	defer protectedSocket.Close()
	const securityServer = "ipsec-3gpp;alg=hmac-sha-1-96;ealg=null;spi-c=111;spi-s=222;port-c=5062;port-s=5063;q=1.0"
	go func() {
		for round := 0; round < 2; round++ {
			socket := server
			if protected && round == 1 {
				socket = protectedSocket
			}
			p, e := socket.ReadFrom(time.Now().Add(2 * time.Second))
			if e != nil {
				done <- e
				return
			}
			req, e := ParseMessage(p.Payload)
			if e != nil {
				done <- e
				return
			}
			if !strings.Contains(req.GetHeader("Via"), "["+clientIP.String()+"]:") || !strings.Contains(req.GetHeader("Contact"), "@["+clientIP.String()+"]:") {
				done <- fmt.Errorf("missing IPv6 address brackets")
				return
			}
			response := &Message{IsResponse: true, StatusCode: 200, Reason: "OK"}
			for _, k := range []string{"Via", "From", "To", "Call-ID", "CSeq"} {
				response.AddHeader(k, req.GetHeader(k))
			}
			if round == 0 {
				if ParseDigestParams(req.GetHeader("Authorization"))["algorithm"] != "AKAv1-MD5" {
					done <- fmt.Errorf("initial AKA algorithm not advertised")
					return
				}
				response.StatusCode = 401
				response.Reason = "Unauthorized"
				nonce := base64.StdEncoding.EncodeToString(bytes.Repeat([]byte{0x42}, 32))
				response.AddHeader("WWW-Authenticate", fmt.Sprintf("Digest realm=\"ims.mnc030.mcc234.3gppnetwork.org\", nonce=\"%s\", algorithm=AKAv1-MD5, qop=\"auth\"", nonce))
				if protected {
					params := map[string]uint64{}
					for _, part := range strings.Split(req.GetHeader("Security-Client"), ";") {
						kv := strings.SplitN(part, "=", 2)
						if len(kv) == 2 {
							n, _ := strconv.ParseUint(kv[1], 10, 32)
							params[kv[0]] = n
						}
					}
					if params["spi-c"] == 0 || params["port-c"] == 0 {
						done <- fmt.Errorf("missing security offer")
						return
					}
					auth, e := sim.Authenticate(context.Background(), simauth.AuthRequest{RAND: bytes.Repeat([]byte{0x42}, 16), AUTN: bytes.Repeat([]byte{0x42}, 16)})
					if e != nil {
						done <- e
						return
					}
					in, _ := ipsec.NewSA(222, ipsec.SuiteNullHMACSHA1, nil, auth.IK, nil)
					out, _ := ipsec.NewSA(uint32(params["spi-c"]), ipsec.SuiteNullHMACSHA1, nil, auth.IK, nil)
					if e = b.InstallUDPProtection(&ipsec.UDPProtection{PeerIP: clientIP, LocalPort: 5063, RemotePort: uint16(params["port-c"]), In: in, Out: out}); e != nil {
						done <- e
						return
					}
					response.AddHeader("Security-Server", securityServer)
				}
			} else if ParseDigestParams(req.GetHeader("Authorization"))["response"] == "" {
				done <- fmt.Errorf("missing authentication")
				return
			}
			if protected && round == 1 && req.GetHeader("Security-Verify") != securityServer {
				done <- fmt.Errorf("security verify missing")
				return
			}
			if e = socket.WriteTo(p.SrcIP, p.SrcPort, response.Encode()); e != nil {
				done <- e
				return
			}
		}
		done <- nil
	}()
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	if err = client.Register(ctx); err != nil {
		t.Fatal(err)
	}
	if err = <-done; err != nil {
		t.Fatal(err)
	}
	if !client.Registered() {
		t.Fatal("not registered")
	}
	sdp := BuildSDP(MediaOffer{LocalIP: clientIP, LocalPort: 40000, SessionID: 1})
	if !strings.Contains(string(sdp), "IN IP6 "+clientIP.String()) {
		t.Fatal("IPv6 SDP missing")
	}
	parsed, err := ParseSDP(sdp)
	if err != nil || !parsed.RemoteIP.Equal(clientIP) {
		t.Fatalf("IPv6 SDP parse %v", err)
	}
}

// Validate the actual nested ESP datagram, including the outer IPv4/UDP header.
type registrationMTUPipe struct {
	peer **ipsec.Tunnel
	t    *testing.T
}

func (p *registrationMTUPipe) SendESP(packet []byte) error {
	if len(packet)+28 > 1400 {
		p.t.Errorf("REGISTER exceeds proxy MTU: %d", len(packet)+28)
	}
	return (&espPipe{peer: p.peer}).SendESP(packet)
}

package ipsec

import (
	"bytes"
	"net"
	"testing"
	"time"
)

func TestESPCipherSuitesAndAntiReplay(t *testing.T) {
	suites := []struct {
		name    string
		suite   CipherSuite
		encKey  []byte
		authKey []byte
		salt    []byte
	}{
		{"AES-GCM-128", SuiteAESGCM128, bytes.Repeat([]byte{0x11}, 16), nil, []byte{1, 2, 3, 4}},
		{"AES-GCM-256", SuiteAESGCM256, bytes.Repeat([]byte{0x22}, 32), nil, []byte{5, 6, 7, 8}},
		{"AES-CBC-128-HMAC-SHA256", SuiteAESCBC128HMACSHA256, bytes.Repeat([]byte{0x33}, 16), bytes.Repeat([]byte{0x44}, 32), nil},
		{"AES-CBC-128-HMAC-SHA1", SuiteAESCBC128HMACSHA1, bytes.Repeat([]byte{0x55}, 16), bytes.Repeat([]byte{0x66}, 20), nil},
	}

	payload := []byte("hello-ipsec-esp-inner-ipv4-packet-payload")
	for _, tc := range suites {
		t.Run(tc.name, func(t *testing.T) {
			outSA, err := NewSA(0x11223344, tc.suite, tc.encKey, tc.authKey, tc.salt)
			if err != nil {
				t.Fatal(err)
			}
			inSA, err := NewSA(0x11223344, tc.suite, tc.encKey, tc.authKey, tc.salt)
			if err != nil {
				t.Fatal(err)
			}
			esp, err := outSA.Encrypt(4, payload)
			if err != nil {
				t.Fatal(err)
			}
			nh, plain, err := inSA.Decrypt(esp)
			if err != nil {
				t.Fatalf("decrypt failed: %v", err)
			}
			if nh != 4 || !bytes.Equal(plain, payload) {
				t.Fatalf("payload mismatch: nh=%d plain=%q", nh, plain)
			}
			// Replay of identical packet must fail
			if _, _, err := inSA.Decrypt(esp); err == nil {
				t.Fatal("expected replay rejection")
			}
			// Tampered packet must fail
			esp2, _ := outSA.Encrypt(4, payload)
			esp2[len(esp2)-1] ^= 0xFF
			if _, _, err := inSA.Decrypt(esp2); err == nil {
				t.Fatal("expected tamper rejection")
			}
		})
	}
}

type pipeTransport struct {
	peer **Tunnel
}

func (p *pipeTransport) SendESP(esp []byte) error {
	cp := append([]byte(nil), esp...)
	go func() {
		_ = (*p.peer).HandleESP(cp)
	}()
	return nil
}

func TestVirtualUDPOverESPTunnel(t *testing.T) {
	keyA := bytes.Repeat([]byte{0xA1}, 16)
	keyB := bytes.Repeat([]byte{0xB2}, 16)
	saltA := []byte{1, 2, 3, 4}
	saltB := []byte{5, 6, 7, 8}

	saOutA, _ := NewSA(0x1001, SuiteAESGCM128, keyA, nil, saltA)
	saInA, _ := NewSA(0x2002, SuiteAESGCM128, keyB, nil, saltB)
	saOutB, _ := NewSA(0x2002, SuiteAESGCM128, keyB, nil, saltB)
	saInB, _ := NewSA(0x1001, SuiteAESGCM128, keyA, nil, saltA)

	var tunA, tunB *Tunnel
	tunA, _ = NewTunnel(net.IPv4(10, 0, 0, 2), saOutA, saInA, &pipeTransport{peer: &tunB})
	tunB, _ = NewTunnel(net.IPv4(10, 0, 0, 1), saOutB, saInB, &pipeTransport{peer: &tunA})

	sockA, err := tunA.BindUDP(5060)
	if err != nil {
		t.Fatal(err)
	}
	defer sockA.Close()
	sockB, err := tunB.BindUDP(5060)
	if err != nil {
		t.Fatal(err)
	}
	defer sockB.Close()

	msg := []byte("REGISTER sip:ims.example SIP/2.0\r\n\r\n")
	if err := sockA.WriteTo(net.IPv4(10, 0, 0, 1), 5060, msg); err != nil {
		t.Fatal(err)
	}
	pkt, err := sockB.ReadFrom(time.Now().Add(2 * time.Second))
	if err != nil {
		t.Fatalf("ReadFrom failed: %v", err)
	}
	if !bytes.Equal(pkt.Payload, msg) || pkt.SrcPort != 5060 {
		t.Fatalf("unexpected inner UDP packet: %+v", pkt)
	}
}

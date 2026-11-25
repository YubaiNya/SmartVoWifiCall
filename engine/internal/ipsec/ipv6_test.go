package ipsec

import (
	"bytes"
	"encoding/binary"
	"net"
	"testing"
	"time"
)

type inspectedIPv6Transport struct {
	sa       *SA
	reply    *Tunnel
	t        *testing.T
	src, dst net.IP
}

func (p *inspectedIPv6Transport) SendESP(packet []byte) error {
	h, raw, err := p.sa.Decrypt(packet)
	if err != nil {
		return err
	}
	if h != 41 || len(raw) < 48 || raw[0]>>4 != 6 || raw[6] != 17 {
		p.t.Error("not IPv6 UDP")
	}
	if !net.IP(raw[8:24]).Equal(p.src) || !net.IP(raw[24:40]).Equal(p.dst) {
		p.t.Error("incorrect address")
	}
	if int(binary.BigEndian.Uint16(raw[4:6])) != len(raw)-40 {
		p.t.Error("incorrect payload length")
	}
	// Independent pseudo-header checksum (including the transmitted checksum).
	pseudo := append(append([]byte{}, p.src.To16()...), p.dst.To16()...)
	tail := make([]byte, 8)
	binary.BigEndian.PutUint32(tail, uint32(len(raw)-40))
	tail[7] = 17
	pseudo = append(pseudo, tail...)
	pseudo = append(pseudo, raw[40:]...)
	var sum uint32
	for i := 0; i < len(pseudo); i += 2 {
		sum += uint32(pseudo[i]) << 8
		if i+1 < len(pseudo) {
			sum += uint32(pseudo[i+1])
		}
	}
	for sum>>16 != 0 {
		sum = (sum & 65535) + (sum >> 16)
	}
	if sum != 65535 {
		p.t.Error("bad IPv6 UDP checksum")
	}
	return p.reply.HandleESP(packet)
}
func TestIPv6UDPOverESP(t *testing.T) {
	key := bytes.Repeat([]byte{0x42}, 16)
	salt := []byte{1, 2, 3, 4}
	out, _ := NewSA(123, SuiteAESGCM128, key, nil, salt)
	in, _ := NewSA(123, SuiteAESGCM128, key, nil, salt)
	inspect, _ := NewSA(123, SuiteAESGCM128, key, nil, salt)
	a, b := net.ParseIP("2001:db8:1::2"), net.ParseIP("2001:db8:2::1")
	receiver, err := NewTunnel(b, nil, in, nil)
	if err != nil {
		t.Fatal(err)
	}
	sender, err := NewTunnel(a, out, nil, &inspectedIPv6Transport{inspect, receiver, t, a, b})
	if err != nil {
		t.Fatal(err)
	}
	socket, err := receiver.BindUDP(5060)
	if err != nil {
		t.Fatal(err)
	}
	defer socket.Close()
	payload := []byte("odd-length REGISTER")
	if err = sender.SendUDP(5061, b, 5060, payload); err != nil {
		t.Fatal(err)
	}
	got, err := socket.ReadFrom(time.Now().Add(time.Second))
	if err != nil || !got.SrcIP.Equal(a) || !bytes.Equal(got.Payload, payload) {
		t.Fatalf("IPv6 delivery failed %v", err)
	}
	if err = sender.SendUDP(5061, net.IPv4(192, 0, 2, 1), 5060, payload); err == nil {
		t.Fatal("accepted mismatched family")
	}
}

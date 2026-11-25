package ikev2

import (
	"bytes"
	"encoding/binary"
	"net"
	"testing"
	"time"
)

func TestProxyUDPReplySourceAndSPICorrelation(t *testing.T) {
	target, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	defer target.Close()
	relay, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	defer relay.Close()
	remote := target.LocalAddr().(*net.UDPAddr)
	conn, err := openUDP(remote, true)
	if err != nil {
		t.Fatal(err)
	}
	c := &Client{conn: conn, remoteAddr: remote, spii: 0x12345678, spir: 0x87654321, pending: map[uint32]chan []byte{0: make(chan []byte, 2)}, stopCh: make(chan struct{}), deadCh: make(chan struct{})}
	c.wg.Add(1)
	go c.readLoop(conn)
	defer func() { conn.Close(); c.wg.Wait() }()
	if _, err = writeUDP(conn, remote, []byte("probe")); err != nil {
		t.Fatal(err)
	}
	target.SetReadDeadline(time.Now().Add(time.Second))
	buf := make([]byte, 64)
	_, clientAddr, err := target.ReadFromUDP(buf)
	if err != nil {
		t.Fatal(err)
	}
	for _, h := range []Header{{SPIi: c.spii + 1, SPIr: c.spir}, {SPIi: c.spii, SPIr: c.spir + 1}} {
		h.Exchange = ExchangeIKESAInit
		h.Flags = FlagResponse
		relay.WriteToUDP(EncodePacket(h, nil), clientAddr)
	}
	select {
	case <-c.pending[0]:
		t.Fatal("accepted foreign SPI")
	case <-time.After(50 * time.Millisecond):
	}
	want := EncodePacket(Header{SPIi: c.spii, SPIr: c.spir, Exchange: ExchangeIKESAInit, Flags: FlagResponse}, nil)
	relay.WriteToUDP(want, clientAddr)
	select {
	case got := <-c.pending[0]:
		if !bytes.Equal(got, want) {
			t.Fatal("wrong reply")
		}
	case <-time.After(time.Second):
		t.Fatal("proxy reply was discarded")
	}
}

func TestIPv6ConfigurationAndSelectors(t *testing.T) {
	request := BuildCFGRequestForFamily(true)
	if !bytes.Equal(request, []byte{1, 0, 0, 0, 0, 8, 0, 0, 0, 10, 0, 0, 0, 21, 0, 0}) {
		t.Fatalf("wrong request %x", request)
	}
	ts := BuildTSAll(true)
	if len(ts) != 44 || ts[0] != 1 || ts[4] != 8 || binary.BigEndian.Uint16(ts[6:8]) != 40 || !bytes.Equal(ts[28:], bytes.Repeat([]byte{255}, 16)) {
		t.Fatal("invalid IPv6 selector")
	}
	local, server := net.ParseIP("2001:db8:1::2"), net.ParseIP("2001:db8:2::1")
	reply := []byte{2, 0, 0, 0, 0, 8, 0, 17}
	reply = append(reply, local.To16()...)
	reply = append(reply, 64, 0, 21, 0, 16)
	reply = append(reply, server.To16()...)
	cfg, err := ParseCFGReply(reply)
	if err != nil || !cfg.LocalIP.Equal(local) || len(cfg.PCSCF) != 1 || !cfg.PCSCF[0].Equal(server) {
		t.Fatalf("IPv6 reply failed: %v", err)
	}
	if _, err = ParseCFGReply(reply[:len(reply)-1]); err == nil {
		t.Fatal("accepted truncated address")
	}
	reply[24] = 129
	if _, err = ParseCFGReply(reply); err == nil {
		t.Fatal("accepted invalid prefix")
	}
}

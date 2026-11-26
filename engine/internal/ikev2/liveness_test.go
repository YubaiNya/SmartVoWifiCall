package ikev2

import (
	"bytes"
	"net"
	"testing"
	"time"
)

func TestUnauthenticatedAndUnsolicitedPacketsCannotProveLiveness(t *testing.T) {
	conn, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	peer, err := net.DialUDP("udp4", nil, conn.LocalAddr().(*net.UDPAddr))
	if err != nil {
		t.Fatal(err)
	}
	defer peer.Close()
	keys := DeriveIKEKeys(bytes.Repeat([]byte{1}, 32), bytes.Repeat([]byte{2}, 32), bytes.Repeat([]byte{3}, 32), 1, 2, false)
	ch := make(chan []byte, 8)
	c := &Client{conn: conn, spii: 1, spir: 2, keys: keys, pending: map[uint32]chan []byte{7: ch}, pendingExchange: map[uint32]byte{7: ExchangeInformational},
		stopCh: make(chan struct{}), deadCh: make(chan struct{})}
	c.lastRecv.Store(1)
	c.wg.Add(1)
	go c.readLoop(conn)
	defer c.Close()
	header := Header{SPIi: 1, SPIr: 2, Exchange: ExchangeInformational, Flags: FlagResponse, MessageID: 7}
	valid, err := EncodeEncryptedPacket(header, nil, keys, false)
	if err != nil {
		t.Fatal(err)
	}
	bad := append([]byte(nil), valid...)
	bad[len(bad)-1] ^= 1
	wrong := header
	wrong.Exchange = ExchangeIKEAuth
	wrongPacket, _ := EncodeEncryptedPacket(wrong, nil, keys, false)
	for _, pkt := range [][]byte{EncodePacket(header, nil), bad, wrongPacket, valid} {
		if _, err := peer.Write(pkt); err != nil {
			t.Fatal(err)
		}
	}
	select {
	case got := <-ch:
		if !bytes.Equal(got, valid) {
			t.Fatal("unverified response reached transaction")
		}
	case <-time.After(time.Second):
		t.Fatal("valid authenticated response rejected")
	}
	// Let readLoop finish publishing its accepted observation, then invalidate
	// the transaction. An old valid response no longer proves current liveness.
	time.Sleep(20 * time.Millisecond)
	c.mu.Lock()
	delete(c.pending, 7)
	delete(c.pendingExchange, 7)
	c.mu.Unlock()
	c.lastRecv.Store(1)
	peer.Write(valid)
	peer.Write(bad)
	time.Sleep(30 * time.Millisecond)
	if c.lastRecv.Load() != 1 {
		t.Fatal("replayed/unverified response refreshed liveness")
	}
}

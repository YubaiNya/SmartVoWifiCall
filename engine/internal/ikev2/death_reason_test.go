package ikev2

import (
	"bytes"
	"net"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/YubaiNya/SmartVoWifiCall/engine/internal/diag"
)

func TestDPDSendFailurePreservesFirstCause(t *testing.T) {
	conn, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	remote := conn.LocalAddr().(*net.UDPAddr)
	conn.Close()
	c := &Client{conn: conn, remoteAddr: remote, deadCh: make(chan struct{}), stopCh: make(chan struct{}),
		pending: make(map[uint32]chan []byte), pendingExchange: make(map[uint32]byte)}
	if c.runDPDExchange(1, []byte{0}) {
		t.Fatal("closed socket reported a successful probe")
	}
	c.markDead("DPD_TIMEOUT")
	if c.DeadReason() != "UDP_WRITE_ERROR" {
		t.Fatal("write failure mislabeled as response timeout")
	}
	select {
	case <-c.Dead():
	default:
		t.Fatal("terminal cause did not notify runtime")
	}
	unknown := &Client{deadCh: make(chan struct{})}
	unknown.markDead("private-fixture-do-not-export")
	if unknown.DeadReason() != "TUNNEL_LOST" {
		t.Fatal("unbounded error text exported")
	}
}

func TestAuthenticatedDPDReplyHasMinimalDiagnostics(t *testing.T) {
	peer, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	defer peer.Close()
	conn, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	dir := t.TempDir()
	logger, err := diag.NewLogger(dir)
	if err != nil {
		t.Fatal(err)
	}
	defer logger.Close()
	keys := DeriveIKEKeys(bytes.Repeat([]byte{1}, 32), bytes.Repeat([]byte{2}, 32), bytes.Repeat([]byte{3}, 32), 1, 2, false)
	c := &Client{cfg: SessionConfig{Logger: logger}, conn: conn, remoteAddr: peer.LocalAddr().(*net.UDPAddr),
		spii: 1, spir: 2, keys: keys, deadCh: make(chan struct{}), stopCh: make(chan struct{}),
		pending: make(map[uint32]chan []byte), pendingExchange: make(map[uint32]byte)}
	c.wg.Add(1)
	go c.readLoop(conn)
	defer c.Close()
	done := make(chan error, 1)
	go func() {
		peer.SetReadDeadline(time.Now().Add(time.Second))
		buf := make([]byte, 4096)
		n, addr, err := peer.ReadFromUDP(buf)
		if err != nil {
			done <- err
			return
		}
		h, _, err := DecodeEncryptedPacket(buf[:n], keys, true)
		if err != nil {
			done <- err
			return
		}
		h.Flags = FlagResponse
		response, err := EncodeEncryptedPacket(h, nil, keys, false)
		if err == nil {
			_, err = peer.WriteToUDP(response, addr)
		}
		done <- err
	}()
	pkt, err := EncodeEncryptedPacket(Header{SPIi: 1, SPIr: 2, Exchange: ExchangeInformational, Flags: FlagInitiator, MessageID: 4}, nil, keys, true)
	if err != nil {
		t.Fatal(err)
	}
	if !c.runDPDExchange(4, pkt) {
		t.Fatal("authenticated DPD failed")
	}
	if err := <-done; err != nil {
		t.Fatal(err)
	}
	data, err := os.ReadFile(filepath.Join(dir, "diagnostic.jsonl"))
	if err != nil {
		t.Fatal(err)
	}
	for _, expected := range []string{"DPD probe sent", "DPD reply authenticated", `"messageId":4`, `"attempt":1`} {
		if !strings.Contains(string(data), expected) {
			t.Fatalf("missing diagnostic %s", expected)
		}
	}
	if strings.Contains(string(data), "127.0.0.1") {
		t.Fatal("liveness diagnostic exposed endpoint")
	}
}

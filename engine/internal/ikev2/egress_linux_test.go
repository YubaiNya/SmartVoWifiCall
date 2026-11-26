//go:build linux && integration

package ikev2

import (
	"context"
	"errors"
	"net"
	"os"
	"testing"
	"time"

	"github.com/YubaiNya/SmartVoWifiCall/engine/internal/egress"
)

// Local loopback only: no carrier traffic, namespace or interface configuration changes.
func TestExplicitInterfaceSocketsAndSystemRoute(t *testing.T) {
	peer, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	defer peer.Close()
	for _, iface := range []string{"", "lo"} {
		for _, proxy := range []bool{false, true} {
			ctx, cancel := context.WithTimeout(context.Background(), time.Second)
			conn, err := openUDPOnInterface(ctx, peer.LocalAddr().(*net.UDPAddr), proxy, iface)
			cancel()
			if err != nil {
				t.Fatal(err)
			}
			if _, err = writeUDP(conn, peer.LocalAddr().(*net.UDPAddr), []byte("route-fixture")); err != nil {
				conn.Close()
				t.Fatal(err)
			}
			peer.SetReadDeadline(time.Now().Add(time.Second))
			buf := make([]byte, 64)
			n, addr, err := peer.ReadFromUDP(buf)
			if err != nil || string(buf[:n]) != "route-fixture" {
				conn.Close()
				t.Fatal("explicit socket did not reach loopback")
			}
			peer.WriteToUDP([]byte("reply"), addr)
			conn.SetReadDeadline(time.Now().Add(time.Second))
			n, err = conn.Read(buf)
			conn.Close()
			if err != nil || string(buf[:n]) != "reply" {
				t.Fatal("route reply failed")
			}
		}
	}
	if _, err = net.InterfaceByName("svwmissing9"); err == nil {
		t.Fatal("test interface unexpectedly exists")
	}
	for _, proxy := range []bool{false, true} {
		conn, err := openUDPOnInterface(context.Background(), peer.LocalAddr().(*net.UDPAddr), proxy, "svwmissing9")
		if conn != nil {
			conn.Close()
			t.Fatal("missing NIC fell back to default")
		}
		if !errors.Is(err, egress.ErrBind) {
			t.Fatal("missing NIC not classified as binding failure")
		}
	}
}

func TestSelectedInterfaceToIsolatedFixture(t *testing.T) {
	address, iface := os.Getenv("VOWIFI_TEST_UDP_PEER"), os.Getenv("VOWIFI_TEST_INTERFACE")
	if address == "" || iface == "" {
		t.Skip("requires an isolated UDP echo fixture and explicit interface")
	}
	peer, err := net.ResolveUDPAddr("udp4", address)
	if err != nil {
		t.Fatal(err)
	}
	for _, selected := range []string{"", iface} {
		for _, proxy := range []bool{false, true} {
			ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
			conn, err := openUDPOnInterface(ctx, peer, proxy, selected)
			cancel()
			if err != nil {
				t.Fatal(err)
			}
			conn.SetDeadline(time.Now().Add(3 * time.Second))
			_, err = writeUDP(conn, peer, []byte("svw-route-fixture"))
			if err != nil {
				conn.Close()
				t.Fatal(err)
			}
			buf := make([]byte, 64)
			n, err := conn.Read(buf)
			conn.Close()
			if err != nil || string(buf[:n]) != "svw-route-ok" {
				t.Fatalf("selected=%q proxy=%v: isolated fixture error=%v bytes=%d", selected, proxy, err, n)
			}
		}
	}
}

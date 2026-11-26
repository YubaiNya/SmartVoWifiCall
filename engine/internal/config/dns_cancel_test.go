package config

import (
	"context"
	"errors"
	"net"
	"testing"
	"time"
)

func TestDNSCancellationInterruptsPendingRead(t *testing.T) {
	server, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	defer server.Close()
	ctx, cancel := context.WithTimeout(context.Background(), time.Minute)
	defer cancel()
	done := make(chan error, 1)
	go func() { _, err := ResolveHostIPv4(ctx, "fixture.invalid", server.LocalAddr().String()); done <- err }()
	server.SetReadDeadline(time.Now().Add(time.Second))
	if _, _, err := server.ReadFromUDP(make([]byte, 512)); err != nil {
		t.Fatal(err)
	}
	cancel()
	select {
	case err := <-done:
		if !errors.Is(err, context.Canceled) {
			t.Fatalf("cancellation lost: %v", err)
		}
	case <-time.After(time.Second):
		t.Fatal("DNS read ignored cancellation")
	}
}

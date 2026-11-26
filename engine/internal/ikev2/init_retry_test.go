package ikev2

import (
	"bytes"
	"context"
	"encoding/binary"
	"errors"
	"net"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/YubaiNya/SmartVoWifiCall/engine/internal/simauth"
)

// Loopback-only responder: every assertion inspects the actual transmitted bytes.
func runInitChallenges(t *testing.T, challenges [][]Payload, inspect func(int, Header, []Payload)) error {
	t.Helper()
	server, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	defer server.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 4*time.Second)
	defer cancel()
	done := make(chan error, 1)
	go func() {
		client, err := Connect(ctx, SessionConfig{EPDGAddr: server.LocalAddr().String(), EAPIdentity: "fixture@example.invalid", SimAuth: &simauth.MockProvider{KeyMaterial: []byte("synthetic")}})
		if client != nil {
			client.Close()
		}
		done <- err
	}()
	server.SetDeadline(time.Now().Add(3 * time.Second))
	buf := make([]byte, 4096)
	for i, payloads := range challenges {
		n, addr, err := server.ReadFromUDP(buf)
		if err != nil {
			t.Fatal(err)
		}
		h, received, err := DecodePacket(buf[:n])
		if err != nil {
			t.Fatal(err)
		}
		inspect(i, h, received)
		response := EncodePacket(Header{SPIi: h.SPIi, Exchange: ExchangeIKESAInit, Flags: FlagResponse, MessageID: 0}, payloads)
		if _, err = server.WriteToUDP(response, addr); err != nil {
			t.Fatal(err)
		}
	}
	select {
	case err := <-done:
		return err
	case <-time.After(time.Second):
		cancel()
		<-done
		t.Fatal("challenge sequence did not terminate")
		return nil
	}
}

func cookieNotify(size int, value byte) Payload {
	return Payload{Type: PayloadNotify, Body: buildNotify(16390, bytes.Repeat([]byte{value}, size))}
}
func keNotify(group uint16) Payload {
	return Payload{Type: PayloadNotify, Body: buildNotify(17, []byte{byte(group >> 8), byte(group)})}
}
func rejectInit() []Payload { return []Payload{{Type: PayloadNotify, Body: buildNotify(14, nil)}} }

func TestCookieRetryPreservesAllOtherPayloads(t *testing.T) {
	for _, size := range []int{1, 16, 64} {
		t.Run(strconv.Itoa(size), func(t *testing.T) {
			var initial []Payload
			var spi uint64
			err := runInitChallenges(t, [][]Payload{{cookieNotify(size, 0x51)}, {cookieNotify(size, 0x52)}, rejectInit()}, func(i int, h Header, received []Payload) {
				if i == 0 {
					initial, spi = received, h.SPIi
					return
				}
				if h.SPIi != spi || h.SPIr != 0 || h.MessageID != 0 {
					t.Error("retry changed transaction identity")
				}
				if len(received) != len(initial)+1 || received[0].Type != PayloadNotify {
					t.Fatal("COOKIE must be first")
				}
				_, value, ok := ParseNotify(received[0].Body)
				if !ok || !bytes.Equal(value, bytes.Repeat([]byte{byte(0x50 + i)}, size)) {
					t.Error("COOKIE not echoed exactly")
				}
				for j, p := range initial {
					if p.Type != received[j+1].Type || !bytes.Equal(p.Body, received[j+1].Body) {
						t.Errorf("COOKIE_RETRY_CHANGED_PAYLOAD_%d", p.Type)
					}
				}
			})
			if err == nil || !strings.HasPrefix(err.Error(), "IKE_SA_INIT_REJECTED_NOTIFIES_") {
				t.Fatalf("%v", err)
			}
		})
	}
}

func TestCookieAndDHRetryKeepNonceAndOffer(t *testing.T) {
	var initial []Payload
	var updatedKE []byte
	err := runInitChallenges(t, [][]Payload{{cookieNotify(16, 1)}, {keNotify(DHGroupECP256)}, {cookieNotify(16, 2)}, rejectInit()}, func(i int, h Header, ps []Payload) {
		if i == 0 {
			initial = ps
			return
		}
		if !bytes.Equal(FindPayload(initial, PayloadNonce).Body, FindPayload(ps, PayloadNonce).Body) {
			t.Error("DH retry changed Ni")
		}
		if !bytes.Equal(FindPayload(initial, PayloadSA).Body, FindPayload(ps, PayloadSA).Body) {
			t.Error("DH retry changed offered algorithms")
		}
		ke := FindPayload(ps, PayloadKE).Body
		if i == 1 && !bytes.Equal(ke, FindPayload(initial, PayloadKE).Body) {
			t.Error("COOKIE changed KE")
		}
		if i == 2 {
			if binary.BigEndian.Uint16(ke) != DHGroupECP256 {
				t.Error("requested group not used")
			}
			updatedKE = ke
		}
		if i == 3 && !bytes.Equal(ke, updatedKE) {
			t.Error("COOKIE after group change regenerated KE")
		}
	})
	if err == nil || !strings.HasPrefix(err.Error(), "IKE_SA_INIT_REJECTED_NOTIFIES_") {
		t.Fatalf("%v", err)
	}
}

func TestCookieRetriesAndInvalidChallengesAreBounded(t *testing.T) {
	for _, tc := range []struct {
		name     string
		messages [][]Payload
		want     string
	}{
		{"empty", [][]Payload{{cookieNotify(0, 1)}}, "IKE_COOKIE_INVALID"},
		{"oversized", [][]Payload{{cookieNotify(65, 1)}}, "IKE_COOKIE_INVALID"},
		{"duplicate", [][]Payload{{cookieNotify(16, 1), cookieNotify(16, 2)}}, "IKE_COOKIE_INVALID"},
		{"repeated", [][]Payload{{cookieNotify(16, 1)}, {cookieNotify(16, 2)}, {cookieNotify(16, 3)}}, "IKE_COOKIE_RETRY_EXHAUSTED"},
		{"unoffered_dh", [][]Payload{{keNotify(31)}}, "IKE_DH_GROUP_NOT_OFFERED"},
		{"same_dh", [][]Payload{{keNotify(14)}}, "IKE_DH_RETRY_UNCHANGED"},
		{"changed_twice", [][]Payload{{keNotify(19)}, {keNotify(5)}}, "IKE_DH_RETRY_EXHAUSTED"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			err := runInitChallenges(t, tc.messages, func(int, Header, []Payload) {})
			if err == nil || err.Error() != tc.want {
				t.Fatalf("want %s, got %v", tc.want, err)
			}
		})
	}
}

func TestCookieChallengeDoesNotRestartCancelledConnect(t *testing.T) {
	server, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	defer server.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	done := make(chan error, 1)
	go func() {
		client, err := Connect(ctx, SessionConfig{EPDGAddr: server.LocalAddr().String(), SimAuth: &simauth.MockProvider{KeyMaterial: []byte("synthetic")}})
		if client != nil {
			client.Close()
		}
		done <- err
	}()
	server.SetDeadline(time.Now().Add(time.Second))
	buf := make([]byte, 4096)
	n, addr, err := server.ReadFromUDP(buf)
	if err != nil {
		t.Fatal(err)
	}
	h, _, err := DecodePacket(buf[:n])
	if err != nil {
		t.Fatal(err)
	}
	cancel()
	server.WriteToUDP(EncodePacket(Header{SPIi: h.SPIi, Exchange: ExchangeIKESAInit, Flags: FlagResponse}, []Payload{cookieNotify(16, 1)}), addr)
	select {
	case err := <-done:
		if !errors.Is(err, context.Canceled) {
			t.Fatalf("%v", err)
		}
	case <-time.After(time.Second):
		t.Fatal("cancel did not stop connect")
	}
	server.SetReadDeadline(time.Now().Add(100 * time.Millisecond))
	if _, _, err = server.ReadFromUDP(buf); err == nil {
		t.Fatal("cancelled connect retransmitted")
	}
}

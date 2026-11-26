package runtime

import (
	"context"
	"errors"
	"fmt"
	"net"
	"os"
	"testing"
	"time"

	"github.com/YubaiNya/SmartVoWifiCall/engine/internal/behavior"
	"github.com/YubaiNya/SmartVoWifiCall/engine/internal/config"
	"github.com/YubaiNya/SmartVoWifiCall/engine/internal/ikev2"
	"github.com/YubaiNya/SmartVoWifiCall/engine/internal/simauth"
	"github.com/YubaiNya/SmartVoWifiCall/engine/internal/sip"
)

func TestStopCancelsPendingHandshake(t *testing.T) {
	pc, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	defer pc.Close()
	dir, err := os.MkdirTemp("", "svw")
	if err != nil {
		t.Fatal(err)
	}
	defer os.RemoveAll(dir)
	eng, err := Start(Options{Config: &config.Config{ICCID: "8999999999999999991", MCC: "999", MNC: "99", EPDGAddr: pc.LocalAddr().String()},
		SocketDir: dir, SimAuth: &simauth.MockProvider{KeyMaterial: []byte("fixture")}})
	if err != nil {
		t.Fatal(err)
	}
	defer eng.Stop()
	pc.SetReadDeadline(time.Now().Add(2 * time.Second))
	if _, _, err = pc.ReadFromUDP(make([]byte, 4096)); err != nil {
		t.Fatal(err)
	}
	if result := eng.Reconnect(); result["ok"] != false || result["reason"] != "RECOVERY_IN_PROGRESS" {
		t.Fatalf("duplicate connection attempt accepted: %v", result)
	}
	eng.Stop()
	done := make(chan struct{})
	go func() { eng.Wait(); close(done) }()
	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatal("stop left handshake running")
	}
	if result := eng.Reconnect(); result["ok"] != false || result["reason"] != "STOPPED" {
		t.Fatal(result)
	}
}

func TestFailureClassificationUsesAttemptEvidence(t *testing.T) {
	tests := []struct {
		stage  behavior.State
		err    error
		want   behavior.State
		manual bool
	}{
		{behavior.Discovery, errors.New("fixture DNS error"), behavior.DNSFailure, false},
		{behavior.IKEConnecting, fmt.Errorf("operation: %w", context.DeadlineExceeded), behavior.IKETimeout, false},
		{behavior.IMSRegistering, context.DeadlineExceeded, behavior.IMSFailed, false},
		{behavior.IKEConnecting, &ikev2.AuthRejectedError{Notifies: []uint16{24}, Stage: "final"}, behavior.IKEAuthFailure, true},
		{behavior.IKEConnecting, errors.New("EAP_AKA_SERVER_MAC_MISMATCH"), behavior.IKEAuthFailure, true},
		{behavior.IMSRegistering, &sip.RegistrationError{StatusCode: 403}, behavior.IMSFailed, true},
		{behavior.Unknown, errors.New("irrelevant log says IKE_EXCHANGE_TIMEOUT"), behavior.Unknown, false},
	}
	for _, tc := range tests {
		got := diagnose(tc.stage, tc.err)
		if got.State != tc.want || got.ManualOnly != tc.manual {
			t.Fatalf("%+v", got)
		}
	}
	got := diagnose(behavior.IMSRegistering, &sip.RegistrationError{StatusCode: 503, RetryAfter: time.Hour})
	if got.RetryAfter != time.Hour {
		t.Fatal("carrier backoff lost")
	}
	backoff := diagnose(behavior.IKEConnecting, &ikev2.AuthRejectedError{Notifies: []uint16{10500}, BackoffTrusted: true, RetryAfter: time.Hour})
	if backoff.RetryAfter != time.Hour || backoff.Cause != "EPDG_REJECTED_WITH_BACKOFF" {
		t.Fatal("ePDG timer lost")
	}
	backoff = diagnose(behavior.IKEConnecting, &ikev2.AuthRejectedError{Notifies: []uint16{10500}, RetryAfter: time.Hour})
	if backoff.RetryAfter != 0 {
		t.Fatal("unverified timer trusted")
	}
	final := diagnose(behavior.IKEConnecting, &ikev2.AuthRejectedError{Notifies: []uint16{24}, Stage: "final"})
	if final.Cause != "IKE_FINAL_AUTH_REJECTED" || final.State != behavior.IKEAuthFailure || !final.ManualOnly {
		t.Fatal("final rejection misclassified")
	}
	challenge := diagnose(behavior.IKEConnecting, errors.New("IKE_COOKIE_RETRY_EXHAUSTED"))
	if challenge.State != behavior.IKEChallengeFailure || challenge.Cause != "COOKIE_CHALLENGE_FAILED" || challenge.ManualOnly {
		t.Fatal("COOKIE challenge misclassified as credential failure")
	}
}

func TestLivenessCauseSurvivesClassification(t *testing.T) {
	for cause, want := range map[string]string{
		"DPD_TIMEOUT": "IKE_DPD_TIMEOUT", "PEER_DELETE": "IKE_PEER_DELETED",
		"UDP_READ_ERROR": "IKE_UDP_READ_ERROR", "UDP_WRITE_ERROR": "IKE_UDP_WRITE_ERROR",
		"TUNNEL_LOST": "IKE_TUNNEL_LOST",
	} {
		got := diagnose(behavior.IMSRegistered, errors.New(cause))
		if got.Cause != want || got.ManualOnly || got.RetryDisabled {
			t.Fatalf("lost liveness evidence: %+v", got)
		}
		if cause == "DPD_TIMEOUT" && got.State != behavior.IKETimeout {
			t.Fatal("DPD timeout state lost")
		}
	}
}

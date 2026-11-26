package behavior

import (
	"encoding/json"
	"sync"
	"testing"
	"time"
)

func TestReplayMilestonesAndRecoveryEvidence(t *testing.T) {
	e := New()
	now := time.Unix(100, 0)
	if e.Snapshot(now).State != Unknown {
		t.Fatal("unobserved must be unknown")
	}
	e.BeginAttempt()
	for _, state := range []State{Discovery, IKEConnecting, IPsecEstablished, IMSRegistering} {
		e.Observe(state, "TEST_CALLBACK")
		if e.Snapshot(now).RecoveryResult != "PENDING" {
			t.Fatal("partial success is not registration")
		}
	}
	e.Fail(Failure{State: IMSFailed, Cause: "SIP_REGISTRATION_FAILED"}, now, 0)
	e.BeginAttempt()
	if e.Snapshot(now).RecoveryResult != "PENDING" {
		t.Fatal("accepted recovery is not success")
	}
	e.Observe(IMSRegistered, "SIP_REGISTER_200_AND_CHILD_SA")
	s := e.Snapshot(now)
	if s.RecoveryResult != "VERIFIED_REGISTERED" || s.Failures != 0 || s.CircuitOpen {
		t.Fatalf("%+v", s)
	}
}

func TestBoundedBackoffCircuitAndPerSIMIsolation(t *testing.T) {
	a, b := New(), New()
	now := time.Unix(100, 0)
	for i, want := range []time.Duration{2, 4, 8, 16, 32, 900, 900} {
		a.BeginAttempt()
		d := a.Fail(Failure{State: DNSFailure, Cause: "DNS_LOOKUP_FAILED"}, now, time.Second)
		if d != want*time.Second+time.Second {
			t.Fatalf("attempt %d: %v", i, d)
		}
		if a.CanRequest(now.Add(d-time.Nanosecond)) || !a.CanRequest(now.Add(d)) {
			t.Fatal("cooldown boundary")
		}
		now = now.Add(d)
	}
	if !a.Snapshot(now).CircuitOpen || b.Snapshot(now).Failures != 0 {
		t.Fatal("circuit leaked to another SIM")
	}
	if a.Snapshot(now).Failures != MaxAttempts {
		t.Fatal("failure counter must saturate")
	}
}

func TestAuthNeedsExplicitRequestAndServerBackoffIsLowerBound(t *testing.T) {
	e := New()
	now := time.Unix(100, 0)
	delay := e.Fail(Failure{State: IKEAuthFailure, Cause: "AUTHENTICATION_REJECTED", ManualOnly: true, RetryAfter: time.Hour}, now, 0)
	if delay != time.Hour || e.CanRequest(now.Add(59*time.Minute)) {
		t.Fatal("server throttle bypassed")
	}
	s := e.Snapshot(now.Add(time.Hour))
	if !s.ManualOnly || !s.CircuitOpen || s.Action != "CHECK_CREDENTIALS_OR_CARRIER" {
		t.Fatalf("%+v", s)
	}
}

func TestConcurrentObservationAndSnapshot(t *testing.T) {
	e := New()
	var wg sync.WaitGroup
	for i := 0; i < 8; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for n := 0; n < 100; n++ {
				e.Observe(IMSRegistering, "SIP_REGISTER_STARTED")
				if _, err := json.Marshal(e.Snapshot(time.Now())); err != nil {
					t.Error(err)
				}
			}
		}()
	}
	wg.Wait()
}

func TestServerDisabledCannotBeOverriddenByManualRequest(t *testing.T) {
	e := New()
	now := time.Unix(100, 0)
	e.Fail(Failure{State: Unknown, Cause: "EPDG_REJECTED_WITH_BACKOFF", RetryDisabled: true}, now, 0)
	if e.CanRequest(now.Add(10 * 365 * 24 * time.Hour)) {
		t.Fatal("deactivated server timer became retryable")
	}
	s := e.Snapshot(now)
	if !s.ManualOnly || !s.RetryDisabled || s.Action != "WAIT_SUBSCRIPTION_OR_POWER_CHANGE" {
		t.Fatalf("%+v", s)
	}
}

func BenchmarkSnapshot(b *testing.B) {
	e := New()
	now := time.Unix(100, 0)
	b.ReportAllocs()
	for i := 0; i < b.N; i++ {
		_ = e.Snapshot(now)
	}
}

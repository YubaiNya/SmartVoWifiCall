// Package behavior correlates verified milestones of one owned SIM session.
// It has no device access, persistence, identifiers, or network side effects.
package behavior

import (
	"sync"
	"time"
)

type State string

const (
	Unknown             State = "UNKNOWN"
	Disabled            State = "DISABLED"
	Discovery           State = "EPDG_DISCOVERY"
	DNSFailure          State = "EPDG_DNS_FAILURE"
	IKEConnecting       State = "IKE_CONNECTING"
	IKETimeout          State = "IKE_TIMEOUT"
	IKEAuthFailure      State = "IKE_AUTH_FAILURE"
	IKEChallengeFailure State = "IKE_CHALLENGE_FAILED"
	IPsecEstablished    State = "IPSEC_ESTABLISHED"
	IMSRegistering      State = "IMS_REGISTERING"
	IMSFailed           State = "IMS_REGISTRATION_FAILED"
	IMSRegistered       State = "IMS_REGISTERED_IWLAN"
	ActiveCall          State = "VOWIFI_ACTIVE_CALL"
	Backoff             State = "RETRY_BACKOFF"
	Recovering          State = "RECOVERY_IN_PROGRESS"
)

// Failure contains categories from actual operation results, never guessed log text.
type Failure struct {
	State         State
	Cause         string
	ManualOnly    bool
	RetryAfter    time.Duration
	RetryDisabled bool
}

// Snapshot deliberately excludes protocol payloads and subscriber/network identifiers.
// REGISTERED describes our SIP registration over our verified tunnel, not Android
// MMTEL availability, emergency service support, or a vendor's private IWLAN state.
type Snapshot struct {
	Schema         int    `json:"schema"`
	State          State  `json:"state"`
	FailureState   State  `json:"failureState"`
	Cause          string `json:"cause"`
	Evidence       string `json:"evidence"`
	Confidence     string `json:"confidence"`
	Action         string `json:"recommendedAction"`
	RecoveryResult string `json:"recoveryResult"`
	Attempt        int    `json:"attempt"`
	Failures       int    `json:"consecutiveFailures"`
	CircuitOpen    bool   `json:"circuitOpen"`
	ManualOnly     bool   `json:"manualOnly"`
	RetryDisabled  bool   `json:"retryDisabled"`
	RetryAfterMS   int64  `json:"retryAfterMs"`
}

const MaxAttempts = 6

type Engine struct {
	mu        sync.Mutex
	snapshot  Snapshot
	notBefore time.Time
}

func New() *Engine {
	return &Engine{snapshot: Snapshot{Schema: 1, State: Unknown, FailureState: Unknown,
		Confidence: "UNKNOWN", Evidence: "NONE", Action: "OBSERVE", RecoveryResult: "NOT_ATTEMPTED"}}
}

// Observe is also the offline replay entry point. Only direct callbacks call it.
func (e *Engine) Observe(state State, evidence string) {
	e.mu.Lock()
	defer e.mu.Unlock()
	e.snapshot.State, e.snapshot.Evidence = state, evidence
	e.snapshot.Confidence = "DIRECT"
	if state == IMSRegistered {
		e.snapshot.RecoveryResult = "VERIFIED_REGISTERED"
		e.snapshot.Failures, e.snapshot.CircuitOpen, e.snapshot.ManualOnly = 0, false, false
		e.snapshot.RetryDisabled = false
		e.snapshot.FailureState, e.snapshot.Cause, e.snapshot.Action = Unknown, "", "OBSERVE"
		e.notBefore = time.Time{}
	}
}

func (e *Engine) BeginAttempt() {
	e.mu.Lock()
	defer e.mu.Unlock()
	e.snapshot.Attempt++
	e.snapshot.State = Recovering
	e.snapshot.Evidence, e.snapshot.Confidence = "CONNECT_ATTEMPT_STARTED", "DIRECT"
	e.snapshot.RecoveryResult = "PENDING"
	e.snapshot.Action = "AWAIT_REGISTRATION"
	e.snapshot.CircuitOpen = false
}

// Fail uses bounded exponential delay plus injected positive jitter (0..1s).
// Six consecutive failures open a 15-minute circuit before one further probe.
// Authentication/security failures require an explicit request after cooldown.
// Network Retry-After is a lower bound, including for explicit requests.
func (e *Engine) Fail(f Failure, now time.Time, jitter time.Duration) time.Duration {
	e.mu.Lock()
	defer e.mu.Unlock()
	s := &e.snapshot
	if s.Failures < MaxAttempts {
		s.Failures++
	}
	delay := 2 * time.Second << (s.Failures - 1)
	if delay > time.Minute {
		delay = time.Minute
	}
	if jitter < 0 {
		jitter = 0
	}
	if jitter > time.Second {
		jitter = time.Second
	}
	delay += jitter
	s.CircuitOpen = s.Failures >= MaxAttempts || f.ManualOnly || f.RetryDisabled
	if s.Failures >= MaxAttempts {
		delay = 15*time.Minute + jitter
	}
	if f.ManualOnly && delay < time.Minute {
		delay = time.Minute
	}
	if f.RetryAfter > delay {
		delay = f.RetryAfter
	}
	e.notBefore = now.Add(delay)
	s.State, s.FailureState, s.Cause = Backoff, f.State, f.Cause
	s.Evidence, s.Confidence = "OPERATION_FAILED", "DIRECT"
	s.ManualOnly = f.ManualOnly || f.RetryDisabled
	s.RetryDisabled = f.RetryDisabled
	s.RecoveryResult, s.Action = "FAILED", "WAIT_BACKOFF"
	if f.ManualOnly {
		s.Action = "CHECK_CREDENTIALS_OR_CARRIER"
	}
	if f.Cause == "IKE_FINAL_AUTH_REJECTED" {
		s.Action = "REVIEW_AUTH_OR_CARRIER_POLICY"
	}
	if f.RetryDisabled {
		s.Action = "WAIT_SUBSCRIPTION_OR_POWER_CHANGE"
	}
	return delay
}

func (e *Engine) CanRequest(now time.Time) bool {
	e.mu.Lock()
	defer e.mu.Unlock()
	return !e.snapshot.RetryDisabled && !now.Before(e.notBefore)
}

func (e *Engine) Snapshot(now time.Time) Snapshot {
	e.mu.Lock()
	defer e.mu.Unlock()
	s := e.snapshot
	if now.Before(e.notBefore) {
		s.RetryAfterMS = e.notBefore.Sub(now).Milliseconds()
	}
	return s
}

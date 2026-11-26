package ikev2

import (
	"errors"
	"testing"
	"time"
)

func TestBackoffTimerUnitsAndDeactivation(t *testing.T) {
	for unit, seconds := range []int{600, 3600, 36000, 2, 30, 60, 3600} {
		d, disabled := decodeBackoff(byte(unit<<5 | 2))
		if disabled || d != time.Duration(2*seconds)*time.Second {
			t.Fatalf("unit %d: %v %v", unit, d, disabled)
		}
		zero, off := decodeBackoff(byte(unit << 5))
		if zero != 0 || off {
			t.Fatal("zero is not disabled")
		}
	}
	for _, raw := range []byte{0xe0, 0xff} {
		d, off := decodeBackoff(raw)
		if !off || d != 0 {
			t.Fatal("deactivation lost")
		}
	}
}

func TestBackoffRequiresVerifiedResponderAndErrorCause(t *testing.T) {
	failure := Payload{Type: PayloadNotify, Body: buildNotify(10500, nil)}
	if !hasErrorNotify([]Payload{{Type: PayloadSA}, failure}) || hasErrorNotify([]Payload{{Type: PayloadNotify, Body: buildNotify(NotifyInitialContact, nil)}}) {
		t.Fatal("error/status notification distinction lost")
	}
	timer := Payload{Type: PayloadNotify, Body: buildNotify(notifyBackoffTimer, []byte{1, 0x21})}
	for _, trusted := range []bool{false, true} {
		var err *AuthRejectedError
		if !errors.As(authRejection([]Payload{failure, timer}, "final", trusted), &err) {
			t.Fatal("wrong error type")
		}
		if err.BackoffTrusted != trusted {
			t.Fatal("trust boundary lost")
		}
		if trusted && err.RetryAfter != time.Hour {
			t.Fatal("timer missing")
		}
		if !trusted && (err.RetryAfter != 0 || err.RetryDisabled) {
			t.Fatal("unauthenticated peer dictated cooldown")
		}
	}
	var noCause *AuthRejectedError
	errors.As(authRejection([]Payload{timer}, "final", true), &noCause)
	if noCause.BackoffTrusted {
		t.Fatal("unassociated timer accepted")
	}
	for _, payloads := range [][]Payload{
		{failure, timer, timer},
		{failure, {Type: PayloadNotify, Body: buildNotify(notifyBackoffTimer, []byte{2, 0x21})}},
		{failure, {Type: PayloadNotify, Body: buildNotify(notifyBackoffTimer, []byte{1})}},
	} {
		if err := authRejection(payloads, "final", true); err.Error() != "IKE_BACKOFF_TIMER_INVALID" {
			t.Fatalf("%v", err)
		}
	}
	var disabled *AuthRejectedError
	errors.As(authRejection([]Payload{failure, {Type: PayloadNotify, Body: buildNotify(notifyBackoffTimer, []byte{1, 0xe0})}}, "final", true), &disabled)
	if !disabled.RetryDisabled {
		t.Fatal("disabled timer converted to fast retry")
	}
}

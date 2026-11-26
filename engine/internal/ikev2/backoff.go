package ikev2

import (
	"errors"
	"time"
)

const notifyBackoffTimer uint16 = 41041

func hasErrorNotify(payloads []Payload) bool {
	for _, p := range payloads {
		if p.Type == PayloadNotify {
			n, _, ok := ParseNotify(p.Body)
			if ok && n > 0 && n < 16384 {
				return true
			}
		}
	}
	return false
}

// A timer carried inside an encrypted exchange is not automatically from an
// authenticated ePDG. Only a verified responder AUTH may set the policy fields.
func authRejection(payloads []Payload, stage string, authenticated bool) error {
	out := &AuthRejectedError{Stage: stage}
	var timer []byte
	hasError := false
	for _, p := range payloads {
		if p.Type != PayloadNotify {
			continue
		}
		n, data, ok := ParseNotify(p.Body)
		if !ok {
			continue
		}
		out.Notifies = append(out.Notifies, n)
		if n > 0 && n < 16384 {
			hasError = true
		}
		if n == notifyBackoffTimer && authenticated {
			if timer != nil || len(p.Body) != 6 || p.Body[0] != 0 || p.Body[1] != 0 || len(data) != 2 || data[0] != 1 {
				return errors.New("IKE_BACKOFF_TIMER_INVALID")
			}
			timer = data
		}
	}
	if authenticated && hasError && timer != nil {
		out.RetryAfter, out.RetryDisabled = decodeBackoff(timer[1])
		out.BackoffTrusted = true
	}
	return out
}

// TS 24.302 8.2.9.1 uses the GPRS timer 3 value encoding (TS 24.008 10.5.7.4a).
// Unit 111 is deactivated: it forbids retry until the relevant SIM/power reset,
// not a zero-second retry. Zero in another unit imposes no server delay.
// Unit 110's 320-hour exception is for extended T3312/T3412/T3512, not Tw3.
func decodeBackoff(value byte) (time.Duration, bool) {
	units := [...]time.Duration{10 * time.Minute, time.Hour, 10 * time.Hour, 2 * time.Second, 30 * time.Second, time.Minute, time.Hour}
	unit, count := value>>5, value&31
	if unit == 7 {
		return 0, true
	}
	return time.Duration(count) * units[unit], false
}

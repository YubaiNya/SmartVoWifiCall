package sip

import (
	"fmt"
	"strconv"
	"strings"
	"time"
)

// RegistrationError retains only the status and server retry interval.
type RegistrationError struct {
	StatusCode int
	RetryAfter time.Duration
}

func (e *RegistrationError) Error() string {
	return fmt.Sprintf("SIP_REGISTER_STATUS_%d", e.StatusCode)
}

func deltaSeconds(value string) (time.Duration, bool) {
	value = strings.TrimSpace(value)
	if value == "" {
		return 0, false
	}
	for _, ch := range value {
		if ch < '0' || ch > '9' {
			return 0, false
		}
	}
	n, err := strconv.ParseUint(value, 10, 32)
	if err != nil {
		return 0, false
	}
	return time.Duration(n) * time.Second, true
}

func retryAfter(resp *Message) time.Duration {
	// RFC 3261 20.33 permits a comment and optional parameters after delta-seconds.
	value := strings.TrimSpace(resp.GetHeader("Retry-After"))
	if i := strings.IndexAny(value, " (;\t"); i >= 0 {
		value = value[:i]
	}
	d, _ := deltaSeconds(value)
	return d
}

func contactValues(value string) []string {
	var out []string
	quoted, escaped, angle, start := false, false, false, 0
	for i, ch := range value {
		if escaped {
			escaped = false
			continue
		}
		if quoted && ch == '\\' {
			escaped = true
			continue
		}
		if ch == '"' {
			quoted = !quoted
		}
		if !quoted {
			if ch == '<' {
				angle = true
			}
			if ch == '>' {
				angle = false
			}
			if ch == ',' && !angle {
				out = append(out, strings.TrimSpace(value[start:i]))
				start = i + 1
			}
		}
	}
	return append(out, strings.TrimSpace(value[start:]))
}

// Select only this client's Contact. A different binding's expiration is not
// evidence for ours. Missing expiry is explicitly unknown, with a conservative
// refresh fallback; zero or malformed explicit expiry never establishes service.
func registrationLifetime(resp *Message, ownContact string, requested int) (time.Duration, bool, error) {
	contacts := resp.GetAllHeaders("Contact")
	matched := len(contacts) == 0
	for _, header := range contacts {
		for _, contact := range contactValues(header) {
			if !sameContactURI(extractURI(contact), extractURI(ownContact)) {
				continue
			}
			matched = true
			params := contact
			if end := strings.IndexByte(contact, '>'); end >= 0 {
				params = contact[end+1:]
			}
			for _, param := range strings.Split(params, ";")[1:] {
				kv := strings.SplitN(strings.TrimSpace(param), "=", 2)
				if strings.EqualFold(kv[0], "expires") {
					if len(kv) != 2 {
						return 0, false, fmt.Errorf("SIP_REGISTRATION_EXPIRY_INVALID")
					}
					d, ok := deltaSeconds(kv[1])
					if !ok || d <= 0 {
						return 0, false, fmt.Errorf("SIP_REGISTRATION_EXPIRY_INVALID")
					}
					return d, true, nil
				}
			}
		}
	}
	if !matched {
		return 0, false, fmt.Errorf("SIP_REGISTRATION_CONTACT_MISSING")
	}
	if value := resp.GetHeader("Expires"); value != "" {
		d, ok := deltaSeconds(value)
		if !ok || d <= 0 {
			return 0, false, fmt.Errorf("SIP_REGISTRATION_EXPIRY_INVALID")
		}
		return d, true, nil
	}
	// Some IMS implementations omit expiry. Never claim the requested value was
	// granted; use a short local refresh interval and expose expiryKnown=false.
	d := time.Duration(requested) * time.Second
	if d > 5*time.Minute {
		d = 5 * time.Minute
	}
	return d, false, nil
}

func refreshDelay(lifetime time.Duration) time.Duration {
	d := lifetime - lifetime/5
	if d > 25*time.Minute {
		d = 25 * time.Minute
	}
	return d
}

func (c *Client) RegistrationRefreshDelay() time.Duration {
	c.mu.Lock()
	defer c.mu.Unlock()
	d := time.Until(c.refreshAt)
	if d < 0 {
		return 0
	}
	return d
}

func (c *Client) RegistrationExpiryKnown() bool {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.expiryKnown
}

// TryBeginRecovery atomically excludes new incoming/outgoing dialogs. Checking
// a UI snapshot and closing later leaves a race with a call starting in between.
func (c *Client) TryBeginRecovery() bool {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.activeCall != nil || c.closed {
		return false
	}
	c.recovering = true
	return true
}

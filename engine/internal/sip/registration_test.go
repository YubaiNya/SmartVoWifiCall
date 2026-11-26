package sip

import (
	"context"
	"sync"
	"testing"
	"time"
)

func TestRegistrationLifetimeSelectsOwnContact(t *testing.T) {
	own := "<sip:fixture@[2001:db8::2]:5060;transport=udp>"
	tests := []struct {
		name, contact, expires string
		seconds                int
		known, bad             bool
	}{
		{"short", own + ";expires=60", "3600", 60, true, false},
		{"equivalent", "<sip:%66ixture@[2001:0db8:0:0:0:0:0:2]:5060;Transport=UDP>;expires=60", "3600", 60, true, false},
		{"multiple", `"other, device" <sip:other@host>;expires=1, ` + own + ";expires=120", "", 120, true, false},
		{"header", own, "90", 90, true, false},
		{"unknown", "", "", 300, false, false},
		{"other", "<sip:other@host>;expires=3600", "3600", 0, false, true},
		{"removed", own + ";expires=0", "3600", 0, false, true},
		{"invalid", own + ";expires=invalid", "3600", 0, false, true},
		{"negative", own + ";expires=-1", "", 0, false, true},
		{"overflow", own + ";expires=99999999999999999999", "", 0, false, true},
		{"header invalid", own, "bad", 0, false, true},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			m := &Message{}
			if tc.contact != "" {
				m.AddHeader("m", tc.contact)
			}
			if tc.expires != "" {
				m.AddHeader("Expires", tc.expires)
			}
			d, k, err := registrationLifetime(m, own, 600000)
			if (err != nil) != tc.bad {
				t.Fatalf("error=%v", err)
			}
			if err == nil && (d != time.Duration(tc.seconds)*time.Second || k != tc.known) {
				t.Fatalf("%v known=%v", d, k)
			}
		})
	}
}

func TestRefreshBeforeExpirationAndExpiryInvalidatesService(t *testing.T) {
	if refreshDelay(60*time.Second) != 48*time.Second {
		t.Fatal("short registrar lifetime ignored")
	}
	if refreshDelay(time.Duration(4294967295)*time.Second) != 25*time.Minute {
		t.Fatal("duration overflow")
	}
	c := &Client{registered: true, registeredUntil: time.Now().Add(-time.Second)}
	if c.Registered() {
		t.Fatal("expired binding advertised")
	}
	if _, err := c.Dial(context.Background(), "tel:123"); err == nil {
		t.Fatal("expired registration allowed a call")
	}
}

func TestRetryAfterParsing(t *testing.T) {
	for _, tc := range []struct {
		value string
		want  time.Duration
	}{
		{"120 (overload);duration=60", 120 * time.Second}, {"0", 0}, {"-1", 0}, {"bad", 0}, {"99999999999999999999", 0},
	} {
		m := &Message{}
		m.AddHeader("Retry-After", tc.value)
		if d := retryAfter(m); d != tc.want {
			t.Fatalf("%s: %v", tc.value, d)
		}
	}
}

func TestRecoveryAndCallStartAreMutuallyExclusive(t *testing.T) {
	for _, stage := range []string{"DIALING", "RINGING", "CONNECTING", "IN_CALL", "ENDING", "UNKNOWN"} {
		c := &Client{activeCall: &callDialog{stage: stage}}
		if c.TryBeginRecovery() {
			t.Fatalf("recovery during %s", stage)
		}
	}
	c := &Client{registered: true}
	if !c.TryBeginRecovery() {
		t.Fatal("idle recovery rejected")
	}
	if _, err := c.Dial(context.Background(), "tel:123"); err == nil {
		t.Fatal("call started after recovery reservation")
	}
	// Both operations take the same SIP lock; whichever wins excludes the other.
	for i := 0; i < 100; i++ {
		c := &Client{}
		var wg sync.WaitGroup
		wg.Add(2)
		go func() { defer wg.Done(); c.TryBeginRecovery() }()
		go func() {
			defer wg.Done()
			c.mu.Lock()
			defer c.mu.Unlock()
			if !c.recovering {
				c.activeCall = &callDialog{stage: "RINGING"}
			}
		}()
		wg.Wait()
		if c.recovering && c.activeCall != nil {
			t.Fatal("recovery raced with call")
		}
	}
}

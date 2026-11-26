package sip

import "testing"

func TestRegistrationContactEquivalence(t *testing.T) {
	for _, tc := range []struct {
		a, b  string
		equal bool
	}{
		{"sip:%61lice@atlanta.com;transport=TCP", "sip:alice@AtLanTa.CoM;Transport=tcp", true},
		{"sip:carol@chicago.com", "sip:carol@chicago.com;newparam=5", true},
		{"sip:alice@host:5060;transport=udp;foo=bar", "sip:alice@HOST:5060;foo=bar;transport=UDP", true},
		{"sip:alice@[2001:db8::1]:5060;transport=udp", "sip:alice@[2001:0DB8:0:0:0:0:0:1]:5060;transport=udp", true},
		{"sip:ALICE@host;transport=udp", "sip:alice@host;transport=udp", false},
		{"sip:bob@host", "sip:bob@host:5060", false},
		{"sip:bob@host", "sip:bob@host;transport=udp", false},
		{"sip:bob@host;transport=udp", "sip:bob@host;transport=tcp", false},
		{"sip:bob@host:5060", "sip:bob@host:5061", false},
		{"sip:bob@host", "sips:bob@host", false},
		{"sip:bob@host", "sip:bob@host;user=phone", false},
		{"sip:bob@host", "sip:bob@host;maddr=other", false},
		{"sip:bob@host;foo=a", "sip:bob@host;foo=b", false},
		{"sip:bob@host;transport=udp;transport=tcp", "sip:bob@host;transport=udp", false},
		{"sip:bob@host?subject=a", "sip:bob@host", false},
		{"sip:%GG@host", "sip:%GG@host", false},
		{"sip:bob@host:65536", "sip:bob@host:65536", false},
	} {
		if got := sameContactURI(tc.a, tc.b); got != tc.equal {
			t.Errorf("%q vs %q: %v", tc.a, tc.b, got)
		}
		if got := sameContactURI(tc.b, tc.a); got != tc.equal {
			t.Error("comparison not symmetric")
		}
	}
}

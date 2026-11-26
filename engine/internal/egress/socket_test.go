package egress

import (
	"errors"
	"syscall"
	"testing"
)

type deniedSocket struct{ called bool }

func (s *deniedSocket) Control(f func(uintptr)) error {
	s.called = true
	return errors.New("fixture denied")
}
func (*deniedSocket) Read(func(uintptr) bool) error  { return nil }
func (*deniedSocket) Write(func(uintptr) bool) error { return nil }

var _ syscall.RawConn = (*deniedSocket)(nil)

func TestSystemRouteDoesNotOverrideSocket(t *testing.T) {
	if Control("") != nil {
		t.Fatal("system routing installed a socket override")
	}
}

func TestExplicitInterfaceNeverFallsBack(t *testing.T) {
	for _, name := range []string{"../wlan0", ".", "..", "wlan0\x00", "1234567890123456", "@system"} {
		socket := &deniedSocket{}
		if !errors.Is(Control(name)("udp4", "127.0.0.1:1", socket), ErrBind) || socket.called {
			t.Fatal("invalid interface reached socket or silently followed default route")
		}
	}
	socket := &deniedSocket{}
	if !errors.Is(Control("wlan0")("udp4", "127.0.0.1:1", socket), ErrBind) || !socket.called {
		t.Fatal("failed explicit binding must fail the attempt")
	}
}

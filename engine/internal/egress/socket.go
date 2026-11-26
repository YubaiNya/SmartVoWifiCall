// Package egress applies an explicit interface only when the user selected one.
package egress

import (
	"errors"
	"regexp"
	"syscall"
)

var ErrBind = errors.New("EGRESS_BIND_FAILED")
var interfaceName = regexp.MustCompile(`^[A-Za-z0-9_.-]{1,15}$`)

func ValidInterface(name string) bool {
	return interfaceName.MatchString(name) && name != "." && name != ".."
}

func Control(iface string) func(string, string, syscall.RawConn) error {
	if iface == "" {
		return nil
	}
	return func(_, _ string, raw syscall.RawConn) error {
		if !ValidInterface(iface) {
			return ErrBind
		}
		var bindErr error
		if err := raw.Control(func(fd uintptr) { bindErr = bindDevice(fd, iface) }); err != nil {
			return ErrBind
		}
		if bindErr != nil {
			return ErrBind
		}
		return nil
	}
}

//go:build linux

package egress

import "syscall"

func bindDevice(fd uintptr, iface string) error {
	return syscall.SetsockoptString(int(fd), syscall.SOL_SOCKET, syscall.SO_BINDTODEVICE, iface)
}

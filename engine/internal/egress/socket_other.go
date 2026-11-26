//go:build !linux

package egress

func bindDevice(_ uintptr, _ string) error { return ErrBind }

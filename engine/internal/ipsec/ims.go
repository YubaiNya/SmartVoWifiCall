package ipsec

import (
	"errors"
	"net"
)

// UDPProtection is one bidirectional pair of IMS transport-mode ESP SAs.
type UDPProtection struct {
	PeerIP                net.IP
	LocalPort, RemotePort uint16
	In, Out               *SA
}

func (t *Tunnel) InstallUDPProtection(p *UDPProtection) error {
	if p == nil || p.In == nil || p.Out == nil || p.PeerIP.To16() == nil || p.LocalPort == 0 || p.RemotePort == 0 {
		return errors.New("IMS_ESP_CONFIG_INVALID")
	}
	t.mu.Lock()
	defer t.mu.Unlock()
	if old := t.udpSecurity[p.LocalPort]; old != nil {
		delete(t.inSecurity, old.In.SPI)
	}
	t.udpSecurity[p.LocalPort] = p
	t.inSecurity[p.In.SPI] = p
	return nil
}
func (t *Tunnel) RemoveUDPProtection(port uint16) {
	t.mu.Lock()
	defer t.mu.Unlock()
	if p := t.udpSecurity[port]; p != nil {
		delete(t.inSecurity, p.In.SPI)
		delete(t.udpSecurity, port)
	}
}

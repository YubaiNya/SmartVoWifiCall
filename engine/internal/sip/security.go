package sip

import (
	"crypto/rand"
	"encoding/binary"
	"errors"
	"fmt"
	"github.com/YubaiNya/SmartVoWifiCall/engine/internal/ipsec"
	"strconv"
	"strings"
)

// RFC 3329 / TS 33.203: protected client/server ports each have an inbound SA.
type imsSecurity struct {
	client, server         *ipsec.UDPSocket
	spiClient, spiServer   uint32
	peerClient, peerServer uint16
	verify                 string
	active                 bool
}

func (c *Client) prepareSecurity() error {
	a, err := c.cfg.Tunnel.BindUDP(0)
	if err != nil {
		return err
	}
	b, err := c.cfg.Tunnel.BindUDP(0)
	if err != nil {
		a.Close()
		return err
	}
	var random [8]byte
	if _, err = rand.Read(random[:]); err != nil {
		a.Close()
		b.Close()
		return err
	}
	c.security = &imsSecurity{client: a, server: b, spiClient: binary.BigEndian.Uint32(random[:4]) | 0x100, spiServer: binary.BigEndian.Uint32(random[4:]) | 0x200}
	if c.security.spiClient == c.security.spiServer {
		c.security.spiServer ^= 0x400
	}
	return nil
}
func (c *Client) securityOffer() string {
	s := c.security
	return fmt.Sprintf("ipsec-3gpp;prot=esp;mod=trans;alg=hmac-sha-1-96;ealg=null;spi-c=%d;spi-s=%d;port-c=%d;port-s=%d", s.spiClient, s.spiServer, s.client.LocalPort(), s.server.LocalPort())
}
func (c *Client) localPort() uint16 {
	if port := c.protectedPort.Load(); port != 0 {
		return uint16(port)
	}
	return c.sock.LocalPort()
}
func (c *Client) contactPort() uint16 {
	if c.security != nil {
		return c.security.server.LocalPort()
	}
	return c.sock.LocalPort()
}
func (c *Client) acceptSecurity(response *Message, ik []byte) error {
	if c.security == nil {
		return nil
	}
	values := response.GetAllHeaders("Security-Server")
	if len(values) == 0 {
		return errors.New("SIP_SECURITY_SERVER_MISSING")
	}
	var selected map[string]string
	for _, value := range values {
		for _, entry := range strings.Split(value, ",") {
			parts := strings.Split(entry, ";")
			if !strings.EqualFold(strings.TrimSpace(parts[0]), "ipsec-3gpp") {
				continue
			}
			p := map[string]string{}
			for _, part := range parts[1:] {
				kv := strings.SplitN(strings.TrimSpace(part), "=", 2)
				if len(kv) == 2 {
					p[strings.ToLower(kv[0])] = strings.Trim(kv[1], "\"")
				}
			}
			if strings.EqualFold(p["alg"], "hmac-sha-1-96") && (p["ealg"] == "" || strings.EqualFold(p["ealg"], "null")) && (p["prot"] == "" || strings.EqualFold(p["prot"], "esp")) && (p["mod"] == "" || strings.EqualFold(p["mod"], "trans")) {
				selected = p
				break
			}
		}
	}
	if selected == nil {
		return errors.New("SIP_SECURITY_MECHANISM_UNSUPPORTED")
	}
	nums := map[string]uint64{}
	for _, key := range []string{"spi-c", "spi-s", "port-c", "port-s"} {
		bits := 32
		if strings.HasPrefix(key, "port") {
			bits = 16
		}
		n, err := strconv.ParseUint(selected[key], 10, bits)
		if err != nil || n == 0 {
			return errors.New("SIP_SECURITY_PARAMETER_INVALID")
		}
		nums[key] = n
	}
	s := c.security
	inC, err := ipsec.NewSA(s.spiClient, ipsec.SuiteNullHMACSHA1, nil, ik, nil)
	if err != nil {
		return err
	}
	inS, err := ipsec.NewSA(s.spiServer, ipsec.SuiteNullHMACSHA1, nil, ik, nil)
	if err != nil {
		return err
	}
	outC, err := ipsec.NewSA(uint32(nums["spi-c"]), ipsec.SuiteNullHMACSHA1, nil, ik, nil)
	if err != nil {
		return err
	}
	outS, err := ipsec.NewSA(uint32(nums["spi-s"]), ipsec.SuiteNullHMACSHA1, nil, ik, nil)
	if err != nil {
		return err
	}
	if err = c.cfg.Tunnel.InstallUDPProtection(&ipsec.UDPProtection{PeerIP: c.pcscf, LocalPort: s.client.LocalPort(), RemotePort: uint16(nums["port-s"]), In: inC, Out: outS}); err != nil {
		return err
	}
	if err = c.cfg.Tunnel.InstallUDPProtection(&ipsec.UDPProtection{PeerIP: c.pcscf, LocalPort: s.server.LocalPort(), RemotePort: uint16(nums["port-c"]), In: inS, Out: outC}); err != nil {
		c.cfg.Tunnel.RemoveUDPProtection(s.client.LocalPort())
		return err
	}
	c.mu.Lock()
	s.peerClient = uint16(nums["port-c"])
	s.peerServer = uint16(nums["port-s"])
	s.verify = strings.Join(values, ", ")
	s.active = true
	c.protectedPort.Store(uint32(s.client.LocalPort()))
	c.mu.Unlock()
	return nil
}

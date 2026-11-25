package ipsec

import (
	"crypto/rand"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net"
	"sync"
	"sync/atomic"
	"time"
)

const (
	ProtoTCP = 6
	ProtoUDP = 17
)

// PacketTransport is the outer NAT-T UDP transport carrying ESP packets (non-zero SPI first 4 bytes).
type PacketTransport interface {
	SendESP(esp []byte) error
}

// UDPPacket represents a received inner UDP datagram from inside the IPsec tunnel.
type UDPPacket struct {
	SrcIP   net.IP
	SrcPort uint16
	DstPort uint16
	Payload []byte
}

// UDPSocket is a virtual inner-tunnel UDP socket (used by IMS SIP UDP and RTP/RTCP).
type UDPSocket struct {
	tunnel    *Tunnel
	localPort uint16
	rx        chan UDPPacket
	closed    atomic.Bool
}

func (u *UDPSocket) LocalPort() uint16 { return u.localPort }

func (u *UDPSocket) ReadFrom(deadline time.Time) (UDPPacket, error) {
	if u.closed.Load() {
		return UDPPacket{}, io.EOF
	}
	if deadline.IsZero() {
		pkt, ok := <-u.rx
		if !ok {
			return UDPPacket{}, io.EOF
		}
		return pkt, nil
	}
	timer := time.NewTimer(time.Until(deadline))
	defer timer.Stop()
	select {
	case pkt, ok := <-u.rx:
		if !ok {
			return UDPPacket{}, io.EOF
		}
		return pkt, nil
	case <-timer.C:
		return UDPPacket{}, osTimeoutErr{}
	}
}

func (u *UDPSocket) WriteTo(dstIP net.IP, dstPort uint16, payload []byte) error {
	if u.closed.Load() {
		return net.ErrClosed
	}
	return u.tunnel.SendUDP(u.localPort, dstIP, dstPort, payload)
}

func (u *UDPSocket) Close() error {
	if u.closed.Swap(true) {
		return nil
	}
	u.tunnel.unbindUDP(u.localPort)
	close(u.rx)
	return nil
}

type osTimeoutErr struct{}

func (osTimeoutErr) Error() string   { return "i/o timeout" }
func (osTimeoutErr) Timeout() bool   { return true }
func (osTimeoutErr) Temporary() bool { return true }

// Tunnel carries inner IPv4 or IPv6 UDP/TCP over userspace ESP.
type Tunnel struct {
	mu          sync.RWMutex
	localIP     net.IP
	outSA       *SA
	inSA        *SA
	transport   PacketTransport
	udpPorts    map[uint16]*UDPSocket
	udpSecurity map[uint16]*UDPProtection
	inSecurity  map[uint32]*UDPProtection
	tcpConns    map[tcpKey]*VirtualTCPConn
	nextPort    uint16
	ipID        atomic.Uint32
	closed      atomic.Bool
}

type tcpKey struct {
	localPort  uint16
	remotePort uint16
	remoteIP   [16]byte
}

func NewTunnel(localIP net.IP, outSA, inSA *SA, transport PacketTransport) (*Tunnel, error) {
	ip := localIP.To16()
	if v4 := localIP.To4(); v4 != nil {
		ip = v4
	}
	if ip == nil {
		return nil, errors.New("TUNNEL_IP_REQUIRED")
	}
	return &Tunnel{
		localIP:     append(net.IP(nil), ip...),
		outSA:       outSA,
		inSA:        inSA,
		transport:   transport,
		udpPorts:    make(map[uint16]*UDPSocket),
		udpSecurity: make(map[uint16]*UDPProtection),
		inSecurity:  make(map[uint32]*UDPProtection),
		tcpConns:    make(map[tcpKey]*VirtualTCPConn),
		nextPort:    32000,
	}, nil
}

func (t *Tunnel) LocalIP() net.IP {
	t.mu.RLock()
	defer t.mu.RUnlock()
	return append(net.IP(nil), t.localIP...)
}

// Rekey atomically swaps inbound/outbound Child SAs during IKEv2 Child SA rekeying.
func (t *Tunnel) Rekey(outSA, inSA *SA) {
	t.mu.Lock()
	t.outSA = outSA
	t.inSA = inSA
	t.mu.Unlock()
}

func (t *Tunnel) BindUDP(port uint16) (*UDPSocket, error) {
	t.mu.Lock()
	defer t.mu.Unlock()
	if port == 0 {
		for tries := 0; tries < 20000; tries++ {
			cand := t.nextPort
			t.nextPort++
			if t.nextPort >= 60000 {
				t.nextPort = 32000
			}
			if _, exists := t.udpPorts[cand]; !exists {
				port = cand
				break
			}
		}
		if port == 0 {
			return nil, errors.New("TUNNEL_NO_FREE_PORT")
		}
	} else if _, exists := t.udpPorts[port]; exists {
		return nil, fmt.Errorf("TUNNEL_PORT_IN_USE_%d", port)
	}
	s := &UDPSocket{
		tunnel:    t,
		localPort: port,
		rx:        make(chan UDPPacket, 128),
	}
	t.udpPorts[port] = s
	return s, nil
}

func (t *Tunnel) unbindUDP(port uint16) {
	t.mu.Lock()
	delete(t.udpPorts, port)
	t.mu.Unlock()
}

func (t *Tunnel) SendUDP(srcPort uint16, dstIP net.IP, dstPort uint16, payload []byte) error {
	if dstIP.To16() == nil || (dstIP.To4() == nil) != (t.localIP.To4() == nil) {
		return errors.New("TUNNEL_ADDRESS_FAMILY_MISMATCH")
	}
	udpLen := 8 + len(payload)
	udp := make([]byte, udpLen)
	binary.BigEndian.PutUint16(udp[0:2], srcPort)
	binary.BigEndian.PutUint16(udp[2:4], dstPort)
	binary.BigEndian.PutUint16(udp[4:6], uint16(udpLen))
	copy(udp[8:], payload)

	t.mu.RLock()
	srcIP := t.localIP
	outSA := t.outSA
	tr := t.transport
	security := t.udpSecurity[srcPort]
	t.mu.RUnlock()

	checksum := transportChecksum(srcIP, dstIP, ProtoUDP, udp)
	if checksum == 0 {
		checksum = 0xffff
	}
	binary.BigEndian.PutUint16(udp[6:8], checksum)
	proto := byte(ProtoUDP)
	if security != nil {
		if !security.PeerIP.Equal(dstIP) || security.RemotePort != dstPort {
			return errors.New("IMS_ESP_SELECTOR_MISMATCH")
		}
		var err error
		udp, err = security.Out.Encrypt(ProtoUDP, udp)
		if err != nil {
			return err
		}
		proto = 50
	}
	ipPkt, nextHeader := buildIP(srcIP, dstIP, proto, uint16(t.ipID.Add(1)), udp)
	esp, err := outSA.Encrypt(nextHeader, ipPkt)
	if err != nil {
		return err
	}
	return tr.SendESP(esp)
}

func (t *Tunnel) sendTCP(srcPort uint16, dstIP net.IP, dstPort uint16, seq, ack uint32, flags byte, window uint16, payload []byte) error {
	if dstIP.To16() == nil || (dstIP.To4() == nil) != (t.localIP.To4() == nil) {
		return errors.New("TUNNEL_ADDRESS_FAMILY_MISMATCH")
	}
	seg := make([]byte, 20+len(payload))
	binary.BigEndian.PutUint16(seg[0:2], srcPort)
	binary.BigEndian.PutUint16(seg[2:4], dstPort)
	binary.BigEndian.PutUint32(seg[4:8], seq)
	binary.BigEndian.PutUint32(seg[8:12], ack)
	seg[12] = 0x50 // 20-byte header
	seg[13] = flags
	binary.BigEndian.PutUint16(seg[14:16], window)
	copy(seg[20:], payload)

	t.mu.RLock()
	srcIP := t.localIP
	outSA := t.outSA
	tr := t.transport
	t.mu.RUnlock()

	binary.BigEndian.PutUint16(seg[16:18], transportChecksum(srcIP, dstIP, ProtoTCP, seg))
	ipPkt, nextHeader := buildIP(srcIP, dstIP, ProtoTCP, uint16(t.ipID.Add(1)), seg)
	esp, err := outSA.Encrypt(nextHeader, ipPkt)
	if err != nil {
		return err
	}
	return tr.SendESP(esp)
}

// HandleESP decrypts an incoming ESP packet and demultiplexes its inner UDP/TCP payload.
func (t *Tunnel) HandleESP(esp []byte) error {
	t.mu.RLock()
	inSA := t.inSA
	t.mu.RUnlock()
	if inSA == nil {
		return errors.New("TUNNEL_NO_IN_SA")
	}
	nextHdr, inner, err := inSA.Decrypt(esp)
	if err != nil {
		return err
	}
	var proto byte
	var srcIP, dstIP net.IP
	var body []byte
	if nextHdr == 4 && len(inner) >= 20 && inner[0]>>4 == 4 {
		ihl := int(inner[0]&15) * 4
		total := int(binary.BigEndian.Uint16(inner[2:4]))
		if ihl < 20 || total < ihl || total > len(inner) {
			return nil
		}
		proto, srcIP, dstIP, body = inner[9], net.IP(inner[12:16]), net.IP(inner[16:20]), inner[ihl:total]
	} else if nextHdr == 41 && len(inner) >= 40 && inner[0]>>4 == 6 {
		total := 40 + int(binary.BigEndian.Uint16(inner[4:6]))
		if total > len(inner) {
			return nil
		}
		proto, srcIP, dstIP, body = inner[6], net.IP(inner[8:24]), net.IP(inner[24:40]), inner[40:total]
	} else {
		return nil
	}
	if !dstIP.Equal(t.localIP) {
		return nil
	}
	var protection *UDPProtection
	if proto == 50 {
		if len(body) < 8 {
			return errors.New("IMS_ESP_SHORT")
		}
		t.mu.RLock()
		protection = t.inSecurity[binary.BigEndian.Uint32(body[:4])]
		t.mu.RUnlock()
		if protection == nil || !protection.PeerIP.Equal(srcIP) {
			return errors.New("IMS_ESP_UNKNOWN_SA")
		}
		proto, body, err = protection.In.Decrypt(body)
		if err != nil {
			return err
		}
		if proto != ProtoUDP {
			return errors.New("IMS_ESP_PROTOCOL_INVALID")
		}
	}

	switch proto {
	case ProtoUDP:
		if len(body) < 8 {
			return nil
		}
		srcPort := binary.BigEndian.Uint16(body[0:2])
		dstPort := binary.BigEndian.Uint16(body[2:4])
		if protection != nil && (dstPort != protection.LocalPort || srcPort != protection.RemotePort) {
			return errors.New("IMS_ESP_SELECTOR_MISMATCH")
		}
		t.mu.RLock()
		required := t.udpSecurity[dstPort]
		t.mu.RUnlock()
		if required != nil && (protection != required || srcPort != required.RemotePort) {
			return errors.New("IMS_ESP_REQUIRED")
		}
		ulen := int(binary.BigEndian.Uint16(body[4:6]))
		if ulen < 8 || ulen > len(body) {
			return nil
		}
		if nextHdr == 41 && (binary.BigEndian.Uint16(body[6:8]) == 0 || transportChecksum(srcIP, dstIP, ProtoUDP, body[:ulen]) != 0) {
			return errors.New("TUNNEL_UDP6_CHECKSUM_INVALID")
		}
		t.mu.RLock()
		sock := t.udpPorts[dstPort]
		if sock != nil && !sock.closed.Load() {
			cp := make([]byte, ulen-8)
			copy(cp, body[8:ulen])
			select {
			case sock.rx <- UDPPacket{SrcIP: srcIP, SrcPort: srcPort, DstPort: dstPort, Payload: cp}:
			default:
			}
		}
		t.mu.RUnlock()
	case ProtoTCP:
		if len(body) < 20 {
			return nil
		}
		srcPort := binary.BigEndian.Uint16(body[0:2])
		dstPort := binary.BigEndian.Uint16(body[2:4])
		var rip [16]byte
		copy(rip[:], srcIP.To16())
		key := tcpKey{localPort: dstPort, remotePort: srcPort, remoteIP: rip}
		t.mu.RLock()
		conn := t.tcpConns[key]
		t.mu.RUnlock()
		if conn != nil {
			conn.onSegment(body)
		}
	}
	return nil
}

// VirtualTCPConn is a lightweight RFC 793 userspace TCP client over IPv4-in-ESP for SIP-over-TCP.
type VirtualTCPConn struct {
	tunnel     *Tunnel
	key        tcpKey
	remoteIP   net.IP
	localPort  uint16
	remotePort uint16

	mu        sync.Mutex
	sndNxt    uint32
	rcvNxt    uint32
	connected chan struct{}
	closed    bool
	rxBuf     []byte
	rxNotify  chan struct{}
}

const (
	tcpFIN = 0x01
	tcpSYN = 0x02
	tcpRST = 0x04
	tcpPSH = 0x08
	tcpACK = 0x10
)

func (t *Tunnel) DialTCP(remoteIP net.IP, remotePort uint16, timeout time.Duration) (*VirtualTCPConn, error) {
	ip := remoteIP.To16()
	if ip == nil || (remoteIP.To4() == nil) != (t.localIP.To4() == nil) {
		return nil, errors.New("TCP_ADDRESS_FAMILY_MISMATCH")
	}
	var rip [16]byte
	copy(rip[:], ip)
	var isnb [4]byte
	_, _ = rand.Read(isnb[:])
	isn := binary.BigEndian.Uint32(isnb[:])

	t.mu.Lock()
	localPort := t.nextPort
	t.nextPort++
	if t.nextPort >= 60000 {
		t.nextPort = 32000
	}
	key := tcpKey{localPort: localPort, remotePort: remotePort, remoteIP: rip}
	c := &VirtualTCPConn{
		tunnel:     t,
		key:        key,
		remoteIP:   append(net.IP(nil), ip...),
		localPort:  localPort,
		remotePort: remotePort,
		sndNxt:     isn,
		connected:  make(chan struct{}),
		rxNotify:   make(chan struct{}, 1),
	}
	t.tcpConns[key] = c
	t.mu.Unlock()

	if err := t.sendTCP(localPort, ip, remotePort, isn, 0, tcpSYN, 65535, nil); err != nil {
		_ = c.Close()
		return nil, err
	}
	c.mu.Lock()
	c.sndNxt++
	c.mu.Unlock()

	if timeout <= 0 {
		timeout = 5 * time.Second
	}
	select {
	case <-c.connected:
		return c, nil
	case <-time.After(timeout):
		_ = c.Close()
		return nil, osTimeoutErr{}
	}
}

func (c *VirtualTCPConn) onSegment(seg []byte) {
	doff := int(seg[12]>>4) * 4
	if doff < 20 || doff > len(seg) {
		return
	}
	seq := binary.BigEndian.Uint32(seg[4:8])
	flags := seg[13]
	payload := seg[doff:]

	c.mu.Lock()
	if c.closed {
		c.mu.Unlock()
		return
	}
	if (flags & tcpRST) != 0 {
		c.closed = true
		c.mu.Unlock()
		c.signalRx()
		return
	}
	if (flags&tcpSYN) != 0 && (flags&tcpACK) != 0 {
		c.rcvNxt = seq + 1
		snd := c.sndNxt
		rcv := c.rcvNxt
		select {
		case <-c.connected:
		default:
			close(c.connected)
		}
		c.mu.Unlock()
		_ = c.tunnel.sendTCP(c.localPort, c.remoteIP, c.remotePort, snd, rcv, tcpACK, 65535, nil)
		return
	}
	needAck := false
	if len(payload) > 0 && seq == c.rcvNxt {
		c.rxBuf = append(c.rxBuf, payload...)
		c.rcvNxt += uint32(len(payload))
		needAck = true
	}
	if (flags & tcpFIN) != 0 {
		c.rcvNxt++
		c.closed = true
		needAck = true
	}
	snd, rcv := c.sndNxt, c.rcvNxt
	c.mu.Unlock()

	if needAck {
		_ = c.tunnel.sendTCP(c.localPort, c.remoteIP, c.remotePort, snd, rcv, tcpACK, 65535, nil)
		c.signalRx()
	}
}

func (c *VirtualTCPConn) signalRx() {
	select {
	case c.rxNotify <- struct{}{}:
	default:
	}
}

func (c *VirtualTCPConn) Write(p []byte) (int, error) {
	c.mu.Lock()
	if c.closed {
		c.mu.Unlock()
		return 0, net.ErrClosed
	}
	written := 0
	for len(p) > 0 {
		chunk := p
		if len(chunk) > 1200 {
			chunk = p[:1200]
		}
		seq, ack := c.sndNxt, c.rcvNxt
		c.sndNxt += uint32(len(chunk))
		c.mu.Unlock()
		if err := c.tunnel.sendTCP(c.localPort, c.remoteIP, c.remotePort, seq, ack, tcpPSH|tcpACK, 65535, chunk); err != nil {
			return written, err
		}
		written += len(chunk)
		p = p[len(chunk):]
		c.mu.Lock()
	}
	c.mu.Unlock()
	return written, nil
}

func (c *VirtualTCPConn) Read(p []byte) (int, error) {
	for {
		c.mu.Lock()
		if len(c.rxBuf) > 0 {
			n := copy(p, c.rxBuf)
			c.rxBuf = c.rxBuf[n:]
			c.mu.Unlock()
			return n, nil
		}
		if c.closed {
			c.mu.Unlock()
			return 0, io.EOF
		}
		c.mu.Unlock()
		<-c.rxNotify
	}
}

func (c *VirtualTCPConn) Close() error {
	c.mu.Lock()
	if c.closed {
		c.mu.Unlock()
		return nil
	}
	c.closed = true
	seq, ack := c.sndNxt, c.rcvNxt
	c.mu.Unlock()
	_ = c.tunnel.sendTCP(c.localPort, c.remoteIP, c.remotePort, seq, ack, tcpFIN|tcpACK, 65535, nil)
	c.tunnel.mu.Lock()
	delete(c.tunnel.tcpConns, c.key)
	c.tunnel.mu.Unlock()
	c.signalRx()
	return nil
}

func buildIPv4(src, dst net.IP, proto byte, id uint16, payload []byte) []byte {
	total := 20 + len(payload)
	pkt := make([]byte, total)
	pkt[0] = 0x45
	binary.BigEndian.PutUint16(pkt[2:4], uint16(total))
	binary.BigEndian.PutUint16(pkt[4:6], id)
	pkt[6] = 0x40 // DF
	pkt[8] = 64   // TTL
	pkt[9] = proto
	copy(pkt[12:16], src.To4())
	copy(pkt[16:20], dst.To4())
	binary.BigEndian.PutUint16(pkt[10:12], ipChecksum(pkt[:20]))
	copy(pkt[20:], payload)
	return pkt
}

func buildIP(src, dst net.IP, proto byte, id uint16, payload []byte) ([]byte, byte) {
	if src.To4() != nil {
		return buildIPv4(src, dst, proto, id, payload), 4
	}
	pkt := make([]byte, 40+len(payload))
	pkt[0], pkt[6], pkt[7] = 0x60, proto, 64
	binary.BigEndian.PutUint16(pkt[4:6], uint16(len(payload)))
	copy(pkt[8:24], src.To16())
	copy(pkt[24:40], dst.To16())
	copy(pkt[40:], payload)
	return pkt, 41
}

func ipChecksum(data []byte) uint16 {
	var sum uint32
	for i := 0; i+1 < len(data); i += 2 {
		sum += uint32(binary.BigEndian.Uint16(data[i : i+2]))
	}
	if len(data)%2 == 1 {
		sum += uint32(data[len(data)-1]) << 8
	}
	for (sum >> 16) != 0 {
		sum = (sum & 0xFFFF) + (sum >> 16)
	}
	return ^uint16(sum)
}

func transportChecksum(src, dst net.IP, proto byte, seg []byte) uint16 {
	var sum uint32
	saddr, daddr := src.To4(), dst.To4()
	if saddr == nil {
		saddr, daddr = src.To16(), dst.To16()
	}
	for i := 0; i < len(saddr); i += 2 {
		sum += uint32(binary.BigEndian.Uint16(saddr[i:i+2])) + uint32(binary.BigEndian.Uint16(daddr[i:i+2]))
	}
	sum += uint32(proto) + uint32(len(seg))
	for i := 0; i+1 < len(seg); i += 2 {
		sum += uint32(binary.BigEndian.Uint16(seg[i : i+2]))
	}
	if len(seg)%2 == 1 {
		sum += uint32(seg[len(seg)-1]) << 8
	}
	for (sum >> 16) != 0 {
		sum = (sum & 0xFFFF) + (sum >> 16)
	}
	return ^uint16(sum)
}

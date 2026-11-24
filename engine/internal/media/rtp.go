package media

import (
	"crypto/rand"
	"encoding/binary"
	"errors"
	"net"
	"sync"
	"sync/atomic"
	"time"

	"github.com/YubaiNya/SmartVoWifiCall/engine/internal/ipsec"
)

// RTPPacket represents an RFC 3550 RTP packet.
type RTPPacket struct {
	PayloadType uint8
	Marker      bool
	Seq         uint16
	Timestamp   uint32
	SSRC        uint32
	Payload     []byte
}

func EncodeRTP(p RTPPacket) []byte {
	out := make([]byte, 12+len(p.Payload))
	out[0] = 0x80 // V=2
	out[1] = p.PayloadType & 0x7F
	if p.Marker {
		out[1] |= 0x80
	}
	binary.BigEndian.PutUint16(out[2:4], p.Seq)
	binary.BigEndian.PutUint32(out[4:8], p.Timestamp)
	binary.BigEndian.PutUint32(out[8:12], p.SSRC)
	copy(out[12:], p.Payload)
	return out
}

func DecodeRTP(raw []byte) (RTPPacket, error) {
	if len(raw) < 12 {
		return RTPPacket{}, errors.New("RTP_SHORT")
	}
	if (raw[0] >> 6) != 2 {
		return RTPPacket{}, errors.New("RTP_BAD_VERSION")
	}
	cc := int(raw[0] & 0x0F)
	hasExt := (raw[0] & 0x10) != 0
	hasPad := (raw[0] & 0x20) != 0
	off := 12 + cc*4
	if off > len(raw) {
		return RTPPacket{}, errors.New("RTP_CSRC_OVERFLOW")
	}
	if hasExt {
		if off+4 > len(raw) {
			return RTPPacket{}, errors.New("RTP_EXT_SHORT")
		}
		extWords := int(binary.BigEndian.Uint16(raw[off+2 : off+4]))
		off += 4 + extWords*4
		if off > len(raw) {
			return RTPPacket{}, errors.New("RTP_EXT_OVERFLOW")
		}
	}
	end := len(raw)
	if hasPad && end > off {
		pad := int(raw[end-1])
		if pad <= end-off {
			end -= pad
		}
	}
	return RTPPacket{
		PayloadType: raw[1] & 0x7F,
		Marker:      (raw[1] & 0x80) != 0,
		Seq:         binary.BigEndian.Uint16(raw[2:4]),
		Timestamp:   binary.BigEndian.Uint32(raw[4:8]),
		SSRC:        binary.BigEndian.Uint32(raw[8:12]),
		Payload:     append([]byte(nil), raw[off:end]...),
	}, nil
}

// JitterBuffer reorders incoming RTP frames by sequence number and smooths 20ms playout.
type JitterBuffer struct {
	mu          sync.Mutex
	frames      map[uint16][]byte
	initialized bool
	nextSeq     uint16
	targetDepth int
}

func NewJitterBuffer(targetDepth int) *JitterBuffer {
	if targetDepth < 1 {
		targetDepth = 2
	}
	return &JitterBuffer{
		frames:      make(map[uint16][]byte),
		targetDepth: targetDepth,
	}
}

func (jb *JitterBuffer) Push(seq uint16, payload []byte) {
	jb.mu.Lock()
	defer jb.mu.Unlock()
	if !jb.initialized {
		jb.initialized = true
		jb.nextSeq = seq
	}
	// Drop stale packets older than nextSeq (using 16-bit wrap-safe signed diff)
	if int16(seq-jb.nextSeq) < 0 {
		return
	}
	if len(jb.frames) > 32 {
		// Prevent unbounded lag: snap forward
		jb.frames = make(map[uint16][]byte)
		jb.nextSeq = seq
	}
	jb.frames[seq] = append([]byte(nil), payload...)
}

func (jb *JitterBuffer) Pop() ([]byte, bool) {
	jb.mu.Lock()
	defer jb.mu.Unlock()
	if !jb.initialized || len(jb.frames) == 0 {
		return nil, false
	}
	if p, ok := jb.frames[jb.nextSeq]; ok {
		delete(jb.frames, jb.nextSeq)
		jb.nextSeq++
		return p, true
	}
	// Packet loss: if newer packets have accumulated past targetDepth, advance over gap (PLC silence).
	if len(jb.frames) >= jb.targetDepth {
		jb.nextSeq++
		return nil, false
	}
	return nil, false
}

// Session bridges bidirectional RTP over the IPsec tunnel with 640B 16kHz PCM frames.
type Session struct {
	sock       *ipsec.UDPSocket
	remoteIP   net.IP
	remotePort uint16
	codec      VoiceCodec
	dtmfPT     uint8
	onDownlink func(pcm16k []byte)

	mu     sync.Mutex
	ssrc   uint32
	seq    uint16
	ts     uint32
	jitter *JitterBuffer
	closed atomic.Bool
	stopCh chan struct{}
	wg     sync.WaitGroup
}

func StartSession(tunnel *ipsec.Tunnel, localPort uint16, remoteIP net.IP, remotePort uint16,
	codec VoiceCodec, dtmfPT uint8, onDownlink func([]byte)) (*Session, error) {
	sock, err := tunnel.BindUDP(localPort)
	if err != nil {
		return nil, err
	}
	var sb [4]byte
	_, _ = rand.Read(sb[:])
	s := &Session{
		sock:       sock,
		remoteIP:   remoteIP.To4(),
		remotePort: remotePort,
		codec:      codec,
		dtmfPT:     dtmfPT,
		onDownlink: onDownlink,
		ssrc:       binary.BigEndian.Uint32(sb[:]),
		jitter:     NewJitterBuffer(2),
		stopCh:     make(chan struct{}),
	}
	s.wg.Add(2)
	go s.rxLoop()
	go s.playoutLoop()
	return s, nil
}

// SendUplinkPCM encodes one 640-byte 16kHz mono PCM16 frame and transmits it as RTP over ESP.
func (s *Session) SendUplinkPCM(pcm16k []byte) error {
	if s.closed.Load() {
		return net.ErrClosed
	}
	encoded, err := s.codec.Encode16k(pcm16k)
	if err != nil {
		return err
	}
	s.mu.Lock()
	seq := s.seq
	s.seq++
	ts := s.ts
	s.ts += s.codec.SamplesPerFrame()
	ssrc := s.ssrc
	s.mu.Unlock()

	pkt := EncodeRTP(RTPPacket{
		PayloadType: s.codec.PayloadType(),
		Seq:         seq,
		Timestamp:   ts,
		SSRC:        ssrc,
		Payload:     encoded,
	})
	return s.sock.WriteTo(s.remoteIP, s.remotePort, pkt)
}

// SendDTMF sends RFC 4733 telephone-event RTP packets for a DTMF digit ('0'-'9','*','#','A'-'D').
func (s *Session) SendDTMF(digit byte, durationMs int) error {
	if s.closed.Load() {
		return net.ErrClosed
	}
	eventCode, ok := dtmfEventCode(digit)
	if !ok {
		return errors.New("INVALID_DTMF_DIGIT")
	}
	if durationMs <= 0 {
		durationMs = 160
	}
	durSamples := uint16((durationMs * s.codec.ClockRate()) / 1000)

	s.mu.Lock()
	ts := s.ts
	ssrc := s.ssrc
	s.mu.Unlock()

	// Send 3 continuation packets + 3 end-of-event packets per RFC 4733 §2.5.1.4.
	steps := []struct {
		end bool
		dur uint16
	}{
		{false, durSamples / 3},
		{false, (durSamples * 2) / 3},
		{true, durSamples},
		{true, durSamples},
		{true, durSamples},
	}
	for i, st := range steps {
		var body [4]byte
		body[0] = eventCode
		body[1] = 10 // volume -10 dBm0
		if st.end {
			body[1] |= 0x80 // End bit
		}
		binary.BigEndian.PutUint16(body[2:4], st.dur)

		s.mu.Lock()
		seq := s.seq
		s.seq++
		s.mu.Unlock()

		pkt := EncodeRTP(RTPPacket{
			PayloadType: s.dtmfPT,
			Marker:      i == 0,
			Seq:         seq,
			Timestamp:   ts,
			SSRC:        ssrc,
			Payload:     body[:],
		})
		if err := s.sock.WriteTo(s.remoteIP, s.remotePort, pkt); err != nil {
			return err
		}
	}
	return nil
}

func (s *Session) rxLoop() {
	defer s.wg.Done()
	for {
		pkt, err := s.sock.ReadFrom(time.Time{})
		if err != nil {
			return
		}
		rtp, err := DecodeRTP(pkt.Payload)
		if err != nil {
			continue
		}
		if rtp.PayloadType == s.dtmfPT {
			continue
		}
		s.jitter.Push(rtp.Seq, rtp.Payload)
	}
}

func (s *Session) playoutLoop() {
	defer s.wg.Done()
	ticker := time.NewTicker(20 * time.Millisecond)
	defer ticker.Stop()
	for {
		select {
		case <-s.stopCh:
			return
		case <-ticker.C:
			payload, ok := s.jitter.Pop()
			if !ok {
				continue
			}
			pcm, err := s.codec.Decode16k(payload)
			if err == nil && s.onDownlink != nil {
				s.onDownlink(pcm)
			}
		}
	}
}

func (s *Session) Close() error {
	if s.closed.Swap(true) {
		return nil
	}
	close(s.stopCh)
	err := s.sock.Close()
	s.wg.Wait()
	return err
}

func dtmfEventCode(digit byte) (byte, bool) {
	switch {
	case digit >= '0' && digit <= '9':
		return digit - '0', true
	case digit == '*':
		return 10, true
	case digit == '#':
		return 11, true
	case digit >= 'A' && digit <= 'D':
		return 12 + (digit - 'A'), true
	default:
		return 0, false
	}
}

package rpc

import (
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net"
	"os"
	"sync"
)

const (
	PCMSampleRateHz   = 16000
	PCMChannelCount   = 1
	PCMSamplesPer20ms = 320
	PCMPayloadBytes   = 640
	PCMHeaderBytes    = 4
	PCMFrameBytes     = 644
)

// EncodePCMFrame wraps a 640-byte 16kHz mono PCM16 payload with BE32(644).
func EncodePCMFrame(payload []byte) ([]byte, error) {
	if len(payload) != PCMPayloadBytes {
		return nil, fmt.Errorf("PCM payload must be %d bytes, got %d", PCMPayloadBytes, len(payload))
	}
	frame := make([]byte, PCMFrameBytes)
	binary.BigEndian.PutUint32(frame[0:4], PCMFrameBytes)
	copy(frame[4:], payload)
	return frame, nil
}

// ReadPCMFrame reads one framed 640-byte PCM payload from r into dst (must be >= 640 bytes).
func ReadPCMFrame(r io.Reader, dst []byte) error {
	if len(dst) < PCMPayloadBytes {
		return errors.New("PCM destination buffer too small")
	}
	var hdr [PCMHeaderBytes]byte
	if _, err := io.ReadFull(r, hdr[:]); err != nil {
		return err
	}
	total := binary.BigEndian.Uint32(hdr[:])
	if total != PCMFrameBytes {
		return fmt.Errorf("unexpected PCM frame size: %d", total)
	}
	_, err := io.ReadFull(r, dst[:PCMPayloadBytes])
	return err
}

// AudioServer serves the raw framed PCM Unix socket (<iccid>.audio.sock) without any JSON handshake.
type AudioServer struct {
	path     string
	listener net.Listener
	mu       sync.Mutex
	active   net.Conn
	uplink   func([]byte)
	downCh   chan []byte
	closed   bool
	wg       sync.WaitGroup
}

func NewAudioServer(socketPath string, onUplink func([]byte)) (*AudioServer, error) {
	_ = os.Remove(socketPath)
	ln, err := net.Listen("unix", socketPath)
	if err != nil {
		return nil, err
	}
	_ = os.Chmod(socketPath, 0o660)
	s := &AudioServer{
		path:     socketPath,
		listener: ln,
		uplink:   onUplink,
		downCh:   make(chan []byte, 64),
	}
	s.wg.Add(1)
	go s.acceptLoop()
	return s, nil
}

// SendDownlink queues a 640-byte PCM16 frame to the connected Android audio client (drops oldest if full).
func (s *AudioServer) SendDownlink(pcm []byte) {
	if len(pcm) != PCMPayloadBytes {
		return
	}
	cp := make([]byte, PCMPayloadBytes)
	copy(cp, pcm)
	select {
	case s.downCh <- cp:
	default:
		select {
		case <-s.downCh:
		default:
		}
		select {
		case s.downCh <- cp:
		default:
		}
	}
}

func (s *AudioServer) acceptLoop() {
	defer s.wg.Done()
	for {
		conn, err := s.listener.Accept()
		if err != nil {
			return
		}
		s.mu.Lock()
		if s.closed {
			s.mu.Unlock()
			_ = conn.Close()
			return
		}
		if s.active != nil {
			_ = s.active.Close()
		}
		s.active = conn
		s.mu.Unlock()

		s.wg.Add(1)
		go s.serveConn(conn)
	}
}

func (s *AudioServer) serveConn(conn net.Conn) {
	defer s.wg.Done()
	defer func() {
		_ = conn.Close()
		s.mu.Lock()
		if s.active == conn {
			s.active = nil
		}
		s.mu.Unlock()
	}()

	done := make(chan struct{})
	go func() {
		defer close(done)
		buf := make([]byte, PCMPayloadBytes)
		for {
			if err := ReadPCMFrame(conn, buf); err != nil {
				return
			}
			if s.uplink != nil {
				cp := make([]byte, PCMPayloadBytes)
				copy(cp, buf)
				s.uplink(cp)
			}
		}
	}()

	for {
		select {
		case <-done:
			return
		case pcm, ok := <-s.downCh:
			if !ok {
				return
			}
			frame, err := EncodePCMFrame(pcm)
			if err != nil {
				continue
			}
			if _, err := conn.Write(frame); err != nil {
				return
			}
		}
	}
}

func (s *AudioServer) Close() error {
	s.mu.Lock()
	if s.closed {
		s.mu.Unlock()
		return nil
	}
	s.closed = true
	if s.active != nil {
		_ = s.active.Close()
	}
	close(s.downCh)
	s.mu.Unlock()
	err := s.listener.Close()
	s.wg.Wait()
	_ = os.Remove(s.path)
	return err
}

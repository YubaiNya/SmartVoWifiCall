package media

import (
	"bytes"
	"encoding/binary"
	"math"
	"net"
	"testing"
	"time"

	"github.com/YubaiNya/SmartVoWifiCall/engine/internal/ipsec"
)

func TestG711CodecsAndJitterBuffer(t *testing.T) {
	// Generate 20ms 1kHz sine wave at 16kHz (320 samples, 640 bytes)
	pcm := make([]byte, 640)
	for i := 0; i < 320; i++ {
		val := int16(12000 * math.Sin(2*math.Pi*1000*float64(i)/16000.0))
		binary.LittleEndian.PutUint16(pcm[i*2:i*2+2], uint16(val))
	}

	for _, name := range []string{"PCMU", "PCMA"} {
		c := NewCodec(name, 0)
		enc, err := c.Encode16k(pcm)
		if err != nil || len(enc) != 160 {
			t.Fatalf("%s encode failed: len=%d err=%v", name, len(enc), err)
		}
		dec, err := c.Decode16k(enc)
		if err != nil || len(dec) != 640 {
			t.Fatalf("%s decode failed: len=%d err=%v", name, len(dec), err)
		}
		// Check decoded waveform has non-trivial energy
		var energy int64
		for i := 0; i < 320; i++ {
			s := int64(int16(binary.LittleEndian.Uint16(dec[i*2 : i*2+2])))
			energy += s * s
		}
		if energy < 1_000_000_000 {
			t.Fatalf("%s decoded energy too low: %d", name, energy)
		}
	}

	// Jitter buffer out-of-order reassembly
	jb := NewJitterBuffer(2)
	jb.Push(10, []byte("p10"))
	jb.Push(12, []byte("p12"))
	jb.Push(11, []byte("p11"))
	for _, want := range []string{"p10", "p11", "p12"} {
		got, ok := jb.Pop()
		if !ok || string(got) != want {
			t.Fatalf("expected %s, got %q (ok=%v)", want, got, ok)
		}
	}
}

type espPipe struct{ peer **ipsec.Tunnel }

func (p *espPipe) SendESP(esp []byte) error {
	cp := append([]byte(nil), esp...)
	go func() { _ = (*p.peer).HandleESP(cp) }()
	return nil
}

func TestRTPSessionAndDTMFOverESP(t *testing.T) {
	k1 := bytes.Repeat([]byte{0x11}, 16)
	k2 := bytes.Repeat([]byte{0x22}, 16)
	outA, _ := ipsec.NewSA(1, ipsec.SuiteAESGCM128, k1, nil, []byte{1, 2, 3, 4})
	inA, _ := ipsec.NewSA(2, ipsec.SuiteAESGCM128, k2, nil, []byte{5, 6, 7, 8})
	outB, _ := ipsec.NewSA(2, ipsec.SuiteAESGCM128, k2, nil, []byte{5, 6, 7, 8})
	inB, _ := ipsec.NewSA(1, ipsec.SuiteAESGCM128, k1, nil, []byte{1, 2, 3, 4})
	var tunA, tunB *ipsec.Tunnel
	tunA, _ = ipsec.NewTunnel(net.IPv4(10, 77, 0, 2), outA, inA, &espPipe{peer: &tunB})
	tunB, _ = ipsec.NewTunnel(net.IPv4(10, 77, 0, 1), outB, inB, &espPipe{peer: &tunA})

	gotDown := make(chan []byte, 4)
	sessA, err := StartSession(tunA, 40000, net.IPv4(10, 77, 0, 1), 40002, NewCodec("PCMU", 0), 101, func(p []byte) {})
	if err != nil {
		t.Fatal(err)
	}
	defer sessA.Close()

	sessB, err := StartSession(tunB, 40002, net.IPv4(10, 77, 0, 2), 40000, NewCodec("PCMU", 0), 101, func(p []byte) {
		select {
		case gotDown <- p:
		default:
		}
	})
	if err != nil {
		t.Fatal(err)
	}
	defer sessB.Close()

	pcm := bytes.Repeat([]byte{0x00, 0x10}, 320)
	if err := sessA.SendUplinkPCM(pcm); err != nil {
		t.Fatal(err)
	}
	if err := sessA.SendDTMF('#', 160); err != nil {
		t.Fatalf("SendDTMF failed: %v", err)
	}
	select {
	case frame := <-gotDown:
		if len(frame) != 640 {
			t.Fatalf("expected 640B downlink PCM, got %d", len(frame))
		}
	case <-time.After(2 * time.Second):
		t.Fatal("timed out waiting for RTP->PCM downlink")
	}
}

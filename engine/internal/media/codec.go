package media

import (
	"encoding/binary"
	"fmt"
)

// VoiceCodec converts between 20ms 16kHz mono PCM16 (640 bytes) and RTP codec payloads.
type VoiceCodec interface {
	Name() string
	PayloadType() uint8
	ClockRate() int
	SamplesPerFrame() uint32
	Encode16k(pcm16k []byte) ([]byte, error)
	Decode16k(payload []byte) ([]byte, error)
}

// ExternalAMRFactory allows plugging in an NDK/MediaCodec AMR-WB or AMR-NB codec backend.
var ExternalAMRFactory func(codecName string, pt uint8) VoiceCodec

func HasAMRSupport() bool {
	return ExternalAMRFactory != nil
}

func NewCodec(name string, pt uint8) VoiceCodec {
	if (name == "AMR-WB" || name == "AMR") && ExternalAMRFactory != nil {
		if c := ExternalAMRFactory(name, pt); c != nil {
			return c
		}
	}
	if name == "PCMA" || pt == 8 {
		return &g711Codec{alaw: true, pt: 8}
	}
	return &g711Codec{alaw: false, pt: 0}
}

type g711Codec struct {
	alaw bool
	pt   uint8
}

func (g *g711Codec) Name() string {
	if g.alaw {
		return "PCMA"
	}
	return "PCMU"
}
func (g *g711Codec) PayloadType() uint8      { return g.pt }
func (g *g711Codec) ClockRate() int          { return 8000 }
func (g *g711Codec) SamplesPerFrame() uint32 { return 160 }

// Encode16k downsamples 320 samples (16kHz, 640B) -> 160 samples (8kHz) -> 160B G.711.
func (g *g711Codec) Encode16k(pcm16k []byte) ([]byte, error) {
	if len(pcm16k) != 640 {
		return nil, fmt.Errorf("expected 640B PCM16, got %d", len(pcm16k))
	}
	out := make([]byte, 160)
	for i := 0; i < 160; i++ {
		s0 := int32(int16(binary.LittleEndian.Uint16(pcm16k[i*4 : i*4+2])))
		s1 := int32(int16(binary.LittleEndian.Uint16(pcm16k[i*4+2 : i*4+4])))
		avg := int16((s0 + s1) / 2)
		if g.alaw {
			out[i] = linearToALaw(avg)
		} else {
			out[i] = linearToMuLaw(avg)
		}
	}
	return out, nil
}

// Decode16k decodes 160B G.711 (8kHz) and linearly upsamples to 320 samples (16kHz, 640B PCM16).
func (g *g711Codec) Decode16k(payload []byte) ([]byte, error) {
	if len(payload) == 0 {
		return make([]byte, 640), nil
	}
	samples8k := make([]int16, 160)
	n := len(payload)
	if n > 160 {
		n = 160
	}
	for i := 0; i < n; i++ {
		if g.alaw {
			samples8k[i] = aLawToLinear(payload[i])
		} else {
			samples8k[i] = muLawToLinear(payload[i])
		}
	}
	out := make([]byte, 640)
	for i := 0; i < 160; i++ {
		cur := samples8k[i]
		next := cur
		if i+1 < 160 {
			next = samples8k[i+1]
		}
		mid := int16((int32(cur) + int32(next)) / 2)
		binary.LittleEndian.PutUint16(out[i*4:i*4+2], uint16(cur))
		binary.LittleEndian.PutUint16(out[i*4+2:i*4+4], uint16(mid))
	}
	return out, nil
}

// ITU-T G.711 mu-law
func linearToMuLaw(sample int16) byte {
	const (
		bias = 0x84
		clip = 32635
	)
	sign := byte(0)
	s := int(sample)
	if s < 0 {
		sign = 0x80
		s = -s
	}
	if s > clip {
		s = clip
	}
	s += bias
	exp := byte(7)
	for mask := 0x4000; (s&mask) == 0 && exp > 0; exp-- {
		mask >>= 1
	}
	mantissa := byte((s >> (exp + 3)) & 0x0F)
	return ^(sign | (exp << 4) | mantissa)
}

func muLawToLinear(u byte) int16 {
	u = ^u
	sign := u & 0x80
	exp := (u >> 4) & 0x07
	mantissa := u & 0x0F
	t := (int(mantissa)<<3 + 0x84) << exp
	t -= 0x84
	if sign != 0 {
		return int16(-t)
	}
	return int16(t)
}

// ITU-T G.711 A-law
func linearToALaw(sample int16) byte {
	sign := byte(0)
	s := int(sample)
	if s < 0 {
		sign = 0x80
		s = -s - 1
	}
	if s > 32767 {
		s = 32767
	}
	var out byte
	if s >= 256 {
		exp := byte(7)
		for mask := 0x4000; (s&mask) == 0 && exp > 1; exp-- {
			mask >>= 1
		}
		mant := byte((s >> (exp + 3)) & 0x0F)
		out = (exp << 4) | mant
	} else {
		out = byte(s >> 4)
	}
	return (out | sign) ^ 0x55
}

func aLawToLinear(a byte) int16 {
	a ^= 0x55
	sign := a & 0x80
	exp := (a >> 4) & 0x07
	mant := int(a & 0x0F)
	var t int
	if exp == 0 {
		t = (mant << 4) + 8
	} else {
		t = ((mant << 4) + 0x108) << (exp - 1)
	}
	if sign != 0 {
		return int16(-t)
	}
	return int16(t)
}

package sip

import (
	"fmt"
	"net"
	"strconv"
	"strings"
)

type MediaOffer struct {
	LocalIP    net.IP
	LocalPort  uint16
	SessionID  uint64
	IncludeAMR bool
}

type MediaAnswer struct {
	RemoteIP   net.IP
	RemotePort uint16
	Codec      string // "AMR-WB", "AMR", "PCMU", "PCMA"
	PayloadTyp uint8
	DTMFType   uint8
	ClockRate  int
}

// BuildSDP generates a standards-compliant 3GPP VoWiFi audio SDP offer/answer.
func BuildSDP(o MediaOffer) []byte {
	ip := o.LocalIP.String()
	var sb strings.Builder
	fmt.Fprintf(&sb, "v=0\r\n")
	family := "IP4"
	if o.LocalIP.To4() == nil {
		family = "IP6"
	}
	fmt.Fprintf(&sb, "o=- %d %d IN %s %s\r\n", o.SessionID, o.SessionID, family, ip)
	fmt.Fprintf(&sb, "s=SmartVoWifiCall\r\n")
	fmt.Fprintf(&sb, "c=IN %s %s\r\n", family, ip)
	fmt.Fprintf(&sb, "t=0 0\r\n")
	if o.IncludeAMR {
		fmt.Fprintf(&sb, "m=audio %d RTP/AVP 104 97 0 8 101\r\n", o.LocalPort)
		fmt.Fprintf(&sb, "a=rtpmap:104 AMR-WB/16000/1\r\n")
		fmt.Fprintf(&sb, "a=fmtp:104 mode-change-capability=2; max-red=0\r\n")
		fmt.Fprintf(&sb, "a=rtpmap:97 AMR/8000/1\r\n")
		fmt.Fprintf(&sb, "a=fmtp:97 mode-change-capability=2; max-red=0\r\n")
	} else {
		fmt.Fprintf(&sb, "m=audio %d RTP/AVP 0 8 101\r\n", o.LocalPort)
	}
	fmt.Fprintf(&sb, "a=rtpmap:0 PCMU/8000\r\n")
	fmt.Fprintf(&sb, "a=rtpmap:8 PCMA/8000\r\n")
	fmt.Fprintf(&sb, "a=rtpmap:101 telephone-event/8000\r\n")
	fmt.Fprintf(&sb, "a=fmtp:101 0-15\r\n")
	fmt.Fprintf(&sb, "a=ptime:20\r\n")
	fmt.Fprintf(&sb, "a=maxptime:40\r\n")
	fmt.Fprintf(&sb, "a=sendrecv\r\n")
	return []byte(sb.String())
}

// ParseSDP extracts the negotiated audio RTP endpoint, codec, and DTMF payload type from SDP.
func ParseSDP(raw []byte) (*MediaAnswer, error) {
	ans := &MediaAnswer{
		Codec:      "PCMU",
		PayloadTyp: 0,
		DTMFType:   101,
		ClockRate:  8000,
	}
	rtpmap := make(map[uint8]string)
	var offeredPTs []uint8
	inAudio := false

	text := strings.ReplaceAll(string(raw), "\r\n", "\n")
	for _, line := range strings.Split(text, "\n") {
		line = strings.TrimSpace(line)
		if strings.HasPrefix(line, "c=IN IP4 ") || strings.HasPrefix(line, "c=IN IP6 ") {
			ipStr := strings.TrimSpace(line[len("c=IN IP4 "):])
			if slash := strings.IndexByte(ipStr, '/'); slash > 0 {
				ipStr = ipStr[:slash]
			}
			if ip := net.ParseIP(ipStr); ip != nil {
				ans.RemoteIP = ip
			}
		} else if strings.HasPrefix(line, "m=audio ") {
			inAudio = true
			fields := strings.Fields(line)
			if len(fields) >= 4 {
				if p, err := strconv.Atoi(fields[1]); err == nil && p > 0 && p <= 65535 {
					ans.RemotePort = uint16(p)
				}
				for _, ptStr := range fields[3:] {
					if pt, err := strconv.Atoi(ptStr); err == nil && pt >= 0 && pt <= 127 {
						offeredPTs = append(offeredPTs, uint8(pt))
					}
				}
			}
		} else if strings.HasPrefix(line, "m=") {
			inAudio = false
		} else if inAudio && strings.HasPrefix(line, "a=rtpmap:") {
			rest := strings.TrimPrefix(line, "a=rtpmap:")
			parts := strings.SplitN(rest, " ", 2)
			if len(parts) == 2 {
				if pt, err := strconv.Atoi(parts[0]); err == nil && pt >= 0 && pt <= 127 {
					rtpmap[uint8(pt)] = strings.ToUpper(strings.TrimSpace(parts[1]))
				}
			}
		}
	}

	// Select best supported codec in offered order (PCMU/PCMA/AMR-WB/AMR)
	picked := false
	for _, pt := range offeredPTs {
		name := rtpmap[pt]
		switch {
		case strings.HasPrefix(name, "TELEPHONE-EVENT"):
			ans.DTMFType = pt
		case !picked && strings.HasPrefix(name, "AMR-WB"):
			ans.Codec = "AMR-WB"
			ans.PayloadTyp = pt
			ans.ClockRate = 16000
			picked = true
		case !picked && strings.HasPrefix(name, "AMR/"):
			ans.Codec = "AMR"
			ans.PayloadTyp = pt
			ans.ClockRate = 8000
			picked = true
		case !picked && (pt == 0 || strings.HasPrefix(name, "PCMU")):
			ans.Codec = "PCMU"
			ans.PayloadTyp = pt
			ans.ClockRate = 8000
			picked = true
		case !picked && (pt == 8 || strings.HasPrefix(name, "PCMA")):
			ans.Codec = "PCMA"
			ans.PayloadTyp = pt
			ans.ClockRate = 8000
			picked = true
		}
	}
	if ans.RemoteIP == nil || ans.RemotePort == 0 {
		return nil, fmt.Errorf("SDP_NO_AUDIO_ENDPOINT")
	}
	return ans, nil
}

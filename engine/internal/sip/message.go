package sip

import (
	"bytes"
	"crypto/hmac"
	"crypto/md5"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"strconv"
	"strings"
)

type HeaderKV struct {
	Name  string
	Value string
}

// Message represents a parsed RFC 3261 SIP request or response.
type Message struct {
	IsResponse bool
	Method     string
	RequestURI string
	StatusCode int
	Reason     string
	Headers    []HeaderKV
	Body       []byte
}

func (m *Message) AddHeader(name, value string) {
	m.Headers = append(m.Headers, HeaderKV{Name: name, Value: value})
}

func (m *Message) SetHeader(name, value string) {
	ln := strings.ToLower(name)
	for i := range m.Headers {
		if strings.ToLower(m.Headers[i].Name) == ln {
			m.Headers[i].Value = value
			return
		}
	}
	m.AddHeader(name, value)
}

func (m *Message) GetHeader(name string) string {
	ln := strings.ToLower(name)
	short := compactName(ln)
	for _, h := range m.Headers {
		hl := strings.ToLower(h.Name)
		if hl == ln || (short != "" && hl == short) {
			return h.Value
		}
	}
	return ""
}

func (m *Message) GetAllHeaders(name string) []string {
	ln := strings.ToLower(name)
	short := compactName(ln)
	var out []string
	for _, h := range m.Headers {
		hl := strings.ToLower(h.Name)
		if hl == ln || (short != "" && hl == short) {
			out = append(out, h.Value)
		}
	}
	return out
}

func compactName(lower string) string {
	switch lower {
	case "via":
		return "v"
	case "from":
		return "f"
	case "to":
		return "t"
	case "call-id":
		return "i"
	case "contact":
		return "m"
	case "content-type":
		return "c"
	case "content-length":
		return "l"
	default:
		return ""
	}
}

func (m *Message) Encode() []byte {
	var buf bytes.Buffer
	if m.IsResponse {
		fmt.Fprintf(&buf, "SIP/2.0 %d %s\r\n", m.StatusCode, m.Reason)
	} else {
		fmt.Fprintf(&buf, "%s %s SIP/2.0\r\n", m.Method, m.RequestURI)
	}
	hasCL := false
	for _, h := range m.Headers {
		if strings.EqualFold(h.Name, "Content-Length") || strings.EqualFold(h.Name, "l") {
			hasCL = true
		}
		fmt.Fprintf(&buf, "%s: %s\r\n", h.Name, h.Value)
	}
	if !hasCL {
		fmt.Fprintf(&buf, "Content-Length: %d\r\n", len(m.Body))
	}
	buf.WriteString("\r\n")
	buf.Write(m.Body)
	return buf.Bytes()
}

func ParseMessage(raw []byte) (*Message, error) {
	sep := bytes.Index(raw, []byte("\r\n\r\n"))
	sepLen := 4
	if sep < 0 {
		sep = bytes.Index(raw, []byte("\n\n"))
		sepLen = 2
	}
	if sep < 0 {
		return nil, errors.New("SIP_INCOMPLETE_HEADERS")
	}
	headStr := strings.ReplaceAll(string(raw[:sep]), "\r\n", "\n")
	lines := strings.Split(headStr, "\n")
	if len(lines) == 0 || strings.TrimSpace(lines[0]) == "" {
		return nil, errors.New("SIP_EMPTY_START_LINE")
	}
	msg := &Message{}
	start := strings.TrimSpace(lines[0])
	if strings.HasPrefix(start, "SIP/2.0 ") {
		msg.IsResponse = true
		rest := strings.TrimPrefix(start, "SIP/2.0 ")
		sp := strings.IndexByte(rest, ' ')
		codeStr := rest
		if sp > 0 {
			codeStr = rest[:sp]
			msg.Reason = strings.TrimSpace(rest[sp+1:])
		}
		code, err := strconv.Atoi(codeStr)
		if err != nil {
			return nil, errors.New("SIP_INVALID_STATUS_CODE")
		}
		msg.StatusCode = code
	} else {
		parts := strings.SplitN(start, " ", 3)
		if len(parts) < 3 {
			return nil, errors.New("SIP_INVALID_REQUEST_LINE")
		}
		msg.Method = parts[0]
		msg.RequestURI = parts[1]
	}

	for _, line := range lines[1:] {
		if line == "" {
			continue
		}
		colon := strings.IndexByte(line, ':')
		if colon <= 0 {
			continue
		}
		msg.Headers = append(msg.Headers, HeaderKV{
			Name:  strings.TrimSpace(line[:colon]),
			Value: strings.TrimSpace(line[colon+1:]),
		})
	}
	body := raw[sep+sepLen:]
	if clStr := msg.GetHeader("Content-Length"); clStr != "" {
		if cl, err := strconv.Atoi(strings.TrimSpace(clStr)); err == nil && cl >= 0 && cl <= len(body) {
			body = body[:cl]
		}
	}
	msg.Body = append([]byte(nil), body...)
	return msg, nil
}

// ParseDigestParams parses a SIP WWW-Authenticate / Authorization Digest header value.
func ParseDigestParams(header string) map[string]string {
	out := make(map[string]string)
	s := strings.TrimSpace(header)
	if strings.HasPrefix(strings.ToLower(s), "digest ") {
		s = strings.TrimSpace(s[7:])
	}
	for len(s) > 0 {
		s = strings.TrimLeft(s, " ,")
		eq := strings.IndexByte(s, '=')
		if eq <= 0 {
			break
		}
		k := strings.ToLower(strings.TrimSpace(s[:eq]))
		s = strings.TrimSpace(s[eq+1:])
		var v string
		if len(s) > 0 && s[0] == '"' {
			s = s[1:]
			end := strings.IndexByte(s, '"')
			if end < 0 {
				v = s
				s = ""
			} else {
				v = s[:end]
				s = s[end+1:]
			}
		} else {
			comma := strings.IndexByte(s, ',')
			if comma < 0 {
				v = strings.TrimSpace(s)
				s = ""
			} else {
				v = strings.TrimSpace(s[:comma])
				s = s[comma+1:]
			}
		}
		out[k] = v
	}
	return out
}

// DecodeAKANonce extracts RAND(16) and AUTN(16) from a 3GPP IMS Digest-AKA Base64 nonce.
func DecodeAKANonce(nonceB64 string) ([]byte, []byte, error) {
	raw, err := base64.StdEncoding.DecodeString(nonceB64)
	if err != nil {
		return nil, nil, fmt.Errorf("SIP_AKA_NONCE_B64: %w", err)
	}
	if len(raw) < 32 {
		return nil, nil, fmt.Errorf("SIP_AKA_NONCE_SHORT_%d", len(raw))
	}
	return append([]byte(nil), raw[0:16]...), append([]byte(nil), raw[16:32]...), nil
}

// ComputeAKADigest computes RFC 3310 AKAv1-MD5 (or RFC 4169 AKAv2-MD5) Digest response.
func ComputeAKADigest(username, realm, method, uri, nonce, nc, cnonce, qop, algorithm string, res, ik, ck []byte) string {
	passwd := res
	if strings.EqualFold(algorithm, "AKAv2-MD5") && len(ik) > 0 && len(ck) > 0 {
		key := append(append([]byte(nil), res...), ik...)
		key = append(key, ck...)
		m := hmac.New(md5.New, key)
		m.Write([]byte("http-digest-akav2-password"))
		passwd = []byte(base64.StdEncoding.EncodeToString(m.Sum(nil)))
	}
	h1 := md5.New()
	h1.Write([]byte(username + ":" + realm + ":"))
	h1.Write(passwd)
	ha1 := hex.EncodeToString(h1.Sum(nil))

	h2 := md5.Sum([]byte(method + ":" + uri))
	ha2 := hex.EncodeToString(h2[:])

	var combined string
	if qop != "" {
		combined = fmt.Sprintf("%s:%s:%s:%s:%s:%s", ha1, nonce, nc, cnonce, qop, ha2)
	} else {
		combined = fmt.Sprintf("%s:%s:%s", ha1, nonce, ha2)
	}
	final := md5.Sum([]byte(combined))
	return hex.EncodeToString(final[:])
}

// ExtractCallerNumber extracts a sanitized caller number from SIP From / P-Asserted-Identity,
// respecting RFC 3323 Privacy: id / anonymous identities.
func ExtractCallerNumber(msg *Message) string {
	if strings.Contains(strings.ToLower(msg.GetHeader("Privacy")), "id") {
		return ""
	}
	for _, hdr := range []string{"P-Asserted-Identity", "From"} {
		val := msg.GetHeader(hdr)
		if val == "" || strings.Contains(strings.ToLower(val), "anonymous") {
			continue
		}
		if num := extractNumberFromURI(val); num != "" {
			return num
		}
	}
	return ""
}

func extractNumberFromURI(val string) string {
	low := strings.ToLower(val)
	for _, prefix := range []string{"tel:", "sip:"} {
		idx := strings.Index(low, prefix)
		if idx < 0 {
			continue
		}
		rest := val[idx+len(prefix):]
		for i, ch := range rest {
			if ch == '@' || ch == ';' || ch == '>' || ch == ' ' {
				rest = rest[:i]
				break
			}
		}
		if len(rest) >= 1 && len(rest) <= 21 {
			valid := true
			for i, ch := range rest {
				if i == 0 && ch == '+' {
					continue
				}
				if ch < '0' || ch > '9' {
					valid = false
					break
				}
			}
			if valid {
				return rest
			}
		}
	}
	return ""
}

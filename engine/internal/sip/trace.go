package sip

import (
	"encoding/base64"
	"fmt"
	"net"
	"regexp"
	"strings"

	"github.com/YubaiNya/SmartVoWifiCall/engine/internal/diag"
)

var traceIPv4 = regexp.MustCompile(`\b(?:[0-9]{1,3}\.){3}[0-9]{1,3}\b`)
var traceIPv6 = regexp.MustCompile(`\[[0-9a-fA-F:]+\]`)
var traceBareIPv6 = regexp.MustCompile(`(?i)(?:[0-9a-f]{0,4}:){2,}[0-9a-f:.]*`)
var traceIMEI = regexp.MustCompile(`(?i)(urn:gsma:imei:)[0-9-]+`)
var traceDisplayName = regexp.MustCompile(`"[^"\r\n]*"\s*<`)

func traceHeader(name, value string) any {
	n := strings.ToLower(name)
	switch n {
	case "security-client", "security-server", "security-verify":
		// Negotiated algorithms, SPIs and ports, never key bytes.
		var entries []map[string]any
		for _, entry := range strings.Split(value, ",") {
			parts := strings.Split(entry, ";")
			item := map[string]any{"mechanism": strings.TrimSpace(parts[0])}
			for _, part := range parts[1:] {
				kv := strings.SplitN(strings.TrimSpace(part), "=", 2)
				if len(kv) != 2 {
					continue
				}
				k := strings.ToLower(kv[0])
				switch k {
				case "alg", "ealg", "prot", "mod", "spi-c", "spi-s", "port-c", "port-s", "q":
					item[k] = diag.Sanitize(kv[1])
				default:
					item[k] = diag.Hidden
				}
			}
			entries = append(entries, item)
		}
		return entries
	case "authorization", "proxy-authorization", "www-authenticate", "proxy-authenticate", "authentication-info":
		fields := ParseDigestParams(value)
		scheme := ""
		if words := strings.Fields(value); len(words) > 0 {
			scheme = words[0]
		}
		out := map[string]any{"scheme": scheme}
		for k, v := range fields {
			switch strings.ToLower(k) {
			case "nonce":
				out[k] = diag.Hidden
				// AKA nonce combines public RAND with private authentication-token parts.
				// Preserve the public portions even though the original encoded token is masked.
				if strings.HasPrefix(strings.ToUpper(fields["algorithm"]), "AKA") {
					if b, err := base64.StdEncoding.DecodeString(v); err == nil && len(b) >= 32 {
						out["noncePublicParts"] = map[string]any{"encoding": "base64_aka", "decodedLength": len(b), "randHex": fmt.Sprintf("% x", b[:16]), "amfHex": fmt.Sprintf("% x", b[22:24]), "sequenceXorAK": diag.Hidden, "authenticationToken": diag.Hidden}
					}
				}
			case "response", "rspauth", "cnonce", "auts", "opaque":
				out[k] = diag.Hidden
			case "username":
				if strings.Contains(v, "@") {
					out[k] = diag.Identity(v)
				} else {
					out[k] = diag.Hidden
				}
			case "uri":
				out[k] = diag.Identity(v)
			case "realm", "algorithm", "qop", "nc", "stale":
				out[k] = diag.Sanitize(v)
			default:
				out[k] = diag.Hidden
			}
		}
		return out
	case "via", "v", "from", "f", "to", "t", "contact", "m", "route", "record-route", "path", "service-route", "p-associated-uri", "p-preferred-identity", "p-asserted-identity", "call-id", "i":
		s := traceDisplayName.ReplaceAllString(value, `"`+diag.Hidden+`" <`)
		s = traceIMEI.ReplaceAllString(s, "${1}"+diag.Hidden)
		s = diag.Identity(s)
		s = traceIPv4.ReplaceAllString(s, diag.Hidden)
		s = traceIPv6.ReplaceAllString(s, diag.Hidden)
		s = traceBareIPv6.ReplaceAllStringFunc(s, func(candidate string) string {
			if ip := net.ParseIP(candidate); ip != nil && ip.To4() == nil {
				return diag.Hidden
			}
			return candidate
		})
		return s
	case "cseq", "expires", "min-expires", "max-forwards", "content-length", "l", "content-type", "c", "allow", "supported", "k", "require", "proxy-require", "user-agent", "server", "retry-after", "www-authenticate-error":
		return diag.Sanitize(value)
	case "p-access-network-info":
		// Access technology is useful, cell/location identifiers are private.
		parts := strings.Split(value, ";")
		out := []string{parts[0]}
		for _, p := range parts[1:] {
			kv := strings.SplitN(p, "=", 2)
			if len(kv) == 2 {
				out = append(out, kv[0]+"="+diag.Hidden)
			} else {
				out = append(out, p)
			}
		}
		return strings.Join(out, ";")
	default:
		return map[string]any{"value": diag.Hidden, "length": len(value), "redactionReason": "unclassified_header"}
	}
}
func (c *Client) traceRegistration(direction string, m *Message) {
	if c.cfg.Logger == nil || m == nil {
		return
	}
	if m.Method != "REGISTER" && !strings.HasSuffix(strings.ToUpper(strings.TrimSpace(m.GetHeader("CSeq"))), " REGISTER") {
		return
	}
	headers := make([]map[string]any, 0, len(m.Headers))
	for _, h := range m.Headers {
		v := any("")
		if h.Value != "" {
			v = traceHeader(h.Name, h.Value)
		}
		headers = append(headers, map[string]any{"name": h.Name, "value": v})
	}
	row := map[string]any{"protocol": "sip", "stage": "registration", "slot": c.cfg.Slot, "session": c.epoch, "direction": direction, "method": m.Method, "requestURI": diag.Identity(m.RequestURI), "status": m.StatusCode, "reason": diag.Sanitize(m.Reason), "headers": headers, "bodyLength": len(m.Body), "body": ""}
	if len(m.Body) > 0 {
		row["body"] = diag.Hidden
		row["bodyRedactionReason"] = "unclassified_register_body"
	}
	c.cfg.Logger.Auth(row)
}

package sip

import (
	"net"
	"strconv"
	"strings"
)

// sameContactURI compares the SIP Contact forms emitted by this client using
// RFC 3261 section 19.1.4. It never resolves hostnames or ignores a different
// subscriber, port, transport or binding-critical parameter. URI headers are
// not emitted by this client and are conservatively required to match exactly.
func sameContactURI(a, b string) bool {
	x, ok := parseContactURI(a)
	if !ok {
		return false
	}
	y, ok := parseContactURI(b)
	if !ok {
		return false
	}
	if x.scheme != y.scheme || x.user != y.user || x.host != y.host || x.port != y.port || x.headers != y.headers {
		return false
	}
	for _, name := range []string{"transport", "user", "ttl", "method", "maddr"} {
		xv, xok := x.params[name]
		yv, yok := y.params[name]
		if xok != yok || xv != yv {
			return false
		}
	}
	for name, value := range x.params {
		if other, exists := y.params[name]; exists && value != other {
			return false
		}
	}
	return true
}

type contactURI struct {
	scheme, user, host, port, headers string
	params                            map[string]string
}

func parseContactURI(raw string) (contactURI, bool) {
	var out contactURI
	scheme, tail, ok := strings.Cut(raw, ":")
	out.scheme = strings.ToLower(scheme)
	if !ok || (out.scheme != "sip" && out.scheme != "sips") {
		return out, false
	}
	// The generated Contact always has a subscriber and no URI headers.
	at := strings.LastIndexByte(tail, '@')
	if at <= 0 {
		return out, false
	}
	out.user, ok = normalizeEscapes(tail[:at])
	if !ok {
		return out, false
	}
	tail = tail[at+1:]
	if i := strings.IndexByte(tail, '?'); i >= 0 {
		out.headers = tail[i+1:]
		tail = tail[:i]
	}
	parts := strings.Split(tail, ";")
	hostPort := parts[0]
	out.params = make(map[string]string)
	for _, part := range parts[1:] {
		name, value, _ := strings.Cut(part, "=")
		name, ok = normalizeEscapes(name)
		if !ok || name == "" {
			return out, false
		}
		name = strings.ToLower(name)
		if _, exists := out.params[name]; exists {
			return out, false
		}
		value, ok = normalizeEscapes(value)
		if !ok {
			return out, false
		}
		if name == "transport" || name == "user" || name == "maddr" {
			value = strings.ToLower(value)
		}
		out.params[name] = value
	}
	if strings.HasPrefix(hostPort, "[") {
		end := strings.IndexByte(hostPort, ']')
		if end < 0 {
			return out, false
		}
		ip := net.ParseIP(hostPort[1:end])
		if ip == nil {
			return out, false
		}
		out.host = ip.String()
		rest := hostPort[end+1:]
		if rest != "" {
			if !strings.HasPrefix(rest, ":") {
				return out, false
			}
			out.port, ok = contactPort(rest[1:])
			if !ok {
				return out, false
			}
		}
	} else {
		host, port, hasPort := strings.Cut(hostPort, ":")
		if host == "" || strings.ContainsAny(host, " []\t\r\n") {
			return out, false
		}
		out.host = strings.ToLower(host)
		if hasPort {
			out.port, ok = contactPort(port)
			if !ok {
				return out, false
			}
		}
	}
	return out, true
}

func contactPort(value string) (string, bool) {
	if value == "" {
		return "", false
	}
	for _, c := range value {
		if c < '0' || c > '9' {
			return "", false
		}
	}
	p, err := strconv.ParseUint(value, 10, 16)
	return strconv.FormatUint(p, 10), err == nil
}

// Only unreserved escapes may be decoded without changing the URI's meaning.
func normalizeEscapes(value string) (string, bool) {
	var out strings.Builder
	for i := 0; i < len(value); i++ {
		if value[i] != '%' {
			out.WriteByte(value[i])
			continue
		}
		if i+2 >= len(value) {
			return "", false
		}
		n, err := strconv.ParseUint(value[i+1:i+3], 16, 8)
		if err != nil {
			return "", false
		}
		ch := byte(n)
		if (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9') || strings.ContainsRune("-_.!~*'()", rune(ch)) {
			out.WriteByte(ch)
		} else {
			out.WriteByte('%')
			out.WriteString(strings.ToUpper(value[i+1 : i+3]))
		}
		i += 2
	}
	return out.String(), true
}

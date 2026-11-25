package ikev2

import (
	"encoding/binary"
	"encoding/hex"
	"fmt"
	"strings"

	"github.com/YubaiNya/SmartVoWifiCall/engine/internal/diag"
)

func traceHex(b []byte) string {
	s := hex.EncodeToString(b)
	var out strings.Builder
	for i := 0; i < len(s); i += 2 {
		if i > 0 {
			out.WriteByte(' ')
		}
		out.WriteString(s[i : i+2])
	}
	return out.String()
}
func payloadName(t byte) string {
	names := map[byte]string{33: "SA", 34: "KE", 35: "IDi", 36: "IDr", 37: "CERT", 38: "CERTREQ", 39: "AUTH", 40: "NONCE", 41: "NOTIFY", 42: "DELETE", 43: "VENDOR", 44: "TSi", 45: "TSr", 46: "SK", 47: "CP", 48: "EAP", 53: "SKF"}
	if s, ok := names[t]; ok {
		return s
	}
	return "UNKNOWN"
}
func notifyName(n uint16) string {
	names := map[uint16]string{14: "NO_PROPOSAL_CHOSEN", 17: "INVALID_KE_PAYLOAD", 24: "AUTHENTICATION_FAILED", 36: "INTERNAL_ADDRESS_FAILURE", 37: "FAILED_CP_REQUIRED", 38: "TS_UNACCEPTABLE", 16384: "INITIAL_CONTACT", 16388: "NAT_DETECTION_SOURCE_IP", 16389: "NAT_DETECTION_DESTINATION_IP", 16390: "COOKIE", 16396: "MOBIKE_SUPPORTED", 16417: "EAP_ONLY_AUTHENTICATION", 41041: "BACKOFF_TIMER", 41101: "DEVICE_IDENTITY"}
	if s, ok := names[n]; ok {
		return s
	}
	return "UNKNOWN"
}
func tracePayloads(ps []Payload) []map[string]any {
	out := make([]map[string]any, 0, len(ps))
	for _, p := range ps {
		b := p.Body
		r := map[string]any{"type": p.Type, "name": payloadName(p.Type), "bodyLength": len(b)}
		switch p.Type {
		case PayloadSA, PayloadKE, PayloadNonce, PayloadDelete, 37, 38, 43:
			// Proposals, public DH values, public IKE nonces and SA identifiers are not keys.
			r["dataHex"] = traceHex(b)
		case PayloadIDi, PayloadIDr:
			if len(b) >= 4 {
				r["idType"] = b[0]
				r["reservedHex"] = traceHex(b[1:4])
				r["identity"] = diag.Identity(string(b[4:]))
			} else {
				r["malformed"] = true
			}
		case PayloadAUTH:
			if len(b) >= 4 {
				r["method"] = b[0]
				r["reservedHex"] = traceHex(b[1:4])
				r["proofLength"] = len(b) - 4
			}
			r["proof"] = diag.Hidden
		case PayloadNotify:
			if len(b) < 4 {
				r["malformed"] = true
				break
			}
			n, data, ok := ParseNotify(b)
			r["protocolId"] = b[0]
			r["spiSize"] = b[1]
			r["notifyType"] = n
			r["notifyName"] = notifyName(n)
			if !ok {
				r["malformed"] = true
				break
			}
			if int(b[1])+4 <= len(b) {
				r["spiHex"] = traceHex(b[4 : 4+int(b[1])])
			}
			r["dataLength"] = len(data)
			switch n {
			case NotifyDeviceIdentity:
				if len(data) >= 3 {
					r["identityLength"] = binary.BigEndian.Uint16(data[:2])
					r["identityType"] = data[2]
					r["identity"] = diag.Hidden
				} else if len(data) > 0 {
					r["malformed"] = true
				}
			case NotifyNATDetectionSourceIP, NotifyNATDetectionDestIP:
				r["addressHash"] = diag.Hidden // hashes can disclose low-entropy endpoint addresses
			default:
				if len(data) == 0 || n == 17 || n == 16390 || n == 41041 {
					r["dataHex"] = traceHex(data)
				} else {
					r["opaqueData"] = diag.Hidden
					r["redactionReason"] = "unclassified_notify_data"
				}
			}
		case PayloadCP:
			if len(b) < 4 {
				r["malformed"] = true
				break
			}
			r["configType"] = b[0]
			r["reservedHex"] = traceHex(b[1:4])
			var attrs []map[string]any
			for off := 4; off < len(b); {
				if off+4 > len(b) {
					r["malformed"] = true
					break
				}
				typ := binary.BigEndian.Uint16(b[off:])
				n := int(binary.BigEndian.Uint16(b[off+2:]))
				a := map[string]any{"type": typ, "length": n}
				off += 4
				if off+n > len(b) {
					r["malformed"] = true
					break
				}
				// Values can contain assigned UE addresses; request attributes are usually empty.
				if n == 0 || typ == 2 || typ == 3 || typ == 5 || typ == 10 || typ == 13 || typ == 15 || typ == 20 || typ == 21 {
					a["dataHex"] = traceHex(b[off : off+n])
				} else {
					a["value"] = diag.Hidden
					if typ == 8 && n == 17 {
						a["prefixLength"] = b[off+16]
					}
				}
				attrs = append(attrs, a)
				off += n
			}
			r["attributes"] = attrs
		case PayloadTSi, PayloadTSr:
			if len(b) < 4 {
				r["malformed"] = true
				break
			}
			r["count"] = b[0]
			r["reservedHex"] = traceHex(b[1:4])
			var selectors []map[string]any
			for off := 4; off < len(b); {
				if off+8 > len(b) {
					r["malformed"] = true
					break
				}
				n := int(binary.BigEndian.Uint16(b[off+2:]))
				if n < 8 || off+n > len(b) {
					r["malformed"] = true
					break
				}
				a := map[string]any{"type": b[off], "protocolId": b[off+1], "length": n, "startPort": binary.BigEndian.Uint16(b[off+4:]), "endPort": binary.BigEndian.Uint16(b[off+6:])}
				addr := b[off+8 : off+n]
				wild := len(addr) == 8 && binary.BigEndian.Uint32(addr) == 0 && binary.BigEndian.Uint32(addr[4:]) == 0xffffffff
				if wild {
					a["addressRange"] = "any_ipv4"
					a["addressRangeHex"] = traceHex(addr)
				} else {
					a["addressRange"] = diag.Hidden
				}
				selectors = append(selectors, a)
				off += n
			}
			r["selectors"] = selectors
		case PayloadEAP:
			r["eap"] = traceEAP(b)
		default:
			// Never emit opaque bytes that could encode subscriber identities or credentials.
			r["opaqueData"] = diag.Hidden
			r["redactionReason"] = "unclassified_payload"
		}
		out = append(out, r)
	}
	return out
}
func traceEAP(b []byte) map[string]any {
	r := map[string]any{}
	if len(b) < 4 {
		r["malformed"] = true
		return r
	}
	r["code"] = b[0]
	r["identifier"] = b[1]
	r["length"] = binary.BigEndian.Uint16(b[2:4])
	if len(b) < 5 {
		return r
	}
	r["methodType"] = b[4]
	if b[4] == 1 {
		r["identity"] = diag.Identity(string(b[5:]))
		return r
	}
	if (b[4] != 23 && b[4] != 50) || len(b) < 8 {
		r["opaqueData"] = diag.Hidden
		return r
	}
	r["subtype"] = b[5]
	r["reservedHex"] = traceHex(b[6:8])
	var attrs []map[string]any
	for off := 8; off < len(b); {
		if off+4 > len(b) {
			r["malformed"] = true
			break
		}
		typ := b[off]
		n := int(b[off+1]) * 4
		if n < 4 || off+n > len(b) {
			r["malformed"] = true
			break
		}
		a := map[string]any{"type": typ, "length": n, "headerHex": traceHex(b[off : off+4])}
		data := b[off+4 : off+n]
		switch typ {
		case ATRand, 129:
			a["dataHex"] = traceHex(data) // RAND and IV are public challenges
		case ATAutn:
			a["sequenceXorAK"] = diag.Hidden
			if len(data) >= 8 {
				a["amfHex"] = traceHex(data[6:8])
			}
			a["authenticationToken"] = diag.Hidden
		case 14:
			l := int(binary.BigEndian.Uint16(b[off+2 : off+4]))
			if l > len(data) {
				a["malformed"] = true
			} else {
				a["identity"] = diag.Identity(string(data[:l]))
				a["paddingHex"] = traceHex(data[l:])
			}
		case 6, 7, 10, 12, 13, 15, 16, 19, 20, 21, 22, 23, 24, 135, 136:
			a["dataHex"] = traceHex(data)
		default:
			// RES, MAC, AUTS, encrypted pseudonyms and unknown extension values.
			a["value"] = diag.Hidden
			a["redactionReason"] = "authentication_or_identity_material"
			if typ == ATAuts {
				a["headerHex"] = traceHex(b[off:off+2]) + " " + diag.Hidden
			}
		}
		attrs = append(attrs, a)
		off += n
	}
	r["attributes"] = attrs
	return r
}

func (c *Client) traceIKE(direction string, raw []byte, attempt int) {
	if c.cfg.Logger == nil || len(raw) < 28 {
		return
	}
	c.mu.Lock()
	keys := c.keys
	c.mu.Unlock()
	h, p, err := DecodePacket(raw)
	row := map[string]any{"protocol": "ikev2", "slot": c.cfg.Slot, "session": fmt.Sprintf("ike%016x", c.spii), "direction": direction, "exchange": h.Exchange, "messageId": h.MessageID, "flags": h.Flags, "wireLength": len(raw), "attempt": attempt, "profile": c.cfg.ProfileTag}
	row["spiI"] = fmt.Sprintf("0x%016x", h.SPIi)
	row["spiR"] = fmt.Sprintf("0x%016x", h.SPIr)
	row["version"] = h.Version
	row["nextPayload"] = h.NextPayload
	row["declaredLength"] = h.Length
	if h.Exchange != ExchangeIKESAInit && h.Exchange != ExchangeIKEAuth {
		return
	}
	if h.Exchange == ExchangeIKESAInit {
		row["stage"] = "ike_sa_init"
	} else {
		row["stage"] = "ike_auth"
	}
	if err == nil && h.NextPayload == PayloadSK && keys != nil {
		_, p, err = DecodeEncryptedPacket(raw, keys, direction == "out")
		row["encrypted"] = true
	}
	if err != nil {
		row["decodeError"] = diag.Sanitize(err.Error())
	} else {
		row["payloads"] = tracePayloads(p)
	}
	c.cfg.Logger.Auth(row)
}

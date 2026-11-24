package ikev2

import (
	"bytes"
	"crypto/aes"
	"crypto/cipher"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/binary"
	"errors"
	"fmt"
	"net"
)

const (
	Version20 = 0x20

	ExchangeIKESAInit      = 34
	ExchangeIKEAuth        = 35
	ExchangeCreateChildSA  = 36
	ExchangeInformational  = 37

	FlagInitiator = 0x08
	FlagResponse  = 0x20

	PayloadNone   byte = 0
	PayloadSA     byte = 33
	PayloadKE     byte = 34
	PayloadIDi    byte = 35
	PayloadIDr    byte = 36
	PayloadAUTH   byte = 39
	PayloadNonce  byte = 40
	PayloadNotify byte = 41
	PayloadDelete byte = 42
	PayloadTSi    byte = 44
	PayloadTSr    byte = 45
	PayloadSK     byte = 46
	PayloadCP     byte = 47
	PayloadEAP    byte = 48

	NotifyNATDetectionSourceIP = 16388
	NotifyNATDetectionDestIP   = 16389

	CFGRequest byte = 1
	CFGReply   byte = 2

	AttrInternalIP4Addr  uint16 = 1
	AttrInternalIP4DNS   uint16 = 3
	AttrInternalIP6Addr  uint16 = 8
	AttrPCSCFIP4Addr     uint16 = 20 // 3GPP TS 24.302
	AttrPCSCFIP6Addr     uint16 = 21

	EAPCodeRequest  byte = 1
	EAPCodeResponse byte = 2
	EAPCodeSuccess  byte = 3
	EAPCodeFailure  byte = 4

	EAPTypeAKA      byte = 23
	EAPTypeAKAPrime byte = 50

	AKAChallenge       byte = 1
	AKASyncFailure     byte = 4
	AKAAuthReject      byte = 2
	AKAClientError     byte = 14
	ATANotification    byte = 12
	ATRand             byte = 1
	ATAutn             byte = 2
	ATRes              byte = 3
	ATAuts             byte = 4
	ATMac              byte = 11
	ATKdfInput         byte = 23
	ATKdf              byte = 24
)

type Header struct {
	SPIi        uint64
	SPIr        uint64
	NextPayload byte
	Version     byte
	Exchange    byte
	Flags       byte
	MessageID   uint32
	Length      uint32
}

type Payload struct {
	Type byte
	Body []byte
}

func EncodePacket(h Header, payloads []Payload) []byte {
	body := encodePayloads(payloads)
	h.Version = Version20
	if len(payloads) > 0 {
		h.NextPayload = payloads[0].Type
	} else {
		h.NextPayload = PayloadNone
	}
	h.Length = uint32(28 + len(body))
	out := make([]byte, 28+len(body))
	binary.BigEndian.PutUint64(out[0:8], h.SPIi)
	binary.BigEndian.PutUint64(out[8:16], h.SPIr)
	out[16] = h.NextPayload
	out[17] = h.Version
	out[18] = h.Exchange
	out[19] = h.Flags
	binary.BigEndian.PutUint32(out[20:24], h.MessageID)
	binary.BigEndian.PutUint32(out[24:28], h.Length)
	copy(out[28:], body)
	return out
}

func EncodeEncryptedPacket(h Header, innerPayloads []Payload, keys *IKEKeys, initiator bool) ([]byte, error) {
	innerNext := PayloadNone
	if len(innerPayloads) > 0 {
		innerNext = innerPayloads[0].Type
	}
	plain := encodePayloads(innerPayloads)

	encKey := keys.SKei
	authKey := keys.SKai
	if !initiator {
		encKey = keys.SKer
		authKey = keys.SKar
	}

	h.Version = Version20
	h.NextPayload = PayloadSK

	if keys.AEAD {
		blk, err := aes.NewCipher(encKey[:16])
		if err != nil {
			return nil, err
		}
		aead, err := cipher.NewGCM(blk)
		if err != nil {
			return nil, err
		}
		padLen := (4 - ((len(plain) + 1) % 4)) % 4
		padded := make([]byte, len(plain)+padLen+1)
		copy(padded, plain)
		padded[len(padded)-1] = byte(padLen)

		iv := make([]byte, 8)
		_, _ = rand.Read(iv)
		var nonce [12]byte
		copy(nonce[0:4], encKey[16:20])
		copy(nonce[4:12], iv)

		skLen := 4 + len(iv) + len(padded) + aead.Overhead()
		totalLen := 28 + skLen
		aad := make([]byte, 32)
		binary.BigEndian.PutUint64(aad[0:8], h.SPIi)
		binary.BigEndian.PutUint64(aad[8:16], h.SPIr)
		aad[16] = PayloadSK
		aad[17] = Version20
		aad[18] = h.Exchange
		aad[19] = h.Flags
		binary.BigEndian.PutUint32(aad[20:24], h.MessageID)
		binary.BigEndian.PutUint32(aad[24:28], uint32(totalLen))
		aad[28] = innerNext
		aad[29] = 0
		binary.BigEndian.PutUint16(aad[30:32], uint16(skLen))

		sealed := aead.Seal(nil, nonce[:], padded, aad)
		out := make([]byte, 0, totalLen)
		out = append(out, aad...)
		out = append(out, iv...)
		out = append(out, sealed...)
		return out, nil
	}

	blk, err := aes.NewCipher(encKey)
	if err != nil {
		return nil, err
	}
	padLen := (aes.BlockSize - ((len(plain) + 1) % aes.BlockSize)) % aes.BlockSize
	padded := make([]byte, len(plain)+padLen+1)
	copy(padded, plain)
	padded[len(padded)-1] = byte(padLen)

	iv := make([]byte, aes.BlockSize)
	_, _ = rand.Read(iv)
	ct := make([]byte, len(padded))
	cipher.NewCBCEncrypter(blk, iv).CryptBlocks(ct, padded)

	skLen := 4 + len(iv) + len(ct) + 16
	totalLen := 28 + skLen
	out := make([]byte, totalLen)
	binary.BigEndian.PutUint64(out[0:8], h.SPIi)
	binary.BigEndian.PutUint64(out[8:16], h.SPIr)
	out[16] = PayloadSK
	out[17] = Version20
	out[18] = h.Exchange
	out[19] = h.Flags
	binary.BigEndian.PutUint32(out[20:24], h.MessageID)
	binary.BigEndian.PutUint32(out[24:28], uint32(totalLen))
	out[28] = innerNext
	out[29] = 0
	binary.BigEndian.PutUint16(out[30:32], uint16(skLen))
	copy(out[32:32+len(iv)], iv)
	copy(out[32+len(iv):32+len(iv)+len(ct)], ct)

	mac := hmac.New(sha256.New, authKey)
	mac.Write(out[:len(out)-16])
	copy(out[len(out)-16:], mac.Sum(nil)[:16])
	return out, nil
}

func DecodePacket(raw []byte) (Header, []Payload, error) {
	if len(raw) < 28 {
		return Header{}, nil, errors.New("IKE_PACKET_SHORT")
	}
	h := Header{
		SPIi:        binary.BigEndian.Uint64(raw[0:8]),
		SPIr:        binary.BigEndian.Uint64(raw[8:16]),
		NextPayload: raw[16],
		Version:     raw[17],
		Exchange:    raw[18],
		Flags:       raw[19],
		MessageID:   binary.BigEndian.Uint32(raw[20:24]),
		Length:      binary.BigEndian.Uint32(raw[24:28]),
	}
	if int(h.Length) > len(raw) || h.Length < 28 {
		return h, nil, errors.New("IKE_LENGTH_INVALID")
	}
	payloads, err := decodePayloads(h.NextPayload, raw[28:h.Length])
	return h, payloads, err
}

func DecodeEncryptedPacket(raw []byte, keys *IKEKeys, fromInitiator bool) (Header, []Payload, error) {
	h, outer, err := DecodePacket(raw)
	if err != nil {
		return h, nil, err
	}
	if len(outer) == 0 || outer[0].Type != PayloadSK {
		return h, outer, nil
	}
	innerNext := raw[28]
	plain, err := verifyAndDecryptSK(keys, fromInitiator, raw[:h.Length], 28)
	if err != nil {
		return h, nil, err
	}
	inner, err := decodePayloads(innerNext, plain)
	return h, inner, err
}

func encodePayloads(payloads []Payload) []byte {
	var buf bytes.Buffer
	for i, p := range payloads {
		next := PayloadNone
		if i+1 < len(payloads) {
			next = payloads[i+1].Type
		}
		var hdr [4]byte
		hdr[0] = next
		hdr[1] = 0
		binary.BigEndian.PutUint16(hdr[2:4], uint16(4+len(p.Body)))
		buf.Write(hdr[:])
		buf.Write(p.Body)
	}
	return buf.Bytes()
}

func decodePayloads(firstType byte, data []byte) ([]Payload, error) {
	var out []Payload
	cur := firstType
	off := 0
	for cur != PayloadNone {
		if off+4 > len(data) {
			return nil, errors.New("IKE_PAYLOAD_HEADER_TRUNCATED")
		}
		next := data[off]
		plen := int(binary.BigEndian.Uint16(data[off+2 : off+4]))
		if plen < 4 || off+plen > len(data) {
			return nil, fmt.Errorf("IKE_PAYLOAD_LEN_INVALID_%d", plen)
		}
		body := make([]byte, plen-4)
		copy(body, data[off+4:off+plen])
		out = append(out, Payload{Type: cur, Body: body})
		off += plen
		if cur == PayloadSK {
			// RFC 7296 §3.14: SK is always the final outer payload; its NextPayload byte
			// identifies the first inner encrypted payload, not a following outer payload.
			break
		}
		cur = next
	}
	return out, nil
}

func FindPayload(payloads []Payload, ptype byte) *Payload {
	for i := range payloads {
		if payloads[i].Type == ptype {
			return &payloads[i]
		}
	}
	return nil
}

// BuildIKEProposal builds an IKE_SA SA payload proposing AES-128-CBC + HMAC-SHA256-128 + PRF-HMAC-SHA256 + MODP-2048.
func BuildIKESAProposal(dhGroup uint16) []byte {
	transforms := [][]byte{
		buildTransform(1, 12, 128), // ENCR_AES_CBC 128-bit
		buildTransform(2, 5, 0),    // PRF_HMAC_SHA2_256
		buildTransform(3, 12, 0),   // AUTH_HMAC_SHA2_256_128
		buildTransform(4, dhGroup, 0),
	}
	return buildProposal(1, 1, nil, transforms)
}

// BuildChildESPProposal builds a Child SA ESP proposal (AES-128-GCM or AES-128-CBC+HMAC-SHA256).
func BuildChildESPProposal(spi uint32, gcm bool) []byte {
	var spiBytes [4]byte
	binary.BigEndian.PutUint32(spiBytes[:], spi)
	var transforms [][]byte
	if gcm {
		transforms = [][]byte{
			buildTransform(1, 20, 128), // ENCR_AES_GCM_16 128-bit
			buildTransform(5, 0, 0),    // ESN=No
		}
	} else {
		transforms = [][]byte{
			buildTransform(1, 12, 128), // ENCR_AES_CBC 128-bit
			buildTransform(3, 12, 0),   // AUTH_HMAC_SHA2_256_128
			buildTransform(5, 0, 0),    // ESN=No
		}
	}
	return buildProposal(1, 3, spiBytes[:], transforms)
}

func buildProposal(num, protoID byte, spi []byte, transforms [][]byte) []byte {
	var tbuf bytes.Buffer
	for i, tr := range transforms {
		if i+1 < len(transforms) {
			tr[0] = 3 // more transforms
		} else {
			tr[0] = 0 // last transform
		}
		tbuf.Write(tr)
	}
	propLen := 8 + len(spi) + tbuf.Len()
	out := make([]byte, propLen)
	out[0] = 0 // last proposal
	binary.BigEndian.PutUint16(out[2:4], uint16(propLen))
	out[4] = num
	out[5] = protoID
	out[6] = byte(len(spi))
	out[7] = byte(len(transforms))
	copy(out[8:], spi)
	copy(out[8+len(spi):], tbuf.Bytes())
	return out
}

func buildTransform(tType byte, tID uint16, keyBits uint16) []byte {
	var attrs []byte
	if keyBits > 0 {
		attrs = []byte{0x80, 0x0E, byte(keyBits >> 8), byte(keyBits)} // Key Length attribute
	}
	tLen := 8 + len(attrs)
	out := make([]byte, tLen)
	binary.BigEndian.PutUint16(out[2:4], uint16(tLen))
	out[4] = tType
	binary.BigEndian.PutUint16(out[6:8], tID)
	copy(out[8:], attrs)
	return out
}

// ParseChildSASPI extracts the 4-byte responder ESP SPI from a Child SA payload.
func ParseChildSASPI(saBody []byte) (uint32, bool) {
	if len(saBody) < 12 {
		return 0, false
	}
	spiLen := int(saBody[6])
	if spiLen != 4 || len(saBody) < 8+spiLen {
		return 0, false
	}
	return binary.BigEndian.Uint32(saBody[8:12]), true
}

func BuildKEPayload(dhGroup uint16, pub []byte) []byte {
	out := make([]byte, 4+len(pub))
	binary.BigEndian.PutUint16(out[0:2], dhGroup)
	copy(out[4:], pub)
	return out
}

func BuildIDPayload(idType byte, value string) []byte {
	out := make([]byte, 4+len(value))
	out[0] = idType
	copy(out[4:], value)
	return out
}

func BuildTSAllIPv4() []byte {
	// 1 selector: IPv4 (7), proto 0 (any), len 16, ports 0..65535, 0.0.0.0..255.255.255.255
	out := make([]byte, 4+16)
	out[0] = 1
	out[4] = 7
	binary.BigEndian.PutUint16(out[6:8], 16)
	binary.BigEndian.PutUint16(out[8:10], 0)
	binary.BigEndian.PutUint16(out[10:12], 65535)
	copy(out[12:16], []byte{0, 0, 0, 0})
	copy(out[16:20], []byte{255, 255, 255, 255})
	return out
}

func BuildCFGRequest() []byte {
	var buf bytes.Buffer
	buf.Write([]byte{CFGRequest, 0, 0, 0})
	for _, attr := range []uint16{AttrInternalIP4Addr, AttrInternalIP4DNS, AttrPCSCFIP4Addr} {
		var a [4]byte
		binary.BigEndian.PutUint16(a[0:2], attr)
		binary.BigEndian.PutUint16(a[2:4], 0)
		buf.Write(a[:])
	}
	return buf.Bytes()
}

type CFGResult struct {
	LocalIP net.IP
	DNS     []net.IP
	PCSCF   []net.IP
}

func ParseCFGReply(body []byte) (*CFGResult, error) {
	if len(body) < 4 || body[0] != CFGReply {
		return nil, errors.New("IKE_CFG_REPLY_INVALID")
	}
	res := &CFGResult{}
	off := 4
	for off+4 <= len(body) {
		atype := binary.BigEndian.Uint16(body[off:off+2]) & 0x7FFF
		alen := int(binary.BigEndian.Uint16(body[off+2 : off+4]))
		off += 4
		if off+alen > len(body) {
			break
		}
		val := body[off : off+alen]
		off += alen
		if alen == 4 {
			ip := net.IPv4(val[0], val[1], val[2], val[3]).To4()
			switch atype {
			case AttrInternalIP4Addr:
				res.LocalIP = ip
			case AttrInternalIP4DNS:
				res.DNS = append(res.DNS, ip)
			case AttrPCSCFIP4Addr:
				res.PCSCF = append(res.PCSCF, ip)
			}
		}
	}
	return res, nil
}

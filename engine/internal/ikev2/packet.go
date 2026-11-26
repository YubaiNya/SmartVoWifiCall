package ikev2

import (
	"bytes"
	"crypto/aes"
	"crypto/cipher"
	"crypto/hmac"
	"crypto/rand"
	"encoding/binary"
	"errors"
	"fmt"
	"net"
)

const (
	Version20 = 0x20

	ExchangeIKESAInit     = 34
	ExchangeIKEAuth       = 35
	ExchangeCreateChildSA = 36
	ExchangeInformational = 37

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

	NotifyAuthenticationFailed = 24
	NotifyInitialContact       = 16384
	NotifyNATDetectionSourceIP = 16388
	NotifyNATDetectionDestIP   = 16389
	NotifyMOBIKESupported      = 16396
	NotifyDeviceIdentity       = 41101 // 3GPP TS 24.302 §8.2.9.2

	DeviceIdentityTypeIMEI   byte = 1
	DeviceIdentityTypeIMEISV byte = 2

	CFGRequest byte = 1
	CFGReply   byte = 2

	AttrInternalIP4Addr uint16 = 1
	AttrInternalIP4DNS  uint16 = 3
	AttrInternalIP6Addr uint16 = 8
	AttrInternalIP6DNS  uint16 = 10
	AttrPCSCFIP4Addr    uint16 = 20 // 3GPP TS 24.302
	AttrPCSCFIP6Addr    uint16 = 21

	EAPCodeRequest  byte = 1
	EAPCodeResponse byte = 2
	EAPCodeSuccess  byte = 3
	EAPCodeFailure  byte = 4

	EAPTypeAKA      byte = 23
	EAPTypeAKAPrime byte = 50

	AKAChallenge    byte = 1
	AKASyncFailure  byte = 4
	AKAAuthReject   byte = 2
	AKAClientError  byte = 14
	ATANotification byte = 12
	ATRand          byte = 1
	ATAutn          byte = 2
	ATRes           byte = 3
	ATAuts          byte = 4
	ATMac           byte = 11
	ATKdfInput      byte = 23
	ATKdf           byte = 24
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

	icvLen := keys.ICVLen()
	skLen := 4 + len(iv) + len(ct) + icvLen
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

	mac := hmac.New(keys.IntegHash(), authKey)
	mac.Write(out[:len(out)-icvLen])
	copy(out[len(out)-icvLen:], mac.Sum(nil)[:icvLen])
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
		return h, nil, errors.New("IKE_ENCRYPTED_PAYLOAD_REQUIRED")
	}
	if keys == nil {
		return h, nil, errors.New("IKE_KEYS_REQUIRED")
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

// BuildIKESAProposal builds a single-transform IKE_SA proposal (AES-128-CBC + HMAC-SHA256-128 + PRF-HMAC-SHA256).
func BuildIKESAProposal(dhGroup uint16) []byte {
	transforms := [][]byte{
		buildTransform(1, 12, 128), // ENCR_AES_CBC 128-bit
		buildTransform(2, 5, 0),    // PRF_HMAC_SHA2_256
		buildTransform(3, 12, 0),   // AUTH_HMAC_SHA2_256_128
		buildTransform(4, dhGroup, 0),
	}
	return buildProposal(1, 1, nil, transforms)
}

// BuildMultiIKESAProposal builds a multi-transform IKE_SA offer compatible with real 3GPP carrier ePDGs:
// AES-CBC (256/128), PRF (HMAC-SHA2-256 / HMAC-SHA1), INTEG (HMAC-SHA2-256-128 / HMAC-SHA1-96),
// DH (primary group plus standard fallback groups 14, 5, 2, 19).
func BuildMultiIKESAProposal(dhGroup uint16) []byte {
	dhGroups := []uint16{dhGroup}
	for _, g := range []uint16{DHGroupMODP2048, DHGroupMODP1536, DHGroupMODP1024, DHGroupECP256} {
		if g != dhGroup {
			dhGroups = append(dhGroups, g)
		}
	}
	transforms := [][]byte{
		buildTransform(1, 12, 256), // ENCR_AES_CBC 256-bit
		buildTransform(1, 12, 128), // ENCR_AES_CBC 128-bit
		buildTransform(2, 5, 0),    // PRF_HMAC_SHA2_256
		buildTransform(2, 2, 0),    // PRF_HMAC_SHA1
		buildTransform(3, 12, 0),   // AUTH_HMAC_SHA2_256_128
		buildTransform(3, 2, 0),    // AUTH_HMAC_SHA1_96
	}
	for _, g := range dhGroups {
		transforms = append(transforms, buildTransform(4, g, 0))
	}
	return buildProposal(1, 1, nil, transforms)
}

// BuildChildESPProposal builds a single-transform Child SA ESP proposal (AES-128-GCM or AES-128-CBC+HMAC-SHA256).
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

// BuildMultiChildESPProposal builds a multi-transform Child SA ESP offer (AES-CBC-256/128 + SHA256/SHA1).
func BuildMultiChildESPProposal(spi uint32) []byte {
	var spiBytes [4]byte
	binary.BigEndian.PutUint32(spiBytes[:], spi)
	transforms := [][]byte{
		buildTransform(1, 12, 256), // ENCR_AES_CBC 256-bit
		buildTransform(1, 12, 128), // ENCR_AES_CBC 128-bit
		buildTransform(3, 12, 0),   // AUTH_HMAC_SHA2_256_128
		buildTransform(3, 2, 0),    // AUTH_HMAC_SHA1_96
		buildTransform(5, 0, 0),    // ESN=No
	}
	return buildProposal(1, 3, spiBytes[:], transforms)
}

// ParseSelectedIKESuite parses the responder's chosen IKE_SA transforms from an SA payload.
func ParseSelectedIKESuite(saBody []byte) IKESuite {
	suite := DefaultIKESuite()
	if len(saBody) < 8 {
		return suite
	}
	spiLen := int(saBody[6])
	numTr := int(saBody[7])
	off := 8 + spiLen
	for i := 0; i < numTr && off+8 <= len(saBody); i++ {
		tLen := int(binary.BigEndian.Uint16(saBody[off+2 : off+4]))
		if tLen < 8 || off+tLen > len(saBody) {
			break
		}
		tType := saBody[off+4]
		tID := binary.BigEndian.Uint16(saBody[off+6 : off+8])
		var keyBits uint16
		if tLen >= 12 && saBody[off+8] == 0x80 && saBody[off+9] == 0x0E {
			keyBits = binary.BigEndian.Uint16(saBody[off+10 : off+12])
		}
		switch tType {
		case 1: // ENCR
			if keyBits == 256 {
				suite.EncKeyLen = 32
			} else if keyBits == 128 {
				suite.EncKeyLen = 16
			}
			if tID == 20 {
				suite.AEAD = true
			}
		case 2: // PRF
			suite.PRFID = tID
		case 3: // INTEG
			suite.AuthID = tID
		}
		off += tLen
	}
	return suite
}

// ParseSelectedChildSA extracts responder ESP SPI and chosen encryption/integrity parameters.
func ParseSelectedChildSA(saBody []byte) (spi uint32, encKeyLen int, authID uint16, ok bool) {
	encKeyLen = 16
	authID = AuthAlgHMACSHA256128
	if len(saBody) < 12 {
		return 0, encKeyLen, authID, false
	}
	spiLen := int(saBody[6])
	numTr := int(saBody[7])
	if spiLen != 4 || len(saBody) < 8+spiLen {
		return 0, encKeyLen, authID, false
	}
	spi = binary.BigEndian.Uint32(saBody[8:12])
	off := 8 + spiLen
	for i := 0; i < numTr && off+8 <= len(saBody); i++ {
		tLen := int(binary.BigEndian.Uint16(saBody[off+2 : off+4]))
		if tLen < 8 || off+tLen > len(saBody) {
			break
		}
		tType := saBody[off+4]
		tID := binary.BigEndian.Uint16(saBody[off+6 : off+8])
		var keyBits uint16
		if tLen >= 12 && saBody[off+8] == 0x80 && saBody[off+9] == 0x0E {
			keyBits = binary.BigEndian.Uint16(saBody[off+10 : off+12])
		}
		switch tType {
		case 1:
			if keyBits == 256 {
				encKeyLen = 32
			} else if keyBits == 128 {
				encKeyLen = 16
			}
		case 3:
			authID = tID
		}
		off += tLen
	}
	return spi, encKeyLen, authID, true
}

// ParseNotify decodes an IKEv2 Notify payload body.
func ParseNotify(body []byte) (notifyType uint16, data []byte, ok bool) {
	if len(body) < 4 {
		return 0, nil, false
	}
	spiLen := int(body[1])
	notifyType = binary.BigEndian.Uint16(body[2:4])
	if 4+spiLen > len(body) {
		return notifyType, nil, true
	}
	return notifyType, body[4+spiLen:], true
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

func BuildTSAll(ipv6 bool) []byte {
	if !ipv6 {
		return BuildTSAllIPv4()
	}
	out := make([]byte, 44)
	out[0], out[4] = 1, 8
	binary.BigEndian.PutUint16(out[6:8], 40)
	binary.BigEndian.PutUint16(out[10:12], 65535)
	for i := 28; i < 44; i++ {
		out[i] = 255
	}
	return out
}

func BuildCFGRequest() []byte {
	return BuildCFGRequestForFamily(false)
}

func BuildCFGRequestForFamily(ipv6 bool) []byte {
	var buf bytes.Buffer
	buf.Write([]byte{CFGRequest, 0, 0, 0})
	attrs := []uint16{AttrInternalIP4Addr, AttrInternalIP4DNS, AttrPCSCFIP4Addr}
	if ipv6 {
		attrs = []uint16{AttrInternalIP6Addr, AttrInternalIP6DNS, AttrPCSCFIP6Addr}
	}
	for _, attr := range attrs {
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
			return nil, errors.New("IKE_CFG_ATTRIBUTE_TRUNCATED")
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
		if atype == AttrInternalIP6Addr {
			if alen != 17 || val[16] > 128 {
				return nil, errors.New("IKE_CFG_IPV6_ADDRESS_INVALID")
			}
			res.LocalIP = append(net.IP(nil), val[:16]...)
		}
		if atype == AttrInternalIP6DNS || atype == AttrPCSCFIP6Addr {
			if alen != 16 {
				return nil, errors.New("IKE_CFG_IPV6_SERVER_INVALID")
			}
			ip := append(net.IP(nil), val...)
			if atype == AttrInternalIP6DNS {
				res.DNS = append(res.DNS, ip)
			} else {
				res.PCSCF = append(res.PCSCF, ip)
			}
		}
	}
	if off != len(body) {
		return nil, errors.New("IKE_CFG_ATTRIBUTE_TRUNCATED")
	}
	if res.LocalIP != nil {
		matched := res.PCSCF[:0]
		for _, ip := range res.PCSCF {
			if (ip.To4() == nil) == (res.LocalIP.To4() == nil) {
				matched = append(matched, ip)
			}
		}
		res.PCSCF = matched
	}
	return res, nil
}

// ParseDeviceIdentityRequest inspects a 3GPP TS 24.302 §8.2.9.2 DEVICE_IDENTITY (41101)
// notify data payload sent by the ePDG and returns the requested identity type (IMEI=1 or IMEISV=2).
func ParseDeviceIdentityRequest(data []byte) byte {
	// Format: [lenHi, lenLo, identityType, ...]
	if len(data) >= 3 && (data[2] == DeviceIdentityTypeIMEI || data[2] == DeviceIdentityTypeIMEISV) {
		return data[2]
	}
	return DeviceIdentityTypeIMEI
}

// BuildDeviceIdentityNotify encodes an IMEI/IMEISV into a 3GPP TS 24.302 §8.2.9.2
// DEVICE_IDENTITY (41101) IKEv2 Notify payload body (ProtocolID=0, SPISize=0, Type=41101).
func BuildDeviceIdentityNotify(imei string, idType byte) ([]byte, bool) {
	digits := make([]byte, 0, 16)
	for i := 0; i < len(imei); i++ {
		if imei[i] >= '0' && imei[i] <= '9' {
			digits = append(digits, imei[i]-'0')
		}
	}
	if len(digits) < 14 {
		return nil, false
	}
	if idType != DeviceIdentityTypeIMEISV {
		idType = DeviceIdentityTypeIMEI
	}
	var tbcd [8]byte
	if idType == DeviceIdentityTypeIMEISV {
		// 16 digits: TAC(8) + SNR(6) + SV(2). Default SV="01" if only 14-15 IMEI digits provided.
		var d16 [16]byte
		if len(digits) >= 16 {
			copy(d16[:], digits[:16])
		} else {
			copy(d16[:14], digits[:14])
			d16[14] = 0
			d16[15] = 1
		}
		for i := 0; i < 8; i++ {
			tbcd[i] = (d16[2*i+1] << 4) | (d16[2*i] & 0x0F)
		}
	} else {
		// 15 digits IMEI: octet i packs (digit 2i+1 << 4) | digit 2i; final high nibble is 0xF filler.
		if len(digits) < 15 {
			return nil, false
		}
		for i := 0; i < 7; i++ {
			tbcd[i] = (digits[2*i+1] << 4) | (digits[2*i] & 0x0F)
		}
		tbcd[7] = 0xF0 | (digits[14] & 0x0F)
	}
	// Notify data: Length(2 bytes big-endian = 1 + 8 = 9) + IdentityType(1) + TBCD(8)
	data := make([]byte, 2+1+len(tbcd))
	binary.BigEndian.PutUint16(data[0:2], uint16(1+len(tbcd)))
	data[2] = idType
	copy(data[3:], tbcd[:])
	return buildNotify(NotifyDeviceIdentity, data), true
}

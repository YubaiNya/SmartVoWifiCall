package sip

import (
	"bytes"
	"errors"
	"fmt"
	"strings"
	"time"
	"unicode/utf16"
)

const (
	RPDataMS2N byte = 0x00
	RPDataN2MS byte = 0x01
	RPAckMS2N  byte = 0x02
	RPAckN2MS  byte = 0x03
)

// IncomingSMS is a decoded 3GPP TS 24.011 / TS 23.040 SMS-DELIVER payload from SIP MESSAGE.
type IncomingSMS struct {
	RPMessageRef byte
	Sender       string
	Text         string
	Timestamp    time.Time
}

// EncodeRPDataSubmit encodes an outbound SMS text into 3GPP TS 24.011 RP-DATA(SMS-SUBMIT) bytes
// for SIP MESSAGE (Content-Type: application/vnd.3gpp.sms). Uses UCS-2 (DCS=0x08) when non-ASCII.
func EncodeRPDataSubmit(rpRef byte, smsc, destination, text string) ([]byte, error) {
	if destination == "" {
		return nil, errors.New("SMS_DESTINATION_EMPTY")
	}
	tpdu, err := encodeSMSSubmitTPDU(rpRef, destination, text)
	if err != nil {
		return nil, err
	}
	var rp bytes.Buffer
	rp.WriteByte(RPDataMS2N)
	rp.WriteByte(rpRef)
	rp.WriteByte(0x00) // RP-Originator Address (empty for MS->Network)
	if smsc == "" {
		rp.WriteByte(0x00)
	} else {
		sc := encodeBCDAddress(smsc, true)
		rp.WriteByte(byte(len(sc)))
		rp.Write(sc)
	}
	if len(tpdu) > 232 {
		return nil, errors.New("SMS_TPDU_TOO_LONG")
	}
	rp.WriteByte(byte(len(tpdu)))
	rp.Write(tpdu)
	return rp.Bytes(), nil
}

// EncodeRPAck encodes a 3GPP TS 24.011 RP-ACK (MS->Network) acknowledging an inbound SMS-DELIVER.
func EncodeRPAck(rpRef byte) []byte {
	return []byte{RPAckMS2N, rpRef}
}

// DecodeRPDataDeliver decodes an inbound 3GPP TS 24.011 RP-DATA(SMS-DELIVER) payload.
func DecodeRPDataDeliver(raw []byte) (*IncomingSMS, error) {
	if len(raw) < 6 {
		return nil, errors.New("RP_DATA_SHORT")
	}
	if raw[0] != RPDataN2MS {
		return nil, fmt.Errorf("RP_UNEXPECTED_MTI_0x%02X", raw[0])
	}
	rpRef := raw[1]
	off := 2
	// RP-Originator Address (SMSC)
	oaLen := int(raw[off])
	off++
	if off+oaLen >= len(raw) {
		return nil, errors.New("RP_OA_OVERFLOW")
	}
	off += oaLen
	// RP-Destination Address (empty for N->MS)
	daLen := int(raw[off])
	off++
	if off+daLen >= len(raw) {
		return nil, errors.New("RP_DA_OVERFLOW")
	}
	off += daLen
	// RP-User-Data (SMS-DELIVER TPDU)
	udLen := int(raw[off])
	off++
	if off+udLen > len(raw) {
		return nil, errors.New("RP_UD_OVERFLOW")
	}
	return decodeSMSDeliverTPDU(rpRef, raw[off:off+udLen])
}

func encodeSMSSubmitTPDU(mr byte, dest, text string) ([]byte, error) {
	var tp bytes.Buffer
	tp.WriteByte(0x01) // MTI=SMS-SUBMIT, no VPF, no UDHI
	tp.WriteByte(mr)

	isIntl := strings.HasPrefix(dest, "+")
	digits := strings.TrimPrefix(dest, "+")
	tp.WriteByte(byte(len(digits)))
	if isIntl {
		tp.WriteByte(0x91)
	} else {
		tp.WriteByte(0x81)
	}
	tp.Write(packSemiOctets(digits))
	tp.WriteByte(0x00) // TP-PID

	// Choose GSM 7-bit for pure printable ASCII, UCS-2 (0x08) otherwise.
	if isPureASCII(text) && len(text) <= 160 {
		tp.WriteByte(0x00) // GSM 7-bit DCS
		tp.WriteByte(byte(len(text)))
		tp.Write(packGSM7([]byte(text)))
	} else {
		u16 := utf16.Encode([]rune(text))
		if len(u16)*2 > 140 {
			u16 = u16[:70]
		}
		tp.WriteByte(0x08) // UCS-2 DCS
		tp.WriteByte(byte(len(u16) * 2))
		for _, r := range u16 {
			tp.WriteByte(byte(r >> 8))
			tp.WriteByte(byte(r))
		}
	}
	return tp.Bytes(), nil
}

func decodeSMSDeliverTPDU(rpRef byte, tp []byte) (*IncomingSMS, error) {
	if len(tp) < 12 {
		return nil, errors.New("SMS_DELIVER_SHORT")
	}
	first := tp[0]
	hasUDH := (first & 0x40) != 0
	oaDigits := int(tp[1])
	oaType := tp[2]
	oaBytes := (oaDigits + 1) / 2
	off := 3
	if off+oaBytes+9 > len(tp) {
		return nil, errors.New("SMS_DELIVER_OA_OVERFLOW")
	}
	sender := decodeAddress(oaType, oaDigits, tp[off:off+oaBytes])
	off += oaBytes
	off++ // TP-PID
	dcs := tp[off]
	off++
	scts := tp[off : off+7]
	off += 7
	ts := decodeSCTS(scts)
	udl := int(tp[off])
	off++
	ud := tp[off:]

	var text string
	coding := (dcs >> 2) & 0x03
	if (dcs & 0xF0) == 0x00 {
		coding = (dcs >> 2) & 0x03
	}
	switch coding {
	case 2: // UCS-2
		data := ud
		if udl < len(data) {
			data = data[:udl]
		}
		if hasUDH && len(data) > 0 {
			hlen := int(data[0]) + 1
			if hlen <= len(data) {
				data = data[hlen:]
			}
		}
		u16 := make([]uint16, len(data)/2)
		for i := 0; i < len(u16); i++ {
			u16[i] = uint16(data[i*2])<<8 | uint16(data[i*2+1])
		}
		text = string(utf16.Decode(u16))
	default: // GSM 7-bit
		skipSeptets := 0
		if hasUDH && len(ud) > 0 {
			hlen := int(ud[0]) + 1
			skipSeptets = (hlen*8 + 6) / 7
		}
		text = unpackGSM7(ud, udl, skipSeptets)
	}
	return &IncomingSMS{
		RPMessageRef: rpRef,
		Sender:       sender,
		Text:         text,
		Timestamp:    ts,
	}, nil
}

func encodeBCDAddress(num string, includeType bool) []byte {
	intl := strings.HasPrefix(num, "+")
	digits := strings.TrimPrefix(num, "+")
	var out []byte
	if includeType {
		if intl {
			out = append(out, 0x91)
		} else {
			out = append(out, 0x81)
		}
	}
	return append(out, packSemiOctets(digits)...)
}

func packSemiOctets(digits string) []byte {
	n := (len(digits) + 1) / 2
	out := make([]byte, n)
	for i := 0; i < n; i++ {
		lo := byte(0x0F)
		if i*2 < len(digits) {
			lo = digits[i*2] - '0'
		}
		hi := byte(0x0F)
		if i*2+1 < len(digits) {
			hi = digits[i*2+1] - '0'
		}
		out[i] = (hi << 4) | (lo & 0x0F)
	}
	return out
}

func decodeAddress(addrType byte, numDigits int, data []byte) string {
	// Alphanumeric sender ID (TON = 5)
	if ((addrType >> 4) & 0x07) == 5 {
		septets := (numDigits * 4) / 7
		return unpackGSM7(data, septets, 0)
	}
	var sb strings.Builder
	if ((addrType >> 4) & 0x07) == 1 {
		sb.WriteByte('+')
	}
	for _, b := range data {
		lo := b & 0x0F
		hi := (b >> 4) & 0x0F
		if lo <= 9 {
			sb.WriteByte('0' + lo)
		}
		if hi <= 9 {
			sb.WriteByte('0' + hi)
		}
	}
	return sb.String()
}

func decodeSCTS(b []byte) time.Time {
	if len(b) < 7 {
		return time.Now().UTC()
	}
	bcd := func(x byte) int { return int(x&0x0F)*10 + int((x>>4)&0x0F) }
	year := 2000 + bcd(b[0])
	month := time.Month(bcd(b[1]))
	day := bcd(b[2])
	hour := bcd(b[3])
	min := bcd(b[4])
	sec := bcd(b[5])
	if month < 1 || month > 12 || day < 1 || day > 31 {
		return time.Now().UTC()
	}
	return time.Date(year, month, day, hour, min, sec, 0, time.UTC)
}

func isPureASCII(s string) bool {
	for i := 0; i < len(s); i++ {
		if s[i] < 0x20 || s[i] > 0x7E {
			return false
		}
	}
	return true
}

func packGSM7(septets []byte) []byte {
	outLen := (len(septets)*7 + 7) / 8
	out := make([]byte, outLen)
	for i, c := range septets {
		bitPos := i * 7
		byteIdx := bitPos / 8
		bitOff := uint(bitPos % 8)
		out[byteIdx] |= (c & 0x7F) << bitOff
		if bitOff > 1 && byteIdx+1 < outLen {
			out[byteIdx+1] |= (c & 0x7F) >> (8 - bitOff)
		}
	}
	return out
}

func unpackGSM7(packed []byte, totalSeptets, skipSeptets int) string {
	var sb strings.Builder
	for i := skipSeptets; i < totalSeptets; i++ {
		bitPos := i * 7
		byteIdx := bitPos / 8
		bitOff := uint(bitPos % 8)
		if byteIdx >= len(packed) {
			break
		}
		val := (packed[byteIdx] >> bitOff) & 0x7F
		if bitOff > 1 && byteIdx+1 < len(packed) {
			val |= (packed[byteIdx+1] << (8 - bitOff)) & 0x7F
		}
		sb.WriteByte(val)
	}
	return sb.String()
}

package ipsec

import (
	"bytes"
	"crypto/hmac"
	"crypto/sha1"
	"encoding/binary"
	"testing"
)

func TestIMSTransportESPIntegrityAndReplay(t *testing.T) {
	key := bytes.Repeat([]byte{0x42}, 16)
	sender, _ := NewSA(123, SuiteNullHMACSHA1, nil, key, nil)
	receiver, _ := NewSA(123, SuiteNullHMACSHA1, nil, key, nil)
	data := []byte("protected SIP datagram")
	wire, err := sender.Encrypt(17, data)
	if err != nil {
		t.Fatal(err)
	}
	if binary.BigEndian.Uint32(wire[:4]) != 123 || binary.BigEndian.Uint32(wire[4:8]) != 1 || !bytes.Equal(wire[8:8+len(data)], data) {
		t.Fatal("transport wire format")
	}
	mac := hmac.New(sha1.New, key)
	mac.Write(wire[:len(wire)-12])
	if !hmac.Equal(mac.Sum(nil)[:12], wire[len(wire)-12:]) {
		t.Fatal("wrong authentication tag")
	}
	forged := append([]byte(nil), wire...)
	forged[9] ^= 1
	if _, _, err = receiver.Decrypt(forged); err == nil {
		t.Fatal("accepted forged ESP")
	}
	proto, got, err := receiver.Decrypt(wire)
	if err != nil || proto != 17 || !bytes.Equal(got, data) {
		t.Fatalf("valid packet rejected: %v", err)
	}
	if _, _, err = receiver.Decrypt(wire); err == nil {
		t.Fatal("replayed ESP accepted")
	}
}

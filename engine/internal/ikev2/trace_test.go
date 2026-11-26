package ikev2

import (
	"bytes"
	"encoding/binary"
	"encoding/json"
	"strings"
	"testing"
)

func TestAuthTraceRedactsOnlyPrivatePayloadParts(t *testing.T) {
	nai := "0234100000000001@nai.epc.mnc010.mcc234.3gppnetwork.org"
	imei := "123456789123456"
	idNotify, _ := BuildDeviceIdentityNotify(imei, DeviceIdentityTypeIMEI)
	v := diagVectors(t)
	// Include actual public AKA challenge/response vectors, not only hand-made maps.
	ps := []Payload{
		{Type: PayloadIDi, Body: BuildIDPayload(3, nai)},
		{Type: PayloadNotify, Body: idNotify},
		{Type: PayloadAUTH, Body: append([]byte{2, 0, 0, 0}, bytes.Repeat([]byte{0x97}, 32)...)},
		{Type: PayloadEAP, Body: diagHex(v["EAP_AKA_CHALLENGE_REQUEST_WITHOUT_IDENTITY_REQ"])},
		{Type: PayloadEAP, Body: diagHex(v["EAP_AKA_CHALLENGE_RESPONSE_WITHOUT_IDENTITY_REQUEST"])},
		{Type: PayloadNonce, Body: bytes.Repeat([]byte{0x71}, 32)},
		{Type: PayloadSA, Body: BuildIKESAProposal(14)},
	}
	b, _ := json.Marshal(tracePayloads(ps))
	s := string(b)
	for _, secret := range []string{"234100000000001", imei, traceHex(diagHex(v["RES"])), traceHex(bytes.Repeat([]byte{0x97}, 32))} {
		if strings.Contains(s, secret) {
			t.Fatalf("private payload material leaked")
		}
	}
	for _, public := range []string{"0[Hidden]@nai.epc.mnc010.mcc234.3gppnetwork.org", traceHex(bytes.Repeat([]byte{0x71}, 32)), traceHex(BuildIKESAProposal(14)), "DEVICE_IDENTITY", "proofLength", "attributes"} {
		if !strings.Contains(s, public) {
			t.Fatalf("nonprivate protocol detail lost: %s", public)
		}
	}
}

func TestAuthTraceMalformedPayloadsNeverDumpOpaqueBytes(t *testing.T) {
	for n := 0; n < 80; n++ {
		b := bytes.Repeat([]byte{0xff}, n)
		if n >= 4 {
			binary.BigEndian.PutUint16(b[2:], uint16(n))
		}
		for _, typ := range []byte{PayloadIDi, PayloadEAP, PayloadCP, PayloadTSi, PayloadNotify, 250} {
			_ = tracePayloads([]Payload{{Type: typ, Body: b}})
		}
	}
}

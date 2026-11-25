package sip

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/YubaiNya/SmartVoWifiCall/engine/internal/diag"
)

func TestDigestAKANoncePreservesPublicParts(t *testing.T) {
	data := append(bytes.Repeat([]byte{0x11}, 16), bytes.Repeat([]byte{0x22}, 16)...)
	token := base64.StdEncoding.EncodeToString(data)
	row := traceHeader("WWW-Authenticate", `Digest realm="ims.example",algorithm=AKAv1-MD5,nonce="`+token+`"`)
	b, _ := json.Marshal(row)
	s := string(b)
	if strings.Contains(s, token) || !strings.Contains(s, `"amfHex":"22 22"`) || !strings.Contains(s, `"randHex":"11 11`) {
		t.Fatal("mixed AKA nonce must retain public RAND/AMF without exposing the original token")
	}
}

func TestIPv6HeaderPrivacyPreservesParameters(t *testing.T) {
	for _, sample := range []struct{ name, value string }{
		{"Via", "SIP/2.0/UDP [2001:db8:1::2]:5060;received=2001:db8:2::3;rport=5060;branch=test"},
		{"Call-ID", "test@2001:db8:1:2:3:4:5:6"},
		{"Contact", "<sip:subscriber@[2001:db8::2]:5060;transport=udp>"},
	} {
		b, _ := json.Marshal(traceHeader(sample.name, sample.value))
		if strings.Contains(string(b), "2001:db8") {
			t.Fatalf("IPv6 address leaked in %s", sample.name)
		}
		if sample.name == "Via" && (!strings.Contains(string(b), "rport=5060") || !strings.Contains(string(b), "branch=test")) {
			t.Fatal("public parameters lost")
		}
	}
}

func TestRegistrationJournalPreservesDigestContext(t *testing.T) {
	dir := t.TempDir()
	logger, err := diag.NewLogger(dir)
	if err != nil {
		t.Fatal(err)
	}
	defer logger.Close()
	c := &Client{cfg: ClientConfig{Logger: logger}, epoch: "test-session"}
	// ClientConfig is the same runtime configuration used by the actual SIP client.
	m := &Message{Method: "REGISTER", RequestURI: "sip:ims.mnc010.mcc234.3gppnetwork.org"}
	m.AddHeader("CSeq", "2 REGISTER")
	m.AddHeader("From", `"Private Name" <sip:234100000000001@ims.mnc010.mcc234.3gppnetwork.org>;tag=abc`)
	m.AddHeader("Contact", `<sip:234100000000001@10.1.2.3:5060>;expires=600;+sip.instance="urn:gsma:imei:12345678-912345-6"`)
	m.AddHeader("Authorization", `Digest username="234100000000001@ims.mnc010.mcc234.3gppnetwork.org",realm="ims.mnc010.mcc234.3gppnetwork.org",nonce="private-nonce",response="private-proof",algorithm=AKAv1-MD5,qop=auth,nc=00000001`)
	c.traceRegistration("out", m)
	b, err := os.ReadFile(filepath.Join(dir, "auth.jsonl"))
	if err != nil {
		t.Fatal(err)
	}
	var row map[string]any
	if err = json.Unmarshal(b, &row); err != nil {
		t.Fatal(err)
	}
	s := string(b)
	for _, private := range []string{"234100000000001", "Private Name", "10.1.2.3", "12345678-912345-6", "private-nonce", "private-proof"} {
		if strings.Contains(s, private) {
			t.Fatalf("private SIP value leaked: %s", private)
		}
	}
	for _, public := range []string{"ims.mnc010.mcc234.3gppnetwork.org", "AKAv1-MD5", "expires=600", "tag=abc", "REGISTER", "sourceDigest"} {
		if !strings.Contains(s, public) {
			t.Fatalf("public SIP detail lost: %s", public)
		}
	}
}

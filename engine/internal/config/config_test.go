package config

import (
	"bytes"
	"context"
	"net"
	"testing"
	"time"
)

func validSample() *Config {
	return &Config{
		Enabled:   true,
		Transport: "iface:wfctun0",
		Slot:      1,
		ICCID:     "8985203000000000001",
		MCC:       "454",
		MNC:       "03",
		MNCLen:    2,
		IMSRealm:  "ims.mnc003.mcc454.3gppnetwork.org",
		DNS:       "1.1.1.1",
		IMSI:      "454030000000001",
		IMEI:      "351234567890123",
	}
}

func TestValidateStrictAndDerivations(t *testing.T) {
	c := validSample()
	if err := c.Validate(true); err != nil {
		t.Fatalf("expected valid config, got: %v", err)
	}
	if got := c.EffectiveEPDG(); got != "wlan.three.com.hk" {
		t.Fatalf("unexpected 3HK ePDG FQDN: %s", got)
	}
	if got := DeriveEPDGFQDN("234", "20"); got != "epdg.epc.mnc020.mcc234.pub.3gppnetwork.org" {
		t.Fatalf("unexpected derived 3GPP ePDG FQDN: %s", got)
	}
	if got := c.EffectiveEAPIdentity(); got != "0454030000000001@wlan.mnc003.mcc454.3gppnetwork.org" {
		t.Fatalf("unexpected EAP identity: %s", got)
	}
	sanitized := c.SanitizedMap()
	if sanitized["iccid"] != "[Hidden]" || sanitized["imsi"] != "[Hidden]" || sanitized["imei"] != "[Hidden]" {
		t.Fatalf("expected sensitive fields masked: %+v", sanitized)
	}

	// Mainland SIM (MCC 460/461) is unlocked and passes strict validation
	mainland := validSample()
	mainland.MCC = "460"
	mainland.MNC = "00"
	mainland.IMSRealm = DeriveIMSRealm("460", "00")
	mainland.IMSI = "460000000000001"
	if err := mainland.Validate(true); err != nil {
		t.Fatalf("expected mainland SIM accepted in strict mode, got: %v", err)
	}

	// O2 UK / giffgaff (23410) prefers 3GPP TS 23.003 @nai.epc first with @wlan fallback
	giff := validSample()
	giff.MCC = "234"
	giff.MNC = "10"
	giff.IMSRealm = DeriveIMSRealm("234", "10")
	giff.IMSI = "234100000000001"
	if err := giff.Validate(true); err != nil {
		t.Fatalf("expected giffgaff config valid, got: %v", err)
	}
	cands := giff.CandidateEAPIdentities()
	if len(cands) < 3 {
		t.Fatalf("expected >=3 NAI candidates, got %d", len(cands))
	}
	if cands[0].Identity != "0234100000000001@nai.epc.mnc010.mcc234.3gppnetwork.org" {
		t.Fatalf("unexpected primary giffgaff NAI: %s", cands[0].Identity)
	}
	if cands[1].Identity != "6234100000000001@nai.epc.mnc010.mcc234.3gppnetwork.org" {
		t.Fatalf("unexpected secondary giffgaff EAP-AKA' NAI: %s", cands[1].Identity)
	}
	if cands[2].Identity != "0234100000000001@wlan.mnc010.mcc234.3gppnetwork.org" {
		t.Fatalf("unexpected fallback giffgaff wlan NAI: %s", cands[2].Identity)
	}

	// Reject mismatched IMSI prefix
	badImsi := validSample()
	badImsi.IMSI = "310260000000001"
	if err := badImsi.Validate(true); err == nil {
		t.Fatal("expected IMSI/PLMN mismatch rejected")
	}
}

func TestResolveHostIPv4OverUDP(t *testing.T) {
	pc, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer pc.Close()

	go func() {
		buf := make([]byte, 512)
		n, addr, err := pc.ReadFrom(buf)
		if err != nil || n < 12 {
			return
		}
		var ans bytes.Buffer
		ans.Write(buf[0:2])                                         // TXID
		ans.Write([]byte{0x81, 0x80, 0x00, 0x01, 0x00, 0x01, 0, 0, 0, 0}) // 1 answer
		ans.Write(buf[12:n])                                        // Echo question
		ans.Write([]byte{0xC0, 0x0C, 0x00, 0x01, 0x00, 0x01, 0, 0, 0, 60, 0, 4, 203, 0, 113, 9})
		_, _ = pc.WriteTo(ans.Bytes(), addr)
	}()

	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	ip, err := ResolveHostIPv4(ctx, "epdg.epc.mnc003.mcc454.pub.3gppnetwork.org", pc.LocalAddr().String())
	if err != nil {
		t.Fatalf("ResolveHostIPv4 failed: %v", err)
	}
	if ip.String() != "203.0.113.9" {
		t.Fatalf("expected 203.0.113.9, got %s", ip.String())
	}
}

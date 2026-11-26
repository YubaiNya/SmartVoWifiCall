package ikev2

import (
	"bytes"
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"github.com/YubaiNya/SmartVoWifiCall/engine/internal/diag"
	"net"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/YubaiNya/SmartVoWifiCall/engine/internal/simauth"
)

// Fixtures are public AOSP vectors, not SIM credentials from any real device.
func diagVectors(t *testing.T) map[string]string {
	t.Helper()
	b, err := os.ReadFile("testdata/auth-aosp-vectors.json")
	if err != nil {
		t.Fatal(err)
	}
	var v map[string]string
	if err = json.Unmarshal(b, &v); err != nil {
		t.Fatal(err)
	}
	return v
}
func diagHex(s string) []byte {
	b, e := hex.DecodeString(s)
	if e != nil {
		panic(e)
	}
	return b
}
func diagPRF(k, b []byte) []byte { m := hmac.New(sha256.New, k); m.Write(b); return m.Sum(nil) }
func diagJoin(parts ...[]byte) []byte {
	var out []byte
	for _, p := range parts {
		out = append(out, p...)
	}
	return out
}

// Deliberately independent of the engine's ComputeAuthPayload and IKE key derivation.
func diagAUTH(msk, init, nonce, id, skp []byte) []byte {
	return diagPRF(diagPRF(msk, []byte("Key Pad for IKEv2")), diagJoin(init, nonce, diagPRF(skp, id)))
}
func diagKeys(ni, nr, shared []byte, spii, spir uint64, encLen int) *IKEKeys {
	spis := make([]byte, 16)
	binary.BigEndian.PutUint64(spis, spii)
	binary.BigEndian.PutUint64(spis[8:], spir)
	seed := diagJoin(ni, nr, spis)
	key := diagPRF(diagJoin(ni, nr), shared)
	var km, prev []byte
	for n := byte(1); len(km) < 32*5+2*encLen; n++ {
		prev = diagPRF(key, diagJoin(prev, seed, []byte{n}))
		km = append(km, prev...)
	}
	off := 0
	take := func(n int) []byte { b := km[off : off+n]; off += n; return b }
	return &IKEKeys{SKd: take(32), SKai: take(32), SKar: take(32), SKei: take(encLen), SKer: take(encLen), SKpi: take(32), SKpr: take(32), Suite: IKESuite{EncKeyLen: encLen, PRFID: 5, AuthID: 12}}
}

type diagSIM struct{ v map[string]string }

func (s diagSIM) Name() string { return "public-aosp-test-vector" }
func (s diagSIM) Authenticate(context.Context, simauth.AuthRequest) (*simauth.AuthResponse, error) {
	return &simauth.AuthResponse{Status: simauth.StatusOK, RES: diagHex(s.v["RES"]), CK: diagHex(s.v["CK"]), IK: diagHex(s.v["IK"])}, nil
}

func TestDiagPublicVectors(t *testing.T) {
	v := diagVectors(t)
	k := DeriveEAPAKAKeys(v["IDENTITY"], diagHex(v["IK"]), diagHex(v["CK"]))
	if !bytes.Equal(k.MSK, diagHex(v["MSK_WITHOUT_IDENTITY_REQ"])) {
		t.Fatal("AOSP EAP-AKA MSK differs")
	}
	if !bytes.Equal(k.EMSK, diagHex(v["EMSK_WITHOUT_IDENTITY_REQ"])) {
		t.Fatal("AOSP EAP-AKA EMSK differs")
	}
	got := ComputeAuthPayloadWithPRF(2, diagHex(v["PSK_HEX_STRING"]), diagHex(v["PSK_IKE_INIT_REQUEST_HEX_STRING"]), diagHex(v["PSK_NONCE_RESP_HEX_STRING"]), diagHex(v["PSK_ID_INITIATOR_PAYLOAD_HEX_STRING"]), diagHex(v["PSK_SKP_HEX_STRING"]))
	if !bytes.Equal(got, diagHex(v["PSK_AUTH_PAYLOAD_SIGNATURE_HEX_STRING"])) {
		t.Fatal("AOSP AUTH SHA1 vector differs")
	}
	t.Log("actual Go code matches independent AOSP MSK, EMSK and AUTH fixtures")
}

type diagObservation struct {
	InitRequests                                                                        int
	AuthValid, ResponseValid, EapSuccessSent, EapOnly, EarlyDeviceID, ChallengeDeviceID bool
	FinalSeen                                                                           bool
	Err                                                                                 error
}

// Loopback only. The verifier captures the wire's initial IKE request and IDi;
// its MSK and expected challenge response come from AOSP, not engine code.
func diagRunPeer(t *testing.T, mode string, encLen int) (diagObservation, error) {
	t.Helper()
	v := diagVectors(t)
	pc, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	defer pc.Close()
	pc.SetDeadline(time.Now().Add(5 * time.Second))
	result := make(chan diagObservation, 1)
	go func() {
		var o diagObservation
		defer func() { result <- o }()
		dh, e := GenerateDH(14)
		if e != nil {
			o.Err = e
			return
		}
		nr := bytes.Repeat([]byte{0x45}, 32)
		if mode == "f3_nonce" {
			nr = bytes.Repeat([]byte{0x45}, 172)
		}
		spir := uint64(0x1122334455667788)
		var spii uint64
		var initReq, initResp, idi, ni []byte
		var keys *IKEKeys
		var childSPI uint32
		identityReplies := 0
		challengeIssued := false
		buf := make([]byte, 8192)
		for {
			n, addr, e := pc.ReadFromUDP(buf)
			if e != nil {
				o.Err = e
				return
			}
			raw := append([]byte(nil), buf[:n]...)
			if len(raw) >= 4 && binary.BigEndian.Uint32(raw[:4]) == 0 {
				raw = raw[4:]
			}
			h, p, e := DecodePacket(raw)
			if e != nil {
				o.Err = e
				return
			}
			send := func(payloads []Payload) error {
				b, e := EncodeEncryptedPacket(Header{SPIi: spii, SPIr: spir, Exchange: ExchangeIKEAuth, Flags: FlagResponse, MessageID: h.MessageID}, payloads, keys, false)
				if e != nil {
					return e
				}
				_, e = pc.WriteToUDP(diagJoin(make([]byte, 4), b), addr)
				return e
			}
			challenge := func() []Payload {
				challengeIssued = true
				p := []Payload{{Type: PayloadEAP, Body: diagHex(v["EAP_AKA_CHALLENGE_REQUEST_WITHOUT_IDENTITY_REQ"])}}
				if mode == "identity_challenge" {
					p = append(p, Payload{Type: PayloadNotify, Body: buildNotify(41101, nil)})
				}
				return p
			}
			if h.Exchange == ExchangeIKESAInit {
				o.InitRequests++
				initReq = append([]byte(nil), raw...)
				spii = h.SPIi
				ni = FindPayload(p, PayloadNonce).Body
				group := binary.BigEndian.Uint16(FindPayload(p, PayloadKE).Body)
				if strings.HasPrefix(mode, "cookie_") {
					// Stateless cookie binds both Ni and KE; regeneration cannot pass.
					secret := []byte("synthetic-cookie-server-key")
					if mode == "cookie_rotate" && o.InitRequests > 1 {
						secret = []byte("synthetic-rotated-cookie-key")
					}
					cookie := diagPRF(secret, diagJoin(ni, FindPayload(p, PayloadKE).Body))[:16]
					var echoed []byte
					for _, pl := range p {
						if pl.Type == PayloadNotify {
							nt, value, ok := ParseNotify(pl.Body)
							if ok && nt == 16390 {
								echoed = value
							}
						}
					}
					if !hmac.Equal(cookie, echoed) {
						if o.InitRequests > 3 {
							o.Err = fmt.Errorf("COOKIE did not converge")
							return
						}
						response := EncodePacket(Header{SPIi: spii, Exchange: ExchangeIKESAInit, Flags: FlagResponse}, []Payload{{Type: PayloadNotify, Body: buildNotify(16390, cookie)}})
						_, o.Err = pc.WriteToUDP(response, addr)
						if o.Err != nil {
							return
						}
						continue
					}
					if mode == "cookie_dh" && group != DHGroupECP256 {
						response := EncodePacket(Header{SPIi: spii, Exchange: ExchangeIKESAInit, Flags: FlagResponse}, []Payload{keNotify(DHGroupECP256)})
						_, o.Err = pc.WriteToUDP(response, addr)
						if o.Err != nil {
							return
						}
						continue
					}
				}
				if dh.Group != group {
					dh, e = GenerateDH(group)
					if e != nil {
						o.Err = e
						return
					}
				}
				shared, e := dh.SharedSecret(FindPayload(p, PayloadKE).Body[4:])
				if e != nil {
					o.Err = e
					return
				}
				keys = diagKeys(ni, nr, shared, spii, spir, encLen)
				sa := buildProposal(1, 1, nil, [][]byte{buildTransform(1, 12, uint16(encLen*8)), buildTransform(2, 5, 0), buildTransform(3, 12, 0), buildTransform(4, group, 0)})
				initResp = EncodePacket(Header{SPIi: spii, SPIr: spir, Exchange: ExchangeIKESAInit, Flags: FlagResponse}, []Payload{{Type: PayloadSA, Body: sa}, {Type: PayloadKE, Body: BuildKEPayload(group, dh.Public)}, {Type: PayloadNonce, Body: nr}})
				if _, e = pc.WriteToUDP(initResp, addr); e != nil {
					o.Err = e
					return
				}
				continue
			}
			_, p, e = DecodeEncryptedPacket(raw, keys, true)
			if e != nil {
				o.Err = e
				return
			}
			for _, x := range p {
				if x.Type == PayloadNotify {
					nt, _, ok := ParseNotify(x.Body)
					if ok && nt == 16417 {
						o.EapOnly = true
					}
					if ok && nt == 41101 {
						if challengeIssued {
							o.ChallengeDeviceID = true
						} else {
							o.EarlyDeviceID = true
						}
					}
				}
			}
			if id := FindPayload(p, PayloadIDi); id != nil {
				idi = append([]byte(nil), id.Body...)
				childSPI, _ = ParseChildSASPI(FindPayload(p, PayloadSA).Body)
				if mode == "identity_early" {
					e = send([]Payload{{Type: PayloadEAP, Body: []byte{1, 0x9a, 0, 5, 1}}, {Type: PayloadNotify, Body: buildNotify(41101, nil)}})
				} else {
					e = send(challenge())
				}
				if e != nil {
					o.Err = e
					return
				}
				continue
			}
			if a := FindPayload(p, PayloadAUTH); a != nil {
				o.FinalSeen = true
				// Negative control: corrupt only the decoded proof before verification.
				if mode == "corrupted_auth" && len(a.Body) > 4 {
					a.Body[4] ^= 1
				}
				expected := diagAUTH(diagHex(v["MSK_WITHOUT_IDENTITY_REQ"]), initReq, nr, idi, keys.SKpi)
				o.AuthValid = len(a.Body) == 36 && a.Body[0] == 2 && hmac.Equal(expected, a.Body[4:])
				if !o.AuthValid || !o.EapSuccessSent {
					o.Err = send([]Payload{{Type: PayloadNotify, Body: buildNotify(24, nil)}})
					return
				}
				// Valid, independently calculated responder AUTH plus CHILD_SA/config.
				cp := []byte{CFGReply, 0, 0, 0, 0, byte(AttrInternalIP4Addr), 0, 4, 10, 88, 0, 2, 0, byte(AttrPCSCFIP4Addr), 0, 4, 10, 88, 0, 1}
				peerID := BuildIDPayload(2, "ims")
				peerAUTH := diagJoin([]byte{2, 0, 0, 0}, diagAUTH(diagHex(v["MSK_WITHOUT_IDENTITY_REQ"]), initResp, ni, peerID, keys.SKpr))
				_ = childSPI
				if mode == "bad_peer_auth" {
					peerAUTH[len(peerAUTH)-1] ^= 1
				}
				if strings.HasPrefix(mode, "backoff_") {
					failure := []Payload{{Type: PayloadIDr, Body: peerID},
						{Type: PayloadNotify, Body: buildNotify(10500, nil)},
						{Type: PayloadNotify, Body: buildNotify(notifyBackoffTimer, []byte{1, 0x21})}}
					if mode == "backoff_bad_auth" {
						peerAUTH[len(peerAUTH)-1] ^= 1
					}
					if mode != "backoff_no_auth" {
						failure = append(failure, Payload{Type: PayloadAUTH, Body: peerAUTH})
					}
					o.Err = send(failure)
					return
				}
				o.Err = send([]Payload{{Type: PayloadIDr, Body: peerID}, {Type: PayloadAUTH, Body: peerAUTH}, {Type: PayloadCP, Body: cp}, {Type: PayloadSA, Body: BuildChildESPProposal(0xaabbccdd, false)}, {Type: PayloadTSi, Body: BuildTSAllIPv4()}, {Type: PayloadTSr, Body: BuildTSAllIPv4()}})
				return
			}
			ep := FindPayload(p, PayloadEAP)
			if ep == nil || len(ep.Body) < 5 {
				o.Err = fmt.Errorf("missing EAP response")
				return
			}
			if ep.Body[4] == 1 {
				if mode == "identity_early" && !challengeIssued {
					e = send(challenge())
				} else {
					identityReplies++
					e = send([]Payload{{Type: PayloadEAP, Body: []byte{1, byte(0x9c + identityReplies), 0, 5, 1}}})
					if mode == "round_limit" && identityReplies >= 15 {
						o.Err = e
						return
					}
				}
			} else {
				o.ResponseValid = bytes.Equal(ep.Body, diagHex(v["EAP_AKA_CHALLENGE_RESPONSE_WITHOUT_IDENTITY_REQUEST"]))
				if !o.ResponseValid {
					o.Err = fmt.Errorf("challenge response differs from external AOSP vector")
					return
				}
				if mode == "round_limit" {
					e = send([]Payload{{Type: PayloadEAP, Body: []byte{1, 0x9c, 0, 5, 1}}})
				} else {
					o.EapSuccessSent = true
					e = send([]Payload{{Type: PayloadEAP, Body: []byte{3, ep.Body[1], 0, 4}}})
				}
			}
			if e != nil {
				o.Err = e
				return
			}
		}
	}()
	logDir := t.TempDir()
	logger, logErr := diag.NewLogger(logDir)
	if logErr != nil {
		t.Fatal(logErr)
	}
	defer logger.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 4*time.Second)
	defer cancel()
	client, connectErr := Connect(ctx, SessionConfig{EPDGAddr: pc.LocalAddr().String(), NATTAddr: pc.LocalAddr().String(), EAPIdentity: v["IDENTITY"], APN: "ims", IMEI: "123456789123456", SimAuth: diagSIM{v: v}, Logger: logger})
	if client != nil {
		client.Close()
	}
	journal, readErr := os.ReadFile(filepath.Join(logDir, "auth.jsonl"))
	if readErr != nil {
		t.Fatal(readErr)
	}
	if !strings.Contains(string(journal), `"direction":"in"`) || !strings.Contains(string(journal), `"direction":"out"`) {
		t.Fatal("actual handshake journal missing direction")
	}
	for _, secret := range []string{v["MSK_WITHOUT_IDENTITY_REQ"], traceHex(diagHex(v["RES"])), "123456789123456"} {
		if strings.Contains(string(journal), secret) {
			t.Fatal("handshake journal leaked authentication material")
		}
	}

	select {
	case o := <-result:
		if o.Err != nil {
			t.Fatalf("peer harness error: %v", o.Err)
		}
		return o, connectErr
	case <-time.After(time.Second):
		t.Fatal("peer did not finish")
		return diagObservation{}, connectErr
	}
}

func TestDiagStrictAUTH(t *testing.T) {
	for _, n := range []int{16, 32} {
		t.Run(fmt.Sprintf("AES%d_SHA256", n*8), func(t *testing.T) {
			o, e := diagRunPeer(t, "normal", n)
			if e != nil || !o.AuthValid || !o.ResponseValid || !o.EapSuccessSent {
				t.Fatalf("connect=%v observation=%+v", e, o)
			}
			t.Logf("independent verifier accepted real Go handshake: EAP response=valid AUTH=valid; EAP-only notify=%v", o.EapOnly)
		})
	}
}

func TestCookieRetryCompletesIndependentlyVerifiedAUTH(t *testing.T) {
	for mode, wantRequests := range map[string]int{"cookie_once": 2, "cookie_rotate": 3, "cookie_dh": 4, "f3_nonce": 1} {
		t.Run(mode, func(t *testing.T) {
			o, err := diagRunPeer(t, mode, 32)
			if err != nil || !o.AuthValid || !o.ResponseValid || !o.EapSuccessSent || o.InitRequests != wantRequests {
				t.Fatalf("mode=%s error=%v initRequests=%d auth=%v eap=%v", mode, err, o.InitRequests, o.AuthValid, o.EapSuccessSent)
			}
		})
	}
}

func TestPeerBackoffRequiresFinalResponderProof(t *testing.T) {
	for _, mode := range []string{"backoff_verified", "backoff_no_auth", "backoff_bad_auth"} {
		t.Run(mode, func(t *testing.T) {
			_, err := diagRunPeer(t, mode, 16)
			if err == nil {
				t.Fatal("rejected child SA became a connection")
			}
			var failure *AuthRejectedError
			trusted := errors.As(err, &failure) && failure.BackoffTrusted
			if trusted != (mode == "backoff_verified") {
				t.Fatalf("timer trust mismatch: %v", err)
			}
			if trusted && failure.RetryAfter != time.Hour {
				t.Fatalf("timer=%v", failure.RetryAfter)
			}
		})
	}
}
func TestDiagDeviceIdentityAtChallenge(t *testing.T) {
	o, e := diagRunPeer(t, "identity_challenge", 32)
	if e != nil || !o.AuthValid || !o.ChallengeDeviceID || o.EarlyDeviceID {
		t.Fatalf("connect=%v observation=%+v", e, o)
	}
}
func TestDiagVerifierRejectsCorruptedAUTH(t *testing.T) {
	o, e := diagRunPeer(t, "corrupted_auth", 32)
	if e == nil || o.AuthValid || !o.EapSuccessSent || !o.ResponseValid {
		t.Fatalf("negative control failed: connect=%v observation=%+v", e, o)
	}
	t.Log("independent verifier rejects a one-bit change in final AUTH")
}
func TestDiagMustNotSendAUTHBeforeEAPSuccess(t *testing.T) {
	o, e := diagRunPeer(t, "round_limit", 32)
	if o.FinalSeen || e == nil || e.Error() != "IKE_EAP_ROUND_LIMIT_WITHOUT_SUCCESS" {
		t.Fatalf("premature AUTH or incorrect stop: %+v %v", o, e)
	}
}
func TestDiagDeviceIdentityMustWaitForAuthenticatedChallenge(t *testing.T) {
	o, e := diagRunPeer(t, "identity_early", 32)
	if o.EarlyDeviceID || !o.ChallengeDeviceID || e != nil {
		t.Fatalf("identity privacy/retention failure: connect=%v observation=%+v", e, o)
	}
}
func TestDiagEAPOnlyNegotiation(t *testing.T) {
	o, _ := diagRunPeer(t, "normal", 32)
	if !o.EapOnly {
		t.Fatal("missing EAP_ONLY_AUTHENTICATION (16417)")
	}
}

func TestDiagRejectsInvalidResponderProof(t *testing.T) {
	_, err := diagRunPeer(t, "bad_peer_auth", 32)
	if err == nil || err.Error() != "IKE_AUTH_RESPONDER_PROOF_INVALID" {
		t.Fatalf("expected invalid responder proof rejection: %v", err)
	}
}

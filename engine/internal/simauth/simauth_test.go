package simauth

import (
	"bufio"
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"net"
	"path/filepath"
	"testing"
)

func TestUMTSChallengeAndResponseParsing(t *testing.T) {
	rand16 := bytes.Repeat([]byte{0x11}, 16)
	autn16 := bytes.Repeat([]byte{0x22}, 16)
	ch, err := BuildUMTSChallenge(rand16, autn16)
	if err != nil {
		t.Fatal(err)
	}
	if len(ch) != 34 || ch[0] != 16 || ch[17] != 16 {
		t.Fatalf("unexpected challenge format: %x", ch)
	}

	// Success 0xDB
	res := []byte{1, 2, 3, 4, 5, 6, 7, 8}
	ck := bytes.Repeat([]byte{0xAA}, 16)
	ik := bytes.Repeat([]byte{0xBB}, 16)
	var raw bytes.Buffer
	raw.WriteByte(0xDB)
	raw.WriteByte(byte(len(res)))
	raw.Write(res)
	raw.WriteByte(byte(len(ck)))
	raw.Write(ck)
	raw.WriteByte(byte(len(ik)))
	raw.Write(ik)

	parsed, err := ParseUMTSResponse(raw.Bytes())
	if err != nil {
		t.Fatal(err)
	}
	if parsed.Status != StatusOK || !bytes.Equal(parsed.RES, res) || !bytes.Equal(parsed.CK, ck) || !bytes.Equal(parsed.IK, ik) {
		t.Fatalf("mismatch parsed success: %+v", parsed)
	}

	// Sync failure 0xDC
	auts := bytes.Repeat([]byte{0xCC}, 14)
	syncRaw := append([]byte{0xDC, 14}, auts...)
	parsedSync, err := ParseUMTSResponse(syncRaw)
	if err != nil {
		t.Fatal(err)
	}
	if parsedSync.Status != StatusSyncFailure || !bytes.Equal(parsedSync.AUTS, auts) {
		t.Fatalf("mismatch parsed sync failure: %+v", parsedSync)
	}
}

func TestBinderProviderAndAutoFallback(t *testing.T) {
	dir := t.TempDir()
	sock := filepath.Join(dir, "sim-aka.sock")
	ln, err := net.Listen("unix", sock)
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()

	res := []byte{0xDE, 0xAD, 0xBE, 0xEF, 0x01, 0x02, 0x03, 0x04}
	ck := bytes.Repeat([]byte{0x33}, 16)
	ik := bytes.Repeat([]byte{0x44}, 16)
	var respBytes bytes.Buffer
	respBytes.WriteByte(0xDB)
	respBytes.WriteByte(byte(len(res)))
	respBytes.Write(res)
	respBytes.WriteByte(byte(len(ck)))
	respBytes.Write(ck)
	respBytes.WriteByte(byte(len(ik)))
	respBytes.Write(ik)

	go func() {
		conn, err := ln.Accept()
		if err != nil {
			return
		}
		defer conn.Close()
		line, err := bufio.NewReader(conn).ReadString('\n')
		if err != nil {
			return
		}
		var req map[string]any
		_ = json.Unmarshal([]byte(line), &req)
		out, _ := json.Marshal(map[string]any{
			"ok":             true,
			"responseBase64": base64.StdEncoding.EncodeToString(respBytes.Bytes()),
		})
		_, _ = conn.Write(append(out, '\n'))
	}()

	failing := &failProvider{}
	binder := &BinderProvider{SocketPath: sock}
	auto := &AutoProvider{Providers: []Provider{failing, binder}}

	got, err := auto.Authenticate(context.Background(), AuthRequest{
		Slot:    1,
		ICCID:   "8985203000000000001",
		AppType: AppUSIM,
		RAND:    bytes.Repeat([]byte{0x11}, 16),
		AUTN:    bytes.Repeat([]byte{0x22}, 16),
	})
	if err != nil {
		t.Fatalf("AutoProvider fallback failed: %v", err)
	}
	if got.Status != StatusOK || !bytes.Equal(got.RES, res) || !bytes.Equal(got.CK, ck) || !bytes.Equal(got.IK, ik) {
		t.Fatalf("unexpected binder response: %+v", got)
	}
}

type failProvider struct{}

func (f *failProvider) Name() string { return "failing" }
func (f *failProvider) Authenticate(ctx context.Context, req AuthRequest) (*AuthResponse, error) {
	return nil, errors.New("RADIO_NOT_AVAILABLE")
}

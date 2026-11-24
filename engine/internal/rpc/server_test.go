package rpc

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"net"
	"path/filepath"
	"sync"
	"testing"
	"time"
)

type stubHandler struct {
	mu       sync.Mutex
	dialGate chan struct{}
	canceled bool
	stopped  bool
}

func (h *stubHandler) GetConfig() map[string]any { return map[string]any{"enabled": true} }
func (h *stubHandler) GetStatus() map[string]any {
	return map[string]any{"ims": "REGISTERED", "tunnel": "CONNECTED"}
}
func (h *stubHandler) GetCallSnapshot() (map[string]any, error) {
	return map[string]any{"epoch": "ep-1", "revision": 1, "stage": "IDLE", "callId": "", "direction": "NONE", "peer": ""}, nil
}
func (h *stubHandler) Dial(ctx context.Context, recipient string) (map[string]any, error) {
	select {
	case <-h.dialGate:
		return map[string]any{"callId": "c-1", "state": "ENDED"}, nil
	case <-ctx.Done():
		return nil, &RPCError{Code: 2005, Message: "CANCELED"}
	}
}
func (h *stubHandler) Answer(ctx context.Context, callID string) (map[string]any, error) {
	return map[string]any{"callId": callID, "state": "IN_CALL"}, nil
}
func (h *stubHandler) Reject(ctx context.Context, callID string) (map[string]any, error) {
	return map[string]any{"callId": callID, "state": "ENDED"}, nil
}
func (h *stubHandler) Hangup(ctx context.Context, callID string) (map[string]any, error) {
	return map[string]any{"callId": callID, "state": "ENDED"}, nil
}
func (h *stubHandler) CancelCall(ctx context.Context, callID string) (map[string]any, error) {
	h.mu.Lock()
	if !h.canceled {
		h.canceled = true
		close(h.dialGate)
	}
	h.mu.Unlock()
	return map[string]any{"callId": callID, "state": "ENDED"}, nil
}
func (h *stubHandler) SendDTMF(ctx context.Context, callID, digit string, durationMs int) (map[string]any, error) {
	return map[string]any{"ok": true}, nil
}
func (h *stubHandler) AudioStart(ctx context.Context, callID string, sampleRate, channels int) (map[string]any, error) {
	return map[string]any{"ok": true, "sampleRate": sampleRate, "channels": channels}, nil
}
func (h *stubHandler) AudioStop(ctx context.Context, callID string) (map[string]any, error) {
	return map[string]any{"ok": true}, nil
}
func (h *stubHandler) SendSMS(ctx context.Context, number, text string) (map[string]any, error) {
	return map[string]any{"statusCode": 200, "smsId": "sms-1"}, nil
}
func (h *stubHandler) Stop() {
	h.mu.Lock()
	h.stopped = true
	h.mu.Unlock()
}

func rpcCall(t *testing.T, conn net.Conn, r *bufio.Reader, id int, method string, params map[string]any) map[string]any {
	t.Helper()
	b, _ := json.Marshal(map[string]any{"id": id, "method": method, "params": params})
	b = append(b, '\n')
	if _, err := conn.Write(b); err != nil {
		t.Fatalf("write %s: %v", method, err)
	}
	line, err := r.ReadString('\n')
	if err != nil {
		t.Fatalf("read %s: %v", method, err)
	}
	var out map[string]any
	if err := json.Unmarshal([]byte(line), &out); err != nil {
		t.Fatalf("unmarshal %s: %v", method, err)
	}
	return out
}

func TestConcurrentRPCAndAudioPCM(t *testing.T) {
	dir := t.TempDir()
	sock := filepath.Join(dir, "test.sock")
	audioSock := filepath.Join(dir, "test.audio.sock")

	h := &stubHandler{dialGate: make(chan struct{})}
	srv, err := NewServer(sock, h)
	if err != nil {
		t.Fatalf("NewServer: %v", err)
	}
	defer srv.Close()

	// Main connection
	c1, err := net.Dial("unix", sock)
	if err != nil {
		t.Fatalf("dial unix: %v", err)
	}
	defer c1.Close()
	r1 := bufio.NewReader(c1)

	hello := rpcCall(t, c1, r1, 1, "hello", map[string]any{"version": 1})
	res := hello["result"].(map[string]any)
	if res["version"].(float64) != 1 {
		t.Fatalf("expected version 1, got %+v", res)
	}

	snap := rpcCall(t, c1, r1, 2, "getCallSnapshot", map[string]any{})
	sres := snap["result"].(map[string]any)
	if sres["epoch"] != "ep-1" || sres["revision"].(float64) != 1 {
		t.Fatalf("invalid snapshot: %+v", sres)
	}

	// Start blocking dial on conn 2 while conn 3 runs getStatus and cancelCall concurrently
	c2, err := net.Dial("unix", sock)
	if err != nil {
		t.Fatal(err)
	}
	defer c2.Close()
	r2 := bufio.NewReader(c2)
	_ = rpcCall(t, c2, r2, 1, "hello", map[string]any{"version": 1})

	dialDone := make(chan map[string]any, 1)
	go func() {
		dialDone <- rpcCall(t, c2, r2, 2, "dial", map[string]any{"recipient": "tel:+447700900123"})
	}()

	time.Sleep(30 * time.Millisecond)
	statusResp := rpcCall(t, c1, r1, 3, "getStatus", map[string]any{})
	if statusResp["result"].(map[string]any)["ims"] != "REGISTERED" {
		t.Fatalf("unexpected status: %+v", statusResp)
	}
	cancelResp := rpcCall(t, c1, r1, 4, "cancelCall", map[string]any{"callId": "c-1"})
	if cancelResp["result"].(map[string]any)["state"] != "ENDED" {
		t.Fatalf("unexpected cancel: %+v", cancelResp)
	}
	select {
	case dr := <-dialDone:
		if dr["result"].(map[string]any)["callId"] != "c-1" {
			t.Fatalf("unexpected dial result: %+v", dr)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("dial did not unblock after concurrent cancelCall")
	}

	// Broadcast async event
	srv.BroadcastEvent("smsReceived", map[string]any{"smsId": "m-1", "number": "+44123", "text": "hi"})
	_ = c1.SetReadDeadline(time.Now().Add(2 * time.Second))
	evLine, err := r1.ReadString('\n')
	if err != nil {
		t.Fatalf("read broadcast event: %v", err)
	}
	var ev map[string]any
	if err := json.Unmarshal([]byte(evLine), &ev); err != nil || ev["event"] != "smsReceived" {
		t.Fatalf("unexpected event: %s", evLine)
	}

	// Full-duplex Audio PCM frame socket test
	gotUplink := make(chan []byte, 1)
	audioSrv, err := NewAudioServer(audioSock, func(pcm []byte) {
		gotUplink <- pcm
	})
	if err != nil {
		t.Fatalf("NewAudioServer: %v", err)
	}
	defer audioSrv.Close()

	ac, err := net.Dial("unix", audioSock)
	if err != nil {
		t.Fatalf("dial audio unix: %v", err)
	}
	defer ac.Close()

	upPayload := bytes.Repeat([]byte{0x12, 0x34}, PCMPayloadBytes/2)
	upFrame, err := EncodePCMFrame(upPayload)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := ac.Write(upFrame); err != nil {
		t.Fatal(err)
	}
	select {
	case got := <-gotUplink:
		if !bytes.Equal(got, upPayload) {
			t.Fatal("uplink PCM mismatch")
		}
	case <-time.After(2 * time.Second):
		t.Fatal("timed out waiting for uplink PCM")
	}

	downPayload := bytes.Repeat([]byte{0x56, 0x78}, PCMPayloadBytes/2)
	audioSrv.SendDownlink(downPayload)
	_ = ac.SetReadDeadline(time.Now().Add(2 * time.Second))
	gotDown := make([]byte, PCMPayloadBytes)
	if err := ReadPCMFrame(ac, gotDown); err != nil {
		t.Fatalf("read downlink PCM: %v", err)
	}
	if !bytes.Equal(gotDown, downPayload) {
		t.Fatal("downlink PCM mismatch")
	}
}

package rpc

import (
	"bufio"
	"encoding/json"
	"net"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/YubaiNya/SmartVoWifiCall/engine/internal/diag"
)

type logHandler struct {
	stubHandler
	logger *diag.Logger
}

func (h *logHandler) AcknowledgeLogs(keys []string) (map[string]any, error) {
	n, err := h.logger.AcknowledgeLogs(keys)
	return map[string]any{"ok": err == nil, "removed": n}, err
}

func TestLogAcknowledgementOverRPC(t *testing.T) {
	dir := t.TempDir()
	l, err := diag.NewLogger(dir)
	if err != nil {
		t.Fatal(err)
	}
	defer l.Close()
	l.Auth(map[string]any{"stage": "uploaded"})
	data, err := os.ReadFile(filepath.Join(dir, "auth.jsonl"))
	if err != nil {
		t.Fatal(err)
	}
	var record struct {
		Capture  string      `json:"captureId"`
		Sequence json.Number `json:"sequence"`
	}
	if err = json.Unmarshal(data, &record); err != nil {
		t.Fatal(err)
	}
	key := "auth:" + record.Capture + ":" + string(record.Sequence)
	l.Auth(map[string]any{"stage": "new"})
	server, err := NewServer(filepath.Join(dir, "rpc.sock"), &logHandler{logger: l})
	if err != nil {
		t.Fatal(err)
	}
	defer server.Close()
	c, err := net.Dial("unix", server.path)
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	r := bufio.NewReader(c)
	bad := rpcCall(t, c, r, 1, "acknowledgeLogs", map[string]any{"records": []any{key, 12}})
	if bad["error"] == nil {
		t.Fatal("malformed ack accepted")
	}
	unchanged, _ := os.ReadFile(filepath.Join(dir, "auth.jsonl"))
	if !strings.Contains(string(unchanged), "uploaded") {
		t.Fatal("invalid ack deleted logs")
	}
	good := rpcCall(t, c, r, 2, "acknowledgeLogs", map[string]any{"records": []string{key}})
	result, ok := good["result"].(map[string]any)
	if !ok || result["ok"] != true || result["removed"] != float64(1) {
		t.Fatalf("ack failed: %v", good)
	}
	left, _ := os.ReadFile(filepath.Join(dir, "auth.jsonl"))
	if strings.Contains(string(left), "uploaded") || !strings.Contains(string(left), "new") {
		t.Fatal("wrong journal entries removed")
	}
	_, err = (&Server{handler: &stubHandler{}}).dispatch("acknowledgeLogs", map[string]any{"records": []any{key}})
	if err == nil {
		t.Fatal("legacy handler pretended to clean logs")
	}
}

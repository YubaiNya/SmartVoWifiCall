package diag

import (
	"bytes"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
)

func journalKeys(t *testing.T, dir, kind string) []string {
	t.Helper()
	var keys []string
	for _, suffix := range []string{".1", ""} {
		data, err := os.ReadFile(filepath.Join(dir, kind+".jsonl"+suffix))
		if os.IsNotExist(err) {
			continue
		}
		if err != nil {
			t.Fatal(err)
		}
		for _, line := range bytes.Split(data, []byte{'\n'}) {
			if len(line) == 0 {
				continue
			}
			var row map[string]json.RawMessage
			if err := json.Unmarshal(line, &row); err != nil {
				t.Fatal(err)
			}
			var capture string
			if err := json.Unmarshal(row["captureId"], &capture); err != nil {
				t.Fatal(err)
			}
			keys = append(keys, kind+":"+capture+":"+string(row["sequence"]))
		}
	}
	return keys
}

func TestUploadedLogCleanupPreservesConcurrentWrites(t *testing.T) {
	dir := t.TempDir()
	l, err := NewLogger(dir)
	if err != nil {
		t.Fatal(err)
	}
	defer l.Close()
	l.Info("test", "uploaded", nil)
	l.Auth(map[string]any{"stage": "uploaded"})
	keys := append(journalKeys(t, dir, "auth"), journalKeys(t, dir, "diagnostic")...)
	var wg sync.WaitGroup
	wg.Add(1)
	go func() {
		defer wg.Done()
		for i := 0; i < 200; i++ {
			l.Info("test", "new", nil)
			l.Auth(map[string]any{"stage": "new"})
		}
	}()
	n, err := l.AcknowledgeLogs(keys)
	if err != nil || n != 2 {
		t.Fatalf("ack: %d %v", n, err)
	}
	wg.Wait()
	if n, err = l.AcknowledgeLogs(keys); err != nil || n != 0 {
		t.Fatalf("repeat: %d %v", n, err)
	}
	for _, kind := range []string{"auth", "diagnostic"} {
		remaining := journalKeys(t, dir, kind)
		if len(remaining) != 200 {
			t.Fatalf("%s lost new logs: %d", kind, len(remaining))
		}
	}
	l.Info("test", "writer still open", nil)
	if len(journalKeys(t, dir, "diagnostic")) != 201 {
		t.Fatal("writer not restored")
	}
}

func TestUploadedLogCleanupAfterRotation(t *testing.T) {
	dir := t.TempDir()
	l, err := NewLogger(dir)
	if err != nil {
		t.Fatal(err)
	}
	defer l.Close()
	l.maxSize = 1
	l.Info("test", "uploaded", nil)
	l.Auth(map[string]any{"data": strings.Repeat("x", 200000)})
	keys := append(journalKeys(t, dir, "auth"), journalKeys(t, dir, "diagnostic")...)
	l.Info("test", "new", nil)
	l.Auth(map[string]any{"data": strings.Repeat("y", 100000)})
	n, err := l.AcknowledgeLogs(keys)
	if err != nil || n != 2 {
		t.Fatalf("rotated ack: %d %v", n, err)
	}
	for _, kind := range []string{"auth", "diagnostic"} {
		if len(journalKeys(t, dir, kind)) != 1 {
			t.Fatal("rotation lost unacknowledged records")
		}
	}
}

func TestUploadedLogCleanupRejectsInvalidBatchBeforeMutation(t *testing.T) {
	dir := t.TempDir()
	l, err := NewLogger(dir)
	if err != nil {
		t.Fatal(err)
	}
	defer l.Close()
	l.Auth(map[string]any{"stage": "keep"})
	keys := journalKeys(t, dir, "auth")
	before, _ := os.ReadFile(filepath.Join(dir, "auth.jsonl"))
	if _, err = l.AcknowledgeLogs(append(keys, "../../config")); err == nil {
		t.Fatal("invalid accepted")
	}
	after, _ := os.ReadFile(filepath.Join(dir, "auth.jsonl"))
	if !bytes.Equal(before, after) {
		t.Fatal("invalid batch deleted data")
	}
}

func TestUploadedLogCleanupRetainsLegacyAndPartialRecords(t *testing.T) {
	key := "auth:capabcd:1"
	line := []byte("{\"captureId\":\"capabcd\",\"sequence\":1}")
	data := append([]byte("legacy\n"), line...)
	got, n := pruneAcknowledged(data, "auth", map[string]bool{key: true})
	if n != 0 || !bytes.Equal(got, data) {
		t.Fatal("partial or legacy record erased")
	}
	data = append(data, '\n')
	got, n = pruneAcknowledged(data, "auth", map[string]bool{key: true})
	if n != 1 || string(got) != "legacy\n" {
		t.Fatal("complete record not removed")
	}
}

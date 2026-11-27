package diag

import (
	"bytes"
	"encoding/json"
	"errors"
	"io"
	"os"
	"path/filepath"
	"regexp"
)

var uploadedKey = regexp.MustCompile(`^(auth|diagnostic):cap[0-9a-f]+:[1-9][0-9]*$`)

// AcknowledgeLogs removes only individually acknowledged records, under the
// same mutex as append and rotation. It never stops a session or resets counters.
// Legacy/unparseable records and records written during upload are retained.
func (l *Logger) AcknowledgeLogs(keys []string) (int, error) {
	if len(keys) > 8192 {
		return 0, errors.New("LOG_ACK_TOO_LARGE")
	}
	wanted := make(map[string]bool, len(keys))
	for _, key := range keys {
		if !uploadedKey.MatchString(key) {
			return 0, errors.New("LOG_ACK_INVALID")
		}
		wanted[key] = true
	}
	if len(wanted) == 0 || l == nil || l.path == "" {
		return 0, nil
	}
	l.mu.Lock()
	defer l.mu.Unlock()
	if l.file == nil {
		return 0, errors.New("LOG_WRITER_CLOSED")
	}
	removed := 0
	for _, name := range []string{"auth.jsonl.1", "auth.jsonl", "diagnostic.jsonl.1", "diagnostic.jsonl"} {
		path := filepath.Join(filepath.Dir(l.path), name)
		info, err := os.Lstat(path)
		if os.IsNotExist(err) {
			continue
		}
		if err != nil || !info.Mode().IsRegular() || info.Size() > 2*1024*1024 {
			return removed, errors.New("LOG_FILE_UNAVAILABLE")
		}
		data, err := os.ReadFile(path)
		if err != nil {
			return removed, errors.New("LOG_READ_FAILED")
		}
		kind := "diagnostic"
		if name[0] == 'a' {
			kind = "auth"
		}
		kept, count := pruneAcknowledged(data, kind, wanted)
		if count == 0 {
			continue
		}
		// Write completely before replacing anything. A failed write leaves the
		// original journal intact; the active writer is reopened even on failure.
		tmp, err := os.CreateTemp(filepath.Dir(path), ".uploaded-log-*")
		if err != nil {
			return removed, errors.New("LOG_CLEANUP_FAILED")
		}
		tmpName := tmp.Name()
		n, err := tmp.Write(kept)
		if err == nil && n != len(kept) {
			err = io.ErrShortWrite
		}
		if err == nil {
			err = tmp.Sync()
		}
		closeErr := tmp.Close()
		if err == nil {
			err = closeErr
		}
		active := path == l.path
		if err == nil {
			if active {
				err = l.file.Close()
				l.file = nil
			}
			if err == nil {
				err = os.Rename(tmpName, path)
			}
		}
		_ = os.Remove(tmpName)
		if active && l.file == nil {
			var reopenErr error
			l.file, reopenErr = os.OpenFile(l.path, os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0600)
			if err == nil {
				err = reopenErr
			}
		}
		if err != nil {
			return removed, errors.New("LOG_CLEANUP_FAILED")
		}
		removed += count
	}
	return removed, nil
}

func pruneAcknowledged(data []byte, kind string, wanted map[string]bool) ([]byte, int) {
	var out bytes.Buffer
	removed := 0
	for _, line := range bytes.SplitAfter(data, []byte{'\n'}) {
		var row struct {
			Capture string      `json:"captureId"`
			Seq     json.Number `json:"sequence"`
		}
		// Never erase a partial record, even if it happens to be valid JSON.
		complete := len(line) > 0 && line[len(line)-1] == '\n'
		if complete && json.Unmarshal(line, &row) == nil && wanted[kind+":"+row.Capture+":"+string(row.Seq)] {
			removed++
		} else {
			out.Write(line)
		}
	}
	return out.Bytes(), removed
}

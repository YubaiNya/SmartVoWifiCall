package diag

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"sync"
	"time"
)

const Hidden = "[Hidden]"

var (
	reICCID = regexp.MustCompile(`\b89\d{13,20}[0-9A-Fa-f]{0,2}\b`)
	reSIP   = regexp.MustCompile(`(?i)\bsip:[^@\s"'<>]+@`)
	reTel   = regexp.MustCompile(`(?i)\btel:\+?[0-9][0-9\-().\s]{4,18}`)
	rePhone = regexp.MustCompile(`(^|[^0-9A-Za-z_])(\+[1-9]\d{6,14})\b`)
	reLong  = regexp.MustCompile(`\b\d{14,22}\b`)
)

// Sanitize replaces private subscriber identifiers with [Hidden].
func Sanitize(s string) string {
	if s == "" {
		return ""
	}
	s = reICCID.ReplaceAllString(s, Hidden)
	s = reSIP.ReplaceAllString(s, "sip:"+Hidden+"@")
	s = reTel.ReplaceAllString(s, "tel:"+Hidden)
	s = rePhone.ReplaceAllString(s, "${1}"+Hidden)
	s = reLong.ReplaceAllString(s, Hidden)
	return s
}

type Logger struct {
	mu      sync.Mutex
	file    *os.File
	maxSize int64
	path    string
}

func NewLogger(logDir string) (*Logger, error) {
	if logDir == "" {
		return &Logger{}, nil
	}
	if err := os.MkdirAll(logDir, 0o700); err != nil {
		return nil, err
	}
	p := filepath.Join(logDir, "diagnostic.jsonl")
	f, err := os.OpenFile(p, os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o600)
	if err != nil {
		return nil, err
	}
	return &Logger{
		file:    f,
		maxSize: 512 * 1024,
		path:    p,
	}, nil
}

func (l *Logger) Log(level, component, msg string, fields map[string]any) {
	if l == nil {
		return
	}
	entry := map[string]any{
		"ts":        time.Now().UTC().Format(time.RFC3339Nano),
		"level":     level,
		"component": component,
		"msg":       Sanitize(msg),
	}
	if len(fields) > 0 {
		clean := make(map[string]any, len(fields))
		for k, v := range fields {
			switch val := v.(type) {
			case string:
				clean[k] = Sanitize(val)
			default:
				clean[k] = val
			}
		}
		entry["fields"] = clean
	}
	data, err := json.Marshal(entry)
	if err != nil {
		return
	}
	data = append(data, '\n')

	l.mu.Lock()
	defer l.mu.Unlock()
	if l.file == nil {
		return
	}
	if info, err := l.file.Stat(); err == nil && info.Size() > l.maxSize {
		_ = l.file.Close()
		_ = os.Rename(l.path, l.path+".1")
		l.file, _ = os.OpenFile(l.path, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0o600)
	}
	if l.file != nil {
		_, _ = l.file.Write(data)
	}
}

func (l *Logger) Info(component, msg string, fields map[string]any) {
	l.Log("INFO", component, msg, fields)
}

func (l *Logger) Warn(component, msg string, fields map[string]any) {
	l.Log("WARN", component, msg, fields)
}

func (l *Logger) Error(component, msg string, fields map[string]any) {
	l.Log("ERROR", component, msg, fields)
}

func (l *Logger) Close() error {
	if l == nil {
		return nil
	}
	l.mu.Lock()
	defer l.mu.Unlock()
	if l.file != nil {
		err := l.file.Close()
		l.file = nil
		return err
	}
	return nil
}

func FormatStderr(level, component, msg string) {
	fmt.Fprintf(os.Stderr, "%s [%s] [%s] %s\n",
		time.Now().UTC().Format("2006-01-02 15:04:05.000"), level, component, Sanitize(msg))
}

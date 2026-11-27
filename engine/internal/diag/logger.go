package diag

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"runtime/debug"
	"sync"
	"time"
)

const Hidden = "[Hidden]"

var SourceDigest = "unversioned-local-build"

var (
	reICCID = regexp.MustCompile(`\b89\d{13,20}[0-9A-Fa-f]{0,2}\b`)
	reSIP   = regexp.MustCompile(`(?i)(\bsips?:)[^@\s"'<>]+@`)
	reTel   = regexp.MustCompile(`(?i)\btel:\+?[0-9][0-9\-().\s]{4,18}`)
	rePhone = regexp.MustCompile(`(^|[^0-9A-Za-z_])(\+[1-9]\d{6,14})\b`)
	reLong  = regexp.MustCompile(`\b\d{14,22}\b`)
	reNAI   = regexp.MustCompile(`\b([06])\d{15}(@[A-Za-z0-9.-]+)`)
	reMail  = regexp.MustCompile(`\b[^\s"'<>@\[\]]+(@[A-Za-z0-9.-]+)`)
)

// Sanitize replaces private subscriber identifiers with [Hidden].
func Sanitize(s string) string {
	if s == "" {
		return ""
	}
	s = reNAI.ReplaceAllString(s, "${1}"+Hidden+"${2}")
	s = reICCID.ReplaceAllString(s, Hidden)
	s = reSIP.ReplaceAllString(s, "${1}"+Hidden+"@")
	s = reTel.ReplaceAllString(s, "tel:"+Hidden)
	s = rePhone.ReplaceAllString(s, "${1}"+Hidden)
	s = reLong.ReplaceAllString(s, Hidden)
	return s
}

type Logger struct {
	mu           sync.Mutex
	file         *os.File
	maxSize      int64
	path         string
	authSeq      uint64
	logSeq       uint64
	authEpoch    string
	authFailures uint64
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
		file:      f,
		maxSize:   512 * 1024,
		path:      p,
		authEpoch: fmt.Sprintf("cap%x", time.Now().UnixNano()),
	}, nil
}

// BuildInfo identifies the actual engine, independently of the Android app version.
func BuildInfo() map[string]any {
	out := map[string]any{"protocolDiagnostics": 1, "sourceDigest": SourceDigest}
	if b, ok := debug.ReadBuildInfo(); ok {
		out["goVersion"] = b.GoVersion
		for _, s := range b.Settings {
			if s.Key == "vcs.revision" || s.Key == "vcs.modified" || s.Key == "GOARCH" || s.Key == "GOOS" {
				out[s.Key] = s.Value
			}
		}
	}
	return out
}

// Identity preserves the protocol prefix and realm, but never the subscriber part.
func Identity(s string) string {
	s = reNAI.ReplaceAllString(s, "${1}"+Hidden+"${2}")
	s = reSIP.ReplaceAllString(s, "${1}"+Hidden+"@")
	s = reMail.ReplaceAllString(s, Hidden+"${1}")
	return Sanitize(s)
}

// Auth records already protocol-sanitized values in a separate bounded journal.
// Keep both generations; collection must report retention rather than imply all history.
func (l *Logger) Auth(fields map[string]any) {
	if l == nil || l.path == "" {
		return
	}
	l.mu.Lock()
	l.authSeq++
	entry := map[string]any{"schema": "smartvowificall.auth.v1", "ts": time.Now().UTC().Format(time.RFC3339Nano), "captureId": l.authEpoch, "sequence": l.authSeq, "writeFailures": l.authFailures, "engine": BuildInfo()}
	for k, v := range fields {
		entry[k] = v
	}
	data, err := json.Marshal(entry)
	p := filepath.Join(filepath.Dir(l.path), "auth.jsonl")
	if err == nil {
		if st, e := os.Stat(p); e == nil && st.Size()+int64(len(data))+1 > 256*1024 {
			// Windows also needs the previous generation removed before rename.
			if e = os.Remove(p + ".1"); e != nil && !os.IsNotExist(e) {
				err = e
			}
			if err == nil {
				err = os.Rename(p, p+".1")
			}
		}
		if err == nil {
			var f *os.File
			f, err = os.OpenFile(p, os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0600)
			if err == nil {
				_, err = f.Write(append(data, '\n'))
				closeErr := f.Close()
				if err == nil {
					err = closeErr
				}
			}
		}
	}
	if err != nil {
		l.authFailures++
	}
	l.mu.Unlock()
	if err != nil {
		l.Warn("auth_trace", "AUTH_TRACE_WRITE_FAILED", nil)
	}
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
	l.mu.Lock()
	defer l.mu.Unlock()
	if l.file == nil {
		return
	}
	l.logSeq++
	entry["captureId"] = l.authEpoch
	entry["sequence"] = l.logSeq
	data, err := json.Marshal(entry)
	if err != nil {
		return
	}
	data = append(data, '\n')
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

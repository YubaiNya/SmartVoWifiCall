package main

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"flag"
	"fmt"
	"os"
	"os/signal"
	"path/filepath"
	"strconv"
	"syscall"
	"time"

	"github.com/YubaiNya/SmartVoWifiCall/engine/internal/config"
	"github.com/YubaiNya/SmartVoWifiCall/engine/internal/runtime"
	"github.com/YubaiNya/SmartVoWifiCall/engine/internal/simauth"
)

func main() {
	if len(os.Args) > 1 && os.Args[1] == "config-verify" {
		os.Exit(runConfigVerify(os.Args[2:]))
	}
	if len(os.Args) > 1 && os.Args[1] == "qrtr-auth" {
		os.Exit(runQRTRAuth(os.Args[2:]))
	}

	var (
		iccid     string
		cfgPath   string
		pidFile   string
		simMode   string
		simSocket string
		logDir    string
		socketDir string
	)
	flag.StringVar(&iccid, "iccid", "", "SIM ICCID identifier")
	flag.StringVar(&cfgPath, "config", "", "Path to per-SIM config JSON")
	flag.StringVar(&pidFile, "pid-file", "", "Optional PID file path")
	flag.StringVar(&simMode, "sim-mode", "auto", "SIM AKA mode: auto|binder|qrtr|mock")
	flag.StringVar(&simSocket, "sim-socket", "/data/adb/smartvowificall_ims/run/sim-aka.sock", "Cross-baseband Root Binder AKA Unix socket path")
	flag.StringVar(&logDir, "log-dir", "", "Sanitized diagnostic log directory")
	flag.StringVar(&socketDir, "socket-dir", "/data/local/tmp/vowifi", "Control and audio Unix socket directory")
	flag.Parse()

	if cfgPath == "" {
		fmt.Fprintln(os.Stderr, "missing --config")
		os.Exit(2)
	}
	cfg, err := config.LoadFile(cfgPath)
	if err != nil {
		fmt.Fprintf(os.Stderr, "config load failed: %v\n", err)
		os.Exit(2)
	}
	if err := cfg.Validate(true); err != nil {
		fmt.Fprintf(os.Stderr, "config invalid: %v\n", err)
		os.Exit(2)
	}
	if iccid != "" && iccid != cfg.ICCID {
		fmt.Fprintln(os.Stderr, "iccid flag does not match config")
		os.Exit(2)
	}
	if pidFile != "" {
		_ = os.MkdirAll(filepath.Dir(pidFile), 0o700)
		_ = os.WriteFile(pidFile, []byte(strconv.Itoa(os.Getpid())+"\n"), 0o600)
	}
	if logDir == "" {
		logDir = filepath.Join("/data/adb/smartvowificall_ims/logs", cfg.ICCID)
	}

	eng, err := runtime.Start(runtime.Options{
		Config:    cfg,
		SimMode:   simMode,
		SimSocket: simSocket,
		SocketDir: socketDir,
		LogDir:    logDir,
	})
	if err != nil {
		fmt.Fprintf(os.Stderr, "engine start failed: %v\n", err)
		os.Exit(1)
	}

	sigCh := make(chan os.Signal, 2)
	signal.Notify(sigCh, syscall.SIGINT, syscall.SIGTERM)
	go func() {
		<-sigCh
		eng.Stop()
	}()
	eng.Wait()
}

func runConfigVerify(args []string) int {
	fs := flag.NewFlagSet("config-verify", flag.ContinueOnError)
	strict := fs.Bool("strict", false, "Enforce strict production config invariants")
	if err := fs.Parse(args); err != nil || fs.NArg() != 1 {
		writeVerifyJSON(false, "INVALID_ARGUMENT")
		return 1
	}
	cfg, err := config.LoadFile(fs.Arg(0))
	if err != nil {
		code := "CONFIG_INVALID"
		if ve, ok := err.(*config.ValidationError); ok {
			code = ve.Code
		}
		writeVerifyJSON(false, code)
		return 1
	}
	if err := cfg.Validate(*strict); err != nil {
		code := "CONFIG_INVALID"
		if ve, ok := err.(*config.ValidationError); ok {
			code = ve.Code
		}
		writeVerifyJSON(false, code)
		return 1
	}
	writeVerifyJSON(true, "CONFIG_VALID")
	return 0
}

func writeVerifyJSON(ok bool, code string) {
	b, _ := json.Marshal(map[string]any{"ok": ok, "code": code})
	fmt.Println(string(b))
}

func runQRTRAuth(args []string) int {
	fs := flag.NewFlagSet("qrtr-auth", flag.ContinueOnError)
	slot := fs.Int("slot", 0, "SIM slot index")
	chB64 := fs.String("challenge", "", "Base64 TS 31.102 UMTS AKA challenge")
	if err := fs.Parse(args); err != nil || *chB64 == "" {
		writeVerifyJSON(false, "INVALID_ARGUMENT")
		return 1
	}
	ch, err := base64.StdEncoding.DecodeString(*chB64)
	if err != nil {
		writeVerifyJSON(false, "CHALLENGE_BASE64_INVALID")
		return 1
	}
	ctx, cancel := context.WithTimeout(context.Background(), 6*time.Second)
	defer cancel()
	q := &simauth.QRTRProvider{Timeout: 5 * time.Second}
	raw, err := q.AuthenticateRaw(ctx, *slot, ch)
	if err != nil {
		b, _ := json.Marshal(map[string]any{"ok": false, "code": err.Error()})
		fmt.Println(string(b))
		return 1
	}
	b, _ := json.Marshal(map[string]any{
		"ok":             true,
		"method":         "qrtr",
		"responseBase64": base64.StdEncoding.EncodeToString(raw),
	})
	fmt.Println(string(b))
	return 0
}

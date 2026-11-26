package runtime

import (
	"bufio"
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/YubaiNya/SmartVoWifiCall/engine/internal/config"
	"github.com/YubaiNya/SmartVoWifiCall/engine/internal/diag"
)

func TestStopReasonIsBoundedAndDoesNotExposeInput(t *testing.T) {
	for _, reason := range []string{"SIGTERM", "SIGINT", "RPC_STOP", "STOP_REQUEST", "private-fixture-do-not-log"} {
		t.Run(reason, func(t *testing.T) {
			dir := t.TempDir()
			logger, err := diag.NewLogger(dir)
			if err != nil {
				t.Fatal(err)
			}
			ctx, cancel := context.WithCancel(context.Background())
			e := &Engine{opts: Options{Config: &config.Config{Slot: 1}}, logger: logger,
				ctx: ctx, cancel: cancel, stopCh: make(chan struct{}), startedAt: time.Now()}
			e.StopWithReason(reason)
			e.StopWithReason("RPC_STOP")
			if ctx.Err() != context.Canceled {
				t.Fatal("diagnostic path did not preserve cancellation")
			}
			data, err := os.ReadFile(filepath.Join(dir, "diagnostic.jsonl"))
			if err != nil {
				t.Fatal(err)
			}
			if strings.Contains(string(data), "private-fixture") {
				t.Fatal("unknown stop input leaked")
			}
			want := reason
			if strings.HasPrefix(reason, "private-") {
				want = "UNSPECIFIED"
			}
			scanner := bufio.NewScanner(strings.NewReader(string(data)))
			count := 0
			for scanner.Scan() {
				var row map[string]any
				if err := json.Unmarshal(scanner.Bytes(), &row); err != nil {
					t.Fatal(err)
				}
				if row["component"] != "lifecycle" {
					continue
				}
				count++
				fields := row["fields"].(map[string]any)
				if fields["event"] != "STOP_REQUEST" || fields["reason"] != want || fields["slot"] != float64(1) || fields["processId"] != float64(os.Getpid()) {
					t.Fatal("missing stop attribution")
				}
			}
			if count != 1 {
				t.Fatalf("stop diagnostics repeated: %d", count)
			}
		})
	}
}

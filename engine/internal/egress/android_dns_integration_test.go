//go:build linux && integration

package egress

import (
	"context"
	"errors"
	"os"
	"testing"
	"time"
)

// An isolated Android emulator supplies the candidate resolver APK at resolverAPK.
func TestAndroidNetworkDNS(t *testing.T) {
	if !HasAndroidResolver() {
		t.Skip("Android app_process required")
	}
	iface := os.Getenv("VOWIFI_TEST_INTERFACE")
	if iface == "" {
		t.Fatal("explicit emulator interface required")
	}
	for _, selected := range []string{"", iface} {
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		ip, err := ResolveAndroid(ctx, "example.com", selected)
		cancel()
		if err != nil || ip.To4() == nil {
			t.Fatalf("Android network DNS did not return IPv4: %v", err)
		}
	}
}

func TestAndroidNetworkDNSCancellation(t *testing.T) {
	if !HasAndroidResolver() {
		t.Skip("Android app_process required")
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, err := ResolveAndroid(ctx, "example.com", ""); !errors.Is(err, context.Canceled) {
		t.Fatal("resolver lost cancellation")
	}
}

func TestAndroidNetworkDNSDoesNotUseMissingInterface(t *testing.T) {
	if !HasAndroidResolver() {
		t.Skip("Android app_process required")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if ip, err := ResolveAndroid(ctx, "example.com", "svwmissing9"); err == nil || ip != nil {
		t.Fatal("missing interface silently used system DNS")
	}
}

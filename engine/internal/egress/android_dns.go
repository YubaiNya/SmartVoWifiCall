package egress

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"net"
	"os"
	"os/exec"
)

const androidProcess = "/system/bin/app_process"
const resolverAPK = "/data/adb/modules/smartvowificall/system/priv-app/SmartVoWifiCall/SmartVoWifiCall.apk"

func HasAndroidResolver() bool {
	_, err := os.Stat(androidProcess)
	return err == nil
}

// ResolveAndroid uses Android Network DNS, including its configured DNS policy.
// Every connection attempt gets a fresh network; no ePDG address is persisted.
func ResolveAndroid(ctx context.Context, host, iface string) (net.IP, error) {
	if iface == "" {
		iface = "@system"
	}
	cmd := exec.CommandContext(ctx, androidProcess, "/system/bin",
		"com.smartvowificall.ims.root.RootNetworkMain", "resolve", iface, host)
	cmd.Env = append(os.Environ(), "CLASSPATH="+resolverAPK)
	var output bytes.Buffer
	cmd.Stdout = &output
	err := cmd.Run()
	if ctx.Err() != nil {
		return nil, ctx.Err()
	}
	if err != nil {
		return nil, errors.New("ANDROID_DNS_FAILED")
	}
	var reply struct {
		OK        bool     `json:"ok"`
		Addresses []string `json:"addresses"`
	}
	if json.Unmarshal(output.Bytes(), &reply) != nil || !reply.OK {
		return nil, errors.New("ANDROID_DNS_FAILED")
	}
	for _, value := range reply.Addresses {
		if ip := net.ParseIP(value); ip != nil && ip.To4() != nil {
			return ip.To4(), nil
		}
	}
	return nil, errors.New("DNS_NO_IPV4_RECORD")
}

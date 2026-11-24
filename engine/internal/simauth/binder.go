package simauth

import (
	"bufio"
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"time"
)

// BinderProvider talks over a protected root Unix Domain Socket to SmartVoWifiCall's Android
// RootSimAuthMain service (which calls ITelephony.getIccAuthentication with APDU logical channel
// fallback), enabling cross-baseband SIM AKA on Qualcomm, MediaTek, Tensor, and Exynos devices.
type BinderProvider struct {
	SocketPath string
	Timeout    time.Duration
}

func (b *BinderProvider) Name() string {
	return "binder"
}

func (b *BinderProvider) Authenticate(ctx context.Context, req AuthRequest) (*AuthResponse, error) {
	if b.SocketPath == "" {
		return nil, errors.New("SIM_BINDER_SOCKET_UNSET")
	}
	challenge, err := BuildUMTSChallenge(req.RAND, req.AUTN)
	if err != nil {
		return nil, err
	}
	timeout := b.Timeout
	if timeout <= 0 {
		timeout = 8 * time.Second
	}
	dialer := net.Dialer{Timeout: timeout}
	conn, err := dialer.DialContext(ctx, "unix", b.SocketPath)
	if err != nil {
		return nil, fmt.Errorf("SIM_BINDER_CONNECT_FAILED: %w", err)
	}
	defer conn.Close()
	deadline, ok := ctx.Deadline()
	if !ok {
		deadline = time.Now().Add(timeout)
	}
	_ = conn.SetDeadline(deadline)

	payload := map[string]any{
		"op":              "authenticate",
		"slot":            req.Slot,
		"iccid":           req.ICCID,
		"appType":         int(req.AppType),
		"authType":        AuthTypeEAPAKA,
		"challengeBase64": base64.StdEncoding.EncodeToString(challenge),
	}
	wire, err := json.Marshal(payload)
	if err != nil {
		return nil, err
	}
	wire = append(wire, '\n')
	if _, err := conn.Write(wire); err != nil {
		return nil, fmt.Errorf("SIM_BINDER_WRITE_FAILED: %w", err)
	}

	line, err := bufio.NewReader(conn).ReadString('\n')
	if err != nil {
		return nil, fmt.Errorf("SIM_BINDER_READ_FAILED: %w", err)
	}
	var reply struct {
		OK             bool   `json:"ok"`
		Code           string `json:"code"`
		ResponseBase64 string `json:"responseBase64"`
	}
	if err := json.Unmarshal([]byte(line), &reply); err != nil {
		return nil, fmt.Errorf("SIM_BINDER_REPLY_INVALID: %w", err)
	}
	if !reply.OK || reply.ResponseBase64 == "" {
		if reply.Code == "" {
			reply.Code = "SIM_BINDER_AUTH_FAILED"
		}
		return nil, errors.New(reply.Code)
	}
	return ParseBase64UMTSResponse(reply.ResponseBase64)
}

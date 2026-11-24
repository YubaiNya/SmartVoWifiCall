package simauth

import (
	"context"
	"encoding/base64"
	"errors"
	"fmt"
	"os"
)

// AppType matches Android TelephonyManager APPTYPE constants.
type AppType int

const (
	AppUSIM AppType = 2
	AppISIM AppType = 5
)

// AuthType matches Android TelephonyManager AUTHTYPE_EAP_AKA (129).
const AuthTypeEAPAKA = 129

type AuthStatus int

const (
	StatusOK          AuthStatus = 0xDB
	StatusSyncFailure AuthStatus = 0xDC
)

type AuthRequest struct {
	Slot    int
	ICCID   string
	AppType AppType
	RAND    []byte // 16 bytes
	AUTN    []byte // 16 bytes
}

type AuthResponse struct {
	Status AuthStatus
	RES    []byte
	CK     []byte
	IK     []byte
	AUTS   []byte // 14 bytes when Status == StatusSyncFailure
}

// Provider abstracts SIM/USIM/ISIM AKA authentication across Qualcomm QRTR, universal Android Root Binder, and offline mocks.
type Provider interface {
	Name() string
	Authenticate(ctx context.Context, req AuthRequest) (*AuthResponse, error)
}

// BuildUMTSChallenge constructs the 3GPP TS 31.102 authenticate input: 0x10 || RAND(16) || 0x10 || AUTN(16).
func BuildUMTSChallenge(rand16, autn16 []byte) ([]byte, error) {
	if len(rand16) != 16 || len(autn16) != 16 {
		return nil, fmt.Errorf("SIM_AKA_INVALID_LEN: rand=%d autn=%d", len(rand16), len(autn16))
	}
	out := make([]byte, 34)
	out[0] = 16
	copy(out[1:17], rand16)
	out[17] = 16
	copy(out[18:34], autn16)
	return out, nil
}

// ParseUMTSResponse decodes a 3GPP TS 31.102 §7.1.2.1 authentication response (0xDB success or 0xDC sync failure).
func ParseUMTSResponse(raw []byte) (*AuthResponse, error) {
	if len(raw) < 2 {
		return nil, errors.New("SIM_AKA_RESPONSE_SHORT")
	}
	switch raw[0] {
	case 0xDB:
		off := 1
		readTLV := func(name string) ([]byte, error) {
			if off >= len(raw) {
				return nil, fmt.Errorf("SIM_AKA_TRUNCATED_%s", name)
			}
			n := int(raw[off])
			off++
			if off+n > len(raw) {
				return nil, fmt.Errorf("SIM_AKA_OVERFLOW_%s", name)
			}
			b := make([]byte, n)
			copy(b, raw[off:off+n])
			off += n
			return b, nil
		}
		res, err := readTLV("RES")
		if err != nil {
			return nil, err
		}
		ck, err := readTLV("CK")
		if err != nil {
			return nil, err
		}
		ik, err := readTLV("IK")
		if err != nil {
			return nil, err
		}
		return &AuthResponse{
			Status: StatusOK,
			RES:    res,
			CK:     ck,
			IK:     ik,
		}, nil
	case 0xDC:
		n := int(raw[1])
		if n != 14 || len(raw) < 2+n {
			return nil, fmt.Errorf("SIM_AKA_INVALID_AUTS_LEN_%d", n)
		}
		auts := make([]byte, n)
		copy(auts, raw[2:2+n])
		return &AuthResponse{
			Status: StatusSyncFailure,
			AUTS:   auts,
		}, nil
	default:
		return nil, fmt.Errorf("SIM_AKA_UNKNOWN_TAG_0x%02X", raw[0])
	}
}

// ParseBase64UMTSResponse decodes a base64-encoded TS 31.102 response (as returned by ITelephony.getIccAuthentication).
func ParseBase64UMTSResponse(b64 string) (*AuthResponse, error) {
	raw, err := base64.StdEncoding.DecodeString(b64)
	if err != nil {
		return nil, fmt.Errorf("SIM_AKA_BASE64_INVALID: %w", err)
	}
	return ParseUMTSResponse(raw)
}

// NewChipsetAutoProvider orders QRTR vs universal Root Binder according to SoC platform:
// - Qualcomm (qrtrPresent=true): QRTR first (survives enableModemForSlot=false), Binder fallback.
// - MediaTek / Tensor / Exynos / Generic: Binder first, QRTR fallback.
func NewChipsetAutoProvider(qrtrPresent bool, binder, qrtr Provider) *AutoProvider {
	if qrtrPresent {
		return &AutoProvider{Providers: []Provider{qrtr, binder}}
	}
	return &AutoProvider{Providers: []Provider{binder, qrtr}}
}

// DetectQRTRPresent checks whether the Linux kernel exposes a Qualcomm QRTR bus/device.
func DetectQRTRPresent() bool {
	if _, err := os.Stat("/dev/qrtr"); err == nil {
		return true
	}
	if st, err := os.Stat("/sys/bus/qrtr"); err == nil && st.IsDir() {
		return true
	}
	return false
}

// AutoProvider tries each provider in order until one succeeds (used to combine universal Binder + Qualcomm QRTR).
type AutoProvider struct {
	Providers []Provider
}

func (a *AutoProvider) Name() string {
	return "auto"
}

func (a *AutoProvider) Authenticate(ctx context.Context, req AuthRequest) (*AuthResponse, error) {
	var lastErr error
	for _, p := range a.Providers {
		if p == nil {
			continue
		}
		resp, err := p.Authenticate(ctx, req)
		if err == nil && resp != nil {
			return resp, nil
		}
		lastErr = fmt.Errorf("%s: %w", p.Name(), err)
	}
	if lastErr == nil {
		return nil, errors.New("SIM_AKA_NO_PROVIDER")
	}
	return nil, lastErr
}

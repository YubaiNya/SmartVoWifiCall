//go:build !linux

package simauth

import (
	"context"
	"errors"
	"time"
)

type QRTRProvider struct {
	Timeout time.Duration
}

func (q *QRTRProvider) Name() string {
	return "qrtr"
}

func (q *QRTRProvider) Authenticate(ctx context.Context, req AuthRequest) (*AuthResponse, error) {
	return nil, errors.New("QRTR_UNSUPPORTED_PLATFORM")
}

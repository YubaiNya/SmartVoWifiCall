package simauth

import (
	"context"
	"crypto/sha256"
)

// MockProvider is a deterministic offline SIM AKA provider for unit tests (supports OK + AUTS resync).
type MockProvider struct {
	ForceSyncFailOnce bool
	SyncFailAUTS      []byte
	KeyMaterial       []byte
	calls             int
}

func (m *MockProvider) Name() string {
	return "mock"
}

func (m *MockProvider) Authenticate(ctx context.Context, req AuthRequest) (*AuthResponse, error) {
	m.calls++
	if m.ForceSyncFailOnce && m.calls == 1 {
		auts := m.SyncFailAUTS
		if len(auts) != 14 {
			auts = []byte{0xA1, 0xA2, 0xA3, 0xA4, 0xA5, 0xA6, 0xA7, 0xA8, 0xA9, 0xAA, 0xAB, 0xAC, 0xAD, 0xAE}
		}
		cp := make([]byte, 14)
		copy(cp, auts)
		return &AuthResponse{
			Status: StatusSyncFailure,
			AUTS:   cp,
		}, nil
	}
	// Deterministic derivation of RES (8B), CK (16B), IK (16B) from KeyMaterial + RAND + AUTN.
	h1 := sha256.New()
	h1.Write([]byte("RES"))
	h1.Write(m.KeyMaterial)
	h1.Write(req.RAND)
	h1.Write(req.AUTN)
	resFull := h1.Sum(nil)

	h2 := sha256.New()
	h2.Write([]byte("CKIK"))
	h2.Write(m.KeyMaterial)
	h2.Write(req.RAND)
	h2.Write(req.AUTN)
	ckik := h2.Sum(nil)

	return &AuthResponse{
		Status: StatusOK,
		RES:    append([]byte(nil), resFull[:8]...),
		CK:     append([]byte(nil), ckik[:16]...),
		IK:     append([]byte(nil), ckik[16:32]...),
	}, nil
}

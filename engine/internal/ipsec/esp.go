package ipsec

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha1"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/binary"
	"errors"
	"fmt"
	"hash"
	"sync"
)

type CipherSuite int

const (
	SuiteAESGCM128 CipherSuite = iota + 1
	SuiteAESGCM256
	SuiteAESCBC128HMACSHA256
	SuiteAESCBC256HMACSHA256
	SuiteAESCBC128HMACSHA1
	SuiteAESCBC256HMACSHA1
)

// ReplayWindow implements RFC 4303 64-bit sliding anti-replay window.
type ReplayWindow struct {
	highest uint64
	bitmap  uint64
}

func (w *ReplayWindow) CheckAndUpdate(seq uint32) bool {
	if seq == 0 {
		return false
	}
	s := uint64(seq)
	if s > w.highest {
		shift := s - w.highest
		if shift >= 64 {
			w.bitmap = 1
		} else {
			w.bitmap = (w.bitmap << shift) | 1
		}
		w.highest = s
		return true
	}
	diff := w.highest - s
	if diff >= 64 {
		return false
	}
	mask := uint64(1) << diff
	if (w.bitmap & mask) != 0 {
		return false
	}
	w.bitmap |= mask
	return true
}

// SA represents one unidirectional IPsec ESP Security Association (RFC 4303).
type SA struct {
	mu      sync.Mutex
	SPI     uint32
	Suite   CipherSuite
	EncKey  []byte
	AuthKey []byte // unused for GCM
	Salt    []byte // 4 bytes for AES-GCM (RFC 4106)
	seq     uint32
	replay  ReplayWindow
	aead    cipher.AEAD
	block   cipher.Block
}

func NewSA(spi uint32, suite CipherSuite, encKey, authKey, salt []byte) (*SA, error) {
	sa := &SA{
		SPI:     spi,
		Suite:   suite,
		EncKey:  append([]byte(nil), encKey...),
		AuthKey: append([]byte(nil), authKey...),
		Salt:    append([]byte(nil), salt...),
	}
	blk, err := aes.NewCipher(sa.EncKey)
	if err != nil {
		return nil, fmt.Errorf("ESP_CIPHER_INIT: %w", err)
	}
	sa.block = blk
	if suite == SuiteAESGCM128 || suite == SuiteAESGCM256 {
		if len(sa.Salt) != 4 {
			return nil, errors.New("ESP_GCM_SALT_LEN_INVALID")
		}
		aead, err := cipher.NewGCM(blk)
		if err != nil {
			return nil, err
		}
		sa.aead = aead
	}
	return sa, nil
}

// Encrypt wraps innerPacket (IPv4 nextHeader=4 or IPv6 nextHeader=41) into an RFC 4303 ESP packet.
func (sa *SA) Encrypt(nextHeader byte, innerPacket []byte) ([]byte, error) {
	sa.mu.Lock()
	sa.seq++
	seq := sa.seq
	sa.mu.Unlock()
	if seq == 0 {
		return nil, errors.New("ESP_SEQ_OVERFLOW")
	}

	// Build padded payload: inner || padding(1..padLen) || padLen || nextHeader
	align := 4
	if sa.aead == nil {
		align = aes.BlockSize
	}
	rawLen := len(innerPacket) + 2
	padLen := (align - (rawLen % align)) % align
	plain := make([]byte, len(innerPacket)+padLen+2)
	copy(plain, innerPacket)
	for i := 0; i < padLen; i++ {
		plain[len(innerPacket)+i] = byte(i + 1)
	}
	plain[len(plain)-2] = byte(padLen)
	plain[len(plain)-1] = nextHeader

	var hdr [8]byte
	binary.BigEndian.PutUint32(hdr[0:4], sa.SPI)
	binary.BigEndian.PutUint32(hdr[4:8], seq)

	if sa.aead != nil {
		// RFC 4106 AES-GCM: 8-byte explicit IV, 12-byte nonce = Salt(4) || IV(8), AAD = SPI||Seq
		var iv [8]byte
		binary.BigEndian.PutUint64(iv[:], uint64(seq))
		var nonce [12]byte
		copy(nonce[0:4], sa.Salt)
		copy(nonce[4:12], iv[:])
		sealed := sa.aead.Seal(nil, nonce[:], plain, hdr[:])
		out := make([]byte, 8+8+len(sealed))
		copy(out[0:8], hdr[:])
		copy(out[8:16], iv[:])
		copy(out[16:], sealed)
		return out, nil
	}

	// AES-CBC + HMAC-SHA256-128 or HMAC-SHA1-96
	iv := make([]byte, aes.BlockSize)
	if _, err := rand.Read(iv); err != nil {
		return nil, err
	}
	ciphertext := make([]byte, len(plain))
	cipher.NewCBCEncrypter(sa.block, iv).CryptBlocks(ciphertext, plain)

	icvLen, newHash := sa.hmacParams()
	out := make([]byte, 8+len(iv)+len(ciphertext)+icvLen)
	copy(out[0:8], hdr[:])
	copy(out[8:8+len(iv)], iv)
	copy(out[8+len(iv):8+len(iv)+len(ciphertext)], ciphertext)

	mac := hmac.New(newHash, sa.AuthKey)
	mac.Write(out[:len(out)-icvLen])
	sum := mac.Sum(nil)
	copy(out[len(out)-icvLen:], sum[:icvLen])
	return out, nil
}

// Decrypt authenticates, anti-replay checks, and decrypts an RFC 4303 ESP packet.
func (sa *SA) Decrypt(esp []byte) (byte, []byte, error) {
	if len(esp) < 24 {
		return 0, nil, errors.New("ESP_PACKET_TOO_SHORT")
	}
	spi := binary.BigEndian.Uint32(esp[0:4])
	if spi != sa.SPI {
		return 0, nil, fmt.Errorf("ESP_SPI_MISMATCH: got 0x%08x want 0x%08x", spi, sa.SPI)
	}
	seq := binary.BigEndian.Uint32(esp[4:8])

	var plain []byte
	if sa.aead != nil {
		if len(esp) < 8+8+sa.aead.Overhead()+2 {
			return 0, nil, errors.New("ESP_GCM_SHORT")
		}
		var nonce [12]byte
		copy(nonce[0:4], sa.Salt)
		copy(nonce[4:12], esp[8:16])
		var err error
		plain, err = sa.aead.Open(nil, nonce[:], esp[16:], esp[0:8])
		if err != nil {
			return 0, nil, errors.New("ESP_GCM_AUTH_FAILED")
		}
	} else {
		icvLen, newHash := sa.hmacParams()
		if len(esp) < 8+aes.BlockSize+aes.BlockSize+icvLen {
			return 0, nil, errors.New("ESP_CBC_SHORT")
		}
		authPart := esp[:len(esp)-icvLen]
		icv := esp[len(esp)-icvLen:]
		mac := hmac.New(newHash, sa.AuthKey)
		mac.Write(authPart)
		expected := mac.Sum(nil)[:icvLen]
		if subtle.ConstantTimeCompare(icv, expected) != 1 {
			return 0, nil, errors.New("ESP_HMAC_FAILED")
		}
		iv := authPart[8 : 8+aes.BlockSize]
		ct := authPart[8+aes.BlockSize:]
		if len(ct) == 0 || len(ct)%aes.BlockSize != 0 {
			return 0, nil, errors.New("ESP_CBC_ALIGNMENT")
		}
		plain = make([]byte, len(ct))
		cipher.NewCBCDecrypter(sa.block, iv).CryptBlocks(plain, ct)
	}

	// Anti-replay check ONLY after cryptographic authentication succeeds (RFC 4303 §3.4.3).
	sa.mu.Lock()
	ok := sa.replay.CheckAndUpdate(seq)
	sa.mu.Unlock()
	if !ok {
		return 0, nil, errors.New("ESP_REPLAY_REJECTED")
	}

	if len(plain) < 2 {
		return 0, nil, errors.New("ESP_PLAIN_SHORT")
	}
	padLen := int(plain[len(plain)-2])
	nextHdr := plain[len(plain)-1]
	if padLen+2 > len(plain) {
		return 0, nil, errors.New("ESP_PAD_INVALID")
	}
	return nextHdr, plain[:len(plain)-padLen-2], nil
}

func (sa *SA) hmacParams() (int, func() hash.Hash) {
	switch sa.Suite {
	case SuiteAESCBC128HMACSHA1, SuiteAESCBC256HMACSHA1:
		return 12, sha1.New
	default:
		return 16, sha256.New
	}
}

package ikev2

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/ecdh"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha1"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/binary"
	"encoding/hex"
	"errors"
	"fmt"
	"hash"
	"math/big"
)

// RFC 2409 Group 2 (1024-bit MODP) prime.
const modp1024Hex = "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD1" +
	"29024E088A67CC74020BBEA63B139B22514A08798E3404DD" +
	"EF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C245" +
	"E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED" +
	"EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE65381" +
	"FFFFFFFFFFFFFFFF"

// RFC 3526 Group 5 (1536-bit MODP) prime.
const modp1536Hex = "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD1" +
	"29024E088A67CC74020BBEA63B139B22514A08798E3404DD" +
	"EF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C245" +
	"E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED" +
	"EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3D" +
	"C2007CB8A163BF0598DA48361C55D39A69163FA8FD24CF5F" +
	"83655D23DCA3AD961C62F356208552BB9ED529077096966D" +
	"670C354E4ABC9804F1746C08CA237327FFFFFFFFFFFFFFFF"

// RFC 3526 Group 14 (2048-bit MODP) prime.
const modp2048Hex = "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD1" +
	"29024E088A67CC74020BBEA63B139B22514A08798E3404DD" +
	"EF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C245" +
	"E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED" +
	"EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3D" +
	"C2007CB8A163BF0598DA48361C55D39A69163FA8FD24CF5F" +
	"83655D23DCA3AD961C62F356208552BB9ED529077096966D" +
	"670C354E4ABC9804F1746C08CA18217C32905E462E36CE3B" +
	"E39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9" +
	"DE2BCBF6955817183995497CEA956AE515D2261898FA0510" +
	"15728E5A8AACAA68FFFFFFFFFFFFFFFF"

var (
	modp1024Prime = mustBigHex(modp1024Hex)
	modp1536Prime = mustBigHex(modp1536Hex)
	modp2048Prime = mustBigHex(modp2048Hex)
)

func mustBigHex(h string) *big.Int {
	b, _ := hex.DecodeString(h)
	return new(big.Int).SetBytes(b)
}

const (
	DHGroupMODP1024 uint16 = 2
	DHGroupMODP1536 uint16 = 5
	DHGroupMODP2048 uint16 = 14
	DHGroupECP256   uint16 = 19

	PRFAlgHMACSHA1   uint16 = 2
	PRFAlgHMACSHA256 uint16 = 5

	AuthAlgHMACSHA196    uint16 = 2
	AuthAlgHMACSHA256128 uint16 = 12
)

type DHKeyPair struct {
	Group   uint16
	Public  []byte
	privBig *big.Int
	ecPriv  *ecdh.PrivateKey
}

func modpParams(group uint16) (*big.Int, int, bool) {
	switch group {
	case DHGroupMODP1024:
		return modp1024Prime, 128, true
	case DHGroupMODP1536:
		return modp1536Prime, 192, true
	case DHGroupMODP2048:
		return modp2048Prime, 256, true
	default:
		return nil, 0, false
	}
}

func GenerateDH(group uint16) (*DHKeyPair, error) {
	if prime, size, ok := modpParams(group); ok {
		privBytes := make([]byte, 32)
		if _, err := rand.Read(privBytes); err != nil {
			return nil, err
		}
		priv := new(big.Int).SetBytes(privBytes)
		g := big.NewInt(2)
		pub := new(big.Int).Exp(g, priv, prime)
		pubBytes := make([]byte, size)
		pub.FillBytes(pubBytes)
		return &DHKeyPair{Group: group, Public: pubBytes, privBig: priv}, nil
	}
	if group == DHGroupECP256 {
		priv, err := ecdh.P256().GenerateKey(rand.Reader)
		if err != nil {
			return nil, err
		}
		raw := priv.PublicKey().Bytes()
		return &DHKeyPair{Group: group, Public: append([]byte(nil), raw[1:]...), ecPriv: priv}, nil
	}
	return nil, fmt.Errorf("UNSUPPORTED_DH_GROUP_%d", group)
}

func (kp *DHKeyPair) SharedSecret(peerPub []byte) ([]byte, error) {
	if prime, size, ok := modpParams(kp.Group); ok {
		if len(peerPub) == 0 {
			return nil, errors.New("DH_PEER_EMPTY")
		}
		p := new(big.Int).SetBytes(peerPub)
		secret := new(big.Int).Exp(p, kp.privBig, prime)
		out := make([]byte, size)
		secret.FillBytes(out)
		return out, nil
	}
	if kp.Group == DHGroupECP256 {
		if len(peerPub) != 64 {
			return nil, errors.New("ECDH_PEER_LEN_INVALID")
		}
		full := make([]byte, 65)
		full[0] = 0x04
		copy(full[1:], peerPub)
		pub, err := ecdh.P256().NewPublicKey(full)
		if err != nil {
			return nil, err
		}
		return kp.ecPriv.ECDH(pub)
	}
	return nil, errors.New("UNSUPPORTED_DH_GROUP")
}

// IKESuite holds the negotiated IKE_SA cipher/PRF/integrity parameters.
type IKESuite struct {
	EncKeyLen int    // 16 or 32
	PRFID     uint16 // PRFAlgHMACSHA256 or PRFAlgHMACSHA1
	AuthID    uint16 // AuthAlgHMACSHA256128 or AuthAlgHMACSHA196
	AEAD      bool
}

func DefaultIKESuite() IKESuite {
	return IKESuite{EncKeyLen: 16, PRFID: PRFAlgHMACSHA256, AuthID: AuthAlgHMACSHA256128}
}

func prfHashFunc(prfID uint16) func() hash.Hash {
	if prfID == PRFAlgHMACSHA1 {
		return sha1.New
	}
	return sha256.New
}

func prfWithHash(newH func() hash.Hash, key, data []byte) []byte {
	m := hmac.New(newH, key)
	m.Write(data)
	return m.Sum(nil)
}

func prfPlusWithHash(newH func() hash.Hash, key, seed []byte, outLen int) []byte {
	var out []byte
	var prev []byte
	for counter := byte(1); len(out) < outLen; counter++ {
		m := hmac.New(newH, key)
		m.Write(prev)
		m.Write(seed)
		m.Write([]byte{counter})
		prev = m.Sum(nil)
		out = append(out, prev...)
	}
	return out[:outLen]
}

// PRF computes HMAC-SHA256(key, data).
func PRFSHA256(key, data []byte) []byte {
	return prfWithHash(sha256.New, key, data)
}

// PRFPlus implements RFC 7296 §2.13 prf+(K, S) using HMAC-SHA256.
func PRFPlusSHA256(key, seed []byte, outLen int) []byte {
	return prfPlusWithHash(sha256.New, key, seed, outLen)
}

// IKEKeys holds the RFC 7296 IKE_SA derived symmetric keys.
type IKEKeys struct {
	SKd   []byte
	SKai  []byte
	SKar  []byte
	SKei  []byte
	SKer  []byte
	SKpi  []byte
	SKpr  []byte
	AEAD  bool
	Suite IKESuite
}

func (k *IKEKeys) PRF(key, data []byte) []byte {
	return prfWithHash(prfHashFunc(k.Suite.PRFID), key, data)
}

func (k *IKEKeys) PRFPlus(key, seed []byte, outLen int) []byte {
	return prfPlusWithHash(prfHashFunc(k.Suite.PRFID), key, seed, outLen)
}

func (k *IKEKeys) IntegHash() func() hash.Hash {
	if k.Suite.AuthID == AuthAlgHMACSHA196 {
		return sha1.New
	}
	return sha256.New
}

func (k *IKEKeys) ICVLen() int {
	if k.Suite.AuthID == AuthAlgHMACSHA196 {
		return 12
	}
	return 16
}

func DeriveIKEKeys(ni, nr, sharedSecret []byte, spii, spir uint64, aead bool) *IKEKeys {
	suite := DefaultIKESuite()
	suite.AEAD = aead
	return DeriveIKEKeysWithSuite(ni, nr, sharedSecret, spii, spir, suite)
}

func DeriveIKEKeysWithSuite(ni, nr, sharedSecret []byte, spii, spir uint64, suite IKESuite) *IKEKeys {
	if suite.EncKeyLen == 0 {
		suite.EncKeyLen = 16
	}
	if suite.PRFID == 0 {
		suite.PRFID = PRFAlgHMACSHA256
	}
	if suite.AuthID == 0 {
		suite.AuthID = AuthAlgHMACSHA256128
	}
	hFn := prfHashFunc(suite.PRFID)
	prfLen := 32
	if suite.PRFID == PRFAlgHMACSHA1 {
		prfLen = 20
	}
	authLen := 32
	if suite.AuthID == AuthAlgHMACSHA196 {
		authLen = 20
	}

	nonceKey := append(append([]byte(nil), ni...), nr...)
	skeyseed := prfWithHash(hFn, nonceKey, sharedSecret)

	var seed []byte
	seed = append(seed, ni...)
	seed = append(seed, nr...)
	var spis [16]byte
	binary.BigEndian.PutUint64(spis[0:8], spii)
	binary.BigEndian.PutUint64(spis[8:16], spir)
	seed = append(seed, spis[:]...)

	if suite.AEAD {
		encTotal := suite.EncKeyLen + 4
		km := prfPlusWithHash(hFn, skeyseed, seed, prfLen+encTotal*2+prfLen*2)
		off := 0
		skd := km[off : off+prfLen]; off += prfLen
		skei := km[off : off+encTotal]; off += encTotal
		sker := km[off : off+encTotal]; off += encTotal
		skpi := km[off : off+prfLen]; off += prfLen
		skpr := km[off : off+prfLen]
		return &IKEKeys{SKd: skd, SKei: skei, SKer: sker, SKpi: skpi, SKpr: skpr, AEAD: true, Suite: suite}
	}
	km := prfPlusWithHash(hFn, skeyseed, seed, prfLen+authLen*2+suite.EncKeyLen*2+prfLen*2)
	off := 0
	skd := km[off : off+prfLen]; off += prfLen
	skai := km[off : off+authLen]; off += authLen
	skar := km[off : off+authLen]; off += authLen
	skei := km[off : off+suite.EncKeyLen]; off += suite.EncKeyLen
	sker := km[off : off+suite.EncKeyLen]; off += suite.EncKeyLen
	skpi := km[off : off+prfLen]; off += prfLen
	skpr := km[off : off+prfLen]
	return &IKEKeys{
		SKd: skd, SKai: skai, SKar: skar, SKei: skei, SKer: sker, SKpi: skpi, SKpr: skpr,
		AEAD: false, Suite: suite,
	}
}

// DeriveChildKeymat derives RFC 7296 §2.17 KEYMAT = prf+(SK_d, Ni | Nr).
func DeriveChildKeymat(skd, ni, nr []byte, length int) []byte {
	seed := append(append([]byte(nil), ni...), nr...)
	return PRFPlusSHA256(skd, seed, length)
}

func (k *IKEKeys) DeriveChildKeymat(ni, nr []byte, length int) []byte {
	seed := append(append([]byte(nil), ni...), nr...)
	return k.PRFPlus(k.SKd, seed, length)
}

// ComputeAuthPayload computes RFC 7296 §2.15 AUTH = prf(prf(key, "Key Pad for IKEv2"), signedOctets).
func ComputeAuthPayload(key, initPacket, peerNonce, idPayloadBody, skp []byte) []byte {
	return ComputeAuthPayloadWithPRF(PRFAlgHMACSHA256, key, initPacket, peerNonce, idPayloadBody, skp)
}

func ComputeAuthPayloadWithPRF(prfID uint16, key, initPacket, peerNonce, idPayloadBody, skp []byte) []byte {
	hFn := prfHashFunc(prfID)
	signed := make([]byte, 0, len(initPacket)+len(peerNonce)+32)
	signed = append(signed, initPacket...)
	signed = append(signed, peerNonce...)
	signed = append(signed, prfWithHash(hFn, skp, idPayloadBody)...)
	inner := prfWithHash(hFn, key, []byte("Key Pad for IKEv2"))
	return prfWithHash(hFn, inner, signed)
}

// EAPAKAKeys holds keys derived per RFC 4187 (EAP-AKA) or RFC 5448 (EAP-AKA').
type EAPAKAKeys struct {
	Kencr []byte
	Kaut  []byte
	MSK   []byte
	EMSK  []byte
	Prime bool
}

// DeriveEAPAKAKeys derives RFC 4187 keys using FIPS 186-2 SHA-1 PRF.
func DeriveEAPAKAKeys(identity string, ik, ck []byte) *EAPAKAKeys {
	h := sha1.New()
	h.Write([]byte(identity))
	h.Write(ik)
	h.Write(ck)
	mk := h.Sum(nil) // 20 bytes
	out := fips1862PRF(mk, 160)
	return &EAPAKAKeys{
		Kencr: out[0:16],
		Kaut:  out[16:32],
		MSK:   out[32:96],
		EMSK:  out[96:160],
		Prime: false,
	}
}

// DeriveEAPAKAPrimeKeys derives RFC 5448 EAP-AKA' keys using HMAC-SHA256.
func DeriveEAPAKAPrimeKeys(identity, networkName string, ik, ck, autn []byte) *EAPAKAKeys {
	// Step 1: CK' || IK' = HMAC-SHA256(CK || IK, 0x20 || NetworkName || len(NetworkName) || SQN^AK || 0x06)
	k0 := append(append([]byte(nil), ck...), ik...)
	var s []byte
	s = append(s, 0x20)
	s = append(s, []byte(networkName)...)
	var nl [2]byte
	binary.BigEndian.PutUint16(nl[:], uint16(len(networkName)))
	s = append(s, nl[:]...)
	if len(autn) >= 6 {
		s = append(s, autn[:6]...)
	}
	s = append(s, 0x00, 0x06)
	ckikPrime := PRFSHA256(k0, s)
	ckPrime := ckikPrime[0:16]
	ikPrime := ckikPrime[16:32]

	// Step 2: MK = PRF'(IK' || CK', "EAP-AKA'" || Identity) -> 208 bytes
	k1 := append(append([]byte(nil), ikPrime...), ckPrime...)
	seed := append([]byte("EAP-AKA'"), []byte(identity)...)
	mk := PRFPlusSHA256(k1, seed, 208)
	return &EAPAKAKeys{
		Kencr: mk[0:16],
		Kaut:  mk[16:48], // 32 bytes for HMAC-SHA256-128
		MSK:   mk[80:144],
		EMSK:  mk[144:208],
		Prime: true,
	}
}

// ComputeEAPATMAC computes the 16-byte AT_MAC value over an EAP-AKA / EAP-AKA' packet (with MAC field zeroed).
func ComputeEAPATMAC(kaut, eapPacket []byte, prime bool) []byte {
	var newH func() hash.Hash = sha1.New
	if prime {
		newH = sha256.New
	}
	m := hmac.New(newH, kaut)
	m.Write(eapPacket)
	return m.Sum(nil)[:16]
}

// fips1862PRF implements RFC 4187 Appendix A / FIPS 186-2 SHA-1 pseudo-random function.
func fips1862PRF(xkey []byte, outLen int) []byte {
	// Pad xkey to 64 bytes per RFC 4187 §7
	state := make([]byte, 20)
	copy(state, xkey)
	out := make([]byte, 0, outLen)
	for len(out) < outLen {
		for j := 0; j < 2 && len(out) < outLen; j++ {
			w := sha1G(state)
			out = append(out, w...)
			// state = (1 + state + w) mod 2^160
			addMod160(state, w)
		}
	}
	return out[:outLen]
}

// sha1G computes single-block SHA-1 compression G(t, c) with 512-bit zero-padded input c.
func sha1G(c []byte) []byte {
	var block [64]byte
	copy(block[:], c)
	var w [80]uint32
	for i := 0; i < 16; i++ {
		w[i] = binary.BigEndian.Uint32(block[i*4 : i*4+4])
	}
	for i := 16; i < 80; i++ {
		v := w[i-3] ^ w[i-8] ^ w[i-14] ^ w[i-16]
		w[i] = (v << 1) | (v >> 31)
	}
	a := uint32(0x67452301)
	b := uint32(0xEFCDAB89)
	c2 := uint32(0x98BADCFE)
	d := uint32(0x10325476)
	e := uint32(0xC3D2E1F0)
	for i := 0; i < 80; i++ {
		var f, k uint32
		switch {
		case i < 20:
			f = (b & c2) | ((^b) & d)
			k = 0x5A827999
		case i < 40:
			f = b ^ c2 ^ d
			k = 0x6ED9EBA1
		case i < 60:
			f = (b & c2) | (b & d) | (c2 & d)
			k = 0x8F1BBCDC
		default:
			f = b ^ c2 ^ d
			k = 0xCA62C1D6
		}
		tmp := ((a << 5) | (a >> 27)) + f + e + k + w[i]
		e = d
		d = c2
		c2 = (b << 30) | (b >> 2)
		b = a
		a = tmp
	}
	var out [20]byte
	binary.BigEndian.PutUint32(out[0:4], a+0x67452301)
	binary.BigEndian.PutUint32(out[4:8], b+0xEFCDAB89)
	binary.BigEndian.PutUint32(out[8:12], c2+0x98BADCFE)
	binary.BigEndian.PutUint32(out[12:16], d+0x10325476)
	binary.BigEndian.PutUint32(out[16:20], e+0xC3D2E1F0)
	return out[:]
}

func addMod160(dst, add []byte) {
	carry := uint32(1)
	for i := 19; i >= 0; i-- {
		sum := uint32(dst[i]) + uint32(add[i]) + carry
		dst[i] = byte(sum)
		carry = sum >> 8
	}
}

func encryptIKEPayload(keys *IKEKeys, initiator bool, header28, plain []byte) ([]byte, error) {
	encKey := keys.SKei
	authKey := keys.SKai
	if !initiator {
		encKey = keys.SKer
		authKey = keys.SKar
	}
	if keys.AEAD {
		blk, err := aes.NewCipher(encKey[:16])
		if err != nil {
			return nil, err
		}
		aead, err := cipher.NewGCM(blk)
		if err != nil {
			return nil, err
		}
		padLen := (4 - ((len(plain) + 1) % 4)) % 4
		padded := make([]byte, len(plain)+padLen+1)
		copy(padded, plain)
		padded[len(padded)-1] = byte(padLen)

		iv := make([]byte, 8)
		_, _ = rand.Read(iv)
		var nonce [12]byte
		copy(nonce[0:4], encKey[16:20])
		copy(nonce[4:12], iv)

		// Update header length before computing AAD (IKE header + SK header 4B + IV 8B + ciphertext + tag 16B)
		totalLen := len(header28) + 4 + len(iv) + len(padded) + aead.Overhead()
		aad := make([]byte, len(header28)+4)
		copy(aad, header28)
		binary.BigEndian.PutUint32(aad[24:28], uint32(totalLen))
		// caller sets SK header 4 bytes right after header28 in aad[28:32]
		sealed := aead.Seal(nil, nonce[:], padded, aad)
		return append(iv, sealed...), nil
	}

	blk, err := aes.NewCipher(encKey)
	if err != nil {
		return nil, err
	}
	padLen := (aes.BlockSize - ((len(plain) + 1) % aes.BlockSize)) % aes.BlockSize
	padded := make([]byte, len(plain)+padLen+1)
	copy(padded, plain)
	padded[len(padded)-1] = byte(padLen)

	iv := make([]byte, aes.BlockSize)
	_, _ = rand.Read(iv)
	ct := make([]byte, len(padded))
	cipher.NewCBCEncrypter(blk, iv).CryptBlocks(ct, padded)

	// Append placeholder 16-byte HMAC-SHA256-128; MAC is computed over full packet minus ICV.
	body := make([]byte, len(iv)+len(ct)+16)
	copy(body[0:len(iv)], iv)
	copy(body[len(iv):len(iv)+len(ct)], ct)
	_ = authKey
	return body, nil
}

func verifyAndDecryptSK(keys *IKEKeys, fromInitiator bool, fullPacket []byte, skOffset int) ([]byte, error) {
	encKey := keys.SKer
	authKey := keys.SKar
	if fromInitiator {
		encKey = keys.SKei
		authKey = keys.SKai
	}
	skBody := fullPacket[skOffset+4:]
	if keys.AEAD {
		if len(skBody) < 8+16+1 {
			return nil, errors.New("IKE_SK_GCM_SHORT")
		}
		blk, err := aes.NewCipher(encKey[:16])
		if err != nil {
			return nil, err
		}
		aead, err := cipher.NewGCM(blk)
		if err != nil {
			return nil, err
		}
		var nonce [12]byte
		copy(nonce[0:4], encKey[16:20])
		copy(nonce[4:12], skBody[0:8])
		plain, err := aead.Open(nil, nonce[:], skBody[8:], fullPacket[:skOffset+4])
		if err != nil {
			return nil, errors.New("IKE_SK_GCM_AUTH_FAILED")
		}
		padLen := int(plain[len(plain)-1])
		if padLen+1 > len(plain) {
			return nil, errors.New("IKE_SK_PAD_INVALID")
		}
		return plain[:len(plain)-padLen-1], nil
	}

	icvLen := keys.ICVLen()
	if len(skBody) < aes.BlockSize+aes.BlockSize+icvLen {
		return nil, errors.New("IKE_SK_CBC_SHORT")
	}
	mac := hmac.New(keys.IntegHash(), authKey)
	mac.Write(fullPacket[:len(fullPacket)-icvLen])
	expected := mac.Sum(nil)[:icvLen]
	if subtle.ConstantTimeCompare(fullPacket[len(fullPacket)-icvLen:], expected) != 1 {
		return nil, errors.New("IKE_SK_HMAC_FAILED")
	}
	blk, err := aes.NewCipher(encKey)
	if err != nil {
		return nil, err
	}
	iv := skBody[:aes.BlockSize]
	ct := skBody[aes.BlockSize : len(skBody)-icvLen]
	if len(ct) == 0 || len(ct)%aes.BlockSize != 0 {
		return nil, errors.New("IKE_SK_CBC_ALIGN")
	}
	plain := make([]byte, len(ct))
	cipher.NewCBCDecrypter(blk, iv).CryptBlocks(plain, ct)
	padLen := int(plain[len(plain)-1])
	if padLen+1 > len(plain) {
		return nil, errors.New("IKE_SK_PAD_INVALID")
	}
	return plain[:len(plain)-padLen-1], nil
}

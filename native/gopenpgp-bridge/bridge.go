// Package bridge is the Android-facing façade over GopenPGP.
//
// WHY THIS EXISTS
//
// BouncyCastle's OpenPGP layer does not implement the RFC 9980 post-quantum algorithms (IDs 30-36):
// not in 1.80, and not on its main branch as of August 2026. So with BouncyCastle, Proton accounts
// that have opted into post-quantum protection cannot be read at all: their v6 key rings fail to PARSE.
//
// GopenPGP fixes that, but ONLY in the "-proton" builds. Plain gopenpgp v3.4.1 / go-crypto v1.4.1
// also stop at Ed448 (28) and have no PQC support whatsoever; a build against them fails on a real
// post-quantum key with "first packet was not a public/private key", which is what go-crypto
// returns after skipping a key packet whose algorithm it does not recognise. Only
// go-crypto v1.4.1-proton ships openpgp/mldsa_eddsa and openpgp/mlkem_ecdh. This was discovered
// the hard way: a post-quantum sign-in still failed after the migration. See pqc_test.go, which
// generates a real v6 ML-DSA key so the claim is tested rather than assumed.
//
// LICENSING (important)
//
// GopenPGP itself is MIT, which is why this module builds against it directly. Proton's prebuilt
// Android artefacts — me.proton.crypto:android-golib and me.proton.core:crypto-android — are
// GPLv3 and MUST NOT be used here: linking them would oblige the app to be GPLv3 too.
//
// DESIGN
//
// gomobile can only bind a restricted subset of Go types, so every exported signature here is
// flat: string, []byte, int, bool, error, or a bound struct pointer. This façade deliberately
// exposes CRYPTO PRIMITIVES ONLY. All Proton-specific orchestration — which key belongs to which
// address, calendar key maps, caching, key-ring lifetime — stays in Kotlin's KeyManager, which
// already works and is well covered by field use. Keeping that split means this file stays small
// enough to review as security-relevant code.
//
// SESSION KEYS AND THE SPLIT MESSAGE MODEL
//
// Proton does not store a PGP message as one blob. It splits it into a key packet (the PKESK,
// which wraps the session key to a recipient) and a data packet (the SEIPD, the actual ciphertext),
// and stores them in separate fields. Sharing an event with attendees means re-wrapping the SAME
// session key to each attendee's key, producing one key packet each while the data packet is
// written once. GopenPGP models this natively — BinaryKeyPacket/BinaryDataPacket, DecryptSessionKey
// and EncryptSessionKey — which is why this bridge exposes session keys as a first-class type
// rather than making Kotlin hand-roll RFC 6637 key agreement and RFC 3394 unwrapping as the
// BouncyCastle implementation had to.
package bridge

import (
	"errors"

	"github.com/ProtonMail/gopenpgp/v3/constants"
	"github.com/ProtonMail/gopenpgp/v3/crypto"
)

// Version reports the GopenPGP version this AAR was built against, so a running app can log
// exactly which crypto stack it is using and a field report is unambiguous.
//
// The "-proton" suffix is the load-bearing part: it is the difference between a build that can read
// post-quantum keys and one that cannot. Keep it in the string.
func Version() string {
	return "gopenpgp/v3 v3.4.1-proton (RFC 9980 PQC)"
}

// ---------------------------------------------------------------------------------------------
// Diagnostics
//
// These answer, on real data, the question the migration rests on: can GopenPGP read a key that
// BouncyCastle rejects? Kept exported so that claim can be verified in the field rather than
// taken on faith.
// ---------------------------------------------------------------------------------------------

// CanParseKey reports whether GopenPGP can read the given armored key. BouncyCastle throws
// "unknown signature key algorithm: 30" on a Proton post-quantum key, before any passphrase is
// involved.
func CanParseKey(armoredKey string) bool {
	_, err := crypto.NewKeyFromArmored(armoredKey)
	return err == nil
}

// KeyAlgorithm returns the OpenPGP public-key algorithm ID of an armored key, or -1 if unreadable.
// IDs 30-36 are the RFC 9980 post-quantum range.
func KeyAlgorithm(armoredKey string) int {
	key, err := crypto.NewKeyFromArmored(armoredKey)
	if err != nil {
		return -1
	}
	return int(key.GetEntity().PrimaryKey.PubKeyAlgo)
}

// KeyVersion returns the OpenPGP key version (4 or 6), or -1 if unreadable. Post-quantum
// algorithms require v6, so this distinguishes a PQC account at a glance.
func KeyVersion(armoredKey string) int {
	key, err := crypto.NewKeyFromArmored(armoredKey)
	if err != nil {
		return -1
	}
	return key.GetEntity().PrimaryKey.Version
}

// ---------------------------------------------------------------------------------------------
// Key ring
// ---------------------------------------------------------------------------------------------

// KeyRing is an opaque handle to one or more keys, bound to Java as a class.
//
// A ring may hold unlocked PRIVATE keys (for decryption and signing) or PUBLIC keys only (for
// encryption and signature verification); the two are not distinguished at this level because
// OpenPGP itself does not, and Proton's flows routinely mix them — encrypting to a calendar's
// public key while signing with the user's private one.
//
// Deliberately a handle rather than passing armored keys per call: the sync path decrypts
// thousands of items per run (measured: over 1000 calendar events on one account), and re-parsing
// and re-unlocking a key for each would be pointlessly slow. KeyManager caches its rings per account
// for the same reason.
type KeyRing struct {
	kr *crypto.KeyRing
}

// NewKeyRing unlocks an armored private key with the given passphrase and returns a handle.
//
// The passphrase arrives as a string because gomobile cannot bind Go's []byte to a Java char[]
// without copying anyway; callers should still clear their own copies.
func NewKeyRing(armoredPrivateKey string, passphrase string) (*KeyRing, error) {
	key, err := crypto.NewPrivateKeyFromArmored(armoredPrivateKey, []byte(passphrase))
	if err != nil {
		return nil, err
	}
	kr, err := crypto.NewKeyRing(key)
	if err != nil {
		return nil, err
	}
	return &KeyRing{kr: kr}, nil
}

// NewPublicKeyRing builds a ring from an armored PUBLIC key, for encrypting to a recipient or
// verifying their signature. Proton hands out attendee and calendar member keys this way.
func NewPublicKeyRing(armoredPublicKey string) (*KeyRing, error) {
	key, err := crypto.NewKeyFromArmored(armoredPublicKey)
	if err != nil {
		return nil, err
	}
	kr, err := crypto.NewKeyRing(key)
	if err != nil {
		return nil, err
	}
	return &KeyRing{kr: kr}, nil
}

// NewEmptyKeyRing returns a ring with no keys, to be filled with AddKey/AddPublicKey. Useful when
// the caller is looping over a recipient list and does not want to special-case the first one.
func NewEmptyKeyRing() *KeyRing {
	return &KeyRing{kr: &crypto.KeyRing{}}
}

// AddKey unlocks a further private key into this ring.
//
// Proton accounts routinely hold several keys, and with post-quantum protection enabled they hold
// TWO primary user keys (a v4 and a v6) — Proton's own clients pass the whole set when decrypting
// rather than choosing one. Supporting that is the point of this method.
func (k *KeyRing) AddKey(armoredPrivateKey string, passphrase string) error {
	if k.kr == nil {
		return errors.New("key ring is closed")
	}
	key, err := crypto.NewPrivateKeyFromArmored(armoredPrivateKey, []byte(passphrase))
	if err != nil {
		return err
	}
	return k.kr.AddKey(key)
}

// AddPublicKey adds an armored public key to this ring.
func (k *KeyRing) AddPublicKey(armoredPublicKey string) error {
	if k.kr == nil {
		return errors.New("key ring is closed")
	}
	key, err := crypto.NewKeyFromArmored(armoredPublicKey)
	if err != nil {
		return err
	}
	return k.kr.AddKey(key)
}

// CountKeys reports how many keys are in the ring, so the Kotlin side can log what it built.
func (k *KeyRing) CountKeys() int {
	if k.kr == nil {
		return 0
	}
	return k.kr.CountEntities()
}

// ArmoredPublicKey exports this ring's first key as an armored public key. Proton needs the
// public half when registering a calendar or addressing an attendee.
func (k *KeyRing) ArmoredPublicKey() (string, error) {
	if k.kr == nil {
		return "", errors.New("key ring is closed")
	}
	keys := k.kr.GetKeys()
	if len(keys) == 0 {
		return "", errors.New("key ring is empty")
	}
	return keys[0].GetArmoredPublicKey()
}

// Close releases the underlying key material. gomobile objects are garbage collected, but private
// keys should not wait for a finalizer.
func (k *KeyRing) Close() {
	if k.kr != nil {
		k.kr.ClearPrivateParams()
		k.kr = nil
	}
}

// ---------------------------------------------------------------------------------------------
// Decryption
// ---------------------------------------------------------------------------------------------

// DecryptArmored decrypts an ASCII-armored PGP message and returns the plaintext.
//
// Signature verification is deliberately NOT performed here. Proton's data model verifies
// signatures against separate address keys at a higher level, and conflating the two would change
// behaviour during a migration whose whole purpose is to change nothing observable.
func (k *KeyRing) DecryptArmored(armoredMessage string) (string, error) {
	if k.kr == nil {
		return "", errors.New("key ring is closed")
	}
	handle, err := crypto.PGP().Decryption().DecryptionKeys(k.kr).New()
	if err != nil {
		return "", err
	}
	result, err := handle.Decrypt([]byte(armoredMessage), crypto.Armor)
	if err != nil {
		return "", err
	}
	return string(result.Bytes()), nil
}

// DecryptBinary decrypts an unarmored PGP message: a key packet followed by a data packet.
//
// Proton splits messages into those two parts and stores them separately, so callers concatenate
// them before calling this. Kotlin's KeyManager.decryptBinary and decryptBinaryWithAllKeys pass the
// concatenated packets straight through to it.
func (k *KeyRing) DecryptBinary(combined []byte) (string, error) {
	if k.kr == nil {
		return "", errors.New("key ring is closed")
	}
	handle, err := crypto.PGP().Decryption().DecryptionKeys(k.kr).New()
	if err != nil {
		return "", err
	}
	result, err := handle.Decrypt(combined, crypto.Bytes)
	if err != nil {
		return "", err
	}
	return string(result.Bytes()), nil
}

// DecryptSplit decrypts a Proton-style split message, where the key packet and data packet were
// stored in separate API fields. Concatenating them is exactly what the wire format is.
func (k *KeyRing) DecryptSplit(keyPacket []byte, dataPacket []byte) (string, error) {
	combined := make([]byte, 0, len(keyPacket)+len(dataPacket))
	combined = append(combined, keyPacket...)
	combined = append(combined, dataPacket...)
	return k.DecryptBinary(combined)
}

// ---------------------------------------------------------------------------------------------
// Session keys
//
// This is the part BouncyCastle forced us to hand-roll: recovering a session key from a PKESK
// meant implementing RFC 6637 X25519 agreement, the KDF, and RFC 3394 AES unwrapping by hand
// against parsed packet bytes. GopenPGP does it natively, and correctly for v6/PQC packets too.
// ---------------------------------------------------------------------------------------------

// SessionKey is the symmetric key that actually encrypts a message body. Proton reuses one session
// key across all recipients of an event and wraps it separately for each.
type SessionKey struct {
	sk *crypto.SessionKey
}

// GenerateSessionKey produces a fresh AES-256 session key for a new message.
func GenerateSessionKey() (*SessionKey, error) {
	sk, err := crypto.GenerateSessionKeyAlgo(constants.AES256)
	if err != nil {
		return nil, err
	}
	return &SessionKey{sk: sk}, nil
}

// NewSessionKeyFromBytes rebuilds a session key from raw bytes and an OpenPGP symmetric algorithm
// ID (7=AES-128, 8=AES-192, 9=AES-256). Proton's API hands these around as integers, and the
// existing Kotlin code already threads that ID through, so the bridge speaks the same dialect
// rather than forcing a translation at every call site.
func NewSessionKeyFromBytes(key []byte, algorithmID int) (*SessionKey, error) {
	algo, err := algoName(algorithmID)
	if err != nil {
		return nil, err
	}
	return &SessionKey{sk: crypto.NewSessionKeyFromToken(key, algo)}, nil
}

// Bytes returns the raw session key material.
func (s *SessionKey) Bytes() []byte {
	if s.sk == nil {
		return nil
	}
	return s.sk.Key
}

// AlgorithmID returns the OpenPGP symmetric algorithm ID (9 = AES-256), matching what Proton's
// API and the existing Kotlin call sites use.
func (s *SessionKey) AlgorithmID() (int, error) {
	if s.sk == nil {
		return 0, errors.New("session key is empty")
	}
	c, err := s.sk.GetCipherFuncInt()
	if err != nil {
		return 0, err
	}
	return int(c), nil
}

// DecryptSessionKey recovers the session key from a Proton SharedKeyPacket (a PKESK), trying every
// key in the ring. This replaces a hand-written packet parser plus RFC 6637/3394 implementation.
func (k *KeyRing) DecryptSessionKey(keyPacket []byte) (*SessionKey, error) {
	if k.kr == nil {
		return nil, errors.New("key ring is closed")
	}
	handle, err := crypto.PGP().Decryption().DecryptionKeys(k.kr).New()
	if err != nil {
		return nil, err
	}
	sk, err := handle.DecryptSessionKey(keyPacket)
	if err != nil {
		return nil, err
	}
	return &SessionKey{sk: sk}, nil
}

// EncryptSessionKey wraps an existing session key to this ring's keys, producing a key packet.
//
// This is how an event is shared: the data packet is written once, and the same session key is
// wrapped once per attendee. Caching the POST-rewrap key packet rather than the pre-rewrap one
// matters — see KeyManager, which learned that the hard way.
func (k *KeyRing) EncryptSessionKey(sessionKey *SessionKey) ([]byte, error) {
	if k.kr == nil {
		return nil, errors.New("key ring is closed")
	}
	if sessionKey == nil || sessionKey.sk == nil {
		return nil, errors.New("session key is empty")
	}
	handle, err := crypto.PGP().Encryption().Recipients(k.kr).New()
	if err != nil {
		return nil, err
	}
	return handle.EncryptSessionKey(sessionKey.sk)
}

// EncryptDataWithSessionKey encrypts plaintext under an existing session key, returning the raw
// data packet with no key packet attached — the half Proton stores once and shares.
func EncryptDataWithSessionKey(sessionKey *SessionKey, plaintext string) ([]byte, error) {
	return encryptWithSessionKey(sessionKey, plaintext, nil)
}

// EncryptDataWithSessionKeyAndSign is EncryptDataWithSessionKey with an inline signature from the
// signing ring, for the flows where Proton requires the body to be signed inside the ciphertext.
func EncryptDataWithSessionKeyAndSign(sessionKey *SessionKey, plaintext string, signingRing *KeyRing) ([]byte, error) {
	if signingRing == nil || signingRing.kr == nil {
		return nil, errors.New("signing key ring is closed")
	}
	return encryptWithSessionKey(sessionKey, plaintext, signingRing.kr)
}

func encryptWithSessionKey(sessionKey *SessionKey, plaintext string, signer *crypto.KeyRing) ([]byte, error) {
	if sessionKey == nil || sessionKey.sk == nil {
		return nil, errors.New("session key is empty")
	}
	builder := crypto.PGP().Encryption().SessionKey(sessionKey.sk)
	if signer != nil {
		builder = builder.SigningKeys(signer)
	}
	handle, err := builder.New()
	if err != nil {
		return nil, err
	}
	msg, err := handle.Encrypt([]byte(plaintext))
	if err != nil {
		return nil, err
	}
	// SessionKey-mode encryption emits no key packet, so the message body IS the data packet.
	return msg.BinaryDataPacket(), nil
}

// DecryptDataWithSessionKey decrypts a data packet using a known session key, without needing any
// private key at all. Proton uses this once a session key has been recovered or shared.
func DecryptDataWithSessionKey(sessionKey *SessionKey, dataPacket []byte) (string, error) {
	if sessionKey == nil || sessionKey.sk == nil {
		return "", errors.New("session key is empty")
	}
	handle, err := crypto.PGP().Decryption().SessionKey(sessionKey.sk).New()
	if err != nil {
		return "", err
	}
	result, err := handle.Decrypt(dataPacket, crypto.Bytes)
	if err != nil {
		return "", err
	}
	return string(result.Bytes()), nil
}

// ---------------------------------------------------------------------------------------------
// Encryption
// ---------------------------------------------------------------------------------------------

// EncryptedMessage carries the two halves of a PGP message separately, because that is how Proton
// stores and transmits them. gomobile cannot return two byte slices from one call, so this is a
// bound handle rather than a tuple.
type EncryptedMessage struct {
	msg *crypto.PGPMessage
}

// KeyPacket returns the PKESK half (the session key wrapped to the recipients).
func (m *EncryptedMessage) KeyPacket() []byte { return m.msg.BinaryKeyPacket() }

// DataPacket returns the SEIPD half (the ciphertext body).
func (m *EncryptedMessage) DataPacket() []byte { return m.msg.BinaryDataPacket() }

// Armored returns the whole message as one ASCII-armored blob, for the fields Proton stores that
// way rather than split.
func (m *EncryptedMessage) Armored() (string, error) { return m.msg.Armor() }

// Bytes returns the whole message unarmored.
func (m *EncryptedMessage) Bytes() []byte { return m.msg.Bytes() }

// ParseArmoredMessage reads an existing armored PGP message so its two halves can be taken apart.
//
// Proton stores some fields armored and others pre-split, and the upload path has to convert
// between the two. The BouncyCastle implementation did this by walking packet headers by hand and
// computing tag/length boundaries — correct, but a parser we had no business maintaining.
func ParseArmoredMessage(armoredMessage string) (*EncryptedMessage, error) {
	msg, err := crypto.NewPGPMessageFromArmored(armoredMessage)
	if err != nil {
		return nil, err
	}
	return &EncryptedMessage{msg: msg}, nil
}

// ParseBinaryMessage is ParseArmoredMessage for an unarmored message.
func ParseBinaryMessage(data []byte) *EncryptedMessage {
	return &EncryptedMessage{msg: crypto.NewPGPMessage(data)}
}

// NewSplitMessage reassembles a message from the two halves Proton stores separately.
func NewSplitMessage(keyPacket []byte, dataPacket []byte) *EncryptedMessage {
	return &EncryptedMessage{msg: crypto.NewPGPSplitMessage(keyPacket, dataPacket)}
}

// Encrypt encrypts plaintext to this ring's keys.
func (k *KeyRing) Encrypt(plaintext string) (*EncryptedMessage, error) {
	return k.encrypt(plaintext, nil)
}

// EncryptAndSign encrypts plaintext to this ring's keys and signs it with the signing ring.
func (k *KeyRing) EncryptAndSign(plaintext string, signingRing *KeyRing) (*EncryptedMessage, error) {
	if signingRing == nil || signingRing.kr == nil {
		return nil, errors.New("signing key ring is closed")
	}
	return k.encrypt(plaintext, signingRing.kr)
}

func (k *KeyRing) encrypt(plaintext string, signer *crypto.KeyRing) (*EncryptedMessage, error) {
	if k.kr == nil {
		return nil, errors.New("key ring is closed")
	}
	builder := crypto.PGP().Encryption().Recipients(k.kr)
	if signer != nil {
		builder = builder.SigningKeys(signer)
	}
	handle, err := builder.New()
	if err != nil {
		return nil, err
	}
	msg, err := handle.Encrypt([]byte(plaintext))
	if err != nil {
		return nil, err
	}
	return &EncryptedMessage{msg: msg}, nil
}

// ---------------------------------------------------------------------------------------------
// Password-based (symmetric) encryption
//
// Used for the local backup archives, which are encrypted with a passphrase the user chooses
// rather than with any Proton key. Output is a standard OpenPGP SKESK message, so the .gpg files
// stay readable by gpg itself, and archives written by the previous BouncyCastle implementation
// stay readable here.
// ---------------------------------------------------------------------------------------------

// EncryptWithPassword encrypts bytes under a passphrase and returns an ASCII-armored message.
func EncryptWithPassword(data []byte, password string) (string, error) {
	handle, err := crypto.PGP().Encryption().Password([]byte(password)).New()
	if err != nil {
		return "", err
	}
	msg, err := handle.Encrypt(data)
	if err != nil {
		return "", err
	}
	return msg.Armor()
}

// DecryptWithPassword decrypts a passphrase-encrypted message, accepting either armored or binary
// input. Which one a given archive is depends on how it was written, and the caller should not
// have to care.
func DecryptWithPassword(data []byte, password string) ([]byte, error) {
	handle, err := crypto.PGP().Decryption().Password([]byte(password)).New()
	if err != nil {
		return nil, err
	}
	if result, err := handle.Decrypt(data, crypto.Armor); err == nil {
		return result.Bytes(), nil
	}
	result, err := handle.Decrypt(data, crypto.Bytes)
	if err != nil {
		return nil, err
	}
	return result.Bytes(), nil
}

// ---------------------------------------------------------------------------------------------
// Signing and verification
// ---------------------------------------------------------------------------------------------

// SignDetachedArmored produces an armored detached signature over text.
func (k *KeyRing) SignDetachedArmored(data string) (string, error) {
	sig, err := k.signDetached([]byte(data), crypto.Armor)
	if err != nil {
		return "", err
	}
	return string(sig), nil
}

// SignDetachedBinary produces an armored detached signature over arbitrary bytes. Proton signs
// binary attachment data this way.
func (k *KeyRing) SignDetachedBinary(data []byte) (string, error) {
	sig, err := k.signDetached(data, crypto.Armor)
	if err != nil {
		return "", err
	}
	return string(sig), nil
}

// SignDetachedRaw produces an UNARMORED detached signature over bytes.
//
// Proton's attachment upload expects the raw signature packet rather than armor, which is why this
// exists alongside the armored variants.
func (k *KeyRing) SignDetachedRaw(data []byte) ([]byte, error) {
	return k.signDetached(data, crypto.Bytes)
}

func (k *KeyRing) signDetached(data []byte, encoding int8) ([]byte, error) {
	if k.kr == nil {
		return nil, errors.New("key ring is closed")
	}
	handle, err := crypto.PGP().Sign().SigningKeys(k.kr).Detached().New()
	if err != nil {
		return nil, err
	}
	return handle.Sign(data, encoding)
}

// VerifyDetachedArmored reports whether an armored detached signature over data is valid under
// this ring.
//
// Returns a bool rather than an error because every existing call site treats an unverifiable
// signature as "unverified" and carries on, rather than aborting the sync — a stricter contract
// here would change behaviour that field use has already settled.
func (k *KeyRing) VerifyDetachedArmored(data string, armoredSignature string) bool {
	if k.kr == nil {
		return false
	}
	handle, err := crypto.PGP().Verify().VerificationKeys(k.kr).New()
	if err != nil {
		return false
	}
	result, err := handle.VerifyDetached([]byte(data), []byte(armoredSignature), crypto.Armor)
	if err != nil {
		return false
	}
	return result.SignatureError() == nil
}

// ---------------------------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------------------------

// algoName maps an OpenPGP symmetric algorithm ID to the name GopenPGP expects. Only the AES
// family is listed: Proton uses AES-256 throughout, and silently accepting a legacy cipher such as
// CAST5 or 3DES here would be a downgrade this bridge should refuse rather than enable.
func algoName(algorithmID int) (string, error) {
	switch algorithmID {
	case 7:
		return constants.AES128, nil
	case 8:
		return "aes192", nil
	case 9:
		return constants.AES256, nil
	default:
		return "", errors.New("unsupported symmetric algorithm ID (only AES is accepted)")
	}
}

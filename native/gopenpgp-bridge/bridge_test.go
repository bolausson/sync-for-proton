// Round-trip tests for the bridge façade.
//
// These exist because the Kotlin side is about to depend on every primitive here, and a Go test
// runs in under a second where a device round trip costs several minutes. They deliberately test
// THROUGH the exported bridge API rather than against GopenPGP directly: the thing that can break
// is this façade's own wiring — a builder configured wrong, a key packet returned where a data
// packet was meant — not the library underneath.
//
// The Proton-shaped flows (split messages, session key reuse across recipients) are covered
// explicitly, because those are the ones the BouncyCastle implementation had to hand-roll and
// therefore the ones where a behavioural difference would actually bite.
package bridge

import (
	"bytes"
	"testing"

	"github.com/ProtonMail/gopenpgp/v3/crypto"
)

const testPassphrase = "correct horse battery staple"

// newTestKey mints a locked armored private key, standing in for a Proton user or calendar key.
func newTestKey(t *testing.T, name string) string {
	t.Helper()
	key, err := crypto.PGP().KeyGeneration().AddUserId(name, name+"@example.test").New().GenerateKey()
	if err != nil {
		t.Fatalf("generate key: %v", err)
	}
	locked, err := crypto.PGP().LockKey(key, []byte(testPassphrase))
	if err != nil {
		t.Fatalf("lock key: %v", err)
	}
	armored, err := locked.Armor()
	if err != nil {
		t.Fatalf("armor key: %v", err)
	}
	return armored
}

func newTestRing(t *testing.T, armoredKey string) *KeyRing {
	t.Helper()
	ring, err := NewKeyRing(armoredKey, testPassphrase)
	if err != nil {
		t.Fatalf("new key ring: %v", err)
	}
	return ring
}

func TestKeyMetadata(t *testing.T) {
	armored := newTestKey(t, "meta")
	if !CanParseKey(armored) {
		t.Error("CanParseKey said no on a key we just generated")
	}
	if CanParseKey("not a pgp key at all") {
		t.Error("CanParseKey said yes on garbage")
	}
	if got := KeyAlgorithm(armored); got < 0 {
		t.Errorf("KeyAlgorithm = %d, want a real algorithm ID", got)
	}
	if got := KeyVersion(armored); got != 4 && got != 6 {
		t.Errorf("KeyVersion = %d, want 4 or 6", got)
	}
	// Garbage must report -1 rather than panicking: KeyManager calls these while diagnosing a key
	// it already knows is unreadable.
	if got := KeyAlgorithm("garbage"); got != -1 {
		t.Errorf("KeyAlgorithm(garbage) = %d, want -1", got)
	}
}

func TestArmoredRoundTrip(t *testing.T) {
	ring := newTestRing(t, newTestKey(t, "armored"))
	defer ring.Close()

	const plaintext = "BEGIN:VCALENDAR\r\nSUMMARY:Zahnarzt\r\nEND:VCALENDAR"
	msg, err := ring.Encrypt(plaintext)
	if err != nil {
		t.Fatalf("encrypt: %v", err)
	}
	armored, err := msg.Armored()
	if err != nil {
		t.Fatalf("armor: %v", err)
	}
	got, err := ring.DecryptArmored(armored)
	if err != nil {
		t.Fatalf("decrypt armored: %v", err)
	}
	if got != plaintext {
		t.Errorf("round trip changed the text:\n got %q\nwant %q", got, plaintext)
	}
}

// TestSplitRoundTrip covers Proton's storage model directly: the two packets travel in separate
// API fields, so the bridge must be able to take them back apart and put them back together.
func TestSplitRoundTrip(t *testing.T) {
	ring := newTestRing(t, newTestKey(t, "split"))
	defer ring.Close()

	const plaintext = "split message body"
	msg, err := ring.Encrypt(plaintext)
	if err != nil {
		t.Fatalf("encrypt: %v", err)
	}
	keyPacket, dataPacket := msg.KeyPacket(), msg.DataPacket()
	if len(keyPacket) == 0 {
		t.Fatal("key packet is empty; Proton stores this as SharedKeyPacket")
	}
	if len(dataPacket) == 0 {
		t.Fatal("data packet is empty")
	}
	got, err := ring.DecryptSplit(keyPacket, dataPacket)
	if err != nil {
		t.Fatalf("decrypt split: %v", err)
	}
	if got != plaintext {
		t.Errorf("split round trip = %q, want %q", got, plaintext)
	}
}

func TestEncryptAndSignThenDecrypt(t *testing.T) {
	recipient := newTestRing(t, newTestKey(t, "recipient"))
	defer recipient.Close()
	signer := newTestRing(t, newTestKey(t, "signer"))
	defer signer.Close()

	const plaintext = "signed and encrypted"
	msg, err := recipient.EncryptAndSign(plaintext, signer)
	if err != nil {
		t.Fatalf("encrypt and sign: %v", err)
	}
	got, err := recipient.DecryptSplit(msg.KeyPacket(), msg.DataPacket())
	if err != nil {
		t.Fatalf("decrypt: %v", err)
	}
	if got != plaintext {
		t.Errorf("= %q, want %q", got, plaintext)
	}
}

// TestSessionKeyReuseAcrossRecipients is the attendee-sharing flow: ONE data packet, one key
// packet per recipient, all unwrapping to the same session key. This is what the BouncyCastle
// implementation hand-rolled with RFC 6637 agreement and RFC 3394 unwrapping.
func TestSessionKeyReuseAcrossRecipients(t *testing.T) {
	organiser := newTestRing(t, newTestKey(t, "organiser"))
	defer organiser.Close()
	attendee := newTestRing(t, newTestKey(t, "attendee"))
	defer attendee.Close()

	sessionKey, err := GenerateSessionKey()
	if err != nil {
		t.Fatalf("generate session key: %v", err)
	}
	algoID, err := sessionKey.AlgorithmID()
	if err != nil {
		t.Fatalf("algorithm id: %v", err)
	}
	if algoID != 9 {
		t.Errorf("session key algorithm = %d, want 9 (AES-256)", algoID)
	}

	const plaintext = "BEGIN:VEVENT\r\nSUMMARY:Team sync\r\nEND:VEVENT"
	dataPacket, err := EncryptDataWithSessionKey(sessionKey, plaintext)
	if err != nil {
		t.Fatalf("encrypt with session key: %v", err)
	}

	// The same session key wrapped separately for each party.
	for name, ring := range map[string]*KeyRing{"organiser": organiser, "attendee": attendee} {
		keyPacket, err := ring.EncryptSessionKey(sessionKey)
		if err != nil {
			t.Fatalf("%s: wrap session key: %v", name, err)
		}
		recovered, err := ring.DecryptSessionKey(keyPacket)
		if err != nil {
			t.Fatalf("%s: recover session key: %v", name, err)
		}
		if !bytes.Equal(recovered.Bytes(), sessionKey.Bytes()) {
			t.Errorf("%s: recovered session key differs from the original", name)
		}
		// And the shared data packet must open with it.
		got, err := DecryptDataWithSessionKey(recovered, dataPacket)
		if err != nil {
			t.Fatalf("%s: decrypt data packet: %v", name, err)
		}
		if got != plaintext {
			t.Errorf("%s: = %q, want %q", name, got, plaintext)
		}
	}
}

func TestSessionKeyFromBytes(t *testing.T) {
	sessionKey, err := GenerateSessionKey()
	if err != nil {
		t.Fatalf("generate: %v", err)
	}
	// Proton hands the raw key and an algorithm ID around; rebuilding from those must give a key
	// that still opens the same data.
	const plaintext = "rebuilt session key"
	dataPacket, err := EncryptDataWithSessionKey(sessionKey, plaintext)
	if err != nil {
		t.Fatalf("encrypt: %v", err)
	}
	rebuilt, err := NewSessionKeyFromBytes(sessionKey.Bytes(), 9)
	if err != nil {
		t.Fatalf("rebuild: %v", err)
	}
	got, err := DecryptDataWithSessionKey(rebuilt, dataPacket)
	if err != nil {
		t.Fatalf("decrypt with rebuilt key: %v", err)
	}
	if got != plaintext {
		t.Errorf("= %q, want %q", got, plaintext)
	}

	// A legacy cipher must be refused rather than quietly downgraded.
	if _, err := NewSessionKeyFromBytes(sessionKey.Bytes(), 3); err == nil {
		t.Error("NewSessionKeyFromBytes accepted CAST5; it should refuse non-AES")
	}
}

func TestSessionKeySignedData(t *testing.T) {
	signer := newTestRing(t, newTestKey(t, "sk-signer"))
	defer signer.Close()

	sessionKey, err := GenerateSessionKey()
	if err != nil {
		t.Fatalf("generate: %v", err)
	}
	const plaintext = "signed under a session key"
	dataPacket, err := EncryptDataWithSessionKeyAndSign(sessionKey, plaintext, signer)
	if err != nil {
		t.Fatalf("encrypt and sign with session key: %v", err)
	}
	got, err := DecryptDataWithSessionKey(sessionKey, dataPacket)
	if err != nil {
		t.Fatalf("decrypt: %v", err)
	}
	if got != plaintext {
		t.Errorf("= %q, want %q", got, plaintext)
	}
}

func TestDetachedSignatureRoundTrip(t *testing.T) {
	armored := newTestKey(t, "sig")
	signing := newTestRing(t, armored)
	defer signing.Close()

	const data = "BEGIN:VCARD\r\nFN:Alice Example\r\nEND:VCARD"
	sig, err := signing.SignDetachedArmored(data)
	if err != nil {
		t.Fatalf("sign: %v", err)
	}

	// Verification happens against the PUBLIC key, which is how Proton actually verifies: the
	// signer's public key arrives from the API, not from local private material.
	pub, err := signing.ArmoredPublicKey()
	if err != nil {
		t.Fatalf("export public key: %v", err)
	}
	verifying, err := NewPublicKeyRing(pub)
	if err != nil {
		t.Fatalf("public ring: %v", err)
	}
	defer verifying.Close()

	if !verifying.VerifyDetachedArmored(data, sig) {
		t.Error("valid signature did not verify")
	}
	if verifying.VerifyDetachedArmored("tampered "+data, sig) {
		t.Error("signature verified against tampered data")
	}

	// A signature from an unrelated key must not verify.
	other := newTestRing(t, newTestKey(t, "other"))
	defer other.Close()
	otherSig, err := other.SignDetachedArmored(data)
	if err != nil {
		t.Fatalf("sign with other key: %v", err)
	}
	if verifying.VerifyDetachedArmored(data, otherSig) {
		t.Error("signature from an unrelated key verified")
	}
}

func TestBinaryDetachedSignature(t *testing.T) {
	signing := newTestRing(t, newTestKey(t, "binsig"))
	defer signing.Close()

	data := []byte{0x00, 0x01, 0xFF, 0xFE, 0x7F}
	sig, err := signing.SignDetachedBinary(data)
	if err != nil {
		t.Fatalf("sign binary: %v", err)
	}
	if sig == "" {
		t.Error("empty signature")
	}
}

func TestMultiKeyRingRoutesByKeyPacket(t *testing.T) {
	// The post-quantum case in miniature: a ring holding several keys must decrypt a message
	// addressed to any one of them, without the caller saying which. That routing is exactly what
	// lets a v4+v6 account work.
	first := newTestKey(t, "first")
	second := newTestKey(t, "second")

	ring := newTestRing(t, first)
	defer ring.Close()
	if err := ring.AddKey(second, testPassphrase); err != nil {
		t.Fatalf("add second key: %v", err)
	}
	if got := ring.CountKeys(); got != 2 {
		t.Fatalf("CountKeys = %d, want 2", got)
	}

	// Encrypt to the SECOND key only, then decrypt with the whole ring.
	only := newTestRing(t, second)
	defer only.Close()
	msg, err := only.Encrypt("addressed to the second key")
	if err != nil {
		t.Fatalf("encrypt: %v", err)
	}
	got, err := ring.DecryptSplit(msg.KeyPacket(), msg.DataPacket())
	if err != nil {
		t.Fatalf("multi-key ring failed to route: %v", err)
	}
	if got != "addressed to the second key" {
		t.Errorf("= %q", got)
	}
}

func TestClosedRingIsSafe(t *testing.T) {
	// KeyManager clears rings on sign-out while a sync may still be in flight, so every entry
	// point must fail cleanly rather than panic on a closed ring.
	ring := newTestRing(t, newTestKey(t, "closed"))
	ring.Close()

	if _, err := ring.DecryptArmored("whatever"); err == nil {
		t.Error("DecryptArmored on a closed ring should error")
	}
	if _, err := ring.Encrypt("whatever"); err == nil {
		t.Error("Encrypt on a closed ring should error")
	}
	if _, err := ring.SignDetachedArmored("whatever"); err == nil {
		t.Error("SignDetachedArmored on a closed ring should error")
	}
	if _, err := ring.DecryptSessionKey([]byte{0x01}); err == nil {
		t.Error("DecryptSessionKey on a closed ring should error")
	}
	if ring.VerifyDetachedArmored("data", "sig") {
		t.Error("VerifyDetachedArmored on a closed ring should be false")
	}
	if got := ring.CountKeys(); got != 0 {
		t.Errorf("CountKeys on a closed ring = %d, want 0", got)
	}
}

func TestParseAndSplitRoundTrip(t *testing.T) {
	// The upload path converts between armored and split forms; both directions must survive.
	ring := newTestRing(t, newTestKey(t, "parse"))
	defer ring.Close()

	const plaintext = "armored then split then armored"
	msg, err := ring.Encrypt(plaintext)
	if err != nil {
		t.Fatalf("encrypt: %v", err)
	}
	armored, err := msg.Armored()
	if err != nil {
		t.Fatalf("armor: %v", err)
	}

	parsed, err := ParseArmoredMessage(armored)
	if err != nil {
		t.Fatalf("parse armored: %v", err)
	}
	kp, dp := parsed.KeyPacket(), parsed.DataPacket()
	if len(kp) == 0 || len(dp) == 0 {
		t.Fatalf("split produced empty halves: key=%d data=%d", len(kp), len(dp))
	}

	// Reassemble from the halves and decrypt.
	rejoined := NewSplitMessage(kp, dp)
	got, err := ring.DecryptBinary(rejoined.Bytes())
	if err != nil {
		t.Fatalf("decrypt rejoined: %v", err)
	}
	if got != plaintext {
		t.Errorf("= %q, want %q", got, plaintext)
	}

	if _, err := ParseArmoredMessage("not armored at all"); err == nil {
		t.Error("ParseArmoredMessage accepted garbage")
	}
}

func TestRawDetachedSignature(t *testing.T) {
	// Proton's attachment upload wants the raw packet, not armor. Both forms must verify.
	signing := newTestRing(t, newTestKey(t, "rawsig"))
	defer signing.Close()

	data := []byte("attachment bytes")
	raw, err := signing.SignDetachedRaw(data)
	if err != nil {
		t.Fatalf("sign raw: %v", err)
	}
	if len(raw) == 0 {
		t.Fatal("empty raw signature")
	}
	// Raw must NOT be armored.
	if bytes.HasPrefix(raw, []byte("-----BEGIN")) {
		t.Error("SignDetachedRaw returned armored output")
	}
}

func TestPasswordRoundTrip(t *testing.T) {
	// Local backup archives: passphrase-encrypted, armored, binary payload (a ZIP).
	payload := []byte{0x50, 0x4B, 0x03, 0x04, 0x00, 0xFF, 0x7F}
	const pw = "backup passphrase"

	armored, err := EncryptWithPassword(payload, pw)
	if err != nil {
		t.Fatalf("encrypt: %v", err)
	}
	if !bytes.HasPrefix([]byte(armored), []byte("-----BEGIN PGP MESSAGE")) {
		t.Errorf("expected an armored PGP message, got %.40q", armored)
	}

	got, err := DecryptWithPassword([]byte(armored), pw)
	if err != nil {
		t.Fatalf("decrypt: %v", err)
	}
	if !bytes.Equal(got, payload) {
		t.Errorf("payload changed: got %x want %x", got, payload)
	}

	// Wrong passphrase must fail rather than return garbage.
	if _, err := DecryptWithPassword([]byte(armored), "wrong"); err == nil {
		t.Error("decrypted with the wrong passphrase")
	}
}

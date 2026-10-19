// Post-quantum tests.
//
// These exist because the whole migration was justified by "GopenPGP supports the RFC 9980
// post-quantum algorithms", and that claim was originally taken on faith. It was FALSE for the
// version first pinned: plain go-crypto v1.4.1 stops at Ed448 (28), exactly like BouncyCastle, and
// a real post-quantum key still failed to load. Only the "-proton" builds carry
// mldsa_eddsa/ and mlkem_ecdh/.
//
// So these tests generate a genuine PQC v6 key and drive it through the bridge. If the pins are
// ever bumped to a non-proton version, this fails loudly instead of shipping a build that cannot
// read the keys it exists to read.
package bridge

import (
	"testing"

	"github.com/ProtonMail/go-crypto/openpgp/packet"
	"github.com/ProtonMail/gopenpgp/v3/crypto"
	"github.com/ProtonMail/gopenpgp/v3/profile"
)

// newPqcKey mints a locked armored PQC (v6) private key, standing in for a Proton account that has
// opted into post-quantum protection.
func newPqcKey(t *testing.T, name string) string {
	t.Helper()
	pgp := crypto.PGPWithProfile(profile.PQC())
	key, err := pgp.KeyGeneration().AddUserId(name, name+"@example.test").New().GenerateKey()
	if err != nil {
		t.Fatalf("generate PQC key: %v", err)
	}
	locked, err := pgp.LockKey(key, []byte(testPassphrase))
	if err != nil {
		t.Fatalf("lock PQC key: %v", err)
	}
	armored, err := locked.Armor()
	if err != nil {
		t.Fatalf("armor PQC key: %v", err)
	}
	return armored
}

// TestPqcAlgorithmIDsAreCompiledIn guards the pin itself. Plain (non-proton) go-crypto does not
// define these, and swapping to it would silently reintroduce the exact bug this migration fixed.
func TestPqcAlgorithmIDsAreCompiledIn(t *testing.T) {
	cases := []struct {
		name string
		algo int
	}{
		{"ML-DSA-65 + Ed25519", packet.PubKeyAlgoMldsa65Ed25519},
		{"ML-DSA-87 + Ed448", packet.PubKeyAlgoMldsa87Ed448},
		{"ML-KEM-768 + X25519", packet.PubKeyAlgoMlkem768X25519},
		{"ML-KEM-1024 + X448", packet.PubKeyAlgoMlkem1024X448},
	}
	want := map[string]int{
		"ML-DSA-65 + Ed25519": 30,
		"ML-DSA-87 + Ed448":   31,
		"ML-KEM-768 + X25519": 35,
		"ML-KEM-1024 + X448":  36,
	}
	for _, c := range cases {
		if c.algo != want[c.name] {
			t.Errorf("%s = %d, want %d", c.name, c.algo, want[c.name])
		}
	}
}

// TestPqcKeyIsReadable is the test that would have caught the original mistake: a real PQC key
// failed to PARSE, before any passphrase was involved.
func TestPqcKeyIsReadable(t *testing.T) {
	armored := newPqcKey(t, "pqc")

	if !CanParseKey(armored) {
		t.Fatal("CanParseKey rejected a post-quantum key: the pinned go-crypto has no RFC 9980 support")
	}
	if got := KeyVersion(armored); got != 6 {
		t.Errorf("KeyVersion = %d, want 6 (post-quantum requires v6 keys)", got)
	}
	// 30 is ML-DSA-65 + Ed25519, the algorithm seen in the field.
	if got := KeyAlgorithm(armored); got != 30 && got != 31 {
		t.Errorf("KeyAlgorithm = %d, want 30 or 31 (ML-DSA)", got)
	}
}

// TestPqcRingUnlockAndRoundTrip drives the actual failing path: build a ring from a PQC private key
// and use it. The reported failure was "first packet was not a public/private key" out of
// NewKeyRing, which is what go-crypto returns when it skips a key packet whose algorithm it does
// not know.
func TestPqcRingUnlockAndRoundTrip(t *testing.T) {
	ring, err := NewKeyRing(newPqcKey(t, "pqcring"), testPassphrase)
	if err != nil {
		t.Fatalf("NewKeyRing on a post-quantum key: %v", err)
	}
	defer ring.Close()
	if got := ring.CountKeys(); got != 1 {
		t.Fatalf("CountKeys = %d, want 1", got)
	}

	const plaintext = "BEGIN:VEVENT\r\nSUMMARY:post-quantum\r\nEND:VEVENT"
	msg, err := ring.Encrypt(plaintext)
	if err != nil {
		t.Fatalf("encrypt to a post-quantum key: %v", err)
	}
	got, err := ring.DecryptSplit(msg.KeyPacket(), msg.DataPacket())
	if err != nil {
		t.Fatalf("decrypt with a post-quantum key: %v", err)
	}
	if got != plaintext {
		t.Errorf("= %q, want %q", got, plaintext)
	}

	// Signing too: Proton signs calendar and contact data, and ML-DSA is a signature algorithm.
	sig, err := ring.SignDetachedArmored(plaintext)
	if err != nil {
		t.Fatalf("sign with a post-quantum key: %v", err)
	}
	pub, err := ring.ArmoredPublicKey()
	if err != nil {
		t.Fatalf("export public key: %v", err)
	}
	verifier, err := NewPublicKeyRing(pub)
	if err != nil {
		t.Fatalf("public ring: %v", err)
	}
	defer verifier.Close()
	if !verifier.VerifyDetachedArmored(plaintext, sig) {
		t.Error("post-quantum signature did not verify")
	}
}

// TestMixedV4AndPqcRing is the shape of a real Proton post-quantum account: TWO primary user keys,
// a v4 and a v6, in one ring, with OpenPGP routing by PKESK.
func TestMixedV4AndPqcRing(t *testing.T) {
	v4 := newTestKey(t, "classic")
	v6 := newPqcKey(t, "quantum")

	ring, err := NewKeyRing(v4, testPassphrase)
	if err != nil {
		t.Fatalf("ring from v4 key: %v", err)
	}
	defer ring.Close()
	if err := ring.AddKey(v6, testPassphrase); err != nil {
		t.Fatalf("add v6 post-quantum key to the ring: %v", err)
	}
	if got := ring.CountKeys(); got != 2 {
		t.Fatalf("CountKeys = %d, want 2", got)
	}

	// A message addressed to ONLY the v6 key must still open from the combined ring.
	only, err := NewKeyRing(v6, testPassphrase)
	if err != nil {
		t.Fatalf("v6-only ring: %v", err)
	}
	defer only.Close()
	msg, err := only.Encrypt("addressed to the post-quantum key")
	if err != nil {
		t.Fatalf("encrypt: %v", err)
	}
	got, err := ring.DecryptSplit(msg.KeyPacket(), msg.DataPacket())
	if err != nil {
		t.Fatalf("mixed ring failed to route to the post-quantum key: %v", err)
	}
	if got != "addressed to the post-quantum key" {
		t.Errorf("= %q", got)
	}
}

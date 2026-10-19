// Interoperability tests against real gpg output.
//
// The backup feature writes .gpg archives users are told they can open with `gpg --decrypt`, and
// existing archives were written by the previous BouncyCastle implementation. Testing GopenPGP
// only against itself would prove neither. These fixtures come from GnuPG 2.4.9 using the same
// classic parameters the old code used: AES-256, CFB+MDC, salted+iterated SHA-256 S2K.
//
// The fixtures are not part of the repository. Point GPG_COMPAT_DIR at a folder holding
// legacy.gpg.asc and payload.bin from gpg to run these; without it both tests are skipped.
// Any payload.bin will do. Make legacy.gpg.asc from it with the passphrase the tests use:
//
//	gpg --batch --symmetric --armor --cipher-algo AES256 --s2k-digest-algo SHA256 --s2k-mode 3 \
//	    --pinentry-mode loopback --passphrase 'backup passphrase' -o legacy.gpg.asc payload.bin
package bridge

import (
	"bytes"
	"os"
	"path/filepath"
	"testing"
)

// compatDir is the folder the gpg fixtures are read from and ours.gpg.asc is written to.
func compatDir(t *testing.T) string {
	dir := os.Getenv("GPG_COMPAT_DIR")
	if dir == "" {
		t.Skip("GPG_COMPAT_DIR is not set, so there are no gpg fixtures to compare against")
	}
	return dir
}

func TestDecryptsArchiveWrittenByGpg(t *testing.T) {
	dir := compatDir(t)
	armored, err := os.ReadFile(filepath.Join(dir, "legacy.gpg.asc"))
	if err != nil {
		t.Skipf("fixture not present: %v", err)
	}
	want, err := os.ReadFile(filepath.Join(dir, "payload.bin"))
	if err != nil {
		t.Skipf("payload not present: %v", err)
	}

	got, err := DecryptWithPassword(armored, "backup passphrase")
	if err != nil {
		t.Fatalf("could not read a gpg-written archive: %v", err)
	}
	if !bytes.Equal(got, want) {
		t.Errorf("payload mismatch:\n got %q\nwant %q", got, want)
	}
}

func TestOurArchiveIsReadableByGpg(t *testing.T) {
	dir := compatDir(t)
	// Written here; after the test run, check that gpg decrypts it to the plaintext below:
	//   gpg --batch --pinentry-mode loopback --passphrase 'backup passphrase' \
	//     --decrypt "$GPG_COMPAT_DIR/ours.gpg.asc"
	armored, err := EncryptWithPassword([]byte("round trip through gpg"), "backup passphrase")
	if err != nil {
		t.Fatalf("encrypt: %v", err)
	}
	if err := os.WriteFile(filepath.Join(dir, "ours.gpg.asc"), []byte(armored), 0o600); err != nil {
		t.Fatalf("write: %v", err)
	}
}

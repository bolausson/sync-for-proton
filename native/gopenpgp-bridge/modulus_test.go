// The SRP modulus check on the Android side (SrpCrypto.readSignedModulus) splits Proton's
// cleartext-signed modulus itself and hands the signed text and the signature block to
// VerifyDetachedArmored. These tests pin that this call accepts Proton's signatures over its
// moduli, and nothing else.
//
// The key and the two signed moduli are Proton's own: modulusPubkey and the test vectors of
// github.com/ProtonMail/go-srp (srp.go and srp_test.go).
package bridge

import "testing"

const srpModulusKey = `-----BEGIN PGP PUBLIC KEY BLOCK-----

xjMEXAHLgxYJKwYBBAHaRw8BAQdAFurWXXwjTemqjD7CXjXVyKf0of7n9Ctm
L8v9enkzggHNEnByb3RvbkBzcnAubW9kdWx1c8J3BBAWCgApBQJcAcuDBgsJ
BwgDAgkQNQWFxOlRjyYEFQgKAgMWAgECGQECGwMCHgEAAPGRAP9sauJsW12U
MnTQUZpsbJb53d0Wv55mZIIiJL2XulpWPQD/V6NglBd96lZKBmInSXX/kXat
Sv+y0io+LR8i2+jV+AbOOARcAcuDEgorBgEEAZdVAQUBAQdAeJHUz1c9+KfE
kSIgcBRE3WuXC4oj5a2/U3oASExGDW4DAQgHwmEEGBYIABMFAlwBy4MJEDUF
hcTpUY8mAhsMAAD/XQD8DxNI6E78meodQI+wLsrKLeHn32iLvUqJbVDhfWSU
WO4BAMcm1u02t4VKw++ttECPt+HUgPUq5pqQWe5Q2cW4TMsE
=Y4Mw
-----END PGP PUBLIC KEY BLOCK-----`

var signedModuli = []struct{ text, signature string }{
	{
		text: "W2z5HBi8RvsfYzZTS7qBaUxxPhsfHJFZpu3Kd6s1JafNrCCH9rfvPLrfuqocxWPgWDH2R8neK7PkNvjxto9TStuY5z7jAzWRvFWN9cQhAKkdWgy0JY6ywVn22+HFpF4cYesHrqFIKUPDMSSIlWjBVmEJZ/MusD44ZT29xcPrOqeZvwtCffKtGAIjLYPZIEbZKnDM1Dm3q2K/xS5h+xdhjnndhsrkwm9U9oyA2wxzSXFL+pdfj2fOdRwuR5nW0J2NFrq3kJjkRmpO/Genq1UW+TEknIWAb6VzJJJA244K/H8cnSx2+nSNZO3bbo6Ys228ruV9A8m6DhxmS+bihN3ttQ==",
		signature: `-----BEGIN PGP SIGNATURE-----
Version: ProtonMail
Comment: https://protonmail.com

wl4EARYIABAFAlwB1j0JEDUFhcTpUY8mAAD8CgEAnsFnF4cF0uSHKkXa1GIa
GO86yMV4zDZEZcDSJo0fgr8A/AlupGN9EdHlsrZLmTA1vhIx+rOgxdEff28N
kvNM7qIK
=q6vu
-----END PGP SIGNATURE-----`,
	},
	{
		text: "o4ycZ14/7LfHkuSKWNlpQEh6bwLMVKvo0MFqVq9wHXwkZ/zMcqYaVhqNvLyDB0WY5Uv/Bo23JQsox52lM+4jPydw9/A9saAj8erLCc3ZaZHxOl/a8tlYTq7FeDrbhSSgivwTKJ5Y9otla/U8FATZBxqi7nqDihS5/7x/yK3VRnEsBG1i5DcY1UQK3KD9i9v7N2QTuGFYnRCv0MFsHzrQZWvUa1NsUhozU5PSV5s7hZkb/p6J3B9ybD6+LzuLS9fyLMcVdxzn2WUXG7JLeBbqsoECUfq9KP2waTzVLELOenWUV1wbioceJsaiP97ViwNJdnKx1ICoYu2c+z8ctVcqlw==",
		signature: `-----BEGIN PGP SIGNATURE-----
Version: ProtonMail
Comment: https://protonmail.com

wl4EARYIABAFAlwB1j0JEDUFhcTpUY8mAAB02wD5AOhMNS/K6/nvaeRhTr5n
iDGMalQccYlb58XzUEhqf3sBAOcTsz0fP3PVdMQYBbqcBl9Y6LGIG9DF4B4H
ZeLCoyYN
=cAxM
-----END PGP SIGNATURE-----`,
	},
}

func modulusRing(t *testing.T) *KeyRing {
	t.Helper()
	ring, err := NewPublicKeyRing(srpModulusKey)
	if err != nil {
		t.Fatalf("Proton's modulus key does not parse: %v", err)
	}
	return ring
}

func TestModulusSignatureVerifies(t *testing.T) {
	ring := modulusRing(t)
	defer ring.Close()
	for i, m := range signedModuli {
		if !ring.VerifyDetachedArmored(m.text, m.signature) {
			t.Errorf("modulus %d: Proton's signature does not verify", i)
		}
	}
}

func TestModulusSignatureRefusesEverythingElse(t *testing.T) {
	ring := modulusRing(t)
	defer ring.Close()
	a, b := signedModuli[0], signedModuli[1]
	if ring.VerifyDetachedArmored("X"+a.text[1:], a.signature) {
		t.Error("a modulus with one character changed verified")
	}
	if ring.VerifyDetachedArmored(a.text, b.signature) {
		t.Error("the signature over another modulus verified")
	}
	if ring.VerifyDetachedArmored(a.text+"\r\nX", a.signature) {
		t.Error("a modulus with a line added verified")
	}
	other := newTestRing(t, newTestKey(t, "not-proton"))
	defer other.Close()
	forged, err := other.SignDetachedArmored(a.text)
	if err != nil {
		t.Fatalf("sign: %v", err)
	}
	if ring.VerifyDetachedArmored(a.text, forged) {
		t.Error("a signature by another key verified")
	}
}

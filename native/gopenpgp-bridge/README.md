# GopenPGP Android bridge

A gomobile binding of [GopenPGP](https://github.com/ProtonMail/gopenpgp), built here rather than
taken off the shelf. Produces `gopenpgpbridge.aar`, which the app consumes from `app/libs/`.

## Why this exists

BouncyCastle's OpenPGP layer does not implement the RFC 9980 post-quantum algorithms (public-key
IDs 30-36). Not in 1.80, and not on its `main` branch as of August 2026:
`PublicKeyAlgorithmTags` stops at `Ed448 = 28` and jumps to the experimental range at 100.

Proton began rolling out opt-in post-quantum protection in 2026. Those accounts get OpenPGP **v6**
keys using the composite algorithms, and BouncyCastle cannot even PARSE them:

```
IOException: unknown signature key algorithm: 30
  at org.bouncycastle.bcpg.SignaturePacket.parseV6
  at org.bouncycastle.openpgp.PGPSecretKeyRing.<init>
```

That fails before any passphrase is tried, so affected users cannot sign in at all.

## The -proton suffix is mandatory, and this is the trap

**Plain `gopenpgp v3.4.1` / `go-crypto v1.4.1` have NO post-quantum support either.** Their
`PublicKeyAlgorithm` constants stop at `Ed448 = 28`, exactly like BouncyCastle. Only the
**`-proton`** builds ship `openpgp/mldsa_eddsa` and `openpgp/mlkem_ecdh` and define:

```
PubKeyAlgoMldsa65Ed25519 = 30      PubKeyAlgoMlkem768X25519 = 35
PubKeyAlgoMldsa87Ed448   = 31      PubKeyAlgoMlkem1024X448  = 36
```

We shipped v1.9.0 against the plain builds on the assumption that "GopenPGP supports PQC", and the
sign-in still failed for post-quantum accounts, with:

```
gopenpgp: error in reading key ring: openpgp: invalid data: first packet was not a public/private key
```

which is what go-crypto returns after **skipping** a key packet whose algorithm it does not know, so
the next packet it hands back is a signature rather than the key. If you ever see that error on a
key that is definitely a key, suspect the pin.

`build-aar.sh` now refuses to build unless the pin is a `-proton` build containing the PQC packages,
and runs `go test` first. `pqc_test.go` generates a real v6 ML-DSA key and drives it through the
bridge, so the claim is tested rather than assumed.

## Licensing — read before adding dependencies

| Artefact | Licence | Usable here? |
|---|---|---|
| `github.com/ProtonMail/gopenpgp` (source) | **MIT** | **Yes** — what we build against |
| `me.proton.crypto:android-golib` (prebuilt AAR) | **GPLv3** | **No** |
| `me.proton.core:crypto-android` | **GPLv3** | **No** |

Linking either GPLv3 artefact would oblige us to publish the app under
GPLv3. Building our own binding from the MIT source is precisely what keeps that clean. **Do not
"simplify" this by depending on Proton's prebuilt AAR.**

## Prerequisites

The commands are for Git Bash on Windows, which build-aar.sh is written for; elsewhere install
Go, the Android NDK and a JDK with your package manager and set ANDROID_HOME and JAVA_HOME.

```bash
scoop install go                                            # Go toolchain
go install golang.org/x/mobile/cmd/gomobile@v0.0.0-20260730202154-c700fe717e6e   # the x/mobile in go.mod
go install golang.org/x/mobile/cmd/gobind@v0.0.0-20260730202154-c700fe717e6e
scoop install android-clt                                   # provides sdkmanager
sdkmanager --sdk_root="$ANDROID_HOME" "ndk;27.3.13750724"   # several GB
gomobile init
```

`javac` comes from Android Studio's bundled JBR; no separate JDK is needed.

## Building

```bash
./build-aar.sh --verify     # check the pins, build nothing
./build-aar.sh              # build gopenpgpbridge.aar and print its SHA-256
cp gopenpgpbridge.aar ../../app/libs/
```

## Reproducibility

Everything is pinned, and the script refuses to run if the pins drift:

- `go.mod` pins **gopenpgp v3.4.1-proton** and **go-crypto v1.4.1-proton** (go-crypto does the actual OpenPGP work; the `-proton` suffix carries PQC)
- `go.mod` also pins golang.org/x/crypto at v0.54.0, the version `gomobile bind` links: it builds in a module of
  its own and resolved a newer x/crypto than the bridge needs, so the pin keeps `go test` on what ships
- `go.sum` pins their hashes; the script runs `go mod verify` and aborts on a mismatch
- The NDK version, `-androidapi`, and the ABI list are constants at the top of the script
- Each build prints the AAR's SHA-256. A rebuild does not reproduce the shipped file byte for byte: the AAR in
  app/libs was built before the script passed `-trimpath`, so its libraries carry build paths. Compare the
  module versions instead: `go version -m` on an extracted `jni/<abi>/libgojni.so` lists every module and its hash

Bump the pins deliberately, in their own commit. Afterwards run `go test ./...` here,
`./gradlew testDirectDebugUnitTest` at the top of the repository, and a sign-in and sync on a
device with a v4 account and with a post-quantum account. This is crypto, and a silent dependency
bump is not acceptable.

## Design

gomobile can only bind a restricted subset of Go types, so every exported signature is flat:
`string`, `[]byte`, `int`, `bool`, `error`, or a bound struct pointer.

The façade exposes **crypto primitives only**. All Proton-specific orchestration — which key
belongs to which address, calendar key maps, caching, key-ring lifetime — stays in Kotlin's
`KeyManager`, which already works and is proven in the field. That split keeps this module small
enough to review as security-relevant code, and keeps the risky part of the migration confined to
swapping primitives rather than rewriting logic.

### Bound surface

| Type | Purpose |
|---|---|
| `Bridge` (package funcs) | version, key metadata, session-key construction and use |
| `KeyRing` | a handle to unlocked private keys or public keys; decrypt, encrypt, sign, verify |
| `SessionKey` | the symmetric key that encrypts a body, shared across recipients |
| `EncryptedMessage` | the two halves of a PGP message — `KeyPacket()` and `DataPacket()` |

### Session keys and Proton's split model

Proton does not store a PGP message as one blob. It splits it into a **key packet** (the PKESK,
wrapping the session key to one recipient) and a **data packet** (the SEIPD ciphertext), stored in
separate API fields. Sharing an event means re-wrapping the *same* session key per attendee: one
data packet, N key packets.

GopenPGP models this natively (`BinaryKeyPacket`/`BinaryDataPacket`, `DecryptSessionKey`,
`EncryptSessionKey`). The BouncyCastle implementation had no equivalent and hand-rolled it: a PKESK
parser, RFC 6637 X25519 agreement, the KDF, and RFC 3394 AES unwrapping, roughly 100 lines of
packet arithmetic. Replacing that with a library call is the single largest correctness win of this
migration, and it is why the bridge exposes session keys as a first-class type.

## Tests

`bridge_test.go` covers every exported primitive through the façade — round trips, the split model,
session-key reuse across recipients, detached signatures (including the negative cases: tampered
data, wrong signer), multi-key routing by key packet, and safe behaviour on a closed ring.

```bash
go test ./...
```

They test **through the bridge API**, not against GopenPGP directly: what can break here is this
façade's own wiring — a builder configured wrong, a key packet returned where a data packet was
meant — not the library underneath. A Go test runs in a second where a device round trip costs
minutes, so this is the first gate for any change to `bridge.go`.

## Size

About 11.6 MB (the size build-aar.sh prints) for `arm64-v8a` + `armeabi-v7a` + `x86_64` (~5 MB of
native code per ABI after stripping, mostly the Go runtime). The direct APK is universal, so it carries
all three ABIs in full; Play's App Bundle is split per device.

## Gotchas

- **`tools.go` must stay.** Nothing imports `x/mobile`, so `go mod tidy` prunes it and the next
  build fails with `missing golang.org/x/mobile dependency`. The `//go:build tools` tag keeps it
  out of the real build.
- **PATH entries must be POSIX form** in bash. A Windows-style `C:/...` contains a colon, which
  bash reads as a PATH separator; `javac` then silently vanishes and gomobile fails late.

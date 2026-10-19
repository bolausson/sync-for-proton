#!/usr/bin/env bash
# Build the GopenPGP Android bridge AAR, reproducibly.
#
# Everything this produces is pinned: go.mod fixes the dependency versions and go.sum fixes their
# hashes, so a rebuild from the same commit yields the same crypto. Run with --verify to check the
# pins without building.
#
# Why we build this ourselves rather than taking Proton's prebuilt artefact: GopenPGP is MIT, but
# me.proton.crypto:android-golib and me.proton.core:crypto-android are GPLv3, which this app cannot
# link against. See native/gopenpgp-bridge/README.md.
#
# Prerequisites (see README for install commands):
#   Go, gomobile + gobind, Android NDK, and a JDK with javac.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$HERE"

# --- Pins. Bump deliberately, never incidentally. ---------------------------------------------
GOPENPGP_VERSION="v3.4.1-proton"   # github.com/ProtonMail/gopenpgp/v3
GO_CRYPTO_VERSION="v1.4.1-proton"  # go-crypto: ONLY the -proton builds carry RFC 9980 PQC
NDK_VERSION="27.3.13750724"        # r27d LTS. Deliberately not a release candidate.
ANDROID_API="29"                   # must match minSdk in app/build.gradle.kts
ABIS="android/arm64,android/arm,android/amd64"   # arm64-v8a, armeabi-v7a, x86_64
OUT="gopenpgpbridge.aar"

# --- Toolchain ---------------------------------------------------------------------------------
# The defaults below suit Git Bash on Windows. Elsewhere, set ANDROID_HOME and JAVA_HOME and put go,
# gomobile and javac on PATH yourself.
: "${ANDROID_HOME:=${LOCALAPPDATA:?set ANDROID_HOME to your Android SDK directory}/Android/Sdk}"
: "${JAVA_HOME:=C:/Program Files/Android/Android Studio/jbr}"
export ANDROID_HOME JAVA_HOME
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/$NDK_VERSION}"
# NOTE: PATH entries must be POSIX form. A Windows-style "C:/..." contains a colon, which bash
# reads as a PATH separator, and javac silently disappears.
export PATH="/c/Program Files/Android/Android Studio/jbr/bin:$HOME/go/bin:$PATH"

fail() { echo "ERROR: $*" >&2; exit 1; }

command -v go       >/dev/null || fail "go not found"
command -v gomobile >/dev/null || fail "gomobile not found (go install golang.org/x/mobile/cmd/gomobile@v0.0.0-20260730202154-c700fe717e6e, the x/mobile in go.mod, and gobind at the same version)"
command -v javac    >/dev/null || fail "javac not found — check JAVA_HOME and the PATH note above"
[ -d "$ANDROID_NDK_HOME" ] || fail "NDK not found at $ANDROID_NDK_HOME"

# --- Verify the pins actually match what is checked in ----------------------------------------
grep -q "gopenpgp/v3 $GOPENPGP_VERSION\$"   go.mod || fail "go.mod does not pin gopenpgp $GOPENPGP_VERSION"
grep -Eq "go-crypto $GO_CRYPTO_VERSION( |\$)" go.mod || fail "go.mod does not pin go-crypto $GO_CRYPTO_VERSION"

echo "gopenpgp   $GOPENPGP_VERSION"
echo "go-crypto  $GO_CRYPTO_VERSION"
echo "NDK        $NDK_VERSION"
echo "minSdk     $ANDROID_API"
echo "ABIs       $ABIS"

if [ "${1:-}" = "--verify" ]; then
    echo "Pins verified. No build requested."
    exit 0
fi

# Fail rather than silently re-resolving: go.sum is the integrity guarantee for crypto code.
go mod verify >/dev/null || fail "go mod verify failed — dependency hashes do not match go.sum"

# The "-proton" suffix is NOT cosmetic and must not be "tidied" to a plain version.
# Plain go-crypto v1.4.1 stops at Ed448 (28) and has no RFC 9980 post-quantum algorithms at all,
# exactly like BouncyCastle. Only the -proton builds ship openpgp/mldsa_eddsa and openpgp/mlkem_ecdh.
# Shipping the plain build silently reintroduces the bug this whole binding exists to fix: a
# post-quantum key failed with "first packet was not a public/private key", which is what
# go-crypto returns after skipping a key packet whose algorithm it does not recognise.
case "$GO_CRYPTO_VERSION" in
    *-proton) ;;
    *) fail "go-crypto must be a -proton build; $GO_CRYPTO_VERSION has no PQC support" ;;
esac
grep -q "mldsa_eddsa" "$(go env GOMODCACHE)/github.com/!proton!mail/go-crypto@$GO_CRYPTO_VERSION/openpgp/packet/packet.go" 2>/dev/null     || ls "$(go env GOMODCACHE)/github.com/!proton!mail/go-crypto@$GO_CRYPTO_VERSION/openpgp/mldsa_eddsa" >/dev/null 2>&1     || fail "go-crypto $GO_CRYPTO_VERSION does not contain the PQC packages"
echo "PQC        RFC 9980 algorithms present (verified in go-crypto $GO_CRYPTO_VERSION)"

# Belt and braces: the Go tests assert the algorithm IDs and round-trip a generated v6 ML-DSA key.
go test ./... >/dev/null || fail "go tests failed — refusing to build an AAR that may not read PQC keys"

echo "Building $OUT ..."
# 16 KB page alignment is MANDATORY, not an optimisation. gomobile links at the old 4 KB default
# (0x1000), and Android then refuses the library on 16 KB-page devices — Android 15+ surfaces it as
# "This app is not 16 KB compatible, ELF alignment check failed", and Google Play rejects such
# uploads. Caught on a real device before release; without this flag the whole GopenPGP route is
# unshippable. Verified below. -trimpath keeps the build machine's file paths out of the library.
gomobile bind -trimpath -target="$ABIS" -androidapi "$ANDROID_API" \
    -ldflags="-extldflags=-Wl,-z,max-page-size=16384" \
    -o "$OUT" .

# Refuse to hand over an AAR that would be rejected on device.
case "$(uname -s)" in
    Linux*)  NDK_HOST="linux-x86_64";   EXE="" ;;
    Darwin*) NDK_HOST="darwin-x86_64";  EXE="" ;;
    *)       NDK_HOST="windows-x86_64"; EXE=".exe" ;;
esac
READELF="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$NDK_HOST/bin/llvm-readelf$EXE"
if [ -x "$READELF" ]; then
    TMPD="$(mktemp -d)"
    # Extract everything: this unzip does not reliably honour a 'jni/*' member filter.
    unzip -o -q "$OUT" -d "$TMPD"
    for so in "$TMPD"/jni/*/libgojni.so; do
        abi="$(basename "$(dirname "$so")")"
        align="$("$READELF" -lW "$so" | awk '/LOAD/{print $NF}' | sort -u | tail -1)"
        [ "$align" = "0x4000" ] || fail "$abi is aligned $align, expected 0x4000 (16 KB)"
        echo "  $abi 16 KB aligned ($align)"
    done
    rm -rf "$TMPD"
else
    echo "  WARNING: llvm-readelf not found; 16 KB alignment NOT verified"
fi

echo
ls -la "$OUT" | awk '{printf "%s  %.1f MB\n", $9, $5/1048576}'
echo "SHA-256: $(sha256sum "$OUT" | cut -d' ' -f1)"
echo
echo "Install into the app with:  cp $OUT ../../app/libs/"

package me.proton.sync.crypto

import android.util.Base64
import at.favre.lib.crypto.bcrypt.BCrypt
import me.proton.sync.SyncLog
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Pure Kotlin implementation of Proton's SRP 6a authentication protocol.
 *
 * IMPORTANT: Proton uses little-endian byte order for all BigInteger operations.
 * The other parameters of Proton's variant: a 2048-bit modulus, SHA-512 stretched to 256 bytes
 * ([expandHash]) as the hash, and a password hash of bcrypt (cost 10, salt + "proton") over the
 * modulus ([hashPasswordV4]).
 */
object SrpCrypto {

    private const val TAG = "SrpCrypto"
    private const val SRP_LEN = 256 // 2048 bits in bytes
    private const val BCRYPT_PREFIX = "\$2a\$10\$"

    /**
     * Proton's SRP modulus key, the Ed25519 key that signs every modulus Proton's servers hand out.
     * Proton's own clients carry the same key (modulusPubkey in github.com/ProtonMail/go-srp).
     */
    internal const val SRP_MODULUS_KEY = """-----BEGIN PGP PUBLIC KEY BLOCK-----

xjMEXAHLgxYJKwYBBAHaRw8BAQdAFurWXXwjTemqjD7CXjXVyKf0of7n9Ctm
L8v9enkzggHNEnByb3RvbkBzcnAubW9kdWx1c8J3BBAWCgApBQJcAcuDBgsJ
BwgDAgkQNQWFxOlRjyYEFQgKAgMWAgECGQECGwMCHgEAAPGRAP9sauJsW12U
MnTQUZpsbJb53d0Wv55mZIIiJL2XulpWPQD/V6NglBd96lZKBmInSXX/kXat
Sv+y0io+LR8i2+jV+AbOOARcAcuDEgorBgEEAZdVAQUBAQdAeJHUz1c9+KfE
kSIgcBRE3WuXC4oj5a2/U3oASExGDW4DAQgHwmEEGBYIABMFAlwBy4MJEDUF
hcTpUY8mAhsMAAD/XQD8DxNI6E78meodQI+wLsrKLeHn32iLvUqJbVDhfWSU
WO4BAMcm1u02t4VKw++ttECPt+HUgPUq5pqQWe5Q2cW4TMsE
=Y4Mw
-----END PGP PUBLIC KEY BLOCK-----"""

    data class SrpProofs(
        val clientEphemeral: ByteArray,
        val clientProof: ByteArray,
        val expectedServerProof: ByteArray
    )

    /** The modulus does not carry a valid signature by Proton's modulus key, so the sign-in must stop. */
    class UnsignedModulusException(message: String) : SecurityException(message)

    /** An OpenPGP cleartext-signed message: the text its signature covers, and that signature. */
    internal class ClearSigned(val text: String, val armoredSignature: String)

    /**
     * Main entry point: compute SRP proofs for authentication.
     */
    fun generateSrpProofs(
        password: String,
        version: Int,
        salt: String,
        signedModulus: String,
        serverEphemeral: String
    ): SrpProofs {
        val modulusBytes = readSignedModulus(signedModulus)
        val serverEphemeralBytes = Base64.decode(serverEphemeral, Base64.DEFAULT)

        SyncLog.d(TAG, "Modulus bytes: ${modulusBytes.size}, ServerEphemeral bytes: ${serverEphemeralBytes.size}")

        val hashedPassword = hashPasswordV4(password, salt, modulusBytes)

        return computeProofs(modulusBytes, serverEphemeralBytes, hashedPassword)
    }

    /**
     * Verify the server's proof matches what we expect.
     */
    fun verifyServerProof(expectedServerProof: ByteArray, serverProof: String): Boolean {
        val serverProofBytes = Base64.decode(serverProof, Base64.DEFAULT)
        return expectedServerProof.contentEquals(serverProofBytes)
    }

    /**
     * The modulus from Proton's signed message, once Proton's modulus key ([SRP_MODULUS_KEY]) is
     * shown to have signed it, the check Proton's own clients make. Without it the modulus could
     * come from anybody able to answer in Proton's place, and a modulus of their choosing could let
     * them test password guesses offline against the proof this app sends.
     *
     * [verify] stands in for the GopenPGP bridge in unit tests, which cannot load its native code.
     *
     * @throws UnsignedModulusException when the message is not cleartext-signed, or the signature
     *   does not verify.
     */
    internal fun readSignedModulus(
        signedModulus: String,
        verify: (text: String, armoredSignature: String) -> Boolean = ::verifyWithModulusKey
    ): ByteArray {
        val signed = try {
            splitClearSigned(signedModulus)
        } catch (e: IllegalArgumentException) {
            throw UnsignedModulusException("The modulus is not a signed message: ${e.message}")
        }
        if (!verify(signed.text, signed.armoredSignature)) {
            throw UnsignedModulusException("The modulus's signature does not verify with Proton's modulus key")
        }
        return java.util.Base64.getDecoder().decode(signed.text.filterNot { it.isWhitespace() })
    }

    /**
     * Split an OpenPGP cleartext-signed message (RFC 9580, section 7) into the text its signature
     * covers and the armored signature. The text comes out in the form it was signed in: dash
     * escapes removed, trailing spaces and tabs dropped from each line, lines joined with CRLF, and
     * no line break after the last one. Anything after the signature other than blank lines is
     * refused, as Proton's go-srp refuses it.
     *
     * @throws IllegalArgumentException when [message] is not shaped like a cleartext-signed message.
     */
    internal fun splitClearSigned(message: String): ClearSigned {
        val lines = message.replace("\r\n", "\n").split("\n")
        var i = lines.indexOfFirst { it.trimEnd() == "-----BEGIN PGP SIGNED MESSAGE-----" }
        require(i >= 0) { "no BEGIN PGP SIGNED MESSAGE line" }
        i++
        while (i < lines.size && lines[i].isNotBlank()) i++ // the armor headers, such as "Hash: SHA256"
        require(i < lines.size) { "no blank line after the headers" }
        i++
        val text = mutableListOf<String>()
        while (i < lines.size && lines[i].trimEnd() != "-----BEGIN PGP SIGNATURE-----") {
            val line = lines[i]
            text.add((if (line.startsWith("- ")) line.substring(2) else line).trimEnd(' ', '\t'))
            i++
        }
        require(i < lines.size) { "no signature" }
        val signatureStart = i
        while (i < lines.size && lines[i].trimEnd() != "-----END PGP SIGNATURE-----") i++
        require(i < lines.size) { "the signature does not end" }
        require(lines.drop(i + 1).all { it.isBlank() }) { "data after the signature" }
        return ClearSigned(
            text = text.joinToString("\r\n"),
            armoredSignature = lines.subList(signatureStart, i + 1).joinToString("\n")
        )
    }

    /** Does [armoredSignature] by [SRP_MODULUS_KEY] cover [text]? Checked in GopenPGP. */
    private fun verifyWithModulusKey(text: String, armoredSignature: String): Boolean {
        val ring = bridge.Bridge.newPublicKeyRing(SRP_MODULUS_KEY)
        try {
            return ring.verifyDetachedArmored(text, armoredSignature)
        } finally {
            ring.close()
        }
    }

    /**
     * Hash the password for SRP (auth version >= 3).
     *
     * Flow: bcrypt(password, salt+"proton") -> replace $2a$ with $2y$ ->
     *       expandHash(bcryptOutput || modulus)
     *
     * This is Proton's password hash for auth version 3 and later; the app uses it for every account.
     */
    private fun hashPasswordV4(password: String, salt: String, modulusBytes: ByteArray): ByteArray {
        // Decode salt, append "proton", take first 16 bytes for bcrypt
        val saltBinary = Base64.decode(salt, Base64.DEFAULT)
        val protonBytes = "proton".toByteArray(Charsets.UTF_8)
        val combined = ByteArray(saltBinary.size + protonBytes.size)
        System.arraycopy(saltBinary, 0, combined, 0, saltBinary.size)
        System.arraycopy(protonBytes, 0, combined, saltBinary.size, protonBytes.size)
        val bcryptSalt = bcryptBase64Encode(combined.copyOf(16))
            .let { if (it.length > 22) it.substring(0, 22) else it }

        // bcrypt hash
        val fullSaltStr = BCRYPT_PREFIX + bcryptSalt
        val rawSalt = bcryptBase64Decode(bcryptSalt).copyOf(16)

        val hashResult = PROTON_BCRYPT.hash(10, rawSalt, password.toByteArray(Charsets.UTF_8))
        var hashStr = String(hashResult, Charsets.UTF_8)

        // Proton uses $2y$ — replace prefix so the full hash string matches
        hashStr = hashStr.replace("\$2a\$", "\$2y\$")

        val hashBytes = hashStr.toByteArray(Charsets.UTF_8)

        // expandHash(bcryptOutput || modulus)
        return expandHash(hashBytes + modulusBytes)
    }

    /**
     * Generate SRP client proofs.
     * All BigInteger operations use LITTLE-ENDIAN byte order, matching Proton's protocol.
     */
    private fun computeProofs(
        modulusBytes: ByteArray,
        serverEphemeralBytes: ByteArray,
        hashedPassword: ByteArray
    ): SrpProofs {
        val N = leToBigInt(modulusBytes)
        val g = BigInteger.valueOf(2)
        val B = leToBigInt(serverEphemeralBytes)

        require(B != BigInteger.ZERO) { "Invalid server ephemeral" }

        // k = H(g_le || N_le)
        val gLE = bigIntToLE(g, SRP_LEN)
        val k = leToBigInt(expandHash(gLE + modulusBytes))

        // x from hashed password (already 256 bytes from expandHash)
        val x = leToBigInt(hashedPassword)

        // Generate random client secret, find valid A and u
        val random = SecureRandom()
        var a: BigInteger = BigInteger.ZERO
        var A: BigInteger = BigInteger.ZERO
        var u: BigInteger = BigInteger.ZERO
        var ALE: ByteArray = ByteArray(SRP_LEN)

        var attempts = 0
        do {
            val aBytes = ByteArray(SRP_LEN)
            random.nextBytes(aBytes)
            a = leToBigInt(aBytes)
            A = g.modPow(a, N)
            if (A == BigInteger.ZERO) continue

            ALE = bigIntToLE(A, SRP_LEN)
            u = leToBigInt(expandHash(ALE + serverEphemeralBytes))
            attempts++
        } while (u == BigInteger.ZERO && attempts < 1000)

        require(u != BigInteger.ZERO) { "Failed to generate valid SRP parameters" }

        // S = (B - k*g^x mod N) ^ (u*x + a) mod (N-1)
        val Nm1 = N.subtract(BigInteger.ONE)
        val kgx = k.multiply(g.modPow(x, N)).mod(N)
        val base = B.subtract(kgx).mod(N).add(N).mod(N)
        val exp = u.multiply(x).add(a).mod(Nm1).add(Nm1).mod(Nm1)
        val S = base.modPow(exp, N)

        val SLE = bigIntToLE(S, SRP_LEN)

        // M1 = H(A_le || B_le || S_le) — client proof
        val M1 = expandHash(ALE + serverEphemeralBytes + SLE)

        // M2 = H(A_le || M1 || S_le) — expected server proof
        val M2 = expandHash(ALE + M1 + SLE)

        SyncLog.d(TAG, "SRP proofs computed successfully")

        return SrpProofs(
            clientEphemeral = ALE,
            clientProof = M1,
            expectedServerProof = M2
        )
    }

    // --- Little-endian BigInteger conversions ---

    /**
     * Convert little-endian byte array to BigInteger.
     */
    private fun leToBigInt(bytes: ByteArray): BigInteger {
        val reversed = bytes.reversedArray()
        return BigInteger(1, reversed)
    }

    /**
     * Convert BigInteger to little-endian byte array, padded to `length` bytes.
     */
    private fun bigIntToLE(value: BigInteger, length: Int): ByteArray {
        val bytes = value.toByteArray()
        // Remove leading zero byte if present (sign byte)
        val unsigned = if (bytes.isNotEmpty() && bytes[0] == 0.toByte()) {
            bytes.copyOfRange(1, bytes.size)
        } else {
            bytes
        }
        // Reverse to little-endian
        val reversed = unsigned.reversedArray()
        // Pad or truncate to desired length
        return if (reversed.size >= length) {
            reversed.copyOf(length)
        } else {
            reversed + ByteArray(length - reversed.size)
        }
    }

    /**
     * Expand a byte array into 256 bytes using SHA-512.
     * Hashes input+[0], input+[1], input+[2], input+[3] and concatenates (4 * 64 = 256 bytes).
     */
    internal fun expandHash(input: ByteArray): ByteArray {
        val result = ByteArray(256)
        for (i in 0 until 4) {
            val md = MessageDigest.getInstance("SHA-512")
            md.update(input)
            md.update(byteArrayOf(i.toByte()))
            val hash = md.digest()
            System.arraycopy(hash, 0, result, i * 64, 64)
        }
        return result
    }

    // ---- bcrypt custom base64 ----
    // bcrypt uses a non-standard base64 alphabet

    private const val BCRYPT_BASE64_CHARS = "./ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

    internal fun bcryptBase64Encode(data: ByteArray): String {
        val sb = StringBuilder()
        var i = 0
        while (i < data.size) {
            val b0 = data[i].toInt() and 0xFF
            sb.append(BCRYPT_BASE64_CHARS[(b0 shr 2) and 0x3F])

            if (i + 1 < data.size) {
                val b1 = data[i + 1].toInt() and 0xFF
                sb.append(BCRYPT_BASE64_CHARS[((b0 and 0x03) shl 4) or ((b1 shr 4) and 0x0F)])

                if (i + 2 < data.size) {
                    val b2 = data[i + 2].toInt() and 0xFF
                    sb.append(BCRYPT_BASE64_CHARS[((b1 and 0x0F) shl 2) or ((b2 shr 6) and 0x03)])
                    sb.append(BCRYPT_BASE64_CHARS[b2 and 0x3F])
                } else {
                    sb.append(BCRYPT_BASE64_CHARS[(b1 and 0x0F) shl 2])
                }
            } else {
                sb.append(BCRYPT_BASE64_CHARS[(b0 and 0x03) shl 4])
            }
            i += 3
        }
        return sb.toString()
    }

    internal fun bcryptBase64Decode(encoded: String): ByteArray {
        val result = mutableListOf<Byte>()
        var i = 0
        while (i < encoded.length) {
            val c0 = BCRYPT_BASE64_CHARS.indexOf(encoded[i])
            if (c0 < 0) break

            if (i + 1 >= encoded.length) break
            val c1 = BCRYPT_BASE64_CHARS.indexOf(encoded[i + 1])
            if (c1 < 0) break
            result.add(((c0 shl 2) or (c1 shr 4)).toByte())

            if (i + 2 >= encoded.length) break
            val c2 = BCRYPT_BASE64_CHARS.indexOf(encoded[i + 2])
            if (c2 < 0) break
            result.add((((c1 and 0x0F) shl 4) or (c2 shr 2)).toByte())

            if (i + 3 >= encoded.length) break
            val c3 = BCRYPT_BASE64_CHARS.indexOf(encoded[i + 3])
            if (c3 < 0) break
            result.add((((c2 and 0x03) shl 6) or c3).toByte())

            i += 4
        }
        return result.toByteArray()
    }

    // ---- bcrypt as Proton runs it ----

    /**
     * bcrypt as Proton's clients run it, for the SRP password hash ([hashPasswordV4]) and the key passphrase
     * (KeyManager.deriveMailboxPassword). bcrypt keys on at most 72 bytes, and Proton's implementations (go-srp
     * through github.com/ProtonMail/bcrypt, bcryptjs on the web) hash a longer password from its first 72 UTF-8
     * bytes, so such a password is a valid Proton password. `BCrypt.withDefaults()` refused it with an
     * IllegalArgumentException, which kept those accounts from signing in to the app until 2.0.0. The truncating
     * strategy gives the hash Proton's clients compute, and runs only on a password over 72 bytes, so every
     * shorter one hashes exactly as before.
     */
    internal val PROTON_BCRYPT: BCrypt.Hasher =
        BCrypt.with(at.favre.lib.crypto.bcrypt.LongPasswordStrategies.truncate(BCrypt.Version.VERSION_2A))
}

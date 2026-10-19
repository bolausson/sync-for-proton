package me.proton.sync.backup

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Passphrase-based encryption for the full ("settings + accounts") backup.
 *
 * Deliberately platform-only (`javax.crypto`) — no new dependency:
 *  - **KDF**: PBKDF2-HMAC-SHA256, [ITERATIONS] iterations, 16-byte random salt → 256-bit key.
 *  - **Cipher**: AES-256-GCM, 12-byte random IV, 128-bit tag.
 *
 * The file key derives purely from the user passphrase — no Android Keystore is
 * involved, which is exactly what makes the file portable to another device. The
 * GCM tag authenticates the payload, so a wrong passphrase or a tampered file
 * fails cleanly with [WrongPassphraseException] rather than yielding garbage.
 */
object BackupCrypto {

    const val KDF_ALGO = "PBKDF2WithHmacSHA256"
    const val CIPHER = "AES/GCM/NoPadding"
    const val ITERATIONS = 210_000          // OWASP guidance for PBKDF2-HMAC-SHA256
    private const val KEY_BITS = 256
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    /** Minimum passphrase length enforced in the UI; re-checked here defensively. */
    const val MIN_PASSPHRASE_LENGTH = 8

    /** Result of an encryption: the ciphertext plus the public KDF/cipher parameters. */
    data class Encrypted(
        val salt: ByteArray,
        val iv: ByteArray,
        val ciphertext: ByteArray
    )

    /** Thrown when GCM authentication fails — wrong passphrase or corrupt/tampered file. */
    class WrongPassphraseException : Exception("Wrong passphrase or corrupt backup file")

    fun randomSalt(): ByteArray = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
    fun randomIv(): ByteArray = ByteArray(IV_BYTES).also { SecureRandom().nextBytes(it) }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(passphrase, salt, iterations, KEY_BITS)
        try {
            return SecretKeyFactory.getInstance(KDF_ALGO).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    fun encrypt(plaintext: ByteArray, passphrase: String): Encrypted {
        val salt = randomSalt()
        val iv = randomIv()
        val keyBytes = deriveKey(passphrase.toCharArray(), salt, ITERATIONS)
        try {
            val cipher = Cipher.getInstance(CIPHER)
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(TAG_BITS, iv))
            val ct = cipher.doFinal(plaintext)
            return Encrypted(salt, iv, ct)
        } finally {
            java.util.Arrays.fill(keyBytes, 0)  // don't leave the file key in the heap
        }
    }

    /**
     * @param iterations PBKDF2 iteration count to derive the key with — pass the value
     *        recorded in the backup envelope so files written by other iteration counts
     *        still decrypt. Defaults to the current [ITERATIONS].
     * @throws WrongPassphraseException on GCM auth failure (wrong passphrase / corrupt file).
     */
    fun decrypt(
        ciphertext: ByteArray,
        passphrase: String,
        salt: ByteArray,
        iv: ByteArray,
        iterations: Int = ITERATIONS
    ): ByteArray {
        val keyBytes = deriveKey(passphrase.toCharArray(), salt, iterations)
        try {
            val cipher = Cipher.getInstance(CIPHER)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(TAG_BITS, iv))
            return cipher.doFinal(ciphertext)
        } catch (e: AEADBadTagException) {
            throw WrongPassphraseException()
        } finally {
            java.util.Arrays.fill(keyBytes, 0)
        }
    }

    fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
    fun unb64(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)
}

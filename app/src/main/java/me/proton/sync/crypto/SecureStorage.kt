package me.proton.sync.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import me.proton.sync.SyncLog
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts/decrypts sensitive strings using Android Keystore (AES-256-GCM).
 *
 * The AES key is hardware-backed on supported devices and never leaves the
 * secure element. Encrypted data is stored as Base64(IV + ciphertext + tag).
 *
 * Backward compatibility: if decryption fails, assumes the data is either
 * Base64-encoded plaintext (v1.2.43) or raw plaintext (pre-v1.2.43).
 */
object SecureStorage {

    private const val TAG = "SecureStorage"
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEY_ALIAS = "proton_sync_secret"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_IV_LENGTH = 12
    private const val GCM_TAG_LENGTH = 128 // bits
    // Prefix to identify Keystore-encrypted values
    private const val ENCRYPTED_PREFIX = "KS:"
    // Prefix for the no-Keystore fallback: Base64-obfuscated only, NOT encrypted.
    // Distinct from KS: so encrypted vs merely-obfuscated values are honestly
    // distinguishable (the old fallback reused KS:, making the prefix meaningless).
    private const val OBFUSCATED_PREFIX = "B64:"

    /**
     * Encrypt a plaintext string. Returns a storable string (KS: prefix + Base64).
     */
    fun encrypt(plaintext: String): String {
        return try {
            val key = getOrCreateKey()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            // Concatenate IV + ciphertext, encode as Base64
            val combined = iv + ciphertext
            ENCRYPTED_PREFIX + Base64.encodeToString(combined, Base64.NO_WRAP)
        } catch (e: Exception) {
            SyncLog.w(TAG, "Keystore encryption failed, falling back to Base64 obfuscation: ${e.javaClass.simpleName}")
            // Fallback: Base64 encode (better than plaintext, but NOT encryption —
            // marked with its own prefix so readers and diagnostics can tell)
            OBFUSCATED_PREFIX + Base64.encodeToString(plaintext.toByteArray(), Base64.NO_WRAP)
        }
    }

    /**
     * Decrypt a stored string. Handles four formats:
     * 1. "KS:..." — Keystore-encrypted (v1.2.44+), or the pre-1.6.18 no-Keystore
     *    fallback which reused the KS: prefix for Base64 plaintext
     * 2. "B64:..." — Base64-obfuscated no-Keystore fallback (v1.6.18+)
     * 3. Base64-encoded plaintext (v1.2.43)
     * 4. Raw plaintext (pre-v1.2.43)
     */
    fun decrypt(stored: String): String {
        if (stored.startsWith(OBFUSCATED_PREFIX)) {
            return try {
                String(Base64.decode(stored.removePrefix(OBFUSCATED_PREFIX), Base64.NO_WRAP), Charsets.UTF_8)
            } catch (_: Exception) {
                stored // Corrupt marker — return as-is
            }
        }
        if (stored.startsWith(ENCRYPTED_PREFIX)) {
            val data = stored.removePrefix(ENCRYPTED_PREFIX)
            try {
                val combined = Base64.decode(data, Base64.NO_WRAP)
                if (combined.size <= GCM_IV_LENGTH) {
                    // Too short for IV + ciphertext — treat as Base64 plaintext
                    return String(combined, Charsets.UTF_8)
                }
                val key = getOrCreateKey()
                val iv = combined.copyOfRange(0, GCM_IV_LENGTH)
                val ciphertext = combined.copyOfRange(GCM_IV_LENGTH, combined.size)
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
                return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
            } catch (e: Exception) {
                // Decryption failed — might be Base64-only fallback from encrypt()
                return try {
                    String(Base64.decode(data, Base64.NO_WRAP), Charsets.UTF_8)
                } catch (_: Exception) {
                    stored // Return as-is
                }
            }
        }

        // No prefix — legacy format. Try Base64 decode first (v1.2.43), then plaintext
        return try {
            val decoded = Base64.decode(stored, Base64.NO_WRAP)
            val result = String(decoded, Charsets.UTF_8)
            // Sanity check: if decoded looks like a valid password (printable chars), use it
            if (result.all { it.code in 32..126 }) result else stored
        } catch (_: Exception) {
            stored // Raw plaintext (pre-v1.2.43)
        }
    }

    /**
     * Strict variant for the session fields wrapped at rest since 1.6.18 (tokens, uid,
     * key salt, encrypted private key). Unlike [decrypt] it applies NO legacy Base64
     * heuristic: an unprefixed value is returned verbatim, because a legacy plaintext
     * token/uid can look like valid Base64 and the heuristic would corrupt it.
     *
     * These fields were never wrapped before 1.6.18, so a KS: value here is always
     * GCM ciphertext from this build line. If the Keystore key was lost (device
     * reset/repair), decryption fails and we return "" — the empty credential then
     * drives the normal re-auth path instead of feeding garbage to the API.
     */
    fun decryptStrict(stored: String): String {
        if (stored.startsWith(OBFUSCATED_PREFIX)) {
            return try {
                String(Base64.decode(stored.removePrefix(OBFUSCATED_PREFIX), Base64.NO_WRAP), Charsets.UTF_8)
            } catch (e: Exception) {
                SyncLog.w(TAG, "Obfuscated value unreadable (${e.javaClass.simpleName}); treating as absent")
                ""
            }
        }
        if (stored.startsWith(ENCRYPTED_PREFIX)) {
            return try {
                val combined = Base64.decode(stored.removePrefix(ENCRYPTED_PREFIX), Base64.NO_WRAP)
                if (combined.size <= GCM_IV_LENGTH) return ""
                val key = getOrCreateKey()
                val iv = combined.copyOfRange(0, GCM_IV_LENGTH)
                val ciphertext = combined.copyOfRange(GCM_IV_LENGTH, combined.size)
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
                String(cipher.doFinal(ciphertext), Charsets.UTF_8)
            } catch (e: Exception) {
                SyncLog.w(TAG, "Keystore decryption failed (${e.javaClass.simpleName}); credential treated as absent, re-auth will be required")
                ""
            }
        }
        // Unprefixed — legacy plaintext written before 1.6.18. Return verbatim.
        return stored
    }

    /** True if a stored value carries one of the at-rest wrapper prefixes. */
    fun isWrapped(stored: String): Boolean =
        stored.startsWith(ENCRYPTED_PREFIX) || stored.startsWith(OBFUSCATED_PREFIX)

    /**
     * True only if the value is real Keystore ciphertext (KS:), NOT plaintext and NOT
     * the B64: obfuscation fallback. Used to decide what still needs (re-)encrypting:
     * a B64: value written during a transient Keystore outage is deliberately treated
     * as still-eligible so it upgrades to genuine ciphertext once the Keystore recovers.
     */
    fun isHardwareEncrypted(stored: String): Boolean = stored.startsWith(ENCRYPTED_PREFIX)

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER)
        keyStore.load(null)

        val existing = keyStore.getEntry(KEY_ALIAS, null)
        if (existing is KeyStore.SecretKeyEntry) {
            return existing.secretKey
        }

        // Generate new AES-256-GCM key in Keystore
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            KEYSTORE_PROVIDER
        )
        keyGenerator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return keyGenerator.generateKey()
    }
}

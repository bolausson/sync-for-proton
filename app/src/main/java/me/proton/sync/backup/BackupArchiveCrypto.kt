package me.proton.sync.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.SecureRandom
import java.util.Date
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Passphrase-based, **gpg-compatible** encryption for a whole backup run.
 *
 * The run's files (per-calendar `.ics` + `contacts.vcf`) are first bundled into one
 * ZIP stream (a JDK built-in — no new dependency, and it preserves the individual
 * filenames), then encrypted as a single symmetric (password-based) OpenPGP message
 * through the GopenPGP bridge (`Bridge.encryptWithPassword`, native/gopenpgp-bridge).
 * The packets are what GopenPGP's default profile picks (ProtonV1 in the pinned v3.4.1-proton):
 * AES-256 + SEIPD/MDC integrity packet + salted-iterated SHA-256 S2K, so the output
 * decrypts on any computer with `gpg --decrypt backup.gpg`, with no dependence on this
 * app surviving. The bridge's compat_test.go holds the interoperability checks against gpg.
 *
 * Deliberately: no OpenPGP compression layer (the ZIP already deflates) and no
 * AEAD/OCB (which gpg < 2.4 cannot read) — classic AES-256/CFB+MDC is the most
 * portable choice.
 */
object BackupArchiveCrypto {

    /** File extension for an encrypted backup run. */
    const val EXTENSION = "gpg"

    private const val LITERAL_NAME = "backup.zip"

    /**
     * Thrown when the archive will not decrypt: a wrong passphrase, or a damaged or altered file that
     * fails OpenPGP's MDC integrity check. The bridge does not tell the two apart.
     */
    class WrongPassphraseException : Exception("Wrong passphrase or corrupt backup file")

    /**
     * Bundle [files] (name → bytes) into a ZIP and symmetric-encrypt it.
     * @return ASCII-armored OpenPGP message bytes (write straight to the `.gpg` file).
     */
    fun encrypt(files: Map<String, ByteArray>, passphrase: CharArray): ByteArray {
        val zipBytes = zip(files)
        // Standard OpenPGP password (SKESK) encryption, so the .gpg file stays readable by gpg
        // itself. GopenPGP picks a salted+iterated SHA-256 S2K; the previous implementation had to
        // ask for SHA-256 explicitly to avoid BouncyCastle defaulting to SHA-1.
        return try {
            bridge.Bridge.encryptWithPassword(zipBytes, String(passphrase)).toByteArray(Charsets.UTF_8)
        } finally {
            java.util.Arrays.fill(zipBytes, 0) // don't leave plaintext backup bytes on the heap
        }
    }

    /**
     * Reverse of [encrypt] — used by the in-app "Decrypt backup" action. Equivalent to
     * `gpg --decrypt`, then unzip.
     *
     * Accepts archives written by the older BouncyCastle implementation too: both produce a
     * standard SKESK message, and the reader takes armored or binary input.
     *
     * @return the bundled files (name → bytes), insertion-ordered.
     * @throws WrongPassphraseException on auth failure / corrupt input.
     */
    fun decrypt(input: InputStream, passphrase: CharArray): LinkedHashMap<String, ByteArray> {
        val zipBytes = try {
            bridge.Bridge.decryptWithPassword(input.readBytes(), String(passphrase))
        } catch (e: Throwable) {
            throw WrongPassphraseException()
        }
        try {
            return unzip(zipBytes)
        } finally {
            java.util.Arrays.fill(zipBytes, 0) // don't leave plaintext backup bytes on the heap
        }
    }

    private fun zip(files: Map<String, ByteArray>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zip ->
            for ((name, bytes) in files) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    private fun unzip(zipBytes: ByteArray): LinkedHashMap<String, ByteArray> {
        val result = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zin ->
            var entry = zin.nextEntry
            while (entry != null) {
                result[entry.name] = zin.readBytes()
                zin.closeEntry()
                entry = zin.nextEntry
            }
        }
        return result
    }
}


package me.proton.sync.crypto

import at.favre.lib.crypto.bcrypt.BCrypt
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The bcrypt both password hashes run ([SrpCrypto.PROTON_BCRYPT]): the hash Proton's clients compute, for every
 * password they accept. Until 2.0.0 a password over 72 UTF-8 bytes threw at sign-in, although Proton's own clients
 * (go-srp through github.com/ProtonMail/bcrypt, bcryptjs on the web) hash it from its first 72 bytes.
 *
 * Cost 4 keeps these fast; the cost does not change which password bytes are keyed.
 */
class ProtonBcryptTest {

    private val salt = ByteArray(16) { (it * 7 + 3).toByte() }

    private fun hash(password: ByteArray) = SrpCrypto.PROTON_BCRYPT.hash(4, salt, password)

    @Test
    fun `a password of up to 72 bytes hashes exactly as before`() {
        // "€" is 3 bytes in UTF-8, so 24 of them are exactly 72.
        for (password in listOf("", "a", "x".repeat(71), "x".repeat(72), "€".repeat(24))) {
            val bytes = password.toByteArray(Charsets.UTF_8)
            assertArrayEquals(password, BCrypt.withDefaults().hash(4, salt, bytes), hash(bytes))
        }
    }

    @Test
    fun `a longer password hashes as its first 72 bytes instead of throwing`() {
        val long = "0123456789".repeat(8).toByteArray() // 80 bytes
        try {
            BCrypt.withDefaults().hash(4, salt, long)
            fail("withDefaults() hashed 80 bytes; the reason for PROTON_BCRYPT is gone")
        } catch (_: IllegalArgumentException) {
        }
        assertArrayEquals(hash(long.copyOf(72)), hash(long))
        assertFalse("the 72nd byte still counts", hash(long.copyOf(71)).contentEquals(hash(long)))
    }

    @Test
    fun `the cut is at byte 72, inside a multi-byte character too, as Proton's bcrypt cuts`() {
        val cyrillic = "ж".repeat(37).toByteArray(Charsets.UTF_8)
        assertEquals(74, cyrillic.size)
        assertArrayEquals(hash(cyrillic.copyOf(72)), hash(cyrillic))
        // 73 bytes whose 72nd byte is the middle of the last "€": bcrypt keys on bytes, not characters.
        val split = ("a" + "€".repeat(24)).toByteArray(Charsets.UTF_8)
        assertEquals(73, split.size)
        assertArrayEquals(hash(split.copyOf(72)), hash(split))
    }

    @Test
    fun `Openwall crypt_blowfish's vectors, the long password included`() {
        // From Openwall's crypt_blowfish tests, which OpenBSD bcrypt and Proton's implementations reproduce. The
        // second password is 98 characters, and only its first 72 count.
        val vectors = listOf(
            "U*U" to "\$2a\$05\$CCCCCCCCCCCCCCCCCCCCC.E5YPO9kmyuRGyh0XouQYb4YMJKvyOeW",
            "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789chars after 72 are ignored" to
                "\$2a\$05\$abcdefghijklmnopqrstuu5s2v8.iXieOjg/.AySBTTZIIVFJeBui",
        )
        for ((password, expected) in vectors) {
            val rawSalt = SrpCrypto.bcryptBase64Decode(expected.substring(7, 29)).copyOf(16)
            val hashed = SrpCrypto.PROTON_BCRYPT.hash(expected.substring(4, 6).toInt(), rawSalt, password.toByteArray())
            assertEquals(password, expected, String(hashed, Charsets.UTF_8))
        }
    }

    @Test
    fun `both password hashes use it, and nothing in the app calls withDefaults`() {
        // hashPasswordV4 decodes its salt with android.util.Base64 and deriveMailboxPassword sits inside KeyManager,
        // which a JVM test cannot run, so their source is checked: either going back to withDefaults() would pass
        // every test above.
        fun source(path: String) = java.io.File("src/main/java/me/proton/sync/$path").readText().replace("\r\n", "\n")
        val srp = source("crypto/SrpCrypto.kt").substringAfter("private fun hashPasswordV4(").substringBefore("\n    }\n")
        assertTrue("hashPasswordV4", srp.contains("PROTON_BCRYPT.hash(10, rawSalt,"))
        val key = source("crypto/KeyManager.kt").substringAfter("private fun deriveMailboxPassword(").substringBefore("\n    }\n")
        assertTrue("deriveMailboxPassword", key.contains("SrpCrypto.PROTON_BCRYPT"))
        // Every source set but the tests (main, debug, direct, play), code lines only: the KDoc may name it.
        val callers = java.io.File("src").listFiles()!!.filter { it.isDirectory && !it.name.startsWith("test") }
            .flatMap { set -> set.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
            .filter { file ->
                file.readLines().any { line ->
                    val code = line.trim()
                    !code.startsWith("*") && !code.startsWith("/") && code.contains("withDefaults()")
                }
            }.map { it.name }
        assertEquals(emptyList<String>(), callers)
    }
}

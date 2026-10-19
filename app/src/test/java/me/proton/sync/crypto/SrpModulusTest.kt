package me.proton.sync.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The parsing half of the SRP modulus check. The signature itself is checked in GopenPGP, whose
 * native code a JVM test cannot load: native/gopenpgp-bridge/modulus_test.go checks that half
 * with the same message, and the srp_modulus debug command checks a live modulus on a phone.
 *
 * The message is one of Proton's own test vectors (github.com/ProtonMail/go-srp, srp_test.go): a
 * real modulus, signed with Proton's modulus key.
 */
class SrpModulusTest {

    private val modulus =
        "W2z5HBi8RvsfYzZTS7qBaUxxPhsfHJFZpu3Kd6s1JafNrCCH9rfvPLrfuqocxWPgWDH2R8neK7PkNvjxto9TStuY5z7jAzWRvFWN9cQhAKkdWgy0JY6ywVn22+HFpF4cYesHrqFIKUPDMSSIlWjBVmEJZ/MusD44ZT29xcPrOqeZvwtCffKtGAIjLYPZIEbZKnDM1Dm3q2K/xS5h+xdhjnndhsrkwm9U9oyA2wxzSXFL+pdfj2fOdRwuR5nW0J2NFrq3kJjkRmpO/Genq1UW+TEknIWAb6VzJJJA244K/H8cnSx2+nSNZO3bbo6Ys228ruV9A8m6DhxmS+bihN3ttQ=="

    private val signature = """-----BEGIN PGP SIGNATURE-----
Version: ProtonMail
Comment: https://protonmail.com

wl4EARYIABAFAlwB1j0JEDUFhcTpUY8mAAD8CgEAnsFnF4cF0uSHKkXa1GIa
GO86yMV4zDZEZcDSJo0fgr8A/AlupGN9EdHlsrZLmTA1vhIx+rOgxdEff28N
kvNM7qIK
=q6vu
-----END PGP SIGNATURE-----"""

    private val signedModulus = "-----BEGIN PGP SIGNED MESSAGE-----\nHash: SHA256\n\n$modulus\n$signature"

    @Test
    fun `Proton's signed modulus splits into the signed text and the signature`() {
        val signed = SrpCrypto.splitClearSigned(signedModulus)
        assertEquals(modulus, signed.text)
        assertEquals(signature, signed.armoredSignature)
    }

    @Test
    fun `CRLF line endings and blank lines after the signature change nothing`() {
        val signed = SrpCrypto.splitClearSigned(signedModulus.replace("\n", "\r\n") + "\r\n\r\n")
        assertEquals(modulus, signed.text)
        assertEquals(signature, signed.armoredSignature)
    }

    @Test
    fun `data after the signature is refused, as go-srp refuses it`() {
        assertRefused(signedModulus + "data after modulus")
        assertRefused(signedModulus + "\n\nmore")
    }

    @Test
    fun `a message without a signature, or no signed message at all, is refused`() {
        assertRefused(modulus)
        assertRefused("-----BEGIN PGP SIGNED MESSAGE-----\nHash: SHA256\n\n$modulus\n")
        assertRefused(signedModulus.substringBefore("-----END PGP SIGNATURE-----"))
        assertRefused("-----BEGIN PGP SIGNED MESSAGE-----\nHash: SHA256")
    }

    @Test
    fun `the text comes out in its signed form`() {
        val message = "-----BEGIN PGP SIGNED MESSAGE-----\nHash: SHA256\n\n" +
            "- -dash escaped  \nsecond line\t\n\nafter a blank line\n$signature"
        assertEquals(
            "-dash escaped\r\nsecond line\r\n\r\nafter a blank line",
            SrpCrypto.splitClearSigned(message).text
        )
    }

    @Test
    fun `a verified modulus decodes to the 2048-bit value`() {
        var seen: Pair<String, String>? = null
        val bytes = SrpCrypto.readSignedModulus(signedModulus) { text, sig -> seen = text to sig; true }
        assertEquals(modulus to signature, seen)
        assertEquals(256, bytes.size)
        assertArrayEquals(java.util.Base64.getDecoder().decode(modulus), bytes)
    }

    @Test
    fun `a modulus whose signature does not verify ends the sign-in`() {
        try {
            SrpCrypto.readSignedModulus(signedModulus) { _, _ -> false }
            fail("a modulus with a failing signature was accepted")
        } catch (e: SrpCrypto.UnsignedModulusException) {
            assertTrue(e.message!!.contains("does not verify"))
        }
    }

    @Test
    fun `an unsigned modulus ends the sign-in without reaching the verifier`() {
        var called = false
        try {
            SrpCrypto.readSignedModulus(modulus) { _, _ -> called = true; true }
            fail("an unsigned modulus was accepted")
        } catch (e: SrpCrypto.UnsignedModulusException) {
            assertFalse(called)
        }
    }

    @Test
    fun `the pinned key is one key, the same one the bridge test verifies Proton's moduli with`() {
        // native/gopenpgp-bridge/modulus_test.go keeps its own copy of the key and is the only test that runs the
        // real signature check. A key changed on one side only, say in a rotation (auth manifest section 3a), would
        // pass every test while the app shipped the other one.
        val goTest = java.io.File("../native/gopenpgp-bridge/modulus_test.go").readText().replace("\r\n", "\n")
        val goKey = Regex("(?s)const srpModulusKey = `(.*?)`").find(goTest)?.groupValues?.get(1)
            ?: error("no srpModulusKey in modulus_test.go")
        for (key in listOf(SrpCrypto.SRP_MODULUS_KEY, goKey)) {
            assertEquals(1, Regex("-----BEGIN PGP PUBLIC KEY BLOCK-----").findAll(key).count())
            assertEquals(1, Regex("-----END PGP PUBLIC KEY BLOCK-----").findAll(key).count())
        }
        fun body(armored: String) = armored.replace("\r\n", "\n").lines().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("-----") && !it.contains(": ") }
        assertEquals(body(goKey), body(SrpCrypto.SRP_MODULUS_KEY))
    }

    private fun assertRefused(message: String) {
        try {
            SrpCrypto.splitClearSigned(message)
            fail("accepted: ${message.take(60)}")
        } catch (_: IllegalArgumentException) {
        }
    }
}

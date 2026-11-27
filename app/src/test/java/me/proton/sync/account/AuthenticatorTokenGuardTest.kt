package me.proton.sync.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Neither of the app's account authenticators hands a token to anyone (2.2.2). With customTokens="true", Android passes
 * every app's AccountManager.getAuthToken request straight to the authenticator, without its own permission prompt.
 * Up to 2.2.1 ProtonAccountAuthenticator answered it with the access token, whoever asked. Low severity: the token is
 * useless on its own, because Proton's API also requires the session ID, which was never handed out (checked on a
 * test phone: without it Proton answers 401). An authenticator cannot run in a JVM test (android.jar stubs), so these
 * guards pin the source.
 */
class AuthenticatorTokenGuardTest {

    /** The code lines only: no KDoc, block or line comments, each trimmed, joined by single spaces. */
    private fun code(path: String): String =
        File("src/main/java/me/proton/sync/$path").readLines().map { it.trim() }
            .filterNot { it.startsWith("*") || it.startsWith("/*") || it.startsWith("//") }
            .joinToString(" ") { it.substringBefore(" // ") }

    @Test
    fun `the main authenticator refuses every getAuthToken caller`() {
        val src = code("account/ProtonAccountAuthenticator.kt")
        assertFalse("never returns a token", src.contains("KEY_AUTHTOKEN"))
        for (forbidden in listOf("sessionManager", "getSession", "accessToken", "peekAuthToken", "SecureStorage")) {
            assertFalse("never reads the session: $forbidden", src.contains(forbidden))
        }
        assertTrue(
            src.contains(
                "): Bundle = Bundle().apply { putInt(AccountManager.KEY_ERROR_CODE, AccountManager.ERROR_CODE_UNSUPPORTED_OPERATION)"
            )
        )
    }

    @Test
    fun `the address book authenticator returns no token either`() {
        val src = code("account/AddressBookAuthenticatorService.kt")
        assertFalse(src.contains("KEY_AUTHTOKEN"))
        assertTrue(
            src.contains(
                "override fun getAuthToken(response: AccountAuthenticatorResponse, account: Account, authTokenType: String, options: Bundle?) = Bundle()"
            )
        )
    }

    @Test
    fun `both authenticators keep customTokens on, so Android never serves the stored token itself`() {
        for (xml in listOf("authenticator.xml", "authenticator_address_book.xml")) {
            assertTrue(xml, File("src/main/res/xml/$xml").readText().contains("android:customTokens=\"true\""))
        }
    }

    @Test
    fun `nothing in the app asks Android for a token`() {
        val callers = File("src/main/java").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { f -> f.readText().let { it.contains(".getAuthToken(") || it.contains("blockingGetAuthToken(") } }
            .map { it.name }
            .toList()
        assertEquals(emptyList<String>(), callers)
    }
}

package me.proton.sync.account

import me.proton.sync.account.SignInErrors.Kind
import me.proton.sync.api.SrpAuthHandler.AuthException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which message a failed sign-in gets, decided by Proton's Code and the HTTP status, never by the
 * exception's text. message() itself needs Resources and is covered by the signin-probe device step.
 */
class SignInErrorsTest {

    private fun refused(status: Int?, code: Int?, untrusted: Boolean = false) =
        AuthException("technical text", httpStatus = status, protonCode = code, untrustedServer = untrusted)

    @Test
    fun `each mapped Proton code has its own kind`() {
        assertEquals(Kind.WRONG_CREDENTIALS, SignInErrors.kindOf(refused(422, 8002)))
        assertEquals(Kind.VERIFICATION_REFUSED, SignInErrors.kindOf(refused(422, 12087)))
        assertEquals(Kind.ACCOUNT_DISABLED, SignInErrors.kindOf(refused(422, 10003)))
        assertEquals(Kind.SSO_ONLY, SignInErrors.kindOf(refused(422, 8100)))
    }

    /** A failed modulus, proof or URL check means the answer may not be Proton's, so its code is ignored. */
    @Test
    fun `an untrusted server wins over any code`() {
        assertEquals(Kind.UNTRUSTED_SERVER, SignInErrors.kindOf(refused(422, 8002, untrusted = true)))
        assertEquals(Kind.UNTRUSTED_SERVER, SignInErrors.kindOf(AuthException("modulus", untrustedServer = true)))
    }

    @Test
    fun `a 401 without a mapped code is an expired session`() {
        assertEquals(Kind.SESSION_EXPIRED, SignInErrors.kindOf(refused(401, null)))
    }

    @Test
    fun `any other refusal is generic`() {
        assertEquals(Kind.PROTON_REFUSED, SignInErrors.kindOf(refused(503, 2000)))
        assertEquals(Kind.PROTON_REFUSED, SignInErrors.kindOf(refused(null, null)))
    }

    @Test
    fun `connection failures are network errors`() {
        assertEquals(Kind.NETWORK, SignInErrors.kindOf(java.net.UnknownHostException("proton.me")))
        assertEquals(Kind.NETWORK, SignInErrors.kindOf(java.net.SocketTimeoutException("timeout")))
        assertEquals(Kind.NETWORK, SignInErrors.kindOf(javax.net.ssl.SSLException("handshake")))
    }

    @Test
    fun `anything else is unexpected`() {
        assertEquals(Kind.UNEXPECTED, SignInErrors.kindOf(IllegalArgumentException("boom")))
    }

    @Test
    fun `support code names what is known`() {
        assertEquals("HTTP 503, code 2000", SignInErrors.supportCode(refused(503, 2000)))
        assertEquals("HTTP 500", SignInErrors.supportCode(refused(500, null)))
        assertEquals("code 2001", SignInErrors.supportCode(refused(null, 2001)))
        assertNull(SignInErrors.supportCode(refused(null, null)))
        assertNull(SignInErrors.supportCode(java.io.IOException("reset")))
    }

    /** The message now carries Proton's Code and Error, where "401" can turn up inside something else. */
    @Test
    fun `the typed status decides an expired session`() {
        assertFalse(
            SignInErrors.isSessionExpired(
                AuthException("2FA verification failed: HTTP 422 (Code 24010: x)", httpStatus = 422, protonCode = 24010)
            )
        )
        assertTrue(SignInErrors.isSessionExpired(refused(401, null)))
    }

    @Test
    fun `other exceptions keep the old match on the message`() {
        assertTrue(SignInErrors.isSessionExpired(Exception("HTTP 401 Unauthorized")))
        assertTrue(SignInErrors.isSessionExpired(Exception("Expired access token")))
        assertTrue(SignInErrors.isSessionExpired(Exception("Invalid access token")))
        assertFalse(SignInErrors.isSessionExpired(Exception("boom")))
        // An AuthException that never got a status (an empty user-info body) falls back to the text too.
        assertFalse(SignInErrors.isSessionExpired(AuthException("Failed to fetch user info: empty response body")))
    }
}

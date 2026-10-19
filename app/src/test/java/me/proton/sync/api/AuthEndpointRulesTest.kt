package me.proton.sync.api

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule that decides whether an /auth/v4 call carries a token.
 *
 * Both directions fail silently, which is why this is tested at all: attaching a token to the login
 * breaks sign-in, and stripping one from the sign-out turns it into a permanent 401 whose only
 * symptom is a session that quietly outlives the account.
 */
class AuthEndpointRulesTest {

    private val base = "https://mail-api.proton.me/"

    @Test
    fun `the login goes out without a token`() {
        assertTrue(isUnauthenticatedAuthCall(base + "auth/v4", "POST"))
    }

    @Test
    fun `the SRP challenge goes out without a token`() {
        assertTrue(isUnauthenticatedAuthCall(base + "auth/v4/info", "POST"))
    }

    @Test
    fun `the sign-out MUST carry a token`() {
        // The regression this file exists for. DELETE and POST share the path, and matching on the
        // path alone stripped the token from the revoke, so it could only ever answer 401 and the
        // session stayed alive on Proton after the account was removed from the phone.
        assertFalse(isUnauthenticatedAuthCall(base + "auth/v4", "DELETE"))
    }

    @Test
    fun `submitting a second factor carries a token`() {
        // Proton authenticates the 2FA submission with the session's Bearer token and UID.
        assertFalse(isUnauthenticatedAuthCall(base + "auth/v4/2fa", "POST"))
    }

    @Test
    fun `revoking other sessions carries a token`() {
        assertFalse(isUnauthenticatedAuthCall(base + "auth/v4/sessions", "DELETE"))
        assertFalse(isUnauthenticatedAuthCall(base + "auth/v4/sessions/abc123", "DELETE"))
    }

    @Test
    fun `the method comparison is not case sensitive`() {
        assertTrue(isUnauthenticatedAuthCall(base + "auth/v4", "post"))
    }

    @Test
    fun `an unrelated endpoint is untouched by this rule`() {
        assertFalse(isUnauthenticatedAuthCall(base + "calendar/v1/events/sync", "PUT"))
        assertFalse(isUnauthenticatedAuthCall(base + "contacts/v4/contacts", "POST"))
    }
}

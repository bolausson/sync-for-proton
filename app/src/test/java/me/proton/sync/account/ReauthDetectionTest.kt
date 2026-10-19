package me.proton.sync.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Whether a sign-in re-authenticates an account or adds one.
 *
 * Getting this wrong is not a cosmetic slip. The new-account path asks the user to name the
 * account, offers the canonical `<username>@proton.me` as the default, and then provisions under
 * whatever they accept. For somebody re-authenticating an account they had named `alice`, taking
 * the offered default would have created a SECOND account beside the first and left the first
 * signed out, with its calendars and contacts stranded under a name nothing syncs to any more.
 *
 * The old test was `EXTRA_REAUTH && EXTRA_ACCOUNT_NAME != null`, and of the three places that
 * start a re-authentication only the settings row ever set that boolean. The session-expired
 * notification set EXTRA_ADD_ACCOUNT, the exact opposite, and the authenticator's getAuthToken set
 * neither. So the rule below deliberately does not consult the flag at all: an account already on
 * the phone decides, and no caller can break this by forgetting to announce itself.
 */
class ReauthDetectionTest {

    /** name -> proton_username, as AccountManager holds it. */
    private fun resolve(
        accounts: Map<String, String>,
        nameExtra: String?,
        signedInAs: String,
    ): String? {
        if (accounts.isEmpty()) return null
        if (nameExtra != null && accounts.containsKey(nameExtra)) return nameExtra
        return accounts.entries.firstOrNull { it.value.equals(signedInAs, ignoreCase = true) }?.key
    }

    private val onePhone = mapOf("alice" to "alice")

    /** The notification path: account named, flag never set. */
    @Test
    fun `the named account is a re-auth even with no flag`() {
        assertEquals("alice", resolve(onePhone, nameExtra = "alice", signedInAs = "alice"))
    }

    /**
     * The name extra is the display name, which the user may have changed since. The Proton
     * username is what they just signed in with and cannot drift.
     */
    @Test
    fun `a renamed account is still found by its proton username`() {
        val renamed = mapOf("Work" to "alice")
        assertEquals("Work", resolve(renamed, nameExtra = "alice", signedInAs = "alice"))
        assertEquals("Work", resolve(renamed, nameExtra = null, signedInAs = "alice"))
    }

    /** Signing in again from the plain Add-account button must not duplicate the account. */
    @Test
    fun `signing in again with no extras at all is still a re-auth`() {
        assertEquals("alice", resolve(onePhone, nameExtra = null, signedInAs = "alice"))
    }

    /** And the half that must keep working: a genuinely new account still gets set up. */
    @Test
    fun `a different proton account is a new account`() {
        assertNull(resolve(onePhone, nameExtra = null, signedInAs = "someone.else"))
    }

    @Test
    fun `the first account on a fresh install is a new account`() {
        assertNull(resolve(emptyMap(), nameExtra = null, signedInAs = "alice"))
    }

    /**
     * A stale name extra, for instance a notification left over from an account since removed,
     * must not veto the username match and send an existing account through setup.
     */
    @Test
    fun `a name extra that matches nothing falls back to the username`() {
        assertEquals("alice", resolve(onePhone, nameExtra = "gone@example.com", signedInAs = "alice"))
    }
}

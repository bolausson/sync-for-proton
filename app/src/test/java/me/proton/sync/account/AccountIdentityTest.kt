package me.proton.sync.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decision behind LoginActivity's two sign-in guards (AccountIdentity): a name already used by an
 * account of ANOTHER sign-in is refused, because requests are routed by that bare name and the sign-in
 * would otherwise write its session over that account.
 */
class AccountIdentityTest {

    @Test
    fun `canonical drops case, spaces and the two proton domains the alias dialog adds`() {
        assertEquals("carol", AccountIdentity.canonical(" Carol@Proton.me "))
        assertEquals("carol", AccountIdentity.canonical("carol@pm.me"))
        assertEquals("a@example.org", AccountIdentity.canonical("A@example.org"))
        assertTrue(AccountIdentity.sameSignIn("carol", "carol@proton.me"))
        assertFalse(AccountIdentity.sameSignIn("alice", "bob"))
    }

    @Test
    fun `typing a username that is another identity's display name is refused`() {
        // The case this guards: account "work" belongs to alice; a new sign-in types "work".
        val phone = mapOf("work" to "alice@proton.me")
        assertTrue(AccountIdentity.takenByOtherSignIn(phone, name = "work", signIn = "work"))
    }

    @Test
    fun `picking another identity's display name at the alias prompt is refused`() {
        val phone = mapOf("Personal" to "alice")
        assertTrue(AccountIdentity.takenByOtherSignIn(phone, name = "Personal", signIn = "bob"))
    }

    @Test
    fun `signing in again as the same identity stays allowed`() {
        // Display name defaulted to the typed name; a later sign-in types the same or the bare form.
        val phone = mapOf("alice@proton.me" to "alice", "a@example.org" to "a@example.org")
        assertFalse(AccountIdentity.takenByOtherSignIn(phone, name = "alice@proton.me", signIn = "alice@proton.me"))
        assertFalse(AccountIdentity.takenByOtherSignIn(phone, name = "a@example.org", signIn = "A@example.org"))
    }

    @Test
    fun `a name no account has, or one that differs only in case, is free`() {
        val phone = mapOf("work" to "alice")
        assertFalse(AccountIdentity.takenByOtherSignIn(phone, name = "bob", signIn = "bob"))
        // AccountManager and the session map compare names exactly, so "Work" is a different key.
        assertFalse(AccountIdentity.takenByOtherSignIn(phone, name = "Work", signIn = "Work"))
        assertFalse(AccountIdentity.takenByOtherSignIn(emptyMap(), name = "work", signIn = "work"))
    }

    @Test
    fun `an account from before proton_username was recorded keeps the old behaviour`() {
        // It cannot be told apart, so it never blocks: that is what the sign-in always did with it.
        val phone = mapOf("work" to null)
        assertFalse(AccountIdentity.takenByOtherSignIn(phone, name = "work", signIn = "someone-else"))
    }
}

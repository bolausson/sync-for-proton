package me.proton.sync.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The encryption-key choice for a calendar with more than one active key.
 *
 * [Harness] drives [CalendarKeySelection] exactly the way [KeyManager.unlockCalendarKey] does,
 * including the "slot has no ring yet" input, and tracks which key the ring would hold. The only thing
 * it stands in for is the decrypt itself, which is the part that needs the native bridge.
 */
class CalendarKeySelectionTest {

    private val slot = "acct:cal:C1"
    private val oldKey = "acct:1111"
    private val newKey = "acct:2222"

    private class Harness(val slot: String) {
        val selection = CalendarKeySelection()
        var ring: String? = null
        var unlockAttempts = 0
        /** KeyManager.goKeyRings for the account: false once signed out. */
        var signedIn = true
        /** KeyManager.goRingKeysAdded: keys in the account-wide decryption ring. */
        val accountRingKeys = HashSet<String>()

        /**
         * One call of unlockCalendarKey for [keyId]. [decrypts] false models a wrong member passphrase.
         * [afterAccountRing] runs where KeyManager has added the key to the account ring but not yet
         * taken the calendar ring lock, the window a sign-out can land in.
         */
        fun offer(keyId: String, isPrimary: Boolean, decrypts: Boolean = true, afterAccountRing: () -> Unit = {}) {
            if (keyId in accountRingKeys && selection.isSettled(keyId, slot, isPrimary, slotHasRing = ring != null)) return
            unlockAttempts++
            if (!decrypts) return                          // decrypt threw: nothing is recorded
            accountRingKeys.add(keyId)
            afterAccountRing()
            if (!signedIn) return                          // signed out meanwhile: discarded
            val takes = selection.takesRing(keyId, slot, isPrimary, slotHasRing = ring != null)
            if (takes) ring = keyId
            selection.recordUnlocked(keyId, slot, isPrimary, tookRing = takes)
        }

        /** KeyManager.clearKeyRing, in its order. */
        fun signOut() {
            signedIn = false
            ring = null
            selection.clearAccount("acct")
            accountRingKeys.clear()
        }
    }

    @Test
    fun `a primary key listed after a non-primary one takes the slot`() {
        val h = Harness(slot)
        h.offer(oldKey, isPrimary = false)
        h.offer(newKey, isPrimary = true)
        assertEquals(newKey, h.ring)
        h.offer(oldKey, isPrimary = false)
        assertEquals("a later non-primary key must not take it back", newKey, h.ring)
    }

    @Test
    fun `a primary key listed before a non-primary one keeps the slot`() {
        val h = Harness(slot)
        h.offer(newKey, isPrimary = true)
        h.offer(oldKey, isPrimary = false)
        assertEquals(newKey, h.ring)
    }

    /**
     * A failed attempt used to be recorded as added, so the next attempt with the RIGHT
     * member passphrase returned early as if it had succeeded.
     */
    @Test
    fun `a failed attempt is not remembered as success`() {
        val h = Harness(slot)
        h.offer(oldKey, isPrimary = false)
        h.offer(newKey, isPrimary = true, decrypts = false)   // another member's passphrase first
        h.offer(newKey, isPrimary = true)                     // our own member passphrase
        assertEquals(newKey, h.ring)
    }

    /**
     * After a key reset the new primary can be listed BEFORE the demoted old key. The
     * old key used to still count as primary from its earlier record and keep the slot.
     */
    @Test
    fun `after a reset the new primary takes over even when listed first`() {
        val h = Harness(slot)
        h.offer(oldKey, isPrimary = true)                     // before the reset: old key is primary
        assertEquals(oldKey, h.ring)
        h.offer(newKey, isPrimary = true)                     // reset: new primary listed first
        h.offer(oldKey, isPrimary = false)                    // demoted old key listed after it
        assertEquals("the uploads of this very sync must use the new primary", newKey, h.ring)
    }

    /** The active-keys listing drops the old key after a reset, so it is never re-offered. */
    @Test
    fun `after a reset the new primary takes over even if the old key is never offered again`() {
        val h = Harness(slot)
        h.offer(oldKey, isPrimary = true)
        h.offer(newKey, isPrimary = true)                     // the listing now holds only the new key
        assertEquals(newKey, h.ring)
        val attempts = h.unlockAttempts
        h.offer(newKey, isPrimary = true)                     // every later sync
        assertEquals(newKey, h.ring)
        assertEquals("and it is settled, not re-unlocked on every call", attempts, h.unlockAttempts)
    }

    @Test
    fun `a primary key demoted with no replacement keeps the slot without a ring change`() {
        val h = Harness(slot)
        h.offer(oldKey, isPrimary = true)
        h.offer(oldKey, isPrimary = false)
        assertEquals(oldKey, h.ring)
        assertTrue(h.selection.isSettled(oldKey, slot, isPrimary = false, slotHasRing = true))
    }

    @Test
    fun `with no primary key at all the first key unlocked keeps the slot`() {
        val h = Harness(slot)
        h.offer(oldKey, isPrimary = false)
        h.offer("acct:3333", isPrimary = false)
        assertEquals(oldKey, h.ring)
    }

    @Test
    fun `once settled, repeating the unlock is skipped`() {
        val h = Harness(slot)
        h.offer(newKey, isPrimary = true)
        h.offer(oldKey, isPrimary = false)
        val attempts = h.unlockAttempts
        h.offer(newKey, isPrimary = true)
        h.offer(oldKey, isPrimary = false)
        assertEquals(attempts, h.unlockAttempts)
    }

    /** No ring means nothing is settled, whatever was recorded. */
    @Test
    fun `a missing ring is never settled`() {
        val h = Harness(slot)
        h.offer(newKey, isPrimary = true)
        assertFalse(h.selection.isSettled(newKey, slot, isPrimary = true, slotHasRing = false))
        assertTrue(h.selection.takesRing(newKey, slot, isPrimary = true, slotHasRing = false))
    }

    /**
     * A sign-out landing between the account ring and the calendar ring lock used to
     * leave the key recorded. After signing back in it counted as settled, was never put back into
     * the new account ring, and every event encrypted to it stopped decrypting until a restart.
     */
    @Test
    fun `an unlock that finishes after sign-out is not remembered`() {
        val h = Harness(slot)
        h.offer(newKey, isPrimary = true, afterAccountRing = { h.signOut() })
        assertEquals(null, h.ring)
        h.signedIn = true                                     // signs back in, same process
        val attempts = h.unlockAttempts
        h.offer(newKey, isPrimary = true)
        assertEquals("the key must be unlocked again", attempts + 1, h.unlockAttempts)
        assertTrue(newKey in h.accountRingKeys)
        assertEquals(newKey, h.ring)
    }

    /** Even a record that survived somehow cannot skip the unlock once the account ring lacks the key. */
    @Test
    fun `a recorded key missing from the account ring is unlocked again`() {
        val h = Harness(slot)
        h.offer(newKey, isPrimary = true)
        h.accountRingKeys.clear()
        val attempts = h.unlockAttempts
        h.offer(newKey, isPrimary = true)
        assertEquals(attempts + 1, h.unlockAttempts)
    }

    @Test
    fun `clearing one account leaves another untouched`() {
        val s = CalendarKeySelection()
        s.recordUnlocked(newKey, slot, isPrimary = true, tookRing = true)
        s.recordUnlocked("other:9999", "other:cal:C1", isPrimary = true, tookRing = true)
        s.clearAccount("acct")
        assertFalse(s.isSettled(newKey, slot, isPrimary = true, slotHasRing = true))
        assertTrue(s.isSettled("other:9999", "other:cal:C1", isPrimary = true, slotHasRing = true))
    }
}

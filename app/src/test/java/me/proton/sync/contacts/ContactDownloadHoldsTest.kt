package me.proton.sync.contacts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contacts too large for the phone's contacts storage, and the rule that decides when the download
 * skips them. A wrong skip is not harmless: skipping a contact with a pending local edit would let the
 * upload replace Proton's complete version with the phone's older one.
 */
class ContactDownloadHoldsTest {

    private val account = "acct"

    @Test
    fun `holds round-trip and unreadable entries are dropped`() {
        val parsed = ContactDownloadHolds.parse(
            setOf("100:update:abc==", "200:new:x-_y", "bad", "x:new:id", "5:other:id", "7:new:", "9:new:a:b")
        )
        assertEquals(
            mapOf(
                "abc==" to ContactDownloadHolds.Hold("abc==", 100, onPhone = true),
                "x-_y" to ContactDownloadHolds.Hold("x-_y", 200, onPhone = false),
                "a:b" to ContactDownloadHolds.Hold("a:b", 9, onPhone = false),
            ),
            parsed
        )
        assertEquals(parsed, ContactDownloadHolds.parse(ContactDownloadHolds.format(parsed.values)))
        assertEquals(emptyMap<String, ContactDownloadHolds.Hold>(), ContactDownloadHolds.parse(null))
    }

    @Test
    fun `skipped only at the held time, in the held situation, with no local edit`() {
        val update = ContactDownloadHolds.Hold("p", 100, onPhone = true)
        assertTrue(ContactDownloadHolds.skips(update, 100, hasLocalRow = true, localEditPending = false))
        // Changed on Proton: try again.
        assertFalse(ContactDownloadHolds.skips(update, 101, hasLocalRow = true, localEditPending = false))
        // A local edit must reach the conflict check instead of being uploaded over Proton's version.
        assertFalse(ContactDownloadHolds.skips(update, 100, hasLocalRow = true, localEditPending = true))
        // Held as an update, but the local row is gone (a forced reload): a new contact is worth a try.
        assertFalse(ContactDownloadHolds.skips(update, 100, hasLocalRow = false, localEditPending = false))
        val new = ContactDownloadHolds.Hold("p", 100, onPhone = false)
        assertTrue(ContactDownloadHolds.skips(new, 100, hasLocalRow = false, localEditPending = false))
        assertFalse(ContactDownloadHolds.skips(null, 100, hasLocalRow = false, localEditPending = false))
    }

    @Test
    fun `hold, release and retain keep the store per account`() {
        val prefs = FakePrefs()
        ContactDownloadHolds.hold(prefs, account, ContactDownloadHolds.Hold("a", 1, true))
        ContactDownloadHolds.hold(prefs, account, ContactDownloadHolds.Hold("b", 2, false))
        ContactDownloadHolds.hold(prefs, account, ContactDownloadHolds.Hold("a", 3, true))
        ContactDownloadHolds.hold(prefs, "other", ContactDownloadHolds.Hold("c", 4, false))
        assertEquals(setOf("a", "b"), ContactDownloadHolds.all(prefs, account).keys)
        assertEquals(3L, ContactDownloadHolds.all(prefs, account).getValue("a").modifyTime)

        ContactDownloadHolds.release(prefs, account, "a")
        assertEquals(setOf("b"), ContactDownloadHolds.all(prefs, account).keys)

        ContactDownloadHolds.retain(prefs, account) { it != "b" }
        assertEquals(emptyMap<String, ContactDownloadHolds.Hold>(), ContactDownloadHolds.all(prefs, account))
        assertEquals(setOf("c"), ContactDownloadHolds.all(prefs, "other").keys)
    }
}

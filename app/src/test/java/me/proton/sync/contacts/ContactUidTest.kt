package me.proton.sync.contacts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * The UID a contact gets, and the one an upload keeps.
 *
 * A new contact's UID is `urn:uuid:` and a random version 4 UUID, and names no domain: before 2.0.0
 * it was `<base64url>` plus Proton's domain, Proton's calendar event format. An upload of a contact
 * Proton already has keeps the UID Proton stores; it used to mint a new one at every phone-side edit.
 */
class ContactUidTest {

    @Test
    fun `a new contact's uid is a urn uuid with a random version 4 uuid`() {
        val uid = newContactUid()
        assertTrue(uid, uid.startsWith("urn:uuid:"))
        val uuid = UUID.fromString(uid.removePrefix("urn:uuid:"))
        assertEquals(4, uuid.version())
        assertEquals(2, uuid.variant())
        assertEquals("canonical lowercase layout", "urn:uuid:$uuid", uid)
        assertFalse(uid, uid.contains("proton", ignoreCase = true))
        assertFalse(uid, uid.contains("@"))
    }

    @Test
    fun `new uids do not repeat`() {
        assertEquals(1000, List(1000) { newContactUid() }.toSet().size)
    }

    @Test
    fun `an upload keeps the uid proton stores, whatever its format`() {
        assertEquals("proton-web-0f1e2d3c", uploadUidFor("proton-web-0f1e2d3c", "urn:uuid:from-the-card"))
        assertEquals("proton-android-1234", uploadUidFor("proton-android-1234", null))
    }

    @Test
    fun `without a stored uid the card's uid is kept`() {
        assertEquals("urn:uuid:from-the-card", uploadUidFor(null, "urn:uuid:from-the-card"))
        assertEquals("urn:uuid:from-the-card", uploadUidFor("  ", "urn:uuid:from-the-card"))
    }

    @Test
    fun `a new uid only when there is neither`() {
        assertTrue(uploadUidFor(null, null).startsWith("urn:uuid:"))
        assertTrue(uploadUidFor("", " ").startsWith("urn:uuid:"))
    }
}

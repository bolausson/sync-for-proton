package me.proton.sync.backup

import ezvcard.Ezvcard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The card that could empty a whole backup.
 *
 * A backup failed, reporting only "Backup failed: IllegalArgumentException". The cause was an
 * asymmetry in ez-vcard: its READER accepts property and group names its own WRITER rejects. One
 * such card threw on the way out, and because the write sat outside the per-contact try and
 * nothing reached disk until every builder had finished, that single card cost the whole backup,
 * contacts AND calendars, on every scheduled run, deterministically.
 *
 * These tests run against the real library rather than a description of it, because the whole
 * defect is a disagreement between two halves of that library about what is legal. A test that
 * mocked the writer would have agreed with the reader and proved nothing.
 */
class VCardRepairTest {

    private fun card(vararg extraLines: String) = Ezvcard.parse(
        buildString {
            append("BEGIN:VCARD\r\n")
            append("VERSION:4.0\r\n")
            append("FN:Jan Jansen\r\n")
            extraLines.forEach { append(it).append("\r\n") }
            append("END:VCARD\r\n")
        }
    ).first()

    /** Two dots before the colon: group `x`, property name `y.z`, which the writer refuses. */
    @Test
    fun `a name the reader accepted and the writer refuses is repaired`() {
        val c = card("x.y.z:kapot")
        try {
            Ezvcard.write(c).go()
            fail("expected ez-vcard's writer to reject this card; if it no longer does, this guard can go")
        } catch (e: IllegalArgumentException) {
            // exactly the exception the failed backup reported, with the message we now keep
            assertTrue(e.message.orEmpty().isNotBlank())
        }
        assertTrue("repair should have changed something", VCardRepair.repair(c) > 0)
        val out = Ezvcard.write(c).go()
        assertTrue("the contact itself must survive the repair", out.contains("FN:Jan Jansen"))
    }

    /** A property with no name left at all is the one case where the property has to go. */
    @Test
    fun `an empty property name drops that property and keeps the contact`() {
        val c = card("item1.:leeg")
        VCardRepair.repair(c)
        val out = Ezvcard.write(c).go()
        assertTrue(out.contains("FN:Jan Jansen"))
    }

    /** A doubled group, common in cards exported from Apple Contacts. */
    @Test
    fun `a doubled group is repaired`() {
        val c = card("item1.item1.X-ABLabel:Werk")
        VCardRepair.repair(c)
        Ezvcard.write(c).go() // must not throw
    }

    // ---- and the half that must not change: an ordinary card is left completely alone ----

    @Test
    fun `a healthy card is not touched`() {
        val c = card("EMAIL:jan@example.com", "TEL:+447700900123", "item1.X-ABLabel:Werk")
        val before = Ezvcard.write(c).go()
        assertEquals("nothing to repair", 0, VCardRepair.repair(c))
        assertEquals(before, Ezvcard.write(c).go())
    }

    /** Values are the user's data. Whatever is in them, repair must not reach into them. */
    @Test
    fun `values are never altered`() {
        val c = card("NOTE:a.b.c: punctuation; and, delimiters", "x.y.z:kapot")
        VCardRepair.repair(c)
        val out = Ezvcard.write(c).go()
        assertTrue("the note's punctuation must survive verbatim", out.contains("a.b.c"))
    }
}

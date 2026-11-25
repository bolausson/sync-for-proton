package me.proton.sync.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which rows came from Proton ([PhoneOnlyRows]): the rule the forced Remote to Local wipe has always used, now also
 * the bulk-deletion restore's and the removed calendar's, so an event that exists only on this phone (not sent yet,
 * waiting for the user's choice, kept here) is never deleted with Proton's.
 */
class PhoneOnlyRowsTest {

    @Test
    fun `a mapping or a Proton id makes a row Proton's, nothing else does`() {
        assertTrue(PhoneOnlyRows.isFromProton(5, null, null, setOf(5L)))
        assertTrue(PhoneOnlyRows.isFromProton(5, "proton-5", null, emptySet()))
        assertTrue(PhoneOnlyRows.isFromProton(5, null, "proton-parent", emptySet()))
        assertFalse(PhoneOnlyRows.isFromProton(5, null, null, emptySet()))
        assertFalse(PhoneOnlyRows.isFromProton(5, "", " ", setOf(6L)))
    }

    @Test
    fun `a removed calendar stays while it holds rows of its own, or their number is unknown`() {
        assertFalse(PhoneOnlyRows.keepRemovedCalendarRow(0))
        assertTrue(PhoneOnlyRows.keepRemovedCalendarRow(1))
        assertTrue(PhoneOnlyRows.keepRemovedCalendarRow(null))
    }

    @Test
    fun `a kept calendar goes once nothing keeps it, and not before`() {
        // The shared calendar "team-1" was removed on Proton while an event waited in it; the user moved the event.
        fun goes(
            listed: Set<String> = setOf("own-1"),
            rowMapped: Boolean = false,
            keptEvents: Int = 0,
            liveRows: Int? = 0,
            phoneOnlyRows: Int? = 0,
        ) = PhoneOnlyRows.removeKeptCalendarRow("team-1", listed, rowMapped, keptEvents, liveRows, phoneOnlyRows)
        assertTrue(goes())
        // Shared again (its sync takes the row back), or a calendar maps the row.
        assertFalse(goes(listed = setOf("own-1", "team-1")))
        assertFalse(goes(rowMapped = true))
        // Events kept there with "Keep on phone" still have their mappings.
        assertFalse(goes(keptEvents = 1))
        // Anything left in it: a row of its own (waiting, kept, never sent), one of Proton's an app moved in, or a
        // count that could not be read.
        assertFalse(goes(phoneOnlyRows = 1))
        assertFalse(goes(liveRows = 1))
        assertFalse(goes(liveRows = null))
        assertFalse(goes(phoneOnlyRows = null))
    }

    @Test
    fun `the two selections name both Proton ids and are each other's complement`() {
        for (sql in listOf(PhoneOnlyRows.FROM_PROTON_SQL, PhoneOnlyRows.PHONE_ONLY_SQL)) {
            assertTrue(sql, sql.contains("COALESCE(_sync_id,'')"))
            assertTrue(sql, sql.contains("COALESCE(original_sync_id,'')"))
        }
        assertEquals(
            "(TRIM(COALESCE(_sync_id,'')) != '' OR TRIM(COALESCE(original_sync_id,'')) != '')",
            PhoneOnlyRows.FROM_PROTON_SQL
        )
        assertEquals(
            "TRIM(COALESCE(_sync_id,'')) = '' AND TRIM(COALESCE(original_sync_id,'')) = ''",
            PhoneOnlyRows.PHONE_ONLY_SQL
        )
    }
}

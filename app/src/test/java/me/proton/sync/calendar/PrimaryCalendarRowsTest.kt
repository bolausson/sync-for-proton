package me.proton.sync.calendar

import me.proton.sync.calendar.PrimaryCalendarChooser.Decision
import me.proton.sync.calendar.PrimaryCalendarChooser.Reason
import me.proton.sync.calendar.PrimaryCalendarChooser.UnknownReason
import me.proton.sync.calendar.PrimaryCalendarRows.ChosenRow
import me.proton.sync.calendar.PrimaryCalendarRows.StoredRow
import me.proton.sync.calendar.PrimaryCalendarRows.Target
import me.proton.sync.calendar.PrimaryCalendarRows.Write
import me.proton.sync.db.entities.CalendarSyncEntity
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The writes that bring one account's calendar rows to a primary-calendar decision: 1 on the chosen row, an
 * explicit 0 on every other row of that account, nothing for an unknown decision, never NULL, and never a row of
 * another account or another app.
 */
class PrimaryCalendarRowsTest {

    private companion object {
        const val ACCOUNT = "user@example.test"
        const val TYPE = "app.account.type"
    }

    private fun row(id: Long, stored: Int?, account: String = ACCOUNT, type: String = TYPE) = StoredRow(id, account, type, stored)

    private fun plan(target: Target, rows: List<StoredRow>) = PrimaryCalendarRows.plan(ACCOUNT, TYPE, target, rows)

    @Test
    fun `a known calendar stores 1 and every other row of the account 0`() {
        val rows = listOf(row(10, null), row(11, 1), row(12, 0), row(13, null))

        assertEquals(
            listOf(Write(10, 0), Write(11, 0), Write(12, 1), Write(13, 0)),
            plan(Target.Primary(12), rows)
        )
    }

    @Test
    fun `rows that already store the right value are not written`() {
        val rows = listOf(row(10, 0), row(11, 1), row(12, 0))

        assertEquals(emptyList<Write>(), plan(Target.Primary(11), rows))
    }

    @Test
    fun `no primary calendar clears every row of the account`() {
        val rows = listOf(row(10, 1), row(11, null), row(12, 0), row(13, 1))

        assertEquals(listOf(Write(10, 0), Write(11, 0), Write(13, 0)), plan(Target.Primary(null), rows))
    }

    @Test
    fun `an unknown decision writes nothing at all`() {
        val rows = listOf(row(10, 1), row(11, null), row(12, 1))

        assertEquals(emptyList<Write>(), plan(Target.Leave, rows))
    }

    @Test
    fun `NULL is never written`() {
        val rows = listOf(row(1, null), row(2, 1), row(3, 0), row(4, null), row(5, 7))
        for (target in listOf(Target.Primary(1), Target.Primary(2), Target.Primary(5), Target.Primary(99), Target.Primary(null))) {
            for (write in plan(target, rows)) {
                assertTrue("$target wrote ${write.value}", write.value == 0 || write.value == 1)
            }
        }
        // A stored value other than 0 or 1 reads as "not 0", so it is rewritten.
        assertEquals(listOf(Write(5, 0)), plan(Target.Primary(2), listOf(row(2, 1), row(5, 7))))
    }

    @Test
    fun `rows of other accounts and other apps are never written`() {
        // read() keeps each row's own ACCOUNT_NAME and ACCOUNT_TYPE from the cursor, so this filter is a second guard
        // behind the account clause of the selection (pinned below), not a comparison of the account with itself.
        val rows = listOf(
            row(10, 1),
            row(11, null, account = "other@example.test"),
            row(12, null, account = ACCOUNT, type = "com.example.other"),
            row(13, 1, account = "other@example.test", type = "com.example.other"),
        )

        assertEquals(listOf(Write(10, 0)), plan(Target.Primary(null), rows))
        assertEquals(listOf(Write(10, 0)), plan(Target.Primary(11), rows))
        assertEquals(listOf(Write(10, 0)), plan(Target.Primary(13), rows))
    }

    @Test
    fun `a chosen row that is not the account's own gets no 1 and the account has no primary`() {
        val rows = listOf(row(10, 1), row(11, 0))

        assertEquals(listOf(Write(10, 0)), plan(Target.Primary(99), rows))
    }

    @Test
    fun `the target follows the decision and where its calendar sits on the phone`() {
        val mappings = listOf("Home" to 12L, "Work" to null, "Team" to 14L)
        val live = setOf("Home", "Work", "Team")
        fun target(decision: Decision) = PrimaryCalendarRows.targetFor(
            decision,
            (decision as? Decision.Known)?.protonCalendarId?.let { PrimaryCalendarRows.locate(it, mappings, live) }
        )

        assertEquals(Target.Primary(12), target(Decision.Known("Home", Reason.PROTON_DEFAULT)))
        // Chosen, but its row could not be created: no row is primary, which is true of the phone.
        assertEquals(Target.Primary(null), target(Decision.Known("Work", Reason.DEFAULT_NOT_SET)))
        assertEquals(Target.Primary(null), target(Decision.Known("Gone", Reason.DEFAULT_NOT_SET)))
        assertEquals(Target.Primary(null), target(Decision.Known(null, Reason.NO_ACTIVE_OWN_CALENDAR)))
        assertEquals(Target.Leave, target(Decision.Unknown(UnknownReason.SETTINGS_FAILED, "HTTP 500")))
        assertEquals(Target.Leave, target(Decision.Unknown(UnknownReason.PARTIAL_ADDRESSES, "1 calendar")))
    }

    @Test
    fun `a mapping read that failed writes nothing, while no mapping means no row`() {
        val home = Decision.Known("Home", Reason.PROTON_DEFAULT)

        // A failed read is not "no row": that cleared the 1 the calendar loop had just written on the chosen row.
        assertEquals(ChosenRow.Unreadable, PrimaryCalendarRows.locate("Home", null, setOf("Home")))
        assertEquals(Target.Leave, PrimaryCalendarRows.targetFor(home, ChosenRow.Unreadable))
        assertEquals(Target.Leave, PrimaryCalendarRows.targetFor(home, null))
        // A read that worked and found nothing.
        assertEquals(ChosenRow.Found(null), PrimaryCalendarRows.locate("Home", emptyList(), setOf("Home")))
        assertEquals(Target.Primary(null), PrimaryCalendarRows.targetFor(home, ChosenRow.Found(null)))
        // A decision that chose none needs no row.
        assertEquals(Target.Primary(null), PrimaryCalendarRows.targetFor(Decision.Known(null, Reason.NO_ACTIVE_OWN_CALENDAR), ChosenRow.Unreadable))
    }

    @Test
    fun `a row the chosen calendar shares with another listed calendar is never primary`() {
        // An older version's reuse by name put a shared calendar called like the own one into the same row.
        val mappings = listOf("Home" to 12L, "Team" to 12L, "Old" to 13L, "Gone" to 13L)

        assertEquals(ChosenRow.Found(12, sharedWith = 1), PrimaryCalendarRows.locate("Home", mappings, setOf("Home", "Team", "Old")))
        assertEquals(Target.Primary(null), PrimaryCalendarRows.targetFor(Decision.Known("Home", Reason.PROTON_DEFAULT), ChosenRow.Found(12, 1)))
        // A calendar no longer on Proton does not count: that row is the chosen calendar's own.
        assertEquals(ChosenRow.Found(13, sharedWith = 0), PrimaryCalendarRows.locate("Old", mappings, setOf("Home", "Team", "Old")))
        assertEquals(Target.Primary(13), PrimaryCalendarRows.targetFor(Decision.Known("Old", Reason.PROTON_DEFAULT), ChosenRow.Found(13)))
    }

    @Test
    fun `whether the chosen row is one of the account's own`() {
        val rows = listOf(row(10, 0), row(11, 1), row(12, null, account = "other@example.test"))

        assertTrue(PrimaryCalendarRows.chosenRowFound(ACCOUNT, TYPE, Target.Primary(11), rows))
        // The provider may have handed the mapping's row id to another account's calendar.
        assertFalse(PrimaryCalendarRows.chosenRowFound(ACCOUNT, TYPE, Target.Primary(12), rows))
        assertFalse(PrimaryCalendarRows.chosenRowFound(ACCOUNT, TYPE, Target.Primary(99), rows))
        assertTrue(PrimaryCalendarRows.chosenRowFound(ACCOUNT, TYPE, Target.Primary(null), rows))
        assertTrue(PrimaryCalendarRows.chosenRowFound(ACCOUNT, TYPE, Target.Leave, rows))
    }

    @Test
    fun `every read names the account by both its name and its type`() {
        // CALENDARS_ID updates write WHERE _id = ? only, so this clause is what keeps other accounts' rows out.
        assertEquals("account_name = ? AND account_type = ?", PrimaryCalendarRows.selection(null))
        assertEquals("account_name = ? AND account_type = ? AND (isPrimary = 1)", PrimaryCalendarRows.selection("isPrimary = 1"))
        assertEquals(
            "account_name = ? AND account_type = ? AND (isPrimary = 0 OR isPrimary IS NULL)",
            PrimaryCalendarRows.selection("isPrimary = 0 OR isPrimary IS NULL")
        )
        assertArrayEquals(arrayOf(ACCOUNT, TYPE), PrimaryCalendarRows.selectionArgs(ACCOUNT, TYPE))
    }

    @Test
    fun `the calendar loop writes the decision, a 0 on shared rows, and nothing on rows that are not the account's`() {
        val result = PrimaryCalendarChooser.Result(emptyList(), Decision.Known("Home", Reason.PROTON_DEFAULT))
        val run = PrimaryCalendarRun.of(
            result,
            localCalendarsByName = mapOf("Home" to listOf(10L), "Work" to listOf(11L, 12L)),
            mappings = listOf(entity("Home", 10), entity("Work", 11), entity("Team", 11), entity("Gone", 12)),
            liveProtonIds = setOf("Home", "Work", "Team"),
        )

        assertEquals(true, run.forExistingRow("Home", 10))
        // Row 11 holds two listed calendars, so neither may make it primary.
        assertEquals(false, run.forExistingRow("Work", 11))
        assertEquals(false, run.forExistingRow("Team", 11))
        // The calendar that is gone does not make row 12 shared.
        assertEquals(false, run.forExistingRow("Work", 12))
        // A mapped row id that is not one of the account's rows gets no IS_PRIMARY from the loop.
        assertNull(run.forExistingRow("Home", 77))
        assertEquals(true, run.forNewRow("Home"))
        assertEquals(false, run.forNewRow("Team"))
    }

    @Test
    fun `while the decision is unknown a new row stores 0 and a candidate's row keeps its value`() {
        val unknown = PrimaryCalendarChooser.Result(
            emptyList(), Decision.Unknown(UnknownReason.SETTINGS_FAILED, "HTTP 503"), neverPrimary = setOf("Feed")
        )
        val run = PrimaryCalendarRun.of(unknown, mapOf("Home" to listOf(10L), "Feed" to listOf(11L)), emptyList(), setOf("Home", "Feed"))

        assertEquals(false, run.forNewRow("Home"))
        assertNull(run.forExistingRow("Home", 10))
        assertEquals(false, run.forExistingRow("Feed", 11))
    }

    private fun entity(protonId: String, androidId: Long?) =
        CalendarSyncEntity(accountName = ACCOUNT, protonCalendarId = protonId, androidCalendarId = androidId, displayName = protonId)
}

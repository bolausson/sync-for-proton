package me.proton.sync.calendar

import me.proton.sync.calendar.PrimaryCalendarChooser.Decision
import me.proton.sync.calendar.PrimaryCalendarChooser.Reason
import me.proton.sync.calendar.PrimaryCalendarChooser.UnknownReason
import me.proton.sync.calendar.PrimaryCalendarMemory.Last
import me.proton.sync.calendar.PrimaryCalendarPicks.Applied
import me.proton.sync.calendar.PrimaryCalendarPicks.NoRow
import me.proton.sync.calendar.PrimaryCalendarRows.ChosenRow
import me.proton.sync.calendar.PrimaryCalendarRows.Target
import me.proton.sync.calendar.PrimaryCalendarRows.Write
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/**
 * Where a choice made on the Default calendar row meets a calendar sync: per account, the last pick and its generation;
 * the settle pass's plan, which keeps a pick made since the sync read Proton's settings and otherwise writes what
 * PrimaryCalendarRows.targetFor decides; what a pick does to the rows (PrimaryCalendarPicks.planned, the rules
 * applyOutsideSync follows); and when the row's load brings the phone in line. Made-up account and calendar names only.
 */
class PrimaryCalendarPicksTest {

    /** The object is process-wide, so every test takes account names no other test uses. */
    private fun account() = "user-${UUID.randomUUID()}@example.test"

    private fun pick(account: String, id: String, name: String = id, reason: Reason = Reason.PICKED_HERE) =
        synchronized(PrimaryCalendarPicks.lock(account)) { PrimaryCalendarPicks.record(account, id, name, reason.name) }

    private fun at(vararg rows: Pair<String, ChosenRow>): (String) -> ChosenRow {
        val map = rows.toMap()
        return { id -> map[id] ?: ChosenRow.Found(null) }
    }

    // ---- in-memory state ----

    @Test
    fun `without a pick there is no generation and nothing newer`() {
        val a = account()

        assertEquals(0L, PrimaryCalendarPicks.generation(a))
        assertNull(PrimaryCalendarPicks.since(a, 0L))
    }

    @Test
    fun `a pick is newer only than what was read before it`() {
        val a = account()
        val before = PrimaryCalendarPicks.generation(a)
        val first = pick(a, "Home")

        assertEquals(first, PrimaryCalendarPicks.since(a, before))
        assertEquals(first.generation, PrimaryCalendarPicks.generation(a))
        // A sync that read the generation after the pick: the pick is not newer.
        assertNull(PrimaryCalendarPicks.since(a, PrimaryCalendarPicks.generation(a)))

        val second = pick(a, "Work")
        assertTrue(second.generation > first.generation)
        assertEquals(second, PrimaryCalendarPicks.since(a, before))
        assertEquals(second, PrimaryCalendarPicks.since(a, first.generation))
        assertNull(PrimaryCalendarPicks.since(a, second.generation))
        assertEquals(PrimaryCalendarPicks.Pick(second.generation, "Work", "Work", Reason.PICKED_HERE.name), second)
    }

    @Test
    fun `accounts are kept apart`() {
        val a = account()
        val b = account()
        pick(a, "Home")

        assertEquals(0L, PrimaryCalendarPicks.generation(b))
        assertNull(PrimaryCalendarPicks.since(b, 0L))
        val other = pick(b, "Work")
        assertEquals("Home", PrimaryCalendarPicks.since(a, 0L)?.protonCalendarId)
        assertEquals(other, PrimaryCalendarPicks.since(b, 0L))
    }

    @Test
    fun `one lock per account`() {
        val a = account()
        val b = account()

        assertSame(PrimaryCalendarPicks.lock(a), PrimaryCalendarPicks.lock(a))
        assertNotSame(PrimaryCalendarPicks.lock(a), PrimaryCalendarPicks.lock(b))
    }

    // ---- what a pick does to the rows ----

    @Test
    fun `a pick follows the row writer's rules for its row`() {
        assertEquals(Target.Primary(41L), PrimaryCalendarPicks.targetForPick(ChosenRow.Found(41L)))
        // A row another listed calendar maps to is never primary.
        assertEquals(Target.Primary(null), PrimaryCalendarPicks.targetForPick(ChosenRow.Found(41L, sharedWith = 1)))
        // Not on the phone yet, or not known where: nothing moves, so the phone keeps its main calendar.
        assertEquals(Target.Leave, PrimaryCalendarPicks.targetForPick(ChosenRow.Found(null)))
        assertEquals(Target.Leave, PrimaryCalendarPicks.targetForPick(ChosenRow.Unreadable))
    }

    // ---- settle ----

    @Test
    fun `a pick made during the sync is kept, with its row`() {
        val a = account()
        val p = pick(a, "Home", "Home")

        val settle = PrimaryCalendarPicks.settle(Decision.Known("Work", Reason.PROTON_DEFAULT), "Work", p, at("Home" to ChosenRow.Found(41L)))!!
        assertEquals(Target.Primary(41L), settle.target)
        assertEquals(Last("Home", 41L, Reason.PICKED_HERE.name, "Home"), settle.last)
        assertTrue(settle.keptPick)
    }

    @Test
    fun `a kept pick without a row records none, and the rows follow the run's own decision`() {
        val p = pick(account(), "New", "New calendar")

        // The loop wrote Home's row 1 for this run; the clean-up still reaches the rows the loop did not touch (a row
        // of a calendar gone from Proton that kept its 1), so only Home's row stores 1.
        val settle = PrimaryCalendarPicks.settle(Decision.Known("Home", Reason.PROTON_DEFAULT), "Home", p, at("Home" to ChosenRow.Found(41L)))!!
        assertEquals(Target.Primary(41L), settle.target)
        assertEquals(Last("New", null, Reason.PICKED_HERE.name, "New calendar"), settle.last)
        assertTrue(settle.keptPick)
        assertTrue(settle.followsRun)
        assertEquals(ChosenRow.Found(null), settle.chosen)
    }

    @Test
    fun `a kept pick without a row leaves the rows when the run's own decision marks none`() {
        val p = pick(account(), "New", "New calendar")

        for (decision in listOf(
            // The run's calendar has no row either, or shares its row: the run would make no row primary.
            Decision.Known("Home", Reason.PROTON_DEFAULT) to at(),
            Decision.Known("Home", Reason.PROTON_DEFAULT) to at("Home" to ChosenRow.Found(41L, sharedWith = 1)),
            Decision.Known(null, Reason.NO_ACTIVE_OWN_CALENDAR) to at(),
            Decision.Unknown(UnknownReason.SETTINGS_FAILED, "HTTP 503") to at("Home" to ChosenRow.Found(41L)),
        )) {
            val settle = PrimaryCalendarPicks.settle(decision.first, "Home", p, decision.second)!!
            assertEquals("${decision.first}", Target.Leave, settle.target)
            assertFalse("${decision.first}", settle.followsRun)
            assertEquals("${decision.first}", Last("New", null, Reason.PICKED_HERE.name, "New calendar"), settle.last)
        }
        // A pick whose calendar has a row never follows the run.
        val onPhone = PrimaryCalendarPicks.settle(
            Decision.Known("Home", Reason.PROTON_DEFAULT), "Home", p, at("New" to ChosenRow.Found(43L), "Home" to ChosenRow.Found(41L))
        )!!
        assertEquals(Target.Primary(43L), onPhone.target)
        assertFalse(onPhone.followsRun)
    }

    @Test
    fun `a kept pick without a row follows a run whose row is another app's, which leaves no row primary`() {
        val p = pick(account(), "New", "New calendar")

        // Home's mapping names row 77, which another app's calendar holds now (Calendar Storage was cleared). The
        // settle pass cannot tell, so the rows follow the run's own decision...
        val settle = PrimaryCalendarPicks.settle(Decision.Known("Home", Reason.PROTON_DEFAULT), "Home", p, at("Home" to ChosenRow.Found(77L)))!!
        assertEquals(Target.Primary(77L), settle.target)
        assertTrue(settle.followsRun)
        assertNull(settle.last.androidCalId)
        // ...and the row writer writes only the account's rows: none stores 1, and the chosen row is not found, which
        // the settle pass logs as no row primary (the wiring test below).
        val rows = listOf(row(41, 1), row(43, 0), row(77, 0, account = "other@example.test"))
        assertEquals(listOf(Write(41, 0)), PrimaryCalendarRows.plan(OWNER, TYPE, settle.target, rows))
        assertFalse(PrimaryCalendarRows.chosenRowFound(OWNER, TYPE, settle.target, rows))
    }

    @Test
    fun `a pick is kept over a decision the sync could not make`() {
        val p = pick(account(), "Home", reason = Reason.PROTON_DEFAULT)
        val unknown = Decision.Unknown(UnknownReason.SETTINGS_FAILED, "HTTP 503")

        val settle = PrimaryCalendarPicks.settle(unknown, null, p, at("Home" to ChosenRow.Found(41L)))!!
        assertEquals(Target.Primary(41L), settle.target)
        assertEquals(Last("Home", 41L, Reason.PROTON_DEFAULT.name, "Home"), settle.last)
        assertTrue(settle.keptPick)
    }

    @Test
    fun `a kept pick whose row two listed calendars share makes no row primary, and unreadable mappings move nothing`() {
        val p = pick(account(), "Home")

        val shared = PrimaryCalendarPicks.settle(Decision.Known("Work", Reason.PROTON_DEFAULT), "Work", p, at("Home" to ChosenRow.Found(41L, sharedWith = 1)))!!
        assertEquals(Target.Primary(null), shared.target)
        assertNull(shared.last.androidCalId)

        val unreadable = PrimaryCalendarPicks.settle(Decision.Known("Work", Reason.PROTON_DEFAULT), "Work", p) { ChosenRow.Unreadable }!!
        assertEquals(Target.Leave, unreadable.target)
        assertEquals(ChosenRow.Unreadable, unreadable.chosen)
        assertFalse(unreadable.followsRun)
    }

    @Test
    fun `without a pick a known decision is settled as the row writer's targetFor decides`() {
        val known = Decision.Known("Home", Reason.DEFAULT_NOT_SET)

        val onPhone = PrimaryCalendarPicks.settle(known, "Home", null, at("Home" to ChosenRow.Found(41L)))!!
        assertEquals(Target.Primary(41L), onPhone.target)
        assertEquals(Last("Home", 41L, Reason.DEFAULT_NOT_SET.name, "Home"), onPhone.last)
        assertFalse(onPhone.keptPick)
        assertEquals(PrimaryCalendarRows.targetFor(known, ChosenRow.Found(41L)), onPhone.target)

        // No row yet: no row is primary (the calendar loop wrote 0 on every row it touched for this decision).
        val notYet = PrimaryCalendarPicks.settle(known, "Home", null, at())!!
        assertEquals(Target.Primary(null), notYet.target)
        assertEquals(Last("Home", null, Reason.DEFAULT_NOT_SET.name, "Home"), notYet.last)

        val none = PrimaryCalendarPicks.settle(Decision.Known(null, Reason.NO_ACTIVE_OWN_CALENDAR), null, null, at())!!
        assertEquals(Target.Primary(null), none.target)
        assertEquals(Last(null, null, Reason.NO_ACTIVE_OWN_CALENDAR.name, null), none.last)
        assertNull(none.chosen)
    }

    @Test
    fun `without a pick an unknown decision writes nothing`() {
        assertNull(
            PrimaryCalendarPicks.settle(Decision.Unknown(UnknownReason.NO_OWN_MEMBER, "x"), null, null, at("Home" to ChosenRow.Found(41L)))
        )
    }

    @Test
    fun `the sync after a pick takes it as Proton's default without a move`() {
        val previous = Last("Home", 41L, Reason.PICKED_HERE.name, "Home")
        val next = PrimaryCalendarPicks.settle(Decision.Known("Home", Reason.PROTON_DEFAULT), "Home", null, at("Home" to ChosenRow.Found(41L)))!!

        assertFalse(PrimaryCalendarMemory.moved(previous, next.last))
        assertEquals("Home", next.last.protonCalendarId)
        assertEquals(Reason.PROTON_DEFAULT.name, next.last.reason)
        assertEquals(41L, next.last.androidCalId)
    }

    @Test
    fun `a sync that read the settings before a pick keeps the pick and logs no move`() {
        val a = account()
        val readBefore = PrimaryCalendarPicks.generation(a)
        pick(a, "Home")
        val stale = Decision.Known("Work", Reason.PROTON_DEFAULT)

        val settle = PrimaryCalendarPicks.settle(stale, "Work", PrimaryCalendarPicks.since(a, readBefore), at("Home" to ChosenRow.Found(41L), "Work" to ChosenRow.Found(42L)))!!
        assertTrue(settle.keptPick)
        assertEquals(Last("Home", 41L, Reason.PICKED_HERE.name, "Home"), settle.last)
        // A sync that read them after the pick decides on its own again.
        val fresh = PrimaryCalendarPicks.settle(stale, "Work", PrimaryCalendarPicks.since(a, PrimaryCalendarPicks.generation(a)), at("Work" to ChosenRow.Found(42L)))!!
        assertFalse(fresh.keptPick)
        assertEquals(Target.Primary(42L), fresh.target)
    }

    // ---- the log text ----

    @Test
    fun `what was done to the rows, for the log`() {
        assertEquals("row 41 primary, 2 other row(s) cleared", PrimaryCalendarPicks.describe(Applied(listOf(Write(41, 1), Write(42, 0), Write(43, 0)), 41L, null)))
        assertEquals("row 41 primary, 0 other row(s) cleared", PrimaryCalendarPicks.describe(Applied(emptyList(), 41L, null)))
        assertEquals("calendar not on this phone yet, rows unchanged", PrimaryCalendarPicks.describe(Applied(emptyList(), null, NoRow.NOT_ON_PHONE)))
        assertTrue(PrimaryCalendarPicks.describe(Applied(listOf(Write(41, 0)), null, NoRow.SHARED_ROW)).contains("no row is primary (1 row(s) cleared)"))
        // A row that is not the account's: unchanged, unless the rows changed between the check and the write.
        assertTrue(PrimaryCalendarPicks.describe(Applied(emptyList(), null, NoRow.NOT_THE_ACCOUNTS)).endsWith("rows unchanged"))
        assertTrue(PrimaryCalendarPicks.describe(Applied(listOf(Write(41, 0), Write(42, 0)), null, NoRow.NOT_THE_ACCOUNTS)).endsWith("2 row(s) cleared"))
        for (noRow in NoRow.entries) {
            assertTrue(noRow.name, PrimaryCalendarPicks.describe(Applied(emptyList(), null, noRow)).isNotBlank())
        }
    }

    // ---- what a pick does to the rows, and when the row's load brings the phone in line ----

    private companion object {
        /** The account whose rows the planning tests read; the in-memory tests take fresh names ([account]). */
        const val OWNER = "user@example.test"
        const val TYPE = "me.proton.sync.account"
    }

    private fun row(id: Long, stored: Int?, account: String = OWNER) = PrimaryCalendarRows.StoredRow(id, account, TYPE, stored)

    private fun planned(chosen: ChosenRow, rows: List<PrimaryCalendarRows.StoredRow>?, permitted: Boolean = true) =
        PrimaryCalendarPicks.planned(OWNER, TYPE, chosen, permitted, rows)

    @Test
    fun `a pick of an own row marks it and clears the account's others, writing only what differs`() {
        val rows = listOf(row(41, 0), row(42, 1), row(43, null), row(77, 1, account = "other@example.test"))

        assertEquals(Applied(listOf(Write(41, 1), Write(42, 0), Write(43, 0)), 41L, null), planned(ChosenRow.Found(41L), rows))
        // In line already: nothing to write.
        assertEquals(Applied(emptyList(), 41L, null), planned(ChosenRow.Found(41L), listOf(row(41, 1), row(42, 0))))
    }

    @Test
    fun `a pick that cannot mark a row writes nothing, or clears a shared row`() {
        val rows = listOf(row(41, 1), row(42, 0))

        assertEquals(Applied(listOf(Write(41, 0)), null, NoRow.SHARED_ROW), planned(ChosenRow.Found(41L, sharedWith = 1), rows))
        assertEquals(Applied(emptyList(), null, NoRow.NOT_ON_PHONE), planned(ChosenRow.Found(null), rows))
        assertEquals(Applied(emptyList(), null, NoRow.MAPPINGS_UNREADABLE), planned(ChosenRow.Unreadable, rows))
        // A mapping that names a row of another account or app: none of the account's rows is touched.
        assertEquals(Applied(emptyList(), null, NoRow.NOT_THE_ACCOUNTS), planned(ChosenRow.Found(77L), rows + row(77, 0, account = "other@example.test")))
        assertEquals(Applied(emptyList(), null, NoRow.NO_PERMISSION), planned(ChosenRow.Found(41L), rows, permitted = false))
        assertEquals(Applied(emptyList(), null, NoRow.WRITE_FAILED), planned(ChosenRow.Found(41L), null))
    }

    @Test
    fun `what the row writer did, for the pick's record`() {
        val writes = listOf(Write(41, 1), Write(42, 0))

        assertEquals(Applied(writes, 41L, null), PrimaryCalendarPicks.written(Target.Primary(41L), PrimaryCalendarRows.Applied(writes, true)))
        assertEquals(Applied(emptyList(), null, NoRow.WRITE_FAILED), PrimaryCalendarPicks.written(Target.Primary(41L), null))
        assertEquals(Applied(listOf(Write(42, 0)), null, NoRow.SHARED_ROW), PrimaryCalendarPicks.written(Target.Primary(null), PrimaryCalendarRows.Applied(listOf(Write(42, 0)), true)))
        // The rows changed between the check and the write: the chosen row was gone.
        assertEquals(Applied(listOf(Write(42, 0)), null, NoRow.NOT_THE_ACCOUNTS), PrimaryCalendarPicks.written(Target.Primary(41L), PrimaryCalendarRows.Applied(listOf(Write(42, 0)), false)))
    }

    @Test
    fun `the row's load brings the phone in line when the record names another calendar, or the rows differ`() {
        val home = Last("Home", 41L, Reason.PROTON_DEFAULT.name, "Home")
        val inLine = Applied(emptyList(), 41L, null)

        assertTrue(PrimaryCalendarPicks.needsBringInLine(null, "Home", inLine))
        assertTrue(PrimaryCalendarPicks.needsBringInLine(home.copy(protonCalendarId = "Work"), "Home", inLine))
        assertTrue(PrimaryCalendarPicks.needsBringInLine(home.copy(protonCalendarId = null), "Home", null))
        assertFalse(PrimaryCalendarPicks.needsBringInLine(home, "Home", inLine))
        // Two rows store 1 although the record is right (a sync stopped after a pick): the second one is cleared.
        assertTrue(PrimaryCalendarPicks.needsBringInLine(home, "Home", Applied(listOf(Write(42, 0)), 41L, null)))
        // The record names another row than the one that would store 1.
        assertTrue(PrimaryCalendarPicks.needsBringInLine(home.copy(androidCalId = 40L), "Home", inLine))
        // Without the rows (no permission, or the read failed) only the calendar counts: nothing moves on a failure.
        assertFalse(PrimaryCalendarPicks.needsBringInLine(home, "Home", null))
    }

    @Test
    fun `a bring-in-line that cannot mark a row is not repeated at every opening`() {
        // What applyOutsideSync stores for each of these is the record with no row, which then matches.
        val stored = Last("Home", null, Reason.PROTON_DEFAULT.name, "Home")
        val rows = listOf(row(41, 0), row(42, 0))

        for (chosen in listOf(ChosenRow.Found(77L), ChosenRow.Found(null), ChosenRow.Found(41L, sharedWith = 1))) {
            val wouldDo = planned(chosen, rows)
            assertFalse("$chosen", PrimaryCalendarPicks.needsBringInLine(stored, "Home", wouldDo))
            assertNull("$chosen", wouldDo.rowId)
        }
        // A shared row that still stores 1 is cleared once.
        assertTrue(PrimaryCalendarPicks.needsBringInLine(stored, "Home", planned(ChosenRow.Found(41L, sharedWith = 1), listOf(row(41, 1)))))
        // No calendar permission: the record a pick stored without a row matches.
        assertFalse(PrimaryCalendarPicks.needsBringInLine(stored, "Home", null))
    }

    // ---- wiring ----

    private fun source(path: String) = File("src/main/java/me/proton/sync/$path").readText().replace("\r\n", "\n")

    private fun body(src: String, signature: String): String {
        assertTrue("$signature not found", src.contains(signature))
        return src.substringAfter(signature).substringBefore("\n    }\n")
    }

    @Test
    fun `the sync reads the pick generation before its settings and settles under the account's lock`() {
        val adapter = source("calendar/CalendarSyncAdapter.kt")
        val decide = body(adapter, "private suspend fun decidePrimaryCalendar(")
        val generation = decide.indexOf("val pickGeneration = PrimaryCalendarPicks.generation(account.name)")
        assertTrue(decide, generation >= 0 && generation < decide.indexOf("fetchCalendarUserSettings(account)"))
        assertTrue(decide, decide.contains(".copy(pickGeneration = pickGeneration)"))

        val write = body(adapter, "private fun writePrimaryCalendar(")
        val locked = write.indexOf("synchronized(PrimaryCalendarPicks.lock(account.name)) {")
        assertTrue(write, locked >= 0)
        assertTrue(write, write.indexOf("PrimaryCalendarPicks.since(account.name, primary.pickGeneration)") > locked)
        assertTrue(write, write.indexOf("writeSettled(account, provider, settle, mappingError)") > locked)
        // The run's own move only: a kept pick was the user's change, not a move Proton made.
        assertTrue(body(adapter, "private fun writeSettled(").contains("if (!settle.keptPick && PrimaryCalendarMemory.moved(previous, last)) {"))
        // A choice on the row may name any calendar, so the mappings are read whatever the decision.
        assertFalse(body(adapter, "private suspend fun settlePrimaryCalendar(").contains("choosesOne"))
    }

    @Test
    fun `a kept pick that follows a row which is not the account's is logged as no row primary`() {
        val settled = body(source("calendar/CalendarSyncAdapter.kt"), "private fun writeSettled(")
        // The write is what finds that the run's row is not the account's, so that check comes before the line that
        // says the rows follow the run's row.
        val foreign = settled.indexOf("settle.followsRun && applied?.chosenRowFound == false -> SyncLog.w(")
        val follows = settled.indexOf("settle.followsRun -> SyncLog.i(")
        assertTrue(settled, foreign >= 0 && follows > foreign)
        assertTrue(settled, settled.indexOf("PrimaryCalendarRows.apply(provider, account, target)") in 0 until foreign)
    }

    @Test
    fun `a pick is recorded and stored under the account's lock`() {
        val picks = source("calendar/PrimaryCalendarPicks.kt")
        val apply = body(picks, "fun applyOutsideSync(")
        val locked = apply.indexOf("synchronized(lock(account.name)) {")
        val recorded = apply.indexOf("record(account.name, protonCalendarId, name, reason)")
        val stored = apply.indexOf("storeRecord(context, account.name, ")
        assertTrue(apply, locked >= 0 && recorded > locked && stored > recorded)
        // Rows only through PrimaryCalendarRows, the row writer, which writes the account's own rows through the
        // sync-adapter URI, and by the same plan the load's check reads.
        val write = body(picks, "private fun writeRows(")
        assertTrue(write, write.contains("PrimaryCalendarRows.apply(provider, account, target)"))
        assertTrue(write, write.contains("planned(account.name, account.type, chosen, permitted = true, rows = rows)"))
    }
}

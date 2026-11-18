package me.proton.sync.calendar

import kotlinx.coroutines.runBlocking
import me.proton.sync.calendar.RangeTrimPass.JournalEntry
import me.proton.sync.calendar.RangeTrimPlanner.Group
import me.proton.sync.db.entities.CalendarEventSyncEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The removal ([RangeTrimPass]): journal first, the rows next, their mappings last. A row that stays keeps the very
 * mapping it had (never a rebuilt one), and a mapping is left without its row only while the journal names that row,
 * which holds reconcileDeletedEvents back until the next sync's recovery deletes the mapping.
 */
class RangeTrimPassTest {

    private val log = ArrayList<String>()
    private val cal = 7L

    /** Rows by id: their dirty flag. */
    private inner class FakeRows(ids: Collection<Long>) : RangeTrimPass.Rows {
        val rows = ids.associateWith { false }.toMutableMap()
        /** Groups whose first row is listed here were touched since the plan: their assert refuses the batch. */
        val touched = HashSet<Long>()
        /** Delete this many rows of the batch, then throw (a provider that is not transactional). */
        var throwAfter: Int? = null
        var unreadable = false

        override fun read(androidCalId: Long) = null
        override fun present(rowIds: Collection<Long>): Map<Long, Boolean>? =
            if (unreadable) null else rowIds.filter { it in rows }.associateWith { rows[it]!! }

        override fun deleteGroups(androidCalId: Long, groups: List<Group>): Boolean {
            log.add("rows.deleteGroups ${groups.size}")
            if (groups.any { it.rowIds.first() in touched }) return false
            throwAfter?.let { n ->
                groups.flatMap { it.rowIds }.take(n).forEach { rows.remove(it) }
                throw IllegalStateException("provider died")
            }
            groups.flatMap { it.rowIds }.forEach { rows.remove(it) }
            return true
        }
    }

    private inner class FakeMappings(entities: Collection<CalendarEventSyncEntity>) : RangeTrimPass.Mappings {
        val byId = entities.associateBy { it.protonEventId }.toMutableMap()
        var failDelete = false
        /** Room cannot be read. */
        var failReads = false
        override suspend fun delete(protonIds: List<String>) {
            log.add("mappings.delete ${protonIds.size}")
            if (failDelete) throw IllegalStateException("disk full")
            protonIds.forEach { byId.remove(it) }
        }
        override suspend fun rowsOf(protonIds: List<String>): Map<String, Long> {
            log.add("mappings.rowsOf ${protonIds.size}")
            if (failReads) throw IllegalStateException("database locked")
            return protonIds.mapNotNull { p -> byId[p]?.androidEventId?.let { p to it } }.toMap()
        }
    }

    private inner class FakeJournal : RangeTrimPass.Journal {
        var entries: List<JournalEntry> = emptyList()
        /** The disk refuses the write (commit() returns false). */
        var failWrite = false
        override fun read() = entries
        override fun write(entries: List<JournalEntry>): Boolean {
            log.add("journal.write ${entries.size}")
            this.entries = entries
            return !failWrite
        }
        override fun clear() { log.add("journal.clear"); entries = emptyList() }
    }

    /** A mapping as the sync keeps it: sequence, baseline and cached fields, which a rebuilt one would lack. */
    private fun entity(id: Long) = CalendarEventSyncEntity(
        accountName = "acct", protonCalendarId = "cal", protonEventId = "p$id", androidEventId = id,
        lastModified = 1_700_000_000, sequence = 3, sharedFingerprint = "fp$id", cachedIsOrganizer = 1,
    )

    /** A group of a mapped master and [placeholders] unmapped placeholders, ids from [first]. */
    private fun group(first: Long, placeholders: Int = 2) =
        Group((first..first + placeholders).toList(), listOf("p$first"), setOf("p$first"), placeholders)

    private fun plan(vararg groups: Group) = RangeTrimPlanner.Plan(groups.toList(), emptyMap())

    private fun run(groups: List<Group>, rows: FakeRows, mappings: FakeMappings, journal: FakeJournal) = runBlocking {
        RangeTrimPass.remove("cal", cal, plan(*groups.toTypedArray()), mappings.byId.values.toList(), rows, mappings, journal)
    }

    /** Outside the journal, no mapping may name a row that is gone: reconcileDeletedEvents would delete it on Proton. */
    private fun assertNoOrphanMapping(rows: FakeRows, mappings: FakeMappings, journal: FakeJournal) {
        val journaled = journal.entries.map { it.rowId }.toSet()
        for (e in mappings.byId.values) {
            assertTrue("mapping ${e.protonEventId} without its row", e.androidEventId in rows.rows || e.androidEventId in journaled)
        }
    }

    @Test
    fun `the journal comes first, then the rows, then their mappings, and the journal is cleared after`() {
        val groups = listOf(group(10), group(20))
        val rows = FakeRows(groups.flatMap { it.rowIds })
        val mappings = FakeMappings(groups.map { entity(it.rowIds.first()) })
        val journal = FakeJournal()
        val out = run(groups, rows, mappings, journal)
        assertEquals(listOf("journal.write 6", "rows.deleteGroups 2", "mappings.delete 2", "journal.clear"), log)
        assertEquals(2, out.removedGroups)
        assertEquals(6, out.removedRows)
        assertTrue(rows.rows.isEmpty())
        assertTrue(mappings.byId.isEmpty())
        assertTrue(journal.entries.isEmpty())
    }

    @Test
    fun `a group touched since the plan is refused and keeps its very mapping, and the others still go`() {
        val groups = listOf(group(10), group(20), group(30))
        val rows = FakeRows(groups.flatMap { it.rowIds }).apply { touched.add(20) }
        val mappings = FakeMappings(groups.map { entity(it.rowIds.first()) })
        val kept = mappings.byId["p20"]
        val journal = FakeJournal()
        val out = run(groups, rows, mappings, journal)
        assertEquals(2, out.removedGroups)
        assertEquals(1, out.refusedGroups)
        assertEquals(0, out.failedGroups)
        assertEquals(setOf("p20"), mappings.byId.keys)
        // Not rebuilt: the same entity, with its sequence, baseline and cached fields.
        assertSame(kept, mappings.byId["p20"])
        assertEquals(setOf(20L, 21L, 22L), rows.rows.keys)
        assertNoOrphanMapping(rows, mappings, journal)
        assertTrue(journal.entries.isEmpty())
        // The whole batch first, then one group at a time.
        assertEquals("rows.deleteGroups 3", log[1])
        assertEquals(3, log.count { it == "rows.deleteGroups 1" })
    }

    @Test
    fun `after a provider that failed half way the mappings of the rows it took go, the others stay, and it counts as failed`() {
        val groups = listOf(group(10), group(20))
        val rows = FakeRows(groups.flatMap { it.rowIds }).apply { throwAfter = 3 }
        val mappings = FakeMappings(groups.map { entity(it.rowIds.first()) })
        val journal = FakeJournal()
        val out = run(groups, rows, mappings, journal)
        // Group 10 lost all its rows: removed, with its mapping. Group 20 is whole and kept its mapping.
        assertEquals(1, out.removedGroups)
        assertEquals(1, out.failedGroups)
        assertEquals(0, out.refusedGroups)
        assertEquals(setOf("p20"), mappings.byId.keys)
        assertNoOrphanMapping(rows, mappings, journal)
        assertFalse(out.stopped)
        assertTrue(journal.entries.isEmpty())
        assertEquals(1, out.notes.size)
    }

    @Test
    fun `when the rows cannot be read after a failure, nothing is touched and the journal stays`() {
        val groups = listOf(group(10))
        val rows = FakeRows(groups.flatMap { it.rowIds }).apply { throwAfter = 1; unreadable = true }
        val mappings = FakeMappings(groups.map { entity(it.rowIds.first()) })
        val journal = FakeJournal()
        val out = run(groups, rows, mappings, journal)
        assertTrue(out.stopped)
        assertEquals(setOf("p10"), mappings.byId.keys)
        assertEquals(3, journal.entries.size)
        assertFalse(log.any { it.startsWith("mappings.delete") })
        assertNoOrphanMapping(rows, mappings, journal)
        assertEquals(2, out.notes.size)
    }

    @Test
    fun `when Room fails after the rows went, the journal stays and names every row whose mapping is left`() {
        val groups = listOf(group(10))
        val rows = FakeRows(groups.flatMap { it.rowIds })
        val mappings = FakeMappings(groups.map { entity(it.rowIds.first()) }).apply { failDelete = true }
        val journal = FakeJournal()
        val out = run(groups, rows, mappings, journal)
        assertTrue(out.stopped)
        assertTrue(rows.rows.isEmpty())
        assertEquals(setOf("p10"), mappings.byId.keys)
        assertEquals(listOf(JournalEntry("cal", cal, 0, 10, "p10")), journal.entries.filter { it.protonEventId != null })
        assertNoOrphanMapping(rows, mappings, journal)
        // The next sync's recovery deletes it.
        mappings.failDelete = false
        val r = runBlocking { RangeTrimPass.recover(rows, mappings, journal) }!!
        assertEquals(1, r.deletedMappings)
        assertTrue(mappings.byId.isEmpty())
        assertTrue(journal.entries.isEmpty())
    }

    @Test
    fun `nothing is deleted when the journal cannot be written`() {
        val groups = listOf(group(10))
        val rows = FakeRows(groups.flatMap { it.rowIds })
        val mappings = FakeMappings(groups.map { entity(it.rowIds.first()) })
        val journal = FakeJournal().apply { failWrite = true }
        val out = run(groups, rows, mappings, journal)
        assertTrue(out.stopped)
        assertEquals(0, out.removedGroups)
        assertEquals(setOf(10L, 11L, 12L), rows.rows.keys)
        assertEquals(setOf("p10"), mappings.byId.keys)
        assertFalse(log.any { it.startsWith("rows.deleteGroups") || it.startsWith("mappings.delete") })
    }

    @Test
    fun `recovery deletes only the mappings of rows that are gone, and only while they still name that row`() {
        val journal = FakeJournal().apply {
            entries = listOf(
                // Group 0 was removed before the crash: its rows are gone, its mapping is left.
                JournalEntry("cal", cal, 0, 10, "p10"), JournalEntry("cal", cal, 0, 11, null),
                // Group 1 never went (the crash came first): its rows are there, one edited since; nothing changes.
                JournalEntry("cal", cal, 1, 20, "p20"), JournalEntry("cal", cal, 1, 21, null),
                // Group 2 went, and its Proton id has since been mapped to a new row: that mapping stays.
                JournalEntry("cal", cal, 2, 30, "p30"),
            )
        }
        val rows = FakeRows(listOf(20L, 21L, 31L)).apply { this.rows[20] = true }
        val mappings = FakeMappings(listOf(entity(10), entity(20), entity(30).copy(androidEventId = 31)))
        val kept = mappings.byId["p20"]
        val r = runBlocking { RangeTrimPass.recover(rows, mappings, journal) }!!
        assertEquals(1, r.deletedMappings)
        assertEquals(2, r.keptRows)
        assertEquals(setOf("p20", "p30"), mappings.byId.keys)
        assertSame(kept, mappings.byId["p20"])
        assertEquals(setOf(20L, 21L, 31L), rows.rows.keys)
        assertNoOrphanMapping(rows, mappings, journal)
        assertTrue(journal.entries.isEmpty())
    }

    @Test
    fun `recovery waits when the rows or the mappings cannot be read or deleted, and does nothing without a journal`() {
        val journal = FakeJournal().apply { entries = listOf(JournalEntry("cal", cal, 0, 10, "p10")) }
        val rows = FakeRows(listOf(10L)).apply { unreadable = true }
        assertEquals(null, runBlocking { RangeTrimPass.recover(rows, FakeMappings(emptyList()), journal) })
        assertEquals(1, journal.entries.size)
        // Room cannot be read: nothing is deleted, the journal stays.
        val unreadable = FakeMappings(listOf(entity(10))).apply { failReads = true }
        log.clear()
        assertEquals(null, runBlocking { RangeTrimPass.recover(FakeRows(emptyList()), unreadable, journal) })
        assertEquals(listOf("mappings.rowsOf 1"), log)
        assertEquals(1, journal.entries.size)
        assertTrue("p10" in unreadable.byId)
        // Room cannot delete: the journal stays.
        val failing = FakeMappings(listOf(entity(10))).apply { failDelete = true }
        assertEquals(null, runBlocking { RangeTrimPass.recover(FakeRows(emptyList()), failing, journal) })
        assertEquals(1, journal.entries.size)
        log.clear()
        val none = runBlocking { RangeTrimPass.recover(FakeRows(emptyList()), FakeMappings(emptyList()), FakeJournal()) }!!
        assertEquals(0, none.deletedMappings + none.keptRows)
        assertTrue(log.isEmpty())
    }

    @Test
    fun `batches hold at most the row limit, a group never split`() {
        val big = Group((1L..300L).toList(), listOf("p1"), setOf("p1"), 299)
        val more = Group((1001L..1200L).toList(), listOf("p1001"), setOf("p1001"), 199)
        val rows = FakeRows(big.rowIds + more.rowIds)
        val mappings = FakeMappings(listOf(entity(1), entity(1001)))
        runBlocking {
            RangeTrimPass.remove("cal", cal, plan(big, more), listOf(entity(1), entity(1001)), rows, mappings, FakeJournal())
        }
        assertEquals(listOf("journal.write 300", "journal.write 200"), log.filter { it.startsWith("journal.write") })
    }

    @Test
    fun `a journal entry survives its text form`() {
        for (e in listOf(JournalEntry("cal==", 7, 3, 99, "evt-id=="), JournalEntry("cal", 7, 0, 5, null))) {
            assertEquals(e, JournalEntry.decode(e.encode()))
        }
        assertEquals(null, JournalEntry.decode("garbage"))
    }

    @Test
    fun `the planner's row is read from the shared columns at their indexes`() {
        val shared = MutableList<String?>(EventConflictGuard.SHARED_COLUMNS.size) { null }
        shared[EventConflictGuard.COLUMN_DTSTART] = "1000"
        shared[EventConflictGuard.COLUMN_DTEND] = "2000"
        shared[EventConflictGuard.COLUMN_RRULE] = "FREQ=DAILY;COUNT=2"
        shared[EventConflictGuard.COLUMN_EVENT_TIMEZONE] = "Europe/Berlin"
        shared[EventConflictGuard.SHARED_COLUMNS.indexOf(android.provider.CalendarContract.Events.STATUS)] = "2"
        val r = RangeTrimPass.rowOf(5, shared, "", "p1", 900, "u", 3000, dirty = false, deleted = true)
        assertEquals(1000L, r.dtStart)
        assertEquals(2000L, r.dtEnd)
        assertEquals("FREQ=DAILY;COUNT=2", r.rrule)
        assertEquals("Europe/Berlin", r.eventTimezone)
        assertTrue(r.cancelled)
        assertEquals(null, r.syncId)
        assertEquals("p1", r.originalSyncId)
        assertTrue(r.deleted)
        assertEquals(EventConflictGuard.fingerprintOf(shared), r.fingerprint)
        assertEquals(false, r.allDay)
        shared[EventConflictGuard.COLUMN_ALL_DAY] = "1"
        assertTrue(RangeTrimPass.rowOf(5, shared, "", "p1", 900, "u", 3000, dirty = false, deleted = true).allDay)
    }
}

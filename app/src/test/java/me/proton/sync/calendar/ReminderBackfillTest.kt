package me.proton.sync.calendar

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which unchanged events the download gives their missing reminder rows.
 *
 * The case that matters is the failed read: it used to come back as an empty set, and an empty set
 * says "no event has reminders", so one failed query rewrote the reminders of every clean event.
 */
class ReminderBackfillTest {

    @Test
    fun `an event the snapshot lists no reminder rows for is backfilled`() {
        assertTrue(needsReminderBackfill(7L, withReminders = setOf(1L, 2L), locallyDirty = emptySet()))
    }

    @Test
    fun `an event that already has reminder rows is left alone`() {
        assertFalse(needsReminderBackfill(1L, withReminders = setOf(1L, 2L), locallyDirty = emptySet()))
    }

    @Test
    fun `an event with unsent edits is never backfilled`() {
        // No rows is also what "the user just deleted every reminder" looks like, before the upload ran.
        assertFalse(needsReminderBackfill(7L, withReminders = setOf(1L), locallyDirty = setOf(7L)))
    }

    @Test
    fun `a failed read of the reminders backfills nothing at all`() {
        for (id in listOf(1L, 7L, 42L)) {
            assertFalse(needsReminderBackfill(id, withReminders = null, locallyDirty = emptySet()))
        }
    }

    @Test
    fun `an empty snapshot is an answer, unlike a failed one`() {
        // The read worked and no event has reminder rows yet, a calendar's first backfill: every clean event qualifies.
        assertTrue(needsReminderBackfill(7L, withReminders = emptySet(), locallyDirty = emptySet()))
    }

    @Test
    fun `the read itself reports a failure as null, never as an empty set`() {
        // needsReminderBackfill can only skip a run the read reports as failed. The read queries the provider, which a
        // unit test cannot run, so its source is checked: going back to emptySet() would pass every test above.
        val source = java.io.File("src/main/java/me/proton/sync/calendar/CalendarSyncAdapter.kt").readText().replace("\r\n", "\n")
        val read = source.substringAfter("private fun readEventIdsWithReminders(").substringBefore("\n    }\n")
            .lines().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")
        assertTrue("the read is found", read.contains("CalendarContract.Reminders.CONTENT_URI"))
        assertTrue("it may return null", read.contains("): Set<Long>? {"))
        assertFalse("no empty set stands in for a failure", read.contains("emptySet()"))
        val noCursor = read.substringAfter("if (cursor == null) {", "").substringBefore("\n            }")
        assertTrue("no cursor is a failure", noCursor.lines().last().trim() == "return null")
        val onException = read.substringAfter("catch (e: Exception) {", "").trimEnd().removeSuffix("}").trimEnd()
        assertTrue("an exception is a failure", onException.lines().last().trim() == "null")
    }
}

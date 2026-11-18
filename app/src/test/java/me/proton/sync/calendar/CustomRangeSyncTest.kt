package me.proton.sync.calendar

import me.proton.sync.SyncPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Two custom-range fixes in 2.1.0, pinned on the adapter's source because both sit in its network paths.
 *
 * Narrowing the range: the run that re-fetches after a range change skipped the all-time /events/ids list and took the
 * new window as the record of what Proton still has, so every mapped event outside it looked deleted on Proton. A few
 * were deleted from the phone at once, unsent edits included; more were held behind a false prompt.
 *
 * Time passing: an event that entered the moving window was downloaded only when something else in its calendar
 * changed, because an unchanged calendar is skipped; an appointment made months ahead could be missing when due.
 */
class CustomRangeSyncTest {

    private val src = File("src/main/java/me/proton/sync/calendar/CalendarSyncAdapter.kt").readText().replace("\r\n", "\n")

    @Test
    fun `every custom-range run with mappings takes Proton's all-time ids as the deletion authority`() {
        assertTrue(src.contains("if (syncMode != SyncPreferences.SYNC_MODE_ALL && existingEventMappings.isNotEmpty()) {\n            val ids = fetchAllEventIds("))
        // Not only the incremental runs: the range-change run (skipMetadata) needs it most.
        assertFalse(src.contains("syncMode != SyncPreferences.SYNC_MODE_ALL && !skipMetadata && existingEventMappings.isNotEmpty()"))
    }

    @Test
    fun `an unchanged custom-range calendar is swept once per local day, marked only after a complete run`() {
        // Not a calendar its last complete run fetched whole: the phone already has every event of it. One that run
        // fetched by the window (large, or its count failed) is swept.
        assertTrue(src.contains("val syncsWhole = prefs.getBoolean(SyncPreferences.fetchedWholeKey(account.name, calInfo.id), false)\n"))
        assertTrue(src.contains("val dailySweepDue = syncMode != SyncPreferences.SYNC_MODE_ALL && !syncsWhole && prefs.getString(sweepDayKey, null) != today"))
        assertTrue(src.contains("val fetchedRange = if (syncMode == SyncPreferences.SYNC_MODE_ALL || totalEventCount in 0..sparseThreshold) null else syncStart..syncEnd"))
        assertTrue(src.contains("val wouldSkip = quiet && !dailySweepDue\n"))
        val end = src.substringAfter("prefs.edit().putString(rangeKey, currentRange).apply()").substringBefore("\n    }\n")
        assertTrue(end, end.contains("if (syncMode != SyncPreferences.SYNC_MODE_ALL && !fetchHadFailures) {"))
        // The day and the path of that complete run, written together.
        assertTrue(end, end.contains("prefs.edit().putString(sweepDayKey, today)\n" +
            "                .putBoolean(SyncPreferences.fetchedWholeKey(account.name, calInfo.id), fetchedRange == null).apply()"))
        assertTrue(SyncPreferences.fetchedWholeKey("acct", "cal1").startsWith(SyncPreferences.lastSyncRangeKey("acct", "")))
    }

    @Test
    fun `older events are removed only after a run that took the window because the calendar is large`() {
        // A count that failed sends a small calendar down the window too; it syncs whole again at the next run.
        assertTrue(src.contains("if (trimSeeds != null && fetchedRange != null && totalEventCount > sparseThreshold && !fetchHadFailures) {"))
    }

    @Test
    fun `the RSVP poll runs on every quiet run, also when the daily sweep takes the skip's place`() {
        // A phone that syncs once a day (Manual only, or every 24 hours) makes every run the day's first: under the
        // skip alone the opt-in poll would never run there.
        val quiet = src.indexOf("                if (quiet) {\n")
        val poll = src.indexOf("checkAttendeeStatusChanges(account, provider, calInfo, calDisplayName)")
        val skip = src.indexOf("                if (wouldSkip) {\n")
        assertTrue(quiet in 0 until poll)
        assertTrue(poll < skip)
        assertTrue(src.contains("val quiet = unchanged &&\n                    RemoteDeletionGuard.pendingCount(context, RemoteDeletionGuard.TYPE_CALENDAR, account.name) == 0"))
        assertEquals(1, Regex("checkAttendeeStatusChanges\\(account, provider, calInfo, calDisplayName\\)").findAll(src).count())
    }

    @Test
    fun `the removal's seed takes the Past events the run's window was computed from`() {
        assertTrue(src.contains("            pastDays = pastDays\n        )\n"))
        assertTrue(src.contains("fetch.windowIds, fetch.allTimeIds, serverDateMillis,\n                plan.pastDays\n            )?.let { trimSeeds[androidCalId] = it }"))
    }

    @Test
    fun `the daily sweep mark is cleared wherever the range keys are, by their prefix`() {
        val key = SyncPreferences.lastSweepDayKey("acct", "cal1")
        assertTrue(key.startsWith(SyncPreferences.lastSyncRangeKey("acct", "")))
        assertEquals("last_sync_range_acct_cal1_swept_day", key)
    }
}

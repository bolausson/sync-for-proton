package me.proton.sync.calendar

import me.proton.sync.SyncPreferences
import me.proton.sync.calendar.RangeTrimRunner.Choice
import me.proton.sync.contacts.FakePrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * The setting of "Remove older events from this phone" ([RangeTrimRunner]): a choice names the Android calendar and the
 * Proton calendar together, so it never passes to a calendar nobody chose, and the removal reads the setting again
 * when it runs, after the other calendars synced.
 */
class RangeTrimRunnerTest {

    private val acct = "a@example.org"
    private val zone = ZoneId.of("Europe/Berlin")
    private val now = Instant.parse("2026-11-14T12:00:00Z").toEpochMilli()

    private fun prefs(past: Long = 30, mode: String = SyncPreferences.SYNC_MODE_CUSTOM) = FakePrefs().apply {
        values[SyncPreferences.calendarPastDaysKey(acct)] = "$past"
        values[SyncPreferences.calendarSyncModeKey(acct)] = mode
    }

    private fun seed(p: FakePrefs, androidCalId: Long = 23, protonCalendarId: String = "feed==", pastDays: Long = 30) = RangeTrimRunner.seedOf(
        p, acct, androidCalId, protonCalendarId, "Feed", windowIds = emptySet(), allTimeIds = emptySet(),
        serverDateMillis = now, pastDays = pastDays, nowMillis = now, zone = zone
    )

    @Test
    fun `a choice survives its text form, and anything else is dropped`() {
        val c = Choice(23, "Rf3_x-Ab==")
        assertEquals(c, Choice.decode(c.encode()))
        for (bad in listOf("23", "|feed", "23|", "x|feed", "")) assertNull(bad, Choice.decode(bad))
        val p = prefs()
        p.values[SyncPreferences.calendarTrimPastKey(acct)] = setOf("23", c.encode())
        assertEquals(setOf(c), RangeTrimRunner.choices(p, acct))
    }

    @Test
    fun `a deleted calendar's Android id given to a new calendar does not inherit the removal`() {
        val p = prefs()
        RangeTrimRunner.setChoices(p, acct, setOf(Choice(23, "feed==")))
        assertNotNull(seed(p))
        // The provider reused id 23 for the next calendar created, here an archive nobody ticked.
        assertNull(seed(p, protonCalendarId = "archive=="))
        assertNull(seed(p, androidCalId = 24))
    }

    @Test
    fun `a Proton calendar gone from Proton is forgotten, the others stay`() {
        val p = prefs()
        RangeTrimRunner.setChoices(p, acct, setOf(Choice(23, "feed=="), Choice(21, "work==")))
        RangeTrimRunner.forgetCalendar(p, acct, "feed==")
        assertEquals(setOf(Choice(21, "work==")), RangeTrimRunner.choices(p, acct))
        RangeTrimRunner.forgetCalendar(p, acct, "work==")
        assertEquals(false, p.contains(SyncPreferences.calendarTrimPastKey(acct)))
    }

    @Test
    fun `the removal reads the setting again, and switched off or out of Custom it does not run`() {
        val p = prefs(past = 30)
        RangeTrimRunner.setChoices(p, acct, setOf(Choice(23, "feed==")))
        val s = seed(p)!!
        assertEquals(s.cutoffMillis, RangeTrimRunner.cutoffNow(p, acct, s, now, zone))
        // Switched off while the other calendars synced.
        RangeTrimRunner.setChoices(p, acct, emptySet())
        assertNull(RangeTrimRunner.cutoffNow(p, acct, s, now, zone))
        RangeTrimRunner.setChoices(p, acct, setOf(Choice(23, "feed==")))
        p.values[SyncPreferences.calendarSyncModeKey(acct)] = SyncPreferences.SYNC_MODE_ALL
        assertNull(RangeTrimRunner.cutoffNow(p, acct, s, now, zone))
    }

    @Test
    fun `a Past events made longer meanwhile moves the cutoff earlier, a shorter one does not move it`() {
        val p = prefs(past = 30)
        RangeTrimRunner.setChoices(p, acct, setOf(Choice(23, "feed==")))
        val s = seed(p)!!
        p.values[SyncPreferences.calendarPastDaysKey(acct)] = "90"
        assertEquals(RangeTrimPlanner.cutoffMillis(now, 90, zone), RangeTrimRunner.cutoffNow(p, acct, s, now, zone))
        p.values[SyncPreferences.calendarPastDaysKey(acct)] = "7"
        assertEquals(s.cutoffMillis, RangeTrimRunner.cutoffNow(p, acct, s, now, zone))
    }

    @Test
    fun `a holiday or subscribed calendar is skipped by the sync only while external calendars are off`() {
        val p = prefs()
        // Not noted yet (no sync since 2.1.0): counts as synced.
        assertEquals(false, RangeTrimRunner.skippedBySync(p, acct, "feed=="))
        RangeTrimRunner.noteCalendarType(p, acct, "feed==", external = true)
        RangeTrimRunner.noteCalendarType(p, acct, "work==", external = false)
        assertEquals(true, RangeTrimRunner.skippedBySync(p, acct, "feed=="))
        assertEquals(false, RangeTrimRunner.skippedBySync(p, acct, "work=="))
        p.values[SyncPreferences.includeExternalCalendarsKey(acct)] = true
        assertEquals(false, RangeTrimRunner.skippedBySync(p, acct, "feed=="))
        // Noted on the range keys' prefix, so every clean-up of those removes it too.
        assertEquals(true, SyncPreferences.calendarExternalKey(acct, "feed==").startsWith(SyncPreferences.lastSyncRangeKey(acct, "")))
    }

    private fun estimate(id: Long, large: Boolean, events: Int, synced: Boolean = true) = RangeTrimDryRun.Estimate(
        id, "cal$id==", "Cal $id", mapped = if (large) 500 else 20, serverTotal = if (large) 500 else 20, threshold = 70,
        events = events, rows = events, plan = RangeTrimPlanner.Plan(emptyList(), emptyMap()), lastDateNull = emptyMap(),
        endDisagreements = 0, synced = synced
    )

    @Test
    fun `the dialog lists large calendars and chosen ones, never one the sync skips, and offers only for a listed loss`() {
        val work = estimate(1, large = true, events = 5)
        val small = estimate(2, large = false, events = 0)
        val frozen = estimate(3, large = true, events = 40, synced = false)
        assertEquals(listOf(work), RangeTrimDryRun.listed(listOf(work, small, frozen), emptySet()))
        // A small calendar it is on for is listed, to be switched off; a skipped one is not, also when it is on.
        assertEquals(listOf(work, small), RangeTrimDryRun.listed(listOf(work, small, frozen), setOf(small.choice, frozen.choice)))
        // The frozen calendar's 40 events never make an offer on their own.
        assertEquals(false, RangeTrimDryRun.offerWanted(RangeTrimDryRun.listed(listOf(estimate(1, true, 0), frozen), emptySet())))
        assertEquals(true, RangeTrimDryRun.offerWanted(RangeTrimDryRun.listed(listOf(work, frozen), emptySet())))
    }

    @Test
    fun `the dialog notes why calendars are left out, and a skipped large one is never called too small`() {
        val work = estimate(1, large = true, events = 5)
        val small = estimate(2, large = false, events = 0)
        val frozen = estimate(3, large = true, events = 40, synced = false)
        val none = emptyList<RangeTrimDryRun.LeftOut>()
        assertEquals(listOf(RangeTrimDryRun.LeftOut.SMALL), RangeTrimDryRun.leftOut(listOf(work, small), emptySet()))
        assertEquals(
            listOf(RangeTrimDryRun.LeftOut.SMALL, RangeTrimDryRun.LeftOut.NOT_SYNCED),
            RangeTrimDryRun.leftOut(listOf(work, small, frozen), emptySet())
        )
        // Only the skipped one: the dialog lists nothing, and says why instead of "too small".
        assertEquals(listOf(RangeTrimDryRun.LeftOut.NOT_SYNCED), RangeTrimDryRun.leftOut(listOf(frozen), emptySet()))
        // A small calendar it is on for is listed, so no note for it.
        assertEquals(none, RangeTrimDryRun.leftOut(listOf(work, small), setOf(small.choice)))
        // A small skipped calendar it is not on for would not be listed anyway; one it is on for is left out.
        val frozenSmall = estimate(4, large = false, events = 0, synced = false)
        assertEquals(none, RangeTrimDryRun.leftOut(listOf(work, frozenSmall), emptySet()))
        assertEquals(listOf(RangeTrimDryRun.LeftOut.NOT_SYNCED), RangeTrimDryRun.leftOut(listOf(work, frozenSmall), setOf(frozenSmall.choice)))
    }

    @Test
    fun `the settings screen redraws the removal row whenever what it counts changes`() {
        val src = java.io.File("src/main/java/me/proton/sync/settings/SyncSettingsActivity.kt").readText().replace("\r\n", "\n")
        val listener = src.substringAfter("private val trimRowListener =").substringBefore("\n    }\n")
        for (key in listOf("SyncPreferences.calendarTrimPastKey(name)", "SyncPreferences.includeExternalCalendarsKey(name)",
            "key.endsWith(\"_external\") && key.startsWith(SyncPreferences.lastSyncRangeKey(name, \"\"))")) {
            assertEquals(key, true, listener.contains(key))
        }
        assertEquals(true, listener.contains("refreshTrimValue(handle.parentAccount)"))
        val resume = src.substringAfter("override fun onResume() {").substringBefore("\n    }\n")
        val pause = src.substringAfter("override fun onPause() {").substringBefore("\n    }\n")
        // Registered before the cards read their values, so no change in between goes unseen.
        val register = resume.indexOf("registerOnSharedPreferenceChangeListener(trimRowListener)")
        assertEquals(true, register >= 0 && register < resume.indexOf("buildAccountCards()"))
        assertEquals(true, pause.contains("unregisterOnSharedPreferenceChangeListener(trimRowListener)"))
        // The calendar mappings it counts, which no preference reports: redrawn when the account's calendar sync ends.
        val indicators = src.substringAfter("private fun refreshSyncIndicators() {").substringBefore("\n    }\n")
        assertEquals(true, indicators.contains("if (calendarActive) calendarSyncSeen.add(handle.parentAccount.name)\n" +
            "            else if (calendarSyncSeen.remove(handle.parentAccount.name)) refreshTrimValue(handle.parentAccount)"))
        // The latest redraw wins: a newer one cancels the one still reading.
        val refresh = src.substringAfter("private fun refreshTrimValue(account: Account) {").substringBefore("\n    }\n")
        assertEquals(true, refresh.indexOf("trimValueJobs.remove(account.name)?.cancel()") in 0 until refresh.indexOf("lifecycleScope.launch"))
        // The narrowing offer compares with the value the Past events picker opened with, read as the sync reads it.
        assertEquals(true, src.contains("onChangedFrom(live, chosen)"))
        assertEquals(true, src.contains("fun days(v: String) = (v.toLongOrNull() ?: SyncPreferences.DEFAULT_PAST_DAYS).coerceAtLeast(1)"))
        assertEquals(false, src.contains("lastPastDays"))
    }

    @Test
    fun `removing the synced calendar data starts the removal of older events over`() {
        val src = java.io.File("src/main/java/me/proton/sync/SyncedDataRemover.kt").readText().replace("\r\n", "\n")
        val body = src.substringAfter("suspend fun removeCalendars(").substringBefore("\n    }\n")
        val mappings = body.indexOf("db.calendarSyncDao().deleteAllByAccount(account.name)")
        assertEquals(true, mappings >= 0)
        for (key in listOf(".remove(SyncPreferences.calendarTrimPastKey(account.name))", ".remove(SyncPreferences.calendarTrimJournalKey(account.name))")) {
            assertEquals(key, true, body.indexOf(key) > mappings)
        }
    }

    @Test
    fun `the seed's cutoff is the Past events the run fetched, not one shortened while it fetched`() {
        // The run's window was computed from 30 days; the setting reads 7 by the time the seed is taken.
        val p = prefs(past = 7)
        RangeTrimRunner.setChoices(p, acct, setOf(Choice(23, "feed==")))
        val s = seed(p, pastDays = 30)!!
        assertEquals(RangeTrimPlanner.cutoffMillis(now, 30, zone), s.cutoffMillis)
        assertEquals(s.cutoffMillis, RangeTrimRunner.cutoffNow(p, acct, s, now, zone))
    }
}

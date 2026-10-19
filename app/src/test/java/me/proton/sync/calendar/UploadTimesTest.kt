package me.proton.sync.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Instant
import java.time.ZoneId

/**
 * What the upload writes as a timed row's local times: Proton's own text for a time the user has not touched
 * since the last sync, the phone's rules for a time they typed. The stale phone is the test JVM, whose tzdb
 * predates 2026d (JetBrains JDK 21.0.10 has 2025b: Vancouver at -08 in December 2026); tzurl 2026d says -07, so
 * Proton's "10:00" is 17:00Z online and 18:00Z on the phone. The tests that need that difference are skipped
 * on a JVM with newer data (StalePhoneJvm).
 */
class UploadTimesTest {

    private fun fixture(tzid: String) = javaClass.getResource("/tzurl/${tzid.replace('/', '_')}.ics")!!.readText()
    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    private val on = RuleSnapshot(
        true,
        OnlineTimeZoneRules(fetch = { fixture(it) }, phoneVersion = { "2021a" }, phoneRules = { ZoneId.of(it).rules })
    )

    /** ON, but every tzurl.org request fails in this process. */
    private val onUnreachable = RuleSnapshot(
        true,
        OnlineTimeZoneRules(fetch = { throw IOException("timeout") }, phoneVersion = { "2021a" }, phoneRules = { ZoneId.of(it).rules })
    )

    private val vancouver = "America/Vancouver"
    private val start = "DTSTART;TZID=America/Vancouver:20261201T100000"
    private val end = "DTEND;TZID=America/Vancouver:20261201T110000"
    private val untouched = TimeEdits(startUntouched = true, endUntouched = true)

    private fun plan(
        rules: RuleSnapshot,
        startIso: String,
        endIso: String,
        edits: TimeEdits = TimeEdits.UNKNOWN,
        standalone: Boolean = true,
        cachedStart: String? = start,
        cachedEnd: String? = end
    ) = UploadTimes.plan(rules, vancouver, ms(startIso), ms(endIso), standalone, cachedStart, cachedEnd, edits)

    @Test
    fun `an untouched time goes back as Proton's own text whatever rules stored it - the online rules failing in this run`() {
        StalePhoneJvm.assume()
        // Stored with the online rules in an earlier process (17:00Z); this process cannot reach tzurl.org.
        // The phone's rules would write 09:00: a title-only edit would have moved the event on Proton.
        val p = plan(onUnreachable, "2026-12-01T17:00:00Z", "2026-12-01T18:00:00Z", untouched)
        assertEquals("20261201T100000", p.startText)
        assertEquals("20261201T110000", p.endText)
        assertEquals("20261201T090000", ICalUtils.formatLocalTimestamp(ms("2026-12-01T17:00:00Z"), vancouver))
    }

    @Test
    fun `an untouched time goes back as Proton's own text after the switch was turned off`() {
        val p = plan(RuleSnapshot.PHONE_ONLY, "2026-12-01T17:00:00Z", "2026-12-01T18:00:00Z", untouched)
        assertEquals("20261201T100000", p.startText)
        assertEquals("20261201T110000", p.endText)
        assertNull("OFF: the invitation describes the phone's rules", p.inviteRules)
    }

    @Test
    fun `an untouched time goes back as Proton's own text for a row an older version stored wrong`() {
        // 1.11.4 with updates on stored Vancouver after November 2026 nine hours early.
        val p = plan(on, "2026-12-01T08:00:00Z", "2026-12-01T09:00:00Z", untouched)
        assertEquals("20261201T100000", p.startText)
        assertEquals("20261201T110000", p.endText)
    }

    @Test
    fun `a time the user typed goes back as the phone's rules give it, also in ON mode`() {
        StalePhoneJvm.assume()
        // The calendar app turned the typed 11:00 into 19:00Z with the phone's -08; the online -07 would say 12:00.
        val p = plan(on, "2026-12-01T19:00:00Z", "2026-12-01T20:00:00Z")
        assertEquals("20261201T110000", p.startText)
        assertEquals("20261201T120000", p.endText)
        // A new event: no line from Proton at all.
        val created = plan(on, "2026-12-01T18:00:00Z", "2026-12-01T19:00:00Z", cachedStart = null, cachedEnd = null)
        assertEquals("20261201T100000", created.startText)
        assertEquals("20261201T110000", created.endText)
        // The online rules still describe the zone in the invitation: they differ from the phone's here.
        assertNotNull(created.inviteRules)
    }

    @Test
    fun `only the end was changed - the start stays Proton's text, the end is the typed one`() {
        StalePhoneJvm.assume()
        val p = plan(onUnreachable, "2026-12-01T17:00:00Z", "2026-12-01T21:00:00Z", TimeEdits(startUntouched = true, endUntouched = false))
        assertEquals("20261201T100000", p.startText)
        assertEquals("20261201T130000", p.endText)
    }

    @Test
    fun `without a record, Proton's text still applies where the stored instant is exactly what it gives`() {
        StalePhoneJvm.assume()
        // Under the online rules (available) or the phone's.
        assertEquals("20261201T100000", plan(on, "2026-12-01T17:00:00Z", "2026-12-01T18:00:00Z").startText)
        assertEquals("20261201T100000", plan(on, "2026-12-01T18:00:00Z", "2026-12-01T19:00:00Z").startText)
        assertEquals("20261201T100000", plan(RuleSnapshot.PHONE_ONLY, "2026-12-01T18:00:00Z", "2026-12-01T19:00:00Z").startText)
        // Neither: a typed time, written with the phone's rules.
        assertEquals("20261201T090000", plan(RuleSnapshot.PHONE_ONLY, "2026-12-01T17:00:00Z", "2026-12-01T18:00:00Z").startText)
    }

    @Test
    fun `a series - untouched is Proton's text, typed is the phone's`() {
        StalePhoneJvm.assume()
        val untouchedSeries = plan(RuleSnapshot.PHONE_ONLY, "2026-12-01T17:00:00Z", "2026-12-01T18:00:00Z", untouched, standalone = false)
        assertEquals("20261201T100000", untouchedSeries.startText)
        val typed = plan(on, "2026-12-01T19:00:00Z", "2026-12-01T20:00:00Z", standalone = false)
        assertEquals("20261201T110000", typed.startText)
        assertNull("a series never uses the online rules", typed.inviteRules)
    }

    @Test
    fun `Proton's text is used only for the same zone`() {
        // The user moved the event to Berlin: the line names another zone, so the instant is written in the new one.
        val p = UploadTimes.plan(RuleSnapshot.PHONE_ONLY, "Europe/Berlin", ms("2026-12-01T09:00:00Z"), ms("2026-12-01T10:00:00Z"),
            true, start, end, untouched)
        assertEquals("20261201T100000", p.startText)
        assertEquals("20261201T110000", p.endText)
        // A spelling Proton normalises reads as the same zone: the upload writes Kyiv as Proton's "Europe/Kiev".
        val q = UploadTimes.plan(RuleSnapshot.PHONE_ONLY, "Europe/Kiev", ms("2026-12-01T07:00:00Z"), ms("2026-12-01T08:00:00Z"),
            true, "DTSTART;TZID=Europe/Kyiv:20261201T100000", null, untouched)
        assertEquals("Proton's text, where the phone's rules would say 09:00", "20261201T100000", q.startText)
        assertEquals("no line for the end: the phone's rules", "20261201T100000", q.endText)
    }

    @Test
    fun `where both rule sets agree at the start and the end, the invitation keeps the phone's rules`() {
        StalePhoneJvm.assume()
        // July 2026: Vancouver is at -07 under both.
        assertNull(plan(on, "2026-07-15T17:00:00Z", "2026-07-15T18:00:00Z", cachedStart = null, cachedEnd = null).inviteRules)
        // An event that ends where they disagree takes the online rules.
        assertNotNull(plan(on, "2026-07-15T17:00:00Z", "2026-12-01T18:00:00Z", cachedStart = null, cachedEnd = null).inviteRules)
    }

    // ---- the record of the synced time columns ----

    /** EventConflictGuard.SHARED_COLUMNS values with the given time columns. */
    private fun shared(start: String?, end: String?, duration: String?, zone: String?, title: String = "t") =
        listOf(title, null, null, start, end, duration, "0", if (duration != null) "FREQ=WEEKLY" else null, null, null, zone, "1", "0")

    @Test
    fun `the record holds the time columns, and tells which times are untouched`() {
        val synced = shared("1796144400000", "1796148000000", null, vancouver)
        val record = SyncedTimes.encode(synced)
        assertEquals("1|1796144400000|1796148000000||America/Vancouver", record)
        val baseline = EventConflictGuard.fingerprintOf(synced)

        // Nothing shared changed: both untouched, record or not.
        val same = SyncedTimes.edits(synced, baseline, null)
        assertTrue(same.startUntouched && same.endUntouched)
        // The title changed, the times did not.
        val retitled = SyncedTimes.edits(shared("1796144400000", "1796148000000", null, vancouver, title = "new"), baseline, record)
        assertTrue(retitled.startUntouched && retitled.endUntouched)
        // Only the end moved.
        val longer = SyncedTimes.edits(shared("1796144400000", "1796151600000", null, vancouver, title = "new"), baseline, record)
        assertTrue(longer.startUntouched)
        assertFalse(longer.endUntouched)
        // Another zone: neither is Proton's text any more.
        val moved = SyncedTimes.edits(shared("1796144400000", "1796148000000", null, "Europe/Berlin", title = "new"), baseline, record)
        assertFalse(moved.startUntouched || moved.endUntouched)
        // Without a record, or with one this version cannot read, nothing is known.
        for (r in listOf(null, "", "garbage", "2|1|2||x", "1|1796144400000|1796148000000")) {
            val e = SyncedTimes.edits(shared("1796144400000", "1796148000000", null, vancouver, title = "new"), baseline, r)
            assertFalse("$r", e.startUntouched || e.endUntouched)
        }
        assertFalse(SyncedTimes.edits(null, baseline, record).startUntouched)
    }

    @Test
    fun `a series' end is its start plus its duration - both must be unchanged`() {
        val synced = shared("1796144400000", null, "P3600S", vancouver)
        val record = SyncedTimes.encode(synced)
        val baseline = EventConflictGuard.fingerprintOf(synced)
        val retitled = SyncedTimes.edits(shared("1796144400000", null, "P3600S", vancouver, title = "new"), baseline, record)
        assertTrue(retitled.startUntouched && retitled.endUntouched)
        val laterStart = SyncedTimes.edits(shared("1796148000000", null, "P3600S", vancouver, title = "new"), baseline, record)
        assertFalse(laterStart.startUntouched || laterStart.endUntouched)
        val longer = SyncedTimes.edits(shared("1796144400000", null, "P7200S", vancouver, title = "new"), baseline, record)
        assertTrue(longer.startUntouched)
        assertFalse(longer.endUntouched)
    }
}

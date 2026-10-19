package me.proton.sync.calendar

import me.proton.sync.calendar.EventRetimePlanner.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The re-time pass's decisions, pure. "Stored" times play what an older version (ical4j's arithmetic) left
 * in the provider; "expected" is what the download writes now. The phone's rules are the JVM's. The pass only
 * ever tags: a dirty row is re-applied once its upload has gone, which sends Proton's own text for every time the
 * user did not touch (UploadTimesTest).
 */
class EventRetimePlannerTest {

    private fun at(local: String, zone: String) =
        LocalDateTime.parse(local, IcalTimeValues.LOCAL_FORMAT).atZone(ZoneId.of(zone)).toInstant().toEpochMilli()

    private val hour = 3_600_000L

    private fun mapping(
        id: String,
        row: Long,
        start: String? = "DTSTART;TZID=Africa/Cairo:20270715T100000",
        end: String? = "DTEND;TZID=Africa/Cairo:20270715T110000",
        uid: String = "uid-$id",
        recurrenceId: String? = null,
        baseline: String? = "fp",
        calendar: String = "cal"
    ) = EventRetimePlanner.Mapping(id, calendar, row, start, end, uid, recurrenceId, baseline)

    private fun row(
        id: Long,
        start: Long?,
        end: Long? = start?.plus(hour),
        duration: String? = null,
        allDay: Boolean = false,
        rrule: String? = null,
        originalSyncId: String? = null,
        originalInstanceTime: Long? = null,
        dirty: Boolean = false,
        deleted: Boolean = false,
        fingerprint: String = "fp"
    ) = EventRetimePlanner.Row(
        id, start, end, duration, allDay, rrule, null, originalSyncId, originalInstanceTime, dirty, deleted,
        listOf("t", null, null, start?.toString(), end?.toString(), duration, if (allDay) "1" else "0", rrule, null, null, "Africa/Cairo", "1", "0"),
        fingerprint
    )

    private fun plan(
        mappings: List<EventRetimePlanner.Mapping>,
        rows: List<EventRetimePlanner.Row>,
        placeholders: Map<String, List<Long>> = emptyMap(),
        held: Set<String> = emptySet(),
        available: Boolean = true
    ) = EventRetimePlanner.plan(mappings, rows.associateBy { it.id }, placeholders, held, ZoneRulesLookup.PHONE,
        available = { _, _, _ -> available }).associateBy { it.mapping.protonEventId }

    private val cairo = at("20270715T100000", "Africa/Cairo")

    @Test
    fun `a row the download would write identically is left alone`() {
        val d = plan(listOf(mapping("a", 1)), listOf(row(1, cairo)))["a"]!!
        assertEquals(Verdict.CONSISTENT, d.verdict)
        assertEquals("Africa/Cairo", d.zone)
    }

    @Test
    fun `a clean row stored with other rules is marked for download, with its delta`() {
        // ical4j's bundled 2022g kept Egypt without summer time: an hour late.
        val d = plan(listOf(mapping("a", 1)), listOf(row(1, cairo + hour)))["a"]!!
        assertEquals(Verdict.TAG, d.verdict)
        assertEquals(60L, d.deltaMinutes)
        assertTrue(d.reason.contains("start"))
    }

    @Test
    fun `a different end alone is a difference too`() {
        val d = plan(listOf(mapping("a", 1)), listOf(row(1, cairo, end = cairo + 2 * hour)))["a"]!!
        assertEquals(Verdict.TAG, d.verdict)
        assertEquals("end", d.reason)
    }

    @Test
    fun `rows that cannot be evaluated are never touched`() {
        val ds = plan(
            listOf(
                mapping("noLine", 1, start = null),
                mapping("unknownZone", 2, start = "DTSTART;TZID=Mars/Olympus:20270715T100000"),
                mapping("garbled", 3, start = "DTSTART;TZID=Africa/Cairo:2027-07-15")
            ),
            listOf(row(1, 0L), row(2, 0L), row(3, 0L))
        )
        for (id in listOf("noLine", "unknownZone", "garbled")) assertEquals(id, Verdict.UNEVALUABLE, ds[id]!!.verdict)
    }

    @Test
    fun `all-day, UTC and floating rows need no zone rules`() {
        val ds = plan(
            listOf(
                mapping("allDay", 1, start = "DTSTART;VALUE=DATE:20270715", end = null),
                mapping("utc", 2, start = "DTSTART:20270715T100000Z", end = null),
                mapping("tzUtc", 3, start = "DTSTART;TZID=UTC:20270715T100000", end = null),
                mapping("floating", 4, start = "DTSTART:20270715T100000", end = null),
                mapping("allDayRow", 5)
            ),
            listOf(row(1, 0L, allDay = true), row(2, 1L), row(3, 2L), row(4, 3L), row(5, 4L, allDay = true))
        )
        for (id in listOf("allDay", "utc", "tzUtc", "floating", "allDayRow")) assertEquals(id, Verdict.RULE_FREE, ds[id]!!.verdict)
    }

    @Test
    fun `gone, held and kept-mine events are left alone`() {
        val ds = plan(
            listOf(mapping("gone", 1), mapping("deleted", 2), mapping("held", 3)),
            listOf(row(2, cairo + hour, deleted = true), row(3, cairo + hour, dirty = true, fingerprint = "other")),
            held = setOf("held")
        )
        assertEquals(Verdict.GONE, ds["gone"]!!.verdict)
        assertEquals(Verdict.GONE, ds["deleted"]!!.verdict)
        assertEquals(Verdict.HELD, ds["held"]!!.verdict)
    }

    @Test
    fun `a clean row that differs from the last agreed state is not reverted by a download`() {
        val d = plan(listOf(mapping("a", 1)), listOf(row(1, cairo + hour, fingerprint = "edited")))["a"]!!
        assertEquals(Verdict.LEAVE_LOCAL_CHANGE, d.verdict)
        // No baseline at all is no evidence of a local change: marked.
        assertEquals(Verdict.TAG, plan(listOf(mapping("b", 2, baseline = null)), listOf(row(2, cairo + hour)))["b"]!!.verdict)
    }

    @Test
    fun `a dirty row is only marked, whatever its change - the pass writes nothing to the phone`() {
        // Its upload sends Proton's own text for the untouched times (UploadTimes), then the download re-applies it.
        val ds = plan(
            listOf(mapping("personal", 1), mapping("edited", 2), mapping("noBaseline", 3, baseline = null)),
            listOf(
                row(1, cairo + hour, dirty = true),
                row(2, cairo + hour, dirty = true, fingerprint = "edited"),
                row(3, cairo + hour, dirty = true)
            )
        )
        for (id in listOf("personal", "edited", "noBaseline")) {
            assertEquals(id, Verdict.TAG_DIRTY, ds[id]!!.verdict)
            assertTrue(id, ds[id]!!.tags)
        }
    }

    @Test
    fun `a series compares its duration, and pulls in its overrides when flagged`() {
        val series = mapping("series", 1, start = "DTSTART;TZID=Africa/Cairo:20270715T100000",
            end = "DTEND;TZID=Africa/Cairo:20270715T113000", uid = "u")
        val override = mapping("override", 2, start = "DTSTART;TZID=Africa/Cairo:20270722T120000", end = null, uid = "u",
            recurrenceId = "RECURRENCE-ID;TZID=Africa/Cairo:20270722T100000")
        val unrelated = mapping("unrelated", 3)
        val ds = plan(
            listOf(series, override, unrelated),
            listOf(
                row(1, cairo + hour, end = null, duration = "P5400S", rrule = "FREQ=WEEKLY"),
                row(2, at("20270722T120000", "Africa/Cairo"), end = at("20270722T120000", "Africa/Cairo"),
                    originalSyncId = "series", originalInstanceTime = at("20270722T100000", "Africa/Cairo")),
                row(3, cairo)
            )
        )
        assertEquals(Verdict.TAG, ds["series"]!!.verdict)
        assertEquals("override of a series being re-timed", ds["override"]!!.reason)
        assertEquals(Verdict.TAG, ds["override"]!!.verdict)
        assertEquals(Verdict.CONSISTENT, ds["unrelated"]!!.verdict)
        // The duration alone.
        val d = plan(listOf(series), listOf(row(1, cairo, end = null, duration = "P3600S", rrule = "FREQ=WEEKLY")))["series"]!!
        assertEquals(Verdict.TAG, d.verdict)
        assertEquals("duration", d.reason)
    }

    @Test
    fun `an override's occurrence - Proton's whole line is checked, an older bare local value means downloading it again`() {
        val series = mapping("series", 1, end = null, uid = "u")
        val seriesRow = row(1, cairo, end = null, duration = "P0S", rrule = "FREQ=WEEKLY")
        val occurrence = at("20270722T100000", "Africa/Cairo")
        fun overrideWith(cached: String, storedOccurrence: Long, dirty: Boolean = false) = plan(
            listOf(series, mapping("o", 2, start = "DTSTART;TZID=Africa/Cairo:20270722T120000", end = null, uid = "u", recurrenceId = cached)),
            listOf(seriesRow, row(2, at("20270722T120000", "Africa/Cairo"), end = at("20270722T120000", "Africa/Cairo"),
                originalSyncId = "series", originalInstanceTime = storedOccurrence, dirty = dirty))
        )["o"]!!
        assertEquals(Verdict.CONSISTENT, overrideWith("RECURRENCE-ID;TZID=Africa/Cairo:20270722T100000", occurrence).verdict)
        val wrong = overrideWith("RECURRENCE-ID;TZID=Africa/Cairo:20270722T100000", occurrence + hour)
        assertEquals(Verdict.TAG, wrong.verdict)
        assertEquals("occurrence", wrong.reason)
        // A bare local value is ical4j's own text from before 1.11.5, which in the zones it misread (Vancouver
        // after October 2026, Lord Howe) is the misread instant again: it can neither confirm nor correct the
        // occurrence, so the override is downloaded once more, which stores Proton's line.
        for (stored in listOf(occurrence, occurrence + hour)) {
            val legacy = overrideWith("20270722T100000", stored)
            assertEquals(Verdict.TAG, legacy.verdict)
            assertEquals("legacy occurrence", legacy.reason)
        }
        assertEquals(Verdict.TAG_DIRTY, overrideWith("20270722T100000", occurrence, dirty = true).verdict)
        // A bare UTC value or date is exact, and still checked.
        assertEquals(Verdict.CONSISTENT, overrideWith("20270722T070000Z", occurrence).verdict)
        assertEquals("occurrence", overrideWith("20270722T070000Z", occurrence + hour).reason)
    }

    @Test
    fun `an override whose own start needs no rules still has its occurrence checked`() {
        val series = mapping("series", 1, end = null, uid = "u")
        val seriesRow = row(1, cairo, end = null, duration = "P0S", rrule = "FREQ=WEEKLY")
        val occurrence = at("20270722T100000", "Africa/Cairo")
        fun utcOverride(storedOccurrence: Long) = plan(
            listOf(series, mapping("o", 2, start = "DTSTART:20270722T090000Z", end = null, uid = "u",
                recurrenceId = "RECURRENCE-ID;TZID=Africa/Cairo:20270722T100000")),
            listOf(seriesRow, row(2, Instant.parse("2027-07-22T09:00:00Z").toEpochMilli(), originalSyncId = "series",
                originalInstanceTime = storedOccurrence))
        )["o"]!!
        assertEquals(Verdict.RULE_FREE, utcOverride(occurrence).verdict)
        val unlinked = utcOverride(occurrence + hour)
        assertEquals(Verdict.TAG, unlinked.verdict)
        assertEquals("occurrence", unlinked.reason)
    }

    @Test
    fun `in a fall-back overlap, the series and its override linked at the provider's instant are consistent`() {
        // New York, 2026-11-01: 01:15 occurs at 05:15Z and at 06:15Z. CalendarProvider expands the series with
        // GregorianCalendar, which gives 06:15Z, and links the override only at that millisecond.
        val z = "America/New_York"
        val series = mapping("series", 1, start = "DTSTART;TZID=$z:20261018T011500", end = "DTEND;TZID=$z:20261018T021500", uid = "u")
        val seriesRow = row(1, at("20261018T011500", z), end = null, duration = "P3600S", rrule = "FREQ=WEEKLY")
        fun override(storedOccurrence: Long) = plan(
            listOf(series, mapping("o", 2, start = "DTSTART;TZID=$z:20261101T090000", end = "DTEND;TZID=$z:20261101T100000",
                uid = "u", recurrenceId = "RECURRENCE-ID;TZID=$z:20261101T011500")),
            listOf(seriesRow, row(2, at("20261101T090000", z), originalSyncId = "series", originalInstanceTime = storedOccurrence))
        )
        val linked = override(Instant.parse("2026-11-01T06:15:00Z").toEpochMilli())
        assertEquals(Verdict.CONSISTENT, linked["series"]!!.verdict)
        assertEquals(Verdict.CONSISTENT, linked["o"]!!.verdict)
        val earlier = override(Instant.parse("2026-11-01T05:15:00Z").toEpochMilli())["o"]!!
        assertEquals(Verdict.TAG, earlier.verdict)
        assertEquals("occurrence", earlier.reason)
    }

    @Test
    fun `an end in a zone whose rules cannot be had makes the row unevaluable too`() {
        // A flight from Berlin to Edmonton; this run could not get Edmonton's online rules.
        val flight = mapping("flight", 1, start = "DTSTART;TZID=Europe/Berlin:20261201T100000",
            end = "DTEND;TZID=America/Edmonton:20261201T130000")
        val d = EventRetimePlanner.plan(
            listOf(flight), mapOf(1L to row(1, at("20261201T100000", "Europe/Berlin"), end = at("20261201T130000", "America/Edmonton") + hour)),
            emptyMap(), emptySet(), ZoneRulesLookup.PHONE,
            available = { zone, _, _ -> zone.id != "America/Edmonton" }
        ).single()
        assertEquals(Verdict.UNEVALUABLE, d.verdict)
        assertTrue(d.unavailable)
        assertTrue(d.reason, d.reason.contains("America/Edmonton"))
    }

    @Test
    fun `a flagged series never pulls in an override whose rules are unavailable, nor one with a local change`() {
        val series = mapping("series", 1, end = null, uid = "u")
        val seriesRow = row(1, cairo + hour, end = null, duration = "P0S", rrule = "FREQ=WEEKLY")
        val occurrence = at("20270722T100000", "Africa/Cairo")
        val noRules = mapping("noRules", 2, start = "DTSTART;TZID=America/Edmonton:20270722T120000", end = null, uid = "u",
            recurrenceId = "RECURRENCE-ID;TZID=Africa/Cairo:20270722T100000")
        val edited = mapping("edited", 3, start = "DTSTART;TZID=Africa/Cairo:20270729T120000", end = null, uid = "u",
            recurrenceId = "RECURRENCE-ID;TZID=Africa/Cairo:20270729T100000")
        val ds = EventRetimePlanner.plan(
            listOf(series, noRules, edited),
            listOf(
                seriesRow,
                row(2, at("20270722T120000", "America/Edmonton"), originalSyncId = "series", originalInstanceTime = occurrence),
                row(3, at("20270729T120000", "Africa/Cairo"), originalSyncId = "series",
                    originalInstanceTime = at("20270729T100000", "Africa/Cairo"), fingerprint = "edited")
            ).associateBy { it.id },
            emptyMap(), emptySet(), ZoneRulesLookup.PHONE,
            available = { zone, standalone, _ -> !(standalone && zone.id == "America/Edmonton") }
        ).associateBy { it.mapping.protonEventId }
        assertEquals(Verdict.TAG, ds["series"]!!.verdict)
        assertEquals(Verdict.UNEVALUABLE, ds["noRules"]!!.verdict)
        assertTrue(ds["noRules"]!!.unavailable)
        assertEquals(Verdict.LEAVE_LOCAL_CHANGE, ds["edited"]!!.verdict)
    }

    @Test
    fun `a consistent series with an exclusion off its wall-clock time is marked - the placeholder came from other rules`() {
        val series = mapping("series", 1, end = null)
        val seriesRow = row(1, cairo, end = null, duration = "P0S", rrule = "FREQ=WEEKLY")
        val right = at("20270722T100000", "Africa/Cairo")
        assertEquals(Verdict.CONSISTENT, plan(listOf(series), listOf(seriesRow), placeholders = mapOf("series" to listOf(right)))["series"]!!.verdict)
        val d = plan(listOf(series), listOf(seriesRow), placeholders = mapOf("series" to listOf(right + hour)))["series"]!!
        assertEquals(Verdict.TAG, d.verdict)
        assertEquals("exclusion", d.reason)
        // A rule that sets its own hours is not judged by wall-clock time.
        val byHour = row(1, cairo, end = null, duration = "P0S", rrule = "FREQ=DAILY;BYHOUR=10,14")
        assertEquals(Verdict.CONSISTENT, plan(listOf(series), listOf(byHour), placeholders = mapOf("series" to listOf(right + 4 * hour)))["series"]!!.verdict)
    }

    @Test
    fun `rules that cannot be had right now make a row unevaluable, never compared against a fallback`() {
        val d = plan(listOf(mapping("a", 1)), listOf(row(1, cairo + hour)), available = false)["a"]!!
        assertEquals(Verdict.UNEVALUABLE, d.verdict)
        assertTrue("worth another run", d.unavailable)
        assertTrue(EventRetimePlanner.summary(listOf(d)).contains("rules unavailable for 1"))
        // The other unevaluable rows are not: another run would find the same.
        assertFalse(plan(listOf(mapping("b", 2, start = null)), listOf(row(2, cairo)))["b"]!!.unavailable)
    }

    @Test
    fun `the pure fingerprint is the collision check's - null and empty alike, trimmed, unit-separated`() {
        val sep = 31.toChar()
        val expected = java.security.MessageDigest.getInstance("SHA-256")
            .digest("a$sep${sep}b$sep".toByteArray())
            .joinToString("") { "%02x".format(it) }
        assertEquals(expected, EventConflictGuard.fingerprintOf(listOf("a", null, " b ")))
        assertEquals(EventConflictGuard.fingerprintOf(listOf("a", "", "b")), EventConflictGuard.fingerprintOf(listOf("a", null, "b")))
        assertEquals(13, EventConflictGuard.SHARED_COLUMNS.size)
        assertEquals("dtstart", EventConflictGuard.SHARED_COLUMNS[EventConflictGuard.COLUMN_DTSTART])
        assertEquals("dtend", EventConflictGuard.SHARED_COLUMNS[EventConflictGuard.COLUMN_DTEND])
        assertEquals("duration", EventConflictGuard.SHARED_COLUMNS[EventConflictGuard.COLUMN_DURATION])
        assertEquals("allDay", EventConflictGuard.SHARED_COLUMNS[EventConflictGuard.COLUMN_ALL_DAY])
        assertEquals("rrule", EventConflictGuard.SHARED_COLUMNS[EventConflictGuard.COLUMN_RRULE])
        assertEquals("rdate", EventConflictGuard.SHARED_COLUMNS[EventConflictGuard.COLUMN_RDATE])
        assertEquals("eventTimezone", EventConflictGuard.SHARED_COLUMNS[EventConflictGuard.COLUMN_EVENT_TIMEZONE])
    }

    @Test
    fun `the pass reads a provider row into the planner's row by those columns`() {
        val shared = listOf("t", "d", "l", "1796144400000", null, "P3600S", "1", "FREQ=WEEKLY", "20261201T100000", "x", "Africa/Cairo", "1", "0")
        val r = EventRetimePass.rowOf(7L, shared, "series", 1796144400000L, dirty = true, deleted = false)
        assertEquals(1796144400000L, r.dtStart)
        assertNull(r.dtEnd)
        assertEquals("P3600S", r.duration)
        assertTrue(r.allDay)
        assertEquals("FREQ=WEEKLY", r.rrule)
        assertEquals("20261201T100000", r.rdate)
        assertTrue(r.isSeries && r.dirty && !r.deleted)
        assertEquals("series", r.originalSyncId)
        assertEquals(EventConflictGuard.fingerprintOf(shared), r.fingerprint)
    }

    @Test
    fun `idempotent and bounded - once corrected, a second run finds nothing`() {
        val mappings = (1..50).map { mapping("e$it", it.toLong()) }
        val stale = (1..50).map { row(it.toLong(), cairo + hour) }
        val first = plan(mappings, stale)
        assertEquals(50, first.size)
        assertTrue(first.values.all { it.verdict == Verdict.TAG })
        val corrected = (1..50).map { row(it.toLong(), cairo) }
        assertTrue(plan(mappings, corrected).values.all { it.verdict == Verdict.CONSISTENT })
        val summary = EventRetimePlanner.summary(first.values.toList())
        assertTrue(summary, summary.startsWith("tag 50"))
        assertTrue(summary, summary.contains("Africa/Cairo 50 (+60min x50)"))
    }
}

package me.proton.sync.calendar

import me.proton.sync.calendar.RangeTrimPlanner.Keep
import me.proton.sync.calendar.RangeTrimPlanner.Mapping
import me.proton.sync.calendar.RangeTrimPlanner.Row
import me.proton.sync.calendar.RangeTrimPlanner.SeriesEnd
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * "Remove older events from this phone" ([RangeTrimPlanner]): which series groups of one large calendar may leave the
 * phone because they ended before the range. A group goes whole or stays whole, and anything unsure keeps it.
 */
class RangeTrimPlannerTest {

    private val zone = ZoneId.of("Europe/Berlin")
    private val now = t("2026-11-14T12:00:00Z")
    // One day back: the range starts 2026-11-13 12:00Z, the cutoff is local midnight a day earlier.
    private val cutoff = RangeTrimPlanner.cutoffMillis(now, 1, zone)
    private val hour = 3_600_000L
    private val day = 24 * hour
    private val longAgo = t("2026-01-10T00:00:00Z") / 1000

    private fun t(s: String) = Instant.parse(s).toEpochMilli()

    private fun row(
        id: Long,
        start: Long?,
        end: Long? = start?.plus(hour),
        uid: String? = "u$id",
        syncId: String? = "p$id",
        origSync: String? = null,
        origTime: Long? = null,
        rrule: String? = null,
        rdate: String? = null,
        duration: String? = null,
        lastDate: Long? = if (rrule == null && rdate == null) end else null,
        cancelled: Boolean = false,
        dirty: Boolean = false,
        deleted: Boolean = false,
        print: String = "fp$id",
    ) = Row(id, syncId, origSync, origTime, uid, start, end, duration, rrule, rdate, "Europe/Berlin", lastDate, cancelled, dirty, deleted, print)

    private fun map(id: Long, protonId: String = "p$id", modified: Long = longAgo, print: String? = "fp$id", rid: String? = null) =
        Mapping(protonId, id, modified, print, rid)

    private fun plan(
        rows: List<Row>,
        mappings: List<Mapping> = rows.filter { it.syncId != null }.map { map(it.id, it.syncId!!) },
        allTime: Set<String> = mappings.map { it.protonEventId }.toSet(),
        window: Set<String> = emptySet(),
        held: Set<String> = emptySet(),
        cancels: Set<String> = emptySet(),
        invites: Set<String> = emptySet(),
    ) = RangeTrimPlanner.plan(RangeTrimPlanner.Input(rows, mappings, cutoff, now, allTime, window, held, cancels, invites))

    /** A weekly series of Mondays at 10:00Z from 2 March that ended on 1 June, with its single edits and placeholders. */
    private fun finishedSeries(): List<Row> = listOf(
        row(10, t("2026-03-02T10:00:00Z"), end = null, uid = "series@x", rrule = "FREQ=WEEKLY;UNTIL=20260601T100000Z",
            duration = "PT1H", lastDate = t("2026-06-01T11:00:00Z")),
        row(11, t("2026-03-10T10:00:00Z"), uid = "series@x", origSync = "p10", origTime = t("2026-03-09T10:00:00Z")),
        row(12, t("2026-03-16T12:00:00Z"), uid = "series@x", origSync = "p10", origTime = t("2026-03-16T10:00:00Z")),
        // Proton's cancelled single edit: its own id and mapping.
        row(13, t("2026-03-23T10:00:00Z"), uid = "series@x", origSync = "p10", origTime = t("2026-03-23T10:00:00Z"), cancelled = true),
    ) + listOf("2026-03-30", "2026-04-06", "2026-04-13").mapIndexed { i, d ->
        // The download's EXDATE placeholders: no id, no UID, no mapping.
        row(14L + i, t("${d}T10:00:00Z"), end = null, uid = null, syncId = null, origSync = "p10",
            origTime = t("${d}T10:00:00Z"), duration = "PT1H", lastDate = null, cancelled = true)
    }

    @Test
    fun `the cutoff is local midnight a day before the range starts`() {
        assertEquals(t("2026-11-11T23:00:00Z"), cutoff)
        assertEquals(t("2026-11-12T00:00:00Z"), RangeTrimPlanner.cutoffMillis(now, 1, ZoneOffset.UTC))
        assertEquals(t("2026-08-14T22:00:00Z"), RangeTrimPlanner.cutoffMillis(now, 90, zone))
        // A Past events below 1, which only a hand-edited backup can store, counts as 1: never today or later.
        for (bad in listOf(0L, -1L, -30L)) assertEquals("$bad", cutoff, RangeTrimPlanner.cutoffMillis(now, bad, zone))
    }

    @Test
    fun `a one-off goes when it ended before the cutoff, and stays when it ends at the cutoff or later`() {
        val p = plan(listOf(row(1, cutoff - 3 * hour, cutoff - 2 * hour), row(2, cutoff - hour, cutoff), row(3, cutoff - hour, cutoff + 1)))
        assertEquals(listOf(listOf(1L)), p.remove.map { it.rowIds })
        assertEquals(mapOf(Keep.REACHES_RANGE to 2), p.kept)
    }

    @Test
    fun `all-day events on the day before the range and on its first day stay in every time zone`() {
        for (z in listOf("-12:00", "Z", "+01:00", "+14:00").map { ZoneId.of(it) }) {
            val c = RangeTrimPlanner.cutoffMillis(now, 1, z)
            val firstDay = Instant.ofEpochMilli(now - day).atZone(z).toLocalDate()
            fun allDay(id: Long, date: LocalDate) = row(id, DownloadTimes.utcMidnight(date), DownloadTimes.utcMidnight(date.plusDays(1)))
            val rows = listOf(allDay(1, firstDay.minusDays(3)), allDay(2, firstDay.minusDays(1)), allDay(3, firstDay))
            val p = RangeTrimPlanner.plan(
                RangeTrimPlanner.Input(rows, rows.map { map(it.id) }, c, now, setOf("p1", "p2", "p3"), emptySet(), emptySet(), emptySet(), emptySet())
            )
            assertEquals("zone $z", listOf(listOf(1L)), p.remove.map { it.rowIds })
        }
    }

    @Test
    fun `a finished series goes as one group with its edits, Proton's cancelled edit and the placeholders`() {
        val p = plan(finishedSeries())
        assertEquals(1, p.remove.size)
        val g = p.remove.single()
        assertEquals((10L..16L).toList(), g.rowIds)
        assertEquals(listOf("p10", "p11", "p12", "p13"), g.protonIds)
        assertEquals(6, g.linkedRows)
        assertTrue(g.linkIds.containsAll(setOf("p10", "p11", "p12", "p13")))
        assertEquals(7, p.rowCount)
    }

    @Test
    fun `a finished series stays whole when one occurrence was moved into the range`() {
        val rows = finishedSeries().map { if (it.id == 12L) it.copy(dtStart = t("2026-11-20T10:00:00Z"), dtEnd = t("2026-11-20T11:00:00Z"), lastDate = t("2026-11-20T11:00:00Z")) else it }
        val p = plan(rows)
        assertEquals(emptyList<RangeTrimPlanner.Group>(), p.remove)
        assertEquals(mapOf(Keep.REACHES_RANGE to 1), p.kept)
    }

    @Test
    fun `an edit whose original date is in the range stays, by ORIGINAL_INSTANCE_TIME or the cached RECURRENCE-ID`() {
        // No series on the phone: the edit alone, moved to the past from a date in the range.
        val unlinked = row(20, t("2026-03-10T10:00:00Z"))
        val p = plan(listOf(unlinked), mappings = listOf(map(20, rid = "RECURRENCE-ID:20261120T100000Z")))
        assertEquals(mapOf(Keep.REACHES_RANGE to 1), p.kept)
        val linked = row(21, t("2026-03-10T10:00:00Z"), origSync = "gone", origTime = t("2026-11-20T10:00:00Z"))
        assertEquals(mapOf(Keep.REACHES_RANGE to 1), plan(listOf(linked)).kept)
    }

    @Test
    fun `a series without an end stays, whatever its LAST_DATE, also with an RDATE`() {
        val endless = row(30, t("2019-01-07T09:00:00Z"), end = null, rrule = "FREQ=WEEKLY", duration = "PT1H")
        val placeholder = row(31, t("2019-01-14T09:00:00Z"), end = null, uid = null, syncId = null, origSync = "p30",
            origTime = t("2019-01-14T09:00:00Z"), duration = "PT1H", cancelled = true)
        assertEquals(mapOf(Keep.NO_END to 1), plan(listOf(endless, placeholder)).kept)
        // A finite LAST_DATE does not make an endless rule end.
        assertEquals(mapOf(Keep.NO_END to 1), plan(listOf(endless.copy(lastDate = t("2026-05-01T00:00:00Z")))).kept)
        val withRdate = endless.copy(rdate = "20260301T100000Z", lastDate = t("2026-03-01T11:00:00Z"))
        assertEquals(mapOf(Keep.NO_END to 1), plan(listOf(withRdate)).kept)
        // Two rule lines: the endless one decides.
        val twoLines = endless.copy(rrule = "FREQ=WEEKLY;UNTIL=20260301T000000Z\nFREQ=MONTHLY", lastDate = t("2026-03-01T00:00:00Z"))
        assertEquals(mapOf(Keep.NO_END to 1), plan(listOf(twoLines)).kept)
    }

    @Test
    fun `a COUNT series is expanded to its last occurrence, and both ends must be before the cutoff`() {
        val tenMondays = row(40, t("2026-03-02T10:00:00Z"), end = null, rrule = "FREQ=WEEKLY;BYDAY=MO;COUNT=10",
            duration = "PT1H", lastDate = t("2026-05-04T11:00:00Z"))
        // The tenth Monday is 4 May; its end plus the day of margin.
        assertEquals(SeriesEnd.At(t("2026-05-05T11:00:00Z")), RangeTrimPlanner.seriesEnd(tenMondays))
        assertEquals(1, plan(listOf(tenMondays)).remove.size)
        // DTSTART on a Tuesday counts as the first occurrence (RFC 5545), and the expansion skips it: one occurrence
        // late, never early.
        val tuesdayStart = tenMondays.copy(dtStart = t("2026-03-03T10:00:00Z"))
        assertTrue((RangeTrimPlanner.seriesEnd(tuesdayStart) as SeriesEnd.At).millis >= t("2026-05-04T11:00:00Z"))
        // A LAST_DATE before the cutoff while the series itself runs on: stays.
        val sixty = tenMondays.copy(rrule = "FREQ=WEEKLY;BYDAY=MO;COUNT=60")
        assertEquals(mapOf(Keep.REACHES_RANGE to 1), plan(listOf(sixty)).kept)
        // The provider left no LAST_DATE: stays.
        assertEquals(mapOf(Keep.NO_END to 1), plan(listOf(tenMondays.copy(lastDate = null))).kept)
        // A COUNT the rule can never reach: stays.
        val never = tenMondays.copy(rrule = "FREQ=YEARLY;BYMONTH=2;BYMONTHDAY=30;COUNT=2")
        assertEquals(SeriesEnd.Unknown, RangeTrimPlanner.seriesEnd(never))
        assertEquals(mapOf(Keep.UNKNOWN_TIME to 1), plan(listOf(never)).kept)
    }

    @Test
    fun `an RDATE series ends at its latest date, and a PERIOD or a missing length keeps it`() {
        val rdates = row(50, t("2026-03-01T10:00:00Z"), rdate = "20260301T100000Z,20260315T100000Z", lastDate = t("2026-03-15T11:00:00Z"))
        assertEquals(SeriesEnd.At(t("2026-03-16T11:00:00Z")), RangeTrimPlanner.seriesEnd(rdates))
        assertEquals(1, plan(listOf(rdates)).remove.size)
        val later = rdates.copy(rdate = "20260301T100000Z,20261120T100000Z", lastDate = t("2026-11-20T11:00:00Z"))
        assertEquals(mapOf(Keep.REACHES_RANGE to 1), plan(listOf(later)).kept)
        assertEquals(SeriesEnd.Unknown, RangeTrimPlanner.seriesEnd(rdates.copy(rdate = "20260301T100000Z/PT1H")))
        // AOSP drops DTEND from a recurring row without DURATION: no length, so no end to trust.
        val noLength = row(51, t("2026-03-02T10:00:00Z"), end = null, rrule = "FREQ=WEEKLY;UNTIL=20260601T100000Z", lastDate = t("2026-06-01T11:00:00Z"))
        assertEquals(mapOf(Keep.UNKNOWN_TIME to 1), plan(listOf(noLength)).kept)
    }

    @Test
    fun `an edit written before its series was known joins it by UID and is dated by its cached RECURRENCE-ID`() {
        val master = row(60, t("2026-03-02T10:00:00Z"), end = null, uid = "s@x", rrule = "FREQ=WEEKLY;UNTIL=20260601T100000Z",
            duration = "PT1H", lastDate = t("2026-06-01T11:00:00Z"))
        val unlinked = row(61, t("2026-03-10T10:00:00Z"), uid = "s@x")
        val p = plan(listOf(master, unlinked), mappings = listOf(map(60), map(61, rid = "RECURRENCE-ID;TZID=Europe/Berlin:20260309T110000")))
        assertEquals(listOf(listOf(60L, 61L)), p.remove.map { it.rowIds })
        // An older bare local value names no exact instant: the group stays.
        val legacy = plan(listOf(master, unlinked), mappings = listOf(map(60), map(61, rid = "20260309T110000")))
        assertEquals(mapOf(Keep.UNKNOWN_TIME to 1), legacy.kept)
    }

    @Test
    fun `a this-and-future split is two series, and an imported RANGE row keeps its series while either part runs`() {
        val oldPart = row(70, t("2026-03-02T10:00:00Z"), end = null, uid = "old@x", rrule = "FREQ=WEEKLY;UNTIL=20260601T100000Z",
            duration = "PT1H", lastDate = t("2026-06-01T11:00:00Z"))
        val newPart = row(71, t("2026-06-08T10:00:00Z"), end = null, uid = "new@x", rrule = "FREQ=WEEKLY", duration = "PT1H")
        val p = plan(listOf(oldPart, newPart))
        assertEquals(listOf(listOf(70L)), p.remove.map { it.rowIds })
        assertEquals(mapOf(Keep.NO_END to 1), p.kept)
        val rangeRow = row(72, t("2026-05-04T10:00:00Z"), end = null, uid = "old@x", rrule = "FREQ=WEEKLY", duration = "PT1H")
        assertEquals(emptyList<RangeTrimPlanner.Group>(), plan(listOf(oldPart, rangeRow)).remove)
    }

    private val past = row(80, t("2026-03-01T10:00:00Z"))

    @Test
    fun `anything waiting, changed, held or recent keeps the group`() {
        assertEquals(mapOf(Keep.UNSENT to 1), plan(listOf(past.copy(dirty = true))).kept)
        assertEquals(mapOf(Keep.UNSENT to 1), plan(listOf(past.copy(deleted = true))).kept)
        assertEquals(mapOf(Keep.CHANGED to 1), plan(listOf(past), mappings = listOf(map(80, print = "fp-other"))).kept)
        assertEquals(mapOf(Keep.CHANGED to 1), plan(listOf(past), mappings = listOf(map(80, print = null))).kept)
        assertEquals(mapOf(Keep.HELD to 1), plan(listOf(past), held = setOf("p80")).kept)
        assertEquals(mapOf(Keep.HELD to 1), plan(listOf(past), invites = setOf("p80")).kept)
        assertEquals(mapOf(Keep.HELD to 1), plan(listOf(past), cancels = setOf("u80")).kept)
        assertEquals(mapOf(Keep.RECENT to 1), plan(listOf(past), mappings = listOf(map(80, modified = now / 1000 - 3 * 24 * 3600))).kept)
        // A value written in milliseconds reads as far in the future: recent.
        assertEquals(mapOf(Keep.RECENT to 1), plan(listOf(past), mappings = listOf(map(80, modified = now))).kept)
        assertEquals(mapOf(Keep.IN_WINDOW to 1), plan(listOf(past), window = setOf("p80")).kept)
        // An occurrence cancelled on the phone and not yet uploaded keeps its series.
        val series = finishedSeries() + row(90, t("2026-04-20T10:00:00Z"), end = null, uid = null, syncId = null, origSync = "p10",
            origTime = t("2026-04-20T10:00:00Z"), duration = "PT1H", lastDate = null, cancelled = true, dirty = true)
        assertEquals(mapOf(Keep.UNSENT to 1), plan(series).kept)
    }

    @Test
    fun `only what Proton confirmed in this run goes`() {
        assertEquals(mapOf(Keep.NOT_FROM_PROTON to 1), plan(listOf(past), allTime = emptySet()).kept)
        // The row carries another id than its mapping.
        assertEquals(mapOf(Keep.NOT_FROM_PROTON to 1), plan(listOf(past.copy(syncId = "p-other")), mappings = listOf(map(80))).kept)
        // A row two mappings name.
        assertEquals(mapOf(Keep.NOT_FROM_PROTON to 1), plan(listOf(past), mappings = listOf(map(80), map(80, protonId = "p81"))).kept)
        // A phone-only event with the same UID.
        val phoneOnly = row(81, t("2026-03-01T12:00:00Z"), uid = "u80", syncId = null)
        assertEquals(mapOf(Keep.NOT_FROM_PROTON to 1), plan(listOf(past, phoneOnly), mappings = listOf(map(80))).kept)
        // An unmapped row with a Proton id: kept (a removal deletes rows before their mappings, so none is left unmapped).
        val stranded = row(82, t("2026-03-02T10:00:00Z"))
        assertEquals(mapOf(Keep.NOT_FROM_PROTON to 1), plan(listOf(stranded), mappings = emptyList()).kept)
    }

    @Test
    fun `an event that exists only on this phone never goes, long ended or not, dirty or clean`() {
        // A new event waiting for the user's choice in a shared calendar, one kept on this phone and one never sent:
        // no Proton id, no mapping. A waiting or kept one stays dirty for good; a clean one (its upload given up) too.
        val ended = t("2026-02-01T10:00:00Z")
        val phoneOnly = listOf(
            row(120, ended, uid = null, syncId = null, dirty = true),
            row(121, ended, uid = "kept-121@example.test", syncId = null, dirty = true),
            row(122, ended, uid = null, syncId = null),
            row(123, ended, uid = null, syncId = null, rrule = "FREQ=DAILY;COUNT=2", duration = "PT1H", end = null, lastDate = ended + 2 * day),
            // Its exception, cancelled on the phone: no ORIGINAL_SYNC_ID, as the series never reached Proton.
            row(124, ended + day, uid = null, syncId = null, origTime = ended + day, cancelled = true),
        )
        val p = plan(phoneOnly, mappings = emptyList(), allTime = setOf("p80"))
        assertTrue(p.remove.isEmpty())
        assertEquals(phoneOnly.size, p.kept.values.sum())
        // Next to a Proton event of the same calendar that does go, they still stay.
        val mixed = plan(listOf(past) + phoneOnly, mappings = listOf(map(80)))
        assertEquals(listOf(listOf(80L)), mixed.remove.map { it.rowIds })
    }

    @Test
    fun `a group over the row limit stays`() {
        val master = row(100, t("2025-01-06T10:00:00Z"), end = null, uid = "big@x", rrule = "FREQ=DAILY;UNTIL=20260101T100000Z",
            duration = "PT1H", lastDate = t("2026-01-01T11:00:00Z"))
        val placeholders = (1..RangeTrimPlanner.MAX_GROUP_ROWS).map { i ->
            row(100L + i, t("2025-01-06T10:00:00Z") + i * day, end = null, uid = null, syncId = null, origSync = "p100",
                origTime = t("2025-01-06T10:00:00Z") + i * day, duration = "PT1H", lastDate = null, cancelled = true)
        }
        assertEquals(mapOf(Keep.TOO_BIG to 1), plan(listOf(master) + placeholders).kept)
        assertEquals(1, plan(listOf(master) + placeholders.dropLast(1)).remove.size)
    }

    @Test
    fun `placeholders whose series is gone go once their date is before the cutoff`() {
        val orphan = row(110, t("2026-03-30T10:00:00Z"), end = null, uid = null, syncId = null, origSync = "gone",
            origTime = t("2026-03-30T10:00:00Z"), duration = "PT1H", lastDate = null, cancelled = true)
        assertEquals(listOf(listOf(110L)), plan(listOf(orphan), mappings = emptyList()).remove.map { it.rowIds })
        val recent = orphan.copy(id = 111, originalInstanceTime = cutoff)
        assertEquals(mapOf(Keep.REACHES_RANGE to 1), plan(listOf(recent), mappings = emptyList()).kept)
    }

    @Test
    fun `every group is either removed or counted once as kept`() {
        val rows = finishedSeries() + listOf(past, row(120, t("2026-11-20T10:00:00Z")), row(121, t("2026-03-01T10:00:00Z"), dirty = true))
        val p = plan(rows)
        val groups = RangeTrimPlanner.groupRows(rows, rows.filter { it.syncId != null }.map { map(it.id, it.syncId!!) })
        assertEquals(groups.size, p.remove.size + p.kept.values.sum())
        assertEquals(2, p.remove.size)
    }

    @Test
    fun `a COUNT rule is expanded on the event's own days, east of UTC too`() {
        // Monday 5 January 00:30 in Berlin is Sunday 23:30 UTC. On local days the fourth Sunday or Monday is Sunday
        // 18 January (23:30 UTC the day before); on UTC days it came out on 12 January, five days early.
        val sunMon = row(150, t("2026-01-04T23:30:00Z"), end = null, rrule = "FREQ=WEEKLY;BYDAY=SU,MO;COUNT=4",
            duration = "PT1H", lastDate = t("2026-01-18T00:30:00Z"))
        assertEquals(SeriesEnd.At(t("2026-01-17T23:30:00Z") + hour + day), RangeTrimPlanner.seriesEnd(sunMon))
        // Weekdays at 09:00 in Sydney from Tuesday 17 November: the tenth is Monday 30 November (22:00 UTC the day before).
        val sydney = row(151, t("2026-11-16T22:00:00Z"), end = null, rrule = "FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR;COUNT=10",
            duration = "P3600S", lastDate = t("2026-11-29T23:00:00Z")).copy(eventTimezone = "Australia/Sydney")
        assertEquals(SeriesEnd.At(t("2026-11-29T22:00:00Z") + hour + day), RangeTrimPlanner.seriesEnd(sydney))
        // Mondays at 11:00 in Berlin from summer into winter: DTSTART's offset puts the last an hour early, inside the
        // day of margin, so the series still ends after its real last occurrence (16 November, 10:00 to 11:00 UTC).
        val intoWinter = row(152, t("2026-07-06T09:00:00Z"), end = null, rrule = "FREQ=WEEKLY;BYDAY=MO;COUNT=20",
            duration = "PT1H", lastDate = t("2026-11-16T11:00:00Z"))
        assertTrue((RangeTrimPlanner.seriesEnd(intoWinter) as SeriesEnd.At).millis >= t("2026-11-16T11:00:00Z"))
        // No zone, or one this cannot read: the days are not known, so neither is the end.
        assertEquals(SeriesEnd.Unknown, RangeTrimPlanner.seriesEnd(sunMon.copy(eventTimezone = null)))
        assertEquals(SeriesEnd.Unknown, RangeTrimPlanner.seriesEnd(sunMon.copy(eventTimezone = "Not/AZone")))
    }

    @Test
    fun `a mapped row without its _SYNC_ID stays, a series and a one-off alike`() {
        // AOSP deletes every row whose ORIGINAL_ID names such a series row along with it, dirty ones included.
        val series = row(160, t("2026-03-02T10:00:00Z"), end = null, syncId = null, rrule = "FREQ=WEEKLY;UNTIL=20260601T100000Z",
            duration = "PT1H", lastDate = t("2026-06-01T11:00:00Z"))
        assertEquals(mapOf(Keep.NOT_FROM_PROTON to 1), plan(listOf(series), mappings = listOf(map(160))).kept)
        assertEquals(1, plan(listOf(series.copy(syncId = "p160")), mappings = listOf(map(160))).remove.size)
        // A calendar app deletes such a row outright, no tombstone: the journal would read the user's delete as the
        // removal's own, and it would never reach Proton.
        val oneOff = row(161, t("2026-03-02T10:00:00Z"), syncId = null)
        assertEquals(mapOf(Keep.NOT_FROM_PROTON to 1), plan(listOf(oneOff), mappings = listOf(map(161))).kept)
        assertEquals(1, plan(listOf(oneOff.copy(syncId = "p161")), mappings = listOf(map(161))).remove.size)
    }

    @Test
    fun `an all-day COUNT series is expanded on UTC's dates, whatever zone another app wrote on it`() {
        // Sunday 1 November (all-day, UTC midnight), Saturdays and Sundays, four times: 1, 7, 8 and 14 November, as the
        // provider expands an all-day row (in UTC). Expanded in Los Angeles' zone instead, DTSTART is Saturday
        // 31 October 17:00 and the fourth is Sunday 8 November: six days early, before the cutoff with the provider's
        // LAST_DATE (computed in EVENT_TIMEZONE, early the same way), though 14 November is in the range.
        val weekends = row(170, t("2026-11-01T00:00:00Z"), end = null, rrule = "FREQ=WEEKLY;BYDAY=SA,SU;COUNT=4",
            duration = "P1D", lastDate = t("2026-11-10T01:00:00Z")).copy(eventTimezone = "America/Los_Angeles", allDay = true)
        assertEquals(SeriesEnd.At(t("2026-11-14T00:00:00Z") + day + day), RangeTrimPlanner.seriesEnd(weekends))
        assertEquals(mapOf(Keep.REACHES_RANGE to 1), plan(listOf(weekends)).kept)
        // A timed row with the same columns is expanded in its zone, and its fourth occurrence did end on 9 November.
        assertEquals(1, plan(listOf(weekends.copy(allDay = false))).remove.size)
        // The first and last day of every month, 24 times from 1 January, in Chicago: the 24th is 31 December, not
        // 1 December.
        val monthEnds = row(171, t("2026-01-01T00:00:00Z"), end = null, rrule = "FREQ=MONTHLY;BYMONTHDAY=1,-1;COUNT=24",
            duration = "P1D", lastDate = t("2026-12-03T00:00:00Z")).copy(eventTimezone = "America/Chicago", allDay = true)
        assertEquals(SeriesEnd.At(t("2026-12-31T00:00:00Z") + day + day), RangeTrimPlanner.seriesEnd(monthEnds))
    }

    @Test
    fun `groups join by Proton id links, by a mapping's id and by UID`() {
        // The master's row lost its _SYNC_ID, but its mapping still names it: the edit still joins it.
        val master = row(130, t("2026-03-02T10:00:00Z"), uid = null, syncId = null)
        val edit = row(131, t("2026-03-10T10:00:00Z"), uid = null, origSync = "p130")
        val other = row(132, t("2026-03-11T10:00:00Z"), uid = "x@y")
        val sameUid = row(133, t("2026-03-12T10:00:00Z"), uid = "x@y")
        val groups = RangeTrimPlanner.groupRows(listOf(master, edit, other, sameUid), listOf(map(130), map(131), map(132), map(133)))
        assertEquals(listOf(listOf(130L, 131L), listOf(132L, 133L)), groups)
    }

    @Test
    fun `durations are read strictly`() {
        assertEquals(3_600_000L, RangeTrimPlanner.durationMillis("P3600S"))
        assertEquals(5_400_000L, RangeTrimPlanner.durationMillis("PT1H30M"))
        assertEquals(day, RangeTrimPlanner.durationMillis("P1D"))
        assertEquals(14 * day, RangeTrimPlanner.durationMillis("P2W"))
        assertEquals(day + 2 * hour, RangeTrimPlanner.durationMillis("P1DT2H"))
        for (bad in listOf("", "P", "-PT1H", "1H", "PT", "P1Y")) assertNull(bad, RangeTrimPlanner.durationMillis(bad))
    }

    @Test
    fun `a cached RECURRENCE-ID is read when it names an exact instant`() {
        assertEquals(t("2026-03-09T10:00:00Z"), RangeTrimPlanner.recurrenceIdMillis("RECURRENCE-ID;TZID=Europe/Berlin:20260309T110000"))
        assertEquals(t("2026-03-09T00:00:00Z"), RangeTrimPlanner.recurrenceIdMillis("20260309"))
        assertEquals(t("2026-03-09T10:00:00Z"), RangeTrimPlanner.recurrenceIdMillis("20260309T100000Z"))
        assertNull(RangeTrimPlanner.recurrenceIdMillis("20260309T100000"))
        assertNull(RangeTrimPlanner.recurrenceIdMillis(""))
    }

    @Test
    fun `RDATE columns are read with their zone prefix, and anything else is unknown`() {
        assertEquals(t("2026-03-15T09:00:00Z"), RangeTrimPlanner.latestRdate("Europe/Berlin;20260301T100000,20260315T100000", null))
        assertEquals(t("2026-03-15T09:00:00Z"), RangeTrimPlanner.latestRdate("20260301T100000,20260315T100000", "Europe/Berlin"))
        assertEquals(t("2026-03-15T00:00:00Z"), RangeTrimPlanner.latestRdate("20260301,20260315", null))
        assertNull(RangeTrimPlanner.latestRdate("Nowhere/Land;20260301T100000", null))
        assertNull(RangeTrimPlanner.latestRdate("20260301T100000Z/20260301T110000Z", null))
    }
}

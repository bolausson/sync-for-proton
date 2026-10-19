package me.proton.sync.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/** The raw-text readers under DownloadTimes: content lines, DATE/DATE-TIME values, durations, zones, ofLocal. */
class IcalTimeTextTest {

    private fun local(v: String) = LocalDateTime.parse(v, IcalTimeValues.LOCAL_FORMAT)

    @Test
    fun `a line splits into name, parameters and value`() {
        val p = IcalPropLine.parse("DTSTART;TZID=\"America/New_York\";VALUE=DATE-TIME:20261115T100000")!!
        assertEquals("DTSTART", p.name)
        assertEquals("America/New_York", p.param("TZID"))
        assertEquals("DATE-TIME", p.param("VALUE"))
        assertEquals("20261115T100000", p.value)
        assertEquals("DTSTART;TZID=\"America/New_York\";VALUE=DATE-TIME:20261115T100000", p.text)
    }

    @Test
    fun `a colon or semicolon inside a quoted parameter splits nothing`() {
        val p = IcalPropLine.parse("X-THING;X-P=\"a;b:c\";Y=z:value:with:colons")!!
        assertEquals("a;b:c", p.param("X-P"))
        assertEquals("z", p.param("Y"))
        assertEquals("value:with:colons", p.value)
    }

    @Test
    fun `names and parameter names are case-insensitive, the first of two parameters wins`() {
        val p = IcalPropLine.parse("dtstart;tzid=Europe/Berlin;TZID=Asia/Tokyo:20261115T100000")!!
        assertEquals("DTSTART", p.name)
        assertEquals("Europe/Berlin", p.param("TZID"))
    }

    @Test
    fun `no colon or no name is no property`() {
        assertNull(IcalPropLine.parse("garbage"))
        assertNull(IcalPropLine.parse(":value"))
        assertNull(IcalPropLine.parse(""))
        assertNull(IcalPropLine.parse(null))
    }

    @Test
    fun `the four value kinds`() {
        fun one(line: String) = IcalTimeValues.single(IcalPropLine.parse(line)!!)
        assertEquals(IcalTimeValue.Day(LocalDate.of(2026, 3, 15)), one("DTSTART;VALUE=DATE:20260315"))
        assertEquals(IcalTimeValue.Utc(Instant.parse("2026-11-15T10:00:00Z")), one("DTSTART:20261115T100000Z"))
        assertEquals(IcalTimeValue.Zoned(local("20261115T100000"), "Europe/Berlin"), one("DTSTART;TZID=Europe/Berlin:20261115T100000"))
        assertEquals(IcalTimeValue.Floating(local("20261115T100000")), one("DTSTART:20261115T100000"))
        // A date ignores a TZID, as ical4j's Date does.
        assertEquals(IcalTimeValue.Day(LocalDate.of(2026, 3, 15)), one("DTSTART;VALUE=DATE;TZID=Europe/Berlin:20260315"))
    }

    @Test
    fun `Z wins over a TZID, as in ical4j`() {
        assertEquals(
            IcalTimeValue.Utc(Instant.parse("2026-11-15T10:00:00Z")),
            IcalTimeValues.single(IcalPropLine.parse("DTSTART;TZID=Europe/Berlin:20261115T100000Z")!!)
        )
    }

    @Test
    fun `shapes outside DATE and DATE-TIME are left to ical4j`() {
        for (line in listOf(
            "DTSTART:20261115",                       // 8 digits without VALUE=DATE: ical4j makes it 00:00Z timed
            "DTSTART;TZID=Europe/Berlin:20261115",
            "DTSTART:20261115T1000",
            "DTSTART:2026-11-15T10:00:00",
            "DTSTART:20261131T100000",                // no such day
            "DTSTART;VALUE=DATE:20261115T100000",
            "RDATE;VALUE=PERIOD:20261201T100000/PT1H",
            "DTSTART:"
        )) {
            assertNull(line, IcalTimeValues.parse(IcalPropLine.parse(line) ?: continue))
        }
        // A multi-valued start is no single value.
        assertNull(IcalTimeValues.single(IcalPropLine.parse("DTSTART:20261115T100000Z,20261116T100000Z")!!))
    }

    @Test
    fun `a multi-valued EXDATE keeps its order and skips empty items`() {
        val values = IcalTimeValues.parse(IcalPropLine.parse("EXDATE;TZID=Europe/Berlin:20261122T100000,,20261108T100000,")!!)!!
        assertEquals(
            listOf(IcalTimeValue.Zoned(local("20261122T100000"), "Europe/Berlin"), IcalTimeValue.Zoned(local("20261108T100000"), "Europe/Berlin")),
            values
        )
    }

    @Test
    fun `durations count days and weeks as exact seconds`() {
        assertEquals(5400L, IcalDurations.seconds("PT1H30M"))
        assertEquals(86_400L, IcalDurations.seconds("P1D"))
        assertEquals(604_800L, IcalDurations.seconds("P1W"))
        assertEquals(93_600L, IcalDurations.seconds("P1DT2H"))
        assertEquals(-900L, IcalDurations.seconds("-PT15M"))
        assertEquals(3600L, IcalDurations.seconds("P3600S"))     // Android's own column form
        assertEquals(0L, IcalDurations.seconds("P0S"))
        assertNull(IcalDurations.seconds("P"))
        assertNull(IcalDurations.seconds("PT"))
        assertNull(IcalDurations.seconds("one hour"))
        assertNull(IcalDurations.seconds(null))
    }

    @Test
    fun `ofLocal semantics - a gap moves forward, an overlap takes the earlier offset`() {
        val berlin = ZoneId.of("Europe/Berlin").rules
        assertEquals(Instant.parse("2026-03-29T01:30:00Z"), LocalTimes.toInstant(local("20260329T023000"), berlin))
        assertEquals(Instant.parse("2026-10-25T00:30:00Z"), LocalTimes.toInstant(local("20261025T023000"), berlin))
        assertEquals(Instant.parse("2026-07-01T08:00:00Z"), LocalTimes.toInstant(local("20260701T100000"), berlin))
        // The same as java.time's own ZonedDateTime.of, which is RFC 5545 3.3.5's rule too.
        for (v in listOf("20260329T023000", "20261025T023000", "20261025T013000", "20260329T015959")) {
            assertEquals(v, local(v).atZone(ZoneId.of("Europe/Berlin")).toInstant(), LocalTimes.toInstant(local(v), berlin))
        }
    }

    /**
     * Android's java.util.GregorianCalendar (libcore ojluni, adjustForZoneAndDaylightSavingsTime), which
     * CalendarProvider's calendarcommon2.Time expands a series with, on java.time data: the offsets are looked up
     * at wall time minus the zone's raw offset, and a DST offset that lands outside DST is dropped.
     */
    private fun androidGregorian(wall: LocalDateTime, rules: java.time.zone.ZoneRules, raw: ZoneOffset): Instant {
        val wallAsUtc = wall.toInstant(ZoneOffset.UTC)
        val std = wallAsUtc.minusSeconds(raw.totalSeconds.toLong())
        val rawAtStd = rules.getStandardOffset(std).totalSeconds
        var dst = rules.getOffset(std).totalSeconds - rawAtStd
        if (dst != 0 && !rules.isDaylightSavings(std.minusSeconds(dst.toLong()))) dst = 0
        return wallAsUtc.minusSeconds((rawAtStd + dst).toLong())
    }

    @Test
    fun `providerExpands - an overlap takes the later offset, a gap is unchanged`() {
        val berlin = ZoneId.of("Europe/Berlin").rules
        assertEquals(Instant.parse("2026-10-25T01:30:00Z"), LocalTimes.toInstant(local("20261025T023000"), berlin, providerExpands = true))
        assertEquals(Instant.parse("2026-03-29T01:30:00Z"), LocalTimes.toInstant(local("20260329T023000"), berlin, providerExpands = true))
        assertEquals(Instant.parse("2026-07-01T08:00:00Z"), LocalTimes.toInstant(local("20260701T100000"), berlin, providerExpands = true))
    }

    @Test
    fun `providerExpands equals Android's GregorianCalendar in every gap and overlap of every zone from 2026 on`() {
        var probes = 0
        for (id in ZoneId.getAvailableZoneIds()) {
            val rules = ZoneId.of(id).rules
            val raw = rules.getStandardOffset(Instant.parse("2037-06-01T00:00:00Z"))
            var t = Instant.parse("2026-01-01T00:00:00Z")
            while (true) {
                val tr = rules.nextTransition(t) ?: break
                if (tr.instant.isAfter(Instant.parse("2040-01-01T00:00:00Z"))) break
                t = tr.instant
                val (from, until) = if (tr.isGap) tr.dateTimeBefore to tr.dateTimeAfter else tr.dateTimeAfter to tr.dateTimeBefore
                var w = from
                while (w.isBefore(until)) {
                    assertEquals("$id $w", androidGregorian(w, rules, raw), LocalTimes.toInstant(w, rules, providerExpands = true))
                    probes++
                    w = w.plusMinutes(15)
                }
            }
        }
        assertTrue("$probes", probes > 10_000)
    }

    @Test
    fun `formatLocal writes the wall-clock time under the given rules`() {
        val newYork = ZoneId.of("America/New_York").rules
        assertEquals("20261115T100000", LocalTimes.formatLocal(Instant.parse("2026-11-15T15:00:00Z"), newYork))
    }

    @Test
    fun `zones resolve by their own name, a prefixed name, or a renamed zone's old name`() {
        assertEquals("Europe/Berlin", EventZones.resolve("Europe/Berlin")!!.columnId)
        assertEquals("Europe/Berlin", EventZones.resolve("\"Europe/Berlin\"")!!.columnId)
        // The TZID's own spelling stays (the upload normalises it); the rules are the link target's.
        val pacific = EventZones.resolve("US/Pacific")!!
        assertEquals("US/Pacific", pacific.columnId)
        assertEquals(ZoneId.of("America/Los_Angeles").rules.getOffset(Instant.parse("2026-07-01T00:00:00Z")),
            pacific.zone.rules.getOffset(Instant.parse("2026-07-01T00:00:00Z")))
        assertEquals("Asia/Calcutta", EventZones.resolve("Asia/Calcutta")!!.columnId)
        assertEquals("Etc/GMT+5", EventZones.resolve("Etc/GMT+5")!!.columnId)
        assertEquals("Europe/Berlin", EventZones.resolve("/mozilla.org/20050126_1/Europe/Berlin")!!.columnId)
        assertEquals("America/Argentina/Buenos_Aires",
            EventZones.resolve("/softwarestudio.org/Olson_20011030_5/America/Argentina/Buenos_Aires")!!.columnId)
        // A phone with older data knows Kyiv only as Kiev: same rules, the old name.
        assertEquals("Europe/Kiev", EventZones.resolve("Europe/Kyiv", known = { it != "Europe/Kyiv" && EventZones.isKnown(it) })!!.columnId)
        // Nuuk keeps its own rules. (Proton's UNSUPPORTED_LINKS maps it to Scoresbysund for its API only.)
        assertEquals("America/Nuuk", EventZones.resolve("America/Nuuk")!!.columnId)
    }

    @Test
    fun `UTC under any name is UTC`() {
        for (name in listOf("UTC", "Etc/UTC", "GMT", "Etc/GMT", "Universal", "Zulu", "etc/utc")) {
            val z = EventZones.resolve(name)!!
            assertEquals(name, "UTC", z.columnId)
            assertEquals(ZoneOffset.UTC, z.zone)
        }
    }

    @Test
    fun `unknown zones and offset ids are left to ical4j`() {
        assertNull(EventZones.resolve("Mars/Olympus"))
        assertNull(EventZones.resolve("GMT+02:00"))
        assertNull(EventZones.resolve("W. Europe Standard Time"))
        assertNull(EventZones.resolve(""))
        assertTrue(EventZones.isKnown("Europe/Berlin"))
    }
}

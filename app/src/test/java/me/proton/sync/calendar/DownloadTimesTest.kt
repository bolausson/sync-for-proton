package me.proton.sync.calendar

import me.proton.sync.crypto.CalendarDecryptor
import net.fortuna.ical4j.data.CalendarBuilder
import net.fortuna.ical4j.model.component.VEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.StringReader
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Every download-side time column, computed with java.time from the raw text (DownloadTimes), never from
 * ical4j's VTIMEZONE arithmetic. Expectations for zones are computed in the test from the JVM's own tzdb,
 * which plays the phone's data. The ON test with Vancouver below also pins the stale phone's -08 for
 * December 2026, so it is skipped on a JVM whose tzdb has 2026d or later (StalePhoneJvm; JetBrains JDK
 * 21.0.10 has 2025b).
 */
class DownloadTimesTest {

    @Before
    fun relaxLikeTheApp() {
        System.setProperty("ical4j.parsing.relaxed", "true")
        System.setProperty("ical4j.unfolding.relaxed", "true")
        System.setProperty("ical4j.compatibility.outlook", "true")
    }

    private fun ics(vararg lines: String) =
        (listOf("BEGIN:VCALENDAR", "VERSION:2.0", "BEGIN:VEVENT", "UID:t") + lines + listOf("END:VEVENT", "END:VCALENDAR"))
            .joinToString("\r\n")

    private fun compute(
        vararg lines: String,
        device: String = "Europe/Berlin",
        rules: ZoneRulesLookup = ZoneRulesLookup.PHONE,
        periodRdate: String? = null
    ): EventTimeColumns = DownloadTimes.compute(RawEventTimes.scan(ics(*lines))!!, rules, ZoneId.of(device), periodRdate)

    private fun at(local: String, zone: String): Long =
        LocalDateTime.parse(local, IcalTimeValues.LOCAL_FORMAT).atZone(ZoneId.of(zone)).toInstant().toEpochMilli()

    private fun utc(iso: String) = Instant.parse(iso).toEpochMilli()

    private fun unevaluable(vararg lines: String) {
        try {
            compute(*lines)
            fail("expected Unevaluable for ${lines.toList()}")
        } catch (_: DownloadTimes.Unevaluable) {
        }
    }

    // ---- (a) the raw text, as the sync has it ----

    private fun merged(vararg parts: String) = CalendarDecryptor.mergeParts(parts.toList(), "uid-1")

    private fun part(vararg lines: String) =
        (listOf("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Proton AG//WebCalendar 5.0.40//EN", "BEGIN:VEVENT", "UID:uid-1") +
            lines + listOf("END:VEVENT", "END:VCALENDAR")).joinToString("\r\n")

    private fun parsedEvent(ics: String): VEvent =
        CalendarBuilder().build(StringReader(ics)).getComponents<VEvent>("VEVENT").first()

    @Test
    fun `the text the decryptor merges is read whole - folded EXDATE, LF line ends, first DTSTART`() {
        val exdates = (1..12).joinToString(",") { "202611%02dT100000".format(it + 10) }
        val text = merged(
            part("DTSTART;TZID=Europe/Berlin:20261101T100000", "DTEND;TZID=Europe/Berlin:20261101T110000",
                "RRULE:FREQ=DAILY;COUNT=30", "EXDATE;TZID=Europe/Berlin:$exdates", "SEQUENCE:0"),
            part("DTSTART;TZID=America/New_York:20261101T100000", "EXDATE;TZID=Europe/Berlin:$exdates", "SUMMARY:x")
        )
        assertTrue("mergeParts folds the long EXDATE line", text.contains("\r\n "))
        assertFalse("and ends lines with LF", text.contains("\r\nEND:VEVENT"))
        val raw = RawEventTimes.scan(text)!!
        assertEquals("the first DTSTART, as ical4j's getProperty", "Europe/Berlin", raw.dtStart!!.param("TZID"))
        assertEquals(2, raw.exdates.size)
        assertTrue(DownloadTimes.agreesWithIcal4j(raw, parsedEvent(text)))
        val t = DownloadTimes.compute(raw, ZoneRulesLookup.PHONE, ZoneId.of("UTC"))
        assertEquals(exdates, t.exdateColumn)          // de-duplicated across the parts, in order
        assertEquals(12, t.exdateInstants.size)
        assertEquals(at("20261111T100000", "Europe/Berlin"), t.exdateInstants.first())
    }

    @Test
    fun `CRLF text, a quoted TZID and nested blocks - only the event's own properties count`() {
        val text = listOf(
            "BEGIN:VCALENDAR", "VERSION:2.0",
            "BEGIN:VTIMEZONE", "TZID:Fake/Zone", "BEGIN:STANDARD", "DTSTART:19700101T000000",
            "TZOFFSETFROM:+0000", "TZOFFSETTO:+0000", "END:STANDARD", "END:VTIMEZONE",
            "BEGIN:VEVENT", "UID:x",
            "BEGIN:VALARM", "ACTION:DISPLAY", "TRIGGER:-PT15M", "DTSTART:19990101T000000Z", "END:VALARM",
            "DTSTART;TZID=\"America/New_York\":20261115T100000",
            "END:VEVENT", "END:VCALENDAR"
        ).joinToString("\r\n")
        val raw = RawEventTimes.scan(text)!!
        assertEquals("20261115T100000", raw.dtStart!!.value)
        val t = DownloadTimes.compute(raw, ZoneRulesLookup.PHONE, ZoneId.of("UTC"))
        assertEquals(utc("2026-11-15T15:00:00Z"), t.dtStart)
        assertEquals("America/New_York", t.eventTimezone)
    }

    @Test
    fun `the external-calendar fallback text is read like any other`() {
        val text = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\nUID:u\r\nDTSTAMP:20261004T000000Z\r\n" +
            "DTSTART:20261115T100000Z\r\nDTEND:20261115T110000Z\r\nSUMMARY:(External event)\r\nEND:VEVENT\r\nEND:VCALENDAR"
        val t = DownloadTimes.compute(RawEventTimes.scan(text)!!, ZoneRulesLookup.PHONE, ZoneId.of("UTC"))
        assertEquals(utc("2026-11-15T10:00:00Z"), t.dtStart)
        assertEquals(utc("2026-11-15T11:00:00Z"), t.dtEnd)
        assertNull(RawEventTimes.scan("BEGIN:VCALENDAR\r\nEND:VCALENDAR"))
    }

    @Test
    fun `agreesWithIcal4j sees what ical4j parsed, property by property`() {
        val text = ics("DTSTART;TZID=Europe/Berlin:20261115T100000", "DURATION:PT1H", "RRULE:FREQ=WEEKLY",
            "EXDATE;TZID=Europe/Berlin:20261122T100000", "EXDATE:20261129T090000Z", "RDATE:20261201T090000Z")
        val raw = RawEventTimes.scan(text)!!
        assertTrue(DownloadTimes.agreesWithIcal4j(raw, parsedEvent(text)))
        // A scan that missed a property (as a mis-unfolded line would) is caught.
        val partial = RawEventTimes(raw.dtStart, null, null, null, null, raw.exdates.take(1), true)
        assertFalse(DownloadTimes.agreesWithIcal4j(partial, parsedEvent(text)))
    }

    // ---- (b) the semantics kept from before ----

    @Test
    fun `all-day is the date's UTC midnight whatever the phone's zone`() {
        for (device in listOf("UTC", "Europe/Berlin", "America/New_York", "Pacific/Midway", "Pacific/Kiritimati")) {
            val t = compute("DTSTART;VALUE=DATE:20260315", device = device)
            assertEquals(device, utc("2026-03-15T00:00:00Z"), t.dtStart)
            assertEquals("UTC", t.eventTimezone)
            assertTrue(t.allDay)
            assertEquals(device, utc("2026-03-16T00:00:00Z"), t.dtEnd)   // no DTEND: one day
            assertEquals("UTC", t.endTimezone)
        }
    }

    @Test
    fun `UTC, TZID plus Z, TZID=UTC and floating`() {
        assertEquals(utc("2026-11-15T10:00:00Z"), compute("DTSTART:20261115T100000Z").dtStart)
        assertEquals("UTC", compute("DTSTART:20261115T100000Z").eventTimezone)
        assertEquals(utc("2026-11-15T10:00:00Z"), compute("DTSTART;TZID=Europe/Berlin:20261115T100000Z").dtStart)
        assertEquals("UTC", compute("DTSTART;TZID=Europe/Berlin:20261115T100000Z").eventTimezone)
        val tzUtc = compute("DTSTART;TZID=UTC:20261115T100000")
        assertEquals(utc("2026-11-15T10:00:00Z"), tzUtc.dtStart)
        assertEquals("UTC", tzUtc.eventTimezone)
        // Floating: the phone's zone at sync time, stored with zone UTC.
        val floating = compute("DTSTART:20261115T100000", device = "America/New_York")
        assertEquals(utc("2026-11-15T15:00:00Z"), floating.dtStart)
        assertEquals("UTC", floating.eventTimezone)
    }

    @Test
    fun `zone names - mozilla prefix, quotes, links, fixed offsets`() {
        val mozilla = compute("DTSTART;TZID=/mozilla.org/20050126_1/Europe/Berlin:20261115T100000")
        assertEquals(utc("2026-11-15T09:00:00Z"), mozilla.dtStart)
        assertEquals("Europe/Berlin", mozilla.eventTimezone)
        assertEquals(at("20260715T100000", "America/Los_Angeles"), compute("DTSTART;TZID=US/Pacific:20260715T100000").dtStart)
        assertEquals(at("20260715T100000", "Asia/Kolkata"), compute("DTSTART;TZID=Asia/Calcutta:20260715T100000").dtStart)
        // Etc/GMT+5 is five hours BEHIND UTC; never collapsed into UTC (ICalUtils.normalizeTimezone would).
        val gmt5 = compute("DTSTART;TZID=Etc/GMT+5:20261115T100000")
        assertEquals(utc("2026-11-15T15:00:00Z"), gmt5.dtStart)
        assertEquals("Etc/GMT+5", gmt5.eventTimezone)
    }

    @Test
    fun `an unknown zone or an unusual value leaves the event to ical4j`() {
        unevaluable("DTSTART;TZID=Mars/Olympus:20261115T100000")
        unevaluable("DTSTART:20261115")                      // 8 digits without VALUE=DATE
        unevaluable("DTSTART;TZID=Europe/Berlin:20261115T100000", "DURATION:one hour")
        unevaluable("DTSTART;TZID=Europe/Berlin:20261115T100000", "EXDATE;TZID=Mars/Olympus:20261122T100000")
    }

    @Test
    fun `no DTEND - DURATION as exact seconds, else nothing for a timed and one day for an all-day event`() {
        val d = compute("DTSTART;TZID=Europe/Berlin:20261115T100000", "DURATION:PT1H30M")
        assertEquals(d.dtStart!! + 5_400_000, d.dtEnd)
        assertEquals("Europe/Berlin", d.endTimezone)
        // P1D across Berlin's clock change is 24 hours, not one calendar day (ical4j adds it to an instant).
        val p1d = compute("DTSTART;TZID=Europe/Berlin:20261024T100000", "DURATION:P1D")
        assertEquals(utc("2026-10-24T08:00:00Z"), p1d.dtStart)
        assertEquals(utc("2026-10-25T08:00:00Z"), p1d.dtEnd)
        val zero = compute("DTSTART;TZID=Europe/Berlin:20261115T100000")
        assertEquals(zero.dtStart, zero.dtEnd)
        assertNull(zero.duration)
    }

    @Test
    fun `a series stores DURATION instead of DTEND, from the instants`() {
        assertEquals("P0S", compute("DTSTART;TZID=Europe/Berlin:20261115T100000", "RRULE:FREQ=DAILY").duration)
        val allDay = compute("DTSTART;VALUE=DATE:20261115", "RRULE:FREQ=YEARLY")
        assertEquals("P86400S", allDay.duration)
        assertNull(allDay.dtEnd)
        assertNull(allDay.endTimezone)
        // Across the fall-back night 01:00 CEST to 04:00 CET is four hours.
        val acrossDst = compute("DTSTART;TZID=Europe/Berlin:20261025T010000", "DTEND;TZID=Europe/Berlin:20261025T040000", "RRULE:FREQ=WEEKLY")
        assertEquals("P14400S", acrossDst.duration)
        assertNull(acrossDst.dtEnd)
    }

    @Test
    fun `RRULE with DURATION and no DTEND keeps the existing behaviour - DTEND, no DURATION column`() {
        // Pinned as it was, not fixed in passing: the derived end is written as DTEND and no DURATION column
        // at all (CalendarMapper only writes "P<n>S" when there is no DURATION property).
        val t = compute("DTSTART;TZID=Europe/Berlin:20261115T100000", "DURATION:PT1H", "RRULE:FREQ=WEEKLY")
        assertNull(t.duration)
        assertEquals(t.dtStart!! + 3_600_000, t.dtEnd)
    }

    @Test
    fun `DTSTART and DTEND in different zones keep their own zones`() {
        val t = compute("DTSTART;TZID=Europe/Berlin:20261115T100000", "DTEND;TZID=America/New_York:20261115T100000")
        assertEquals("Europe/Berlin", t.eventTimezone)
        assertEquals("America/New_York", t.endTimezone)
        assertEquals(utc("2026-11-15T15:00:00Z"), t.dtEnd)
    }

    @Test
    fun `RECURRENCE-ID with a TZID, Z, a date, and RANGE ignored`() {
        val zoned = compute("RECURRENCE-ID;TZID=Europe/Berlin:20261115T100000", "DTSTART;TZID=Europe/Berlin:20261115T120000")
        assertEquals(utc("2026-11-15T09:00:00Z"), zoned.originalInstanceTime)
        assertFalse(zoned.originalAllDay)
        assertEquals("RECURRENCE-ID;TZID=Europe/Berlin:20261115T100000", zoned.recurrenceIdLine)
        assertEquals(utc("2026-11-15T09:00:00Z"),
            compute("RECURRENCE-ID:20261115T090000Z", "DTSTART:20261115T110000Z").originalInstanceTime)
        val date = compute("RECURRENCE-ID;VALUE=DATE:20261115", "DTSTART;VALUE=DATE:20261116")
        assertEquals(utc("2026-11-15T00:00:00Z"), date.originalInstanceTime)
        assertTrue(date.originalAllDay)
        assertEquals(utc("2026-11-15T09:00:00Z"),
            compute("RECURRENCE-ID;RANGE=THISANDFUTURE;TZID=Europe/Berlin:20261115T100000",
                "DTSTART;TZID=Europe/Berlin:20261115T100000", "RRULE:FREQ=WEEKLY").originalInstanceTime)
    }

    @Test
    fun `EXDATE - every line, own zones, UTC kept, de-duplicated, raw text in the column`() {
        val t = compute(
            "DTSTART;TZID=Europe/Berlin:20261115T100000", "RRULE:FREQ=WEEKLY",
            "EXDATE;TZID=Europe/Berlin:20261122T100000,20261129T100000",
            "EXDATE:20261206T090000Z",
            "EXDATE;TZID=Europe/Berlin:20261122T100000"
        )
        assertEquals(listOf(utc("2026-11-22T09:00:00Z"), utc("2026-11-29T09:00:00Z"), utc("2026-12-06T09:00:00Z")), t.exdateInstants)
        assertEquals("20261122T100000,20261129T100000,20261206T090000Z", t.exdateColumn)
        assertEquals("Europe/Berlin", t.exdateRowTimezone)
    }

    @Test
    fun `EXDATE of an all-day series is the date's UTC midnight`() {
        val t = compute("DTSTART;VALUE=DATE:20261115", "RRULE:FREQ=WEEKLY",
            "EXDATE;VALUE=DATE:20261122", "EXDATE;TZID=Europe/Berlin:20261129T000000")
        // The DATE-TIME given where a date belongs: its UTC date (Berlin's midnight is the 28th in UTC).
        assertEquals(listOf(utc("2026-11-22T00:00:00Z"), utc("2026-11-28T00:00:00Z")), t.exdateInstants)
        assertEquals("UTC", t.exdateRowTimezone)
    }

    @Test
    fun `RDATE - the first line's text, a PERIOD keeps ical4j's text`() {
        val t = compute("DTSTART;TZID=Europe/Berlin:20261115T100000",
            "RDATE;TZID=Europe/Berlin:20261201T100000,20261208T100000", "RDATE:20261215T090000Z")
        assertEquals("20261201T100000,20261208T100000", t.rdateColumn)
        assertFalse("RDATE makes a series", t.standalone)
        val period = compute("DTSTART;TZID=Europe/Berlin:20261115T100000", "RDATE;VALUE=PERIOD:20261201T100000/PT1H",
            periodRdate = "20261201T090000Z/PT1H")
        assertEquals("20261201T090000Z/PT1H", period.rdateColumn)
    }

    // ---- (c) gaps and overlaps no longer depend on the phone's own zone ----

    @Test
    fun `a time in a gap moves forward, the same on every phone`() {
        for (device in listOf("Europe/Berlin", "America/New_York", "Asia/Kolkata")) {
            assertEquals(device, utc("2026-03-29T01:30:00Z"), compute("DTSTART;TZID=Europe/Berlin:20260329T023000", device = device).dtStart)
        }
        // A time in the PHONE's own gap (Berlin's, on 29 March) is not shifted when it names another zone.
        assertEquals(utc("2026-03-29T06:30:00Z"), compute("DTSTART;TZID=America/New_York:20260329T023000", device = "Europe/Berlin").dtStart)
    }

    @Test
    fun `a one-off time in an overlap takes the earlier offset`() {
        assertEquals(utc("2026-10-25T00:30:00Z"), compute("DTSTART;TZID=Europe/Berlin:20261025T023000").dtStart)
        assertEquals(utc("2026-11-01T05:15:00Z"), compute("DTSTART;TZID=America/New_York:20261101T011500").dtStart)
    }

    @Test
    fun `what the provider expands takes the later offset in an overlap, as its GregorianCalendar and ical4j did`() {
        // New York falls back on 2026-11-01: 01:15 occurs at 05:15Z (EDT) and again at 06:15Z (EST). The provider
        // expands the series to 06:15Z and links exceptions only at that millisecond.
        val series = compute(
            "DTSTART;TZID=America/New_York:20261018T011500", "DTEND;TZID=America/New_York:20261018T021500",
            "RRULE:FREQ=WEEKLY", "EXDATE;TZID=America/New_York:20261101T011500"
        )
        assertEquals(listOf(utc("2026-11-01T06:15:00Z")), series.exdateInstants)
        val override = compute(
            "RECURRENCE-ID;TZID=America/New_York:20261101T011500",
            "DTSTART;TZID=America/New_York:20261101T090000", "DTEND;TZID=America/New_York:20261101T100000"
        )
        assertEquals("the occurrence, as the provider expands it", utc("2026-11-01T06:15:00Z"), override.originalInstanceTime)
        assertEquals("its own start, a one-off time", utc("2026-11-01T14:00:00Z"), override.dtStart)
        // A series starting inside the overlap: the instant the provider expands from, and a length that agrees.
        val starting = compute(
            "DTSTART;TZID=America/New_York:20261101T011500", "DTEND;TZID=America/New_York:20261101T021500", "RRULE:FREQ=WEEKLY"
        )
        assertEquals(utc("2026-11-01T06:15:00Z"), starting.dtStart)
        assertEquals("P3600S", starting.duration)
        // Berlin and Sydney the same way.
        assertEquals(listOf(utc("2026-10-25T01:30:00Z")), compute("DTSTART;TZID=Europe/Berlin:20261011T023000",
            "RRULE:FREQ=WEEKLY", "EXDATE;TZID=Europe/Berlin:20261025T023000").exdateInstants)
        assertEquals(utc("2026-04-04T16:30:00Z"), compute("RECURRENCE-ID;TZID=Australia/Sydney:20260405T023000",
            "DTSTART;TZID=Australia/Sydney:20260405T120000").originalInstanceTime)
        // A gap is read the same way in both scopes: forward.
        assertEquals(listOf(utc("2026-03-29T01:30:00Z")), compute("DTSTART;TZID=Europe/Berlin:20260322T023000",
            "RRULE:FREQ=WEEKLY", "EXDATE;TZID=Europe/Berlin:20260329T023000").exdateInstants)
    }

    // ---- (d) the zones ical4j got wrong, now the phone's own rules ----

    @Test
    fun `the zones ical4j misread or knew outdated now follow the phone's rules exactly`() {
        val cases = listOf(
            "Africa/Cairo" to "20270715T100000",
            "Asia/Almaty" to "20250715T100000",
            "Asia/Qostanay" to "20250715T100000",
            "America/Asuncion" to "20260715T100000",
            "America/Scoresbysund" to "20250715T100000",
            "America/Nuuk" to "20250715T100000",
            "Asia/Gaza" to "20250405T100000",
            "Pacific/Fiji" to "20270115T100000",
            "Australia/Darwin" to "20270115T100000",
            "Pacific/Efate" to "20270115T100000",
            "Australia/Lord_Howe" to "20270122T100000",
            "Antarctica/Troll" to "20270715T100000",
            "Antarctica/Casey" to "20270715T100000",
        )
        for ((zone, local) in cases) {
            val t = compute("DTSTART;TZID=$zone:$local", "DTEND;TZID=$zone:$local", "RECURRENCE-ID;TZID=$zone:$local")
            assertEquals("$zone start", at(local, zone), t.dtStart)
            assertEquals("$zone end", at(local, zone), t.dtEnd)
            assertEquals("$zone occurrence", at(local, zone), t.originalInstanceTime)
            assertEquals(zone, t.eventTimezone)
        }
    }

    @Test
    fun `Gaza - the series and its exclusion a week later agree`() {
        val t = compute("DTSTART;TZID=Asia/Gaza:20250405T100000", "RRULE:FREQ=WEEKLY",
            "EXDATE;TZID=Asia/Gaza:20250412T100000")
        assertEquals(at("20250405T100000", "Asia/Gaza"), t.dtStart)
        assertEquals(listOf(at("20250412T100000", "Asia/Gaza")), t.exdateInstants)
    }

    @Test
    fun `the EXDATE column and the cached RECURRENCE-ID are Proton's own text, never re-formatted`() {
        // Where ical4j misread the zone, its re-formatted value was a different time (Lord Howe 20270121T233000
        // for 20270122T100000). The raw text cannot be wrong that way.
        val t = compute("DTSTART;TZID=Australia/Lord_Howe:20270115T100000", "RRULE:FREQ=WEEKLY",
            "EXDATE;TZID=Australia/Lord_Howe:20270122T100000", "RECURRENCE-ID;TZID=Australia/Lord_Howe:20270115T100000")
        assertEquals("20270122T100000", t.exdateColumn)
        assertEquals("RECURRENCE-ID;TZID=Australia/Lord_Howe:20270115T100000", t.recurrenceIdLine)
        // And the upload's EXDATE line, built from that column, round-trips the line Proton sent.
        assertEquals("EXDATE;TZID=Australia/Lord_Howe:20270122T100000",
            "EXDATE;TZID=Australia/Lord_Howe:" + t.exdateColumn)
    }

    // ---- (f) ON mode: online rules for one-off times only ----

    private fun fixture(tzid: String) = javaClass.getResource("/tzurl/${tzid.replace('/', '_')}.ics")!!.readText()

    private fun onlineSnapshot(phoneVersion: String = "2021a") = RuleSnapshot(
        true,
        OnlineTimeZoneRules(fetch = { fixture(it) }, phoneVersion = { phoneVersion }, phoneRules = { ZoneId.of(it).rules })
    )

    @Test
    fun `ON - a one-off event and an override's own times use the online rules, a series the phone's`() {
        StalePhoneJvm.assume()
        val rules = onlineSnapshot()
        // tzurl 2026d: Vancouver stays at -07 from November 2026. The JVM's older tzdb (the stale phone): -08.
        val oneOff = compute("DTSTART;TZID=America/Vancouver:20261201T100000", "DTEND;TZID=America/Vancouver:20261201T110000", rules = rules)
        assertEquals(utc("2026-12-01T17:00:00Z"), oneOff.dtStart)
        assertEquals(utc("2026-12-01T18:00:00Z"), oneOff.dtEnd)
        assertTrue(oneOff.standalone)

        val series = compute("DTSTART;TZID=America/Vancouver:20261201T100000", "DTEND;TZID=America/Vancouver:20261201T110000",
            "RRULE:FREQ=WEEKLY", "EXDATE;TZID=America/Vancouver:20261208T100000", rules = rules)
        assertEquals(at("20261201T100000", "America/Vancouver"), series.dtStart)
        assertEquals(utc("2026-12-01T18:00:00Z"), series.dtStart)
        assertEquals("P3600S", series.duration)
        assertEquals(listOf(at("20261208T100000", "America/Vancouver")), series.exdateInstants)

        val override = compute("RECURRENCE-ID;TZID=America/Vancouver:20261201T100000",
            "DTSTART;TZID=America/Vancouver:20261201T120000", rules = rules)
        assertEquals("the occurrence it replaces: phone rules", utc("2026-12-01T18:00:00Z"), override.originalInstanceTime)
        assertEquals("its own start: online rules", utc("2026-12-01T19:00:00Z"), override.dtStart)
    }

    @Test
    fun `ON on an up-to-date phone - tzurl is not newer, so the phone's rules`() {
        val t = compute("DTSTART;TZID=America/Vancouver:20261201T100000", rules = onlineSnapshot(phoneVersion = "2026e"))
        assertEquals(at("20261201T100000", "America/Vancouver"), t.dtStart)
    }
}

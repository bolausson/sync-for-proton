package me.proton.sync.calendar

import net.fortuna.ical4j.data.CalendarBuilder
import net.fortuna.ical4j.model.Calendar
import net.fortuna.ical4j.model.Component
import net.fortuna.ical4j.model.Property
import net.fortuna.ical4j.model.component.VEvent
import net.fortuna.ical4j.model.component.VTimeZone
import net.fortuna.ical4j.model.property.Attendee
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.StringReader
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Locale
import java.util.TimeZone
import net.fortuna.ical4j.model.TimeZone as IcalTimeZone

/**
 * The invitation .ics and email text.
 *
 * The first fixtures use UTC, which skips the VTIMEZONE block; the later ones use real zones. Every
 * check reads the unfolded lines one at a time: DTSTAMP is the time of the call.
 */
class CalendarInviteSenderIcsTest {

    @Before
    fun relaxLikeTheApp() {
        System.setProperty("ical4j.parsing.relaxed", "true")
        System.setProperty("ical4j.unfolding.relaxed", "true")
        System.setProperty("ical4j.compatibility.outlook", "true")
    }

    private val organizer = "organizer@example.com"
    private val guest = "guest@example.com"

    private fun event(
        summary: String = "SFP E2E",
        description: String? = null,
        location: String? = null,
        attendees: List<CalendarInviteSender.AttendeeInfo> = listOf(CalendarInviteSender.AttendeeInfo(guest, "Test Guest", "tok")),
        uid: String = "uid-1",
        organizerName: String? = "organizer",
        rruleLine: String? = null,
        exdateLines: String? = null
    ) = CalendarInviteSender.EventInfo(
        uid = uid, summary = summary, description = description, location = location,
        dtStart = 1_790_236_800_000L, dtEnd = 1_790_240_400_000L, timezone = "UTC", allDay = false,
        organizer = organizer, attendees = attendees, sharedEventId = null, sessionKey = null,
        sequence = 1, rruleLine = rruleLine, exdateLines = exdateLines, organizerName = organizerName
    )

    private fun unfold(ics: String) = ics.replace("\r\n ", "")
    private fun lines(ics: String) = unfold(ics).split("\r\n")
    private fun line(ics: String, name: String) = lines(ics).first { it.startsWith(name) }
    private fun octets(s: String) = s.toByteArray(Charsets.UTF_8).size
    private fun parse(ics: String): VEvent =
        CalendarBuilder().build(StringReader(ics)).getComponents<VEvent>("VEVENT").first()

    // ---- escaping and folding ----

    @Test
    fun summaryIsEscapedLikeEveryOtherText() {
        val ics = CalendarInviteSender.buildIcs(event(summary = "Lunch, Bob; a\\b"), "REQUEST")
        assertEquals("SUMMARY:Lunch\\, Bob\\; a\\\\b", line(ics, "SUMMARY:"))
    }

    @Test
    fun lineBreaksOfAnyKindBecomeEscapedNewlinesAndNoBareCrSurvives() {
        val ics = CalendarInviteSender.buildIcs(event(description = "one\r\ntwo\rthree\nfour\u000Bfive"), "REQUEST")
        assertEquals("DESCRIPTION:one\\ntwo\\nthree\\nfour\\nfive", line(ics, "DESCRIPTION:"))
        for (physical in ics.split("\r\n")) assertFalse(physical, physical.contains('\r'))
    }

    @Test
    fun controlCharactersAreDroppedButTabsKept() {
        assertEquals("a\tb", CalendarInviteSender.escapeIcsText("a\u0001\tb\u007F"))
    }

    @Test
    fun everyPhysicalLineFitsIn75Octets() {
        val long = "Grüße aus Köln, Привет; 東京で 😀 ".repeat(6)
        val ics = CalendarInviteSender.buildIcs(
            event(
                summary = long, description = long, location = long, uid = "u".repeat(150),
                rruleLine = "RRULE:FREQ=DAILY;COUNT=8;WKST=MO\r\n",
                exdateLines = "EXDATE:" + List(8) { "2026092${it}T080000Z" }.joinToString(",") + "\r\n"
            ),
            "REQUEST"
        )
        for (physical in ics.split("\r\n")) assertTrue("${octets(physical)}: $physical", octets(physical) <= 75)
        assertTrue(ics.endsWith("END:VCALENDAR\r\n"))
    }

    @Test
    fun escapedTextRoundTripsThroughIcal4j() {
        val title = "Plan: A, B; C \\ D"
        val description = "first line\nsecond, with ; and \\"
        val parsed = parse(CalendarInviteSender.buildIcs(event(summary = title, description = description), "REQUEST"))
        assertEquals(title, parsed.summary.value)
        assertEquals(description, parsed.description.value)
    }

    @Test
    fun nameParametersAreQuotedWhenTheyMustBe() {
        assertEquals("\"Doe, Zoë\"", CalendarInviteSender.icsParamValue("Doe, Zoë"))
        assertEquals("\"Team: Sales\"", CalendarInviteSender.icsParamValue("Team: Sales"))
        assertEquals("A B", CalendarInviteSender.icsParamValue("A \"B\""))
        assertEquals("Zoë Doe", CalendarInviteSender.icsParamValue("Zoë Doe"))
        val ics = CalendarInviteSender.buildIcs(
            event(organizerName = "Doe, Z.", attendees = listOf(CalendarInviteSender.AttendeeInfo(guest, "Team: Sales", "tok"))),
            "REQUEST"
        )
        assertTrue(line(ics, "ORGANIZER").startsWith("ORGANIZER;CN=\"Doe, Z.\":mailto:$organizer"))
        assertTrue(line(ics, "ATTENDEE").startsWith("ATTENDEE;CN=\"Team: Sales\";"))
        assertTrue(line(ics, "ATTENDEE").endsWith(":mailto:$guest"))
    }

    // ---- what a guest line says about the answer ----

    private fun attendeeLine(att: CalendarInviteSender.AttendeeInfo, method: String = "REQUEST") =
        line(CalendarInviteSender.buildIcs(event(attendees = listOf(att)), method), "ATTENDEE")

    @Test
    fun aFreshInvitationAsksForAnAnswer() {
        val l = attendeeLine(CalendarInviteSender.AttendeeInfo(guest, null, "tok"))
        assertTrue(l, l.contains(";RSVP=TRUE") && l.contains(";PARTSTAT=NEEDS-ACTION"))
    }

    /** Receivers apply the organiser's PARTSTAT, so an update states the answer the guest gave. */
    @Test
    fun anUpdateStatesTheGuestsAnswer() {
        val l = attendeeLine(CalendarInviteSender.AttendeeInfo(guest, null, "tok", "ACCEPTED"))
        assertTrue(l, l.contains(";RSVP=TRUE;PARTSTAT=ACCEPTED;"))
    }

    @Test
    fun aCancellationIsUnaffected() {
        val l = attendeeLine(CalendarInviteSender.AttendeeInfo(guest, null, "tok", "ACCEPTED"), method = "CANCEL")
        assertTrue(l, l.startsWith("ATTENDEE;CUTYPE=INDIVIDUAL;ROLE=REQ-PARTICIPANT;PARTSTAT=NEEDS-ACTION"))
    }

    // ---- wording ----

    @Test
    fun bodySaysWhatHappened() {
        fun first(ev: CalendarInviteSender.EventInfo, method: String, update: Boolean) =
            CalendarInviteSender.buildPlainTextBody(ev, method, update).lines().first()
        assertEquals("You are invited to SFP E2E.", first(event(), "REQUEST", false))
        assertEquals("SFP E2E has been updated.", first(event(), "REQUEST", true))
        assertEquals("SFP E2E was canceled.", first(event(), "CANCEL", false))
        assertEquals("You are invited to an event.", first(event(summary = " "), "REQUEST", false))
        assertEquals("The event has been updated.", first(event(summary = ""), "REQUEST", true))
    }

    @Test
    fun subjectWordingIsUnchanged() {
        val s = CalendarInviteSender.buildSubject(event(), "REQUEST", isUpdate = true, includeTitleInSubject = false)
        assertTrue(s, s.startsWith("Updated invitation for an event starting on Thursday 24 September 2026 at 08:00"))
        val titled = CalendarInviteSender.buildSubject(event(), "REQUEST", isUpdate = false, includeTitleInSubject = true)
        assertTrue(titled, titled.startsWith("SFP E2E - Invitation for an event starting on"))
    }

    // ---- shared fixtures ----

    private val other = "second@example.com"
    private val hour = 3_600_000L
    private val berlin = ZoneId.of("Europe/Berlin")

    /** A daily Berlin series (10:00 local, the same instant as [event]) with everything a REQUEST carries. */
    private fun series(
        attendees: List<CalendarInviteSender.AttendeeInfo> = listOf(CalendarInviteSender.AttendeeInfo(guest, "Test Guest", "tok")),
        recurrenceIdLine: String? = null
    ) = event(
        description = "Bring the slides", location = "Room 1", attendees = attendees,
        rruleLine = "RRULE:FREQ=DAILY;COUNT=5\r\n", exdateLines = "EXDATE;TZID=Europe/Berlin:20260925T100000\r\n"
    ).copy(timezone = "Europe/Berlin", recurrenceIdLine = recurrenceIdLine, sharedEventId = "shared-1", sessionKey = "c2Vzc2lvbi1rZXk=")

    private fun utc(year: Int, month: Int, day: Int) =
        ZonedDateTime.of(year, month, day, 12, 0, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()

    private fun utcMidnight(year: Int, month: Int, day: Int) =
        LocalDate.of(year, month, day).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    private fun berlinTime(month: Int, day: Int, hourOfDay: Int) =
        ZonedDateTime.of(2026, month, day, hourOfDay, 0, 0, 0, berlin).toInstant().toEpochMilli()

    /** All-day in September 2026, as the provider stores it: UTC midnights, end exclusive. */
    private fun allDay(fromDay: Int, untilDay: Int) = event().copy(
        allDay = true, timezone = "UTC", dtStart = utcMidnight(2026, 9, fromDay), dtEnd = utcMidnight(2026, 9, untilDay)
    )

    /** Saturday 22:00 summer time to Sunday 10:00 winter time: Berlin leaves DST on 25 October 2026. */
    private fun dstCrossing() = event().copy(timezone = "Europe/Berlin", dtStart = berlinTime(10, 24, 22), dtEnd = berlinTime(10, 25, 10))

    private fun parseCalendar(ics: String): Calendar = CalendarBuilder().build(StringReader(ics))

    private fun zonesOf(ics: String): List<String> =
        parseCalendar(ics).getComponents<VTimeZone>(Component.VTIMEZONE).map { it.timeZoneId.value }

    private fun attendeesOf(ics: String): List<String> =
        parse(ics).getProperties<Attendee>(Property.ATTENDEE).map { it.calAddress.toString() }

    private fun has(ics: String, name: String) = lines(ics).any { it.startsWith(name) }

    private fun body(ev: CalendarInviteSender.EventInfo, method: String, update: Boolean = false) =
        CalendarInviteSender.buildPlainTextBody(ev, method, update)

    private fun firstLine(ev: CalendarInviteSender.EventInfo, method: String, update: Boolean = false) =
        body(ev, method, update).lines().first()

    /** The line after "TIME:" in a body. */
    private fun timeLine(text: String): String {
        val l = text.lines()
        return l[l.indexOf("TIME:") + 1]
    }

    private fun subject(ev: CalendarInviteSender.EventInfo, method: String, update: Boolean = false, title: Boolean = false) =
        CalendarInviteSender.buildSubject(ev, method, isUpdate = update, includeTitleInSubject = title)

    private fun abbr(tzId: String, epochMillis: Long) = CalendarInviteSender.getTimezoneAbbr(tzId, epochMillis)

    /** Runs [block] with the JVM's default zone set to [tzId], as a phone in that zone would be. */
    private inline fun inDefaultZone(tzId: String, block: () -> Unit) {
        val saved = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(tzId))
        try {
            block()
        } finally {
            TimeZone.setDefault(saved)
        }
    }

    /** The definition ical4j read gives the JVM's own offset for the zone, four days a month for three years. */
    private fun assertDefinesTheRealZone(vtz: VTimeZone) {
        val id = vtz.timeZoneId.value
        val defined = IcalTimeZone(vtz)
        val real = TimeZone.getTimeZone(id)
        for (year in 2026..2028) for (month in 1..12) for (day in listOf(1, 8, 15, 22)) {
            val t = utc(year, month, day)
            assertEquals("$id on $year-$month-$day", real.getOffset(t), defined.getOffset(t))
        }
    }

    // ---- cancellations ----

    @Test
    fun `a cancellation names only its recipients and drops the description and EXDATEs`() {
        val stays = CalendarInviteSender.AttendeeInfo(guest, "Test Guest", "tok-1", "ACCEPTED")
        val removed = CalendarInviteSender.AttendeeInfo(other, "Other Guest", "tok-2")
        val full = series(attendees = listOf(stays, removed))
        val cancel = CalendarInviteSender.cancellationOf(full, listOf(removed), wholeEvent = false)
        // Only these four fields change; the rest is the event itself.
        assertEquals(full.copy(attendees = listOf(removed), description = null, exdateLines = null, cancelWholeEvent = false), cancel)

        val ics = CalendarInviteSender.buildIcs(cancel, "CANCEL")
        assertTrue(ics.contains("\r\nMETHOD:CANCEL\r\n"))
        assertEquals(listOf("mailto:$other"), attendeesOf(ics))
        assertTrue(ics.contains("\r\nRRULE:FREQ=DAILY;COUNT=5\r\n"))
        assertFalse(has(ics, "DESCRIPTION"))
        assertFalse(has(ics, "EXDATE"))
    }

    @Test
    fun `a whole-event cancellation says the event is cancelled`() {
        val full = series()
        assertTrue(full.cancelWholeEvent)   // the default
        val cancel = CalendarInviteSender.cancellationOf(full, full.attendees, wholeEvent = true)
        assertTrue(cancel.cancelWholeEvent)
        val ics = CalendarInviteSender.buildIcs(cancel, "CANCEL")
        assertTrue(ics, ics.contains("\r\nSTATUS:CANCELLED\r\n"))
        assertEquals(1, lines(ics).count { it.startsWith("STATUS") })
        val parsed = parse(ics)
        assertEquals("CANCELLED", parsed.status.value)
        assertEquals(listOf("mailto:$guest"), attendeesOf(ics))
    }

    @Test
    fun `a removed guest gets a cancellation without any STATUS`() {
        val removed = CalendarInviteSender.AttendeeInfo(other, null, "tok-2")
        val full = series(attendees = listOf(CalendarInviteSender.AttendeeInfo(guest, null, "tok-1"), removed))
        val cancel = CalendarInviteSender.cancellationOf(full, listOf(removed), wholeEvent = false)
        val ics = CalendarInviteSender.buildIcs(cancel, "CANCEL")
        assertFalse(ics, has(ics, "STATUS"))
        val parsed = parse(ics)
        assertNull(parsed.status)
        assertEquals(listOf("mailto:$other"), attendeesOf(ics))
        assertEquals("shared-1", parsed.getProperty<Property>("X-PM-SHARED-EVENT-ID").value)
        assertEquals("SFP E2E was canceled.", firstLine(cancel, "CANCEL"))
    }

    /** buildIcs drops them itself, even when handed an event that still has them. */
    @Test
    fun `a cancellation never carries the description, EXDATE or session key`() {
        val ics = CalendarInviteSender.buildIcs(series(), "CANCEL")
        assertFalse(has(ics, "DESCRIPTION"))
        assertFalse(has(ics, "EXDATE"))
        assertFalse(has(ics, "X-PM-SESSION-KEY"))
        assertTrue(ics.contains("\r\nX-PM-SHARED-EVENT-ID:shared-1\r\n"))
        assertTrue(ics.contains("\r\nSTATUS:CANCELLED\r\n"))
        val parsed = parse(ics)
        assertNull(parsed.description)
        assertEquals(0, parsed.getProperties<Property>(Property.EXDATE).size)
        assertNull(parsed.getProperty<Property>("X-PM-SESSION-KEY"))
        assertEquals("shared-1", parsed.getProperty<Property>("X-PM-SHARED-EVENT-ID").value)
        assertEquals("CANCELLED", parsed.status.value)
    }

    @Test
    fun `a request keeps STATUS CONFIRMED, the description, EXDATE and both Proton lines`() {
        // cancelWholeEvent only matters to a cancellation.
        for (ev in listOf(series(), series().copy(cancelWholeEvent = false))) {
            val ics = CalendarInviteSender.buildIcs(ev, "REQUEST")
            assertTrue(ics.contains("\r\nSTATUS:CONFIRMED\r\n"))
            assertTrue(ics.contains("\r\nDESCRIPTION:Bring the slides\r\n"))
            assertTrue(ics.contains("\r\nEXDATE;TZID=Europe/Berlin:20260925T100000\r\n"))
            assertTrue(ics.contains("\r\nX-PM-SHARED-EVENT-ID:shared-1\r\n"))
            assertTrue(ics.contains("\r\nX-PM-SESSION-KEY:c2Vzc2lvbi1rZXk=\r\n"))
            val parsed = parse(ics)
            assertEquals("CONFIRMED", parsed.status.value)
            assertEquals("Bring the slides", parsed.description.value)
            assertEquals(1, parsed.getProperties<Property>(Property.EXDATE).size)
            assertEquals("shared-1", parsed.getProperty<Property>("X-PM-SHARED-EVENT-ID").value)
            assertEquals("c2Vzc2lvbi1rZXk=", parsed.getProperty<Property>("X-PM-SESSION-KEY").value)
            assertEquals(listOf("mailto:$guest"), attendeesOf(ics))
        }
    }

    // ---- VTIMEZONE ----

    @Test
    fun `a timed event gets one VTIMEZONE for its zone, before the event`() {
        val ics = CalendarInviteSender.buildIcs(event().copy(timezone = "Europe/Berlin"), "REQUEST")
        val l = lines(ics)
        assertEquals(1, l.count { it == "BEGIN:VTIMEZONE" })
        assertTrue(ics.contains("\r\nCALSCALE:GREGORIAN\r\nBEGIN:VTIMEZONE\r\nTZID:Europe/Berlin\r\nX-LIC-LOCATION:Europe/Berlin\r\n"))
        assertTrue(l.indexOf("END:VTIMEZONE") < l.indexOf("BEGIN:VEVENT"))
        assertTrue(ics.contains("\r\nDTSTART;TZID=Europe/Berlin:20260924T100000\r\n"))
        assertEquals(listOf("Europe/Berlin"), zonesOf(ics))
        assertEquals(1_790_236_800_000L, parse(ics).startDate.date.time)
    }

    @Test
    fun `the Berlin definition has the last-Sunday rules`() {
        val ics = CalendarInviteSender.buildIcs(event().copy(timezone = "Europe/Berlin"), "REQUEST")
        assertTrue(ics, ics.contains("\r\nTZOFFSETFROM:+0100\r\nTZOFFSETTO:+0200\r\n"))
        assertTrue(ics, ics.contains("\r\nRRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=-1SU\r\n"))
        assertTrue(ics, ics.contains("\r\nRRULE:FREQ=YEARLY;BYMONTH=10;BYDAY=-1SU\r\n"))
        assertDefinesTheRealZone(parseCalendar(ics).getComponents<VTimeZone>(Component.VTIMEZONE).single())
    }

    /** The old template gave every zone the EU dates, four weeks a year wrong for New York. */
    @Test
    fun `a New York event gets the US rules, not the EU ones`() {
        val ics = CalendarInviteSender.buildIcs(event().copy(timezone = "America/New_York"), "REQUEST")
        assertTrue(ics, ics.contains("\r\nTZOFFSETFROM:-0500\r\nTZOFFSETTO:-0400\r\n"))
        assertTrue(ics, ics.contains("\r\nRRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=2SU\r\n"))
        assertTrue(ics, ics.contains("\r\nRRULE:FREQ=YEARLY;BYMONTH=11;BYDAY=1SU\r\n"))
        assertFalse(ics, ics.contains("-1SU"))
        assertTrue(ics.contains("\r\nDTSTART;TZID=America/New_York:20260924T040000\r\n"))
        assertEquals(1_790_236_800_000L, parse(ics).startDate.date.time)
        val vtz = parseCalendar(ics).getComponents<VTimeZone>(Component.VTIMEZONE).single()
        assertEquals("America/New_York", vtz.timeZoneId.value)
        assertDefinesTheRealZone(vtz)
        // 10 March 2026 is summer time in New York, still winter time under the EU dates.
        assertEquals(-4 * hour, IcalTimeZone(vtz).getOffset(utc(2026, 3, 10)).toLong())
    }

    @Test
    fun `EXDATE and RECURRENCE-ID zones get their own definitions, but a cancellation ignores the EXDATE ones`() {
        val ev = event(
            rruleLine = "RRULE:FREQ=DAILY;COUNT=5\r\n",
            exdateLines = "EXDATE;TZID=America/New_York:20260925T040000\r\n"
        ).copy(timezone = "Europe/Berlin", recurrenceIdLine = "RECURRENCE-ID;TZID=Asia/Kolkata:20260924T133000\r\n")

        val request = CalendarInviteSender.buildIcs(ev, "REQUEST")
        assertEquals(3, lines(request).count { it == "BEGIN:VTIMEZONE" })
        assertEquals(listOf("Europe/Berlin", "America/New_York", "Asia/Kolkata"), zonesOf(request))
        for (vtz in parseCalendar(request).getComponents<VTimeZone>(Component.VTIMEZONE)) assertDefinesTheRealZone(vtz)
        val parsed = parse(request)
        assertEquals(1_790_236_800_000L, parsed.startDate.date.time)
        assertEquals(1_790_236_800_000L, parsed.recurrenceId.date.time)

        val cancel = CalendarInviteSender.buildIcs(ev, "CANCEL")
        assertEquals(listOf("Europe/Berlin", "Asia/Kolkata"), zonesOf(cancel))
        assertFalse(cancel, cancel.contains("America/New_York"))

        // A UTC event gets a definition only for the zone its lines name.
        val utcSeries = CalendarInviteSender.buildIcs(event(exdateLines = "EXDATE;TZID=America/New_York:20260925T040000\r\n"), "REQUEST")
        assertEquals(listOf("America/New_York"), zonesOf(utcSeries))
    }

    @Test
    fun `a zone named more than once is defined once`() {
        val ics = CalendarInviteSender.buildIcs(series(recurrenceIdLine = "RECURRENCE-ID;TZID=Europe/Berlin:20260926T100000\r\n"), "REQUEST")
        assertEquals(1, lines(ics).count { it == "BEGIN:VTIMEZONE" })
        assertEquals(listOf("Europe/Berlin"), zonesOf(ics))
    }

    @Test
    fun `an all-day event and a UTC event have no VTIMEZONE`() {
        // All-day even with a named zone on the event: its dates carry no time and no zone.
        val allDayIcs = CalendarInviteSender.buildIcs(
            allDay(24, 25).copy(timezone = "Europe/Berlin", exdateLines = "EXDATE;VALUE=DATE:20260925\r\n"), "REQUEST"
        )
        assertFalse(allDayIcs, allDayIcs.contains("VTIMEZONE"))
        assertTrue(allDayIcs.contains("\r\nDTSTART;VALUE=DATE:20260924\r\n"))
        assertEquals(emptyList<String>(), zonesOf(allDayIcs))
        assertEquals("20260924", parse(allDayIcs).startDate.value)

        val utcIcs = CalendarInviteSender.buildIcs(event(exdateLines = "EXDATE:20260925T080000Z\r\n"), "REQUEST")
        assertFalse(utcIcs, utcIcs.contains("VTIMEZONE"))
        assertTrue(utcIcs.contains("\r\nDTSTART:20260924T080000Z\r\n"))
        assertEquals(emptyList<String>(), zonesOf(utcIcs))
        assertEquals(1_790_236_800_000L, parse(utcIcs).startDate.date.time)
    }

    @Test
    fun `an event across the DST change reads back as the same instants`() {
        val ev = dstCrossing()
        val ics = CalendarInviteSender.buildIcs(ev, "REQUEST")
        assertTrue(ics.contains("\r\nDTSTART;TZID=Europe/Berlin:20261024T220000\r\n"))
        assertTrue(ics.contains("\r\nDTEND;TZID=Europe/Berlin:20261025T100000\r\n"))
        val parsed = parse(ics)
        assertEquals(ev.dtStart, parsed.startDate.date.time)
        assertEquals(ev.dtEnd, parsed.endDate.date.time)
    }

    @Test
    fun `the calendar file stays ASCII under an Arabic locale`() {
        val saved = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag("ar"))   // Locale("ar"), which formats with Arabic-Indic digits
        val ics = try {
            CalendarInviteSender.buildIcs(event().copy(timezone = "Asia/Kolkata"), "REQUEST")
        } finally {
            Locale.setDefault(saved)
        }
        // Parsed only after the locale is back.
        assertEquals("", ics.filter { it.code > 126 })
        assertTrue(ics, ics.contains("\r\nTZOFFSETFROM:+0630\r\nTZOFFSETTO:+0530\r\n"))
        assertTrue(ics.contains("\r\nDTSTART;TZID=Asia/Kolkata:20260924T133000\r\n"))
        assertEquals(listOf("Asia/Kolkata"), zonesOf(ics))
        assertEquals(1_790_236_800_000L, parse(ics).startDate.date.time)
    }

    // ---- body wording and times ----

    @Test
    fun `one occurrence says so in the body`() {
        val occurrence = event().copy(recurrenceIdLine = "RECURRENCE-ID:20260924T080000Z\r\n")
        assertEquals("One occurrence of SFP E2E was canceled.", firstLine(occurrence, "CANCEL"))
        assertEquals("One occurrence of SFP E2E has been updated.", firstLine(occurrence, "REQUEST", update = true))
        assertEquals("One occurrence of an event was canceled.", firstLine(occurrence.copy(summary = " "), "CANCEL"))
        assertEquals("One occurrence of an event has been updated.", firstLine(occurrence.copy(summary = ""), "REQUEST", update = true))
        // A fresh invitation reads as before.
        assertEquals("You are invited to SFP E2E.", firstLine(occurrence, "REQUEST"))
        // An empty line is no recurrence id.
        assertEquals("SFP E2E was canceled.", firstLine(event().copy(recurrenceIdLine = ""), "CANCEL"))
        // The .ics names the occurrence it cancels.
        val ics = CalendarInviteSender.buildIcs(occurrence, "CANCEL")
        assertTrue(ics.contains("\r\nRECURRENCE-ID:20260924T080000Z\r\n"))
        assertEquals(1_790_236_800_000L, parse(ics).recurrenceId.date.time)
    }

    @Test
    fun `a cancellation without a title or recurrence id names the event`() {
        assertEquals("The event was canceled.", firstLine(event(summary = ""), "CANCEL"))
        assertEquals("The event was canceled.", firstLine(event(summary = "   "), "CANCEL"))
    }

    /** In Los Angeles a UTC midnight is still the day before: the old body showed Wednesday 23. */
    @Test
    fun `an all-day body reads its dates in UTC whatever the phone zone`() {
        var ics = ""
        inDefaultZone("America/Los_Angeles") {
            assertEquals("Thursday 24 September 2026 (all day)", timeLine(body(allDay(24, 25), "REQUEST")))
            assertEquals("Thursday 24 September 2026 - Friday 25 September 2026", timeLine(body(allDay(24, 26), "REQUEST")))
            assertEquals("Thursday 24 September 2026 - Saturday 26 September 2026", timeLine(body(allDay(24, 27), "CANCEL")))
            assertFalse(body(allDay(24, 25), "REQUEST").contains("GMT"))
            ics = CalendarInviteSender.buildIcs(allDay(24, 27), "REQUEST")
        }
        assertTrue(ics.contains("\r\nDTSTART;VALUE=DATE:20260924\r\n"))
        assertTrue(ics.contains("\r\nDTEND;VALUE=DATE:20260927\r\n"))
        assertEquals("20260924", parse(ics).startDate.value)
    }

    @Test
    fun `a timed body gives each end its own offset`() {
        assertEquals(
            "Saturday 24 October 2026 at 22:00 (GMT+2) - Sunday 25 October 2026 at 10:00 (GMT+1)",
            timeLine(body(dstCrossing(), "REQUEST"))
        )
        assertEquals(
            "Thursday 24 September 2026 at 10:00 (GMT+2) - Thursday 24 September 2026 at 11:00 (GMT+2)",
            timeLine(body(event().copy(timezone = "Europe/Berlin"), "REQUEST"))
        )
        assertEquals(
            "Thursday 24 September 2026 at 08:00 (GMT+0) - Thursday 24 September 2026 at 09:00 (GMT+0)",
            timeLine(body(event(), "CANCEL"))
        )
    }

    @Test
    fun `a cancellation body leaves out the description`() {
        assertTrue(body(series(), "REQUEST").contains("DESCRIPTION:\nBring the slides"))
        assertFalse(body(series(), "CANCEL").contains("Bring the slides"))
    }

    // ---- subject ----

    @Test
    fun `an all-day subject has only the date`() {
        inDefaultZone("America/Los_Angeles") {
            assertEquals("Invitation for an event on Thursday 24 September 2026", subject(allDay(24, 25), "REQUEST"))
            assertEquals("Invitation for an event starting on Thursday 24 September 2026", subject(allDay(24, 27), "REQUEST"))
            assertEquals("Updated invitation for an event starting on Thursday 24 September 2026", subject(allDay(24, 27), "REQUEST", update = true))
            assertEquals("Cancellation of an event on Thursday 24 September 2026", subject(allDay(24, 25), "CANCEL"))
            assertEquals("Cancellation of an event starting on Thursday 24 September 2026", subject(allDay(24, 26), "CANCEL"))
            assertEquals("SFP E2E - Invitation for an event on Thursday 24 September 2026", subject(allDay(24, 25), "REQUEST", title = true))
        }
    }

    @Test
    fun `a timed subject keeps its time and zone`() {
        val inBerlin = event().copy(timezone = "Europe/Berlin")
        assertEquals("Invitation for an event starting on Thursday 24 September 2026 at 10:00 (GMT+2)", subject(inBerlin, "REQUEST"))
        assertEquals("Updated invitation for an event starting on Thursday 24 September 2026 at 10:00 (GMT+2)", subject(inBerlin, "REQUEST", update = true))
        assertEquals("Cancellation of an event starting on Thursday 24 September 2026 at 10:00 (GMT+2)", subject(inBerlin, "CANCEL"))
        assertEquals(
            "SFP E2E - Cancellation of an event starting on Thursday 24 September 2026 at 10:00 (GMT+2)",
            subject(inBerlin, "CANCEL", title = true)
        )
        assertEquals(
            "Invitation for an event starting on Thursday 24 September 2026 at 04:00 (GMT-4)",
            subject(event().copy(timezone = "America/New_York"), "REQUEST")
        )
        // A blank title adds no prefix.
        assertEquals(
            "Invitation for an event starting on Thursday 24 September 2026 at 08:00 (GMT+0)",
            subject(event(summary = "  "), "REQUEST", title = true)
        )
    }

    // ---- getTimezoneAbbr ----

    private fun assertZoneNames() {
        val summer = utc(2026, 7, 1)
        val winter = utc(2026, 1, 15)
        assertEquals("GMT+2", abbr("Europe/Berlin", summer))
        assertEquals("GMT+1", abbr("Europe/Berlin", winter))
        assertEquals("GMT-4", abbr("America/New_York", summer))
        assertEquals("GMT-5", abbr("America/New_York", winter))
        assertEquals("GMT+5:30", abbr("Asia/Kolkata", summer))
        assertEquals("GMT+5:45", abbr("Asia/Kathmandu", summer))
        assertEquals("GMT-2:30", abbr("America/St_Johns", summer))
        assertEquals("GMT-3:30", abbr("America/St_Johns", winter))
        assertEquals("GMT+0", abbr("UTC", summer))
        assertEquals("GMT+0", abbr("Europe/London", winter))
        assertEquals("GMT+1", abbr("Europe/London", summer))
    }

    @Test
    fun `zone names are GMT offsets with minutes only when there are some`() {
        assertZoneNames()
    }

    @Test
    fun `zone names do not depend on the phone locale`() {
        val saved = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag("ar"))   // Locale("ar"), which formats with Arabic-Indic digits
        try {
            assertZoneNames()
            assertEquals(
                "Invitation for an event starting on Thursday 24 September 2026 at 13:30 (GMT+5:30)",
                subject(event().copy(timezone = "Asia/Kolkata"), "REQUEST")
            )
        } finally {
            Locale.setDefault(saved)
        }
    }

    // ---- the two invitation shapes, whole ----
    //
    // These hold buildIcs: that it renders an EventInfo's SEQUENCE, CREATED, LAST-MODIFIED, PARTSTAT and the
    // two X-PM- lines exactly as given, in one fixed order. They do NOT hold the other half, which is which
    // values the upload path CHOOSES for those fields (CalendarSyncAdapter's sendCreateInvites and
    // sendUpdateInviteEmails); nothing here would fail if a path picked the wrong SEQUENCE. Folding and the
    // trailing CRLF belong to the escaping and folding tests above, because shape() unfolds and drops
    // empty lines.

    /** Every line of an invitation in order, except DTSTAMP, which is the time of the call. */
    private fun shape(ics: String) = lines(ics).filter { it.isNotEmpty() && !it.startsWith("DTSTAMP:") }

    /**
     * An update's invitation, line by line. The two X-PM- lines are what lets a Proton guest open the event
     * from the mail, CREATED and LAST-MODIFIED differ, and SEQUENCE is whatever the EventInfo carries, which
     * RFC 5546 3.2.2.2 requires to be the one Proton holds or Gmail and Outlook ignore the update. That the
     * update path passes the right one is not tested here; see the note above.
     */
    @Test
    fun `the update invitation shape carries the shared event id, the session key and its own created`() {
        val updated = event(
            attendees = listOf(CalendarInviteSender.AttendeeInfo(guest, "Test Guest", "tok", "ACCEPTED"))
        ).copy(
            sharedEventId = "SHARED-1", sessionKey = "c2Vzc2lvbg==", sequence = 3,
            created = 1_790_150_400_000L, lastModified = 1_790_236_800_000L
        )
        assertEquals(
            listOf(
                "BEGIN:VCALENDAR",
                "PRODID:${ICalUtils.PRODID}",
                "VERSION:2.0",
                "METHOD:REQUEST",
                "CALSCALE:GREGORIAN",
                "BEGIN:VEVENT",
                "DTSTART:20260924T080000Z",
                "DTEND:20260924T090000Z",
                "ORGANIZER;CN=organizer:mailto:organizer@example.com",
                "UID:uid-1",
                "ATTENDEE;CN=Test Guest;ROLE=REQ-PARTICIPANT;RSVP=TRUE;PARTSTAT=ACCEPTED" +
                    ";X-PM-TOKEN=tok:mailto:guest@example.com",
                "CREATED:20260923T080000Z",
                "LAST-MODIFIED:20260924T080000Z",
                "SEQUENCE:3",
                "STATUS:CONFIRMED",
                "SUMMARY:SFP E2E",
                "TRANSP:OPAQUE",
                "X-PM-SHARED-EVENT-ID:SHARED-1",
                "X-PM-SESSION-KEY:c2Vzc2lvbg==",
                "END:VEVENT",
                "END:VCALENDAR"
            ),
            shape(CalendarInviteSender.buildIcs(updated, "REQUEST"))
        )
    }

    /**
     * A create's invitation. What distinguishes it from an update is SEQUENCE:0, a guest who has not answered,
     * and CREATED equal to LAST-MODIFIED to the millisecond, NOT the absence of the Proton lines: the create
     * builds its EventInfo after the create response, taking sharedEventId from Response.Event.SharedEventID
     * and the session key from the key packet it just sent, so a real create invitation carries both.
     */
    @Test
    fun `the create invitation shape carries both Proton lines with sequence zero`() {
        val created = event().copy(
            sharedEventId = "SHARED-1", sessionKey = "c2Vzc2lvbg==", sequence = 0,
            created = 1_790_236_800_000L, lastModified = 1_790_236_800_000L
        )
        val ics = CalendarInviteSender.buildIcs(created, "REQUEST")
        assertEquals(
            listOf(
                "BEGIN:VCALENDAR",
                "PRODID:${ICalUtils.PRODID}",
                "VERSION:2.0",
                "METHOD:REQUEST",
                "CALSCALE:GREGORIAN",
                "BEGIN:VEVENT",
                "DTSTART:20260924T080000Z",
                "DTEND:20260924T090000Z",
                "ORGANIZER;CN=organizer:mailto:organizer@example.com",
                "UID:uid-1",
                "ATTENDEE;CN=Test Guest;ROLE=REQ-PARTICIPANT;RSVP=TRUE;PARTSTAT=NEEDS-ACTION" +
                    ";X-PM-TOKEN=tok:mailto:guest@example.com",
                "CREATED:20260924T080000Z",
                "LAST-MODIFIED:20260924T080000Z",
                "SEQUENCE:0",
                "STATUS:CONFIRMED",
                "SUMMARY:SFP E2E",
                "TRANSP:OPAQUE",
                "X-PM-SHARED-EVENT-ID:SHARED-1",
                "X-PM-SESSION-KEY:c2Vzc2lvbg==",
                "END:VEVENT",
                "END:VCALENDAR"
            ),
            shape(ics)
        )
    }

    /** A property of buildIcs, not of the create path: with neither value, neither line is emitted. */
    @Test
    fun `buildIcs omits both Proton lines when the event has neither`() {
        val ics = CalendarInviteSender.buildIcs(event().copy(sharedEventId = null, sessionKey = null), "REQUEST")
        assertTrue(lines(ics).none { it.startsWith("X-PM-") })
    }
}

package me.proton.sync.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [ICalUtils] iCalendar formatting and duration parsing.
 *
 * These use known epoch timestamps to verify RFC 5545 compliant output.
 */
class ICalUtilsTest {

    // ---- PRODID ----

    /** This app, its maker and a plain x.y.z version. */
    @Test
    fun prodId_namesThisAppAndItsVersion() {
        assertEquals("-//BEO//Sync for Proton ${me.proton.sync.BuildConfig.VERSION_NAME}//EN", ICalUtils.PRODID)
        assertTrue(ICalUtils.PRODID, Regex("""-//BEO//Sync for Proton \d+\.\d+\.\d+//EN""").matches(ICalUtils.PRODID))
    }

    // ---- formatTimestamp (UTC) ----

    @Test
    fun formatTimestamp_epoch0() {
        assertEquals("19700101T000000Z", ICalUtils.formatTimestamp(0))
    }

    @Test
    fun formatTimestamp_knownDate() {
        // 2026-03-26 13:00:00 UTC
        assertEquals("20260326T130000Z", ICalUtils.formatTimestamp(1774530000000))
    }

    // ---- formatDate (date-only) ----

    @Test
    fun formatDate_epoch0() {
        assertEquals("19700101", ICalUtils.formatDate(0))
    }

    @Test
    fun formatDate_knownDate() {
        assertEquals("20260326", ICalUtils.formatDate(1774530000000))
    }

    // ---- formatLocalTimestamp (timezone-aware) ----

    @Test
    fun formatLocalTimestamp_utc() {
        assertEquals("20260326T130000", ICalUtils.formatLocalTimestamp(1774530000000, "UTC"))
    }

    @Test
    fun formatLocalTimestamp_berlin() {
        // Berlin is UTC+1 in March (CET)
        assertEquals("20260326T140000", ICalUtils.formatLocalTimestamp(1774530000000, "Europe/Berlin"))
    }

    // ---- parseDurationToMillis ----

    @Test
    fun parseDuration_seconds() {
        assertEquals(3_600_000, ICalUtils.parseDurationToMillis("P3600S"))
    }

    @Test
    fun parseDuration_hours() {
        assertEquals(3_600_000, ICalUtils.parseDurationToMillis("PT1H"))
    }

    @Test
    fun parseDuration_minutes() {
        assertEquals(1_800_000, ICalUtils.parseDurationToMillis("PT30M"))
    }

    @Test
    fun parseDuration_days() {
        assertEquals(86_400_000, ICalUtils.parseDurationToMillis("P1D"))
    }

    @Test
    fun parseDuration_complex() {
        // 1 day + 2 hours + 30 minutes = 95,400,000 ms
        assertEquals(95_400_000, ICalUtils.parseDurationToMillis("P1DT2H30M"))
    }

    @Test
    fun parseDuration_fallbackToOneHour() {
        // Invalid/empty duration should fallback to 1 hour
        assertEquals(3_600_000, ICalUtils.parseDurationToMillis("P"))
        assertEquals(3_600_000, ICalUtils.parseDurationToMillis("PT"))
    }

    // ---- formatTimestampFromSeconds (epoch seconds variant) ----

    @Test
    fun formatTimestampFromSeconds_epoch0() {
        assertEquals("19700101T000000Z", ICalUtils.formatTimestampFromSeconds(0))
    }

    @Test
    fun formatTimestampFromSeconds_knownDate() {
        // 2026-03-26 13:00:00 UTC = 1774530000 seconds
        assertEquals("20260326T130000Z", ICalUtils.formatTimestampFromSeconds(1774530000))
    }

    @Test
    fun formatTimestampFromSeconds_matchesMillisVariant() {
        // Same timestamp: 1774530000 seconds == 1774530000000 millis
        val fromSeconds = ICalUtils.formatTimestampFromSeconds(1774530000)
        val fromMillis = ICalUtils.formatTimestamp(1774530000000)
        assertEquals(fromMillis, fromSeconds)
    }

    // ---- normalizeTimezone ----

    @Test
    fun normalizeTimezone_passesThroughIana() {
        assertEquals("Europe/Berlin", ICalUtils.normalizeTimezone("Europe/Berlin"))
        assertEquals("America/New_York", ICalUtils.normalizeTimezone("America/New_York"))
    }

    @Test
    fun normalizeTimezone_utcUnchanged() {
        assertEquals("UTC", ICalUtils.normalizeTimezone("UTC"))
    }

    @Test
    fun normalizeTimezone_emptyToUtc() {
        assertEquals("UTC", ICalUtils.normalizeTimezone(""))
        assertEquals("UTC", ICalUtils.normalizeTimezone("   "))
    }

    @Test
    fun normalizeTimezone_gmtOffsetsToUtc() {
        assertEquals("UTC", ICalUtils.normalizeTimezone("GMT"))
        assertEquals("UTC", ICalUtils.normalizeTimezone("GMT+02:00"))
        assertEquals("UTC", ICalUtils.normalizeTimezone("GMT-03:00"))
        assertEquals("UTC", ICalUtils.normalizeTimezone("GMT+5"))
    }

    @Test
    fun normalizeTimezone_etcUtcFamilyToUtc() {
        // Proton rejects Etc/UTC and Etc/GMT family with code=2001
        assertEquals("UTC", ICalUtils.normalizeTimezone("Etc/UTC"))
        assertEquals("UTC", ICalUtils.normalizeTimezone("Etc/GMT"))
        assertEquals("UTC", ICalUtils.normalizeTimezone("Etc/GMT+5"))
        assertEquals("UTC", ICalUtils.normalizeTimezone("Etc/GMT-3"))
        assertEquals("UTC", ICalUtils.normalizeTimezone("Etc/Universal"))
        assertEquals("UTC", ICalUtils.normalizeTimezone("Etc/Zulu"))
        assertEquals("UTC", ICalUtils.normalizeTimezone("UCT"))
        assertEquals("UTC", ICalUtils.normalizeTimezone("Zulu"))
    }

    // ---- Proton timezone remapping (ProtonTimezones tables) ----
    // NOTE: deprecated-alias canonicalization (e.g. Europe/Belfast → Europe/London) needs
    // Android's ICU, which isn't available in plain JVM tests — that path is verified on
    // device. These cover the table lookups, which work regardless of ICU.

    @Test
    fun normalizeTimezone_supportedZonePassesThrough() {
        assertEquals("Europe/London", ICalUtils.normalizeTimezone("Europe/London"))
        assertEquals("America/Argentina/Buenos_Aires", ICalUtils.normalizeTimezone("America/Argentina/Buenos_Aires"))
    }

    // ---- foldLine ----

    private fun unfold(folded: String) = folded.replace("\r\n ", "")
    private fun octets(s: String) = s.toByteArray(Charsets.UTF_8).size

    @Test
    fun foldLine_shortLineUnchanged() {
        val line = "SUMMARY:" + "a".repeat(67)
        assertEquals(75, octets(line))
        assertEquals(line, ICalUtils.foldLine(line))
    }

    @Test
    fun foldLine_asciiFoldsAt75Then74() {
        val line = "DESCRIPTION:" + "x".repeat(200)
        val physical = ICalUtils.foldLine(line).split("\r\n")
        assertEquals(75, physical[0].length)
        assertEquals(" ", physical[1].take(1))
        assertEquals(75, physical[1].length)
        assertEquals(line, unfold(ICalUtils.foldLine(line)))
    }

    /** The decryptor's old fold cut here and decoded both halves of the "ü" as U+FFFD. */
    @Test
    fun foldLine_neverSplitsAMultiByteCharacter() {
        val line = "DESCRIPTION:" + "a".repeat(62) + "ü" + "b".repeat(40)
        assertEquals(74, octets("DESCRIPTION:" + "a".repeat(62)))
        val folded = ICalUtils.foldLine(line)
        assertEquals(line, unfold(folded))
        assertEquals(74, octets(folded.split("\r\n")[0]))
        assertEquals(false, folded.contains('�'))
    }

    @Test
    fun foldLine_neverSplitsASurrogatePair() {
        val line = "DESCRIPTION:" + "a".repeat(61) + "😀" + "b".repeat(40)
        val folded = ICalUtils.foldLine(line)
        assertEquals(line, unfold(folded))
        assertEquals(73, octets(folded.split("\r\n")[0]))
    }

    @Test
    fun foldLine_everyPhysicalLineFitsIn75Octets() {
        val text = "Grüße aus Köln, Привет мир, 東京で会いましょう 😀 ".repeat(12)
        val line = "DESCRIPTION:$text"
        val folded = ICalUtils.foldLine(line)
        for (physical in folded.split("\r\n")) assertEquals(true, octets(physical) <= 75)
        assertEquals(line, unfold(folded))
    }

    @Test
    fun foldLine_roundTripsThroughIcal4j() {
        val text = List(8) { "Grüße aus Köln. Привет мир. 東京で会いましょう 😀" }.joinToString(" / ")
        val ical = listOf(
            "BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//test//EN", "BEGIN:VEVENT", "UID:u1",
            "DTSTAMP:20260101T000000Z", "DTSTART:20260101T100000Z",
            ICalUtils.foldLine("DESCRIPTION:$text"),
            "END:VEVENT", "END:VCALENDAR"
        ).joinToString("\r\n", postfix = "\r\n")
        val event = net.fortuna.ical4j.data.CalendarBuilder().build(java.io.StringReader(ical))
            .getComponents<net.fortuna.ical4j.model.component.VEvent>("VEVENT").first()
        assertEquals(text, event.description.value)
    }

    @Test
    fun normalizeTimezone_unsupportedZoneRedirectsToProtonZone() {
        // Zones Proton's API rejects → the supported zone from unsupportedTimezoneLinks
        assertEquals("Asia/Shanghai", ICalUtils.normalizeTimezone("Asia/Singapore"))
        assertEquals("Europe/Paris", ICalUtils.normalizeTimezone("CET"))
        assertEquals("America/New_York", ICalUtils.normalizeTimezone("America/Fort_Wayne"))
        assertEquals("Europe/Kiev", ICalUtils.normalizeTimezone("Europe/Kyiv"))
    }

    @Test
    fun normalizeTimezone_mstIsArizonaTimeNotParis() {
        // Proton's own list maps MST to Europe/Paris; an MST event would then be labelled Paris time
        // and its repeats would follow Paris summer time.
        assertEquals("America/Phoenix", ICalUtils.normalizeTimezone("MST"))
        assertEquals("Europe/Paris", ICalUtils.normalizeTimezone("MET"))
    }

    // ---- structuralLines: the baseline a mapping row caches (one derivation, two writers) ----

    @Test
    fun structuralLines_takesTheDtstartDtendAndRruleFromAPropertyOnlyBlock() {
        val lines = ICalUtils.structuralLines(
            "DTSTART;TZID=Europe/Berlin:20260924T143000\r\nDTEND;TZID=Europe/Berlin:20260924T153000\r\n",
            " FREQ=WEEKLY;BYDAY=TH "
        )
        assertEquals("DTSTART;TZID=Europe/Berlin:20260924T143000", lines.dtStart)
        assertEquals("DTEND;TZID=Europe/Berlin:20260924T153000", lines.dtEnd)
        assertEquals("FREQ=WEEKLY;BYDAY=TH", lines.rrule)
    }

    @Test
    fun structuralLines_allDayEventStrippedOfItsDtendHasNeitherEndNorRule() {
        val lines = ICalUtils.structuralLines("DTSTART;VALUE=DATE:20260924\r\n", null)
        assertEquals("DTSTART;VALUE=DATE:20260924", lines.dtStart)
        assertEquals(null, lines.dtEnd)
        assertEquals(null, lines.rrule)
    }

    /**
     * An empty rule stays empty. decideUpdateSequence asks (serverRrule ?: "") != (currentRrule ?: ""),
     * so "" turning into null here would look like a structural change on every single-occurrence
     * event, bump SEQUENCE and make every guest answer the invitation again.
     */
    @Test
    fun structuralLines_anEmptyRuleStaysEmptyAndDoesNotBecomeNull() {
        val lines = ICalUtils.structuralLines("DTSTART:20260924T080000Z\r\nDTEND:20260924T090000Z\r\n", "")
        assertEquals("DTSTART:20260924T080000Z", lines.dtStart)
        assertEquals("DTEND:20260924T090000Z", lines.dtEnd)
        assertEquals("", lines.rrule)
    }

    /**
     * The agreement the update path rests on and nothing tested until now: the server form and the
     * local form yield the same three values for the same event. decideUpdateSequence compares them
     * directly, so if the "RRULE:" strip were ever dropped the two sides could never match again.
     */
    @Test
    fun type2StructuralLines_agreesWithTheLocalFormForTheSameEvent() {
        val server = ICalUtils.type2StructuralLines(
            "BEGIN:VEVENT\r\nDTSTAMP:20260924T080000Z\r\n" +
                "DTSTART;TZID=Europe/Berlin:20260924T143000\r\nDTEND;TZID=Europe/Berlin:20260924T153000\r\n" +
                "RRULE:FREQ=WEEKLY;BYDAY=TH\r\nEND:VEVENT\r\n"
        )
        val local = ICalUtils.structuralLines(
            "DTSTART;TZID=Europe/Berlin:20260924T143000\r\nDTEND;TZID=Europe/Berlin:20260924T153000\r\n",
            "FREQ=WEEKLY;BYDAY=TH"
        )
        assertEquals(local, server)
        assertEquals("DTSTART;TZID=Europe/Berlin:20260924T143000", server.dtStart)
        assertEquals("DTEND;TZID=Europe/Berlin:20260924T153000", server.dtEnd)
        assertEquals("FREQ=WEEKLY;BYDAY=TH", server.rrule)
    }

    /**
     * What the colon in the RRULE filter is really for. Not RECURRENCE-ID, which a single-occurrence override
     * carries: that is kept out by the property name, R-E-C against R-R-U, so this test passed with or without
     * the colon before. What the colon rejects is a bare "RRULE" line, which would otherwise survive
     * removePrefix and come back as an EMPTY rule, and an empty rule is not the same as no rule to
     * decideUpdateSequence: one reads as a change against a null server rule, the other does not.
     */
    @Test
    fun type2StructuralLines_neitherARecurrenceIdNorABareRruleNameIsARule() {
        val override = ICalUtils.type2StructuralLines(
            "DTSTAMP:20260924T080000Z\r\nRECURRENCE-ID;TZID=Europe/Berlin:20260924T143000\r\nRRULE\r\n" +
                "DTSTART;TZID=Europe/Berlin:20260924T150000\r\n"
        )
        assertEquals(null, override.rrule)
        assertEquals("DTSTART;TZID=Europe/Berlin:20260924T150000", override.dtStart)
        assertEquals(null, override.dtEnd)
    }

    // ---- the RECURRENCE-ID line, shared by the upload and the cancellation retry ----

    @Test
    fun recurrenceIdLine_hasOneShapePerKindOfOccurrence() {
        assertEquals(
            "RECURRENCE-ID;VALUE=DATE:20260924\r\n",
            ICalUtils.recurrenceIdLine(1_790_236_800_000L, allDay = true, timezone = "Europe/Berlin")
        )
        assertEquals(
            "RECURRENCE-ID:20260924T080000Z\r\n",
            ICalUtils.recurrenceIdLine(1_790_236_800_000L, allDay = false, timezone = "UTC")
        )
        assertEquals(
            "RECURRENCE-ID;TZID=Europe/Berlin:20260924T100000\r\n",
            ICalUtils.recurrenceIdLine(1_790_236_800_000L, allDay = false, timezone = "Europe/Berlin")
        )
    }

    /**
     * The retry guard rebuilds this line for a candidate row and compares it with the queued one, so two
     * different occurrences of the same series must never produce the same line.
     */
    @Test
    fun recurrenceIdLine_distinguishesTwoOccurrencesOfTheSameSeries() {
        val first = ICalUtils.recurrenceIdLine(1_790_236_800_000L, allDay = false, timezone = "Europe/Berlin")
        val week = ICalUtils.recurrenceIdLine(1_790_841_600_000L, allDay = false, timezone = "Europe/Berlin")
        assertEquals(false, first == week)
    }
}

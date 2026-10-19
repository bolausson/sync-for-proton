package me.proton.sync.backup

import net.fortuna.ical4j.data.CalendarBuilder
import net.fortuna.ical4j.model.component.VEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.StringReader

/**
 * A backup that names a timezone it does not define is not a backup.
 *
 * The exporter used to copy only the VEVENTs into a fresh calendar, so every VTIMEZONE that came
 * with them was dropped. The resulting file carried `DTSTART;TZID=Europe/Amsterdam` with nothing
 * anywhere defining `Europe/Amsterdam`: invalid per RFC 5545 and refused outright by a fair number
 * of importers. It looked perfectly healthy until the day somebody needed to restore it.
 *
 * The second test below is the quieter half, and the reason these run against real ical4j instead
 * of a description of it. Left to resolve an id itself, ical4j answers from its bundled registry,
 * which maps zones onto whichever definition shares their rules. The user's Amsterdam comes back
 * as Brussels. The event stays at the correct instant so nothing looks wrong, and the zone has been
 * renamed inside their own backup.
 */
class IcsSerializerTest {

    @Before
    fun relaxLikeTheApp() {
        // ProtonSyncApp sets these three before any parsing happens; without them the test would
        // be exercising a stricter ical4j than the app ever runs.
        System.setProperty("ical4j.parsing.relaxed", "true")
        System.setProperty("ical4j.unfolding.relaxed", "true")
        System.setProperty("ical4j.compatibility.outlook", "true")
    }

    private val amsterdamVTimezone = """
        BEGIN:VTIMEZONE
        TZID:Europe/Amsterdam
        BEGIN:DAYLIGHT
        TZOFFSETFROM:+0100
        TZOFFSETTO:+0200
        TZNAME:CEST
        DTSTART:19700329T020000
        RRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=-1SU
        END:DAYLIGHT
        BEGIN:STANDARD
        TZOFFSETFROM:+0200
        TZOFFSETTO:+0100
        TZNAME:CET
        DTSTART:19701025T030000
        RRULE:FREQ=YEARLY;BYMONTH=10;BYDAY=-1SU
        END:STANDARD
        END:VTIMEZONE
    """.trimIndent()

    private fun source(withZone: Boolean) = buildString {
        append("BEGIN:VCALENDAR\r\n")
        append("VERSION:2.0\r\n")
        append("PRODID:-//test//test//EN\r\n")
        if (withZone) append(amsterdamVTimezone.replace("\n", "\r\n")).append("\r\n")
        append("BEGIN:VEVENT\r\n")
        append("UID:evt-1\r\n")
        append("DTSTAMP:20260821T180000Z\r\n")
        append("DTSTART;TZID=Europe/Amsterdam:20260821T200000\r\n")
        append("DTEND;TZID=Europe/Amsterdam:20260821T210000\r\n")
        append("SUMMARY:Borrel\r\n")
        append("END:VEVENT\r\n")
        append("END:VCALENDAR\r\n")
    }

    private fun parse(ics: String) = IcsSerializer.parseFaithfully(ics)

    private fun roundTrip(withZone: Boolean): String {
        val parsed = parse(source(withZone))
        val events = parsed.getComponents<VEvent>("VEVENT").toList()
        return IcsSerializer.serialize("-//test//test//EN", "Prive", events, IcsSerializer.zonesOf(parsed))
    }

    @Test
    fun `the source timezone definition travels with its events`() {
        val out = roundTrip(withZone = true)
        assertTrue("the file must define the zone it names", out.contains("BEGIN:VTIMEZONE"))
        assertTrue("and define exactly the zone the events reference", out.contains("TZID:Europe/Amsterdam"))
        assertTrue(out.contains("DTSTART;TZID=Europe/Amsterdam"))
        assertFalse("the user's zone must not be renamed", out.contains("Europe/Brussels"))
    }

    /**
     * The case that matters, because it is the only one Proton produces: its ICS wrapping is
     * VCALENDAR / VERSION / PRODID / VEVENT and carries no VTIMEZONE at all.
     *
     * The app deliberately does not invent one. Three attempts did, and all three shipped wrong
     * rules - ical4j's zoneinfo frozen at 2023, then the platform's rules anchored at 2020, then
     * the platform's full history, which was itself four years stale on the Galaxy Note 10 it was
     * tested on (tzdata2021a). A phone has no authoritative copy of the world's timezone rules,
     * so the file states the zone's NAME and leaves the rules to the importer, whose database is
     * far likelier to be current.
     *
     * What must survive is the user's own zone id: ical4j renames Europe/Amsterdam to
     * Europe/Brussels through its alias table unless parseFaithfully prevents it.
     */
    @Test
    fun `a zone the source never defined keeps its name and no rules are invented`() {
        val out = roundTrip(withZone = false)
        assertTrue("the user's zone must survive", out.contains("DTSTART;TZID=Europe/Amsterdam"))
        assertFalse("the alias must not leak into the user's backup", out.contains("Brussels"))
        assertFalse(
            "no definition may be invented from this device's idea of the rules",
            out.contains("BEGIN:VTIMEZONE")
        )
    }

    /**
     * The known, accepted cost of the above. RFC 5545 says a file should define the zones it names,
     * and this one does not when the source supplied nothing.
     *
     * It is a deliberate trade, made after measuring both sides. Shipping stale rules moved real
     * appointments by an hour, silently, and was verified to do so; the validity concern is real
     * but has never once been observed to bite - Proton's own import accepts such a file, checked
     * twice against a live account. A file rejected loudly beats a file accepted and misread.
     *
     * This test exists so the trade is visible rather than looking like an oversight, and so that
     * anyone who reverses it has to delete a test that says why.
     */
    @Test
    fun `a file with no source definition names a zone it does not define, on purpose`() {
        val out = roundTrip(withZone = false)
        assertTrue(out.contains("TZID=Europe/Amsterdam"))
        assertFalse(out.contains("BEGIN:VTIMEZONE"))
    }

    /**
     * Renaming a definition is only safe because ical4j aliases zones whose observances match. If
     * that ever stops holding, this catches it: the moment the event happens must not move.
     */
    @Test
    fun `relabelling a zone does not move the event`() {
        for (withZone in listOf(true, false)) {
            val source = parse(source(withZone)).getComponents<VEvent>("VEVENT").first()
            val out = parse(roundTrip(withZone)).getComponents<VEvent>("VEVENT").first()
            assertEquals(
                "the instant must be identical (withZone=$withZone)",
                source.startDate.date.time, out.startDate.date.time
            )
        }
    }

    /**
     * When the source DID supply a definition, it must still be carried and must still match what
     * the events reference. That half of the old rule survives; the half that required inventing a
     * definition when the source supplied none does not, for the reasons above.
     */
    @Test
    fun `a source-supplied definition matches what the events reference`() {
        val out = roundTrip(withZone = true)
        for (id in referencedTzIds(out)) {
            assertTrue("$id referenced but not defined", out.contains("TZID:$id"))
        }
    }

    /** And nothing is added for events that need nothing: a UTC-only calendar stays clean. */
    @Test
    fun `a calendar with no zone references carries no VTIMEZONE`() {
        val ics = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//test//test//EN\r\n" +
            "BEGIN:VEVENT\r\nUID:evt-2\r\nDTSTAMP:20260821T180000Z\r\n" +
            "DTSTART:20260821T180000Z\r\nDTEND:20260821T190000Z\r\nSUMMARY:Call\r\n" +
            "END:VEVENT\r\nEND:VCALENDAR\r\n"
        val parsed = parse(ics)
        val out = IcsSerializer.serialize(
            "-//test//test//EN", "Prive",
            parsed.getComponents<VEvent>("VEVENT").toList(), IcsSerializer.zonesOf(parsed)
        )
        assertFalse("nothing references a zone, so nothing should define one", out.contains("BEGIN:VTIMEZONE"))
        assertTrue(out.contains("UID:evt-2"))
    }

    @Test
    fun `zonesOf reads the definitions the source supplied`() {
        assertEquals(setOf("Europe/Amsterdam"), IcsSerializer.zonesOf(parse(source(true))).keys)
        assertTrue(IcsSerializer.zonesOf(parse(source(false))).isEmpty())
    }

    /**
     * Every event in every backup this app ever wrote was malformed, and nothing noticed.
     *
     * Proton splits an event's iCal across content parts and each part repeats the identifying
     * properties, so merging them produced a VEVENT carrying UID four times and DTSTAMP three
     * times. RFC 5545 section 3.6.1 says those MUST NOT occur more than once. The sync path never
     * saw it, because it reads single values out to the Android provider; only the backup writes
     * the merged component back out. It surfaced by comparing a backup taken before a restore
     * against one taken after: Proton's own importer had silently dropped ORGANIZER, CREATED and
     * STATUS from what it was handed, which is what a lenient parser does with a component it
     * cannot reconcile. A stricter one may reject the file outright, and a backup that cannot be
     * read back is not a backup.
     */
    @Test
    fun `properties that may appear only once appear only once`() {
        val ics = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//t//t//EN\r\n" +
            "BEGIN:VEVENT\r\n" +
            "UID:evt-1\r\nUID:evt-1\r\nDTSTAMP:20260826T075619Z\r\n" +
            "DTSTART;TZID=Europe/Amsterdam:20260821T200000\r\n" +
            "UID:evt-1\r\nDTSTAMP:20260826T075619Z\r\nSUMMARY:Borrel\r\n" +
            "ATTENDEE:mailto:a@example.com\r\nATTENDEE:mailto:b@example.com\r\n" +
            "END:VEVENT\r\nEND:VCALENDAR\r\n"
        val parsed = parse(ics)
        val out = IcsSerializer.serialize(
            "-//test//test//EN", "Prive",
            parsed.getComponents<VEvent>("VEVENT").toList(), IcsSerializer.zonesOf(parsed)
        )
        assertEquals("UID must appear exactly once", 1, Regex("(?m)^UID:").findAll(out).count())
        assertEquals("DTSTAMP must appear exactly once", 1, Regex("(?m)^DTSTAMP:").findAll(out).count())
        assertTrue("the event itself must survive", out.contains("SUMMARY:Borrel"))
        // The half that must NOT be touched: properties that legitimately repeat.
        assertEquals("both attendees must survive", 2, Regex("(?m)^ATTENDEE").findAll(out).count())
    }

    /**
     * An identical property line twice says nothing the first one did not. Proton's content-part
     * merge produced two byte-identical EXDATEs on a recurring event - the same excluded occurrence
     * listed twice - where Proton's own export writes one.
     *
     * The second half is the one that must keep working: two EXDATEs for DIFFERENT dates are two
     * real exclusions and both have to survive.
     */
    @Test
    fun `an exact duplicate is dropped, a different value is kept`() {
        val ics = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//t//t//EN\r\n" +
            "BEGIN:VEVENT\r\nUID:e1\r\nDTSTAMP:20260101T000000Z\r\n" +
            "DTSTART;TZID=Europe/Berlin:20260827T083000\r\nRRULE:FREQ=WEEKLY\r\n" +
            "EXDATE;TZID=Europe/Berlin:20260903T083000\r\n" +
            "EXDATE;TZID=Europe/Berlin:20260903T083000\r\n" +
            "EXDATE;TZID=Europe/Berlin:20260910T083000\r\n" +
            "SUMMARY:Weekly\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
        val parsed = parse(ics)
        val out = IcsSerializer.serialize(
            "-//t//t//EN", "cal", parsed.getComponents<VEvent>("VEVENT").toList(), IcsSerializer.zonesOf(parsed)
        )
        assertEquals("the repeat of 0903 must go", 1, Regex("20260903T083000").findAll(out).count())
        assertEquals("but 0910 is a different exclusion", 1, Regex("20260910T083000").findAll(out).count())
        assertTrue(out.contains("RRULE:FREQ=WEEKLY"))
    }

    /**
     * A backup names this app without its version, so unchanged events give the same file whichever
     * release wrote it: Proton Mail files a one-event .ics without METHOD under a hash of its bytes.
     */
    @Test
    fun `a backup's PRODID names this app without a version`() {
        assertEquals("-//BEO//Sync for Proton//EN", CalendarBackupBuilder.PRODID)
        val ics = IcsSerializer.serialize(CalendarBackupBuilder.PRODID, "cal", emptyList(), emptyMap())
        assertTrue(ics, ics.lines().contains("PRODID:-//BEO//Sync for Proton//EN"))
    }

    /** Every TZID= parameter appearing in a serialised file. */
    private fun referencedTzIds(ics: String): Set<String> =
        Regex("TZID=([^:;\r\n]+)").findAll(ics).map { it.groupValues[1] }.toSet()
}

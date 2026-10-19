package me.proton.sync.backup

import net.fortuna.ical4j.model.component.VEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A backup must not assert timezone rules it cannot possibly know are current.
 *
 * Three attempts were made at writing a VTIMEZONE into the exported .ics and all three shipped
 * wrong rules:
 *
 *  1. ical4j's bundled zoneinfo, frozen at 2023-11-02, which still gives Paraguay the DST it
 *     abolished in October 2024 and Kazakhstan the +0600 it left in March 2024.
 *  2. Only the platform's RECURRING rules, anchored at 2020, so every date before then fell back to
 *     the raw standard offset - Europe/Berlin in July 2015 restored an hour out.
 *  3. The platform's full history - which looked right against a JVM carrying tzdb 2025b, and is
 *     wrong on the actual test device: a Note 10 on Android 12 carries tzdata2021a, four years
 *     stale, and gets those same zones wrong.
 *
 * There is no source of truth for this on a phone. Whatever the app emits is a snapshot of what
 * that particular device happens to know, and it can be years behind. So it emits nothing, the
 * importer resolves the TZID against its own database, and the times come out right.
 *
 * What the app DOES owe the user is their own zone id: ical4j renames Europe/Amsterdam to
 * Europe/Brussels when it resolves a TZID through its alias table, and parseFaithfully prevents
 * that. The name is ours to preserve; the rules are not ours to assert.
 */
class TimezoneFidelityTest {

    @Before
    fun relaxLikeTheApp() {
        System.setProperty("ical4j.parsing.relaxed", "true")
        System.setProperty("ical4j.unfolding.relaxed", "true")
        System.setProperty("ical4j.compatibility.outlook", "true")
    }

    private fun roundTrip(tzid: String, withSourceZone: String? = null): String {
        val src = buildString {
            append("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//t//t//EN\r\n")
            if (withSourceZone != null) append(withSourceZone)
            append("BEGIN:VEVENT\r\nUID:e1\r\nDTSTAMP:20260101T000000Z\r\n")
            append("DTSTART;TZID=$tzid:20260615T120000\r\nSUMMARY:Borrel\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n")
        }
        val parsed = IcsSerializer.parseFaithfully(src)
        return IcsSerializer.serialize(
            "-//t//t//EN", "cal", parsed.getComponents<VEvent>("VEVENT").toList(), IcsSerializer.zonesOf(parsed)
        )
    }

    /**
     * The zone the user's calendar actually uses survives. Without parseFaithfully, ical4j resolves
     * Europe/Amsterdam through tz.alias and the event comes back out as Europe/Brussels - the same
     * instant, somebody else's city.
     */
    @Test
    fun `an aliased zone keeps the user's own id`() {
        val out = roundTrip("Europe/Amsterdam")
        assertTrue("the user's zone must survive", out.contains("DTSTART;TZID=Europe/Amsterdam"))
        assertFalse("ical4j's alias must not leak into the backup", out.contains("Brussels"))
    }

    @Test
    fun `an ordinary zone is written unchanged`() {
        assertTrue(roundTrip("Europe/Berlin").contains("DTSTART;TZID=Europe/Berlin"))
    }

    /**
     * The heart of it: no invented definition, whatever the phone believes. A device four years
     * behind must not stamp its beliefs into a file that outlives it.
     */
    @Test
    fun `no timezone definition is invented`() {
        for (tzid in listOf("Europe/Berlin", "Europe/Amsterdam", "America/Asuncion", "Asia/Almaty")) {
            val out = roundTrip(tzid)
            assertFalse(
                "$tzid: the backup must not assert rules this device cannot know are current",
                out.contains("BEGIN:VTIMEZONE")
            )
            assertTrue(out.contains("DTSTART;TZID=$tzid"))
        }
    }

    /** But a definition the SOURCE supplied is the source's to keep, and still travels. */
    @Test
    fun `a definition the source supplied is kept`() {
        val supplied = "BEGIN:VTIMEZONE\r\nTZID:Europe/Berlin\r\n" +
            "BEGIN:STANDARD\r\nTZOFFSETFROM:+0200\r\nTZOFFSETTO:+0100\r\nTZNAME:CET\r\n" +
            "DTSTART:19701025T030000\r\nRRULE:FREQ=YEARLY;BYMONTH=10;BYDAY=-1SU\r\nEND:STANDARD\r\n" +
            "END:VTIMEZONE\r\n"
        val out = roundTrip("Europe/Berlin", withSourceZone = supplied)
        assertEquals(
            "the source's own definition should be carried through exactly once",
            1, Regex("(?m)^BEGIN:VTIMEZONE").findAll(out).count()
        )
        assertTrue(out.contains("TZID:Europe/Berlin"))
    }
}

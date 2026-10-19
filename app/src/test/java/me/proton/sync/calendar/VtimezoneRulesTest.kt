package me.proton.sync.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.zone.ZoneRules

/**
 * The "ON" interpreter against tzurl.org's own files (test resources, Olson 2026d, fetched 2026-10-04).
 *
 * Two kinds of expectation. Rules that changed after the test JVM's tzdb (2025b in JDK 21.0.10) are pinned to the
 * instants IANA 2026d gives (Vancouver and Edmonton from November 2026). Everything older is compared with
 * the JVM's own rules, probe by probe, which an independent compiler (OpenJDK's) built from IANA's source.
 */
class VtimezoneRulesTest {

    private fun text(name: String) = javaClass.getResource("/tzurl/$name.ics")!!.readText()
    private fun build(name: String) = VtimezoneRules.build(text(name))
    private fun at(rules: ZoneRules, local: String): Instant =
        LocalTimes.toInstant(LocalDateTime.parse(local, IcalTimeValues.LOCAL_FORMAT), rules)

    @Test
    fun `Vancouver 2026d - permanent -07 from November 2026, where ical4j put events nine hours early`() {
        val r = build("America_Vancouver").rules
        assertEquals(Instant.parse("2026-11-15T17:00:00Z"), at(r, "20261115T100000"))
        assertEquals(Instant.parse("2026-12-01T17:00:00Z"), at(r, "20261201T100000"))
        assertEquals(Instant.parse("2027-07-15T17:00:00Z"), at(r, "20270715T100000"))
        assertEquals(Instant.parse("2026-07-15T17:00:00Z"), at(r, "20260715T100000"))   // PDT, summer 2026
        assertEquals(Instant.parse("2025-12-15T18:00:00Z"), at(r, "20251215T100000"))   // PST, winter 2025
    }

    @Test
    fun `Edmonton 2026d - permanent -06 from November 2026`() {
        assertEquals(Instant.parse("2026-12-15T16:00:00Z"), at(build("America_Edmonton").rules, "20261215T100000"))
    }

    @Test
    fun `Cairo, Almaty, Asuncion and Scoresbysund - the zones the bundled 2022g data got wrong`() {
        assertEquals(Instant.parse("2027-07-15T07:00:00Z"), at(build("Africa_Cairo").rules, "20270715T100000"))
        for ((file, zone) in listOf("Asia_Almaty" to "Asia/Almaty", "America_Asuncion" to "America/Asuncion",
            "America_Scoresbysund" to "America/Scoresbysund")) {
            val r = build(file).rules
            for (year in 2023..2025) for (month in 1..12) {
                val local = "%04d%02d15T100000".format(year, month)
                assertEquals("$zone $local", at(ZoneId.of(zone).rules, local), at(r, local))
            }
        }
    }

    /** Offsets every three hours from 1970 to the end of 2024 equal the JVM's own rules (2025b in JDK 21.0.10). */
    @Test
    fun `every fixture matches the JVM's tzdb from 1970 to 2024, except tzurl's own Istanbul error`() {
        val files = listOf(
            "America_Vancouver" to "America/Vancouver", "America_Edmonton" to "America/Edmonton",
            "Africa_Cairo" to "Africa/Cairo", "America_Asuncion" to "America/Asuncion",
            "America_Scoresbysund" to "America/Scoresbysund", "Asia_Almaty" to "Asia/Almaty",
            "Asia_Qostanay" to "Asia/Qostanay", "Australia_Darwin" to "Australia/Darwin",
            "Pacific_Fiji" to "Pacific/Fiji", "Australia_Lord_Howe" to "Australia/Lord_Howe",
            "Antarctica_Troll" to "Antarctica/Troll", "Antarctica_Casey" to "Antarctica/Casey",
            "Pacific_Efate" to "Pacific/Efate", "Europe_Berlin" to "Europe/Berlin",
            "Asia_Kathmandu" to "Asia/Kathmandu", "Pacific_Chatham" to "Pacific/Chatham",
            "Africa_Casablanca" to "Africa/Casablanca", "America_Winnipeg" to "America/Winnipeg",
            "Asia_Gaza" to "Asia/Gaza", "America_New_York" to "America/New_York", "Asia_Kolkata" to "Asia/Kolkata",
        )
        val start = Instant.parse("1970-01-01T00:00:00Z").epochSecond
        val end = Instant.parse("2025-01-01T00:00:00Z").epochSecond
        for ((file, zone) in files) {
            val ours = build(file).rules
            val jvm = ZoneId.of(zone).rules
            var t = start
            while (t < end) {
                val i = Instant.ofEpochSecond(t)
                if (ours.getOffset(i) != jvm.getOffset(i)) fail("$zone differs at $i: ${ours.getOffset(i)} vs ${jvm.getOffset(i)}")
                t += 3 * 3600
            }
        }
    }

    @Test
    fun `Istanbul 1974 is tzurl's own error, reproduced exactly as written`() {
        // vzic wrote Turkey's "Oct Sun>=31" as BYYEARDAY=-61..-67, a week too early: tzurl ends DST on
        // 1974-10-27, IANA on 1974-11-03. A correct reader of tzurl's text gives tzurl's date.
        val probe = Instant.parse("1974-10-30T12:00:00Z")
        assertEquals(ZoneOffset.ofHours(2), build("Europe_Istanbul").rules.getOffset(probe))
        assertEquals(ZoneOffset.ofHours(3), ZoneId.of("Europe/Istanbul").rules.getOffset(probe))
    }

    @Test
    fun `local times resolve like ZonedDateTime ofLocal around every Berlin transition 2000 to 2030`() {
        val ours = build("Europe_Berlin").rules
        val zone = ZoneId.of("Europe/Berlin")
        var checked = 0
        var tr = zone.rules.nextTransition(Instant.parse("2000-01-01T00:00:00Z"))
        while (tr != null && tr.instant.isBefore(Instant.parse("2031-01-01T00:00:00Z"))) {
            var local = tr.dateTimeBefore.minusHours(3)
            val stop = tr.dateTimeBefore.plusHours(3)
            while (!local.isAfter(stop)) {
                assertEquals("$local", ZonedDateTime.ofLocal(local, zone, null).toInstant(), LocalTimes.toInstant(local, ours))
                local = local.plusMinutes(15)
                checked++
            }
            tr = zone.rules.nextTransition(tr.instant)
        }
        assertEquals(true, checked > 1000)
    }

    @Test
    fun `the release is read from PRODID, and a link answers with its target's TZID`() {
        assertEquals("2026d", build("America_Vancouver").olsonVersion)
        assertEquals("Europe/Berlin", build("Europe_Oslo").tzid)
    }

    @Test
    fun `CRLF, LF and folding every line give the same rules`() {
        val lf = text("Pacific_Chatham").replace("\r\n", "\n")
        val crlf = lf.replace("\n", "\r\n")
        val folded = lf.lines().joinToString("\r\n") { line ->
            if (line.length <= 10) line else line.chunked(10).joinToString("\r\n ")
        }
        val a = VtimezoneRules.build(lf).rules
        assertEquals(a, VtimezoneRules.build(crlf).rules)
        assertEquals(a, VtimezoneRules.build(folded).rules)
    }

    private fun zone(vararg observances: String) = (
        listOf("BEGIN:VCALENDAR", "PRODID:-//tzurl.org//NONSGML Olson 2026d//EN", "BEGIN:VTIMEZONE", "TZID:Test/Zone") +
            observances + listOf("END:VTIMEZONE", "END:VCALENDAR")
        ).joinToString("\r\n")

    private fun standard(from: String, to: String, start: String, vararg extra: String) =
        (listOf("BEGIN:STANDARD", "TZOFFSETFROM:$from", "TZOFFSETTO:$to", "DTSTART:$start") + extra + "END:STANDARD").joinToString("\r\n")

    private fun refused(name: String, text: String) {
        try {
            VtimezoneRules.build(text)
            fail("$name was accepted")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun `bounded before anything is expanded - an answer cannot make the reader allocate without end`() {
        // The helpers make acceptable text.
        assertEquals(0, VtimezoneRules.build(zone(standard("+0100", "+0100", "19000101T000000"))).transitionCount)
        refused("a DTSTART before 1800", zone(standard("+0100", "+0100", "17990101T000000")))
        refused("an RDATE before 1800", zone(standard("+0100", "+0100", "19000101T000000", "RDATE:17000101T000000")))
        refused("two BYMONTH", zone(standard("+0100", "+0200", "19800330T020000", "RRULE:FREQ=YEARLY;BYMONTH=3,4;BYDAY=-1SU")))
        refused("eight BYMONTHDAY", zone(standard("+0100", "+0200", "19800330T020000",
            "RRULE:FREQ=YEARLY;BYMONTH=3;BYMONTHDAY=8,9,10,11,12,13,14,15;BYDAY=SU")))
        refused("eight BYYEARDAY", zone(standard("+0100", "+0200", "19800330T020000", "RRULE:FREQ=YEARLY;BYYEARDAY=1,2,3,4,5,6,7,8")))
        refused("two BYDAY", zone(standard("+0100", "+0200", "19800330T020000", "RRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=-1SU,-1SA")))
        refused("101 observances", zone(*(0..100).map { standard("+0100", "+0100", "%04d0101T000000".format(1900 + it)) }.toTypedArray()))
        refused("2001 RDATE values", zone(standard("+0100", "+0100", "19000101T000000",
            "RDATE:" + (0..2000).joinToString(",") { "%04d%02d01T000000".format(1901 + it / 12, 1 + it % 12) })))
        // Every list at its bound, five times over: more onsets than any real zone has, refused as it expands.
        val dense = standard("+0100", "+0100", "18000101T000000", "RRULE:FREQ=YEARLY;BYMONTH=1;BYMONTHDAY=1,2,3,4,5,6,7")
        val started = System.nanoTime()
        refused("over ${VtimezoneRules.MAX_ONSETS} onsets", zone(dense, dense, dense, dense, dense))
        assertEquals(true, System.nanoTime() - started < 2_000_000_000L)
        // Unbounded, this answer measured 120 MB, and four of it ran out of memory under -Xmx256m.
        refused("the 385-byte blow-up", zone(standard("+0100", "+0100", "00010101T000000",
            "RRULE:FREQ=YEARLY;BYMONTH=1,2,3,4,5,6,7,8,9,10,11,12;BYMONTHDAY=" + (1..31).joinToString(","))))
    }

    @Test
    fun `anything outside the subset tzurl writes is refused, so the phone's rules apply`() {
        val base = text("Europe_Berlin").replace("\r\n", "\n")
        val firstObservance = Regex("BEGIN:STANDARD\n.*?END:STANDARD\n", RegexOption.DOT_MATCHES_ALL).find(base)!!.value
        val vtimezone = Regex("BEGIN:VTIMEZONE\n.*?END:VTIMEZONE\n", RegexOption.DOT_MATCHES_ALL).find(base)!!.value
        val cases = mapOf(
            "missing TZOFFSETFROM" to base.replaceFirst(Regex("TZOFFSETFROM:[^\n]*\n"), ""),
            "DTSTART with a TZID" to base.replaceFirst("\nDTSTART:", "\nDTSTART;TZID=Europe/Berlin:"),
            "a UTC DTSTART" to base.replaceFirst(Regex("\nDTSTART:(\\d{8}T\\d{6})"), "\nDTSTART:$1Z"),
            "a PERIOD RDATE" to base.replaceFirst("END:STANDARD", "RDATE;VALUE=PERIOD:19800101T000000/PT1H\nEND:STANDARD"),
            "BYSETPOS" to base.replaceFirst("RRULE:FREQ=YEARLY", "RRULE:FREQ=YEARLY;BYSETPOS=1"),
            "FREQ=MONTHLY" to base.replaceFirst("RRULE:FREQ=YEARLY", "RRULE:FREQ=MONTHLY"),
            "INTERVAL=2" to base.replaceFirst("RRULE:FREQ=YEARLY", "RRULE:FREQ=YEARLY;INTERVAL=2"),
            "a broken chain" to base.replace(Regex("TZOFFSETFROM:\\+0200\n(TZOFFSETTO:\\+0100\nDTSTART:19961027)"), "TZOFFSETFROM:+0400\n$1"),
            "two VTIMEZONEs" to base.replace(vtimezone, vtimezone + vtimezone.replace("TZID:Europe/Berlin", "TZID:Europe/Paris")),
            "an HTML error page" to "<html><body>502 Bad Gateway</body></html>",
            "an empty body" to "",
            "a truncated download" to base.substring(0, base.length / 2),
            "EXDATE in an observance" to base.replaceFirst("END:STANDARD", "EXDATE:19800101T000000\nEND:STANDARD"),
            "an offset of +0160" to base.replaceFirst(Regex("TZOFFSETTO:[^\n]*"), "TZOFFSETTO:+0160"),
            "no observances" to base.replace(Regex("BEGIN:(STANDARD|DAYLIGHT)\n.*?END:\\1\n", RegexOption.DOT_MATCHES_ALL), ""),
            "a mismatched END" to base.replaceFirst("END:STANDARD", "END:DAYLIGHT"),
        )
        assertNotEquals("the chain case really changes the text", base, cases["a broken chain"])
        assertEquals(true, firstObservance.isNotEmpty())
        for ((name, broken) in cases) {
            try {
                VtimezoneRules.build(broken)
                fail("$name was accepted")
            } catch (_: IllegalArgumentException) {
            }
        }
    }
}

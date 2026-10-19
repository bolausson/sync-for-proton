package me.proton.sync.calendar

import net.fortuna.ical4j.data.CalendarBuilder
import net.fortuna.ical4j.model.DateTime
import net.fortuna.ical4j.model.component.VTimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.StringReader
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.Month
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.zone.ZoneOffsetTransition
import java.time.zone.ZoneOffsetTransitionRule
import java.time.zone.ZoneOffsetTransitionRule.TimeDefinition
import java.time.zone.ZoneRules
import java.util.Locale
import java.util.TimeZone

/**
 * The VTIMEZONE an invitation carries (VTimezoneBuilder), built from the JVM's own zone rules.
 *
 * The strongest check reads the text of every zone back through ical4j, as a receiving calendar
 * would, and compares the offset ical4j finds with the one java.time gives, from the year before
 * the event to eight years after it.
 */
class VTimezoneBuilderTest {

    @Before
    fun relaxLikeTheApp() {
        System.setProperty("ical4j.parsing.relaxed", "true")
        System.setProperty("ical4j.unfolding.relaxed", "true")
        System.setProperty("ical4j.compatibility.outlook", "true")
    }

    /** The event: 2026-09-28 10:00 UTC. */
    private val eventMillis = LocalDateTime.of(2026, 9, 28, 10, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    /** Zones that must always get a definition: common ones, odd offsets, odd rules, fixed ones. */
    private val mustBuild = listOf(
        "Europe/Berlin", "America/New_York", "Australia/Sydney", "Asia/Kolkata", "Africa/Cairo",
        "America/Sao_Paulo", "Pacific/Auckland", "Europe/London", "Europe/Dublin", "America/Santiago",
        "America/St_Johns", "Asia/Kathmandu", "Australia/Lord_Howe", "Pacific/Chatham", "Antarctica/Troll",
        "Africa/Casablanca", "Etc/GMT+5", "Etc/GMT-14", "UTC"
    )

    private fun build(tzId: String): String {
        val vtz = VTimezoneBuilder.build(tzId, eventMillis)
        assertNotNull("no VTIMEZONE for $tzId", vtz)
        return vtz!!
    }

    // ---- reading the text back ----

    /** The definition inside a minimal calendar whose event uses it, parsed by ical4j. */
    private fun parse(tzId: String, vtz: String): VTimeZone {
        val ics = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Test//VTimezoneBuilderTest//EN\r\n" + vtz +
            "BEGIN:VEVENT\r\nUID:vtz-probe\r\nDTSTAMP:20260919T120000Z\r\n" +
            "DTSTART;TZID=$tzId:20260928T120000\r\nDTEND;TZID=$tzId:20260928T130000\r\nSUMMARY:Probe\r\n" +
            "END:VEVENT\r\nEND:VCALENDAR\r\n"
        return CalendarBuilder().build(StringReader(ics)).getComponents<VTimeZone>("VTIMEZONE").single()
    }

    /**
     * Runs [block] with UTC as the JVM's default zone. ical4j 3.2.14 reads a block's floating DTSTART
     * in the default zone first, and around that zone's own switch it moves the time by an hour (seen
     * with Europe/Berlin as the default: Athens, Sydney and Bishkek read an hour off at their change).
     * UTC never switches, so what is left is what the text says.
     */
    private fun <T> inUtc(block: () -> T): T {
        val saved = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        try {
            return block()
        } finally {
            TimeZone.setDefault(saved)
        }
    }

    /** Whether some block of [vtz] has begun by [at]. */
    private fun covers(vtz: VTimeZone, at: Instant): Boolean {
        val date = DateTime(at.toEpochMilli())
        return vtz.observances.any { it.getLatestOnset(date) != null }
    }

    /**
     * The offset ical4j finds at [at], in seconds. Before the first block begins it is that block's
     * TZOFFSETFROM, as libical reads it: RFC 5545 leaves that time open, and ical4j picks any block.
     */
    private fun ical4jOffset(vtz: VTimeZone, at: Instant): Int {
        val date = DateTime(at.toEpochMilli())
        val observance = vtz.getApplicableObservance(date)
        if (observance != null && observance.getLatestOnset(date) != null) return observance.offsetTo.offset.totalSeconds
        return vtz.observances.minByOrNull { it.startDate.date.time }!!.offsetFrom.offset.totalSeconds
    }

    /** Every 13 days (and an hour later each time) from the year before the event to eight years after, plus each change. */
    private fun probes(rules: ZoneRules, eventMillis: Long): List<Instant> {
        val year = Instant.ofEpochMilli(eventMillis).atZone(ZoneOffset.UTC).year
        val from = LocalDate.of(year - 1, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant()
        val to = LocalDate.of(year + 9, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant()
        val out = ArrayList<Instant>()
        var t = from
        while (t.isBefore(to)) {
            out.add(t)
            t = t.plus(Duration.ofDays(13)).plusSeconds(3_600)
        }
        var change = rules.nextTransition(from)
        while (change != null && change.instant.isBefore(to)) {
            out.add(change.instant.minusSeconds(1))
            out.add(change.instant)
            out.add(change.instant.plusSeconds(3_600))
            change = rules.nextTransition(change.instant)
        }
        return out.filter { !it.isBefore(from) }
    }

    /**
     * Every probe where ical4j, reading [vtz], disagrees with [rules]. Empty when the text is right.
     * With [mustCover], an instant before every block (the start of the year before the event) is
     * wrong too, whatever the first TZOFFSETFROM says: the builder promises history from there.
     */
    private fun mismatches(tzId: String, rules: ZoneRules, eventMillis: Long, vtz: String, mustCover: Boolean = true): List<String> =
        inUtc {
            val parsed = parse(tzId, vtz)
            val out = ArrayList<String>()
            val all = probes(rules, eventMillis)
            if (mustCover && !covers(parsed, all.min())) out.add("$tzId: no block has begun by ${all.min()}")
            for (p in all) {
                val want = rules.getOffset(p).totalSeconds
                val got = ical4jOffset(parsed, p)
                if (got != want) out.add("$tzId at $p: java.time $want s, ical4j $got s")
            }
            out
        }

    private fun checkEveryZone(eventMillis: Long, mustCover: Boolean) {
        val zones = ZoneId.getAvailableZoneIds().sorted()
        assertTrue("the JVM knows only ${zones.size} zones", zones.size > 400)
        val missing = ArrayList<String>()
        val wrong = ArrayList<String>()
        for (id in zones) {
            val vtz = VTimezoneBuilder.build(id, eventMillis)
            if (vtz == null) {
                missing.add(id)
                continue
            }
            try {
                val bad = mismatches(id, ZoneId.of(id).rules, eventMillis, vtz, mustCover)
                if (bad.isNotEmpty()) wrong.add("${bad.size} wrong, first: ${bad[0]}")
            } catch (e: Exception) {
                wrong.add("$id does not parse: $e")
            }
        }
        for (id in mustBuild) assertFalse("$id has no definition", id in missing)
        assertTrue("only a handful of zones may lack a definition: $missing", missing.size <= 3)
        assertTrue("${wrong.size} zones read wrong: ${wrong.take(10)}", wrong.isEmpty())
    }

    @Test
    fun `every zone reads back through ical4j with the offsets java time gives`() {
        checkEveryZone(eventMillis, mustCover = true)
    }

    /**
     * An event of 2005. Antarctica/Troll only began in 2005, so for the year before its text starts
     * later and the time before its first block is read from that block's TZOFFSETFROM.
     */
    @Test
    fun `an event in the past reads back right too`() {
        checkEveryZone(LocalDateTime.of(2005, 3, 1, 8, 0).toInstant(ZoneOffset.UTC).toEpochMilli(), mustCover = false)
    }

    // ---- the blocks of the text ----

    /** One STANDARD or DAYLIGHT block: its property lines, without BEGIN and END. */
    private class Block(val kind: String, val lines: List<String>) {
        fun values(name: String): List<String> = lines.filter { it.substringBefore(':') == name }.map { it.substringAfter(':') }
        fun value(name: String): String? = values(name).singleOrNull()
        override fun toString() = "$kind $lines"
    }

    private fun blocks(vtz: String): List<Block> {
        val out = ArrayList<Block>()
        var open: String? = null
        var lines = ArrayList<String>()
        for (line in vtz.split("\r\n")) {
            val kind = open
            if (kind == null) {
                if (line == "BEGIN:STANDARD" || line == "BEGIN:DAYLIGHT") {
                    open = line.substringAfter(':')
                    lines = ArrayList()
                }
            } else if (line == "END:$kind") {
                out.add(Block(kind, lines))
                open = null
            } else {
                lines.add(line)
            }
        }
        return out
    }

    /** The blocks that repeat every year (the ones with an RRULE). */
    private fun recurring(tzId: String): List<Block> = blocks(build(tzId)).filter { it.value("RRULE") != null }

    private fun assertRule(tzId: String, blocks: List<Block>, kind: String, from: String, to: String, time: String, by: String) {
        val block = blocks.singleOrNull { it.kind == kind }
        assertNotNull("$tzId: no single recurring $kind in $blocks", block)
        assertEquals("$tzId $kind", "FREQ=YEARLY;$by", block!!.value("RRULE"))
        assertEquals("$tzId $kind", from, block.value("TZOFFSETFROM"))
        assertEquals("$tzId $kind", to, block.value("TZOFFSETTO"))
        val start = block.value("DTSTART")!!
        assertEquals("$tzId $kind", time, start.substringAfter('T'))
        assertTrue("$tzId $kind: $start is not a date of its own rule", occursOn("FREQ=YEARLY;$by", localDate(start)))
    }

    @Test
    fun `Berlin changes on the last Sunday of March and of October`() {
        val rules = recurring("Europe/Berlin")
        assertEquals(rules.toString(), 2, rules.size)
        assertRule("Europe/Berlin", rules, "DAYLIGHT", "+0100", "+0200", "020000", "BYMONTH=3;BYDAY=-1SU")
        assertRule("Europe/Berlin", rules, "STANDARD", "+0200", "+0100", "030000", "BYMONTH=10;BYDAY=-1SU")
    }

    @Test
    fun `New York changes on the second Sunday of March and the first Sunday of November`() {
        val rules = recurring("America/New_York")
        assertEquals(rules.toString(), 2, rules.size)
        assertRule("America/New_York", rules, "DAYLIGHT", "-0500", "-0400", "020000", "BYMONTH=3;BYDAY=2SU")
        assertRule("America/New_York", rules, "STANDARD", "-0400", "-0500", "020000", "BYMONTH=11;BYDAY=1SU")
    }

    /** Its summer is in our winter: the EU template it used to get was wrong for half the year. */
    @Test
    fun `Sydney's summer starts in October and ends in April`() {
        val rules = recurring("Australia/Sydney")
        assertEquals(rules.toString(), 2, rules.size)
        assertRule("Australia/Sydney", rules, "DAYLIGHT", "+1000", "+1100", "020000", "BYMONTH=10;BYDAY=1SU")
        assertRule("Australia/Sydney", rules, "STANDARD", "+1100", "+1000", "030000", "BYMONTH=4;BYDAY=1SU")
    }

    /**
     * RFC 5545 leaves a DTSTART that is not one of its RRULE's own dates undefined, and receivers
     * differ on it, so every recurring block must start on one.
     */
    @Test
    fun `every recurring block starts on one of its own dates`() {
        val form = Regex(
            "FREQ=YEARLY;BYMONTH=([1-9]|1[0-2]);(BYMONTHDAY=[1-9]\\d?|BYDAY=(-1|[1-4])(MO|TU|WE|TH|FR|SA|SU)" +
                "|BYMONTHDAY=-?\\d{1,2}(,-?\\d{1,2}){6};BYDAY=(MO|TU|WE|TH|FR|SA|SU))"
        )
        var seen = 0
        for (id in ZoneId.getAvailableZoneIds().sorted()) {
            val vtz = VTimezoneBuilder.build(id, eventMillis) ?: continue
            for (b in blocks(vtz)) {
                val rrule = b.value("RRULE") ?: continue
                seen++
                assertTrue("$id: unexpected form $rrule", form.matches(rrule))
                val start = b.value("DTSTART")!!
                assertTrue("$id: $start is not a date of $rrule", occursOn(rrule, localDate(start)))
            }
        }
        assertTrue("only $seen recurring blocks", seen > 200)
    }

    /**
     * One block, no rule. Its start is Kolkata's last real change (war time ended in 1945), so the
     * offset before it is that of war time, which is accurate history and harmless to receivers.
     */
    @Test
    fun `a zone without daylight saving time has one standard block and no rule`() {
        val all = blocks(build("Asia/Kolkata"))
        assertEquals(all.toString(), 1, all.size)
        val block = all.single()
        assertEquals("STANDARD", block.kind)
        assertEquals("+0530", block.value("TZOFFSETTO"))
        assertNull(block.value("RRULE"))
        assertTrue(block.values("RDATE").isEmpty())
        assertEquals("+0630", block.value("TZOFFSETFROM"))
        assertEquals("19451015T000000", block.value("DTSTART"))
    }

    @Test
    fun `a zone that never changed has the same offset on both sides`() {
        for ((id, offset) in listOf("Etc/GMT+5" to "-0500", "Etc/GMT-14" to "+1400", "UTC" to "+0000")) {
            val all = blocks(build(id))
            assertEquals("$id: $all", 1, all.size)
            val block = all.single()
            assertEquals(id, "STANDARD", block.kind)
            assertEquals(id, offset, block.value("TZOFFSETFROM"))
            assertEquals(id, offset, block.value("TZOFFSETTO"))
            assertEquals(id, "19700101T000000", block.value("DTSTART"))
            assertNull(id, block.value("RRULE"))
        }
    }

    // ---- shape ----

    @Test
    fun `every definition has the shape receivers expect`() {
        val names = setOf("BEGIN", "END", "TZID", "X-LIC-LOCATION", "TZOFFSETFROM", "TZOFFSETTO", "DTSTART", "RDATE", "RRULE")
        val offsetForm = Regex("[+-]\\d{4}(\\d{2})?")
        val localStamp = Regex("\\d{8}T\\d{6}")
        for (id in ZoneId.getAvailableZoneIds().sorted()) {
            val vtz = VTimezoneBuilder.build(id, eventMillis) ?: continue
            assertTrue(id, vtz.startsWith("BEGIN:VTIMEZONE\r\nTZID:$id\r\nX-LIC-LOCATION:$id\r\n"))
            assertTrue(id, vtz.endsWith("\r\nEND:VTIMEZONE\r\n"))
            assertFalse(id, vtz.contains("TZNAME"))
            for (line in vtz.removeSuffix("\r\n").split("\r\n")) {
                assertFalse("$id: empty line or stray line break [$line]", line.isEmpty() || '\r' in line || '\n' in line)
                val name = line.substringBefore(':')
                val value = line.substringAfter(':')
                assertTrue("$id: unexpected line $line", name in names)
                when (name) {
                    "BEGIN", "END" -> assertTrue("$id: $line", value in setOf("VTIMEZONE", "STANDARD", "DAYLIGHT"))
                    "TZOFFSETFROM", "TZOFFSETTO" -> {
                        assertTrue("$id: $line", offsetForm.matches(value) && value != "-0000")
                        assertFalse("$id: seconds written though there are none: $line", value.length == 7 && value.endsWith("00"))
                    }
                    "DTSTART", "RDATE" -> assertTrue("$id: not a local date-time: $line", localStamp.matches(value))
                }
            }
            val all = blocks(vtz)
            assertTrue(id, all.isNotEmpty())
            for (b in all) {
                for (p in listOf("TZOFFSETFROM", "TZOFFSETTO", "DTSTART")) assertEquals("$id $p: $b", 1, b.values(p).size)
                assertTrue("$id: $b", b.values("RRULE").size <= 1)
                assertFalse("$id: RRULE and RDATE together in $b", b.values("RRULE").isNotEmpty() && b.values("RDATE").isNotEmpty())
            }
        }
    }

    private fun fixedOffset(offset: ZoneOffset): String? {
        val vtz = VTimezoneBuilder.build("Test/Fixed", eventMillis, ZoneRules.of(offset))
        assertNotNull(vtz)
        val block = blocks(vtz!!).single()
        assertEquals(block.value("TZOFFSETTO"), block.value("TZOFFSETFROM"))
        return block.value("TZOFFSETTO")
    }

    @Test
    fun `offsets are sign hours and minutes with seconds only when there are some`() {
        assertEquals("+0100", fixedOffset(ZoneOffset.ofHours(1)))
        assertEquals("-0500", fixedOffset(ZoneOffset.ofHours(-5)))
        assertEquals("+0000", fixedOffset(ZoneOffset.UTC))
        assertEquals("+1400", fixedOffset(ZoneOffset.ofHours(14)))
        assertEquals("+0545", fixedOffset(ZoneOffset.ofHoursMinutes(5, 45)))
        assertEquals("-0330", fixedOffset(ZoneOffset.ofHoursMinutes(-3, -30)))
        assertEquals("-002521", fixedOffset(ZoneOffset.ofHoursMinutesSeconds(0, -25, -21)))
        assertEquals("+085756", fixedOffset(ZoneOffset.ofHoursMinutesSeconds(8, 57, 56)))
        // A real one: Palau's local mean time, the offset before its change of 1901.
        assertEquals("+085756", blocks(build("Pacific/Palau")).first().value("TZOFFSETFROM"))
        // And ical4j reads an offset with seconds.
        val dublinMeanTime = ZoneRules.of(ZoneOffset.ofHoursMinutesSeconds(0, -25, -21))
        val vtz = VTimezoneBuilder.build("Test/Seconds", eventMillis, dublinMeanTime)!!
        assertEquals(emptyList<String>(), mismatches("Test/Seconds", dublinMeanTime, eventMillis, vtz))
    }

    // ---- a rule no RRULE can express ----

    private val two = ZoneOffset.ofHours(2)
    private val three = ZoneOffset.ofHours(3)

    /** Egypt since 2023: summer from the last Friday of April, back after the last Thursday of October at 24:00. */
    private val egyptLike: ZoneRules = ZoneRules.of(
        two, two, emptyList(),
        listOf(
            ZoneOffsetTransition.of(LocalDateTime.of(2023, 4, 28, 0, 0), two, three),
            ZoneOffsetTransition.of(LocalDateTime.of(2023, 10, 27, 0, 0), three, two)
        ),
        listOf(
            ZoneOffsetTransitionRule.of(Month.APRIL, -1, DayOfWeek.FRIDAY, LocalTime.MIDNIGHT, false, TimeDefinition.WALL, two, two, three),
            ZoneOffsetTransitionRule.of(Month.OCTOBER, -1, DayOfWeek.THURSDAY, LocalTime.MIDNIGHT, true, TimeDefinition.WALL, two, three, two)
        )
    )

    @Test
    fun `a rule no RRULE can express is listed as dated onsets and still read right`() {
        val vtz = VTimezoneBuilder.build("Test/Egypt", eventMillis, egyptLike)
        assertNotNull(vtz)
        val repeating = blocks(vtz!!).filter { it.value("RRULE") != null || it.values("RDATE").isNotEmpty() }
        assertEquals(repeating.toString(), 2, repeating.size)
        assertEquals("FREQ=YEARLY;BYMONTH=4;BYDAY=-1FR", repeating.single { it.kind == "DAYLIGHT" }.value("RRULE"))
        val winter = repeating.single { it.kind == "STANDARD" }
        assertNull(winter.value("RRULE"))
        assertEquals("+0300", winter.value("TZOFFSETFROM"))
        assertEquals("+0200", winter.value("TZOFFSETTO"))
        val onsets = winter.values("RDATE")
        // Thursday 31 October 2030 at 24:00 is 1 November.
        assertTrue(onsets.toString(), "20301101T000000" in onsets)
        assertEquals("2100", onsets.last().take(4))
        assertEquals(emptyList<String>(), mismatches("Test/Egypt", egyptLike, eventMillis, vtz))
    }

    // ---- inputs ----

    @Test
    fun `given rules are used instead of looking the id up`() {
        val vtz = VTimezoneBuilder.build("Not/A_Zone", eventMillis, ZoneRules.of(three))
        assertNotNull(vtz)
        assertTrue(vtz!!.startsWith("BEGIN:VTIMEZONE\r\nTZID:Not/A_Zone\r\n"))
        assertEquals("+0300", blocks(vtz).single().value("TZOFFSETTO"))
        assertEquals(build("Europe/Berlin"), VTimezoneBuilder.build("Europe/Berlin", eventMillis, ZoneId.of("Europe/Berlin").rules))
    }

    @Test
    fun `an unknown zone gives null and no input throws`() {
        assertNull(VTimezoneBuilder.build("No/Such_Zone", eventMillis))
        assertNull(VTimezoneBuilder.build("", eventMillis))
        assertNull(VTimezoneBuilder.build("Europe/Berlin\r\nEND:VCALENDAR", eventMillis))
        for (ms in listOf(Long.MIN_VALUE, -1L, 0L, Long.MAX_VALUE)) {
            for (id in listOf("Europe/Berlin", "Africa/Cairo", "Asia/Kolkata", "Etc/GMT+5", "No/Such_Zone")) {
                val vtz = VTimezoneBuilder.build(id, ms)
                assertTrue("$id at $ms", vtz == null || vtz.startsWith("BEGIN:VTIMEZONE\r\nTZID:$id\r\n") && vtz.endsWith("END:VTIMEZONE\r\n"))
            }
        }
    }

    // ---- recurrenceOf ----

    private val plusOne = ZoneOffset.ofHours(1)
    private val plusTwo = ZoneOffset.ofHours(2)

    private fun rule(month: Month, dayIndicator: Int, day: DayOfWeek?, time: LocalTime = LocalTime.of(2, 0), endOfDay: Boolean = false) =
        ZoneOffsetTransitionRule.of(month, dayIndicator, day, time, endOfDay, TimeDefinition.WALL, plusOne, plusOne, plusTwo)

    @Test
    fun `a rule on a fixed date recurs on that day of the month`() {
        assertEquals("BYMONTH=3;BYMONTHDAY=21", VTimezoneBuilder.recurrenceOf(rule(Month.MARCH, 21, null), 2026))
    }

    @Test
    fun `the nth weekday of a month is written as an nth BYDAY`() {
        assertEquals("BYMONTH=3;BYDAY=2SU", VTimezoneBuilder.recurrenceOf(rule(Month.MARCH, 8, DayOfWeek.SUNDAY), 2026))
        assertEquals("BYMONTH=11;BYDAY=1SU", VTimezoneBuilder.recurrenceOf(rule(Month.NOVEMBER, 1, DayOfWeek.SUNDAY), 2026))
        assertEquals("BYMONTH=4;BYDAY=4SA", VTimezoneBuilder.recurrenceOf(rule(Month.APRIL, 22, DayOfWeek.SATURDAY), 2026))
    }

    /** The JDK says "last Sunday" (day -1), Android's provider can say "Sunday on or after the 25th". */
    @Test
    fun `the last Sunday reads the same however the provider encodes it`() {
        for (month in listOf(Month.MARCH, Month.OCTOBER)) {
            val expected = "BYMONTH=${month.value};BYDAY=-1SU"
            assertEquals(expected, VTimezoneBuilder.recurrenceOf(rule(month, -1, DayOfWeek.SUNDAY), 2026))
            assertEquals(expected, VTimezoneBuilder.recurrenceOf(rule(month, 25, DayOfWeek.SUNDAY), 2026))
        }
        val berlin = ZoneId.of("Europe/Berlin").rules.transitionRules.map { VTimezoneBuilder.recurrenceOf(it, 2026) }
        assertEquals(setOf("BYMONTH=3;BYDAY=-1SU", "BYMONTH=10;BYDAY=-1SU"), berlin.toSet())
    }

    @Test
    fun `a week that is neither nth nor last becomes a seven day window`() {
        assertEquals(
            "BYMONTH=9;BYMONTHDAY=12,13,14,15,16,17,18;BYDAY=SU",
            VTimezoneBuilder.recurrenceOf(rule(Month.SEPTEMBER, 12, DayOfWeek.SUNDAY), 2026)
        )
        // The Sunday on or before the second last day of February, whose length changes.
        assertEquals(
            "BYMONTH=2;BYMONTHDAY=-8,-7,-6,-5,-4,-3,-2;BYDAY=SU",
            VTimezoneBuilder.recurrenceOf(rule(Month.FEBRUARY, -2, DayOfWeek.SUNDAY), 2026)
        )
        // The first Thursday at 24:00 is a Friday between the 2nd and the 8th.
        assertEquals(
            "BYMONTH=3;BYMONTHDAY=2,3,4,5,6,7,8;BYDAY=FR",
            VTimezoneBuilder.recurrenceOf(rule(Month.MARCH, 1, DayOfWeek.THURSDAY, LocalTime.MIDNIGHT, true), 2026)
        )
    }

    /** Egypt: the last Thursday of October at 24:00 is 1 November when that Thursday is the 31st. */
    @Test
    fun `a rule whose onset leaves its month has no recurrence`() {
        assertNull(VTimezoneBuilder.recurrenceOf(rule(Month.OCTOBER, -1, DayOfWeek.THURSDAY, LocalTime.MIDNIGHT, true), 2026))
    }

    // ---- tzidsOf ----

    @Test
    fun `a timed event names its own zone and an all day event none`() {
        assertEquals(listOf("Europe/Berlin"), VTimezoneBuilder.tzidsOf("Europe/Berlin", false))
        assertEquals(listOf("Europe/Berlin"), VTimezoneBuilder.tzidsOf("Europe/Berlin", false, null, null))
        assertEquals(emptyList<String>(), VTimezoneBuilder.tzidsOf("Europe/Berlin", true))
        // A line without a TZID names nothing.
        assertEquals(listOf("Europe/Berlin"), VTimezoneBuilder.tzidsOf("Europe/Berlin", false, "EXDATE:20261001T090000Z\r\n"))
    }

    @Test
    fun `UTC is never named`() {
        assertEquals(emptyList<String>(), VTimezoneBuilder.tzidsOf("UTC", false))
        assertEquals(
            listOf("Europe/Berlin"),
            VTimezoneBuilder.tzidsOf("Europe/Berlin", false, "EXDATE;TZID=UTC:20261001T090000\r\n", "RECURRENCE-ID;TZID=\"UTC\":20261001T090000\r\n")
        )
        assertEquals(listOf("Asia/Tokyo"), VTimezoneBuilder.tzidsOf("UTC", false, "EXDATE;TZID=Asia/Tokyo:20261001T090000\r\n"))
    }

    @Test
    fun `zones named by exception and occurrence lines are collected once in order`() {
        val exdates = "EXDATE;TZID=America/New_York:20261001T090000,20261008T090000\r\n" +
            "EXDATE;TZID=Europe/Berlin:20261015T150000\r\n" +
            "EXDATE;VALUE=DATE-TIME;TZID=\"Asia/Tokyo\":20261022T090000\r\n" +
            "EXDATE;TZID=America/New_York:20261029T090000\r\n"
        val recurrenceId = "RECURRENCE-ID;TZID=\"Australia/Sydney\";RANGE=THISANDFUTURE:20261105T090000\r\n"
        assertEquals(
            listOf("Europe/Berlin", "America/New_York", "Asia/Tokyo", "Australia/Sydney"),
            VTimezoneBuilder.tzidsOf("Europe/Berlin", false, exdates, recurrenceId)
        )
        // An all-day event names no zone of its own, but a line that names one still needs it.
        assertEquals(listOf("Europe/Paris"), VTimezoneBuilder.tzidsOf("Europe/Berlin", true, "EXDATE;TZID=Europe/Paris:20261001T000000\r\n"))
        // Quoted or not, one zone is named once.
        assertEquals(listOf("Europe/Berlin"), VTimezoneBuilder.tzidsOf("Europe/Berlin", false, null, "RECURRENCE-ID;TZID=\"Europe/Berlin\":20261001T090000\r\n"))
    }

    // ---- locale ----

    /** Arabic, Persian and Thai digits must never reach the text, whatever the phone's language. */
    @Test
    fun `the text is the same whatever the phone's language`() {
        val zones = listOf("Europe/Berlin", "Asia/Kolkata", "Africa/Cairo", "America/St_Johns", "Asia/Kathmandu", "Pacific/Palau")
        val saved = Locale.getDefault()
        val savedFormat = Locale.getDefault(Locale.Category.FORMAT)
        val savedDisplay = Locale.getDefault(Locale.Category.DISPLAY)
        try {
            Locale.setDefault(Locale.ROOT)
            val expected = zones.map { build(it) }
            for (text in expected) assertTrue(text, text.all { it.code < 128 })
            for (tag in listOf("ar", "hi-IN", "fa-IR", "ar-EG-u-nu-arab", "th-TH-u-nu-thai")) {
                Locale.setDefault(Locale.forLanguageTag(tag))
                assertEquals(tag, expected, zones.map { build(it) })
            }
        } finally {
            Locale.setDefault(saved)
            Locale.setDefault(Locale.Category.FORMAT, savedFormat)
            Locale.setDefault(Locale.Category.DISPLAY, savedDisplay)
        }
    }

    // ---- helpers ----

    private fun localDate(stamp: String): LocalDate = LocalDate.parse(stamp.substring(0, 8), DateTimeFormatter.BASIC_ISO_DATE)

    private val dayCodes = listOf("MO", "TU", "WE", "TH", "FR", "SA", "SU")

    /** Whether [date] is one of the yearly dates of [rrule], in the forms the builder writes. */
    private fun occursOn(rrule: String, date: LocalDate): Boolean {
        val parts = rrule.split(';').associate { it.substringBefore('=') to it.substringAfter('=') }
        if (parts["FREQ"] != "YEARLY" || parts["BYMONTH"]?.toInt() != date.monthValue) return false
        val length = date.lengthOfMonth()
        val monthDays = parts["BYMONTHDAY"]?.split(',')?.map { it.toInt() }?.map { if (it < 0) length + 1 + it else it }
        if (monthDays != null && date.dayOfMonth !in monthDays) return false
        val byDay = parts["BYDAY"] ?: return monthDays != null
        if (dayCodes.indexOf(byDay.takeLast(2)) + 1 != date.dayOfWeek.value) return false
        val nth = byDay.dropLast(2).toIntOrNull() ?: return true
        return if (nth > 0) (date.dayOfMonth - 1) / 7 + 1 == nth else (length - date.dayOfMonth) / 7 + 1 == -nth
    }

    @Test
    fun `a zone that gave up summer time is written as standard only`() {
        // These made their summer offset permanent by moving the standard offset, which is no wall-clock
        // transition: the last dated change used to keep a DAYLIGHT label for ever, and a reader that takes
        // the latest DAYLIGHT and STANDARD blocks for a yearly rule then invented summer time.
        for (id in listOf("Europe/Istanbul", "Asia/Amman", "Asia/Damascus", "America/Whitehorse", "America/Asuncion")) {
            val vtz = VTimezoneBuilder.build(id, eventMillis)
            assertNotNull("$id has no definition", vtz)
            assertFalse("$id still has a DAYLIGHT block: $vtz", vtz!!.contains("BEGIN:DAYLIGHT"))
            assertFalse("$id should have no rule left", vtz.contains("RRULE:"))
            // The offsets are unchanged: the text still reads back as java.time has it.
            assertEquals(emptyList<String>(), mismatches(id, ZoneId.of(id).rules, eventMillis, vtz))
        }
        // A zone that still changes keeps both kinds.
        val berlin = VTimezoneBuilder.build("Europe/Berlin", eventMillis)!!
        assertTrue(berlin.contains("BEGIN:DAYLIGHT"))
        assertTrue(berlin.contains("BEGIN:STANDARD"))
    }

}

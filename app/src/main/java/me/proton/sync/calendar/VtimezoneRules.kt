package me.proton.sync.calendar

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.Year
import java.time.ZoneOffset
import java.time.temporal.TemporalAdjusters
import java.time.zone.ZoneOffsetTransition
import java.time.zone.ZoneRules
import java.util.TreeSet

/**
 * One tzurl.org VTIMEZONE text turned into java.time [ZoneRules] by our own code: no ical4j offset lookup and
 * no ical4j recurrence expansion, which is where every measured misread came from (ical4j 3.2.14 puts
 * America/Vancouver nine hours early from 2026-11-01 on tzurl's current file, Lord Howe 10.5 hours, Troll 9).
 *
 * The model is RFC 5545 3.6.5: each observance contributes onsets, its DTSTART plus its RRULE instances plus
 * its RDATEs. Each onset is a local time read in that observance's TZOFFSETFROM, and from that instant on its
 * TZOFFSETTO applies. Before the first onset the first onset's TZOFFSETFROM applies (vzic writes LMT there).
 *
 * STRICT on purpose: anything outside the narrow subset tzurl's vzic writes (one VTIMEZONE; local DTSTART and
 * RDATE; FREQ=YEARLY rules with BYMONTH, BYMONTHDAY, BYYEARDAY, BYDAY, UNTIL or COUNT, INTERVAL 1) throws
 * IllegalArgumentException, and so does a broken chain (an onset whose TZOFFSETFROM is not the previous
 * onset's TZOFFSETTO) or two onsets at one instant that disagree. The caller then uses the phone's rules.
 *
 * BOUNDED as well, before anything is expanded, so a small answer that is valid syntax but not vzic's shape
 * cannot make the expansion allocate without end: no DTSTART or RDATE before [MIN_YEAR]; at most one BYMONTH
 * and one BYDAY value and seven BYMONTHDAY or BYYEARDAY values per rule (vzic writes one month, one weekday and
 * "Sun>=8" as seven month days); at most [MAX_OBSERVANCES] observances, [MAX_RDATES] RDATE values and
 * [MAX_ONSETS] onsets in all. Measured on all 346 zone files the app can ask for (Olson 2026d): earliest
 * DTSTART 1844, at most 30 observances and 132 RDATE values, and the list sizes above.
 *
 * Validated in 2026-10 against IANA 2026d compiled by OpenJDK's own tzdb compiler: all 346 zones the app can
 * ask for build, 345 have identical 1970-2037 transitions, and 26 million local-time probes matched. The one
 * difference is an error in tzurl's own file (Europe/Istanbul 1974-75; Asia/Hong_Kong 1948-64 before 1970),
 * which any correct reader reproduces. Onsets are expanded through [HORIZON_YEAR] only.
 */
internal object VtimezoneRules {

    /** Onsets are expanded through this year inclusive; trust the rules only for local years below it. */
    const val HORIZON_YEAR = 2100

    /** No observance starts, and no RDATE lies, before this year. */
    const val MIN_YEAR = 1800

    const val MAX_OBSERVANCES = 100
    const val MAX_RDATES = 2_000
    const val MAX_ONSETS = 10_000

    class Built(
        /** The TZID tzurl answered with: for a link it is the target (Europe/Oslo comes back as Europe/Berlin). */
        val tzid: String,
        val prodId: String?,
        val rules: ZoneRules,
        val transitionCount: Int
    ) {
        /** The tz release tzurl built the file from, "2026d" from "-//tzurl.org//NONSGML Olson 2026d//EN". */
        val olsonVersion: String? get() = prodId?.let { OLSON.find(it)?.groupValues?.get(1) }
    }

    private val OLSON = Regex("Olson\\s+(\\d{4}[a-z]+)")
    private val OFFSET = Regex("([+-])(\\d{2})(\\d{2})(\\d{2})?")
    private val LOCAL_VALUE = Regex("\\d{8}T\\d{6}")
    private val UTC_VALUE = Regex("\\d{8}T\\d{6}Z")
    private val DATE_VALUE = Regex("\\d{8}")
    private val BYDAY_ITEM = Regex("([+-]?\\d{1,2})?(MO|TU|WE|TH|FR|SA|SU)")
    private val DAY_CODES = listOf("MO", "TU", "WE", "TH", "FR", "SA", "SU")
    private val RRULE_KEYS = setOf("FREQ", "UNTIL", "COUNT", "INTERVAL", "BYMONTH", "BYMONTHDAY", "BYYEARDAY", "BYDAY", "WKST")

    private class Observance(
        val from: ZoneOffset,
        val to: ZoneOffset,
        val dtStart: LocalDateTime,
        val rrule: Map<String, String>?,
        val rdates: List<LocalDateTime>
    )

    private class Onset(val epochSecond: Long, val from: ZoneOffset, val to: ZoneOffset, val local: LocalDateTime)

    /** Build the rules, or throw IllegalArgumentException (also for DateTimeException: wrapped). */
    fun build(text: String): Built {
        try {
            val parsed = parse(text)
            val onsets = onsets(parsed.observances)
            val transitions = ArrayList<ZoneOffsetTransition>()
            for (o in onsets) {
                if (o.from == o.to) continue   // a name-only change, e.g. PDT to MST at -07:00
                transitions.add(ZoneOffsetTransition.of(o.local, o.from, o.to))
            }
            val base = onsets.first().from
            val rules = ZoneRules.of(base, base, emptyList(), transitions, emptyList())
            return Built(parsed.tzid, parsed.prodId, rules, transitions.size)
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: RuntimeException) {
            throw IllegalArgumentException("${e.javaClass.simpleName}: ${e.message}", e)
        }
    }

    private class Parsed(val tzid: String, val prodId: String?, val observances: List<Observance>)

    private fun parse(text: String): Parsed {
        val unfolded = text.replace("\r\n", "\n").replace("\r", "\n").replace(Regex("\n[ \t]"), "")
        var tzid: String? = null
        var prodId: String? = null
        var vtimezones = 0
        val observances = ArrayList<Observance>()
        val stack = ArrayList<String>()
        var from: ZoneOffset? = null
        var to: ZoneOffset? = null
        var dtStart: LocalDateTime? = null
        var rrule: Map<String, String>? = null
        var rdates = ArrayList<LocalDateTime>()
        var rdateCount = 0

        for (line in unfolded.split("\n")) {
            if (line.isEmpty()) continue
            val colon = valueColon(line)
            require(colon >= 0) { "line without ':'" }
            val headParts = line.substring(0, colon).split(";")
            val value = line.substring(colon + 1)
            val name = headParts[0].uppercase()
            val params = headParts.drop(1).map { it.uppercase() }
            val path = stack.joinToString("/")

            when (name) {
                "BEGIN" -> {
                    val comp = value.trim().uppercase()
                    stack.add(comp)
                    val newPath = stack.joinToString("/")
                    when (comp) {
                        "VCALENDAR" -> require(newPath == "VCALENDAR") { "VCALENDAR misplaced" }
                        "VTIMEZONE" -> {
                            require(newPath == "VCALENDAR/VTIMEZONE") { "VTIMEZONE misplaced" }
                            vtimezones++
                            require(vtimezones <= 1) { "more than one VTIMEZONE" }
                        }
                        "STANDARD", "DAYLIGHT" -> {
                            require(newPath == "VCALENDAR/VTIMEZONE/$comp") { "observance misplaced" }
                            from = null; to = null; dtStart = null; rrule = null; rdates = ArrayList()
                        }
                        else -> throw IllegalArgumentException("unexpected component $comp")
                    }
                    continue
                }
                "END" -> {
                    val comp = value.trim().uppercase()
                    require(stack.isNotEmpty()) { "END without BEGIN" }
                    val top = stack.removeAt(stack.size - 1)
                    require(comp == top) { "END:$comp closes $top" }
                    if (comp == "STANDARD" || comp == "DAYLIGHT") {
                        val f = from
                        val t = to
                        val s = dtStart
                        require(f != null && t != null && s != null) { "observance lacks DTSTART/TZOFFSETFROM/TZOFFSETTO" }
                        require(observances.size < MAX_OBSERVANCES) { "more than $MAX_OBSERVANCES observances" }
                        observances.add(Observance(f, t, s, rrule, rdates))
                    }
                    continue
                }
            }
            when {
                path == "VCALENDAR" -> if (name == "PRODID") prodId = value.trim()
                path == "VCALENDAR/VTIMEZONE" -> if (name == "TZID") {
                    require(tzid == null) { "two TZIDs" }
                    tzid = value.trim()
                }
                path.endsWith("/STANDARD") || path.endsWith("/DAYLIGHT") -> when (name) {
                    "DTSTART" -> {
                        require(dtStart == null) { "two DTSTARTs" }
                        requireLocalDateTimeParams(params)
                        dtStart = parseLocal(value.trim())
                    }
                    "TZOFFSETFROM" -> {
                        require(from == null) { "two TZOFFSETFROMs" }
                        from = parseOffset(value.trim())
                    }
                    "TZOFFSETTO" -> {
                        require(to == null) { "two TZOFFSETTOs" }
                        to = parseOffset(value.trim())
                    }
                    "RRULE" -> {
                        require(rrule == null) { "two RRULEs" }
                        rrule = parseRrule(value.trim())
                    }
                    "RDATE" -> {
                        requireLocalDateTimeParams(params)
                        for (v in value.split(",")) {
                            rdates.add(parseLocal(v.trim()))
                            rdateCount++
                            require(rdateCount <= MAX_RDATES) { "more than $MAX_RDATES RDATE values" }
                        }
                    }
                    "EXDATE", "EXRULE" -> throw IllegalArgumentException("$name in an observance")
                    else -> { /* TZNAME, COMMENT, X-... */ }
                }
                else -> throw IllegalArgumentException("property outside a known component")
            }
        }
        require(stack.isEmpty()) { "unterminated ${stack.joinToString("/")}" }
        val id = tzid
        require(vtimezones == 1 && id != null) { "no VTIMEZONE/TZID" }
        require(observances.isNotEmpty()) { "no observances" }
        return Parsed(id, prodId, observances)
    }

    /** The colon that ends the name and parameters, skipping colons inside quoted parameter values. */
    private fun valueColon(line: String): Int {
        var quoted = false
        for (i in line.indices) {
            val c = line[i]
            if (c == '"') quoted = !quoted else if (c == ':' && !quoted) return i
        }
        return -1
    }

    private fun requireLocalDateTimeParams(params: List<String>) {
        for (p in params) require(p == "VALUE=DATE-TIME") { "unsupported parameter $p" }
    }

    /** An observance's DTSTART or RDATE value: local, and not before [MIN_YEAR]. */
    private fun parseLocal(v: String): LocalDateTime {
        require(LOCAL_VALUE.matches(v)) { "not a local DATE-TIME" }
        val local = LocalDateTime.parse(v, IcalTimeValues.LOCAL_FORMAT)
        require(local.year >= MIN_YEAR) { "a year before $MIN_YEAR" }
        return local
    }

    internal fun parseOffset(v: String): ZoneOffset {
        val m = OFFSET.matchEntire(v) ?: throw IllegalArgumentException("bad offset $v")
        val h = m.groupValues[2].toInt()
        val mi = m.groupValues[3].toInt()
        val s = m.groupValues[4].ifEmpty { "0" }.toInt()
        require(mi <= 59 && s <= 59) { "bad offset $v" }
        val total = h * 3600 + mi * 60 + s
        return ZoneOffset.ofTotalSeconds(if (m.groupValues[1] == "-") -total else total)
    }

    private fun parseRrule(v: String): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        for (part in v.split(";")) {
            val eq = part.indexOf('=')
            require(eq > 0) { "bad RRULE part" }
            val k = part.substring(0, eq).uppercase()
            require(k in RRULE_KEYS) { "unsupported RRULE part $k" }
            require(m.put(k, part.substring(eq + 1).uppercase()) == null) { "repeated $k" }
        }
        require(m["FREQ"] == "YEARLY") { "FREQ not YEARLY" }
        require(!m.containsKey("INTERVAL") || m["INTERVAL"] == "1") { "INTERVAL" }
        require(!(m.containsKey("UNTIL") && m.containsKey("COUNT"))) { "UNTIL and COUNT" }
        return m
    }

    /** RRULE instances of one observance, in order, DTSTART included when it matches, through [lastYear]. */
    private fun expandRrule(o: Observance, lastYear: Int): List<LocalDateTime> {
        val r = o.rrule!!
        val out = ArrayList<LocalDateTime>()
        var untilInstant: Instant? = null
        var untilLocal: LocalDateTime? = null
        r["UNTIL"]?.let { until ->
            when {
                UTC_VALUE.matches(until) ->
                    untilInstant = LocalDateTime.parse(until.substring(0, 15), IcalTimeValues.LOCAL_FORMAT).toInstant(ZoneOffset.UTC)
                LOCAL_VALUE.matches(until) -> untilLocal = LocalDateTime.parse(until, IcalTimeValues.LOCAL_FORMAT)
                DATE_VALUE.matches(until) ->
                    untilLocal = LocalDate.parse(until, java.time.format.DateTimeFormatter.BASIC_ISO_DATE).atTime(LocalTime.MAX)
                else -> throw IllegalArgumentException("bad UNTIL")
            }
        }
        val count = r["COUNT"]?.toInt() ?: Int.MAX_VALUE
        require(count > 0) { "bad COUNT" }

        val byMonth = intList(r["BYMONTH"], 1, 12, false)
        val byMonthDay = intList(r["BYMONTHDAY"], 1, 31, true)
        val byYearDay = intList(r["BYYEARDAY"], 1, 366, true)
        val byDay = byDayList(r["BYDAY"])   // (ordinal, 0 = none) to day-of-week value
        // vzic's shape, and the bound on how much one rule can expand to (see the class comment).
        require((byMonth?.size ?: 0) <= 1) { "more than one BYMONTH" }
        require((byMonthDay?.size ?: 0) <= 7) { "more than seven BYMONTHDAY" }
        require((byYearDay?.size ?: 0) <= 7) { "more than seven BYYEARDAY" }
        require((byDay?.size ?: 0) <= 1) { "more than one BYDAY" }
        val ordinals = byDay != null && byDay.any { it.first != 0 }
        require(!(ordinals && (byMonthDay != null || byYearDay != null))) { "ordinal BYDAY with BYMONTHDAY/BYYEARDAY" }
        require(!(byDay != null && byMonth == null && byMonthDay == null && byYearDay == null)) { "BYDAY over a whole year" }

        val time = o.dtStart.toLocalTime()
        var emitted = 0
        for (year in o.dtStart.year..lastYear) {
            val dates = TreeSet<LocalDate>()
            if (byYearDay != null) {
                val len = Year.of(year).length()
                for (yd in byYearDay) {
                    val day = if (yd > 0) yd else len + 1 + yd
                    if (day < 1 || day > len) continue
                    val d = LocalDate.ofYearDay(year, day)
                    if (byMonth != null && d.monthValue !in byMonth) continue
                    if (byMonthDay != null && !monthDayMatches(d, byMonthDay)) continue
                    if (byDay != null && !weekdayMatches(d, byDay)) continue
                    dates.add(d)
                }
            } else {
                val months = byMonth ?: listOf(o.dtStart.monthValue)
                for (month in months) {
                    val first = LocalDate.of(year, month, 1)
                    val mlen = first.lengthOfMonth()
                    if (byMonthDay != null) {
                        for (md in byMonthDay) {
                            val day = if (md > 0) md else mlen + 1 + md
                            if (day < 1 || day > mlen) continue
                            val d = first.withDayOfMonth(day)
                            if (byDay != null && !weekdayMatches(d, byDay)) continue
                            dates.add(d)
                        }
                    } else if (byDay != null) {
                        for ((ord, dowValue) in byDay) {
                            val dow = DayOfWeek.of(dowValue)
                            if (ord == 0) {
                                var d = first.with(TemporalAdjusters.firstInMonth(dow))
                                while (d.monthValue == month) { dates.add(d); d = d.plusWeeks(1) }
                            } else {
                                val d = if (ord > 0) {
                                    first.with(TemporalAdjusters.firstInMonth(dow)).plusWeeks((ord - 1).toLong())
                                } else {
                                    first.with(TemporalAdjusters.lastInMonth(dow)).minusWeeks((-ord - 1).toLong())
                                }
                                if (d.monthValue == month) dates.add(d)
                            }
                        }
                    } else {
                        val day = o.dtStart.dayOfMonth
                        if (day <= mlen) dates.add(first.withDayOfMonth(day))
                    }
                }
            }
            for (d in dates) {
                val occ = d.atTime(time)
                if (occ.isBefore(o.dtStart)) continue
                untilInstant?.let { if (occ.toInstant(o.from).isAfter(it)) return out }
                untilLocal?.let { if (occ.isAfter(it)) return out }
                out.add(occ)
                require(out.size <= MAX_ONSETS) { "more than $MAX_ONSETS onsets" }
                if (++emitted >= count) return out
            }
        }
        return out
    }

    private fun monthDayMatches(d: LocalDate, mds: List<Int>): Boolean {
        val len = d.lengthOfMonth()
        return mds.any { (if (it > 0) it else len + 1 + it) == d.dayOfMonth }
    }

    private fun weekdayMatches(d: LocalDate, byDay: List<Pair<Int, Int>>): Boolean =
        byDay.any { it.second == d.dayOfWeek.value }

    private fun intList(v: String?, min: Int, max: Int, negativeAllowed: Boolean): List<Int>? {
        if (v == null) return null
        return v.split(",").map { s ->
            val n = s.trim().toInt()
            val a = Math.abs(n)
            require(n != 0 && a >= min && a <= max && (n > 0 || negativeAllowed)) { "bad value $v" }
            n
        }
    }

    private fun byDayList(v: String?): List<Pair<Int, Int>>? {
        if (v == null) return null
        return v.split(",").map { s ->
            val m = BYDAY_ITEM.matchEntire(s.trim()) ?: throw IllegalArgumentException("bad BYDAY $v")
            val ordText = m.groupValues[1]
            val ord = if (ordText.isEmpty()) 0 else ordText.toInt()
            require(!(ord == 0 && ordText.isNotEmpty())) { "bad BYDAY $v" }
            require(Math.abs(ord) <= 5) { "bad BYDAY ordinal $v" }
            ord to (DAY_CODES.indexOf(m.groupValues[2]) + 1)
        }
    }

    /** All onsets through [HORIZON_YEAR], sorted, de-duplicated and continuity-checked. */
    private fun onsets(observances: List<Observance>): List<Onset> {
        val all = ArrayList<Onset>()
        for (o in observances) {
            val locals = TreeSet<LocalDateTime>()
            locals.add(o.dtStart)
            if (o.rrule != null) locals.addAll(expandRrule(o, HORIZON_YEAR))
            locals.addAll(o.rdates)
            for (l in locals) {
                if (l.year > HORIZON_YEAR) continue
                all.add(Onset(l.toEpochSecond(o.from), o.from, o.to, l))
                require(all.size <= MAX_ONSETS) { "more than $MAX_ONSETS onsets" }
            }
        }
        all.sortBy { it.epochSecond }
        val out = ArrayList<Onset>()
        for (o in all) {
            val prev = out.lastOrNull()
            if (prev != null) {
                if (prev.epochSecond == o.epochSecond) {
                    require(prev.to == o.to && prev.from == o.from) { "two onsets at one instant disagree" }
                    continue   // the same onset listed twice
                }
                require(prev.to == o.from) { "discontinuity at ${o.local}" }
            }
            out.add(o)
        }
        require(out.isNotEmpty()) { "no onsets" }
        return out
    }
}

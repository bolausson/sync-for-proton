package me.proton.sync.calendar

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.zone.ZoneOffsetTransition
import java.time.zone.ZoneOffsetTransitionRule
import java.time.zone.ZoneRules
import java.util.Locale

/**
 * A VTIMEZONE (RFC 5545 3.6.5) for an invitation, built from the phone's own time-zone rules.
 *
 * It used to be one template with the EU's rules (last Sunday of March and October) for every zone,
 * which was wrong for 169 of 604 zones: New York and Los Angeles for four weeks a year, Sydney and
 * Auckland for half the year (their summer is in our winter), London and every EET zone by an hour at
 * the switch. Google Calendar and Proton read only the TZID name, so they never showed it; Outlook and
 * Apple Calendar apply the definition.
 *
 * A zone that no longer has summer time is written as STANDARD blocks only (see below).
 *
 * The recurrence of each rule is read from what the rule actually produces over 28 years (a full
 * weekday and leap-year cycle), never from how the rule is encoded, because Android's and the JDK's
 * providers encode the same rule differently ("last Sunday" as day -1 or as day 25). A rule no RRULE
 * can express (Egypt: last Thursday at 24:00, which can fall on 1 November) is listed as RDATEs.
 * History since the year before the event is included as dated transitions, so an event in the past
 * or near a rule change is still defined correctly.
 *
 * Pure and non-throwing: any failure gives null, and the invitation is then sent without a
 * definition rather than not at all.
 */
internal object VTimezoneBuilder {
    private const val CR = "\r\n"
    private val DAY_CODES = arrayOf("MO", "TU", "WE", "TH", "FR", "SA", "SU")

    fun build(tzId: String, fromMillis: Long, rules: ZoneRules? = null): String? = try {
        buildOrThrow(tzId, fromMillis, rules ?: ZoneId.of(tzId).rules)
    } catch (_: Exception) {
        null
    }

    private fun buildOrThrow(tzId: String, fromMillis: Long, rules: ZoneRules): String {
        val fromYear = Instant.ofEpochMilli(fromMillis).atZone(ZoneOffset.UTC).year - 1
        val fromInstant = LocalDate.of(fromYear, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant()
        val history = rules.transitions
        var start = history.indexOfLast { !it.instant.isAfter(fromInstant) }
        if (start < 0) start = 0
        val body = StringBuilder()

        // Dated transitions from the year before the event on, one block per kind of change.
        val groups = LinkedHashMap<Triple<ZoneOffset, ZoneOffset, Boolean>, MutableList<ZoneOffsetTransition>>()
        for (i in start until history.size) {
            val t = history[i]
            groups.getOrPut(Triple(t.offsetBefore, t.offsetAfter, rules.isDaylightSavings(t.instant))) { ArrayList() }.add(t)
        }
        // A zone with no rule left for the future has no summer time any more, so every block of its history
        // is written as STANDARD. Several zones (Istanbul, Amman, Damascus, Whitehorse, Asuncion and 16 more)
        // made their summer offset permanent by moving the standard offset, which is no wall-clock transition:
        // the last dated change then keeps its DAYLIGHT label for ever, and a reader that takes the latest
        // DAYLIGHT and STANDARD blocks for a yearly rule (Outlook, MS-OXCICAL, which ignores RDATE) invents
        // summer time the zone no longer has. The offsets and dates are what every other reader uses, and they
        // are unchanged; only the label is.
        val keepsDst = rules.transitionRules.isNotEmpty()
        for ((key, ts) in groups) {
            val kind = if (key.third && keepsDst) "DAYLIGHT" else "STANDARD"
            body.append("BEGIN:$kind$CR")
                .append("TZOFFSETFROM:${offset(ts[0].offsetBefore)}$CR")
                .append("TZOFFSETTO:${offset(ts[0].offsetAfter)}$CR")
                .append("DTSTART:${stamp(ts[0].dateTimeBefore)}$CR")
            for (i in 1 until ts.size) body.append("RDATE:${stamp(ts[i].dateTimeBefore)}$CR")
            body.append("END:$kind$CR")
        }

        // The recurring rules that apply after the last dated transition.
        val lastExplicit = history.lastOrNull()
        for (rule in rules.transitionRules) {
            var year = lastExplicit?.dateTimeAfter?.year ?: fromYear
            var first = rule.createTransition(year)
            if (lastExplicit != null && !first.instant.isAfter(lastExplicit.instant)) first = rule.createTransition(++year)
            val kind = if (rule.offsetAfter != rule.standardOffset) "DAYLIGHT" else "STANDARD"
            body.append("BEGIN:$kind$CR")
                .append("TZOFFSETFROM:${offset(rule.offsetBefore)}$CR")
                .append("TZOFFSETTO:${offset(rule.offsetAfter)}$CR")
                .append("DTSTART:${stamp(first.dateTimeBefore)}$CR")
            val by = recurrenceOf(rule, year)
            if (by != null) {
                body.append("RRULE:FREQ=YEARLY;$by$CR")
            } else {
                for (y in year + 1..maxOf(2100, year + 60)) body.append("RDATE:${stamp(rule.createTransition(y).dateTimeBefore)}$CR")
            }
            body.append("END:$kind$CR")
        }

        // A zone that never changes.
        if (body.isEmpty()) {
            val o = offset(rules.getOffset(Instant.ofEpochMilli(fromMillis)))
            body.append("BEGIN:STANDARD${CR}TZOFFSETFROM:$o${CR}TZOFFSETTO:$o${CR}DTSTART:19700101T000000${CR}END:STANDARD$CR")
        }
        return "BEGIN:VTIMEZONE${CR}TZID:$tzId${CR}X-LIC-LOCATION:$tzId$CR$body" + "END:VTIMEZONE$CR"
    }

    /** The BYxxx part that reproduces the rule's local onset dates for 28 years, or null. */
    internal fun recurrenceOf(rule: ZoneOffsetTransitionRule, firstYear: Int): String? {
        val onsets: List<LocalDateTime> = (0 until 28).map { rule.createTransition(firstYear + it).dateTimeBefore }
        val time = onsets[0].toLocalTime()
        if (onsets.any { it.toLocalTime() != time }) return null
        val days = onsets.map { it.toLocalDate() }
        val month = days[0].month
        if (days.any { it.month != month }) return null
        val byMonth = "BYMONTH=${month.value};"
        val d0 = days[0].dayOfMonth
        if (days.all { it.dayOfMonth == d0 }) return byMonth + "BYMONTHDAY=$d0"
        val weekday: DayOfWeek = days[0].dayOfWeek
        if (days.any { it.dayOfWeek != weekday }) return null
        val code = DAY_CODES[weekday.value - 1]
        val nth = (d0 - 1) / 7
        if (nth <= 3 && days.all { (it.dayOfMonth - 1) / 7 == nth }) return byMonth + "BYDAY=${nth + 1}$code"
        if (days.all { it.dayOfMonth > it.lengthOfMonth() - 7 }) return byMonth + "BYDAY=-1$code"
        val lo = days.minOf { it.dayOfMonth }
        val hi = days.maxOf { it.dayOfMonth }
        if (hi - lo <= 6 && lo + 6 <= month.minLength()) return byMonth + "BYMONTHDAY=${(lo..lo + 6).joinToString(",")};BYDAY=$code"
        val fromEnd = days.map { it.dayOfMonth - it.lengthOfMonth() - 1 }
        val nlo = fromEnd.min()
        val nhi = fromEnd.max()
        if (nhi - nlo <= 6 && nlo + 6 <= -1) return byMonth + "BYMONTHDAY=${(nlo..nlo + 6).joinToString(",")};BYDAY=$code"
        return null
    }

    private fun offset(o: ZoneOffset): String {
        val total = o.totalSeconds
        val abs = Math.abs(total)
        val sign = if (total < 0) "-" else "+"
        val base = String.format(Locale.ROOT, "%s%02d%02d", sign, abs / 3600, (abs % 3600) / 60)
        return if (abs % 60 != 0) base + String.format(Locale.ROOT, "%02d", abs % 60) else base
    }

    private fun stamp(t: LocalDateTime): String =
        String.format(Locale.ROOT, "%04d%02d%02dT%02d%02d%02d", t.year, t.monthValue, t.dayOfMonth, t.hour, t.minute, t.second)

    /** Every TZID an invitation's lines name: its own zone, and those of its EXDATE and RECURRENCE-ID lines. */
    fun tzidsOf(eventTimezone: String, allDay: Boolean, vararg lines: String?): List<String> {
        val out = LinkedHashSet<String>()
        if (!allDay && eventTimezone != "UTC") out.add(eventTimezone)
        val re = Regex("TZID=([^:;\\r\\n]+)")
        for (line in lines) if (line != null) re.findAll(line).forEach { out.add(it.groupValues[1].trim('"')) }
        out.remove("UTC")
        return out.toList()
    }
}

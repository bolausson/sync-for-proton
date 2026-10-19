package me.proton.sync.calendar

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.zone.ZoneRules

/*
 * Event times read from the iCal TEXT, never from ical4j's computed values.
 *
 * Why: ical4j 3.2.14 binds every TZID to its own VTIMEZONE (Proton never sends one), from its bundled
 * tzdb 2022g, and misreads some current definitions outright (America/Vancouver from 2026-11-01 nine hours
 * early, Lord Howe, Troll, Darwin, Fiji, Efate). Since 1.11.5 every instant the download stores comes from
 * java.time, computed from the property's own text: its TZID, its VALUE type and its local value. See
 * DownloadTimes for the per-column rules.
 *
 * Everything in this file is pure (no Android types), so the JVM tests pin it exactly.
 */

/**
 * One content line split into its name, parameters and value (RFC 5545 3.1), the way a reader that
 * computes nothing sees it. The name and parameter names are upper-cased, parameter values lose their
 * quotes, and a ';' or ':' inside a quoted parameter value does not split anything. The first of two
 * equal parameters wins, as ical4j's getParameter does.
 */
internal class IcalPropLine private constructor(
    val name: String,
    val params: Map<String, String>,
    val value: String,
    /** The whole line, unfolded and trimmed: what a cache stores. */
    val text: String
) {
    fun param(name: String): String? = params[name]

    override fun toString(): String = text

    companion object {
        fun parse(line: String?): IcalPropLine? {
            val text = line?.trim() ?: return null
            if (text.isEmpty()) return null
            var quoted = false
            var colon = -1
            val semicolons = ArrayList<Int>()
            var i = 0
            while (i < text.length && colon < 0) {
                when (text[i]) {
                    '"' -> quoted = !quoted
                    ';' -> if (!quoted) semicolons.add(i)
                    ':' -> if (!quoted) colon = i
                }
                i++
            }
            if (colon <= 0) return null
            val name = text.substring(0, semicolons.firstOrNull() ?: colon).trim().uppercase()
            if (name.isEmpty()) return null
            val params = LinkedHashMap<String, String>()
            val bounds = semicolons + colon
            for (k in 0 until bounds.size - 1) {
                val p = text.substring(bounds[k] + 1, bounds[k + 1])
                val eq = p.indexOf('=')
                if (eq <= 0) continue
                val key = p.substring(0, eq).trim().uppercase()
                var v = p.substring(eq + 1).trim()
                if (v.length >= 2 && v.startsWith('"') && v.endsWith('"')) v = v.substring(1, v.length - 1)
                params.putIfAbsent(key, v)
            }
            return IcalPropLine(name, params, text.substring(colon + 1).trim(), text)
        }
    }
}

/** A DATE or DATE-TIME value of DTSTART, DTEND, RECURRENCE-ID, EXDATE or RDATE (RFC 5545 3.3.4, 3.3.5). */
internal sealed class IcalTimeValue {
    /** VALUE=DATE: a calendar day. Android stores it as that day's UTC midnight, whatever the phone's zone. */
    data class Day(val date: LocalDate) : IcalTimeValue()

    /** A DATE-TIME ending in Z: an exact instant. Z wins over a TZID, as in ical4j. */
    data class Utc(val instant: Instant) : IcalTimeValue()

    /** A local DATE-TIME with a TZID: needs that zone's rules. */
    data class Zoned(val local: LocalDateTime, val tzid: String) : IcalTimeValue()

    /** A local DATE-TIME without a TZID: the phone's own zone when the sync runs (RFC 5545 "floating"). */
    data class Floating(val local: LocalDateTime) : IcalTimeValue()
}

internal object IcalTimeValues {
    private val DATE = Regex("\\d{8}")
    private val DATE_TIME = Regex("\\d{8}T\\d{6}Z?")
    // STRICT: an impossible date such as 20261131 is no value at all (SMART would quietly make it the 30th),
    // so the event goes to ical4j as before rather than to a time nobody wrote. STRICT needs "uuuu", not "yyyy".
    private val DATE_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("uuuuMMdd").withResolverStyle(java.time.format.ResolverStyle.STRICT)
    val LOCAL_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss").withResolverStyle(java.time.format.ResolverStyle.STRICT)

    /**
     * Every value of a date property, in order, or null when any of them is not one of RFC 5545's DATE or
     * DATE-TIME forms. Null also for VALUE=PERIOD and any other VALUE type, and for a bare 8-digit value
     * without VALUE=DATE: ical4j makes that a timed event at 00:00Z, a shape this reader leaves to ical4j
     * rather than imitate. Empty items between commas are skipped, as the EXDATE column always did.
     */
    fun parse(line: IcalPropLine): List<IcalTimeValue>? {
        val type = line.param("VALUE")?.trim()?.uppercase()
        val tzid = line.param("TZID")?.trim()?.takeIf { it.isNotEmpty() }
        val items = line.value.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (items.isEmpty()) return null
        val out = ArrayList<IcalTimeValue>(items.size)
        for (item in items) {
            val v = when (type) {
                "DATE" -> if (DATE.matches(item)) day(item) else null
                null, "DATE-TIME" -> if (DATE_TIME.matches(item)) dateTime(item, tzid) else null
                else -> null
            } ?: return null
            out.add(v)
        }
        return out
    }

    /** The one value of a single-valued property (DTSTART, DTEND, RECURRENCE-ID), or null. */
    fun single(line: IcalPropLine): IcalTimeValue? = parse(line)?.singleOrNull()

    private fun day(v: String): IcalTimeValue? = try {
        IcalTimeValue.Day(LocalDate.parse(v, DATE_FORMAT))
    } catch (_: Exception) {
        null
    }

    private fun dateTime(v: String, tzid: String?): IcalTimeValue? = try {
        val local = LocalDateTime.parse(v.removeSuffix("Z"), LOCAL_FORMAT)
        when {
            v.endsWith("Z") -> IcalTimeValue.Utc(local.toInstant(ZoneOffset.UTC))
            tzid != null -> IcalTimeValue.Zoned(local, tzid)
            else -> IcalTimeValue.Floating(local)
        }
    } catch (_: Exception) {
        null
    }

    fun formatLocal(local: LocalDateTime): String = LOCAL_FORMAT.format(local)
}

/** RFC 5545 DURATION values (3.3.6). */
internal object IcalDurations {
    // A "T" is optional before the time part because Android's own DURATION column, which this also reads,
    // is written "P3600S" (CalendarMapper) and AOSP reads that form.
    private val FORM = Regex("([+-])?P(?:(\\d+)W)?(?:(\\d+)D)?T?(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?")

    /**
     * The duration in seconds, days and weeks counted as exactly 86400 and 604800 s: ical4j adds a DURATION
     * to the start instant (Instant.plus), so P1D across a clock change is 24 hours, not one calendar day.
     * Null when [value] is not a duration or names no amount at all.
     */
    fun seconds(value: String?): Long? {
        val v = value?.trim()?.uppercase() ?: return null
        val m = FORM.matchEntire(v) ?: return null
        val g = m.groupValues
        if ((2..6).all { g[it].isEmpty() }) return null
        fun n(i: Int) = if (g[i].isEmpty()) 0L else g[i].toLong()
        val total = n(2) * 604_800 + n(3) * 86_400 + n(4) * 3_600 + n(5) * 60 + n(6)
        return if (g[1] == "-") -total else total
    }
}

/**
 * Where a zone's rules come from. [standalone] is true for an instant Android stores and shows as it is (a
 * one-off event's start and end, an override's own start and end), and false for one it expands itself
 * (a series' start, duration and exclusions, an override's ORIGINAL_INSTANCE_TIME), which must follow the
 * phone's own rules: CalendarProvider expands a series with the phone's data and matches an exception by
 * the exact millisecond. [local] is the wall-clock time being converted, for rule sources that only cover
 * some years.
 */
internal fun interface ZoneRulesLookup {
    fun rulesFor(zone: ZoneId, standalone: Boolean, local: LocalDateTime): ZoneRules

    companion object {
        /** The phone's own rules for everything (java.time, i.e. the phone's ICU tz data on Android). */
        val PHONE = ZoneRulesLookup { zone, _, _ -> zone.rules }
    }
}

internal object LocalTimes {
    /**
     * The instant of [local] under [rules].
     *
     * In a gap (spring forward) the time is read with the offset before the gap, i.e. moved forward by the
     * gap's length, whichever caller asks: ZonedDateTime.ofLocal, RFC 5545 (3.3.5) and Android's
     * GregorianCalendar all agree there.
     *
     * In an overlap (fall back) they do not:
     *  - [providerExpands] false: the first occurrence, the earlier instant (RFC 5545, ofLocal). For times
     *    Android stores and shows as they are: a one-off event's start and end, an override's own.
     *  - [providerExpands] true: the offset AFTER the transition, the later instant. For times CalendarProvider
     *    computes itself when it expands a series (a series' start and length, an override's
     *    ORIGINAL_INSTANCE_TIME, a cancelled EXDATE row): it turns each occurrence's wall time into an instant
     *    with calendarcommon2.Time, i.e. java.util.GregorianCalendar, which looks the offset up at wall time
     *    minus the zone's raw offset and so lands after a fall-back transition. An exception is linked to its
     *    occurrence only at the exact millisecond, so the earlier instant would leave a cancelled occurrence
     *    showing and an edited one doubled. ical4j (every version before 1.11.5) also went through
     *    GregorianCalendar and wrote the later instant. Measured: for every transition from 2026 on, in every
     *    zone, this equals Android's GregorianCalendar in both gaps and overlaps; only past overlaps in zones
     *    whose standard offset has changed since (Istanbul before 2016, Nuuk before 2023) differ.
     */
    fun toInstant(local: LocalDateTime, rules: ZoneRules, providerExpands: Boolean = false): Instant {
        val valid = rules.getValidOffsets(local)
        if (valid.size == 1) return local.toInstant(valid[0])
        // Not one valid offset means a transition; the fallback is never reached.
        val transition = rules.getTransition(local) ?: return local.toInstant(valid.firstOrNull() ?: ZoneOffset.UTC)
        return when {
            transition.isGap -> local.toInstant(transition.offsetBefore)
            providerExpands -> local.toInstant(transition.offsetAfter)
            else -> local.toInstant(transition.offsetBefore)
        }
    }

    /** [instant] as a local "yyyyMMdd'T'HHmmss" value under [rules]. */
    fun formatLocal(instant: Instant, rules: ZoneRules): String =
        IcalTimeValues.formatLocal(LocalDateTime.ofInstant(instant, rules.getOffset(instant)))
}

/** A TZID resolved to a zone this phone knows: the id written to EVENT_TIMEZONE, and the zone. */
internal class ResolvedZone(val columnId: String, val zone: ZoneId)

internal object EventZones {
    /** UTC under its other names. Written as "UTC", which is also how the upload spells it. */
    private val UTC_NAMES = setOf(
        "UTC", "ETC/UTC", "GMT", "ETC/GMT", "UCT", "ETC/UCT", "UNIVERSAL", "ETC/UNIVERSAL", "ZULU", "ETC/ZULU",
        "GMT0", "ETC/GMT0", "GMT+0", "GMT-0", "ETC/GMT+0", "ETC/GMT-0", "GREENWICH", "ETC/GREENWICH"
    )

    /**
     * Zones IANA renamed, so a phone with older data knows them only by their old name. Both names have the
     * same rules (the old one is a backward link), unlike ProtonTimezones.UNSUPPORTED_LINKS, which maps zones
     * Proton's API refuses to a DIFFERENT zone (America/Godthab to Atlantic/Stanley) and must never be used to
     * compute a time.
     */
    private val RENAMED = mapOf(
        "Europe/Kyiv" to "Europe/Kiev",
        "Pacific/Kanton" to "Pacific/Enderbury",
        "America/Nuuk" to "America/Godthab",
    )

    /** Whether [a] and [b] are one zone under its old and new name (an IANA rename, either way round). */
    fun isRename(a: String, b: String): Boolean = RENAMED[a] == b || RENAMED[b] == a

    private val available: Set<String> by lazy { ZoneId.getAvailableZoneIds().toHashSet() }

    /** Whether this phone's tz data has a region called [id]: never an offset id such as "GMT+02:00". */
    fun isKnown(id: String): Boolean = id in available

    /**
     * [tzid] resolved to a zone this phone knows, or null. Tried in this order: the TZID itself; the zone
     * name at the end of a prefixed id such as "/mozilla.org/20050126_1/Europe/Berlin" (ical4j's relaxed
     * rule); then the old name of a renamed zone. The column keeps the TZID's own spelling whenever the phone
     * knows it, so "US/Pacific" stays "US/Pacific" (the upload normalises it, ICalUtils.normalizeTimezone).
     *
     * Deliberately NOT ICalUtils.normalizeTimezone: that collapses "Etc/GMT+5" and "GMT+02:00" into UTC for
     * Proton's API, which would move such an event by hours. Null means unknown: the caller leaves the event
     * to ical4j, as before 1.11.5.
     */
    fun resolve(tzid: String, known: (String) -> Boolean = ::isKnown): ResolvedZone? {
        val id = tzid.trim().removeSurrounding("\"").trim()
        if (id.isEmpty()) return null
        if (id.uppercase() in UTC_NAMES) return ResolvedZone("UTC", ZoneOffset.UTC)
        for (candidate in candidates(id)) {
            if (!known(candidate)) continue
            val zone = try { ZoneId.of(candidate) } catch (_: Exception) { continue }
            return ResolvedZone(candidate, zone)
        }
        return null
    }

    private fun candidates(id: String): List<String> {
        val out = ArrayList<String>()
        out.add(id)
        val segments = id.split('/').filter { it.isNotEmpty() }
        if (id.startsWith("/") || segments.size > 3) {
            if (segments.size >= 3) out.add(segments.takeLast(3).joinToString("/"))
            if (segments.size >= 2) out.add(segments.takeLast(2).joinToString("/"))
        }
        for (c in out.toList()) RENAMED[c]?.let { out.add(it) }
        return out.distinct()
    }
}

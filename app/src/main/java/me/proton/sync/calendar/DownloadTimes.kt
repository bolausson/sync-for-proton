package me.proton.sync.calendar

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * The time properties of an event's first VEVENT, read from the text the sync actually parsed.
 *
 * Mirrors what ical4j's getProperty and getProperties return: the FIRST DTSTART, DTEND, DURATION,
 * RECURRENCE-ID and RDATE (a merged Proton event repeats properties across its parts and ical4j takes the
 * first), and EVERY EXDATE. Only the VEVENT's own properties count: a nested block (VALARM, or a VTIMEZONE
 * a part carried along) is skipped, because its DTSTART lines are rules, not the event's start.
 */
internal class RawEventTimes(
    val dtStart: IcalPropLine?,
    val dtEnd: IcalPropLine?,
    val duration: IcalPropLine?,
    val recurrenceId: IcalPropLine?,
    val rdate: IcalPropLine?,
    val exdates: List<IcalPropLine>,
    val hasRrule: Boolean
) {
    /** A series in Android's sense: the provider expands it (RRULE or RDATE). */
    val isSeries: Boolean get() = hasRrule || rdate != null

    companion object {
        /** RFC 5545 3.1 unfolding, LF or CRLF, as CalendarDecryptor.mergeParts and ical4j's relaxed reader do. */
        private val FOLD = Regex("\r?\n[ \t]")

        /** The first VEVENT's time properties, or null when [ics] has no VEVENT. */
        fun scan(ics: String): RawEventTimes? {
            var inEvent = false
            var found = false
            var nested = 0
            var dtStart: IcalPropLine? = null
            var dtEnd: IcalPropLine? = null
            var duration: IcalPropLine? = null
            var recurrenceId: IcalPropLine? = null
            var rdate: IcalPropLine? = null
            var hasRrule = false
            val exdates = ArrayList<IcalPropLine>()
            for (raw in ics.replace(FOLD, "").lines()) {
                val line = raw.trim()
                if (line.isEmpty()) continue
                val upper = line.uppercase()
                if (upper.startsWith("BEGIN:")) {
                    if (inEvent) nested++
                    else if (!found && upper.substring(6).trim() == "VEVENT") { inEvent = true; found = true }
                    continue
                }
                if (upper.startsWith("END:")) {
                    if (inEvent) {
                        if (nested > 0) nested-- else if (upper.substring(4).trim() == "VEVENT") inEvent = false
                    }
                    continue
                }
                if (!inEvent || nested > 0) continue
                val prop = IcalPropLine.parse(line) ?: continue
                when (prop.name) {
                    "DTSTART" -> if (dtStart == null) dtStart = prop
                    "DTEND" -> if (dtEnd == null) dtEnd = prop
                    "DURATION" -> if (duration == null) duration = prop
                    "RECURRENCE-ID" -> if (recurrenceId == null) recurrenceId = prop
                    "RDATE" -> if (rdate == null) rdate = prop
                    "EXDATE" -> exdates.add(prop)
                    "RRULE" -> hasRrule = true
                }
            }
            if (!found) return null
            return RawEventTimes(dtStart, dtEnd, duration, recurrenceId, rdate, exdates, hasRrule)
        }
    }
}

/**
 * Every time column the download writes for one event, computed with java.time from the raw text
 * ([DownloadTimes.compute]). CalendarMapper only copies these into ContentValues.
 */
internal data class EventTimeColumns(
    /** Events.DTSTART, null when the event has no DTSTART (never the case for a Proton event). */
    val dtStart: Long?,
    /** Events.EVENT_TIMEZONE, written whenever [dtStart] is. */
    val eventTimezone: String?,
    val allDay: Boolean,
    /** Events.DTEND; null when there is no end at all or a series carries it as [duration] instead. */
    val dtEnd: Long?,
    /** Events.EVENT_END_TIMEZONE, with [dtEnd]. */
    val endTimezone: String?,
    /** Events.DURATION: "P<seconds>S" for a series, or the DURATION value of an event without a start. */
    val duration: String?,
    /** RECURRENCE-ID as ORIGINAL_INSTANCE_TIME, null without one. */
    val originalInstanceTime: Long?,
    /** RECURRENCE-ID;VALUE=DATE: ORIGINAL_ALL_DAY. */
    val originalAllDay: Boolean,
    /** Events.EXDATE: every EXDATE value as Proton wrote it, de-duplicated, in order, without a TZID. */
    val exdateColumn: String?,
    /** Events.RDATE: the first RDATE's values as written (a PERIOD RDATE keeps ical4j's text, see below). */
    val rdateColumn: String?,
    /** ORIGINAL_INSTANCE_TIME of the cancelled rows a series' EXDATEs become, de-duplicated, in order. */
    val exdateInstants: List<Long>,
    /** EVENT_TIMEZONE of those cancelled rows: the series' own zone, or UTC. */
    val exdateRowTimezone: String,
    /** The RECURRENCE-ID line as Proton sent it, for cached_recurrence_id. */
    val recurrenceIdLine: String?,
    /** True when the start and end were "standalone" (one-off event or override), false for a series. */
    val standalone: Boolean
)

internal object DownloadTimes {

    /** The raw text cannot be read with certainty; the event keeps ical4j's times, as before 1.11.5. */
    class Unevaluable(message: String) : Exception(message)

    private const val DAY_MS = 86_400_000L

    /**
     * Compute every time column of one event from [raw], with today's semantics kept exactly except where
     * they were ical4j's arithmetic:
     *  - VALUE=DATE: all-day, stored as the date's UTC midnight, zone "UTC", whatever the phone's zone;
     *  - a trailing Z: that instant, zone "UTC" (Z wins over a TZID);
     *  - a TZID: the local time under that zone's rules ([rules]), zone = the TZID as the phone knows it;
     *  - no TZID (floating): the local time in [deviceZone] with the phone's rules, zone "UTC";
     *  - no DTEND: DTSTART plus DURATION as exact seconds, else +0 for a timed and +1 day for an all-day
     *    event, in DTSTART's zone (ical4j's getEndDate(true));
     *  - a series with an end and no DURATION property stores DURATION "P<end-start>S" instead of DTEND;
     *  - RECURRENCE-ID and EXDATE instants always use the phone's rules (the provider expands with them).
     *
     * A wall time that occurs twice (a fall-back overlap) is read the way its consumer reads it
     * ([LocalTimes.toInstant]): the first occurrence for a one-off event's or an override's own start and end
     * (RFC 5545), the later one for everything CalendarProvider expands itself and matches by the millisecond
     * (a series' start and end, RECURRENCE-ID, EXDATE), as its GregorianCalendar does and ical4j did.
     *
     * Throws [Unevaluable] for anything it cannot read with certainty: a value that is not RFC 5545's DATE or
     * DATE-TIME, a TZID this phone does not know, a multi-valued DTSTART. [periodRdate] is ical4j's text of a
     * VALUE=PERIOD RDATE, kept as before (it has no TZID-free text of its own).
     */
    fun compute(
        raw: RawEventTimes,
        rules: ZoneRulesLookup,
        deviceZone: ZoneId,
        periodRdate: String? = null,
        resolve: (String) -> ResolvedZone? = { EventZones.resolve(it) }
    ): EventTimeColumns {
        val standalone = !raw.isSeries
        fun instantOf(v: IcalTimeValue, standaloneScope: Boolean): Instant = when (v) {
            is IcalTimeValue.Day -> v.date.atStartOfDay(ZoneOffset.UTC).toInstant()
            is IcalTimeValue.Utc -> v.instant
            is IcalTimeValue.Floating -> LocalTimes.toInstant(v.local, deviceZone.rules, providerExpands = !standaloneScope)
            is IcalTimeValue.Zoned -> {
                val zone = resolve(v.tzid) ?: throw Unevaluable("unknown time zone '${v.tzid}'")
                LocalTimes.toInstant(
                    v.local, rules.rulesFor(zone.zone, standaloneScope, v.local), providerExpands = !standaloneScope
                )
            }
        }
        fun zoneColumn(v: IcalTimeValue): String = when (v) {
            is IcalTimeValue.Zoned -> resolve(v.tzid)?.columnId ?: throw Unevaluable("unknown time zone '${v.tzid}'")
            else -> "UTC"
        }
        fun single(line: IcalPropLine): IcalTimeValue =
            IcalTimeValues.single(line) ?: throw Unevaluable("unreadable ${line.name}")

        val start = raw.dtStart?.let { single(it) }
        val allDay = start is IcalTimeValue.Day
        val startMillis = start?.let { instantOf(it, standalone).toEpochMilli() }
        val startZone = start?.let { if (allDay) "UTC" else zoneColumn(it) }

        val end = raw.dtEnd?.let { single(it) }
        var dtEnd: Long?
        var endZone: String?
        if (end != null) {
            val endInstant = instantOf(end, standalone)
            dtEnd = if (allDay) utcMidnightOf(end, endInstant) else endInstant.toEpochMilli()
            endZone = if (allDay) "UTC" else zoneColumn(end)
        } else if (start != null) {
            val plusSeconds = raw.duration?.let {
                IcalDurations.seconds(it.value) ?: throw Unevaluable("unreadable DURATION")
            } ?: if (allDay) 86_400L else 0L
            val derived = startMillis!! + plusSeconds * 1000
            dtEnd = if (allDay) Math.floorDiv(derived, DAY_MS) * DAY_MS else derived
            endZone = startZone
        } else {
            dtEnd = null
            endZone = null
        }

        var duration: String? = null
        if (raw.hasRrule && dtEnd != null && raw.duration == null && startMillis != null) {
            duration = "P${(dtEnd - startMillis) / 1000}S"
            dtEnd = null
            endZone = null
        } else if (start == null && end == null && raw.duration != null) {
            duration = raw.duration.value
        }

        // A DATE value is its UTC midnight (instantOf), as ORIGINAL_ALL_DAY occurrences are stored.
        val rid = raw.recurrenceId?.let { single(it) }
        val originalInstanceTime = rid?.let { instantOf(it, false).toEpochMilli() }

        val exdateInstants = LinkedHashSet<Long>()
        for (line in raw.exdates) {
            val values = IcalTimeValues.parse(line) ?: throw Unevaluable("unreadable EXDATE")
            for (v in values) {
                val instant = instantOf(v, false)
                exdateInstants.add(if (allDay) utcMidnightOf(v, instant) else instant.toEpochMilli())
            }
        }
        val exdateRowTimezone = if (allDay || start == null) "UTC" else zoneColumn(start)

        val exdateColumn = raw.exdates
            .flatMap { it.value.split(',') }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .takeIf { it.isNotEmpty() }
            ?.joinToString(",")

        val rdateColumn = raw.rdate?.let {
            if (it.param("VALUE")?.trim()?.uppercase() == "PERIOD") periodRdate else it.value
        }

        return EventTimeColumns(
            dtStart = startMillis,
            eventTimezone = startZone,
            allDay = allDay,
            dtEnd = dtEnd,
            endTimezone = endZone,
            duration = duration,
            originalInstanceTime = originalInstanceTime,
            originalAllDay = rid is IcalTimeValue.Day,
            exdateColumn = exdateColumn,
            rdateColumn = rdateColumn,
            exdateInstants = exdateInstants.toList(),
            exdateRowTimezone = exdateRowTimezone,
            recurrenceIdLine = raw.recurrenceId?.text,
            standalone = standalone
        )
    }

    /**
     * An all-day event's end or exclusion as Android stores it: the UTC midnight of the value's date, or of
     * the UTC date of a DATE-TIME given where a date belongs (what CalendarMapper.normalizeToUtcMidnight did).
     */
    private fun utcMidnightOf(v: IcalTimeValue, instant: Instant): Long = when (v) {
        is IcalTimeValue.Day -> v.date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        else -> Math.floorDiv(instant.toEpochMilli(), DAY_MS) * DAY_MS
    }

    /** Midnight UTC of [date], Android's representation of an all-day date. */
    fun utcMidnight(date: LocalDate): Long = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    /**
     * Whether the scan saw the same time properties ical4j parsed. A disagreement (a property ical4j's relaxed
     * parser dropped, a folding the scan read differently) means the scan cannot be trusted for this event,
     * which then keeps ical4j's times as before. Cheap, and it turns any scanner bug into the old behaviour
     * instead of a wrong time.
     */
    fun agreesWithIcal4j(raw: RawEventTimes, vEvent: net.fortuna.ical4j.model.component.VEvent): Boolean {
        fun has(name: String) = vEvent.getProperty<net.fortuna.ical4j.model.Property>(name) != null
        val exdates = vEvent.getProperties<net.fortuna.ical4j.model.Property>("EXDATE")?.size ?: 0
        return (raw.dtStart != null) == has("DTSTART") &&
            (raw.dtEnd != null) == has("DTEND") &&
            (raw.duration != null) == has("DURATION") &&
            (raw.recurrenceId != null) == has("RECURRENCE-ID") &&
            (raw.rdate != null) == has("RDATE") &&
            raw.hasRrule == has("RRULE") &&
            raw.exdates.size == exdates
    }
}

package me.proton.sync.calendar

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Decides, for every event already on the phone, whether its stored times still match what the download
 * would write now ([DownloadTimes] with the current rules), and what to do about a row that does not.
 *
 * Pure: the shell ([EventRetimePass]) reads the mappings, the provider rows and the cancelled placeholders,
 * and writes what this returns. The expected times come from the SERVER's own lines cached on the mapping
 * (cached_dtstart_line, cached_dtend_line: Proton's raw type-2 lines), computed with the same functions
 * and scope rules as the download, so a row the download would write identically is never touched, and a
 * second run with the same rules finds nothing (idempotent).
 *
 * Verdicts:
 *  - GONE: no live local row. HELD: a collision prompt or a "Keep mine" decision owns the event.
 *  - UNEVALUABLE: no cached start line (subscribed calendars carry their iCal elsewhere), an unreadable line,
 *    a zone this phone does not know, or ([Decision.unavailable]) rules that should be used but could not be
 *    had in this run. Never compared against fallback rules, so rows cannot flip-flop; the pass runs again
 *    for the unavailable ones (RetimeState).
 *  - RULE_FREE: all-day, UTC or floating: no zone rules involved, nothing to correct.
 *  - CONSISTENT: what the download would write now.
 *  - TAG: a clean row that differs: the next download re-applies it from Proton ([RETIME_TAG]).
 *  - LEAVE_LOCAL_CHANGE: a clean row whose shared fields differ from the last agreed state (an edit the
 *    upload gave up on): re-applying would silently revert it, so it is left alone and logged.
 *  - TAG_DIRTY: a dirty row that differs: only tagged, and nothing on the phone changes now. Its pending
 *    upload sends Proton's own time text for every time the user did not touch (UploadTimes), so it cannot
 *    move the event; once it has gone, the row is clean and the download re-applies it.
 *
 * A flagged series pulls in its overrides, whose ORIGINAL_INSTANCE_TIME was computed with the same rules.
 * A consistent clean series is also checked through its cancelled placeholders (an exclusion after a rule
 * change while the series started before it): every one must fall on the series' own wall-clock time.
 *
 * Until 1.11.5 an override's cached RECURRENCE-ID was ical4j's re-formatted value, which in the zones ical4j
 * misread is the misread instant again: it can neither confirm nor correct ORIGINAL_INSTANCE_TIME. Such an
 * override is downloaded again once ("legacy occurrence"), which stores Proton's own line.
 */
internal object EventRetimePlanner {

    /** calendar_event_sync.sync_tag of an event the next download must re-apply although Proton did not change it. */
    const val RETIME_TAG = "retime"

    class Mapping(
        val protonEventId: String,
        val protonCalendarId: String,
        val androidEventId: Long,
        val cachedDtStartLine: String?,
        val cachedDtEndLine: String?,
        val cachedUid: String?,
        val cachedRecurrenceId: String?,
        val sharedFingerprint: String?
    )

    class Row(
        val id: Long,
        val dtStart: Long?,
        val dtEnd: Long?,
        val duration: String?,
        val allDay: Boolean,
        val rrule: String?,
        val rdate: String?,
        val originalSyncId: String?,
        val originalInstanceTime: Long?,
        val dirty: Boolean,
        val deleted: Boolean,
        /** EventConflictGuard.SHARED_COLUMNS values as read, in that order. */
        val sharedValues: List<String?>,
        /** EventConflictGuard.fingerprintOf([sharedValues]). */
        val fingerprint: String
    ) {
        val isSeries: Boolean get() = !rrule.isNullOrEmpty() || !rdate.isNullOrEmpty()
    }

    enum class Verdict { GONE, HELD, UNEVALUABLE, RULE_FREE, CONSISTENT, TAG, LEAVE_LOCAL_CHANGE, TAG_DIRTY }

    class Decision(
        val mapping: Mapping,
        val verdict: Verdict,
        val reason: String = "",
        /** The event's zone, for the per-zone log. */
        val zone: String? = null,
        /** Stored start minus expected start, in minutes, when the start differs. */
        val deltaMinutes: Long? = null,
        /** UNEVALUABLE only because the rules it needs could not be had in this run: worth another run. */
        val unavailable: Boolean = false
    ) {
        val tags: Boolean get() = verdict == Verdict.TAG || verdict == Verdict.TAG_DIRTY
    }

    /**
     * Plan every mapping. [placeholders] maps a series' Proton id to the ORIGINAL_INSTANCE_TIME of its clean
     * cancelled placeholders (DIRTY = 0, no _SYNC_ID). [held] are Proton ids a collision prompt or a
     * "Keep mine" decision owns. [rules] is the sync run's rule source, the download's.
     */
    fun plan(
        mappings: List<Mapping>,
        rows: Map<Long, Row>,
        placeholders: Map<String, List<Long>>,
        held: Set<String>,
        rules: ZoneRulesLookup,
        /**
         * False when the rules for a time cannot be had right now although they should be used (ON, and the
         * online rules failed to load in this run): such a row is UNEVALUABLE, never compared against the
         * fallback, or rows would flip between rule sources with the network. Asked for the start's and, when it
         * names a zone, the end's zone.
         */
        available: (ZoneId, Boolean, LocalDateTime) -> Boolean = { _, _, _ -> true },
        resolve: (String) -> ResolvedZone? = { EventZones.resolve(it) }
    ): List<Decision> {
        val decisions = LinkedHashMap<String, Decision>()
        for (m in mappings) {
            decisions[m.protonEventId] = evaluate(m, rows[m.androidEventId], placeholders, held, rules, available, resolve)
        }
        // A flagged series pulls in its overrides: their ORIGINAL_INSTANCE_TIME came from the same rules. Linked
        // by the override row's ORIGINAL_SYNC_ID, or by UID within the calendar for an override not linked locally.
        val byParent = HashMap<String, MutableList<Mapping>>()
        val overridesByUid = HashMap<Pair<String, String>, MutableList<Mapping>>()
        for (m in mappings) {
            rows[m.androidEventId]?.originalSyncId?.let { byParent.getOrPut(it) { ArrayList() }.add(m) }
            if (m.cachedRecurrenceId != null && m.cachedUid != null) {
                overridesByUid.getOrPut(m.protonCalendarId to m.cachedUid) { ArrayList() }.add(m)
            }
        }
        val flaggedSeries = decisions.values.filter { d ->
            d.tags && rows[d.mapping.androidEventId]?.let { it.isSeries && it.originalSyncId == null } == true
        }
        for (series in flaggedSeries) {
            val linked = LinkedHashSet<Mapping>()
            byParent[series.mapping.protonEventId]?.let { linked.addAll(it) }
            series.mapping.cachedUid?.let { uid -> overridesByUid[series.mapping.protonCalendarId to uid]?.let { linked.addAll(it) } }
            for (m in linked) {
                if (m.protonEventId == series.mapping.protonEventId) continue
                val row = rows[m.androidEventId] ?: continue
                val current = decisions[m.protonEventId] ?: continue
                // Never an override whose own times need rules this run could not get: downloading it now would
                // write them with the fallback. The pass runs again for it once they can be had.
                if (current.unavailable) continue
                if (current.verdict != Verdict.CONSISTENT && current.verdict != Verdict.RULE_FREE &&
                    current.verdict != Verdict.UNEVALUABLE
                ) continue
                val verdict = when {
                    row.dirty -> Verdict.TAG_DIRTY
                    m.sharedFingerprint != null && row.fingerprint != m.sharedFingerprint -> Verdict.LEAVE_LOCAL_CHANGE
                    else -> Verdict.TAG
                }
                decisions[m.protonEventId] = Decision(m, verdict, "override of a series being re-timed", current.zone)
            }
        }
        return decisions.values.toList()
    }

    private fun evaluate(
        m: Mapping,
        r: Row?,
        placeholders: Map<String, List<Long>>,
        held: Set<String>,
        rules: ZoneRulesLookup,
        available: (ZoneId, Boolean, LocalDateTime) -> Boolean,
        resolve: (String) -> ResolvedZone?
    ): Decision {
        if (r == null || r.deleted) return Decision(m, Verdict.GONE)
        if (m.protonEventId in held) return Decision(m, Verdict.HELD)
        val startLine = IcalPropLine.parse(m.cachedDtStartLine)
            ?: return Decision(m, Verdict.UNEVALUABLE, "no cached start")
        val start = IcalTimeValues.single(startLine) ?: return Decision(m, Verdict.UNEVALUABLE, "unreadable cached start")
        val standalone = !r.isSeries
        val mismatches = ArrayList<String>()
        var zoneColumn: String? = null
        var delta: Long? = null
        var ownRuleFree = true

        if (!r.allDay && start is IcalTimeValue.Zoned) {
            val zone = resolve(start.tzid) ?: return Decision(m, Verdict.UNEVALUABLE, "unknown zone ${start.tzid}")
            if (zone.columnId != "UTC") {
                ownRuleFree = false
                zoneColumn = zone.columnId
                val stored = r.dtStart ?: return Decision(m, Verdict.UNEVALUABLE, "no stored start", zone.columnId)
                val endValue = IcalPropLine.parse(m.cachedDtEndLine)?.let { IcalTimeValues.single(it) }
                val endZone = (endValue as? IcalTimeValue.Zoned)?.let { resolve(it.tzid) }
                if (!available(zone.zone, standalone, start.local)) {
                    return Decision(m, Verdict.UNEVALUABLE, "rules for ${zone.columnId} unavailable", zone.columnId, unavailable = true)
                }
                if (endValue is IcalTimeValue.Zoned && endZone != null && !available(endZone.zone, standalone, endValue.local)) {
                    return Decision(m, Verdict.UNEVALUABLE, "rules for ${endZone.columnId} unavailable", zone.columnId, unavailable = true)
                }

                val expectedStart = LocalTimes.toInstant(
                    start.local, rules.rulesFor(zone.zone, standalone, start.local), providerExpands = !standalone
                )
                val expectedEnd: Instant? = when (endValue) {
                    is IcalTimeValue.Utc -> endValue.instant
                    is IcalTimeValue.Zoned -> endZone?.let {
                        LocalTimes.toInstant(endValue.local, rules.rulesFor(it.zone, standalone, endValue.local), providerExpands = !standalone)
                    }
                    else -> null
                }
                if (Math.floorDiv(stored, 1000L) != expectedStart.epochSecond) mismatches.add("start")
                if (!standalone) {
                    if (expectedEnd != null) {
                        val expectedSeconds = expectedEnd.epochSecond - expectedStart.epochSecond
                        val storedSeconds = IcalDurations.seconds(r.duration)
                        if (storedSeconds != null && storedSeconds != expectedSeconds) mismatches.add("duration")
                    }
                } else {
                    val end = expectedEnd ?: if (r.dtEnd == stored) expectedStart else null
                    if (end != null && r.dtEnd != null && Math.floorDiv(r.dtEnd, 1000L) != end.epochSecond) mismatches.add("end")
                }
                if ("start" in mismatches) delta = (stored - expectedStart.toEpochMilli()) / 60_000

                if (mismatches.isEmpty() && !standalone && !r.dirty && r.originalSyncId == null &&
                    !wallClockFixed(r.rrule) && placeholders[m.protonEventId].orEmpty().any { oit ->
                        val local = LocalDateTime.ofInstant(Instant.ofEpochMilli(oit), zone.zone.rules.getOffset(Instant.ofEpochMilli(oit)))
                        local.toLocalTime() != start.local.toLocalTime()
                    }
                ) {
                    mismatches.add("exclusion")
                }
            }
        }

        // An override's occurrence, whatever its own start is: computed with the phone's rules (the provider
        // expands the series with them), which can always be had.
        if (r.originalSyncId != null && r.originalInstanceTime != null) {
            when (val occurrence = occurrenceOf(m, rules, resolve)) {
                is Occurrence.At ->
                    if (Math.floorDiv(r.originalInstanceTime, 1000L) != Math.floorDiv(occurrence.millis, 1000L)) mismatches.add("occurrence")
                Occurrence.Legacy -> mismatches.add("legacy occurrence")
                Occurrence.Unknown -> {}
            }
        }

        if (mismatches.isEmpty()) return Decision(m, if (ownRuleFree) Verdict.RULE_FREE else Verdict.CONSISTENT, zone = zoneColumn)

        val reason = mismatches.joinToString("+")
        if (r.dirty) return Decision(m, Verdict.TAG_DIRTY, reason, zoneColumn, delta)
        val localChange = m.sharedFingerprint != null && r.fingerprint != m.sharedFingerprint
        return Decision(m, if (localChange) Verdict.LEAVE_LOCAL_CHANGE else Verdict.TAG, reason, zoneColumn, delta)
    }

    private sealed class Occurrence {
        class At(val millis: Long) : Occurrence()

        /** A bare local value an older version cached: ical4j's own text, not Proton's. */
        object Legacy : Occurrence()

        object Unknown : Occurrence()
    }

    /**
     * The ORIGINAL_INSTANCE_TIME an override's cached RECURRENCE-ID stands for, with the PHONE's rules and the
     * provider's reading of an ambiguous wall time (it expands the series with them and links by the
     * millisecond). The mapping holds the whole line since 1.11.5 (download and upload alike). Older downloads
     * cached ical4j's bare value: a date or a UTC value is still exact, a local one is [Occurrence.Legacy].
     */
    private fun occurrenceOf(m: Mapping, rules: ZoneRulesLookup, resolve: (String) -> ResolvedZone?): Occurrence {
        val cached = m.cachedRecurrenceId?.trim()?.takeIf { it.isNotEmpty() } ?: return Occurrence.Unknown
        val line = if (cached.uppercase().startsWith("RECURRENCE-ID")) {
            cached
        } else when {
            cached.length == 8 -> "RECURRENCE-ID;VALUE=DATE:$cached"
            cached.endsWith("Z") -> "RECURRENCE-ID:$cached"
            else -> return Occurrence.Legacy
        }
        val value = IcalPropLine.parse(line)?.let { IcalTimeValues.single(it) } ?: return Occurrence.Unknown
        return when (value) {
            is IcalTimeValue.Day -> Occurrence.At(DownloadTimes.utcMidnight(value.date))
            is IcalTimeValue.Utc -> Occurrence.At(value.instant.toEpochMilli())
            is IcalTimeValue.Zoned -> resolve(value.tzid)?.let {
                Occurrence.At(
                    LocalTimes.toInstant(value.local, rules.rulesFor(it.zone, false, value.local), providerExpands = true).toEpochMilli()
                )
            } ?: Occurrence.Unknown
            is IcalTimeValue.Floating -> Occurrence.Unknown
        }
    }

    /** A rule that sets its own hours or minutes: occurrences need not share the series' wall-clock time. */
    private fun wallClockFixed(rrule: String?): Boolean {
        val upper = rrule?.uppercase() ?: return false
        return "BYHOUR" in upper || "BYMINUTE" in upper || "BYSECOND" in upper
    }

    /** A short summary of [decisions] for the log: counts by verdict, and the top zones with their deltas. */
    fun summary(decisions: List<Decision>): String {
        val counts = decisions.groupingBy { it.verdict }.eachCount()
            .entries.sortedBy { it.key.ordinal }.joinToString(", ") { "${it.key.name.lowercase()} ${it.value}" }
        val unavailable = decisions.count { it.unavailable }
        val zones = decisions.filter { it.zone != null && (it.tags || it.verdict == Verdict.LEAVE_LOCAL_CHANGE) }
            .groupBy { it.zone!! }
            .entries.sortedByDescending { it.value.size }.take(10)
            .joinToString("; ") { (zone, ds) ->
                val deltas = ds.mapNotNull { it.deltaMinutes }.groupingBy { it }.eachCount()
                    .entries.sortedByDescending { it.value }.take(3)
                    .joinToString(",") { "${if (it.key > 0) "+" else ""}${it.key}min x${it.value}" }
                "$zone ${ds.size}" + if (deltas.isNotEmpty()) " ($deltas)" else ""
            }
        return counts + (if (unavailable > 0) " | rules unavailable for $unavailable" else "") +
            if (zones.isNotEmpty()) " | zones: $zones" else ""
    }
}

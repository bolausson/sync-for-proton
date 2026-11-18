package me.proton.sync.calendar

import net.fortuna.ical4j.model.DateTime
import net.fortuna.ical4j.model.Recur
import net.fortuna.ical4j.model.parameter.Value
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Which events of one large calendar may leave the phone because they ended before its sync range ("Remove older
 * events from this phone", a setting per calendar). Pure, so every rule is tested without a phone; RangeTrimPass reads
 * the rows and removes them, and nothing is ever written to Proton.
 *
 * Only whole series groups go. Android keeps a repeating event as one row, so a series stays whole, with every earlier
 * date, or goes whole with its single edits, Proton's cancelled edits and the cancelled-date placeholders the download
 * writes for each EXDATE. A group joins the rows of one calendar by their Proton id links (_SYNC_ID and
 * ORIGINAL_SYNC_ID) and by their iCal UID. It goes only when every row in it passes every test below; the reason it
 * stays is counted for the log. When in doubt it stays.
 *
 * Time, against the cutoff [Input.cutoffMillis] (local midnight a day before the range starts, [cutoffMillis]):
 *  - a series row (RRULE or RDATE) ends before it twice over: by its own last occurrence ([seriesEnd]: every rule line
 *    needs UNTIL or COUNT, COUNT is expanded, the latest RDATE counts, a day of margin is added) and by the provider's
 *    LAST_DATE, which must not be null. LAST_DATE alone is too early for long COUNT rules and finite for an endless
 *    rule with an RDATE; the own end alone trusts a computation the provider never sees.
 *  - any other row ends before it (DTEND, else DTSTART plus DURATION, else DTSTART), and so does its LAST_DATE when
 *    the provider set one.
 *  - a single edit also has its original date before it, and so has a cancelled one: ORIGINAL_INSTANCE_TIME, or for
 *    a row written before its series was known the mapping's cached RECURRENCE-ID. Unknown keeps the group.
 * Nothing waiting or undecided: no DIRTY or DELETED row; every mapped row's shared fields still match the mapping's
 * baseline (a missing baseline counts as changed, as the collision check reads it); no collision hold, "keep mine",
 * kept or pending deletion, queued guest cancellation or invitation work; nothing Proton changed in the last
 * [RECENT_SECONDS].
 * Backed by Proton: every mapped row's _SYNC_ID is its mapping's id and that id is in this run's all-time id list. A row
 * without a _SYNC_ID is one a calendar app deletes outright, with no tombstone, so the removal could not tell that
 * delete from its own and the user's deletion would never reach Proton; and AOSP's delete of a series row without one
 * also deletes every row whose ORIGINAL_ID names it, dirty ones included, which the removal's asserts cannot see. The
 * only unmapped rows allowed are the download's placeholders.
 * Proton's veto: no member is among the events this run's window returned.
 * At most [MAX_GROUP_ROWS] rows, so a group fits one selection.
 */
internal object RangeTrimPlanner {
    const val MAX_GROUP_ROWS = 400
    const val RECENT_SECONDS = 7L * 24 * 3600
    private const val DAY_MILLIS = 24L * 3600 * 1000

    /** One row of the calendar as the provider holds it. */
    data class Row(
        val id: Long,
        val syncId: String?,
        val originalSyncId: String?,
        val originalInstanceTime: Long?,
        val uid: String?,
        val dtStart: Long?,
        val dtEnd: Long?,
        val duration: String?,
        val rrule: String?,
        val rdate: String?,
        val eventTimezone: String?,
        val lastDate: Long?,
        val cancelled: Boolean,
        val dirty: Boolean,
        val deleted: Boolean,
        /** EventConflictGuard.fingerprintOf the row's shared columns. */
        val fingerprint: String,
        /** ALL_DAY: the provider expands the row on UTC's dates, whatever its EVENT_TIMEZONE. */
        val allDay: Boolean = false,
    )

    /** One mapping of the calendar that names a row. */
    data class Mapping(
        val protonEventId: String,
        val androidEventId: Long,
        val lastModifiedSeconds: Long,
        val sharedFingerprint: String?,
        val cachedRecurrenceId: String?,
    )

    class Input(
        val rows: Collection<Row>,
        val mappings: Collection<Mapping>,
        val cutoffMillis: Long,
        val nowMillis: Long,
        /** Proton's all-time /events/ids of this calendar, from this run. */
        val allTimeIds: Set<String>,
        /** The events this run's window returned. */
        val windowIds: Set<String>,
        /** Collision holds, "keep mine", and the deletion guard's kept and pending ids. */
        val heldIds: Set<String>,
        /** UIDs with a queued guest cancellation. */
        val cancellationUids: Set<String>,
        /** Proton ids with invitation work left (invite_pending_, invite_retry_count_). */
        val inviteWorkIds: Set<String>,
    )

    /** Why a group stays, one per group. */
    enum class Keep(val label: String) {
        REACHES_RANGE("reaches the range"),
        NO_END("no end"),
        UNKNOWN_TIME("end not known"),
        IN_WINDOW("in Proton's window"),
        UNSENT("unsent"),
        CHANGED("changed on this phone or no baseline"),
        HELD("held or with mail work"),
        RECENT("changed in 7 days"),
        NOT_FROM_PROTON("not from Proton"),
        TOO_BIG("over $MAX_GROUP_ROWS rows"),
    }

    /**
     * One group to remove: all its rows, the Proton ids of its mappings, and [linkIds], every Proton id a row of the
     * group can point at through ORIGINAL_SYNC_ID; [linkedRows] of its rows do. The removal checks that the
     * provider still has exactly that many rows pointing at [linkIds], so an occurrence a calendar app edited or
     * deleted in between (a new row of its own) keeps the group.
     */
    data class Group(val rowIds: List<Long>, val protonIds: List<String>, val linkIds: Set<String>, val linkedRows: Int)

    class Plan(val remove: List<Group>, val kept: Map<Keep, Int>) {
        val rowCount: Int get() = remove.sumOf { it.rowIds.size }

        /** Counts for the log line, the kept ones by reason. */
        fun summary(): String = "${rowCount} row(s) in ${remove.size} group(s); kept: " +
            Keep.values().joinToString(", ") { "${it.label} ${kept[it] ?: 0}" }
    }

    fun plan(input: Input): Plan {
        val rows = input.rows.associateBy { it.id }
        val mappingsByRow = input.mappings.groupBy { it.androidEventId }
        val groups = groupRows(input.rows, input.mappings)
        val remove = ArrayList<Group>()
        val kept = LinkedHashMap<Keep, Int>()
        for (ids in groups) {
            val members = ids.mapNotNull { rows[it] }
            val reason = judge(members, mappingsByRow, input)
            if (reason == null) {
                val mapped = members.flatMap { r -> mappingsByRow[r.id].orEmpty().map { it.protonEventId } }
                val linkIds = (members.mapNotNull { it.syncId } + mapped).toSet()
                val linked = members.count { r -> r.originalSyncId?.let { it in linkIds } == true }
                remove.add(Group(members.map { it.id }, mapped, linkIds, linked))
            } else {
                kept[reason] = (kept[reason] ?: 0) + 1
            }
        }
        return Plan(remove, kept)
    }

    /** The rows of one calendar in series groups, by Proton id links and UID; each group sorted by row id. */
    internal fun groupRows(rows: Collection<Row>, mappings: Collection<Mapping>): List<List<Long>> {
        val parent = HashMap<Long, Long>()
        fun find(x: Long): Long {
            var r = x
            while (parent[r] != r) r = parent[r] ?: r
            var c = x
            while (parent[c] != r) { val next = parent[c] ?: r; parent[c] = r; c = next }
            return r
        }
        fun union(a: Long, b: Long) { val ra = find(a); val rb = find(b); if (ra != rb) parent[maxOf(ra, rb)] = minOf(ra, rb) }
        for (r in rows) parent[r.id] = r.id
        // The Proton ids a row stands for: its own _SYNC_ID and the ids of the mappings that name it.
        val byProtonId = HashMap<String, Long>()
        for (r in rows) r.syncId?.takeIf { it.isNotBlank() }?.let { id -> byProtonId[id]?.let { union(it, r.id) } ?: byProtonId.put(id, r.id) }
        for (m in mappings) if (m.androidEventId in parent) {
            byProtonId[m.protonEventId]?.let { union(it, m.androidEventId) } ?: byProtonId.put(m.protonEventId, m.androidEventId)
        }
        val byUid = HashMap<String, Long>()
        for (r in rows) {
            r.originalSyncId?.takeIf { it.isNotBlank() }?.let { link -> byProtonId[link]?.let { union(it, r.id) } }
            r.uid?.takeIf { it.isNotBlank() }?.let { uid -> byUid[uid]?.let { union(it, r.id) } ?: byUid.put(uid, r.id) }
        }
        return rows.groupBy { find(it.id) }.values.map { g -> g.map { it.id }.sorted() }.sortedBy { it.first() }
    }

    /** Null when the group may go, else the one reason it stays. Time first: most groups are simply in range. */
    private fun judge(members: List<Row>, mappingsByRow: Map<Long, List<Mapping>>, input: Input): Keep? {
        val c = input.cutoffMillis
        var timeReason: Keep? = null
        for (r in members) {
            val maps = mappingsByRow[r.id].orEmpty()
            val m = maps.singleOrNull()
            val t = timeOf(r, m, c)
            if (t != null && (timeReason == null || t.ordinal < timeReason.ordinal)) timeReason = t
        }
        if (timeReason != null) return timeReason
        if (members.size > MAX_GROUP_ROWS) return Keep.TOO_BIG
        if (members.any { it.dirty || it.deleted }) return Keep.UNSENT
        for (r in members) {
            val maps = mappingsByRow[r.id].orEmpty()
            if (maps.size > 1) return Keep.NOT_FROM_PROTON
            val m = maps.singleOrNull()
            if (m == null) {
                val placeholder = r.cancelled && r.syncId == null && r.originalSyncId != null && !r.dirty
                if (!placeholder) return Keep.NOT_FROM_PROTON
            } else {
                // A mapped row without its _SYNC_ID (written on this phone before 1.2.73, or its write-back cut off) is
                // one a calendar app deletes outright: the journal would read that delete as the removal's own, and the
                // user's deletion would never reach Proton. Deleting a series row whose _SYNC_ID is empty, AOSP also
                // deletes every row whose ORIGINAL_ID names it, dirty ones included, which the asserts cannot see.
                if (r.syncId != m.protonEventId) return Keep.NOT_FROM_PROTON
                if (m.protonEventId !in input.allTimeIds) return Keep.NOT_FROM_PROTON
            }
        }
        for (r in members) {
            val m = mappingsByRow[r.id]?.singleOrNull() ?: continue
            if (m.sharedFingerprint == null || m.sharedFingerprint != r.fingerprint) return Keep.CHANGED
        }
        val ids = members.flatMap { r -> mappingsByRow[r.id].orEmpty().map { it.protonEventId } }
        if (ids.any { it in input.heldIds || it in input.inviteWorkIds }) return Keep.HELD
        if (members.any { r -> r.uid?.takeIf { it.isNotBlank() }?.let { it in input.cancellationUids } == true }) return Keep.HELD
        val recentFrom = input.nowMillis / 1000 - RECENT_SECONDS
        for (r in members) {
            val m = mappingsByRow[r.id]?.singleOrNull() ?: continue
            // A value in milliseconds (one older write path) reads as far in the future: recent, so kept.
            if (m.lastModifiedSeconds > recentFrom) return Keep.RECENT
        }
        if (ids.any { it in input.windowIds }) return Keep.IN_WINDOW
        return null
    }

    /** Null when the row ended before [cutoff] by every test that applies to it. */
    private fun timeOf(r: Row, m: Mapping?, cutoff: Long): Keep? {
        val isChild = r.originalSyncId != null || m?.cachedRecurrenceId != null
        if (r.cancelled && isChild) {
            val original = r.originalInstanceTime ?: m?.cachedRecurrenceId?.let { recurrenceIdMillis(it) } ?: return Keep.UNKNOWN_TIME
            return if (original < cutoff) null else Keep.REACHES_RANGE
        }
        if (!r.rrule.isNullOrBlank() || !r.rdate.isNullOrBlank()) {
            return when (val end = seriesEnd(r)) {
                SeriesEnd.None -> Keep.NO_END
                SeriesEnd.Unknown -> Keep.UNKNOWN_TIME
                is SeriesEnd.At -> when {
                    r.lastDate == null -> Keep.NO_END
                    end.millis >= cutoff || r.lastDate >= cutoff -> Keep.REACHES_RANGE
                    else -> null
                }
            }
        }
        val end = when {
            r.dtEnd != null -> r.dtEnd
            r.dtStart == null -> return Keep.UNKNOWN_TIME
            r.duration.isNullOrBlank() -> r.dtStart
            else -> r.dtStart + (durationMillis(r.duration.orEmpty()) ?: return Keep.UNKNOWN_TIME)
        }
        if (end >= cutoff || (r.lastDate != null && r.lastDate >= cutoff)) return Keep.REACHES_RANGE
        if (isChild) {
            val original = r.originalInstanceTime ?: m?.cachedRecurrenceId?.let { recurrenceIdMillis(it) } ?: return Keep.UNKNOWN_TIME
            if (original >= cutoff) return Keep.REACHES_RANGE
        }
        return null
    }

    sealed class SeriesEnd {
        data class At(val millis: Long) : SeriesEnd()
        object None : SeriesEnd()
        object Unknown : SeriesEnd()
    }

    /**
     * When the series of [r] ends at the latest: its last occurrence's start plus its length, plus a day of margin (a
     * COUNT rule is expanded with DTSTART's offset throughout, so a fall of the zone's offset before the last
     * occurrence moves it early by that fall: an hour for daylight saving, at most a few hours in any zone since 1970).
     * [SeriesEnd.None] for a rule line with neither UNTIL nor COUNT; [SeriesEnd.Unknown] for anything this cannot read
     * for sure: an unparsable rule, a COUNT the expansion does not reach or whose time zone is not known, an RDATE
     * period, or no length. An all-day row is read on UTC's dates, as the provider expands it.
     */
    internal fun seriesEnd(r: Row): SeriesEnd {
        val start = r.dtStart ?: return SeriesEnd.Unknown
        val length = when {
            !r.duration.isNullOrBlank() -> durationMillis(r.duration) ?: return SeriesEnd.Unknown
            r.dtEnd != null && r.dtEnd >= start -> r.dtEnd - start
            else -> return SeriesEnd.Unknown
        }
        // The provider expands an all-day row in UTC whatever its zone (CalendarInstancesHelper); this app writes "UTC"
        // on every all-day row, another calendar app may write the phone's zone.
        val zoneId = if (r.allDay) "UTC" else r.eventTimezone
        var lastStart = start
        val lines = r.rrule.orEmpty().split('\n').map { it.trim().removePrefix("RRULE:") }.filter { it.isNotEmpty() }
        for (line in lines) {
            val recur = try {
                Recur(line)
            } catch (e: Exception) {
                return SeriesEnd.Unknown
            }
            val until = recur.until
            val count = recur.count
            val last = when {
                until != null -> until.time + if (until is DateTime) 0 else DAY_MILLIS
                count > 0 -> lastOfCount(line, start, count, zoneId) ?: return SeriesEnd.Unknown
                else -> return SeriesEnd.None
            }
            lastStart = maxOf(lastStart, last)
        }
        if (!r.rdate.isNullOrBlank()) lastStart = maxOf(lastStart, latestRdate(r.rdate, zoneId) ?: return SeriesEnd.Unknown)
        return SeriesEnd.At(lastStart + length + DAY_MILLIS)
    }

    /**
     * The start of the last occurrence of a COUNT rule: the [count]-th match from [start] of the same rule without its
     * COUNT, expanded on the local dates of [zoneId] as the provider expands the row. The rule's BYDAY and BYMONTHDAY
     * name days of that zone, and on UTC days an occurrence east of UTC moved a day, which put the [count]-th match days
     * or months early. So DTSTART's wall time is expanded as if it were UTC and the matches are shifted back by
     * DTSTART's offset: an occurrence after the zone's offset fell comes out early by that fall (an hour for daylight
     * saving, at most a few hours in any zone since 1970), which [seriesEnd]'s day of margin covers. RFC 5545 counts
     * DTSTART as the first occurrence, so when DTSTART does not match the rule this is one occurrence late, never early.
     * The COUNT is taken out of the rule and the expansion stops after [count] matches instead, the last of which is
     * read off the sorted list. Null when the expansion does not reach [count], or when the zone is not known: on the
     * wrong days the result could be early.
     */
    private fun lastOfCount(rule: String, start: Long, count: Int, zoneId: String?): Long? = try {
        val zone = zoneId?.takeIf { it.isNotBlank() }?.let { ZoneId.of(it) }
        if (zone == null) {
            null
        } else {
            val offset = zone.rules.getOffset(Instant.ofEpochMilli(start)).totalSeconds * 1000L
            val endless = Recur(rule.split(';').filterNot { it.trim().uppercase().startsWith("COUNT=") }.joinToString(";"))
            val seed = DateTime(start + offset).apply { isUtc = true }
            // Proton caps COUNT at 499; a yearly rule that rarely matches needs centuries.
            val until = DateTime(start + offset + 1000L * 366 * DAY_MILLIS).apply { isUtc = true }
            val dates = endless.getDates(seed, seed, until, Value.DATE_TIME, count).map { it.time }.sorted()
            if (dates.size < count) null else dates[count - 1] - offset
        }
    } catch (e: Exception) {
        null
    }

    private val LOCAL = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
    private val DAY = DateTimeFormatter.ofPattern("yyyyMMdd")

    /**
     * The latest value of an Events.RDATE column: comma-separated dates or date-times, UTC (Z) or local, the local
     * ones in the column's own "TZID;" prefix or [eventTimezone], else read as UTC (the day of margin covers it).
     * Null for anything else, a PERIOD above all.
     */
    internal fun latestRdate(rdate: String, eventTimezone: String?): Long? {
        var text = rdate.trim()
        var zone: ZoneId? = eventTimezone?.let { runCatching { ZoneId.of(it) }.getOrNull() }
        val semi = text.indexOf(';')
        if (semi >= 0) {
            zone = runCatching { ZoneId.of(text.substring(0, semi)) }.getOrNull() ?: return null
            text = text.substring(semi + 1)
        }
        var latest: Long? = null
        for (raw in text.split(',')) {
            val v = raw.trim()
            if (v.isEmpty()) continue
            if ('/' in v) return null
            val millis = try {
                when {
                    v.length == 8 -> LocalDate.parse(v, DAY).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                    v.endsWith("Z") -> LocalDateTime.parse(v.dropLast(1), LOCAL).toInstant(ZoneOffset.UTC).toEpochMilli()
                    else -> LocalDateTime.parse(v, LOCAL).atZone(zone ?: ZoneOffset.UTC).toInstant().toEpochMilli()
                }
            } catch (e: Exception) {
                return null
            }
            latest = maxOf(latest ?: millis, millis)
        }
        return latest
    }

    /**
     * An Events.DURATION value (RFC 2445: "P3600S", "PT1H30M", "P1D", "P2W", "P1DT2H") in milliseconds; null when it is
     * anything else, negative included. ICalUtils.parseDurationToMillis falls back to an hour, which would make an
     * end too early here.
     */
    internal fun durationMillis(text: String): Long? {
        val m = DURATION.matchEntire(text.trim().uppercase()) ?: return null
        val (w, d, h, min, s) = m.destructured
        if (listOf(w, d, h, min, s).all { it.isEmpty() }) return null
        fun n(x: String) = if (x.isEmpty()) 0L else x.toLong()
        return (((n(w) * 7 + n(d)) * 24 + n(h)) * 60 + n(min)) * 60_000 + n(s) * 1000
    }

    private val DURATION = Regex("""P(?:(\d+)W)?(?:(\d+)D)?(?:T?(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?)?""")

    /** The instant a cached RECURRENCE-ID names (the whole line, or an older bare value), null when not exact. */
    internal fun recurrenceIdMillis(cached: String): Long? {
        val text = cached.trim().takeIf { it.isNotEmpty() } ?: return null
        val line = if (text.uppercase().startsWith("RECURRENCE-ID")) text else when {
            text.length == 8 -> "RECURRENCE-ID;VALUE=DATE:$text"
            text.endsWith("Z") -> "RECURRENCE-ID:$text"
            else -> return null
        }
        return when (val v = IcalPropLine.parse(line)?.let { IcalTimeValues.single(it) }) {
            is IcalTimeValue.Day -> DownloadTimes.utcMidnight(v.date)
            is IcalTimeValue.Utc -> v.instant.toEpochMilli()
            is IcalTimeValue.Zoned -> runCatching { v.local.atZone(ZoneId.of(v.tzid)).toInstant().toEpochMilli() }.getOrNull()
            else -> null
        }
    }

    /**
     * The event count at or below which a calendar syncs whole (the flat path) in a custom range of [pastDays] and
     * [futureDays]: CalendarSyncAdapter's sparse threshold, max(70, windows of 90 days x 5). Such a calendar is never
     * trimmed: its next sync downloads every event again.
     */
    fun wholeSyncThreshold(pastDays: Long, futureDays: Long): Int {
        val chunk = me.proton.sync.SyncConstants.CALENDAR_QUERY_INITIAL_CHUNK_DAYS
        val windows = (pastDays + futureDays + chunk - 1) / chunk
        return maxOf(
            me.proton.sync.SyncConstants.CALENDAR_HYBRID_FETCH_THRESHOLD.toLong(),
            windows * me.proton.sync.SyncConstants.CALENDAR_PARALLEL_GET_PERMITS
        ).toInt()
    }

    /**
     * Local midnight [pastDays] + 1 days before today in [zone]: a day before the range starts, at least. Past events
     * below 1, which the app never stores but a hand-edited backup can, count as 1: the cutoff is never later than the
     * day before yesterday, so nothing still to come is ever removed. Every cutoff (the sync's, the dialog's, the
     * check's) comes from here.
     */
    fun cutoffMillis(nowMillis: Long, pastDays: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate().minusDays(pastDays.coerceAtLeast(1) + 1)
            .atStartOfDay(zone).toInstant().toEpochMilli()
}

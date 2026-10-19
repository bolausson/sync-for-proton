package me.proton.sync.calendar

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.zone.ZoneRules

/**
 * Which local text a timed row's stored instants go back to Proton as.
 *
 * The rule: a time the user has not touched since the last sync goes back as PROTON'S OWN TEXT (the local value
 * of the cached DTSTART or DTEND line), whatever rules the stored instant was computed with; a time the user
 * changed goes back as the phone's own rules give it, because the calendar app turned the wall time the user
 * typed into an instant with exactly those rules. So an edit that never touched a time (a title, a location, a
 * reminder) cannot move the event on Proton: not when the online rules cannot be fetched in this run, not after
 * "Update time zones online" was turned off, not after a tz data update, not for a row an older version stored
 * wrong. And a typed time keeps the wall time the user typed, also in ON mode on a phone with stale tz data.
 *
 * "Not touched" is known ([TimeEdits], [SyncedTimes]) when the row's shared fields still equal the last agreed
 * state, or when its time columns still equal the record the last sync left. Without either (a row synced before
 * 1.11.5 whose shared fields changed since), the server's text is still used when the stored instant is exactly
 * what that text gives under the phone's rules or the online rules.
 *
 * Pure, so the JVM tests pin it. All-day and UTC rows never get here: their text needs no zone rules.
 */
internal object UploadTimes {

    /** The local values ("yyyyMMdd'T'HHmmss") the upload writes, and the rules an invitation's VTIMEZONE describes. */
    class Plan(
        val startText: String,
        /** The effective end: DTEND, or DTSTART plus DURATION for a series. */
        val endText: String,
        /**
         * The online rules when they apply to this event (ON, a one-off event or an override's own times,
         * available in this run) and differ from the phone's at its start or end, else null for the phone's own.
         * Only the invitation uses them: its VTIMEZONE then describes current rules, so a reader that applies the
         * definition (Outlook, Apple Calendar) gets the same instants as one that only reads the TZID name.
         */
        val inviteRules: ZoneRules?
    )

    fun plan(
        rules: RuleSnapshot,
        /** The TZID the upload writes (the row's zone, normalised to Proton's list). */
        timezone: String,
        startMillis: Long,
        endMillis: Long,
        standalone: Boolean,
        cachedDtStartLine: String?,
        cachedDtEndLine: String?,
        edits: TimeEdits
    ): Plan {
        val zone = try { ZoneId.of(timezone) } catch (_: Exception) { null }
        val start = Instant.ofEpochMilli(startMillis)
        val end = Instant.ofEpochMilli(endMillis)
        val online = if (zone != null && standalone) {
            rules.onlineFor(zone, LocalDateTime.ofInstant(start, zone.rules.getOffset(start)).year)
        } else {
            null
        }
        val startText = lineText(startMillis, cachedDtStartLine, timezone, zone, online, standalone, edits.startUntouched)
        val endText = lineText(endMillis, cachedDtEndLine, timezone, zone, online, standalone, edits.endUntouched)
        val inviteRules = online?.takeIf {
            zone != null && (it.getOffset(start) != zone.rules.getOffset(start) || it.getOffset(end) != zone.rules.getOffset(end))
        }
        return Plan(startText, endText, inviteRules)
    }

    private fun lineText(
        millis: Long,
        cachedLine: String?,
        timezone: String,
        zone: ZoneId?,
        online: ZoneRules?,
        standalone: Boolean,
        untouched: Boolean
    ): String {
        val phoneText = ICalUtils.formatLocalTimestamp(millis, timezone)
        if (zone == null) return phoneText
        val cached = IcalPropLine.parse(cachedLine)?.let { IcalTimeValues.single(it) } as? IcalTimeValue.Zoned ?: return phoneText
        if (!sameZone(cached.tzid, timezone)) return phoneText
        val serverText = IcalTimeValues.formatLocal(cached.local)
        if (untouched) return serverText
        if (LocalTimes.toInstant(cached.local, zone.rules, providerExpands = !standalone).toEpochMilli() == millis) return serverText
        if (online != null && LocalTimes.toInstant(cached.local, online).toEpochMilli() == millis) return serverText
        return phoneText
    }

    /** [millis] as local "yyyyMMdd'T'HHmmss" text in [timezone]: with [rules], or the phone's own when null. */
    fun formatLocal(millis: Long, timezone: String, rules: ZoneRules?): String =
        if (rules == null) {
            ICalUtils.formatLocalTimestamp(millis, timezone)
        } else {
            LocalTimes.formatLocal(Instant.ofEpochMilli(millis), rules)
        }

    private fun sameZone(cachedTzid: String, rowTimezone: String): Boolean =
        cachedTzid == rowTimezone || ICalUtils.normalizeTimezone(cachedTzid) == rowTimezone
}

/** Which of a row's two times the user has not touched since the last sync wrote them ([SyncedTimes.edits]). */
internal class TimeEdits(val startUntouched: Boolean, val endUntouched: Boolean) {
    companion object {
        /** Nothing is known: a new event, or a row synced before 1.11.5 whose shared fields changed since. */
        val UNKNOWN = TimeEdits(startUntouched = false, endUntouched = false)
    }
}

/**
 * calendar_event_sync.cached_times: a row's time columns (DTSTART, DTEND, DURATION, EVENT_TIMEZONE) exactly as
 * the provider held them right after the last sync wrote the event (a download) or sent it (an upload). The
 * cached DTSTART and DTEND lines on the same mapping are the text Proton holds for those very values, so while
 * the columns still equal this record, that text is still right ([UploadTimes]).
 */
internal object SyncedTimes {
    private const val VERSION = "1"

    /** The record of [shared], a row's EventConflictGuard.SHARED_COLUMNS values. */
    fun encode(shared: List<String?>): String = listOf(
        VERSION,
        shared[EventConflictGuard.COLUMN_DTSTART],
        shared[EventConflictGuard.COLUMN_DTEND],
        shared[EventConflictGuard.COLUMN_DURATION],
        shared[EventConflictGuard.COLUMN_EVENT_TIMEZONE]
    ).joinToString("|") { it?.trim() ?: "" }

    /**
     * Which times of the row whose shared values are [current] are untouched: both when its fingerprint still
     * equals [baselineFingerprint] (no shared field changed at all), otherwise each one whose columns still equal
     * [record]. The start is DTSTART in its zone; the end is DTEND, or for a series DTSTART plus DURATION.
     */
    fun edits(current: List<String?>?, baselineFingerprint: String?, record: String?): TimeEdits {
        if (current == null) return TimeEdits.UNKNOWN
        if (baselineFingerprint != null && EventConflictGuard.fingerprintOf(current) == baselineFingerprint) {
            return TimeEdits(startUntouched = true, endUntouched = true)
        }
        val parts = record?.split('|') ?: return TimeEdits.UNKNOWN
        if (parts.size != 5 || parts[0] != VERSION) return TimeEdits.UNKNOWN
        fun value(column: Int) = current[column]?.trim() ?: ""
        val sameZone = value(EventConflictGuard.COLUMN_EVENT_TIMEZONE) == parts[4]
        val sameStart = value(EventConflictGuard.COLUMN_DTSTART) == parts[1]
        val sameEnd = value(EventConflictGuard.COLUMN_DTEND) == parts[2] &&
            value(EventConflictGuard.COLUMN_DURATION) == parts[3] &&
            (value(EventConflictGuard.COLUMN_DURATION).isEmpty() || sameStart)
        return TimeEdits(startUntouched = sameZone && sameStart, endUntouched = sameZone && sameEnd)
    }
}

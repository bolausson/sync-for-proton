package me.proton.sync.calendar

import android.accounts.Account
import android.content.ContentProviderClient
import android.content.Context
import android.provider.CalendarContract
import me.proton.sync.SyncLog
import me.proton.sync.db.entities.CalendarEventSyncEntity
import java.io.IOException

/**
 * The provider side of the re-time pass: reads what [EventRetimePlanner] needs and hands back what the adapter
 * must write to Room. It never writes to the provider: a row that differs is only tagged, and the download
 * re-applies it through the normal path once it is clean (CalendarSyncAdapter.isRetimeDue). Non-suspend on
 * purpose (the calendar adapter's suspend functions sit at the coroutine transform ceiling);
 * CalendarSyncAdapter.runRetimePass does the Room part.
 */
internal object EventRetimePass {
    private const val TAG = "CalendarSync"
    private const val CHUNK = 400

    class Outcome(
        val decisions: List<EventRetimePlanner.Decision>,
        /** Proton ids to mark RETIME_TAG: TAG and TAG_DIRTY. */
        val tagIds: List<String>,
        /** Proton calendar ids holding a clean tagged row: their cursor is cleared so they are downloaded. */
        val calendarsToRefetch: Set<String>,
        /** Rows left unchecked because the rules they need could not be had in this run. */
        val unavailable: Int
    ) {
        val summary: String
            get() = EventRetimePlanner.summary(decisions) + " | calendars to download again ${calendarsToRefetch.size}"
    }

    /** Plan for one account. Throws when the provider cannot be read. Writes nothing anywhere. */
    fun evaluate(
        context: Context,
        provider: ContentProviderClient,
        account: Account,
        entities: List<CalendarEventSyncEntity>,
        rules: RuleSnapshot
    ): Outcome {
        val mappings = entities.mapNotNull { e ->
            val id = e.androidEventId ?: return@mapNotNull null
            EventRetimePlanner.Mapping(
                protonEventId = e.protonEventId,
                protonCalendarId = e.protonCalendarId,
                androidEventId = id,
                cachedDtStartLine = e.cachedDtStartLine,
                cachedDtEndLine = e.cachedDtEndLine,
                cachedUid = e.cachedUid,
                cachedRecurrenceId = e.cachedRecurrenceId,
                sharedFingerprint = e.sharedFingerprint
            )
        }
        val rows = readRows(provider, mappings.map { it.androidEventId })
            ?: throw IOException("the calendar provider could not be read")
        val seriesCalendars = HashMap<Long, MutableSet<String>>()
        for (m in mappings) {
            val read = rows[m.androidEventId] ?: continue
            val r = read.planned
            if (r.isSeries && r.originalSyncId == null && !r.dirty && !r.deleted) {
                seriesCalendars.getOrPut(read.calendarId) { HashSet() }.add(m.protonEventId)
            }
        }
        val placeholders = readPlaceholders(provider, seriesCalendars)
            ?: throw IOException("the cancelled occurrences could not be read")
        val held = EventConflictGuard.heldIds(context, account.name) + EventConflictGuard.localWinsIds(context, account.name)

        val decisions = EventRetimePlanner.plan(
            mappings = mappings,
            rows = rows.mapValues { it.value.planned },
            placeholders = placeholders,
            held = held,
            rules = rules,
            available = { zone, standalone, local -> !(standalone && rules.onlineUnavailable(zone, local.year)) }
        )

        val calendarsToRefetch = decisions.filter { it.verdict == EventRetimePlanner.Verdict.TAG }
            .map { it.mapping.protonCalendarId }.toSet()
        for (d in decisions) {
            if (d.verdict == EventRetimePlanner.Verdict.LEAVE_LOCAL_CHANGE) {
                SyncLog.w(TAG, "Re-time: event ${d.mapping.protonEventId} keeps its stored times (${d.reason}): " +
                    "its shared fields differ from the last synced state, and downloading it again would undo that")
            }
        }
        return Outcome(
            decisions,
            decisions.filter { it.tags }.map { it.mapping.protonEventId },
            calendarsToRefetch,
            decisions.count { it.unavailable }
        )
    }

    /** One provider row as read: the planner's view and the calendar it lives in. */
    private class ReadRow(val planned: EventRetimePlanner.Row, val calendarId: Long)

    private val EXTRA_COLUMNS = arrayOf(
        CalendarContract.Events._ID,
        CalendarContract.Events.CALENDAR_ID,
        CalendarContract.Events.ORIGINAL_SYNC_ID,
        CalendarContract.Events.ORIGINAL_INSTANCE_TIME,
        CalendarContract.Events.DIRTY,
        CalendarContract.Events.DELETED
    )

    /** The rows by _ID, or null when the provider cannot be read (never an empty map in its place). */
    private fun readRows(provider: ContentProviderClient, ids: List<Long>): Map<Long, ReadRow>? {
        val shared = EventConflictGuard.SHARED_COLUMNS
        val projection = shared + EXTRA_COLUMNS
        val out = HashMap<Long, ReadRow>()
        for (chunk in ids.distinct().chunked(CHUNK)) {
            val selection = "${CalendarContract.Events._ID} IN (${chunk.joinToString(",") { "?" }})"
            val cursor = try {
                provider.query(CalendarContract.Events.CONTENT_URI, projection, selection, chunk.map { it.toString() }.toTypedArray(), null)
            } catch (e: Exception) {
                SyncLog.w(TAG, "Re-time: could not read events: ${e.message}")
                null
            } ?: return null
            cursor.use { c ->
                val n = shared.size
                while (c.moveToNext()) {
                    val values = List(n) { i -> if (c.isNull(i)) null else c.getString(i) }
                    val id = c.getLong(n)
                    out[id] = ReadRow(
                        planned = rowOf(
                            id = id,
                            shared = values,
                            originalSyncId = if (c.isNull(n + 2)) null else c.getString(n + 2),
                            originalInstanceTime = if (c.isNull(n + 3)) null else c.getLong(n + 3),
                            dirty = !c.isNull(n + 4) && c.getInt(n + 4) != 0,
                            deleted = !c.isNull(n + 5) && c.getInt(n + 5) != 0
                        ),
                        calendarId = c.getLong(n + 1)
                    )
                }
            }
        }
        return out
    }

    /** The planner's row from [EventConflictGuard.SHARED_COLUMNS] values: pure, so the column indexes are pinned by a test. */
    internal fun rowOf(
        id: Long,
        shared: List<String?>,
        originalSyncId: String?,
        originalInstanceTime: Long?,
        dirty: Boolean,
        deleted: Boolean
    ) = EventRetimePlanner.Row(
        id = id,
        dtStart = shared[EventConflictGuard.COLUMN_DTSTART]?.toLongOrNull(),
        dtEnd = shared[EventConflictGuard.COLUMN_DTEND]?.toLongOrNull(),
        duration = shared[EventConflictGuard.COLUMN_DURATION],
        allDay = shared[EventConflictGuard.COLUMN_ALL_DAY] == "1",
        rrule = shared[EventConflictGuard.COLUMN_RRULE],
        rdate = shared[EventConflictGuard.COLUMN_RDATE],
        originalSyncId = originalSyncId,
        originalInstanceTime = originalInstanceTime,
        dirty = dirty,
        deleted = deleted,
        sharedValues = shared,
        fingerprint = EventConflictGuard.fingerprintOf(shared)
    )

    /**
     * Series Proton id to the ORIGINAL_INSTANCE_TIME of its clean cancelled placeholders: the rows the download
     * writes for each EXDATE (DIRTY = 0, no _SYNC_ID). A user's own pending "delete this occurrence" is DIRTY = 1
     * and not counted. Null when the provider cannot be read.
     */
    private fun readPlaceholders(provider: ContentProviderClient, seriesByCalendar: Map<Long, Set<String>>): Map<String, List<Long>>? {
        val out = HashMap<String, MutableList<Long>>()
        for ((calendarId, seriesIds) in seriesByCalendar) {
            val cursor = try {
                provider.query(
                    CalendarContract.Events.CONTENT_URI,
                    arrayOf(CalendarContract.Events.ORIGINAL_SYNC_ID, CalendarContract.Events.ORIGINAL_INSTANCE_TIME),
                    "${CalendarContract.Events.CALENDAR_ID} = ? AND ${CalendarContract.Events.STATUS} = ? AND " +
                        "${CalendarContract.Events._SYNC_ID} IS NULL AND ${CalendarContract.Events.DIRTY} = 0 AND " +
                        "${CalendarContract.Events.DELETED} = 0 AND ${CalendarContract.Events.ORIGINAL_SYNC_ID} IS NOT NULL",
                    arrayOf(calendarId.toString(), CalendarContract.Events.STATUS_CANCELED.toString()),
                    null
                )
            } catch (e: Exception) {
                SyncLog.w(TAG, "Re-time: could not read cancelled occurrences: ${e.message}")
                null
            } ?: return null
            cursor.use { c ->
                while (c.moveToNext()) {
                    if (c.isNull(0) || c.isNull(1)) continue
                    val series = c.getString(0)
                    if (series in seriesIds) out.getOrPut(series) { ArrayList() }.add(c.getLong(1))
                }
            }
        }
        return out
    }

    /** One row's state for the download's re-time gate (isRetimeDue): read fresh, null when unreadable. */
    class RowState(val dirty: Boolean, val deleted: Boolean, val fingerprint: String)

    fun readRowState(provider: ContentProviderClient, androidEventId: Long): RowState? = try {
        provider.query(
            CalendarContract.Events.CONTENT_URI,
            EventConflictGuard.SHARED_COLUMNS + arrayOf(CalendarContract.Events.DIRTY, CalendarContract.Events.DELETED),
            "${CalendarContract.Events._ID} = ?",
            arrayOf(androidEventId.toString()),
            null
        )?.use { c ->
            if (!c.moveToFirst()) return@use null
            val n = EventConflictGuard.SHARED_COLUMNS.size
            val values = List(n) { i -> if (c.isNull(i)) null else c.getString(i) }
            RowState(
                dirty = !c.isNull(n) && c.getInt(n) != 0,
                deleted = !c.isNull(n + 1) && c.getInt(n + 1) != 0,
                fingerprint = EventConflictGuard.fingerprintOf(values)
            )
        }
    } catch (e: Exception) {
        SyncLog.w(TAG, "Re-time: could not read event row $androidEventId: ${e.message}")
        null
    }
}

package me.proton.sync.calendar

import android.accounts.Account
import android.content.ContentProviderClient
import android.content.ContentProviderOperation
import android.content.OperationApplicationException
import android.content.SharedPreferences
import android.provider.CalendarContract
import me.proton.sync.SyncLog
import me.proton.sync.SyncUtils
import me.proton.sync.db.entities.CalendarEventSyncEntity

/**
 * The removal side of "Remove older events from this phone" ([RangeTrimPlanner] decides what goes). Nothing here talks
 * to Proton. The rows go first and their mappings after them, so a mapping is never rebuilt: a row that stays keeps
 * the mapping it always had, with its sequence, baseline and cached fields. Between the two steps a mapping names a
 * row that is gone, which reconcileDeletedEvents would send to Proton as a deletion; the journal names those rows
 * before anything is deleted, and while it holds anything reconcileDeletedEvents sends nothing
 * ([RangeTrimRunner.removalUnfinished]).
 *
 * Batches of at most [BATCH_ROWS] rows, each in this order:
 *  1. the batch's rows and Proton ids go to the journal, written through (commit) before anything else;
 *  2. one provider batch deletes the groups as the sync adapter (a hard delete, no tombstone), each behind two
 *     asserts: its rows are all there and clean, and the rows pointing at the group's Proton ids are exactly the
 *     planned ones, so an occurrence a calendar app edited or deleted in between (a new row of its own) keeps it;
 *  3. the mappings of the rows that are gone are deleted, and only those;
 *  4. the journal is cleared.
 * When an assert refuses a batch (the provider rolls the whole batch back), its groups are retried one at a time and the
 * one a user touched stays. When the provider fails otherwise, the rows still there keep their mappings and the
 * mappings of any that went anyway (a provider that is not transactional) go. When the rows cannot be read, or Room
 * fails, the journal stays and [recover] finishes the job at the next sync.
 *
 * The provider, Room and the journal sit behind [Rows], [Mappings] and [Journal], so every order and failure is
 * tested on the JVM.
 */
internal object RangeTrimPass {
    private const val TAG = "CalendarSync"
    const val BATCH_ROWS = RangeTrimPlanner.MAX_GROUP_ROWS

    /**
     * One row of a removal batch with one Proton id whose mapping names it (null for an unmapped row), journaled
     * before anything is deleted. [group] numbers the groups of the batch.
     */
    data class JournalEntry(
        val protonCalendarId: String,
        val androidCalId: Long,
        val group: Int,
        val rowId: Long,
        val protonEventId: String?,
    ) {
        fun encode() = listOf(protonCalendarId, androidCalId, group, rowId, protonEventId.orEmpty()).joinToString("|")

        companion object {
            fun decode(text: String): JournalEntry? {
                val p = text.split('|')
                if (p.size != 5) return null
                return JournalEntry(
                    p[0], p[1].toLongOrNull() ?: return null, p[2].toIntOrNull() ?: return null,
                    p[3].toLongOrNull() ?: return null, p[4].ifEmpty { null }
                )
            }
        }
    }

    interface Rows {
        /** The calendar's rows as the planner reads them, or null when the provider cannot be read. */
        fun read(androidCalId: Long): List<RangeTrimPlanner.Row>?

        /** Of [rowIds], the ones still present and whether each is dirty, or null when the provider cannot be read. */
        fun present(rowIds: Collection<Long>): Map<Long, Boolean>?

        /**
         * Delete [groups] of [androidCalId] in one provider batch, each behind its asserts: true when it went through,
         * false when an assert refused it. Throws on any other failure.
         */
        fun deleteGroups(androidCalId: Long, groups: List<RangeTrimPlanner.Group>): Boolean
    }

    interface Mappings {
        suspend fun delete(protonIds: List<String>)

        /** Of [protonIds], the ones whose mapping names a row, with that row. */
        suspend fun rowsOf(protonIds: List<String>): Map<String, Long>
    }

    interface Journal {
        fun read(): List<JournalEntry>

        /** True once [entries] are on disk; nothing may be deleted before. */
        fun write(entries: List<JournalEntry>): Boolean
        fun clear()
    }

    /**
     * What a removal did; [notes] are failures for the caller to log (this object logs nothing itself). [refusedGroups]
     * stayed because something changed since the plan (an assert refused them), [failedGroups] because the provider
     * failed; both are tried again at a later sync.
     */
    class Outcome(
        val removedGroups: Int,
        val removedRows: Int,
        val refusedGroups: Int,
        val failedGroups: Int,
        val stopped: Boolean,
        val notes: List<String>,
    )

    /** What a recovery did: the mappings of rows that were gone, deleted, and the journaled rows still there. */
    class Recovery(val deletedMappings: Int, val keptRows: Int)

    suspend fun remove(
        protonCalendarId: String,
        androidCalId: Long,
        plan: RangeTrimPlanner.Plan,
        entities: Collection<CalendarEventSyncEntity>,
        rows: Rows,
        mappings: Mappings,
        journal: Journal,
    ): Outcome {
        val byRow: Map<Long, List<String>> = entities.filter { it.androidEventId != null }
            .groupBy({ it.androidEventId!! }, { it.protonEventId })
        val batches = ArrayList<List<RangeTrimPlanner.Group>>()
        var current = ArrayList<RangeTrimPlanner.Group>()
        var size = 0
        for (g in plan.remove) {
            if (current.isNotEmpty() && size + g.rowIds.size > BATCH_ROWS) {
                batches.add(current); current = ArrayList(); size = 0
            }
            current.add(g); size += g.rowIds.size
        }
        if (current.isNotEmpty()) batches.add(current)

        var groups = 0
        var removedRows = 0
        var refused = 0
        var failed = 0
        val notes = ArrayList<String>()
        fun stopped() = Outcome(groups, removedRows, refused, failed, stopped = true, notes = notes)
        for (batch in batches) {
            when (val r = runBatch(protonCalendarId, androidCalId, batch, byRow, rows, mappings, journal, notes)) {
                is BatchResult.Done -> { groups += batch.size; removedRows += batch.sumOf { it.rowIds.size } }
                is BatchResult.Stopped -> return stopped()
                is BatchResult.Undone -> {
                    groups += r.gone.size; removedRows += r.gone.sumOf { it.rowIds.size }
                    val left = batch - r.gone.toSet()
                    when {
                        !r.refused -> failed += left.size
                        batch.size == 1 -> refused += left.size
                        // One group of the batch changed since the plan: the others go one at a time.
                        else -> for (g in left) {
                            when (val one = runBatch(protonCalendarId, androidCalId, listOf(g), byRow, rows, mappings, journal, notes)) {
                                is BatchResult.Done -> { groups++; removedRows += g.rowIds.size }
                                is BatchResult.Stopped -> return stopped()
                                is BatchResult.Undone -> when {
                                    one.gone.isNotEmpty() -> { groups++; removedRows += g.rowIds.size }
                                    one.refused -> refused++
                                    else -> failed++
                                }
                            }
                        }
                    }
                }
            }
        }
        return Outcome(groups, removedRows, refused, failed, stopped = false, notes = notes)
    }

    private sealed class BatchResult {
        object Done : BatchResult()
        object Stopped : BatchResult()
        /**
         * The batch did not go through, [refused] by an assert or failed otherwise: [gone] lost every row anyway (a
         * provider that is not transactional).
         */
        class Undone(val gone: List<RangeTrimPlanner.Group>, val refused: Boolean) : BatchResult()
    }

    private suspend fun runBatch(
        protonCalendarId: String,
        androidCalId: Long,
        batch: List<RangeTrimPlanner.Group>,
        byRow: Map<Long, List<String>>,
        rows: Rows,
        mappings: Mappings,
        journal: Journal,
        notes: MutableList<String>,
    ): BatchResult {
        val entries = batch.flatMapIndexed { i, g ->
            g.rowIds.flatMap { id ->
                byRow[id]?.takeIf { it.isNotEmpty() }?.map { p -> JournalEntry(protonCalendarId, androidCalId, i, id, p) }
                    ?: listOf(JournalEntry(protonCalendarId, androidCalId, i, id, null))
            }
        }
        if (!journal.write(entries)) {
            // Without the journal on disk, a crash after the rows went would leave mappings that nothing holds back.
            journal.clear()
            notes.add("the journal could not be written: nothing removed this time")
            return BatchResult.Stopped
        }
        val allRows = batch.flatMap { it.rowIds }
        val ok: Boolean? = try {
            rows.deleteGroups(androidCalId, batch)
        } catch (e: Exception) {
            notes.add("a removal batch of ${batch.size} group(s) failed: ${e.message}")
            null
        }
        val gone: Set<Long> = if (ok == true) {
            allRows.toSet()
        } else {
            // Normally nothing went: the provider rolls a refused or failed batch back. Its rows keep their mappings.
            val present = rows.present(allRows) ?: run {
                notes.add("the rows of a failed batch could not be read: the journal stays for the next sync")
                return BatchResult.Stopped
            }
            allRows.filterTo(HashSet()) { it !in present }
        }
        // The mappings of the rows that are gone, and only those: until this, the journal holds reconcile back.
        val goneIds = gone.flatMap { byRow[it].orEmpty() }
        try {
            if (goneIds.isNotEmpty()) mappings.delete(goneIds)
        } catch (e: Exception) {
            notes.add("could not delete the mappings of ${gone.size} removed row(s): ${e.message}; the journal stays for the next sync")
            return BatchResult.Stopped
        }
        journal.clear()
        return if (ok == true) BatchResult.Done else BatchResult.Undone(batch.filter { g -> g.rowIds.all { it in gone } }, refused = ok == false)
    }

    /**
     * Finish what an interrupted removal left, as a sync's first step, whatever the setting is now. The rows went before
     * their mappings, so a journaled row is either still there, with the mapping it always had, or gone; the mapping of
     * a gone row is deleted, as planned, when it still names that row. Rows are never touched here. When the provider
     * or Room cannot be read, the journal stays for the next sync, and null is returned.
     */
    suspend fun recover(rows: Rows, mappings: Mappings, journal: Journal): Recovery? {
        val entries = journal.read()
        if (entries.isEmpty()) return Recovery(0, 0)
        val present = rows.present(entries.map { it.rowId }) ?: return null
        val gone = entries.filter { it.rowId !in present && it.protonEventId != null }
        val stale = try {
            val pointing = mappings.rowsOf(gone.mapNotNull { it.protonEventId }.distinct())
            gone.filter { e -> pointing[e.protonEventId] == e.rowId }.mapNotNull { it.protonEventId }.distinct()
        } catch (e: Exception) {
            return null
        }
        try {
            if (stale.isNotEmpty()) mappings.delete(stale)
        } catch (e: Exception) {
            return null
        }
        journal.clear()
        return Recovery(stale.size, entries.map { it.rowId }.distinct().count { it in present })
    }

    /** The ids and UIDs that keep a group on the phone whatever its dates ([RangeTrimPlanner.Input]). */
    class Guards(val heldIds: Set<String>, val cancellationUids: Set<String>, val inviteWorkIds: Set<String>)

    /**
     * [Guards] for [accountName]: collision holds and "keep mine" (EventConflictGuard), the deletion guard's kept and
     * pending calendar ids, queued guest cancellations, and invitation work left on any of [protonIds].
     */
    fun guards(context: android.content.Context, prefs: SharedPreferences, accountName: String, protonIds: Collection<String>): Guards {
        val t = me.proton.sync.RemoteDeletionGuard.TYPE_CALENDAR
        val held = EventConflictGuard.heldIds(context, accountName) + EventConflictGuard.localWinsIds(context, accountName) +
            me.proton.sync.RemoteDeletionGuard.keptIds(context, t, accountName) +
            me.proton.sync.RemoteDeletionGuard.pendingIds(context, t, accountName)
        val uids = PendingCancellationStore.read(prefs, accountName).mapTo(HashSet()) { it.uid }
        val invites = protonIds.filterTo(HashSet()) { prefs.contains("invite_pending_$it") || prefs.contains("invite_retry_count_$it") }
        return Guards(held, uids, invites)
    }

    // --- The real implementations ---------------------------------------------------------------------------

    private val ROW_COLUMNS = arrayOf(
        CalendarContract.Events._ID,
        CalendarContract.Events._SYNC_ID,
        CalendarContract.Events.ORIGINAL_SYNC_ID,
        CalendarContract.Events.ORIGINAL_INSTANCE_TIME,
        CalendarContract.Events.UID_2445,
        CalendarContract.Events.LAST_DATE,
        CalendarContract.Events.DIRTY,
        CalendarContract.Events.DELETED,
    )
    private val STATUS_INDEX = EventConflictGuard.SHARED_COLUMNS.indexOf(CalendarContract.Events.STATUS)

    /** The planner's row from what the provider returned; pure, so the column indexes are pinned by a test. */
    internal fun rowOf(
        id: Long,
        shared: List<String?>,
        syncId: String?,
        originalSyncId: String?,
        originalInstanceTime: Long?,
        uid: String?,
        lastDate: Long?,
        dirty: Boolean,
        deleted: Boolean,
    ) = RangeTrimPlanner.Row(
        id = id,
        syncId = syncId?.takeIf { it.isNotBlank() },
        originalSyncId = originalSyncId?.takeIf { it.isNotBlank() },
        originalInstanceTime = originalInstanceTime,
        uid = uid,
        dtStart = shared[EventConflictGuard.COLUMN_DTSTART]?.toLongOrNull(),
        dtEnd = shared[EventConflictGuard.COLUMN_DTEND]?.toLongOrNull(),
        duration = shared[EventConflictGuard.COLUMN_DURATION],
        rrule = shared[EventConflictGuard.COLUMN_RRULE],
        rdate = shared[EventConflictGuard.COLUMN_RDATE],
        eventTimezone = shared[EventConflictGuard.COLUMN_EVENT_TIMEZONE],
        lastDate = lastDate,
        cancelled = shared[STATUS_INDEX] == CalendarContract.Events.STATUS_CANCELED.toString(),
        dirty = dirty,
        deleted = deleted,
        fingerprint = EventConflictGuard.fingerprintOf(shared),
        allDay = shared[EventConflictGuard.COLUMN_ALL_DAY] == "1",
    )

    /** [Rows] over the calendar provider, as the sync adapter of [account]. */
    class ProviderRows(private val provider: ContentProviderClient, private val account: Account) : Rows {
        private val uri get() = SyncUtils.asCallerSyncAdapter(CalendarContract.Events.CONTENT_URI, account)

        override fun read(androidCalId: Long): List<RangeTrimPlanner.Row>? {
            val shared = EventConflictGuard.SHARED_COLUMNS
            val n = shared.size
            return try {
                provider.query(uri, shared + ROW_COLUMNS, "${CalendarContract.Events.CALENDAR_ID} = ?", arrayOf(androidCalId.toString()), null)
                    ?.use { c ->
                        val out = ArrayList<RangeTrimPlanner.Row>()
                        while (c.moveToNext()) {
                            val values = List(n) { i -> if (c.isNull(i)) null else c.getString(i) }
                            out.add(rowOf(
                                id = c.getLong(n),
                                shared = values,
                                syncId = if (c.isNull(n + 1)) null else c.getString(n + 1),
                                originalSyncId = if (c.isNull(n + 2)) null else c.getString(n + 2),
                                originalInstanceTime = if (c.isNull(n + 3)) null else c.getLong(n + 3),
                                uid = if (c.isNull(n + 4)) null else c.getString(n + 4),
                                lastDate = if (c.isNull(n + 5)) null else c.getLong(n + 5),
                                dirty = !c.isNull(n + 6) && c.getInt(n + 6) != 0,
                                deleted = !c.isNull(n + 7) && c.getInt(n + 7) != 0,
                            ))
                        }
                        out
                    }
            } catch (e: Exception) {
                SyncLog.w(TAG, "Range trim: could not read the events of calendar $androidCalId: ${e.message}")
                null
            }
        }

        override fun present(rowIds: Collection<Long>): Map<Long, Boolean>? {
            val out = HashMap<Long, Boolean>()
            try {
                for (chunk in rowIds.distinct().chunked(BATCH_ROWS)) {
                    provider.query(
                        uri, arrayOf(CalendarContract.Events._ID, CalendarContract.Events.DIRTY),
                        "${CalendarContract.Events._ID} IN (${chunk.joinToString(",") { "?" }})", chunk.map { it.toString() }.toTypedArray(), null
                    )?.use { c -> while (c.moveToNext()) out[c.getLong(0)] = !c.isNull(1) && c.getInt(1) != 0 } ?: return null
                }
            } catch (e: Exception) {
                SyncLog.w(TAG, "Range trim: could not check which rows are still there: ${e.message}")
                return null
            }
            return out
        }

        override fun deleteGroups(androidCalId: Long, groups: List<RangeTrimPlanner.Group>): Boolean {
            val ops = ArrayList<ContentProviderOperation>()
            for (g in groups) {
                val ids = g.rowIds.map { it.toString() }
                val clean = "${CalendarContract.Events.CALENDAR_ID} = ? AND ${CalendarContract.Events._ID} IN " +
                    "(${ids.joinToString(",") { "?" }}) AND ${CalendarContract.Events.DIRTY} = 0 AND ${CalendarContract.Events.DELETED} = 0"
                val args = (listOf(androidCalId.toString()) + ids).toTypedArray()
                ops.add(ContentProviderOperation.newAssertQuery(uri).withSelection(clean, args).withExpectedCount(ids.size).build())
                if (g.linkIds.isNotEmpty()) {
                    val links = g.linkIds.toList()
                    ops.add(
                        ContentProviderOperation.newAssertQuery(uri)
                            .withSelection(
                                "${CalendarContract.Events.CALENDAR_ID} = ? AND ${CalendarContract.Events.ORIGINAL_SYNC_ID} IN " +
                                    "(${links.joinToString(",") { "?" }})",
                                (listOf(androidCalId.toString()) + links).toTypedArray()
                            )
                            .withExpectedCount(g.linkedRows).build()
                    )
                }
                ops.add(ContentProviderOperation.newDelete(uri).withSelection(clean, args).build())
            }
            return try {
                provider.applyBatch(ops)
                true
            } catch (e: OperationApplicationException) {
                false
            }
        }
    }

    /** [Journal] in the app's preferences, one string per row, written with commit(). */
    class PrefsJournal(private val prefs: SharedPreferences, private val key: String) : Journal {
        override fun read(): List<JournalEntry> = prefs.getStringSet(key, null).orEmpty().mapNotNull { JournalEntry.decode(it) }
        override fun write(entries: List<JournalEntry>): Boolean =
            prefs.edit().putStringSet(key, entries.map { it.encode() }.toSet()).commit()
        override fun clear() {
            prefs.edit().remove(key).commit()
        }
    }
}

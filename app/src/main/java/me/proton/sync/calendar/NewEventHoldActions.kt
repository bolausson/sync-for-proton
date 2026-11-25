package me.proton.sync.calendar

import android.accounts.Account
import android.content.ContentProviderClient
import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.Context
import android.content.OperationApplicationException
import android.content.SharedPreferences
import android.database.Cursor
import android.provider.CalendarContract
import androidx.preference.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.proton.sync.FullResyncHold
import me.proton.sync.LogNames
import me.proton.sync.SyncConstants
import me.proton.sync.SyncLog
import me.proton.sync.SyncNotificationHelper
import me.proton.sync.SyncScheduling
import me.proton.sync.calendar.NewEventHoldRules.Entry
import me.proton.sync.calendar.NewEventHoldRules.State
import me.proton.sync.db.SyncDatabase

/**
 * The ONE handler behind every answer to the question about a new event in a shared calendar: the prompt's buttons
 * (NewEventHoldReceiver), the decision dialog (settings.NewEventHoldDialog) and the debug verbs event-hold and
 * event-hold-dialog. Answers run one at a time, process-wide ([mutex]), and each reads its entry again and names the
 * state its button was drawn for (the notification's: waiting; the dialog's: the state it showed), so two answers
 * to one question, whatever their order, or a stale prompt, cannot both apply: the loser gets [Outcome.NOT_WAITING].
 *
 *  - [Choice.KEEP] (waiting only): the entry becomes kept; nothing is sent anywhere, ever.
 *  - [Choice.PUBLISH] (waiting or kept): the entry becomes published, so the next upload sends the series to the
 *    shared calendar without a new verdict; needs that calendar mapped and writable.
 *  - [Choice.MOVE] (waiting or kept): the series moves into the account's default calendar (NewEventHolds.moveTarget)
 *    with one plain provider batch, and the entry goes once the provider has it.
 *
 * A parent row that is gone or sits in another calendar ends the question, or passes it to a row of the series left
 * with its UID (NewEventHolds.passOnOrDrop), whose prompt then asks; either way the answer is [Outcome.NOT_WAITING].
 *
 * Publish and Move then ask for the calendar sync as the user's own sync (SyncScheduling.userSyncExtras, answering the
 * account's own sync, as the collision and deletion answers do): it runs at once, also while Android's automatic sync
 * is off or the account is on Manual only, and is not retried in the background then; when it fails the stored answer
 * is applied by the next sync. While a full re-sync of the account's calendar is being prepared, the request waits
 * until the re-sync's own request is posted (FullResyncHold). Keep asks for nothing. Each answer is stored before that
 * request, and committed.
 *
 * The answers are safe against a sync that runs meanwhile: the upload takes its snapshot of the entries before it
 * reads the dirty rows, Move writes the provider before it drops the entry, and Keep and Publish are one
 * compare-and-set each. So the run sees the series waiting, kept, published, or already in its new calendar, where the
 * account's own calendar never holds.
 */
internal object NewEventHoldActions {

    private const val TAG = "UserAction"

    enum class Choice { MOVE, KEEP, PUBLISH }

    enum class Outcome { DONE, NOT_WAITING, FAILED }

    private val mutex = Mutex()

    /**
     * Where the decision dialog and the debug verbs run an answer, so it completes whatever the screen does (a rotation
     * cancels the screen's own scope). Every launch on it catches its own exceptions.
     */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private class Result(val outcome: Outcome, val detail: String?, val calendar: NewEventHolds.CalendarRow? = null)

    /**
     * Applies [choice] to the held series whose parent row is [eventId] of [account], from [origin] ("notification",
     * "dialog", "debug"). [expectedState] is the state the button was drawn for (null: the debug verb, any state its
     * choice allows): an entry in another state now gives [Outcome.NOT_WAITING]. [expectedTargetCalendarId] is the
     * calendar the Move button named, when it named one: a default changed since the prompt was drawn gives
     * [Outcome.FAILED], and the prompt is posted again with the new name. [requestSync] false (the debug verbs' nosync)
     * skips only the sync request, and says so. Then the prompts are brought up to date, and one UserAction line says
     * what happened; never a title. Never throws but for a cancellation.
     */
    suspend fun answer(
        context: Context,
        database: SyncDatabase,
        account: Account,
        eventId: Long,
        choice: Choice,
        expectedTargetCalendarId: Long?,
        origin: String,
        requestSync: Boolean = true,
        expectedState: State? = null,
    ): Outcome = withContext(Dispatchers.IO) {
        mutex.withLock {
            val result = try {
                decide(context, database, account, eventId, choice, expectedState, expectedTargetCalendarId, requestSync)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Result(Outcome.FAILED, e.javaClass.simpleName)
            }
            if (result.outcome != Outcome.FAILED) SyncNotificationHelper.cancelNewEventHoldPrompt(context, account.name, eventId)
            NewEventHolds.refreshOutsideSync(context, account, database)
            // A prompt posted by a refresh that read the store before this answer: down again, unless the event waits.
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            if (NewEventHoldStore.snapshot(prefs, account.name).none { it.eventId == eventId && it.state == State.WAITING }) {
                SyncNotificationHelper.cancelNewEventHoldPrompt(context, account.name, eventId)
            }
            // A calendar's name only with Verbose logging (LogNames).
            val calendar = result.calendar?.let { "calendar ${it.id}${LogNames.named(it.name)}" } ?: "its calendar"
            val line = "New event $eventId in $calendar: ${choice.name.lowercase()} from $origin: ${result.outcome}" +
                (result.detail?.let { " ($it)" } ?: "")
            if (result.outcome == Outcome.DONE) SyncLog.i(TAG, line) else SyncLog.w(TAG, line)
            result.outcome
        }
    }

    private suspend fun decide(
        context: Context,
        database: SyncDatabase,
        account: Account,
        eventId: Long,
        choice: Choice,
        expectedState: State?,
        expectedTarget: Long?,
        requestSync: Boolean,
    ): Result {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val entry = NewEventHoldStore.snapshot(prefs, account.name).firstOrNull { it.eventId == eventId }
            ?: return Result(Outcome.NOT_WAITING, "nothing is held for this event")
        val allowed = when (choice) {
            Choice.KEEP -> entry.state == State.WAITING
            Choice.MOVE, Choice.PUBLISH -> entry.state == State.WAITING || entry.state == State.KEPT
        }
        if (!allowed) return Result(Outcome.NOT_WAITING, "it is ${entry.state.name.lowercase()}")
        // A button drawn for a waiting event never acts on one the user has answered since (Keep, then a stale Publish).
        if (expectedState != null && entry.state != expectedState) {
            return Result(Outcome.NOT_WAITING, "it is ${entry.state.name.lowercase()} now, not ${expectedState.name.lowercase()}")
        }
        if (!NewEventHolds.hasCalendarPermission(context)) return Result(Outcome.FAILED, "no calendar permission")
        val mappedCalendarIds = database.calendarSyncDao().getAllByAccount(account.name).mapNotNullTo(HashSet()) { it.androidCalendarId }
        val mappedEventIds = database.calendarEventSyncDao().androidEventIdsByAccount(account.name).toHashSet()
        val client = context.contentResolver.acquireContentProviderClient(CalendarContract.AUTHORITY)
            ?: return Result(Outcome.FAILED, "the calendar provider is not available")
        return try {
            applyChoice(context, client, prefs, account, entry, choice, expectedTarget, mappedCalendarIds, mappedEventIds, requestSync)
        } finally {
            client.close()
        }
    }

    /** The provider and store part of [answer], under its mutex. Non-suspend. */
    private fun applyChoice(
        context: Context,
        client: ContentProviderClient,
        prefs: SharedPreferences,
        account: Account,
        entry: Entry,
        choice: Choice,
        expectedTarget: Long?,
        mappedCalendarIds: Set<Long>,
        mappedEventIds: Set<Long>,
        requestSync: Boolean,
    ): Result {
        val present = NewEventHolds.presentRows(client, account, listOf(entry.eventId))
            ?: return Result(Outcome.FAILED, "the event could not be read")
        val source = NewEventHolds.calendarRow(client, account, entry.calendarId)
        if (present[entry.eventId]?.calendarId != entry.calendarId) {
            // Deleted, or moved to another calendar in a calendar app: nothing left to answer here, unless rows of the
            // series are left with its UID, where the question goes on.
            return gone(context, client, prefs, account, entry, present[entry.eventId], mappedEventIds, source)
        }
        return when (choice) {
            Choice.KEEP ->
                if (NewEventHoldStore.transition(prefs, account.name, entry, entry.copy(state = State.KEPT), commit = true)) {
                    Result(Outcome.DONE, "kept on this phone, never sent to Proton", source)
                } else {
                    Result(Outcome.NOT_WAITING, "it changed meanwhile", source)
                }
            Choice.PUBLISH -> {
                if (entry.calendarId !in mappedCalendarIds || source == null ||
                    source.accessLevel < CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR
                ) {
                    return Result(Outcome.FAILED, "its calendar is not synced or not writable now", source)
                }
                if (!NewEventHoldStore.transition(prefs, account.name, entry, entry.copy(state = State.PUBLISHED), commit = true)) {
                    return Result(Outcome.NOT_WAITING, "it changed meanwhile", source)
                }
                Result(Outcome.DONE, "goes to the shared calendar with the next sync; " + requestCalendarSync(context, account, requestSync), source)
            }
            Choice.MOVE -> move(context, client, prefs, account, entry, expectedTarget, mappedCalendarIds, mappedEventIds, source, requestSync)
        }
    }

    private fun move(
        context: Context,
        client: ContentProviderClient,
        prefs: SharedPreferences,
        account: Account,
        entry: Entry,
        expectedTarget: Long?,
        mappedCalendarIds: Set<Long>,
        mappedEventIds: Set<Long>,
        source: NewEventHolds.CalendarRow?,
        requestSync: Boolean,
    ): Result {
        val target = NewEventHolds.moveTarget(client, account, entry.calendarId, mappedCalendarIds)
            ?: return Result(Outcome.FAILED, "no default calendar of this account to move it to", source)
        if (expectedTarget != null && expectedTarget != target.calendarId) {
            return Result(Outcome.FAILED, "the default calendar changed since the prompt was drawn", source)
        }
        val series = seriesRows(client, entry, mappedEventIds)
            ?: return Result(Outcome.FAILED, "the event's rows could not be read", source)
        val rows = series.rows
        if (rows.none { it.id == entry.eventId }) {
            return gone(context, client, prefs, account, entry, null, mappedEventIds, source)
        }
        val organizers = NewEventHoldRules.movePlan(rows, source?.ownerAccount, target.ownerAccount)
        val from = entry.calendarId.toString()
        val ops = ArrayList<ContentProviderOperation>()
        // The parent first, and only while it is still where the prompt saw it: the batch is one transaction.
        ops.add(
            ContentProviderOperation.newAssertQuery(CalendarContract.Events.CONTENT_URI)
                .withSelection(
                    "${CalendarContract.Events._ID} = ? AND ${CalendarContract.Events.CALENDAR_ID} = ? AND ${CalendarContract.Events.DELETED} = 0",
                    arrayOf(entry.eventId.toString(), from)
                )
                .withExpectedCount(1)
                .build()
        )
        // A plain update through the Events directory, never the sync adapter's: the provider validates the row, marks
        // it DIRTY with this app in MUTATORS, and leaves it as it was. A sync-adapter update would run scrubEventData,
        // which sets a recurring row's DTEND to NULL. The calendar in the selection keeps it to the rows read above.
        for (r in rows.sortedBy { if (it.id == entry.eventId) 0 else 1 }) {
            val op = ContentProviderOperation.newUpdate(CalendarContract.Events.CONTENT_URI)
                .withSelection("${CalendarContract.Events._ID} = ? AND ${CalendarContract.Events.CALENDAR_ID} = ?", arrayOf(r.id.toString(), from))
                .withValue(CalendarContract.Events.CALENDAR_ID, target.calendarId)
            organizers[r.id]?.let { op.withValue(CalendarContract.Events.ORGANIZER, it) }
            ops.add(op.build())
        }
        // Last, inside the same transaction: no live exception of a moved row is left outside the target calendar but the
        // ones the reads saw and leave (Proton's): not one a calendar app added to the source calendar since the read, and
        // not one in a third calendar, where an app that moved only the parent left it. The provider never moves
        // exceptions with their parent, and one left behind would go up where it sits, a cancelled one as its whole
        // series, so the series would reach Proton twice. Such a series cannot be moved (FAILED): Keep and Publish stay,
        // and its prompt and dialog do not offer Move (NewEventHolds.prompts reads where its exceptions sit).
        val moved = rows.map { it.id }
        val seen = series.seen.toList()
        ops.add(
            ContentProviderOperation.newAssertQuery(CalendarContract.Events.CONTENT_URI)
                .withSelection(
                    "${CalendarContract.Events.CALENDAR_ID} != ? AND ${CalendarContract.Events.DELETED} = 0 AND " +
                        "${CalendarContract.Events.ORIGINAL_ID} IN (${moved.joinToString(",") { "?" }}) AND " +
                        "${CalendarContract.Events._ID} NOT IN (${seen.joinToString(",") { "?" }})",
                    (listOf(target.calendarId.toString()) + moved.map { it.toString() } + seen.map { it.toString() }).toTypedArray()
                )
                .withExpectedCount(0)
                .build()
        )
        val results = try {
            client.applyBatch(ops)
        } catch (e: OperationApplicationException) {
            // The parent left the calendar between the read and the write, a new row of the series appeared there, or an
            // exception of the series sits in another calendar; nothing was written.
            val now = NewEventHolds.presentRows(client, account, listOf(entry.eventId))
            if (now != null && now[entry.eventId]?.calendarId != entry.calendarId) {
                return gone(context, client, prefs, account, entry, now[entry.eventId], mappedEventIds, source)
            }
            return Result(
                Outcome.FAILED,
                "the calendar provider refused the move, the series changed meanwhile, or a changed date of it sits in another calendar",
                source
            )
        } catch (e: Exception) {
            return Result(Outcome.FAILED, "the calendar provider refused the move (${e.javaClass.simpleName})", source)
        }
        if ((results.getOrNull(1)?.count ?: 0) == 0) {
            return gone(context, client, prefs, account, entry, null, mappedEventIds, source)
        }
        // Only after the provider has it. When this fails the entry changed meanwhile, which the next sweep settles: the
        // series sits in the default calendar now, where nothing is held.
        if (!NewEventHoldStore.transition(prefs, account.name, entry, null, commit = true)) {
            SyncLog.w(TAG, "New event ${entry.eventId}: moved, but its entry had changed meanwhile; the next sync drops it")
        }
        return Result(
            Outcome.DONE,
            "${rows.size} row(s) moved to calendar ${target.calendarId}${LogNames.named(target.name)}, ${organizers.size} organizer(s) " +
                "rewritten; " + requestCalendarSync(context, account, requestSync),
            source
        )
    }

    /**
     * The parent row of [entry] is gone or sits in another calendar ([row] as read, null when gone or not known): the
     * question ends, or passes to a row of the series left with its UID (NewEventHolds.passOnOrDrop), which asks again.
     * A failed read of those rows keeps the entry, and the answer fails.
     */
    private fun gone(
        context: Context,
        client: ContentProviderClient,
        prefs: SharedPreferences,
        account: Account,
        entry: Entry,
        row: NewEventHoldRules.PresentRow?,
        mappedEventIds: Set<Long>,
        source: NewEventHolds.CalendarRow?,
    ): Result = when (NewEventHolds.passOnOrDrop(context, client, prefs, account, entry, row, mappedEventIds)) {
        NewEventHolds.Settled.DROPPED -> Result(Outcome.NOT_WAITING, "the event is no longer in that calendar", source)
        NewEventHolds.Settled.PASSED_ON ->
            Result(Outcome.NOT_WAITING, "the event is no longer in that calendar; the rest of its series asks again", source)
        NewEventHolds.Settled.KEPT -> Result(Outcome.FAILED, "the rows of the event's series could not be read", source)
    }

    /**
     * The rows Move takes along, with their ORGANIZER: the parent, its exceptions (ORIGINAL_ID), and, with a stored UID,
     * the rows of that UID without a parent that carry nothing of Proton's (a "this and following" split's clone) and
     * their own exceptions. Only live rows of the entry's calendar. Null when the provider could not be read.
     */
    private fun seriesRows(client: ContentProviderClient, entry: Entry, mappedEventIds: Set<Long>): Series? {
        val where = StringBuilder(
            "${CalendarContract.Events.CALENDAR_ID} = ? AND ${CalendarContract.Events.DELETED} = 0 AND " +
                "(${CalendarContract.Events._ID} = ? OR ${CalendarContract.Events.ORIGINAL_ID} = ?"
        )
        val args = arrayListOf(entry.calendarId.toString(), entry.eventId.toString(), entry.eventId.toString())
        entry.uid?.takeIf { it.isNotBlank() }?.let {
            where.append(" OR (${CalendarContract.Events.UID_2445} = ? AND ${CalendarContract.Events.ORIGINAL_ID} IS NULL)")
            args.add(it)
        }
        where.append(")")
        val series = moveRows(client, entry, where.toString(), args, mappedEventIds) ?: return null
        // The exceptions of the rows found by UID, which no ORIGINAL_ID of the parent's names.
        val clones = series.filter { !it.left && it.row.id != entry.eventId && !it.exception }.map { it.row.id }
        val ofClones = if (clones.isEmpty()) emptyList<Found>() else moveRows(
            client, entry,
            "${CalendarContract.Events.CALENDAR_ID} = ? AND ${CalendarContract.Events.DELETED} = 0 AND " +
                "${CalendarContract.Events.ORIGINAL_ID} IN (${clones.joinToString(",") { "?" }})",
            listOf(entry.calendarId.toString()) + clones.map { it.toString() }, mappedEventIds, ofUidRows = true
        ) ?: return null
        val all = (series + ofClones).distinctBy { it.row.id }
        return Series(all.filterNot { it.left }.map { it.row }, all.mapTo(HashSet()) { it.row.id })
    }

    /** What Move takes along ([rows]), and every row id its reads saw ([seen]), the ones it leaves where they are too. */
    private class Series(val rows: List<NewEventHoldRules.MoveRow>, val seen: Set<Long>)

    /**
     * One row [seriesRows] read: [exception] when its ORIGINAL_ID is the parent's; [left] when it stays where it is
     * (one of Proton's that only shares the UID).
     */
    private class Found(val row: NewEventHoldRules.MoveRow, val exception: Boolean, val left: Boolean)

    /**
     * The live rows of [where] ([args]) with their ORGANIZER, for [seriesRows]. A row of the parent (itself, or an
     * exception of it) always moves; any other row ([ofUidRows]: every row) only when it carries nothing of Proton's.
     */
    private fun moveRows(
        client: ContentProviderClient,
        entry: Entry,
        where: String,
        args: List<String>,
        mappedEventIds: Set<Long>,
        ofUidRows: Boolean = false,
    ): List<Found>? = try {
        client.query(
            CalendarContract.Events.CONTENT_URI,
            arrayOf(
                CalendarContract.Events._ID, CalendarContract.Events.ORGANIZER, CalendarContract.Events.ORIGINAL_ID,
                CalendarContract.Events._SYNC_ID, CalendarContract.Events.ORIGINAL_SYNC_ID,
            ),
            where, args.toTypedArray(), null
        )?.use { c ->
            fun text(i: Int) = if (c.getType(i) == Cursor.FIELD_TYPE_STRING) c.getString(i) else null
            val out = ArrayList<Found>()
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val exception = !c.isNull(2) && c.getLong(2) == entry.eventId
                val ofParent = !ofUidRows && (id == entry.eventId || exception)
                // A row matched by UID, or an exception of one, never takes an event of Proton's along.
                val left = !ofParent && PhoneOnlyRows.isFromProton(id, text(3), text(4), mappedEventIds)
                out.add(Found(NewEventHoldRules.MoveRow(id, text(1)), exception, left))
            }
            out
        }
    } catch (e: Exception) {
        null
    }

    /**
     * Asks for the calendar sync an answer needs, as the user's own sync, and says what was done for the log line.
     * [requested] false is the debug verbs' nosync: nothing is asked. Without EXTRA_USER_SYNC_NOW: the upload runs after
     * the per-calendar passes whether or not they are skipped as unchanged, and the series goes up as a create, so the
     * answer needs no sweep of every calendar. While a full re-sync of the account's calendar is being prepared nothing is
     * asked now (FullResyncHold): a sync started inside the preparation would upload the rows it marks as ordinary
     * changes, so the sync is asked for once the re-sync's own request is posted.
     */
    private fun requestCalendarSync(context: Context, account: Account, requested: Boolean): String {
        if (!requested) return "no sync requested (nosync)"
        if (FullResyncHold.holdAnswerIfPreparing(account.name)) {
            return "no sync requested yet: a full re-sync of this calendar is being prepared, and the sync is asked for " +
                "once the re-sync's own request is posted"
        }
        return try {
            val extras = SyncScheduling.userSyncExtras(context, account.name, answering = account)
            ContentResolver.requestSync(account, SyncConstants.AUTHORITY_CALENDAR, extras)
            "calendar sync requested"
        } catch (e: Exception) {
            "the calendar sync could not be requested (${e.javaClass.simpleName}); the next sync applies it"
        }
    }
}

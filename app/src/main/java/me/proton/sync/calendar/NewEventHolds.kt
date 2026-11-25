package me.proton.sync.calendar

import android.Manifest
import android.accounts.Account
import android.content.ContentProviderClient
import android.content.ContentUris
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.database.Cursor
import android.provider.CalendarContract
import android.text.BidiFormatter
import androidx.preference.PreferenceManager
import me.proton.sync.R
import me.proton.sync.SyncLog
import me.proton.sync.SyncNotificationHelper
import me.proton.sync.SyncUtils
import me.proton.sync.calendar.NewEventHoldRules.Entry
import me.proton.sync.calendar.NewEventHoldRules.State
import me.proton.sync.db.SyncDatabase

/**
 * The Android side of the new events held in shared calendars ([NewEventHoldRules], [NewEventHoldStore]): the sweep that
 * drops entries whose question is gone, the prompts (one notification for each of at most
 * [NewEventHoldRules.MAX_PROMPTS] waiting series of an account, and one summary for the rest, on the Sync alerts
 * channel), and the target of Move, the account's default calendar: the one row of the account that stores IS_PRIMARY = 1
 * (PrimaryCalendarRows; the sync and the Default calendar row set it). Everything here is non-suspend but
 * [refreshOutsideSync], never throws, and runs off the main thread.
 *
 * Rows are read as the sync adapter of the account, so the provider limits every read to the account's calendars: a
 * row a calendar app moved to another account reads as gone.
 */
internal object NewEventHolds {

    private const val TAG = "CalendarSync"
    private const val NOTIFY_TAG = "SyncNotify"
    private const val CHUNK = 400

    /** The prompt's content intent: SyncSettingsActivity opens the decision dialog for this account and event. */
    const val EXTRA_ACCOUNT = "new_event_hold_account"
    const val EXTRA_EVENT = "new_event_hold_event"

    /** The summary's content intent, with [EXTRA_ACCOUNT]: the walk through the account's waiting events instead. */
    const val EXTRA_WALK = "new_event_hold_walk"

    /**
     * Held by [refreshPrompts] while it decides what is on screen: two refreshes at once (a sync's, the screen's) would
     * each take down and post by what they read, and together leave more prompts up than [NewEventHoldRules.MAX_PROMPTS].
     */
    private val promptLock = Any()

    /**
     * The prompts each account's last posting refresh put up (notification ids, by account name), read and written only
     * under [promptLock]. Android lists a notification only a moment after notify() returns (its service adds it on its
     * own thread), so a refresh right after another one can read a screen without the prompts that one just posted:
     * these count as on screen too, so a prompt past the limit always comes down.
     */
    private val postedPromptIds = HashMap<String, Set<Int>>()

    /** Where Move puts an event: the account's default calendar, its name without the account prefix, its owner. */
    data class MoveTarget(val calendarId: Long, val name: String, val ownerAccount: String?)

    /**
     * What a prompt about [entry] shows: the event's [title] (null or blank: untitled), its calendar's [calendarName],
     * the first occurrence's times, whether Publish is offered ([canPublish]: the calendar is mapped and writable) and
     * Move's target (null: no usable default calendar, or a changed date of the series sits in a third calendar, where
     * Move would refuse it; Move is not offered).
     */
    data class Prompt(
        val entry: Entry,
        val title: String?,
        val calendarName: String,
        val startMillis: Long?,
        val endMillis: Long?,
        val allDay: Boolean,
        val canPublish: Boolean,
        val moveTarget: MoveTarget?,
    )

    /** One calendar row of the account: its name without the account prefix, its access level and OWNER_ACCOUNT. */
    data class CalendarRow(val id: Long, val name: String, val accessLevel: Int, val ownerAccount: String?)

    /**
     * The prompt's text, the notification's and the dialog's: who added what to which calendar. The title and the
     * calendar's name come from other people's apps and choices: cleaned ([NewEventHoldRules.shownText]) and wrapped
     * for the text's direction, so neither can reorder or push away the warning around them.
     */
    fun promptText(context: Context, prompt: Prompt): String {
        val bidi = BidiFormatter.getInstance()
        val title = bidi.unicodeWrap(
            NewEventHoldRules.shownText(prompt.title) ?: context.getString(R.string.notification_conflict_untitled_event)
        )
        val calendar = bidi.unicodeWrap(NewEventHoldRules.shownText(prompt.calendarName) ?: "#${prompt.entry.calendarId}")
        val writer = prompt.entry.writer
        return if (writer != null) {
            context.getString(R.string.notification_new_event_hold_body, writer, title, calendar)
        } else {
            context.getString(R.string.notification_new_event_hold_body_unknown_app, title, calendar)
        }
    }

    /**
     * Drops the entries whose question is gone ([NewEventHoldRules.staleEntries]: the row was deleted, moved to another
     * calendar, or reached Proton; a published one also once it is clean), takes their prompts down, and returns the
     * entries left. A series whose parent row was deleted or moved away keeps its question in the oldest row left with
     * its UID ([passOnOrDrop]). [mappedEventIds] are the account's mapped Android event ids. A failed read keeps
     * everything.
     */
    fun sweep(context: Context, provider: ContentProviderClient, account: Account, mappedEventIds: Set<Long>): List<Entry> {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val entries = NewEventHoldStore.snapshot(prefs, account.name)
        if (entries.isEmpty()) return entries
        val present = presentRows(provider, account, entries.map { it.eventId })
        if (present == null) {
            SyncLog.w(TAG, "The rows of ${entries.size} waiting or kept event(s) of ${account.name} could not be read; every entry is kept")
            return entries
        }
        val stale = NewEventHoldRules.staleEntries(entries, present, mappedEventIds)
        if (stale.isEmpty()) return entries
        val dropped = stale.count { passOnOrDrop(context, provider, prefs, account, it, present[it.eventId], mappedEventIds) == Settled.DROPPED }
        if (dropped > 0) {
            val left = NewEventHoldStore.counts(prefs, account.name)
            SyncLog.w(
                TAG,
                "Dropped $dropped waiting or kept event(s) for ${account.name}: deleted, moved or synced on this phone " +
                    "(${left.waiting} waiting, ${left.kept} kept left)"
            )
            // Each dropped series took its prompt down; with none left waiting, no refresh may follow (an empty store
            // asks for none), so the summary of the ones past the prompts' limit goes here.
            if (left.waiting == 0) SyncNotificationHelper.cancelNewEventHoldSummary(context, account.name)
        }
        return NewEventHoldStore.snapshot(prefs, account.name)
    }

    /** What [passOnOrDrop] did with a stale entry. */
    enum class Settled { DROPPED, PASSED_ON, KEPT }

    /**
     * Settles the stale [entry] (its parent row as read: [row], null when gone, or when its tombstone goes up with this
     * run's upload): when the parent was deleted or moved away and rows of the series with its UID are left in its
     * calendar ([NewEventHoldRules.mayPassOn]), the question passes to the oldest of them ([Settled.PASSED_ON]; its
     * prompt follows at the next refresh); else the entry goes ([Settled.DROPPED]). Either way the old prompt comes down.
     * A failed read of those rows keeps the entry as it is ([Settled.KEPT]). For the sweep, the upload's plan (a
     * parent's tombstone, NewEventHoldRules.Plan.dropEntries), and an answer that finds the parent gone.
     */
    fun passOnOrDrop(
        context: Context,
        provider: ContentProviderClient,
        prefs: SharedPreferences,
        account: Account,
        entry: Entry,
        row: NewEventHoldRules.PresentRow?,
        mappedEventIds: Set<Long>,
    ): Settled {
        if (NewEventHoldRules.mayPassOn(entry, row)) {
            val followers = uidRows(provider, account, entry, mappedEventIds) ?: return Settled.KEPT
            val next = NewEventHoldRules.passedOn(entry, followers)
            if (next != null && NewEventHoldStore.passOn(prefs, account.name, entry, next)) {
                SyncNotificationHelper.cancelNewEventHoldPrompt(context, account.name, entry.eventId)
                SyncLog.i(
                    TAG,
                    "New event ${entry.eventId} of ${account.name} is ${if (row == null) "gone" else "in another calendar now"}: " +
                        "its question passes to event ${next.eventId}, a row of the same series still in calendar ${entry.calendarId}"
                )
                return Settled.PASSED_ON
            }
        }
        NewEventHoldStore.drop(prefs, account.name, listOf(entry))
        SyncNotificationHelper.cancelNewEventHoldPrompt(context, account.name, entry.eventId)
        return Settled.DROPPED
    }

    /**
     * The live rows of [entry]'s calendar with its UID and no ORIGINAL_ID that carry nothing of Proton's: the rows of
     * its series that follow it by UID. Null when they could not be read.
     */
    private fun uidRows(provider: ContentProviderClient, account: Account, entry: Entry, mappedEventIds: Set<Long>): List<Long>? {
        val uid = entry.uid?.takeIf { it.isNotBlank() } ?: return emptyList()
        return try {
            provider.query(
                SyncUtils.asCallerSyncAdapter(CalendarContract.Events.CONTENT_URI, account),
                arrayOf(CalendarContract.Events._ID),
                "${CalendarContract.Events.CALENDAR_ID} = ? AND ${CalendarContract.Events.DELETED} = 0 AND " +
                    "${CalendarContract.Events.ORIGINAL_ID} IS NULL AND ${CalendarContract.Events.UID_2445} = ? AND " +
                    PhoneOnlyRows.PHONE_ONLY_SQL,
                arrayOf(entry.calendarId.toString(), uid),
                null
            )?.use { c ->
                val out = ArrayList<Long>()
                while (c.moveToNext()) c.getLong(0).takeIf { it !in mappedEventIds }?.let { out.add(it) }
                out
            }
        } catch (e: Exception) {
            SyncLog.w(TAG, "Could not read the rows of the series of new event ${entry.eventId} of ${account.name}: ${e.javaClass.simpleName}")
            null
        }
    }

    /**
     * The parent rows [ids] as the provider holds them now, by id, or null when they could not be read. Only rows of
     * the account's calendars.
     */
    fun presentRows(provider: ContentProviderClient, account: Account, ids: Collection<Long>): Map<Long, NewEventHoldRules.PresentRow>? {
        val out = HashMap<Long, NewEventHoldRules.PresentRow>()
        try {
            for (chunk in ids.distinct().chunked(CHUNK)) {
                provider.query(
                    SyncUtils.asCallerSyncAdapter(CalendarContract.Events.CONTENT_URI, account),
                    arrayOf(
                        CalendarContract.Events._ID, CalendarContract.Events.CALENDAR_ID, CalendarContract.Events.DELETED,
                        CalendarContract.Events.DIRTY, CalendarContract.Events._SYNC_ID,
                    ),
                    "${CalendarContract.Events._ID} IN (${chunk.joinToString(",") { "?" }})",
                    chunk.map { it.toString() }.toTypedArray(),
                    null
                )?.use { c ->
                    while (c.moveToNext()) {
                        out[c.getLong(0)] = NewEventHoldRules.PresentRow(
                            calendarId = c.getLong(1),
                            deleted = !c.isNull(2) && c.getInt(2) != 0,
                            dirty = !c.isNull(3) && c.getInt(3) != 0,
                            syncId = if (c.getType(4) == Cursor.FIELD_TYPE_STRING) c.getString(4) else null,
                        )
                    }
                } ?: return null
            }
        } catch (e: Exception) {
            SyncLog.w(TAG, "Could not read the rows of the waiting events of ${account.name}: ${e.javaClass.simpleName}")
            return null
        }
        return out
    }

    /** What the prompts show of an event row; [deleted]: a tombstone, which gets no prompt. */
    private class Details(
        val calendarId: Long,
        val title: String?,
        val start: Long?,
        val end: Long?,
        val allDay: Boolean,
        val calendarName: String,
        val accessLevel: Int,
        val deleted: Boolean,
    )

    /**
     * The prompts of [entries] (oldest first, as given), or null when the rows could not be read. An entry whose row is
     * gone, deleted on the phone (a tombstone the next upload settles) or sits in another calendar has none; the next
     * sweep or upload ends its question. [mappedCalendarIds] are the account's mapped Android calendar ids: Publish
     * needs the calendar mapped, and Move a mapped default calendar, and a series whose changed dates all sit in its
     * own calendar or that one ([exceptionCalendars], NewEventHoldRules.moveKeepsTogether): Move refuses any other.
     * Move is checked only for the entries [checkMoveOf] picks out of those a prompt can be drawn for (given in
     * [entries]' order; all of them unless a caller says otherwise), and the others name no Move target: the check reads
     * the rows of each series, and a refresh, with hundreds of series waiting, posts a prompt for only a few of them.
     */
    fun prompts(
        context: Context,
        provider: ContentProviderClient,
        account: Account,
        mappedCalendarIds: Set<Long>,
        entries: List<Entry>,
        checkMoveOf: (List<Entry>) -> List<Entry> = { it },
    ): List<Prompt>? {
        if (entries.isEmpty()) return emptyList()
        val details = details(provider, account, entries.map { it.eventId }) ?: return null
        val drawn = entries.filter { e -> details[e.eventId]?.let { it.calendarId == e.calendarId && !it.deleted } == true }
        val target = primaryTarget(provider, account, mappedCalendarIds)
        val checked = if (target == null) emptyList() else checkMoveOf(drawn)
        val exceptionsIn = if (checked.isEmpty()) emptyMap() else exceptionCalendars(provider, account, checked)
        val checkedIds = checked.mapTo(HashSet()) { it.eventId }
        return drawn.map { e ->
            val d = details.getValue(e.eventId)
            Prompt(
                entry = e,
                title = d.title,
                calendarName = d.calendarName,
                startMillis = d.start,
                endMillis = d.end,
                allDay = d.allDay,
                canPublish = e.calendarId in mappedCalendarIds && d.accessLevel >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR,
                moveTarget = target?.takeIf {
                    e.eventId in checkedIds && it.calendarId != e.calendarId &&
                        NewEventHoldRules.moveKeepsTogether(e.calendarId, it.calendarId, exceptionsIn[e.eventId].orEmpty())
                },
            )
        }
    }

    /**
     * The calendars that hold a live exception of each entry's series, by its parent row id: exceptions of the parent
     * and of the rows of its calendar with its UID and no ORIGINAL_ID that carry nothing of Proton's, the rows Move takes
     * along. Read through the plain Events URI, as Move's closing assert counts them, so a calendar of another account
     * counts too. A failed read leaves an entry out (Move stays offered, and its own assert still refuses).
     */
    private fun exceptionCalendars(provider: ContentProviderClient, account: Account, entries: List<Entry>): Map<Long, Set<Long>> {
        val seriesOf = HashMap<Long, Long>()
        for (e in entries) {
            seriesOf[e.eventId] = e.eventId
            uidRows(provider, account, e, emptySet())?.forEach { seriesOf.putIfAbsent(it, e.eventId) }
        }
        val out = HashMap<Long, MutableSet<Long>>()
        try {
            for (chunk in seriesOf.keys.toList().chunked(CHUNK)) {
                provider.query(
                    CalendarContract.Events.CONTENT_URI,
                    arrayOf(CalendarContract.Events.ORIGINAL_ID, CalendarContract.Events.CALENDAR_ID),
                    "${CalendarContract.Events.DELETED} = 0 AND ${CalendarContract.Events.ORIGINAL_ID} IN (${chunk.joinToString(",") { "?" }})",
                    chunk.map { it.toString() }.toTypedArray(),
                    null
                )?.use { c ->
                    while (c.moveToNext()) seriesOf[c.getLong(0)]?.let { out.getOrPut(it) { HashSet() }.add(c.getLong(1)) }
                }
            }
        } catch (e: Exception) {
            SyncLog.w(TAG, "Could not read where the changed dates of the waiting events of ${account.name} sit: ${e.javaClass.simpleName}")
        }
        return out
    }

    private fun details(provider: ContentProviderClient, account: Account, ids: List<Long>): Map<Long, Details>? {
        val out = HashMap<Long, Details>()
        try {
            for (chunk in ids.distinct().chunked(CHUNK)) {
                provider.query(
                    SyncUtils.asCallerSyncAdapter(CalendarContract.Events.CONTENT_URI, account),
                    arrayOf(
                        CalendarContract.Events._ID, CalendarContract.Events.TITLE, CalendarContract.Events.DTSTART,
                        CalendarContract.Events.DTEND, CalendarContract.Events.DURATION, CalendarContract.Events.ALL_DAY,
                        CalendarContract.Events.CALENDAR_ID, CalendarContract.Events.CALENDAR_DISPLAY_NAME,
                        CalendarContract.Events.CALENDAR_ACCESS_LEVEL, CalendarContract.Events.DELETED,
                    ),
                    "${CalendarContract.Events._ID} IN (${chunk.joinToString(",") { "?" }})",
                    chunk.map { it.toString() }.toTypedArray(),
                    null
                )?.use { c ->
                    fun text(i: Int) = if (c.getType(i) == Cursor.FIELD_TYPE_STRING) c.getString(i) else null
                    fun number(i: Int) = if (c.getType(i) == Cursor.FIELD_TYPE_INTEGER) c.getLong(i) else null
                    while (c.moveToNext()) {
                        val start = number(2)
                        val duration = text(4)?.takeIf { it.isNotBlank() }
                        val end = number(3) ?: if (start != null && duration != null) {
                            try {
                                start + ICalUtils.parseDurationToMillis(duration)
                            } catch (e: Exception) {
                                null
                            }
                        } else null
                        out[c.getLong(0)] = Details(
                            calendarId = c.getLong(6),
                            title = text(1),
                            start = start,
                            end = end,
                            allDay = (number(5) ?: 0L) != 0L,
                            calendarName = displayName(text(7), account) ?: "#${c.getLong(6)}",
                            accessLevel = number(8)?.toInt() ?: 0,
                            deleted = (number(9) ?: 0L) != 0L,
                        )
                    }
                } ?: return null
            }
        } catch (e: Exception) {
            SyncLog.w(TAG, "Could not read the waiting events of ${account.name}: ${e.javaClass.simpleName}")
            return null
        }
        return out
    }

    /** [name] without the "<account> - " prefix several accounts give each calendar, or null when blank. */
    private fun displayName(name: String?, account: Account): String? =
        name?.removePrefix("${account.name} - ")?.takeIf { it.isNotBlank() }

    /** Calendar row [calendarId] of [account], or null when it is not one of the account's or could not be read. */
    fun calendarRow(provider: ContentProviderClient, account: Account, calendarId: Long): CalendarRow? = try {
        provider.query(
            ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI, calendarId),
            arrayOf(
                CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
                CalendarContract.Calendars.OWNER_ACCOUNT, CalendarContract.Calendars.ACCOUNT_NAME,
                CalendarContract.Calendars.ACCOUNT_TYPE,
            ),
            null, null, null
        )?.use { c ->
            if (!c.moveToFirst() || c.getString(3) != account.name || c.getString(4) != account.type) return@use null
            CalendarRow(
                id = calendarId,
                name = displayName(c.getString(0), account) ?: "#$calendarId",
                accessLevel = if (c.isNull(1)) 0 else c.getInt(1),
                ownerAccount = c.getString(2),
            )
        }
    } catch (e: Exception) {
        SyncLog.w(TAG, "Could not read calendar $calendarId of ${account.name}: ${e.javaClass.simpleName}")
        null
    }

    /**
     * Move's target for an event in [sourceCalendarId]: the account's only row that STORES IS_PRIMARY = 1
     * ([PrimaryCalendarRows.read]), when it is not the source, is mapped ([mappedCalendarIds]) and accepts writes
     * (CAL_ACCESS_CONTRIBUTOR or more). Null when there is none, or more than one: Move is not offered then.
     */
    fun moveTarget(
        provider: ContentProviderClient,
        account: Account,
        sourceCalendarId: Long,
        mappedCalendarIds: Set<Long>,
    ): MoveTarget? = primaryTarget(provider, account, mappedCalendarIds)?.takeIf { it.calendarId != sourceCalendarId }

    private fun primaryTarget(provider: ContentProviderClient, account: Account, mappedCalendarIds: Set<Long>): MoveTarget? {
        val rows = try {
            PrimaryCalendarRows.read(provider, account)
        } catch (e: Exception) {
            SyncLog.w(TAG, "Could not read which calendar of ${account.name} is primary: ${e.javaClass.simpleName}")
            null
        } ?: return null
        val primary = rows.filter { it.stored == 1 && it.accountName == account.name && it.accountType == account.type }
            .singleOrNull() ?: return null
        if (primary.id !in mappedCalendarIds) return null
        val row = calendarRow(provider, account, primary.id) ?: return null
        if (row.accessLevel < CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR) return null
        return MoveTarget(row.id, row.name, row.ownerAccount)
    }

    /**
     * Brings the prompts of [account] in line with its waiting series: at most [NewEventHoldRules.MAX_PROMPTS] of them
     * get a prompt of their own ([NewEventHoldRules.promptPlan]: [alertId], a series held in this run, first, then the
     * newest), posted silently but for [alertId]; the rest are counted on one summary ("N more events waiting for your
     * choice"), which goes once no more wait than that. Prompts of kept and published series, and of waiting ones past
     * the limit, come down first (those on screen, and those the last refresh posted, which Android may not list yet),
     * so no more than the limit is ever up. The summary is posted before the prompts, so its count is the update Android
     * is the least likely to drop. Logs a W line when none could be shown: notifications off for the app or its alerts
     * channel, or the rows unreadable; the account card lists them anyway.
     *
     * Runs beside the answers (a sync, the screen's resume, the dialog, the Default calendar row), so each prompt is
     * posted only while its entry is still stored as read, and taken down again when an answer or a sweep changed it
     * during the post: an answer commits before it takes its prompt down, so either it sees this post or this check
     * sees the answer, and an answered prompt never comes back. One refresh at a time ([promptLock]); an answer's own
     * refresh then gives its place to the next series.
     */
    fun refreshPrompts(
        context: Context,
        provider: ContentProviderClient,
        account: Account,
        mappedCalendarIds: Set<Long>,
        alertId: Long?,
    ) {
        synchronized(promptLock) { postPrompts(context, provider, account, mappedCalendarIds, alertId) }
    }

    /** [refreshPrompts]' work, under its lock. Never throws. */
    private fun postPrompts(
        context: Context,
        provider: ContentProviderClient,
        account: Account,
        mappedCalendarIds: Set<Long>,
        alertId: Long?,
    ) {
        try {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val all = NewEventHoldStore.snapshot(prefs, account.name)
            // What is on screen, read once, and what the last refresh posted (Android lists a post a moment after it is
            // made): only those come down. Unreadable (null), every prompt that may be up does.
            val onScreen = SyncNotificationHelper.activeNotificationIds(context)?.let { it + postedPromptIds[account.name].orEmpty() }
            for (e in all) if (e.state != State.WAITING) takeDownPrompt(context, account, e, onScreen)
            val waiting = all.filter { it.state == State.WAITING }
            if (waiting.isEmpty()) {
                SyncNotificationHelper.cancelNewEventHoldSummary(context, account.name)
                return
            }
            // Move is checked only for the series that get a prompt of their own: the same plan as below, from the same list.
            val prompts = prompts(context, provider, account, mappedCalendarIds, waiting) { drawn -> NewEventHoldRules.promptPlan(drawn, alertId).shown }
            if (prompts == null) {
                SyncLog.w(NOTIFY_TAG, "No prompt posted for the ${waiting.size} event(s) of ${account.name} waiting in a shared calendar: their rows could not be read; the account card lists them")
                return
            }
            val plan = NewEventHoldRules.promptPlan(prompts.map { it.entry }, alertId)
            val chosen = plan.shown.toHashSet()
            // Past the limit (and those no prompt can be drawn for): down before any is posted.
            for (e in waiting) if (e !in chosen) takeDownPrompt(context, account, e, onScreen)
            // The summary before the prompts: Android drops an update of a notification already up, without an error,
            // while the app posts more than about five a second, and every refresh posts the prompts again, so the
            // count goes first, where it is the post least likely to be dropped.
            showOrCancelSummary(context, account, plan.rest)
            var shown = 0
            val posted = HashSet<Int>()
            for (p in prompts) {
                if (p.entry !in chosen) continue
                if (!NewEventHoldStore.isStored(prefs, account.name, p.entry)) continue
                if (SyncNotificationHelper.showNewEventHoldPrompt(context, account.name, p, alert = p.entry.eventId == alertId)) {
                    shown++
                    posted += SyncNotificationHelper.newEventHoldNotificationId(account.name, p.entry.eventId)
                }
                if (!NewEventHoldStore.isStored(prefs, account.name, p.entry)) {
                    SyncNotificationHelper.cancelNewEventHoldPrompt(context, account.name, p.entry.eventId)
                }
            }
            postedPromptIds[account.name] = posted
            if (shown == 0 && prompts.isNotEmpty()) {
                SyncLog.w(
                    NOTIFY_TAG,
                    "Nothing shown for the ${prompts.size} event(s) of ${account.name} waiting in a shared calendar: " +
                        "notifications are off for the app or its alerts channel; the account card lists them"
                )
            }
        } catch (e: Exception) {
            SyncLog.w(NOTIFY_TAG, "Could not post the prompts of the waiting events of ${account.name}: ${e.javaClass.simpleName}")
        }
    }

    /** Takes [entry]'s prompt down when it is on screen ([onScreen], notification ids; null: unknown, so it does). */
    private fun takeDownPrompt(context: Context, account: Account, entry: Entry, onScreen: Set<Int>?) {
        if (onScreen == null || SyncNotificationHelper.newEventHoldNotificationId(account.name, entry.eventId) in onScreen) {
            SyncNotificationHelper.cancelNewEventHoldPrompt(context, account.name, entry.eventId)
        }
    }

    /**
     * The summary of the waiting series [rest] that get no prompt of their own, dated by the newest of them; taken down
     * when there is none. Logged only when it could not be shown.
     */
    private fun showOrCancelSummary(context: Context, account: Account, rest: List<Entry>) {
        if (rest.isEmpty()) {
            SyncNotificationHelper.cancelNewEventHoldSummary(context, account.name)
            return
        }
        if (!SyncNotificationHelper.showNewEventHoldSummary(context, account.name, rest.size, rest.maxOf { it.heldAtMillis })) {
            SyncLog.w(NOTIFY_TAG, "The summary of ${rest.size} more event(s) of ${account.name} waiting in a shared calendar could not be shown; the account card lists them")
        }
    }

    /**
     * [sweep] and [refreshPrompts] outside a calendar sync: after an answer, when the settings screen or the decision
     * dialog opens, and after the Default calendar row moved the primary row (the prompts name it on their Move
     * button). Reads this account's mappings from [database] and opens its own provider client. Returns at once when
     * nothing is held, without the calendar permission, or when the mappings cannot be read. On a background thread;
     * never throws but for a cancellation.
     */
    suspend fun refreshOutsideSync(context: Context, account: Account, database: SyncDatabase, alertId: Long? = null) {
        try {
            if (NewEventHoldStore.snapshot(PreferenceManager.getDefaultSharedPreferences(context), account.name).isEmpty()) return
            if (!hasCalendarPermission(context)) return
            val mappedCalendarIds = database.calendarSyncDao().getAllByAccount(account.name).mapNotNullTo(HashSet()) { it.androidCalendarId }
            val mappedEventIds = database.calendarEventSyncDao().androidEventIdsByAccount(account.name).toHashSet()
            val client = context.contentResolver.acquireContentProviderClient(CalendarContract.AUTHORITY) ?: return
            try {
                sweep(context, client, account, mappedEventIds)
                refreshPrompts(context, client, account, mappedCalendarIds, alertId)
            } finally {
                client.close()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            SyncLog.w(TAG, "The waiting events of ${account.name} could not be checked: ${e.javaClass.simpleName}")
        }
    }

    fun hasCalendarPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED
}

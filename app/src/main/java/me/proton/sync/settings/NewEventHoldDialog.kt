package me.proton.sync.settings

import android.accounts.Account
import android.content.Context
import android.content.DialogInterface
import android.os.Bundle
import android.provider.CalendarContract
import android.text.format.DateUtils
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.proton.sync.Plurals
import me.proton.sync.R
import me.proton.sync.SyncLog
import me.proton.sync.calendar.NewEventHoldActions
import me.proton.sync.calendar.NewEventHoldActions.Choice
import me.proton.sync.calendar.NewEventHoldRules.State
import me.proton.sync.calendar.NewEventHoldStore
import me.proton.sync.calendar.NewEventHolds
import me.proton.sync.db.SyncDatabase

/**
 * The decision dialog about a new event an app wrote into a calendar someone else shared with the account: one event,
 * as tapping its notification opens it, or a walk through the waiting (or the kept) events, oldest first, as the
 * account card's rows open it. Shows the prompt's text, the event's date and what the three choices mean, and answers
 * through [NewEventHoldActions.answer], the handler the notification's buttons use.
 *
 * A waiting event: Move to the default calendar (or, without one, Keep on phone) is the main button, Keep on phone the
 * neutral one, Publish the negative one when the calendar is writable. A kept event: Move, Publish, and Cancel. After an
 * answer a walk shows the next event; Cancel or Back ends it; a FAILED answer shows the same event again. The answer
 * runs on NewEventHoldActions.scope, so a rotation cannot cut it in half, and a screen re-created while the dialog is
 * up shows it again ([saveState], [restoreState]). Every answer names the state the dialog showed, so one given on the
 * notification meanwhile is not undone. Logs what it showed and which buttons, never a title.
 */
internal class NewEventHoldDialog(private val activity: AppCompatActivity, private val database: SyncDatabase) {

    enum class Walk { WAITING, KEPT }

    private class Loaded(val prompt: NewEventHolds.Prompt, val left: Int)

    /** What the dialog on screen shows, for [saveState]: the one event (or, in a walk, the event shown again), the walk. */
    private class Shown(val accountName: String, val eventId: Long?, val walk: Walk, val inWalk: Boolean, val requestSync: Boolean)

    /** The dialog on screen, so a second show replaces it. */
    private var shownDialog: AlertDialog? = null
    private var shown: Shown? = null

    /**
     * Keeps what the dialog on screen shows in [out], so a screen re-created (rotation, dark mode, a language change)
     * shows it again ([restoreState]); nothing when none is up. A debug press is never repeated.
     */
    fun saveState(out: Bundle) {
        val s = shown?.takeIf { shownDialog?.isShowing == true } ?: return
        out.putString(KEY_ACCOUNT, s.accountName)
        out.putLong(KEY_EVENT, s.eventId ?: -1L)
        out.putString(KEY_WALK, s.walk.name)
        out.putBoolean(KEY_IN_WALK, s.inWalk)
        out.putBoolean(KEY_REQUEST_SYNC, s.requestSync)
    }

    /**
     * Shows again the dialog [saveState] kept in [saved], for the account [accountOf] finds by its name; returns
     * whether it does. [onAnswered] as for [show]. Main thread.
     */
    fun restoreState(saved: Bundle?, accountOf: (String) -> Account?, onAnswered: (Account) -> Unit): Boolean {
        val name = saved?.getString(KEY_ACCOUNT) ?: return false
        val account = accountOf(name) ?: return false
        val walk = Walk.entries.firstOrNull { it.name == saved.getString(KEY_WALK) } ?: Walk.WAITING
        val eventId = saved.getLong(KEY_EVENT, -1L).takeIf { it > 0 }
        SyncLog.i(TAG, "New event holds: the dialog comes back for '${account.name}' after the screen was re-created")
        show(
            account, eventId = eventId, walk = walk, requestSync = saved.getBoolean(KEY_REQUEST_SYNC, true),
            inWalk = saved.getBoolean(KEY_IN_WALK, false), onAnswered = { onAnswered(account) }
        )
        return true
    }

    /**
     * Show it for [account]: the event [eventId] (waiting or kept), or else the oldest of [walk]. [press] (the
     * event-hold-dialog debug verb only) presses the button that carries that choice through its own listener;
     * [requestSync] false is that verb's nosync. [onAnswered] runs on the main thread after every answer, as the card
     * row's redraw. [inWalk]: [eventId] shown again inside a walk, after a failed answer, which the walk goes on from.
     * Main thread.
     */
    fun show(
        account: Account,
        eventId: Long? = null,
        walk: Walk = Walk.WAITING,
        press: Choice? = null,
        requestSync: Boolean = true,
        inWalk: Boolean = false,
        onAnswered: () -> Unit,
    ) {
        val app = activity.applicationContext
        activity.lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) { load(app, account, eventId, walk) }
            if (activity.isFinishing || activity.isDestroyed) return@launch
            if (loaded == null) {
                SyncLog.i(TAG, "New event holds: nothing waiting (or kept) for '${account.name}'" +
                    (eventId?.let { " for event $it" } ?: " (${walk.name.lowercase()})"))
                return@launch
            }
            present(account, loaded, single = eventId != null && !inWalk, walk = walk, press = press, requestSync = requestSync, onAnswered = onAnswered)
        }
    }

    private suspend fun load(context: Context, account: Account, eventId: Long?, walk: Walk): Loaded? {
        NewEventHolds.refreshOutsideSync(context, account, database)
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val entries = NewEventHoldStore.snapshot(prefs, account.name)
        val state = if (walk == Walk.KEPT) State.KEPT else State.WAITING
        val entry = if (eventId != null) {
            entries.firstOrNull { it.eventId == eventId && it.state != State.PUBLISHED }
        } else {
            entries.filter { it.state == state }.minByOrNull { it.heldAtMillis }
        } ?: return null
        if (!NewEventHolds.hasCalendarPermission(context)) return null
        val mappedCalendarIds = try {
            database.calendarSyncDao().getAllByAccount(account.name).mapNotNullTo(HashSet()) { it.androidCalendarId }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            SyncLog.w(TAG, "New event holds: the calendar mappings of '${account.name}' could not be read: ${e.javaClass.simpleName}")
            return null
        }
        val client = context.contentResolver.acquireContentProviderClient(CalendarContract.AUTHORITY) ?: return null
        val prompt = try {
            NewEventHolds.prompts(context, client, account, mappedCalendarIds, listOf(entry))?.firstOrNull()
        } finally {
            client.close()
        } ?: return null
        return Loaded(prompt, entries.count { it.state == entry.state })
    }

    private fun present(
        account: Account,
        loaded: Loaded,
        single: Boolean,
        walk: Walk,
        press: Choice?,
        requestSync: Boolean,
        onAnswered: () -> Unit,
    ) {
        shownDialog?.let { if (it.isShowing) it.dismiss() }
        val prompt = loaded.prompt
        val entry = prompt.entry
        val kept = entry.state == State.KEPT
        val target = prompt.moveTarget
        val title = when {
            single -> activity.getString(R.string.notification_new_event_hold_title)
            kept -> Plurals.text(activity, R.plurals.label_new_event_kept, loaded.left, loaded.left)
            else -> Plurals.text(activity, R.plurals.label_new_event_holds, loaded.left, loaded.left)
        }
        val message = NewEventHolds.promptText(activity, prompt) + dateLine(prompt) + "\n\n" +
            activity.getString(R.string.info_new_event_holds_body)

        // Which button carries which choice, for the log and the debug verb's press.
        val buttons = LinkedHashMap<Choice, Int>()
        val builder = AppDialogBuilder(activity).setTitle(title).setMessage(message)
        fun onChoice(choice: Choice) = DialogInterface.OnClickListener { _, _ ->
            answer(account, entry.eventId, entry.state, choice, target?.calendarId, single, walk, requestSync, onAnswered)
        }
        if (target != null) {
            builder.setPositiveButton(activity.getString(R.string.action_new_event_move, target.name), onChoice(Choice.MOVE))
            buttons[Choice.MOVE] = DialogInterface.BUTTON_POSITIVE
        }
        if (!kept) {
            if (target == null) {
                builder.setPositiveButton(R.string.remote_deletion_keep, onChoice(Choice.KEEP))
                buttons[Choice.KEEP] = DialogInterface.BUTTON_POSITIVE
            } else {
                builder.setNeutralButton(R.string.remote_deletion_keep, onChoice(Choice.KEEP))
                buttons[Choice.KEEP] = DialogInterface.BUTTON_NEUTRAL
            }
        }
        if (prompt.canPublish) {
            builder.setNegativeButton(R.string.action_new_event_publish, onChoice(Choice.PUBLISH))
            buttons[Choice.PUBLISH] = DialogInterface.BUTTON_NEGATIVE
        }
        if (kept) builder.setNeutralButton(R.string.btn_cancel, null)
        builder.setCancelable(true)
        val dialog = builder.create()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY && !activity.isDestroyed && dialog.isShowing) dialog.dismiss()
        }
        activity.lifecycle.addObserver(observer)
        dialog.setOnDismissListener {
            activity.lifecycle.removeObserver(observer)
            if (shownDialog === dialog) {
                shownDialog = null
                shown = null
            }
        }
        dialog.show()
        shownDialog = dialog
        // A walk comes back on the event it showed, and goes on from there.
        shown = Shown(account.name, entry.eventId, walk, inWalk = !single, requestSync = requestSync)
        val what = if (single) "one event" else "${walk.name.lowercase()}, ${loaded.left} left"
        SyncLog.i(
            TAG,
            "New event holds: dialog shown ($what) for '${account.name}': ${entry.state.name.lowercase()} event ${entry.eventId} " +
                "in calendar ${entry.calendarId}, move=${yes(Choice.MOVE in buttons)} keep=${yes(Choice.KEEP in buttons)} " +
                "publish=${yes(Choice.PUBLISH in buttons)}"
        )
        if (press != null) {
            val which = buttons[press]
            if (which == null) {
                SyncLog.w(TAG, "New event holds: press ${press.name.lowercase()} ignored: the dialog has no such button")
            } else {
                dialog.getButton(which)?.performClick()
            }
        }
    }

    /**
     * The answer, on the answers' own scope; then [onAnswered], and in a walk the next event, on the main thread.
     * [shownState] is the state the dialog showed: an answer given elsewhere since (the notification's) wins.
     */
    private fun answer(
        account: Account,
        eventId: Long,
        shownState: State,
        choice: Choice,
        shownTarget: Long?,
        single: Boolean,
        walk: Walk,
        requestSync: Boolean,
        onAnswered: () -> Unit,
    ) {
        val app = activity.applicationContext
        NewEventHoldActions.scope.launch {
            try {
                val outcome = NewEventHoldActions.answer(
                    app, database, account, eventId, choice,
                    expectedTargetCalendarId = if (choice == Choice.MOVE) shownTarget else null,
                    origin = "dialog", requestSync = requestSync, expectedState = shownState
                )
                withContext(Dispatchers.Main) {
                    if (activity.isFinishing || activity.isDestroyed) return@withContext
                    onAnswered()
                    when {
                        outcome == NewEventHoldActions.Outcome.FAILED -> show(
                            account, eventId = eventId, walk = walk, requestSync = requestSync, inWalk = !single, onAnswered = onAnswered
                        )
                        !single -> show(account, walk = walk, requestSync = requestSync, onAnswered = onAnswered)
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                SyncLog.w(TAG, "New event holds: the answer for event $eventId could not be finished: ${e.javaClass.simpleName}")
            }
        }
    }

    /** "\n" and the event's first date (and time), or nothing when it has none; all-day events in UTC. */
    private fun dateLine(prompt: NewEventHolds.Prompt): String {
        val start = prompt.startMillis ?: return ""
        val end = prompt.endMillis?.takeIf { it >= start } ?: start
        var flags = DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_SHOW_YEAR
        if (!prompt.allDay) flags = flags or DateUtils.FORMAT_SHOW_TIME
        // An all-day event's times are UTC midnights: read in UTC, or a phone west of UTC shows the day before.
        val zone = if (prompt.allDay) "UTC" else null
        return "\n" + withoutLongDashes(DateUtils.formatDateRange(activity, java.util.Formatter(StringBuilder(64)), start, end, flags, zone).toString())
    }

    private fun yes(b: Boolean) = if (b) "yes" else "no"

    private companion object {
        const val TAG = "SyncSettings"
        const val KEY_ACCOUNT = "new_event_hold_dialog_account"
        const val KEY_EVENT = "new_event_hold_dialog_event"
        const val KEY_WALK = "new_event_hold_dialog_walk"
        const val KEY_IN_WALK = "new_event_hold_dialog_in_walk"
        const val KEY_REQUEST_SYNC = "new_event_hold_dialog_request_sync"
    }
}

/**
 * [text] with every dash Android's date-range formatter may put between two dates or times (a figure dash, an en dash,
 * an em dash or a horizontal bar; most languages get an en dash between "17:00" and "18:00") turned into a hyphen-minus,
 * nothing else changed: the app shows no en or em dash anywhere, in any language (the house style the dash scan holds
 * the app's own texts to). Every DateUtils.formatDateRange result the app shows goes through it (NewEventHoldDateLineTest).
 */
internal fun withoutLongDashes(text: String): String = LONG_DASHES.replace(text, "-")

private val LONG_DASHES = Regex("[‒–—―]")

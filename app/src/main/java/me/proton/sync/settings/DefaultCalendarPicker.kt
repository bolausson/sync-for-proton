package me.proton.sync.settings

import android.accounts.Account
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.proton.sync.R
import me.proton.sync.SyncLog
import me.proton.sync.backup.BackupDependenciesEntryPoint
import me.proton.sync.calendar.DefaultCalendarChoices
import me.proton.sync.calendar.DefaultCalendarChoices.SaveOutcome
import me.proton.sync.calendar.DefaultCalendarService
import me.proton.sync.calendar.DefaultCalendarService.Fixture
import me.proton.sync.calendar.DefaultCalendarService.Kind
import me.proton.sync.calendar.DefaultCalendarService.Load
import me.proton.sync.calendar.NewEventHolds
import me.proton.sync.db.SyncDatabase
import java.util.concurrent.ConcurrentHashMap

/**
 * The account card's "Default calendar" row: a waiting dialog while Proton is read, then a single-choice list of the
 * account's own active calendars with Proton's default checked, and a change of Proton's default when another one is
 * chosen (DefaultCalendarService). The row's tap and the default_calendar debug verb both enter through [show].
 *
 * The load runs in the screen's lifecycleScope, so turning the phone or changing the language cancels it (only reads
 * were sent). Its key unlock blocks, and a cancellation cannot stop it, so Cancel and the screen's end free the row at
 * once instead of when the abandoned load returns ([busy] holds one token per load). A change runs on [saveScope] and
 * completes across that: its toast's text is read while the screen is alive and shown with the application context, a
 * tap on the row meanwhile shows the waiting dialog until the change ends, and the redrawn card shows the result from
 * the record (the screen's preference listener).
 *
 * No subscription gate, as with the name repair: it changes a Proton setting and a flag on the phone. It asks for a
 * sync only when Proton confirmed a calendar that has no row on this phone yet: the ordinary calendar sync that brings
 * that calendar down (SyncScheduling.requestSettingsSync, none while Android's automatic sync is off).
 */
internal class DefaultCalendarPicker(private val activity: AppCompatActivity, private val database: SyncDatabase) {

    /**
     * The default_calendar debug verb: [Mode.LIST] is the load without a dialog, [Mode.OPEN] the row's tap,
     * [Mode.CANCEL] the tap with the waiting dialog's Cancel pressed through its own listener as soon as it shows,
     * [Mode.PICK] the tap with the offered calendar [namePart] names pressed through the list's own item listener.
     * [fixture] stands in for Proton (none sends a change).
     */
    data class Debug(val mode: Mode, val namePart: String?, val fixture: Fixture) {
        enum class Mode { LIST, OPEN, CANCEL, PICK }

        companion object {
            /**
             * The verb's extras, or null when they do not make one: a mode other than list, open, cancel or pick, pick
             * without a name part, or a fixture other than none, offline, offline_save, timeout_save, refuse, auth,
             * incomplete or none_own (missing or empty reads as none).
             */
            fun parse(mode: String?, part: String?, fixture: String?): Debug? {
                val m = when (mode) {
                    "list" -> Mode.LIST
                    "open" -> Mode.OPEN
                    "cancel" -> Mode.CANCEL
                    "pick" -> Mode.PICK
                    else -> return null
                }
                val p = part?.trim()?.takeIf { it.isNotEmpty() }
                if (m == Mode.PICK && p == null) return null
                val f = when (fixture) {
                    null, "", "none" -> Fixture.NONE
                    "offline" -> Fixture.OFFLINE
                    "offline_save" -> Fixture.OFFLINE_SAVE
                    "timeout_save" -> Fixture.TIMEOUT_SAVE
                    "refuse" -> Fixture.REFUSE
                    "auth" -> Fixture.AUTH
                    "incomplete" -> Fixture.INCOMPLETE
                    "none_own" -> Fixture.NONE_OWN
                    else -> return null
                }
                return Debug(m, p, f)
            }
        }
    }

    /** What [busy] holds while an account's change is on its way: its job, so a tap meanwhile can wait for it. */
    private class SaveHold {
        @Volatile
        var job: Job? = null
    }

    private val service by lazy {
        val deps = EntryPointAccessors.fromApplication(activity.applicationContext, BackupDependenciesEntryPoint::class.java)
        // A moved primary row renames the Move button of the prompts about new events in shared calendars.
        DefaultCalendarService(deps.apiClient(), deps.sessionManager(), deps.keyManager(), database.calendarSyncDao()) { context, account ->
            NewEventHolds.refreshOutsideSync(context, account, database)
        }
    }

    /** The list this picker shows, so that a second tap replaces it. */
    private var shownList: AlertDialog? = null

    /** The row's tap for [account], or the debug verb's [debug]. Main thread. */
    fun show(account: Account, debug: Debug? = null) {
        val name = account.name
        val token = Any()
        val holder = busy.putIfAbsent(name, token)
        if (holder != null) {
            val save = (holder as? SaveHold)?.job
            if (save != null && save.isActive && debug?.mode != Debug.Mode.LIST) {
                SyncLog.d(TAG, "Default calendar for '$name': a change is on its way; waiting for it")
                waitFor(save)
            } else {
                SyncLog.d(TAG, "Default calendar for '$name': already busy")
            }
            return
        }
        val app = activity.applicationContext
        val fixture = debug?.fixture ?: Fixture.NONE
        val listOnly = debug?.mode == Debug.Mode.LIST
        val progress = if (listOnly) null else waiting(withCancel = true)
        // The screen's end frees the row at once: the load may still be blocked in the key unlock, and the recreated
        // card's row must not wait for it. Its late end changes nothing, since lifecycleScope has cancelled it.
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY) {
                busy.remove(name, token)
                progress?.let { dismissIfAttached(it) }
            }
        }
        activity.lifecycle.addObserver(observer)
        val service = this.service
        // Started only once the waiting dialog shows, so that a load which ends at once never leaves it up.
        val job = activity.lifecycleScope.launch(start = CoroutineStart.LAZY) {
            val load = try {
                withContext(Dispatchers.IO) { service.load(app, account, fixture) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Load.Failed(Kind.UNREACHABLE, e.javaClass.simpleName)
            } finally {
                // Released before any list shows, so that the list's own handler can take it again.
                busy.remove(name, token)
                activity.lifecycle.removeObserver(observer)
                progress?.let { dismissIfAttached(it) }
            }
            if (activity.isFinishing || activity.isDestroyed) return@launch
            if (listOnly) {
                SyncLog.i(TAG, "Default calendar for '$name': list: ${describe(load)}")
                return@launch
            }
            when (load) {
                // The activity's context, alive here: below Android 13 only it carries the in-app language.
                is Load.Failed -> when (load.kind) {
                    Kind.INCOMPLETE -> message(R.string.default_calendar_incomplete)
                    Kind.AUTH -> Toast.makeText(activity, R.string.error_session_expired, Toast.LENGTH_LONG).show()
                    Kind.UNREACHABLE -> Toast.makeText(activity, R.string.default_calendar_unreachable, Toast.LENGTH_LONG).show()
                }
                Load.NoOwnCalendar -> message(R.string.default_calendar_none_own)
                is Load.Ready -> present(account, load.offer, debug, fixture)
            }
        }
        // However the load ends, also one that never ran because the screen was already gone.
        job.invokeOnCompletion { busy.remove(name, token) }
        if (progress != null) {
            progress.setOnShowListener {
                val cancel = progress.getButton(AlertDialog.BUTTON_NEGATIVE)
                cancel?.setOnClickListener {
                    // Free at once: the load may be blocked in the key unlock, which a cancellation cannot stop.
                    busy.remove(name, token)
                    job.cancel()
                    progress.dismiss()
                    SyncLog.i(TAG, "Default calendar for '$name': loading cancelled")
                }
                if (debug?.mode == Debug.Mode.CANCEL) {
                    if (job.isActive) cancel?.performClick()
                    else SyncLog.i(TAG, "Default calendar for '$name': cancel not pressed, the load had already ended")
                }
            }
            progress.show()
        }
        job.start()
    }

    private fun present(account: Account, offer: DefaultCalendarChoices.Offer, debug: Debug?, fixture: Fixture) {
        shownList?.let { dismissIfAttached(it) }
        var answered = false
        val names = offer.choices.map { it.name as CharSequence }.toTypedArray()
        val dialog = AppDialogBuilder(activity)
            .setTitle(R.string.label_default_calendar)
            .setSingleChoiceItems(names, offer.checkedIndex) { d, which ->
                if (answered) return@setSingleChoiceItems
                answered = true
                d.dismiss()
                onPicked(account, offer, which, fixture)
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
        shownList = dialog
        val observer = dismissAtDestroy(dialog)
        dialog.setOnDismissListener {
            activity.lifecycle.removeObserver(observer)
            if (shownList === dialog) shownList = null
        }
        SyncLog.i(TAG, "Default calendar for '${account.name}': list shown with ${offer.choices.size} calendar(s)")
        if (debug?.mode == Debug.Mode.PICK) {
            val i = DefaultCalendarChoices.matchByNamePart(offer.choices, debug.namePart)
            if (i == null) {
                SyncLog.w(
                    TAG,
                    "Default calendar for '${account.name}': pick ignored: '${debug.namePart}' names none or several of " +
                        "the offered calendars"
                )
                dialog.dismiss()
            } else {
                // Through the list's own item listener, as a tap on that line runs it.
                dialog.listView.performItemClick(null, i, i.toLong())
            }
        }
    }

    /** The list's answer: the calendar at [which] of [offer]. */
    private fun onPicked(account: Account, offer: DefaultCalendarChoices.Offer, which: Int, fixture: Fixture) {
        val app = activity.applicationContext
        val name = account.name
        val choice = offer.choices[which]
        if (!DefaultCalendarChoices.needsPut(choice.protonCalendarId, offer.storedId)) {
            // The load brought the phone in line with this read of Proton when the list opened. Nothing is applied now:
            // the list may be older than a change made since, on Proton or by a calendar sync.
            SyncLog.i(USER_ACTION, "Default calendar for '$name': ${shown(choice)} is already Proton's default; nothing sent")
            return
        }
        val hold = SaveHold()
        if (busy.putIfAbsent(name, hold) != null) {
            SyncLog.d(TAG, "Default calendar for '$name': already busy")
            return
        }
        // Read while the screen is alive: below Android 13 the in-app language reaches only activity contexts, and the
        // toast may come after the screen was recreated.
        val texts = TOASTS.associateWith { activity.getString(it) }
        val service = this.service
        // No button: the change is on its way, and it completes whatever the screen does.
        val progress = waiting(withCancel = false)
        val observer = dismissAtDestroy(progress)
        progress.show()
        hold.job = saveScope.launch {
            try {
                val outcome = try {
                    service.save(app, account, offer, choice, fixture)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    SaveOutcome.Unreachable(e.javaClass.simpleName)
                } finally {
                    busy.remove(name, hold)
                }
                withContext(Dispatchers.Main) {
                    activity.lifecycle.removeObserver(observer)
                    dismissIfAttached(progress)
                    Toast.makeText(app, texts.getValue(toastFor(outcome)), Toast.LENGTH_LONG).show()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                SyncLog.w(TAG, "Default calendar for '$name': the change's end could not be shown (${e.javaClass.simpleName})")
            }
        }
    }

    /** The button-less waiting dialog while [save], a change made from this row (also before a rotation), is on its way. */
    private fun waitFor(save: Job) {
        val progress = waiting(withCancel = false)
        val observer = dismissAtDestroy(progress)
        progress.show()
        activity.lifecycleScope.launch {
            try {
                save.join()
            } finally {
                activity.lifecycle.removeObserver(observer)
                dismissIfAttached(progress)
            }
        }
    }

    /** A message about the list with OK, dismissed with the screen. */
    private fun message(text: Int) {
        val dialog = AppDialogBuilder(activity)
            .setTitle(R.string.label_default_calendar)
            .setMessage(text)
            .setPositiveButton(android.R.string.ok, null)
            .show()
        val observer = dismissAtDestroy(dialog)
        dialog.setOnDismissListener { activity.lifecycle.removeObserver(observer) }
    }

    /** The small waiting dialog: with Cancel while the list loads, without a button while a change is sent. */
    private fun waiting(withCancel: Boolean): AlertDialog = AppDialogBuilder(activity)
        .setTitle(R.string.label_default_calendar)
        .setMessage(R.string.default_calendar_connecting)
        .setCancelable(false)
        .apply { if (withCancel) setNegativeButton(R.string.btn_cancel, null) }
        .create()

    /** Dismisses [dialog] when the screen is destroyed, while its window still exists. The caller removes it when done. */
    private fun dismissAtDestroy(dialog: AlertDialog): LifecycleEventObserver {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_DESTROY) dismissIfAttached(dialog) }
        activity.lifecycle.addObserver(observer)
        return observer
    }

    /** Never on a dialog whose window is already gone ("not attached to window manager"). */
    private fun dismissIfAttached(dialog: AlertDialog) {
        if (!activity.isDestroyed && dialog.isShowing) dialog.dismiss()
    }

    private fun toastFor(outcome: SaveOutcome): Int = when (outcome) {
        SaveOutcome.Saved -> R.string.default_calendar_changed
        is SaveOutcome.Refused -> R.string.default_calendar_refused
        SaveOutcome.Auth -> R.string.error_session_expired
        is SaveOutcome.Unreachable, is SaveOutcome.Uncertain -> R.string.default_calendar_unreachable
    }

    /** [choice] for a log line: its name only while Verbose logging is on, else the start of its Proton id (LogNames). */
    private fun shown(choice: DefaultCalendarChoices.Choice): String =
        me.proton.sync.LogNames.calendar(choice.name, choice.protonCalendarId.take(8) + "…")

    private fun describe(load: Load): String = when (load) {
        is Load.Ready -> "${load.offer.choices.size} calendar(s) offered, ${shown(load.offer.checked)} checked"
        Load.NoOwnCalendar -> "no calendar of its own can be the default"
        is Load.Failed -> "failed (${load.kind}: ${load.detail})"
    }

    private companion object {
        const val TAG = "SyncSettings"
        const val USER_ACTION = "UserAction"

        /** Every text [toastFor] gives. */
        val TOASTS = listOf(
            R.string.default_calendar_changed, R.string.default_calendar_refused, R.string.error_session_expired,
            R.string.default_calendar_unreachable,
        )

        /**
         * Accounts whose load or change runs now, process-wide, so a double tap or a second command is ignored: a token
         * per load (freed by its end, its Cancel or the screen's end, and only its own), a [SaveHold] per change.
         */
        val busy = ConcurrentHashMap<String, Any>()

        /**
         * A change outlives the screen. Every launch on it catches its own exceptions: a launch here has no handler, and
         * an uncaught one would end the process.
         */
        val saveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}

package me.proton.sync.calendar

import android.accounts.Account
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import me.proton.sync.ProtonSyncApp
import me.proton.sync.SyncLog

/**
 * The buttons of the prompt about a new event in a shared calendar (SyncNotificationHelper.showNewEventHoldPrompt):
 * Move to the default calendar, Keep on phone, Publish. Every answer goes through [NewEventHoldActions.answer], the
 * one handler the decision dialog and the debug verbs use too, which also asks for the sync an answer needs and takes
 * the prompt down. Nothing runs on the main thread: the answer reads the provider and the database.
 */
class NewEventHoldReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val accountName = intent.getStringExtra(EXTRA_ACCOUNT_NAME) ?: return
        val eventId = intent.getLongExtra(EXTRA_EVENT_ID, -1L).takeIf { it > 0 } ?: return
        val choice = when (intent.action) {
            ACTION_MOVE -> NewEventHoldActions.Choice.MOVE
            ACTION_KEEP -> NewEventHoldActions.Choice.KEEP
            ACTION_PUBLISH -> NewEventHoldActions.Choice.PUBLISH
            else -> return
        }
        // The calendar the Move button named: a default changed since the prompt was drawn refuses the tap.
        val target = intent.getLongExtra(EXTRA_TARGET_CALENDAR_ID, -1L).takeIf { it >= 0 }
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                // Prompts are posted for waiting events only, so a button acts on nothing the user has answered since.
                NewEventHoldActions.answer(
                    app, (app as ProtonSyncApp).syncDatabase, Account(accountName, ProtonSyncApp.ACCOUNT_TYPE),
                    eventId, choice, expectedTargetCalendarId = target, origin = "notification",
                    expectedState = NewEventHoldRules.State.WAITING
                )
            } catch (e: Exception) {
                SyncLog.w(TAG, "The answer to the prompt about new event $eventId could not be applied: ${e.javaClass.simpleName}")
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "UserAction"
        const val EXTRA_ACCOUNT_NAME = "account_name"
        const val EXTRA_EVENT_ID = "event_id"
        const val EXTRA_TARGET_CALENDAR_ID = "target_calendar_id"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
        const val ACTION_MOVE = "me.proton.sync.action.NEW_EVENT_MOVE"
        const val ACTION_KEEP = "me.proton.sync.action.NEW_EVENT_KEEP"
        const val ACTION_PUBLISH = "me.proton.sync.action.NEW_EVENT_PUBLISH"
    }
}

package me.proton.sync.calendar

import android.accounts.Account
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import me.proton.sync.FullResyncHold
import me.proton.sync.ProtonSyncApp
import me.proton.sync.SyncConstants
import me.proton.sync.SyncLog
import me.proton.sync.SyncScheduling
import me.proton.sync.SyncUtils

/**
 * Handles the **Keep Proton's** / **Keep mine** actions on the collision notification raised by
 * [EventConflictGuard].
 *
 * While an event is held, neither the download nor the upload touches it, so answering is what
 * releases it in one direction or the other:
 *
 *  - **Keep Proton's** clears the local `DIRTY` flag so the pending local edit is abandoned, then
 *    releases the hold. The download's per-event gate still sees the server copy as newer, so the
 *    next sync applies it normally.
 *  - **Keep mine** just releases the hold and leaves `DIRTY` set, so the next upload sends the
 *    local version, which becomes the server's.
 *
 * Both then request a sync so the choice takes effect immediately rather than at the next
 * scheduled run.
 */
class EventConflictReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val accountName = intent.getStringExtra(EXTRA_ACCOUNT_NAME) ?: return
        val protonEventId = intent.getStringExtra(EXTRA_PROTON_EVENT_ID) ?: return
        val androidEventId = intent.getLongExtra(EXTRA_ANDROID_EVENT_ID, -1L)
        val nId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)

        when (intent.action) {
            ACTION_KEEP_REMOTE -> {
                SyncLog.i(TAG, "Collision on $protonEventId: user chose to keep Proton's version")
                if (androidEventId > 0) clearDirty(context, accountName, androidEventId)
                EventConflictGuard.release(context, accountName, protonEventId)
            }
            ACTION_KEEP_LOCAL -> {
                SyncLog.i(TAG, "Collision on $protonEventId: user chose to keep the phone's version")
                // DIRTY stays set so uploadDirtyEvents pushes the local version. Releasing the hold
                // alone is not enough: the download phase runs FIRST and would re-detect the same
                // collision and re-hold the event before the upload got a turn, so this choice could
                // never take effect. The marker tells the download to leave this event alone until
                // the upload has pushed it.
                EventConflictGuard.markLocalWins(context, accountName, protonEventId)
                EventConflictGuard.release(context, accountName, protonEventId)
            }
            else -> return
        }

        requestSync(context, accountName)
        context.getSystemService(NotificationManager::class.java)?.cancel(nId)
    }

    /**
     * Drop the pending local edit. Written through the sync-adapter URI so the provider does not
     * immediately re-dirty the row as it would for an ordinary app write.
     */
    private fun clearDirty(context: Context, accountName: String, androidEventId: Long) {
        try {
            val account = Account(accountName, ProtonSyncApp.ACCOUNT_TYPE)
            val uri = ContentUris.withAppendedId(
                SyncUtils.asCallerSyncAdapter(CalendarContract.Events.CONTENT_URI, account),
                androidEventId
            )
            val values = ContentValues().apply { put(CalendarContract.Events.DIRTY, 0) }
            context.contentResolver.update(uri, values, null, null)
        } catch (e: Exception) {
            // Worst case the flag stays set and the local version wins instead. Not silent data
            // loss, and the next sync still reconciles, so this must not crash the receiver.
            SyncLog.w(TAG, "Could not clear DIRTY on event $androidEventId: ${e.message}")
        }
    }

    private fun requestSync(context: Context, accountName: String) {
        val account = Account(accountName, ProtonSyncApp.ACCOUNT_TYPE)
        // While a full re-sync of the account's calendar is being prepared, asked for once its own request is posted
        // (FullResyncHold), with userSyncNow kept for that request.
        if (FullResyncHold.holdAnswerIfPreparing(accountName, userSyncNow = true)) {
            SyncLog.i(TAG, "Collision: the sync for $accountName is asked for once its full re-sync's own request is posted")
            return
        }
        // userSyncNow bypasses the calendar-level unchanged-skip. The sync that raised this prompt already
        // recorded the calendar's latest cursor, so without it the calendar looks unchanged and gets skipped
        // wholesale, leaving the very event we just resolved unapplied. The answer is stored before this request,
        // so if the sync fails (it is not retried while Android's automatic sync is off), the next one applies it.
        // answering: the tap whose sync raised the prompt may still be running, and Android drops a request equal to it.
        val bundle = SyncScheduling.userSyncExtras(context, accountName, userSyncNow = true, answering = account)
        ContentResolver.requestSync(account, SyncConstants.AUTHORITY_CALENDAR, bundle)
    }

    companion object {
        private const val TAG = "EventConflict"
        const val EXTRA_ACCOUNT_NAME = "account_name"
        const val EXTRA_CALENDAR_ID = "calendar_id"
        const val EXTRA_PROTON_EVENT_ID = "proton_event_id"
        const val EXTRA_ANDROID_EVENT_ID = "android_event_id"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
        const val ACTION_KEEP_REMOTE = "me.proton.sync.action.EVENT_CONFLICT_KEEP_REMOTE"
        const val ACTION_KEEP_LOCAL = "me.proton.sync.action.EVENT_CONFLICT_KEEP_LOCAL"
    }
}

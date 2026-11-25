package me.proton.sync.calendar

import android.accounts.Account
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import me.proton.sync.FullResyncHold
import me.proton.sync.ProtonSyncApp
import me.proton.sync.SyncConstants
import me.proton.sync.SyncLog
import me.proton.sync.SyncScheduling

/**
 * Handles the **Keep on Proton** / **Delete on Proton** actions from the bulk-deletion
 * safety notification (see [BulkDeletionGuard]).
 *
 * The receiver does NO database or network work itself — it just records the decision
 * in SharedPreferences and triggers a calendar sync that carries it out:
 *  - **Keep**   → clear the held state + request a restore; the next sync drops the
 *                 stale local event mappings so the events re-download from Proton.
 *  - **Delete** → mark the deletions approved; the next sync's reconcile proceeds to
 *                 delete them on Proton (bypassing the suspicious-bulk hold).
 */
class BulkDeletionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val accountName = intent.getStringExtra(EXTRA_ACCOUNT_NAME) ?: return
        val nId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)
        val account = Account(accountName, ProtonSyncApp.ACCOUNT_TYPE)

        when (intent.action) {
            ACTION_KEEP -> {
                SyncLog.i(TAG, "Bulk-deletion: user chose KEEP for $accountName - restoring affected calendars from Proton")
                // Keep the held calendar ids and flag a restore; the next sync's consumeRestore
                // reads + clears them. (Do NOT clear() here — that would drop the ids the scoped
                // restore needs.)
                BulkDeletionGuard.requestRestore(context, accountName)
                requestCalendarSync(context, account)
            }
            ACTION_DELETE -> {
                SyncLog.i(TAG, "Bulk-deletion: user chose DELETE for $accountName - confirming deletions on Proton")
                BulkDeletionGuard.setApproved(context, accountName)
                requestCalendarSync(context, account)
            }
            else -> return
        }

        context.getSystemService(NotificationManager::class.java)?.cancel(nId)
    }

    /**
     * As the user's own sync (the decision is stored first, so the next sync applies it if this one fails), answering on
     * [account]: Android drops a request equal to its running sync's. While a full re-sync of the account's calendar is
     * being prepared, asked for once the re-sync's own request is posted (FullResyncHold).
     */
    private fun requestCalendarSync(context: Context, account: Account) {
        if (FullResyncHold.holdAnswerIfPreparing(account.name)) {
            SyncLog.i(TAG, "Bulk-deletion: the sync for ${account.name} is asked for once its full re-sync's own request is posted")
            return
        }
        val extras = SyncScheduling.userSyncExtras(context, account.name, answering = account)
        ContentResolver.requestSync(account, SyncConstants.AUTHORITY_CALENDAR, extras)
    }

    companion object {
        private const val TAG = "BulkDeletion"
        const val EXTRA_ACCOUNT_NAME = "account_name"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
        const val ACTION_KEEP = "me.proton.sync.action.BULK_DELETE_KEEP"
        const val ACTION_DELETE = "me.proton.sync.action.BULK_DELETE_DELETE"
    }
}

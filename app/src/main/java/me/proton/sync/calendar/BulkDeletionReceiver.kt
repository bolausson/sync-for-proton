package me.proton.sync.calendar

import android.accounts.Account
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import me.proton.sync.ProtonSyncApp
import me.proton.sync.SyncConstants
import me.proton.sync.SyncLog

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
                requestCalendarSync(account)
            }
            ACTION_DELETE -> {
                SyncLog.i(TAG, "Bulk-deletion: user chose DELETE for $accountName - confirming deletions on Proton")
                BulkDeletionGuard.setApproved(context, accountName)
                requestCalendarSync(account)
            }
            else -> return
        }

        context.getSystemService(NotificationManager::class.java)?.cancel(nId)
    }

    private fun requestCalendarSync(account: Account) {
        val bundle = Bundle().apply {
            putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
            putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
        }
        ContentResolver.requestSync(account, SyncConstants.AUTHORITY_CALENDAR, bundle)
    }

    companion object {
        private const val TAG = "BulkDeletion"
        const val EXTRA_ACCOUNT_NAME = "account_name"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
        const val ACTION_KEEP = "me.proton.sync.action.BULK_DELETE_KEEP"
        const val ACTION_DELETE = "me.proton.sync.action.BULK_DELETE_DELETE"
    }
}

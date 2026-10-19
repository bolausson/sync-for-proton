package me.proton.sync

import android.accounts.Account
import android.accounts.AccountManager
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.os.Bundle

/**
 * Handles the **Delete from phone** / **Keep on phone** actions of the server→local deletion
 * confirmation notification (see [RemoteDeletionGuard]). Records the decision in prefs and, for
 * Delete, triggers a sync that carries it out:
 *  - DELETE → approve; the next sync deletes the held items locally.
 *  - KEEP   → move the held ids into the kept set; they stay on the phone, no re-prompt.
 *
 * Shared by contacts and calendar via the [EXTRA_TYPE] extra.
 */
class RemoteDeletionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val accountName = intent.getStringExtra(EXTRA_ACCOUNT_NAME) ?: return
        val type = intent.getStringExtra(EXTRA_TYPE) ?: return
        val nId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)

        when (intent.action) {
            ACTION_DELETE -> {
                SyncLog.i(TAG, "Remote-deletion: user chose DELETE for $type / $accountName")
                RemoteDeletionGuard.approve(context, type, accountName)
                requestSync(context, accountName, type)
            }
            ACTION_KEEP -> {
                SyncLog.i(TAG, "Remote-deletion: user chose KEEP for $type / $accountName")
                RemoteDeletionGuard.keep(context, type, accountName)
                // Nothing to apply — the items stay; just dismiss the prompt below.
            }
            else -> return
        }
        context.getSystemService(NotificationManager::class.java)?.cancel(nId)
    }

    private fun requestSync(context: Context, accountName: String, type: String) {
        val bundle = Bundle().apply {
            putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
            putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
        }
        if (type == RemoteDeletionGuard.TYPE_CALENDAR) {
            val account = Account(accountName, ProtonSyncApp.ACCOUNT_TYPE)
            ContentResolver.requestSync(account, SyncConstants.AUTHORITY_CALENDAR, bundle)
        } else {
            // Contacts sync runs on the linked address-book account.
            val am = AccountManager.get(context)
            val ab = am.getAccountsByType(ProtonSyncApp.ACCOUNT_TYPE_ADDRESS_BOOK).firstOrNull {
                am.getUserData(it, SyncConstants.EXTRA_PARENT_ACCOUNT_NAME) == accountName || it.name == accountName
            }
            if (ab != null) ContentResolver.requestSync(ab, SyncConstants.AUTHORITY_CONTACTS, bundle)
        }
    }

    companion object {
        private const val TAG = "RemoteDeletion"
        const val EXTRA_ACCOUNT_NAME = "account_name"
        const val EXTRA_TYPE = "deletion_type"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
        const val ACTION_DELETE = "me.proton.sync.action.REMOTE_DELETE_CONFIRM"
        const val ACTION_KEEP = "me.proton.sync.action.REMOTE_DELETE_KEEP"
    }
}

package me.proton.sync.contacts

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import me.proton.sync.SyncLog
import androidx.preference.PreferenceManager

/**
 * Handles conflict resolution actions from notifications.
 * Stores the user's decision in SharedPreferences for the next sync to act on.
 */
class ConflictResolutionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val protonContactId = intent.getStringExtra(EXTRA_CONTACT_ID) ?: return
        val accountName = intent.getStringExtra(EXTRA_ACCOUNT_NAME) ?: return
        val action = intent.getStringExtra(EXTRA_ACTION) ?: return
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)

        SyncLog.i(TAG, "Conflict resolution for $protonContactId: $action")

        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val key = "conflict_${accountName}_${protonContactId}"

        when (action) {
            ACTION_USE_LOCAL -> prefs.edit().putString(key, DECISION_LOCAL).apply()
            ACTION_USE_REMOTE -> prefs.edit().putString(key, DECISION_REMOTE).apply()
            ACTION_SKIP -> prefs.edit().putString(key, DECISION_SKIP).apply()
        }

        // Dismiss the notification
        val nm = context.getSystemService(NotificationManager::class.java)
        nm?.cancel(notificationId)
    }

    companion object {
        private const val TAG = "ConflictResolver"

        const val EXTRA_CONTACT_ID = "contact_id"
        const val EXTRA_ACCOUNT_NAME = "account_name"
        const val EXTRA_ACTION = "action"
        const val EXTRA_NOTIFICATION_ID = "notification_id"

        const val ACTION_USE_LOCAL = "use_local"
        const val ACTION_USE_REMOTE = "use_remote"
        const val ACTION_SKIP = "skip"

        const val DECISION_LOCAL = "local"
        const val DECISION_REMOTE = "remote"
        const val DECISION_SKIP = "skip"

        fun getDecision(context: Context, accountName: String, protonContactId: String): String? {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            return prefs.getString("conflict_${accountName}_${protonContactId}", null)
        }

        fun clearDecision(context: Context, accountName: String, protonContactId: String) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            prefs.edit().remove("conflict_${accountName}_${protonContactId}").apply()
        }

        fun clearAllSkipDecisions(context: Context, accountName: String) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val prefix = "conflict_${accountName}_"
            val toRemove = prefs.all.keys.filter {
                it.startsWith(prefix) && prefs.getString(it, null) == DECISION_SKIP
            }
            if (toRemove.isNotEmpty()) {
                val editor = prefs.edit()
                toRemove.forEach { editor.remove(it) }
                editor.apply()
                SyncLog.i(TAG, "Cleared ${toRemove.size} skip decisions for $accountName")
            }
        }
    }
}

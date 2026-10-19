package me.proton.sync.calendar

import android.content.Context
import androidx.preference.PreferenceManager

/**
 * Safety guard against the app mass-deleting Proton events when the local Android
 * calendar provider under-reports.
 *
 * `reconcileDeletedEvents` infers "the user deleted this event" purely from the
 * event being absent in the local Android calendar provider. If a calendar's events
 * vanish locally while the Room mappings survive (OS-cleared Calendar storage, an
 * account/calendar-id glitch, a half-restored DB), that inference would delete them
 * ALL on Proton — real, irreversible data loss.
 *
 * This guard intercepts any *suspiciously large* deletion, holds it back (never
 * deletes on Proton automatically), and asks the user via an ongoing notification to
 * either **Keep on Proton** (restore the events on the phone) or **Delete on Proton**
 * (confirm it was intentional). No event ids are persisted — only the affected Proton
 * *calendar* ids (so Keep re-downloads just those, not the whole account) and a count
 * for the notification text. The next sync re-detects the same still-missing events.
 */
object BulkDeletionGuard {

    /** Below this many deletions we don't guard — deleting a handful of events is normal. */
    const val ABS_MIN = 10

    /** A partial wipe is suspicious when at least this fraction of a calendar's events vanished at once. */
    const val FRACTION = 0.5

    private fun countKey(account: String) = "bulk_del_pending_count_$account"
    private fun calsKey(account: String) = "bulk_del_pending_cals_$account"
    private fun approvedKey(account: String) = "bulk_del_approved_$account"
    private fun restoreKey(account: String) = "bulk_del_restore_$account"

    /**
     * True when a calendar's deletion set is large enough to be a likely provider glitch
     * rather than genuine user intent.
     *
     * @param deletedCount mapped events missing from the local provider for this calendar
     * @param localCount   events the local provider currently reports for this calendar
     * @param totalMappings events we had previously synced for this calendar
     */
    fun isSuspicious(deletedCount: Int, localCount: Int, totalMappings: Int): Boolean {
        if (deletedCount == 0) return false
        if (localCount == 0 && totalMappings > 0) return true                 // whole calendar vanished locally
        return deletedCount >= ABS_MIN && deletedCount >= totalMappings * FRACTION
    }

    private fun prefs(context: Context) = PreferenceManager.getDefaultSharedPreferences(context)

    /** Record a held deletion: [count] events for the notification, [calendarIds] to restore on Keep. */
    fun markPending(context: Context, account: String, count: Int, calendarIds: Set<String>) =
        prefs(context).edit()
            .putInt(countKey(account), count)
            .putStringSet(calsKey(account), calendarIds)
            .apply()

    fun pendingCount(context: Context, account: String): Int =
        prefs(context).getInt(countKey(account), 0)

    /** User tapped "Delete on Proton": the next sync should carry out the held deletions. */
    fun setApproved(context: Context, account: String) =
        prefs(context).edit().putBoolean(approvedKey(account), true).apply()

    fun isApproved(context: Context, account: String): Boolean =
        prefs(context).getBoolean(approvedKey(account), false)

    /** User tapped "Keep on Proton": ask the next sync to re-download the affected calendars. */
    fun requestRestore(context: Context, account: String) =
        prefs(context).edit().putBoolean(restoreKey(account), true).apply()

    /**
     * One-shot: if a restore is pending, return the affected Proton calendar ids to re-download
     * and clear the held state. Returns null when no restore is pending.
     */
    fun consumeRestore(context: Context, account: String): Set<String>? {
        val p = prefs(context)
        if (!p.getBoolean(restoreKey(account), false)) return null
        val cals = p.getStringSet(calsKey(account), emptySet())?.toSet() ?: emptySet()
        p.edit()
            .remove(restoreKey(account))
            .remove(calsKey(account))
            .remove(countKey(account))
            .apply()
        return cals
    }

    fun clear(context: Context, account: String) =
        prefs(context).edit()
            .remove(countKey(account))
            .remove(calsKey(account))
            .remove(approvedKey(account))
            .remove(restoreKey(account))
            .apply()
}

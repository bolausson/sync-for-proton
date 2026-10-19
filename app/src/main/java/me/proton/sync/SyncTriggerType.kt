package me.proton.sync

import android.content.ContentResolver
import android.os.Bundle

/**
 * Type-safe representation of what triggered a sync adapter run.
 *
 * Replaces the identical trigger-detection `when` block that was duplicated in
 * both [me.proton.sync.calendar.CalendarSyncAdapter] and
 * [me.proton.sync.contacts.ContactsSyncAdapter].
 *
 * @property label Human-readable description used in log messages.
 */
sealed class SyncTriggerType(val label: String) {

    /** First sync after the user logs in (SYNC_EXTRAS_INITIALIZE). */
    object InitialSync : SyncTriggerType("Initial sync after login")

    /** User-initiated full Remote→Local re-sync. */
    object ManualForceRemoteToLocal : SyncTriggerType("Manual forced Remote→Local")

    /** User-initiated full Local→Remote re-sync. */
    object ManualForceLocalToRemote : SyncTriggerType("Manual forced Local→Remote")

    /**
     * User pressed one of the explicit "Sync now" buttons in settings
     * ([SyncConstants.EXTRA_USER_SYNC_NOW] set). Distinct from [ManualSync] because other
     * code paths (re-auth completion, deletion receivers) also request MANUAL syncs; only
     * this trigger bypasses the calendar unchanged-skip.
     */
    object UserSyncNow : SyncTriggerType("Manual sync (user)")

    /** MANUAL sync not from a user button (re-auth completion, receivers, provisioning). */
    object ManualSync : SyncTriggerType("Manual sync")

    /** Periodic sync triggered by WorkManager watchdog or system scheduler. */
    object ScheduledSync : SyncTriggerType("Scheduled sync")

    companion object {
        /**
         * Determines the trigger type from the sync adapter [extras] Bundle.
         *
         * Priority order matches the original inline logic:
         * initialize > forceRemoteToLocal > forceLocalToRemote/upload > scheduledByWorker > manual > scheduled.
         */
        fun fromExtras(extras: Bundle): SyncTriggerType {
            val isManual = extras.getBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, false)
            val isUpload = extras.getBoolean(ContentResolver.SYNC_EXTRAS_UPLOAD, false)
            val isInitialize = extras.getBoolean(ContentResolver.SYNC_EXTRAS_INITIALIZE, false)
            val isForceRemoteToLocal = extras.getBoolean(SyncConstants.EXTRA_FORCE_REMOTE_TO_LOCAL, false)
            val isForceLocalToRemote = extras.getBoolean(SyncConstants.EXTRA_FORCE_LOCAL_TO_REMOTE, false)
            val isScheduledByWorker = extras.getBoolean(SyncConstants.EXTRA_SCHEDULED_BY_WORKER, false)
            val isUserSyncNow = extras.getBoolean(SyncConstants.EXTRA_USER_SYNC_NOW, false)
            return when {
                isInitialize -> InitialSync
                isForceRemoteToLocal -> ManualForceRemoteToLocal
                isForceLocalToRemote || (isManual && isUpload) -> ManualForceLocalToRemote
                isUserSyncNow -> UserSyncNow
                isScheduledByWorker -> ScheduledSync
                isManual -> ManualSync
                else -> ScheduledSync
            }
        }
    }
}

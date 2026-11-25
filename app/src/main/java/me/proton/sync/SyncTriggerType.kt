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

    /**
     * The one sync requested when the subscription turns active ([SyncConstants.EXTRA_CATCH_UP]). A label only:
     * nothing branches on it, so the run behaves as the MANUAL or ordinary request it is.
     */
    object CatchUpSync : SyncTriggerType("Catch-up sync after activation")

    /**
     * The calendar sync asked for after a calendar setting changed ([SyncConstants.EXTRA_SETTINGS_CHANGED], set by
     * SyncScheduling.requestSettingsSync). A label only: nothing branches on it, so the run behaves as the ordinary
     * request it is.
     */
    object SettingsChangeSync : SyncTriggerType("Sync after a settings change")

    companion object {
        /** Determines the trigger type from the sync adapter [extras] Bundle ([from]). */
        fun fromExtras(extras: Bundle): SyncTriggerType = from { extras.getBoolean(it, false) }

        /**
         * The trigger type from [flag], which reads one boolean extra by its key. Priority order (the original inline
         * logic's, with the settings change added before the watchdog's flag):
         * initialize > forceRemoteToLocal > forceLocalToRemote/upload > userSyncNow > catchUp > settingsChanged >
         * scheduledByWorker > manual > scheduled.
         */
        internal fun from(flag: (String) -> Boolean): SyncTriggerType {
            val isManual = flag(ContentResolver.SYNC_EXTRAS_MANUAL)
            val isUpload = flag(ContentResolver.SYNC_EXTRAS_UPLOAD)
            val isInitialize = flag(ContentResolver.SYNC_EXTRAS_INITIALIZE)
            val isForceRemoteToLocal = flag(SyncConstants.EXTRA_FORCE_REMOTE_TO_LOCAL)
            val isForceLocalToRemote = flag(SyncConstants.EXTRA_FORCE_LOCAL_TO_REMOTE)
            val isScheduledByWorker = flag(SyncConstants.EXTRA_SCHEDULED_BY_WORKER)
            val isUserSyncNow = flag(SyncConstants.EXTRA_USER_SYNC_NOW)
            val isCatchUp = flag(SyncConstants.EXTRA_CATCH_UP)
            val isSettingsChange = flag(SyncConstants.EXTRA_SETTINGS_CHANGED)
            return when {
                isInitialize -> InitialSync
                isForceRemoteToLocal -> ManualForceRemoteToLocal
                isForceLocalToRemote || (isManual && isUpload) -> ManualForceLocalToRemote
                isUserSyncNow -> UserSyncNow
                isCatchUp -> CatchUpSync
                isSettingsChange -> SettingsChangeSync
                isScheduledByWorker -> ScheduledSync
                isManual -> ManualSync
                else -> ScheduledSync
            }
        }
    }
}

package me.proton.sync

import android.accounts.Account
import android.accounts.AccountManager
import android.content.ContentResolver
import android.content.Context
import android.os.Bundle
import me.proton.sync.SyncLog
import androidx.preference.PreferenceManager
import androidx.work.*
import java.util.concurrent.TimeUnit

/**
 * WorkManager-based periodic sync watchdog.
 *
 * Android's addPeriodicSync is unreliable — the OS can defer or skip syncs entirely,
 * especially on Samsung devices with aggressive battery optimization.
 *
 * WorkManager provides more reliable periodic execution because:
 * - Work survives process death (persisted in SQLite)
 * - Work survives device reboot
 * - WorkManager negotiates with the OS for execution windows
 * - It respects Doze mode but fires when the maintenance window opens
 *
 * This worker triggers ContentResolver.requestSync for a specific account,
 * which invokes our existing SyncAdapters.
 */
class SyncWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val accountName = inputData.getString(KEY_ACCOUNT_NAME)
        val syncType = inputData.getString(KEY_SYNC_TYPE) ?: "both"

        SyncLog.i(TAG, "WorkManager sync watchdog triggered for $accountName ($syncType)")

        val accountManager = AccountManager.get(applicationContext)

        if (syncType == "contacts" || syncType == "both") {
            // Match on the parent-account user-data link. The address-book account's name mirrors
            // the parent's, but one made by an older version keeps another name until the start-up
            // migration renames it.
            val abAccount = accountManager.getAccountsByType(ProtonSyncApp.ACCOUNT_TYPE_ADDRESS_BOOK)
                .firstOrNull {
                    accountName == null ||
                        accountManager.getUserData(it, SyncConstants.EXTRA_PARENT_ACCOUNT_NAME) == accountName ||
                        it.name == accountName // also an ab-account without the parent link
                }
            if (abAccount != null && ContentResolver.getSyncAutomatically(abAccount, SyncConstants.AUTHORITY_CONTACTS)) {
                val bundle = Bundle().apply {
                    // Use MANUAL+EXPEDITED to bypass Android's sync scheduling delays,
                    // but mark as scheduled so logging can distinguish from user-initiated syncs.
                    putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
                    putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
                    putBoolean(SyncConstants.EXTRA_SCHEDULED_BY_WORKER, true)
                }
                ContentResolver.requestSync(abAccount, SyncConstants.AUTHORITY_CONTACTS, bundle)
                SyncLog.i(TAG, "Requested contacts sync for ${abAccount.name}")
            }
        }

        if (syncType == "calendar" || syncType == "both") {
            val mainAccount = accountManager.getAccountsByType(ProtonSyncApp.ACCOUNT_TYPE)
                .firstOrNull { accountName == null || it.name == accountName }
            if (mainAccount != null && ContentResolver.getSyncAutomatically(mainAccount, SyncConstants.AUTHORITY_CALENDAR)) {
                val bundle = Bundle().apply {
                    putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
                    putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
                    putBoolean(SyncConstants.EXTRA_SCHEDULED_BY_WORKER, true)
                }
                ContentResolver.requestSync(mainAccount, SyncConstants.AUTHORITY_CALENDAR, bundle)
                SyncLog.i(TAG, "Requested calendar sync for ${mainAccount.name}")
            }
        }

        return Result.success()
    }

    companion object {
        private const val TAG = "SyncWorker"
        private const val KEY_ACCOUNT_NAME = "account_name"
        private const val KEY_SYNC_TYPE = "sync_type"
        // WorkManager minimum periodic interval is 15 minutes
        private const val MIN_INTERVAL_MINUTES = 15L

        /**
         * Schedule per-account, per-type periodic sync watchdog.
         * Interval is user's chosen sync interval + 1 minute as a fallback safety net.
         */
        fun scheduleForAccount(
            context: Context,
            accountName: String,
            syncType: String, // "contacts" or "calendar"
            userIntervalMinutes: Long
        ) {
            // <= 0, not < 0: see the note in EventLoopWorker. A divided sentinel must not arm a job.
            if (userIntervalMinutes <= 0) {
                // Manual only — cancel the watchdog
                cancelForAccount(context, accountName, syncType)
                return
            }

            val watchdogInterval = maxOf(userIntervalMinutes + 1, MIN_INTERVAL_MINUTES)

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val inputData = Data.Builder()
                .putString(KEY_ACCOUNT_NAME, accountName)
                .putString(KEY_SYNC_TYPE, syncType)
                .build()

            val request = PeriodicWorkRequestBuilder<SyncWorker>(
                watchdogInterval, TimeUnit.MINUTES,
                // Flex window: last 5 minutes of the interval
                minOf(5, watchdogInterval / 3), TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setInputData(inputData)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
                .build()

            val workName = workName(accountName, syncType)
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                workName,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
            SyncLog.i(TAG, "Scheduled $syncType watchdog for $accountName every ${watchdogInterval}m")
        }

        /**
         * Cancel watchdog for a specific account + type.
         */
        fun cancelForAccount(context: Context, accountName: String, syncType: String) {
            val workName = workName(accountName, syncType)
            WorkManager.getInstance(context).cancelUniqueWork(workName)
            SyncLog.i(TAG, "Cancelled $syncType watchdog for $accountName")
        }

        /**
         * Cancel the watchdog for every account and both sync types. Used when
         * subscription becomes inactive so the device isn't woken up for work
         * the adapter will only skip.
         */
        fun cancelAll(context: Context) {
            val accountManager = AccountManager.get(context)
            val accounts = accountManager.getAccountsByType(ProtonSyncApp.ACCOUNT_TYPE)
            for (account in accounts) {
                cancelForAccount(context, account.name, "contacts")
                cancelForAccount(context, account.name, "calendar")
            }
        }

        /**
         * Schedule watchdogs for all accounts based on their current interval settings.
         * Call this during app startup.
         */
        fun scheduleAll(context: Context) {
            val accountManager = AccountManager.get(context)
            val accounts = accountManager.getAccountsByType(ProtonSyncApp.ACCOUNT_TYPE)
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)

            for (account in accounts) {
                // Only schedule watchdogs when fallback full sync is enabled
                val contactsFallbackEnabled = prefs.getBoolean(SyncPreferences.fallbackContactsEnabledKey(account.name), false)
                if (contactsFallbackEnabled) {
                    val contactsInterval = prefs.getString(
                        SyncPreferences.fallbackContactsIntervalKey(account.name), SyncPreferences.DEFAULT_FALLBACK_INTERVAL_SECONDS
                    )?.toLongOrNull() ?: 43200L
                    scheduleForAccount(context, account.name, "contacts", contactsInterval / 60)
                } else {
                    scheduleForAccount(context, account.name, "contacts", -1)
                }

                val calendarFallbackEnabled = prefs.getBoolean(SyncPreferences.fallbackCalendarEnabledKey(account.name), false)
                if (calendarFallbackEnabled) {
                    val calendarInterval = prefs.getString(
                        SyncPreferences.fallbackCalendarIntervalKey(account.name), SyncPreferences.DEFAULT_FALLBACK_INTERVAL_SECONDS
                    )?.toLongOrNull() ?: 43200L
                    scheduleForAccount(context, account.name, "calendar", calendarInterval / 60)
                } else {
                    scheduleForAccount(context, account.name, "calendar", -1)
                }
            }
        }

        private fun workName(accountName: String, syncType: String): String {
            return "proton_sync_${syncType}_${accountName.hashCode()}"
        }
    }
}

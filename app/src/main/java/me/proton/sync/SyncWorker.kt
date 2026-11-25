package me.proton.sync

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import android.os.Bundle
import me.proton.sync.SyncLog
import androidx.preference.PreferenceManager
import androidx.work.*
import java.util.concurrent.TimeUnit

/**
 * The optional "Fallback full sync": a WorkManager job per account and type that asks Android, at the fallback
 * interval, for one ordinary sync request ([runOnce]), which Android runs only while its automatic sync is on. It is
 * skipped with a log line while that is off, the account is paused after a sign-in problem, the subscription is
 * inactive, the type is switched off or a full re-sync of that type of the account is being prepared. "Manual only"
 * does not stop it: it has its own interval.
 *
 * WorkManager, because Android's own periodic sync can be deferred or skipped for a long time, especially on Samsung
 * devices with aggressive battery optimization, while WorkManager work survives process death and reboots and runs
 * when Doze opens its maintenance window.
 */
class SyncWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        runOnce(applicationContext, inputData.getString(KEY_ACCOUNT_NAME), inputData.getString(KEY_SYNC_TYPE) ?: "both")
        return Result.success()
    }

    companion object {
        private const val TAG = "SyncWorker"
        private const val KEY_ACCOUNT_NAME = "account_name"
        private const val KEY_SYNC_TYPE = "sync_type"
        // WorkManager minimum periodic interval is 15 minutes
        private const val MIN_INTERVAL_MINUTES = 15L

        /**
         * One Fallback full sync of [accountName] (the first account when null) for [syncType], "contacts", "calendar"
         * or "both": per type, one ordinary sync request through SyncScheduling.requestBackgroundSync, marked
         * [SyncConstants.EXTRA_SCHEDULED_BY_WORKER] and neither manual nor expedited, or a line saying why not. The
         * job's body, and the watchdog debug verb's.
         */
        internal fun runOnce(context: Context, accountName: String?, syncType: String) {
            val am = AccountManager.get(context)
            val parent = am.getAccountsByType(ProtonSyncApp.ACCOUNT_TYPE).firstOrNull { accountName == null || it.name == accountName }
            if (parent == null) {
                SyncLog.i(TAG, "Fallback full sync for '${accountName ?: "the first account"}': no such account, nothing requested")
                return
            }
            fun request(account: Account?, authority: String, type: String) {
                if (account == null) {
                    SyncLog.i(TAG, "Fallback full sync for '${parent.name}' ($type): skipped, the account has no address book")
                    return
                }
                val extras = Bundle().apply { putBoolean(SyncConstants.EXTRA_SCHEDULED_BY_WORKER, true) }
                val decision = SyncScheduling.requestBackgroundSync(
                    context, account, authority, parent.name, extras, manualOnlyApplies = false,
                )
                if (decision == SyncScheduling.BackgroundRequest.SEND) {
                    SyncLog.i(TAG, "Fallback full sync for '${parent.name}' ($type): sync requested")
                } else {
                    SyncLog.i(TAG, "Fallback full sync for '${parent.name}' ($type): skipped, ${decision.reason(type)}")
                }
            }
            if (syncType == "contacts" || syncType == "both") {
                val ab = SyncScheduling.linkedAddressBook(am, parent, am.getAccountsByType(ProtonSyncApp.ACCOUNT_TYPE_ADDRESS_BOOK))
                request(ab, SyncConstants.AUTHORITY_CONTACTS, "contacts")
            }
            if (syncType == "calendar" || syncType == "both") {
                request(parent, SyncConstants.AUTHORITY_CALENDAR, "calendar")
            }
        }

        /**
         * The stored interval in SECONDS, as the schedulers' MINUTES.
         *
         * Exists as a named function because doing it inline is what caused the bug: `seconds / 60`
         * truncates the -1 "manual" sentinel to 0, which is not negative, so a guard of `< 0` let it
         * through to be clamped up to the minimum interval. Every caller goes through here now, and
         * the unit test exercises THIS function rather than a copy of it.
         */
        internal fun intervalMinutesFor(storedSeconds: Long): Long =
            if (storedSeconds < 0) -1L else storedSeconds / 60

        /** Whether an interval in minutes should result in scheduled work at all. */
        internal fun schedulesWork(intervalMinutes: Long): Boolean = intervalMinutes > 0

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
            // Zero is off too, not only a negative value: see intervalMinutesFor. A divided sentinel must not arm a job.
            if (!schedulesWork(userIntervalMinutes)) {
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
            for (account in accountManager.getAccountsByType(ProtonSyncApp.ACCOUNT_TYPE)) {
                scheduleFromSettings(context, account.name)
            }
        }

        /**
         * Schedule or cancel one account's two watchdogs from its fallback settings. The body of
         * [scheduleAll], on its own so a new or renamed account can be armed without touching the
         * others (SyncScheduling.scheduleWorkers).
         */
        fun scheduleFromSettings(context: Context, accountName: String) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)

            // Only schedule watchdogs when fallback full sync is enabled
            val contactsFallbackEnabled = prefs.getBoolean(SyncPreferences.fallbackContactsEnabledKey(accountName), false)
            if (contactsFallbackEnabled) {
                val contactsInterval = prefs.getString(
                    SyncPreferences.fallbackContactsIntervalKey(accountName), SyncPreferences.DEFAULT_FALLBACK_INTERVAL_SECONDS
                )?.toLongOrNull() ?: 43200L
                scheduleForAccount(context, accountName, "contacts", intervalMinutesFor(contactsInterval))
            } else {
                scheduleForAccount(context, accountName, "contacts", -1)
            }

            val calendarFallbackEnabled = prefs.getBoolean(SyncPreferences.fallbackCalendarEnabledKey(accountName), false)
            if (calendarFallbackEnabled) {
                val calendarInterval = prefs.getString(
                    SyncPreferences.fallbackCalendarIntervalKey(accountName), SyncPreferences.DEFAULT_FALLBACK_INTERVAL_SECONDS
                )?.toLongOrNull() ?: 43200L
                scheduleForAccount(context, accountName, "calendar", intervalMinutesFor(calendarInterval))
            } else {
                scheduleForAccount(context, accountName, "calendar", -1)
            }
        }

        /** The unique work name of an account's watchdog (also read by the schedule_info debug command). */
        internal fun workName(accountName: String, syncType: String): String {
            return "proton_sync_${syncType}_${accountName.hashCode()}"
        }
    }
}

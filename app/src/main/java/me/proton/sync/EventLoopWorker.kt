package me.proton.sync

import android.accounts.Account
import android.accounts.AccountManager
import android.content.ContentResolver
import android.content.Context
import android.os.Bundle
import androidx.preference.PreferenceManager
import androidx.work.*
import me.proton.sync.api.ProtonApiError
import me.proton.sync.billing.SubscriptionGate
import me.proton.sync.crypto.SecureStorage
import okhttp3.OkHttpClient
import okhttp3.Request
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.concurrent.TimeUnit

/**
 * WorkManager-based Event Loop poller for near-real-time sync.
 *
 * Instead of blindly syncing on a fixed schedule, this worker polls Proton's
 * unified event loop (`/core/v5/events/{eventID}`) to detect changes.
 * Only triggers a sync when the server reports actual changes to contacts,
 * calendars, labels, or addresses.
 *
 * ## How It Works
 * 1. On first run: fetch latest EventID via `/core/v5/events/latest`
 * 2. On each poll: fetch `/core/v5/events/{lastEventID}`
 * 3. If response contains changes → trigger targeted sync
 * 4. If `Refresh=255` → trigger full sync
 * 5. If `More=1` → continue polling (drain the event queue)
 * 6. Store new EventID for next poll
 *
 * ## Fallback
 * A separate periodic full sync (configurable, default off/12h) ensures
 * data consistency even if the event loop misses something.
 *
 * @see SyncWorker for the legacy periodic sync watchdog
 */
class EventLoopWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    private val gson = Gson()
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override fun doWork(): Result {
        val accountName = inputData.getString(KEY_ACCOUNT_NAME) ?: return Result.success()
        val prefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)

        // Subscription gate — skip event loop entirely if no active subscription
        if (!SubscriptionGate.isSyncAllowedCached(applicationContext)) {
            SyncLog.d(TAG, "Event loop skipped for $accountName: subscription inactive")
            return Result.success()
        }

        SyncLog.i(TAG, "Event loop poll for $accountName")

        val accountManager = AccountManager.get(applicationContext)
        val mainAccount = accountManager.getAccountsByType(ProtonSyncApp.ACCOUNT_TYPE)
            .firstOrNull { it.name == accountName } ?: return Result.success()

        // Get stored session from AccountManager for API calls. Values are wrapped
        // at rest since 1.6.18 (or legacy plaintext) — decryptStrict handles both.
        val accessToken = accountManager.peekAuthToken(mainAccount, "access")
            ?.let { SecureStorage.decryptStrict(it) } ?: ""
        val uid = accountManager.getUserData(mainAccount, "uid")
            ?.let { SecureStorage.decryptStrict(it) } ?: ""

        if (accessToken.isEmpty() || uid.isEmpty()) {
            SyncLog.w(TAG, "No credentials for $accountName, skipping event loop")
            return Result.success()
        }

        // Get or bootstrap EventID
        val eventIdKey = SyncPreferences.eventLoopIdKey(accountName)
        var lastEventId = prefs.getString(eventIdKey, null)

        if (lastEventId == null) {
            // Bootstrap: fetch latest EventID
            lastEventId = fetchLatestEventId(accessToken, uid)
            if (lastEventId != null) {
                prefs.edit().putString(eventIdKey, lastEventId).apply()
                SyncLog.i(TAG, "Bootstrapped event loop for $accountName")
            }
            // Check for pending settings changes even on bootstrap
            val settingsDirtyKey = SyncPreferences.calendarSettingsDirtyKey(accountName)
            if (prefs.getBoolean(settingsDirtyKey, false)) {
                SyncLog.i(TAG, "Event loop: calendar settings changed, triggering calendar sync")
                prefs.edit().putBoolean(settingsDirtyKey, false).apply()
                triggerSync(accountManager, accountName, contacts = false, calendar = true)
            }
            return Result.success()
        }

        // Poll for changes — lastEventId is non-null here (null case returned above)
        var currentEventId: String = lastEventId!!
        var hasContactChanges = false
        var hasCalendarChanges = false
        var needsFullRefresh = false
        var iterations = 0

        do {
            val response = fetchEvents(accessToken, uid, currentEventId) ?: break
            iterations++

            val newEventId = response["EventID"] as? String
            val more = (response["More"] as? Number)?.toInt() ?: 0
            val refresh = (response["Refresh"] as? Number)?.toInt() ?: 0

            if (refresh == 255 || refresh == 1) {
                needsFullRefresh = true
            }

            // Check for contact changes
            val contacts = response["Contacts"] as? List<*>
            val contactEmails = response["ContactEmails"] as? List<*>
            val labels = response["Labels"] as? List<*>
            if (!contacts.isNullOrEmpty() || !contactEmails.isNullOrEmpty()) {
                hasContactChanges = true
            }
            // Any label change counts as a contact change: contact groups are labels with Type=2,
            // and the type is not checked here.
            if (!labels.isNullOrEmpty()) {
                hasContactChanges = true
            }

            // Check for calendar changes (addresses change can affect calendar keys)
            val addresses = response["Addresses"] as? List<*>
            val user = response["User"]
            if (addresses != null || user != null) {
                hasCalendarChanges = true
            }

            // Calendar-level changes (new/deleted calendars)
            val calendars = response["Calendars"] as? List<*>
            if (!calendars.isNullOrEmpty()) {
                hasCalendarChanges = true
            }

            // Calendar event-level changes (attendee RSVP, event modifications)
            val calendarEvents = response["CalendarEvents"] as? List<*>
            if (!calendarEvents.isNullOrEmpty()) {
                hasCalendarChanges = true
            }

            SyncLog.d(
                TAG,
                "Poll iter=$iterations more=$more refresh=$refresh " +
                    "contacts=${contacts?.size ?: 0} contactEmails=${contactEmails?.size ?: 0} " +
                    "labels=${labels?.size ?: 0} addresses=${addresses?.size ?: 0} " +
                    "user=${if (user != null) 1 else 0} " +
                    "calendars=${calendars?.size ?: 0} calendarEvents=${calendarEvents?.size ?: 0}"
            )

            if (newEventId != null) {
                currentEventId = newEventId
            }

            // Safety: don't loop forever
            if (iterations > 50) {
                SyncLog.w(TAG, "Event loop hit 50 iterations, breaking")
                break
            }
        } while (more == 1)

        // Save new EventID
        if (currentEventId != lastEventId) {
            prefs.edit().putString(eventIdKey, currentEventId).apply()
        }

        // Check for local settings changes (calendar range, external calendars toggle)
        val settingsDirtyKey = SyncPreferences.calendarSettingsDirtyKey(accountName)
        if (prefs.getBoolean(settingsDirtyKey, false)) {
            SyncLog.i(TAG, "Event loop: calendar settings changed, triggering calendar sync")
            hasCalendarChanges = true
            prefs.edit().putBoolean(settingsDirtyKey, false).apply()
        }

        // Check for locally dirty events/contacts (user edits on device).
        //
        // One try per query, deliberately. They shared a single catch, so any failure on the calendar
        // side, including the AccountManager lookup that used to sit above it, skipped the contacts
        // check as well: a contact the user had just edited then waited for the next periodic sync
        // instead of being picked up here. Each cursor is closed on the way out, exception or not.
        val account = Account(accountName, ProtonSyncApp.ACCOUNT_TYPE)
        val cr = applicationContext.contentResolver

        // Dirty calendar events
        try {
            cr.query(
                android.provider.CalendarContract.Events.CONTENT_URI,
                arrayOf("_id"),
                "${android.provider.CalendarContract.Events.DIRTY} = 1 AND ${android.provider.CalendarContract.Events.CALENDAR_ID} IN " +
                    "(SELECT _id FROM calendars WHERE account_name = ? AND account_type = ?)",
                arrayOf(accountName, account.type),
                null
            )?.use { calCursor ->
                val dirtyEvents = calCursor.count
                if (dirtyEvents > 0) {
                    SyncLog.i(TAG, "Event loop: $dirtyEvents locally dirty calendar event(s), triggering calendar sync")
                    hasCalendarChanges = true
                }
            }
        } catch (e: Exception) {
            SyncLog.d(TAG, "Event loop: failed to check for dirty calendar events: ${e.message}")
        }

        // Dirty contacts: query by the address-book account's name, which mirrors the parent's
        // display name (see SyncUtils.addressBookAccountName).
        try {
            val abAccount = Account(SyncUtils.addressBookAccountName(accountManager, account), ProtonSyncApp.ACCOUNT_TYPE_ADDRESS_BOOK)
            cr.query(
                android.provider.ContactsContract.RawContacts.CONTENT_URI,
                arrayOf("_id"),
                "${android.provider.ContactsContract.RawContacts.DIRTY} = 1 AND " +
                    "${android.provider.ContactsContract.RawContacts.ACCOUNT_NAME} = ? AND " +
                    "${android.provider.ContactsContract.RawContacts.ACCOUNT_TYPE} = ?",
                arrayOf(abAccount.name, abAccount.type),
                null
            )?.use { contactCursor ->
                val dirtyContacts = contactCursor.count
                if (dirtyContacts > 0) {
                    SyncLog.i(TAG, "Event loop: $dirtyContacts locally dirty contact(s), triggering contact sync")
                    hasContactChanges = true
                }
            }
        } catch (e: Exception) {
            SyncLog.d(TAG, "Event loop: failed to check for dirty contacts: ${e.message}")
        }

        // Trigger syncs based on detected changes
        if (needsFullRefresh) {
            SyncLog.i(TAG, "Event loop: full refresh requested, triggering both syncs")
            triggerSync(accountManager, accountName, contacts = true, calendar = true)
        } else {
            if (hasContactChanges) {
                SyncLog.i(TAG, "Event loop: contact changes detected, triggering contact sync")
                triggerSync(accountManager, accountName, contacts = true, calendar = false)
            }
            if (hasCalendarChanges) {
                SyncLog.i(TAG, "Event loop: calendar changes detected, triggering calendar sync")
                triggerSync(accountManager, accountName, contacts = false, calendar = true)
            }
            if (!hasContactChanges && !hasCalendarChanges) {
                SyncLog.d(TAG, "Event loop: no changes detected")
            }
        }

        return Result.success()
    }

    private fun fetchLatestEventId(accessToken: String, uid: String): String? {
        return try {
            val client = httpClient
            val request = Request.Builder()
                .url(ProtonSyncApp.BASE_URL + "core/v5/events/latest")
                .header("Authorization", "Bearer $accessToken")
                .header("x-pm-uid", uid)
                .header("x-pm-appversion", ProtonSyncApp.APP_VERSION)
                .build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string()
                val map = gson.fromJson<Map<String, Any>>(body, object : TypeToken<Map<String, Any>>() {}.type)
                map["EventID"] as? String
            } else {
                // Silent until 2.0.2. Code and Error only, never the body.
                SyncLog.w(TAG, "Event loop bootstrap failed: HTTP ${response.code}" + ProtonApiError.describe(readErrorBody(response)))
                response.close()
                null
            }
        } catch (e: Exception) {
            SyncLog.w(TAG, "Failed to bootstrap event loop: ${e.message}")
            null
        }
    }

    private fun fetchEvents(accessToken: String, uid: String, eventId: String): Map<String, Any>? {
        return try {
            val client = httpClient
            val request = Request.Builder()
                .url(ProtonSyncApp.BASE_URL + "core/v5/events/$eventId")
                .header("Authorization", "Bearer $accessToken")
                .header("x-pm-uid", uid)
                .header("x-pm-appversion", ProtonSyncApp.APP_VERSION)
                .build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string()
                gson.fromJson<Map<String, Any>>(body, object : TypeToken<Map<String, Any>>() {}.type)
            } else {
                SyncLog.w(TAG, "Event loop poll failed: HTTP ${response.code}" + ProtonApiError.describe(readErrorBody(response)))
                response.close()
                null
            }
        } catch (e: Exception) {
            SyncLog.w(TAG, "Event loop poll error: ${e.message}")
            null
        }
    }

    /** A failed response's body text for [ProtonApiError.describe], or null when it cannot be read. */
    private fun readErrorBody(response: okhttp3.Response): String? =
        try {
            response.body?.string()
        } catch (_: Exception) {
            null
        }

    private fun triggerSync(accountManager: AccountManager, accountName: String, contacts: Boolean, calendar: Boolean) {
        val bundle = Bundle().apply {
            putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
            putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
            putBoolean(SyncConstants.EXTRA_SCHEDULED_BY_WORKER, true)
        }

        if (contacts) {
            // Match on the parent-account user-data link rather than .name. The address-book
            // account's name mirrors the parent's display name, but one made by an older version
            // carries a different name until the start-up migration renames it, and the link
            // holds either way.
            val abAccount = accountManager.getAccountsByType(ProtonSyncApp.ACCOUNT_TYPE_ADDRESS_BOOK)
                .firstOrNull { accountManager.getUserData(it, SyncConstants.EXTRA_PARENT_ACCOUNT_NAME) == accountName }
            if (abAccount != null && ContentResolver.getSyncAutomatically(abAccount, SyncConstants.AUTHORITY_CONTACTS)) {
                ContentResolver.requestSync(abAccount, SyncConstants.AUTHORITY_CONTACTS, bundle)
            }
        }

        if (calendar) {
            val mainAccount = accountManager.getAccountsByType(ProtonSyncApp.ACCOUNT_TYPE)
                .firstOrNull { it.name == accountName }
            if (mainAccount != null && ContentResolver.getSyncAutomatically(mainAccount, SyncConstants.AUTHORITY_CALENDAR)) {
                ContentResolver.requestSync(mainAccount, SyncConstants.AUTHORITY_CALENDAR, bundle)
            }
        }
    }

    companion object {
        private const val TAG = "EventLoop"
        private const val KEY_ACCOUNT_NAME = "account_name"
        private const val MIN_INTERVAL_MINUTES = 15L

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

        /** The unique work name of an account's event loop (also read by the schedule_info debug command). */
        internal fun workName(accountName: String) = "proton_event_loop_${accountName.hashCode()}"

        /**
         * Schedule the event loop poller for an account.
         *
         * @param intervalMinutes User-configured interval (mapped from seconds in prefs by
         *                        [intervalMinutesFor]). Zero or negative cancels.
         */
        fun scheduleForAccount(context: Context, accountName: String, intervalMinutes: Long) {
            val workName = workName(accountName)

            // <= 0, not < 0. A caller dividing a -1 SECONDS sentinel by 60 gets 0, and 0 used to
            // fall through and schedule at MIN_INTERVAL_MINUTES - the opposite of "off".
            if (intervalMinutes <= 0) {
                WorkManager.getInstance(context).cancelUniqueWork(workName)
                SyncLog.i(TAG, "Cancelled event loop for $accountName")
                return
            }

            val effectiveInterval = maxOf(intervalMinutes, MIN_INTERVAL_MINUTES)
            if (intervalMinutes < MIN_INTERVAL_MINUTES) {
                SyncLog.i(TAG, "Requested ${intervalMinutes}m for $accountName, clamped to ${effectiveInterval}m (WorkManager minimum)")
            }

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val inputData = Data.Builder()
                .putString(KEY_ACCOUNT_NAME, accountName)
                .build()

            val request = PeriodicWorkRequestBuilder<EventLoopWorker>(
                effectiveInterval, TimeUnit.MINUTES,
                minOf(5, effectiveInterval / 3), TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setInputData(inputData)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                workName,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
            SyncLog.i(TAG, "Scheduled event loop for $accountName every ${effectiveInterval}m")
        }

        /**
         * Cancel the event loop for every Proton account. Used when subscription
         * becomes inactive so the device isn't woken up for work the adapter
         * will only skip.
         */
        fun cancelAll(context: Context) {
            val accountManager = AccountManager.get(context)
            val accounts = accountManager.getAccountsByType(ProtonSyncApp.ACCOUNT_TYPE)
            for (account in accounts) {
                scheduleForAccount(context, account.name, -1)
            }
        }

        /**
         * Schedule event loops for all accounts based on their sync interval settings.
         */
        fun scheduleAll(context: Context) {
            val accountManager = AccountManager.get(context)
            for (account in accountManager.getAccountsByType(ProtonSyncApp.ACCOUNT_TYPE)) {
                scheduleFromSettings(context, account.name)
            }
        }

        /**
         * Schedule one account's event loop from its stored interval, or cancel it for "Manual only".
         * The body of [scheduleAll], on its own so a new or renamed account can be armed without
         * touching the others (SyncScheduling.scheduleWorkers).
         */
        fun scheduleFromSettings(context: Context, accountName: String) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val intervalSeconds = prefs.getString(
                SyncPreferences.eventLoopIntervalKey(accountName),
                "3600" // default 1 hour
            )?.toLongOrNull() ?: 3600L

            val minutes = intervalMinutesFor(intervalSeconds)
            if (schedulesWork(minutes)) {
                scheduleForAccount(context, accountName, minutes)
            } else {
                // CANCEL, not skip. Skipping only declines to create a job; it leaves any
                // existing one running, and WorkManager unique periodic work outlives an app
                // upgrade and a force-stop. A user who chose "Manual only" on v1.10.49 got a
                // 15-minute job from that release's truncation bug, and updating to v1.10.50
                // fixed the setting for the FUTURE while that job kept firing forever, because
                // nothing here ever cancelled it. Seen in the field: after the update and a
                // force-stop, the job still fired every 15 minutes.
                // SyncWorker.scheduleAll has always had this else branch; this one did not.
                scheduleForAccount(context, accountName, -1)
            }
        }
    }
}

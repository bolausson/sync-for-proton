package me.proton.sync

import android.accounts.Account
import android.accounts.AccountManager
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import me.proton.sync.SyncLog
import androidx.core.app.NotificationCompat
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.launch
import me.proton.sync.account.AccountSessionManager
import me.proton.sync.account.LoginActivity
import me.proton.sync.billing.SubscriptionManager
import me.proton.sync.calendar.EventConflictGuard
import me.proton.sync.db.SyncDatabase
import javax.inject.Inject
import android.content.ContentResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

@HiltAndroidApp
class ProtonSyncApp : Application() {

    @Inject lateinit var accountSessionManager: AccountSessionManager
    @Inject lateinit var syncDatabase: SyncDatabase
    @Inject lateinit var subscriptionManager: SubscriptionManager

    /**
     * The earliest the app's own code runs, before any ContentProvider. androidx.startup's provider
     * initialises WorkManager before [onCreate], and a worker started from there can parse iCal, so
     * ical4j's time-zone download is switched OFF here, unconditionally and for good: since 1.11.5 no
     * event time comes from ical4j, and "Update time zones online" uses the app's own client instead
     * (IcalTimeZoneUpdates, TimeZoneRuleSource).
     */
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        me.proton.sync.calendar.IcalTimeZoneUpdates.disable()
    }

    override fun onCreate() {
        super.onCreate()
        // Must precede any network/JCA use: Android 16 (targetSdk 36) drops the
        // platform BC provider and with it the default "BKS" keystore type, which
        // every OkHttp trust-manager init resolves. The license validate kicked off
        // by subscriptionManager.connect() below is the first such use.
        me.proton.sync.crypto.BouncyCastleSetup.ensureInstalled()
        // Before anything reads a setting. A stored value of the wrong type (an edited settings backup
        // restored by an older version) makes every reader of it throw, and several are read below outside
        // any try, so it stopped every start of the app. Logged after the start line, once the log file (if
        // the user keeps one) is open.
        val settingsRepair = repairSettingTypes()
        applyVerboseLoggingPref()
        // Resume the user-chosen file log if it was enabled and the folder grant still holds.
        // This is the only path that writes the log to storage; without it the log lives in
        // memory only and does not survive the process.
        FileLogController.initFromPrefs(this)
        SyncLog.i(TAG, "${getString(R.string.app_name)} ${getString(R.string.version_label)} starting (verbose=${SyncLog.isVerboseEnabled()})")
        settingsRepair?.let { SyncLog.w(TAG, it) }
        // The field evidence for event times: which tz data java.time uses here, whether it looks stuck,
        // and whether the app may contact tzurl.org ("Update time zones online").
        logTimeZoneSource()
        logCryptoBackends()
        // Log the Room schema version every launch so migrations are verifiable from a field log.
        // Reading readableDatabase opens the DB (and applies any pending migration) — do it off
        // the main thread.
        Thread {
            try {
                SyncLog.i(TAG, "Room DB schema version = ${syncDatabase.openHelper.readableDatabase.version}")
            } catch (e: Exception) {
                SyncLog.w(TAG, "Could not read Room DB schema version: ${e.message}")
            }
            // One-time cleanup. The first v1.9.4 build kept an app-private log directory
            // unconditionally. That behaviour is gone, so remove what it left behind instead
            // of leaving up to a megabyte of logs on devices that never asked to store any.
            try {
                val legacy = java.io.File(filesDir, LEGACY_LOG_DIR)
                if (legacy.exists() && legacy.deleteRecursively()) {
                    SyncLog.i(TAG, "Removed the legacy automatic log directory")
                }
            } catch (_: Throwable) { /* best effort; never block start-up over a cleanup */ }
            // Event times are computed differently than when the stored ones were (an app update, a tz
            // data update): ask for a calendar sync once, so the re-time pass runs soon. Off the main
            // thread, like everything that touches AccountManager here.
            try {
                me.proton.sync.calendar.TimeZoneRuleSource.requestRetimeIfIdentityChanged(this)
            } catch (e: Exception) {
                SyncLog.w(TAG, "Could not check the event time identity: ${e.message}")
            }
        }.start()

        // Enable relaxed iCal parsing for malformed calendar data
        System.setProperty("ical4j.parsing.relaxed", "true")
        System.setProperty("ical4j.unfolding.relaxed", "true")
        System.setProperty("ical4j.compatibility.outlook", "true")

        createNotificationChannel()

        // Re-arm the local data-backup schedule. Independent of the subscription gate —
        // a user's data backup is never withheld — so it lives here, not in the
        // subscription observer that arms the sync schedules.
        me.proton.sync.backup.BackupScheduleWorker.reschedule(this)

        migrateParentAccountNames()
        migrateAddressBookAccountNames()

        // Sync schedules are armed by the subscription observer, once SubscriptionManager emits a
        // non-Unknown state (cached or live), never unconditionally here: that was a double arm,
        // with the observer re-arming the moment the state resolved. The observer goes BEFORE
        // connect() because it reads the cached state first, before either backend can change it:
        // that is how it tells a plain start of an active install (re-arm only) from an activation
        // (re-arm and one catch-up sync). Otherwise the order does not matter, since a StateFlow
        // replays its current value to a collector that arrives late.
        observeSubscriptionForScheduling()

        // Connect the subscription backend (Play Billing, or the Paddle licence check in the
        // direct flavour) so the subscription gate resolves early.
        subscriptionManager.connect()

        val accountManager = AccountManager.get(this)
        accountManager.addOnAccountsUpdatedListener({ accounts ->
            val activeNames = accounts
                .filter { it.type == ACCOUNT_TYPE }
                .map { it.name }
                .toSet()

            accountSessionManager.removeStaleAccounts(activeNames)

            // Remove orphaned address book accounts whose parent was removed.
            // Match by EXTRA_PARENT_ACCOUNT_NAME (set at login): the ab-account.name mirrors the
            // parent's name, but one made by an older version can carry a different name until
            // the start-up migration renames it. Fall back to .name comparison for an ab-account
            // without the link, so it is not wrongly classified as an orphan.
            val addressBookAccounts = accounts.filter { it.type == ACCOUNT_TYPE_ADDRESS_BOOK }
            for (abAccount in addressBookAccounts) {
                val parentName = accountManager.getUserData(abAccount, SyncConstants.EXTRA_PARENT_ACCOUNT_NAME)
                    ?: abAccount.name
                if (parentName !in activeNames) {
                    SyncLog.i(TAG, "Removing orphaned address book account: ${abAccount.name}")
                    accountManager.removeAccountExplicitly(abAccount)
                }
            }

            // Preferences and scheduled work for accounts that are gone. This listener already
            // reaps sessions, orphaned address-book accounts and Room rows, and skipped only these
            // two - which mattered little until v1.10.51 started HONOURING a stored interval of -1
            // rather than ignoring it. A leftover event_loop_interval_<name> = -1 from a removed
            // account then applied to a new account of the same name, and it would never sync in
            // the background with nothing on screen to explain why. The scheduled work is the same
            // shape of leak: work names are derived from the account name, so once the account is
            // gone every cancelAll/scheduleAll pass iterates live accounts and can never reach it.
            forgetRemovedAccounts(activeNames)

            // Room cleanup on a coroutine
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                try {
                    syncDatabase.contactSyncDao().deleteByAccountNotIn(activeNames)
                    syncDatabase.calendarSyncDao().deleteByAccountNotIn(activeNames)
                    syncDatabase.calendarEventSyncDao().deleteByAccountNotIn(activeNames)
                } catch (e: Exception) {
                    SyncLog.e(TAG, "Failed to clean up database for removed accounts", e)
                }
            }
        }, null, true)
    }

    /**
     * One-shot migration: rename parent Proton accounts whose name still equals the user-chosen
     * alias to the email-shaped `proton_username` form, so that the Settings → Manage accounts
     * list is consistent with the address-book accounts (which were renamed in v1.3.79). This
     * is the bigger of the two account renames — the parent account name is keyed by every
     * SharedPreferences setting, every Room row, every CalendarContract.Calendars row, and
     * every authToken / userData entry on the AccountManager record.
     *
     * Must run BEFORE [migrateAddressBookAccountNames] so the address-book account's
     * EXTRA_PARENT_ACCOUNT_NAME points at the new parent name when the AB-side migration
     * does its parent lookup.
     *
     * Steps per stale parent:
     *  1. Add a new parent account with the email-shaped name.
     *  2. Copy all known user-data fields + auth token from old to new.
     *  3. Rename the account_name in every Room sync table (3 tables).
     *  4. Rename the account_name in CalendarContract.Calendars (events follow via FK).
     *  5. Rename every SharedPreferences key whose suffix or middle token is the old name.
     *  6. Update the linked address-book account's EXTRA_PARENT_ACCOUNT_NAME.
     *  7. Re-arm calendar sync settings (setIsSyncable / setSyncAutomatically / SyncUtils.applyPeriodicSync),
     *     and move the old name's scheduled work to the new name.
     *  8. Drop the in-memory session for the old name from AccountSessionManager.
     *  9. Remove the old parent account.
     */
    private fun migrateParentAccountNames() {
        val am = AccountManager.get(this)
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
        val parents = am.getAccountsByType(ACCOUNT_TYPE)
        for (oldParent in parents) {
            // Email-shaped existing name is a user choice (custom-domain login like
            // a@example.org, or already-migrated `<username>@proton.me`). Don't
            // overwrite it — the v1.3.80 forced rename did exactly that and nuked
            // user-chosen display names. The migration only needs to upgrade bare
            // canonical usernames (`<username>` → `<username>@proton.me`).
            if (oldParent.name.contains('@')) continue
            // The @-check above only covers email-shaped renames. If the user picked
            // a bare custom name (e.g. `Alice`, or stripped the @ off a canonical
            // username), the migration would still want to "fix" it back to email-shape
            // on every launch. The user-chosen flag — set at login and by the in-app
            // rename dialog — is the durable signal that we should leave the name alone.
            if (prefs.getBoolean(SyncPreferences.displayNameUserChosenKey(oldParent.name), false)) continue
            val protonUsername = am.getUserData(oldParent, "proton_username")
            if (protonUsername.isNullOrEmpty()) continue
            val desired = if (protonUsername.contains('@')) protonUsername else "$protonUsername@proton.me"
            if (desired == oldParent.name) continue
            renameParentAccount(oldParent, desired)
        }
    }

    /**
     * Atomically rename a parent Proton account from [oldParent].name to [desired].
     *
     * Re-points every place the account name appears: Room tables, CalendarContract,
     * SharedPreferences, the linked address-book account's parent link, and the
     * AccountSessionManager cache. Then drops the old AccountManager record.
     *
     * Used both by [migrateParentAccountNames] (the v1.3.80 one-shot upgrade of bare
     * canonical usernames) and by the in-app Display Name editor in
     * [me.proton.sync.settings.SyncSettingsActivity].
     *
     * Returns true if the rename succeeded (or the desired name already existed and the
     * stale copy was removed); false on any failure.
     */
    fun renameParentAccount(oldParent: Account, desired: String): Boolean {
        val am = AccountManager.get(this)
        if (desired == oldParent.name) return true
        if (am.getAccountsByType(ACCOUNT_TYPE).any { it.name == desired }) {
            SyncLog.i(TAG, "Parent rename: target $desired already exists, removing legacy ${oldParent.name}")
            am.removeAccountExplicitly(oldParent)
            return true
        }
        SyncLog.i(TAG, "Parent rename: ${oldParent.name} → $desired")
        // The deletion prompt's notification id is built from the account name, while its state moves
        // with the preference keys, so the old notification would sit there for ever with nothing behind
        // it. Take it down; the next sync re-posts it under the new name from the migrated count.
        for (t in listOf(
            RemoteDeletionGuard.TYPE_CALENDAR,
            RemoteDeletionGuard.TYPE_CONTACTS,
            RemoteDeletionGuard.TYPE_CONTACTS_FILTER
        )) {
            SyncNotificationHelper.cancelRemoteDeletionPrompt(this, oldParent.name, t)
        }
        // Same for the per-event collision prompts, whose ids carry the account name too. Their
        // buttons name the old account, so answering one after the rename released a hold that had
        // already moved to the new name and the event stayed blocked for ever. The hold itself moves
        // with its preference key below, and the next sync posts the prompt again under the new name.
        for (protonEventId in EventConflictGuard.heldIds(this, oldParent.name)) {
            SyncNotificationHelper.cancelEventConflict(this, oldParent.name, protonEventId)
        }
        return try {
            val newParent = Account(desired, ACCOUNT_TYPE)
            if (!am.addAccountExplicitly(newParent, null, null)) {
                SyncLog.w(TAG, "Parent rename: could not add $desired, leaving ${oldParent.name} in place")
                return false
            }

            // Copy all known user-data fields. SecureStorage-encrypted values (mailbox_password)
            // round-trip cleanly because the keystore key is per-app, not per-account.
            for (key in PARENT_USER_DATA_KEYS) {
                am.getUserData(oldParent, key)?.let { am.setUserData(newParent, key, it) }
            }
            am.peekAuthToken(oldParent, AccountSessionManager.AUTH_TOKEN_TYPE)?.let {
                am.setAuthToken(newParent, AccountSessionManager.AUTH_TOKEN_TYPE, it)
            }

            // Room tables
            kotlinx.coroutines.runBlocking {
                val a = syncDatabase.contactSyncDao().renameAccount(oldParent.name, desired)
                val b = syncDatabase.calendarSyncDao().renameAccount(oldParent.name, desired)
                val c = syncDatabase.calendarEventSyncDao().renameAccount(oldParent.name, desired)
                SyncLog.i(TAG, "Parent rename: re-pointed Room rows (contact=$a, calendar=$b, events=$c)")
            }

            // CalendarContract.Calendars — events stay bound to their calendar via FK.
            val calsUri = android.provider.CalendarContract.Calendars.CONTENT_URI.buildUpon()
                .appendQueryParameter(android.provider.CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                .appendQueryParameter(android.provider.CalendarContract.Calendars.ACCOUNT_NAME, oldParent.name)
                .appendQueryParameter(android.provider.CalendarContract.Calendars.ACCOUNT_TYPE, ACCOUNT_TYPE)
                .build()
            val calValues = android.content.ContentValues().apply {
                put(android.provider.CalendarContract.Calendars.ACCOUNT_NAME, desired)
            }
            val calsUpdated = contentResolver.update(
                calsUri, calValues,
                "${android.provider.CalendarContract.Calendars.ACCOUNT_NAME} = ? AND " +
                    "${android.provider.CalendarContract.Calendars.ACCOUNT_TYPE} = ?",
                arrayOf(oldParent.name, ACCOUNT_TYPE)
            )
            SyncLog.i(TAG, "Parent rename: re-pointed $calsUpdated calendar(s)")

            // SharedPreferences — every key built by SyncPreferences ends with `_$accountName`
            // or contains `_${accountName}_`. Rename all matching keys atomically.
            renameAccountInPrefs(oldParent.name, desired)

            // Update the linked address-book account's parent-link user-data.
            am.getAccountsByType(ACCOUNT_TYPE_ADDRESS_BOOK)
                .filter { am.getUserData(it, SyncConstants.EXTRA_PARENT_ACCOUNT_NAME) == oldParent.name }
                .forEach { am.setUserData(it, SyncConstants.EXTRA_PARENT_ACCOUNT_NAME, desired) }

            // Carry calendar sync state to the new parent.
            val syncOn = ContentResolver.getSyncAutomatically(oldParent, SyncConstants.AUTHORITY_CALENDAR)
            ContentResolver.setIsSyncable(newParent, SyncConstants.AUTHORITY_CALENDAR, 1)
            ContentResolver.setSyncAutomatically(newParent, SyncConstants.AUTHORITY_CALENDAR, syncOn)
            // The preferences were renamed above, so this reads the account's own interval.
            SyncUtils.applyPeriodicSync(this, newParent, SyncConstants.AUTHORITY_CALENDAR, desired)

            // The in-memory session map is keyed by name; drop the old key so the next
            // getSession() rebuilds from AccountManager under the new name.
            accountSessionManager.removeSession(oldParent.name)

            am.removeAccountExplicitly(oldParent)
            // Work names derive from the account name, and once renameAccountInPrefs has moved the
            // keys, forgetRemovedAccounts can no longer find the old name: its event loop and
            // watchdogs ran for ever against an account that does not exist, and the new name had
            // none until the next start.
            EventLoopWorker.scheduleForAccount(this, oldParent.name, -1)
            SyncWorker.cancelForAccount(this, oldParent.name, "contacts")
            SyncWorker.cancelForAccount(this, oldParent.name, "calendar")
            SyncScheduling.scheduleWorkers(this, desired)
            SyncLog.i(TAG, "Parent rename: ${oldParent.name} → $desired complete")
            true
        } catch (e: Exception) {
            SyncLog.e(TAG, "Parent rename failed for ${oldParent.name}", e)
            false
        }
    }

    /**
     * Drops per-account preferences and scheduled work for accounts that no longer exist.
     *
     * Only names this app owns are considered: the key convention is `<setting>_<accountName>`, so
     * a name is taken from the suffix of a key we recognise (SyncPreferences.ACCOUNT_SCOPED_PREFIXES)
     * and removed only when AccountManager no longer lists it under any reading of the key
     * (SyncPreferences.keysOfRemovedAccounts). Anything unrecognised is left alone.
     */
    private fun forgetRemovedAccounts(activeNames: Set<String>) {
        try {
            val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
            val removed = SyncPreferences.keysOfRemovedAccounts(prefs.all.keys, activeNames)
            if (removed.isEmpty()) return
            val stale = LinkedHashSet(removed.values)
            val edit = prefs.edit()
            for (key in removed.keys) edit.remove(key)
            // Read before the keys go, because the prompts are keyed by account name and event id and
            // there is nothing left to name them by afterwards.
            val strandedPrompts = stale.associateWith { EventConflictGuard.heldIds(this, it) }
            edit.apply()
            for (name in stale) {
                EventLoopWorker.scheduleForAccount(this, name, -1)
                SyncWorker.cancelForAccount(this, name, "contacts")
                SyncWorker.cancelForAccount(this, name, "calendar")
                // Its held server-to-local deletions, and their ongoing prompts, go with it. Not via
                // ACCOUNT_SCOPED_PREFIXES: "remote_del_pending_contacts_" is also a prefix of the
                // contacts-filter key, so the account name would be read as "filter_<name>" and the
                // wrong entry deleted.
                RemoteDeletionGuard.forgetAllHolds(this, name)
                // And the collision holds. Re-adding the same address inherited them otherwise: events
                // held out of both directions by a question asked of the account's previous life, about
                // local rows that a re-add does not bring back.
                EventConflictGuard.forgetAll(this, name)
                for (protonEventId in strandedPrompts[name].orEmpty()) {
                    SyncNotificationHelper.cancelEventConflict(this, name, protonEventId)
                }
            }
            SyncLog.i(TAG, "Forgot settings and scheduled work for removed account(s): ${stale.joinToString()}")
        } catch (e: Exception) {
            SyncLog.w(TAG, "Could not clean up preferences for removed accounts: ${e.javaClass.simpleName}")
        }
    }

    /**
     * Renames every SharedPreferences key whose tail or middle token equals [oldName] so it
     * uses [newName] instead. SyncPreferences keys are always `prefix_$accountName` or
     * `prefix_${accountName}_suffix`, so we match against `_oldName` followed by `_` or
     * end-of-string. Account-name regex meta-chars are escaped defensively.
     */
    private fun renameAccountInPrefs(oldName: String, newName: String) {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
        val all = prefs.all
        val edit = prefs.edit()
        var renamed = 0
        for ((key, value) in all) {
            // Never a device-wide key: an account called "updates" would otherwise carry off
            // "online_timezone_updates_enabled" (SyncPreferences.DEVICE_WIDE_KEYS).
            val newKey = SyncPreferences.renamedAccountKey(key, oldName, newName) ?: continue
            when (value) {
                is String -> edit.putString(newKey, value)
                is Boolean -> edit.putBoolean(newKey, value)
                is Int -> edit.putInt(newKey, value)
                is Long -> edit.putLong(newKey, value)
                is Float -> edit.putFloat(newKey, value)
                is Set<*> -> @Suppress("UNCHECKED_CAST") edit.putStringSet(newKey, value as Set<String>)
            }
            edit.remove(key)
            renamed++
        }
        edit.apply()
        if (renamed > 0) SyncLog.i(TAG, "Parent migration: renamed $renamed pref key(s)")
    }

    /**
     * Migration: rename address-book accounts whose name differs from the one
     * [SyncUtils.addressBookAccountName] wants, which is the parent account's name. From v1.3.79
     * until v1.4.14 dropped it, they had an email-shaped name derived from `proton_username`, so
     * one Proton account showed up under two names in Android's account list.
     *
     * Safe to call on every startup — it's a no-op once accounts are already migrated.
     * Steps per stale ab-account:
     *  1. Add a new ab-account with the desired name (preserving parent-link user-data).
     *  2. UPDATE every RawContact + Settings row from the old account_name to the new one
     *     (CALLER_IS_SYNCADAPTER so the provider doesn't dirty-flag every contact).
     *  3. Copy sync settings (setIsSyncable / setSyncAutomatically / SyncUtils.applyPeriodicSync).
     *  4. Remove the old ab-account.
     */
    private fun migrateAddressBookAccountNames() {
        val am = AccountManager.get(this)
        val abAccounts = am.getAccountsByType(ACCOUNT_TYPE_ADDRESS_BOOK)
        for (oldAb in abAccounts) {
            val parentName = am.getUserData(oldAb, SyncConstants.EXTRA_PARENT_ACCOUNT_NAME) ?: oldAb.name
            val parent = am.getAccountsByType(ACCOUNT_TYPE).firstOrNull { it.name == parentName } ?: continue
            val desired = SyncUtils.addressBookAccountName(am, parent)
            if (desired == oldAb.name) continue  // already migrated
            if (am.getAccountsByType(ACCOUNT_TYPE_ADDRESS_BOOK).any { it.name == desired }) {
                // Already exists (e.g. from an earlier interrupted migration). Just remove the old.
                SyncLog.i(TAG, "Address-book migration: target $desired already exists, removing legacy ${oldAb.name}")
                am.removeAccountExplicitly(oldAb)
                continue
            }
            SyncLog.i(TAG, "Address-book migration: ${oldAb.name} → $desired")
            try {
                val newAb = Account(desired, ACCOUNT_TYPE_ADDRESS_BOOK)
                val added = am.addAccountExplicitly(newAb, null, android.os.Bundle().apply {
                    putString(SyncConstants.EXTRA_PARENT_ACCOUNT_NAME, parentName)
                    putString(SyncConstants.EXTRA_PARENT_ACCOUNT_TYPE, ACCOUNT_TYPE)
                })
                if (!added) {
                    SyncLog.w(TAG, "Address-book migration: could not add $desired, leaving ${oldAb.name} in place")
                    continue
                }

                // Move RawContacts + Data rows from the old account_name to the new one.
                val rawUri = android.provider.ContactsContract.RawContacts.CONTENT_URI.buildUpon()
                    .appendQueryParameter(android.provider.ContactsContract.CALLER_IS_SYNCADAPTER, "true")
                    .appendQueryParameter(android.provider.ContactsContract.RawContacts.ACCOUNT_NAME, oldAb.name)
                    .appendQueryParameter(android.provider.ContactsContract.RawContacts.ACCOUNT_TYPE, ACCOUNT_TYPE_ADDRESS_BOOK)
                    .build()
                val rawValues = android.content.ContentValues().apply {
                    put(android.provider.ContactsContract.RawContacts.ACCOUNT_NAME, desired)
                }
                val updated = contentResolver.update(
                    rawUri, rawValues,
                    "${android.provider.ContactsContract.RawContacts.ACCOUNT_NAME} = ? AND " +
                        "${android.provider.ContactsContract.RawContacts.ACCOUNT_TYPE} = ?",
                    arrayOf(oldAb.name, ACCOUNT_TYPE_ADDRESS_BOOK)
                )
                SyncLog.i(TAG, "Address-book migration: re-pointed $updated raw contact(s)")

                // Move ContactsContract.Settings row.
                val newSettings = android.content.ContentValues().apply {
                    put(android.provider.ContactsContract.Settings.ACCOUNT_NAME, desired)
                    put(android.provider.ContactsContract.Settings.ACCOUNT_TYPE, ACCOUNT_TYPE_ADDRESS_BOOK)
                    put(android.provider.ContactsContract.Settings.SHOULD_SYNC, 1)
                    put(android.provider.ContactsContract.Settings.UNGROUPED_VISIBLE, 1)
                }
                contentResolver.insert(
                    android.provider.ContactsContract.Settings.CONTENT_URI.buildUpon()
                        .appendQueryParameter(android.provider.ContactsContract.CALLER_IS_SYNCADAPTER, "true")
                        .appendQueryParameter(android.provider.ContactsContract.Settings.ACCOUNT_NAME, desired)
                        .appendQueryParameter(android.provider.ContactsContract.Settings.ACCOUNT_TYPE, ACCOUNT_TYPE_ADDRESS_BOOK)
                        .build(),
                    newSettings
                )
                contentResolver.delete(
                    android.provider.ContactsContract.Settings.CONTENT_URI.buildUpon()
                        .appendQueryParameter(android.provider.ContactsContract.CALLER_IS_SYNCADAPTER, "true")
                        .build(),
                    "${android.provider.ContactsContract.Settings.ACCOUNT_NAME} = ? AND " +
                        "${android.provider.ContactsContract.Settings.ACCOUNT_TYPE} = ?",
                    arrayOf(oldAb.name, ACCOUNT_TYPE_ADDRESS_BOOK)
                )

                // Copy sync settings.
                val syncOn = ContentResolver.getSyncAutomatically(oldAb, SyncConstants.AUTHORITY_CONTACTS)
                ContentResolver.setIsSyncable(newAb, SyncConstants.AUTHORITY_CONTACTS, 1)
                ContentResolver.setSyncAutomatically(newAb, SyncConstants.AUTHORITY_CONTACTS, syncOn)
                SyncUtils.applyPeriodicSync(this, newAb, SyncConstants.AUTHORITY_CONTACTS, parentName)

                am.removeAccountExplicitly(oldAb)
                SyncLog.i(TAG, "Address-book migration: ${oldAb.name} → $desired complete")
            } catch (e: Exception) {
                SyncLog.e(TAG, "Address-book migration failed for ${oldAb.name}", e)
            }
        }
    }

    /**
     * Cancel all WorkManager periodic jobs and Android sync-framework periodic
     * schedules when the subscription goes inactive, and re-arm them when it
     * becomes active again. Avoids waking the device for work the sync adapters
     * will only immediately skip.
     *
     * An activation within a running process, or after a start with no open gate, also requests
     * one catch-up sync (SyncScheduling.requestCatchUpSyncs): syncs the gate dropped meanwhile,
     * such as the setup chooser's first sync or a restore's, are not replayed otherwise, and a
     * restored or newly signed-in account sat empty until its first scheduled sync. A plain start
     * of an active install only re-arms. SyncScheduling.Tracker decides which is which.
     *
     * The user's `setSyncAutomatically` on/off preference is intentionally left
     * alone: we only remove the *periodic* portion, so when the subscription
     * is renewed the user's toggle state is preserved.
     */
    private fun observeSubscriptionForScheduling() {
        // Read here, on the calling thread, before connect() can change the state: the cached
        // verdict is what separates a plain start from one where the gate was closed.
        val tracker = SyncScheduling.Tracker(activeAtStart = subscriptionManager.isSyncAllowed())
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            subscriptionManager.state.collect { state ->
                when (val action = tracker.next(state)) {
                    SyncScheduling.Action.ARM, SyncScheduling.Action.ARM_AND_CATCH_UP -> {
                        SyncLog.i(TAG, "Subscription active - arming sync schedules")
                        SyncScheduling.armAll(this@ProtonSyncApp)
                        if (action == SyncScheduling.Action.ARM_AND_CATCH_UP) {
                            SyncScheduling.requestCatchUpSyncs(this@ProtonSyncApp)
                        }
                    }
                    SyncScheduling.Action.DISARM -> {
                        SyncLog.i(TAG, "Subscription inactive - cancelling sync schedules")
                        SyncScheduling.disarmAll(this@ProtonSyncApp)
                    }
                    SyncScheduling.Action.NONE -> {}
                }
            }
        }
    }



    /**
     * One line on where event times come from: the tz data version java.time reads on this phone (ICU's,
     * see TimeZoneRuleSource.phoneTzdataVersion), whether it looks stuck (TzDataFreshness), and the
     * "Update time zones online" switch. Pure reads, no network.
     */
    private fun logTimeZoneSource() {
        try {
            val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
            val version = me.proton.sync.calendar.TimeZoneRuleSource.phoneTzdataVersion()
            val outdated = me.proton.sync.calendar.TzDataFreshness.isOutdated(
                version, java.time.Year.now().value
            )
            val online = me.proton.sync.calendar.TimeZoneRuleSource.isOnlineEnabled(prefs)
            SyncLog.i(
                TAG,
                "Time zone data on this phone: ${version ?: "unknown"} (outdated: ${if (outdated) "yes" else "no"}); " +
                    "online time zone updates (tzurl.org): ${if (online) "on" else "off"}"
            )
        } catch (e: Exception) {
            SyncLog.w(TAG, "Could not read the time zone setup: ${e.message}")
        }
    }

    /**
     * Record which crypto backends actually loaded, so a field log says unambiguously what did the
     * work. The GopenPGP bridge is a native library: a packaging or minification mistake shows up
     * as UnsatisfiedLinkError at runtime rather than a build failure, so failing to load must be
     * visible rather than silent.
     *
     * NOT swallowed any more. GopenPGP is now the only OpenPGP implementation in the app, so a
     * bridge that fails to load means nothing can be decrypted at all — logging that as an error
     * and carrying on is the honest behaviour, and this line is the first thing to read in a
     * field report.
     */
    private fun logCryptoBackends() {
        try {
            SyncLog.i(TAG, "Crypto backends: ${bridge.Bridge.version()} (OpenPGP), BouncyCastle provider (JCA/TLS only)")
        } catch (e: Throwable) {
            SyncLog.e(TAG, "GopenPGP bridge failed to load - NOTHING can be decrypted: ${e.javaClass.simpleName}: ${e.message}", e)
        }
    }

    /**
     * Puts every setting a settings backup carries back into the type its readers ask for
     * ([me.proton.sync.backup.BackupSettings.repairStoredTypes]). Returns the line to log, naming keys only
     * and never a value, or null when nothing was wrong. Never throws: a failed check leaves start-up as it
     * was before the check existed.
     */
    private fun repairSettingTypes(): String? = try {
        val repair = me.proton.sync.backup.BackupSettings.repairStoredTypes(
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
        )
        if (repair.isEmpty) {
            null
        } else {
            "Settings stored with the wrong type, repaired before use:" + listOfNotNull(
                repair.converted.takeIf { it.isNotEmpty() }?.let { " converted ${it.joinToString()}" },
                repair.assumed.takeIf { it.isNotEmpty() }?.let { " unreadable, set to their safe value: ${it.joinToString()}" },
                repair.removed.takeIf { it.isNotEmpty() }?.let { " removed, so their defaults apply: ${it.joinToString()}" },
            ).joinToString(";")
        }
    } catch (e: Exception) {
        "Could not check the types of the stored settings: ${e.javaClass.simpleName}"
    }

    private fun applyVerboseLoggingPref() {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
        val enabled = prefs.getBoolean(
            SyncPreferences.KEY_VERBOSE_LOGGING,
            SyncPreferences.DEFAULT_VERBOSE_LOGGING
        )
        SyncLog.setVerboseEnabled(enabled)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val authChannel = NotificationChannel(
                CHANNEL_AUTH,
                getString(R.string.notification_channel_auth),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.notification_channel_auth_description)
            }
            val conflictChannel = NotificationChannel(
                CHANNEL_CONFLICT,
                getString(R.string.notification_channel_conflict),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = getString(R.string.notification_channel_conflict_description)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(authChannel)
            nm?.createNotificationChannel(conflictChannel)
        }
    }

    /**
     * Why are we asking the user to re-authenticate? Picks the appropriate notification
     * copy (token vs. decryption failure) and lets the user understand the difference.
     *
     *  - [SESSION_EXPIRED]: refresh token rejected, 401/403, account moved server-side.
     *    Re-entering the same login password will work.
     *  - [KEYS_LOCKED]: SRP login still works but the key passphrase is wrong (mailbox
     *    password rotated, account converted to two-password mode). The user may need
     *    to enter their *mailbox* password, not just their login password.
     *
     * Declared at outer-class level (not inside the companion) so external callers can
     * reference it as `ProtonSyncApp.AuthFailReason.X` — Kotlin does not surface nested
     * types declared inside a companion object via the outer class name.
     */
    enum class AuthFailReason { SESSION_EXPIRED, KEYS_LOCKED }

    companion object {
        const val TAG = "ProtonSync"

        /** Directory the first v1.9.4 build wrote logs into unconditionally; deleted on start. */
        private const val LEGACY_LOG_DIR = "diaglog"
        const val ACCOUNT_TYPE = "me.proton.sync"
        const val ACCOUNT_TYPE_ADDRESS_BOOK = "me.proton.sync.address_book"
        const val BASE_URL = "https://mail-api.proton.me/"
        const val APP_VERSION = "Other"
        const val CHANNEL_AUTH = "proton_sync_auth"
        const val CHANNEL_CONFLICT = "proton_sync_conflict"
        private const val NOTIFICATION_ID_AUTH = 9001

        /** All AccountManager user-data keys we set on the parent Proton account. Used by
         *  [migrateParentAccountNames] to copy state from the legacy-named account to the
         *  email-shaped one before deleting the old. Add to this list if you introduce a
         *  new setUserData call on a parent ACCOUNT_TYPE account. */
        private val PARENT_USER_DATA_KEYS = arrayOf(
            "proton_username",
            "alias",
            "refresh_token",
            "uid",
            "key_salt",
            "enc_private_key",
            "mailbox_password",
            "password_mode"
        )

        fun notifyReLoginRequired(
            context: Context,
            accountName: String,
            reason: AuthFailReason = AuthFailReason.SESSION_EXPIRED
        ) {
            val intent = Intent(context, LoginActivity::class.java).apply {
                putExtra(LoginActivity.EXTRA_ACCOUNT_NAME, accountName)
                // Not EXTRA_ADD_ACCOUNT: this notification names an account that already exists
                // and has merely been signed out. Saying "add" sent the user through new-account
                // setup, display-name prompt and all.
                putExtra(SyncConstants.EXTRA_REAUTH, true)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context, accountName.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val (titleRes, messageRes) = when (reason) {
                AuthFailReason.SESSION_EXPIRED ->
                    R.string.notification_auth_title to R.string.notification_auth_message
                AuthFailReason.KEYS_LOCKED ->
                    R.string.notification_decrypt_failed_title to R.string.notification_decrypt_failed_message
            }

            val notification = NotificationCompat.Builder(context, CHANNEL_AUTH)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(context.getString(titleRes))
                .setContentText(context.getString(messageRes, accountName))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build()

            try {
                val nm = context.getSystemService(NotificationManager::class.java)
                nm?.notify(NOTIFICATION_ID_AUTH + accountName.hashCode(), notification)
            } catch (e: SecurityException) {
                SyncLog.w(TAG, "Cannot post notification (permission denied): ${e.message}")
            }
        }
    }
}

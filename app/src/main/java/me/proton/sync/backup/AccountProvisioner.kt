package me.proton.sync.backup

import android.accounts.Account
import android.accounts.AccountManager
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.os.Bundle
import android.provider.ContactsContract
import me.proton.sync.ProtonSyncApp
import me.proton.sync.SyncConstants
import me.proton.sync.SyncLog
import me.proton.sync.SyncScheduling
import me.proton.sync.SyncUtils
import me.proton.sync.account.AccountSession
import me.proton.sync.account.AccountSessionManager

/**
 * The account-provisioning tail shared by first-time login and backup import.
 *
 * Historically this lived inline at the end of `LoginActivity.onLoginComplete`.
 * Extracting it guarantees a backup-restored account is wired up *identically*
 * to a freshly-logged-in one (parent account + address-book child + contacts
 * provider settings + auto-sync + periodic sync + its schedules), so the two paths
 * cannot drift.
 *
 * The caller is responsible for building the final [AccountSession] (including
 * any token merge) before calling [provision]; this object only persists it and
 * creates the surrounding Android account plumbing.
 */
object AccountProvisioner {

    private const val TAG = "AccountProvisioner"

    /**
     * Create (or refresh) the parent account and its address-book child, persist
     * the session, and enable contacts + calendar sync.
     *
     * @param finalSession the session to persist; its [AccountSession.accountName]
     *        is the user-visible display name (and the parent Account.name).
     * @param protonUsername the raw Proton login identifier (stored as `proton_username`
     *        user-data and used by `addressBookAccountName`); may differ from the display name.
     * @param requestInitialSync when true, fire an immediate expedited manual sync for both
     *        authorities. Sign-in passes true only for an account that already exists (a new
     *        account's first sync waits for the setup chooser). Import passes false: it asks for
     *        the first syncs itself once the restored settings are written, and only while the
     *        subscription is active; otherwise the catch-up sync the subscription observer
     *        requests when it turns active does it (ProtonSyncApp).
     */
    fun provision(
        context: Context,
        sessionManager: AccountSessionManager,
        finalSession: AccountSession,
        protonUsername: String,
        requestInitialSync: Boolean,
        /**
         * Whether to switch both sync types on straight away.
         *
         * A fresh sign-in passes false and asks instead. Copying a whole address book and every
         * calendar onto somebody's phone before they have said they want either is a decision the
         * app was making for them, and for a user who only wanted contacts it could mean a phone
         * full of calendar events they then could not remove.
         *
         * Restoring a backup passes true only for a backup too old to record the switches; a
         * current one passes [restoreSyncContacts] / [restoreSyncCalendar] instead and this flag
         * is not consulted for that type at all.
         */
        enableSync: Boolean = true,
        /**
         * The switch states a backup recorded, or null for anything that is not a restore.
         *
         * A restore should reproduce what the user had, which is neither "always on" (the old
         * behaviour, which overrode a deliberate choice) nor "whatever this device says" (which
         * ignores the backup). Non-null wins over every other rule here.
         */
        restoreSyncContacts: Boolean? = null,
        restoreSyncCalendar: Boolean? = null,
    ) {
        val displayName = finalSession.accountName
        val account = Account(displayName, ProtonSyncApp.ACCOUNT_TYPE)
        val accountManager = AccountManager.get(context)

        // Back in good standing — drop any backoff so the sync below can run.
        SyncUtils.clearSyncPause(context, displayName)
        SyncUtils.clearSyncPause(context, protonUsername)

        val existing = accountManager.getAccountsByType(ProtonSyncApp.ACCOUNT_TYPE)
        // Whether this is a brand new account or one signing in again. enableSync only describes
        // what a NEW account should start with; an account that already exists has sync settings
        // the user chose, and re-authenticating is not a request to change them.
        val isNewAccount = existing.none { it.name == displayName }
        if (isNewAccount) {
            // A false return means the OS refused to add the account — abort rather than
            // proceed to persist a session and arm periodic sync on a non-existent account.
            val added = accountManager.addAccountExplicitly(account, null, null)
            if (!added) {
                throw IllegalStateException("addAccountExplicitly refused for parent account '$displayName'")
            }
        }

        // Store raw Proton username for API reference.
        accountManager.setUserData(account, "proton_username", protonUsername)

        // Persist the session (Keystore rewrap of the mailbox password happens here).
        sessionManager.storeSession(account, finalSession)

        // Address-book child account mirrors the parent display name (v1.4.14+).
        val abAccountName = SyncUtils.addressBookAccountName(accountManager, account)
        val addressBookAccount = Account(abAccountName, ProtonSyncApp.ACCOUNT_TYPE_ADDRESS_BOOK)
        val abExists = accountManager.getAccountsByType(ProtonSyncApp.ACCOUNT_TYPE_ADDRESS_BOOK)
            .any { it.name == abAccountName }
        if (!abExists) {
            val abAdded = accountManager.addAccountExplicitly(addressBookAccount, null, Bundle().apply {
                putString(SyncConstants.EXTRA_PARENT_ACCOUNT_NAME, displayName)
                putString(SyncConstants.EXTRA_PARENT_ACCOUNT_TYPE, ProtonSyncApp.ACCOUNT_TYPE)
            })
            if (!abAdded) {
                SyncLog.w(TAG, "addAccountExplicitly refused for address-book child '$abAccountName'")
            }
        }

        // Contacts provider settings for the address-book account.
        try {
            val contactValues = ContentValues().apply {
                put(ContactsContract.Settings.ACCOUNT_NAME, addressBookAccount.name)
                put(ContactsContract.Settings.ACCOUNT_TYPE, addressBookAccount.type)
                put(ContactsContract.Settings.SHOULD_SYNC, 1)
                put(ContactsContract.Settings.UNGROUPED_VISIBLE, 1)
            }
            context.contentResolver.insert(
                ContactsContract.Settings.CONTENT_URI
                    .buildUpon()
                    .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true")
                    .appendQueryParameter(ContactsContract.Settings.ACCOUNT_NAME, addressBookAccount.name)
                    .appendQueryParameter(ContactsContract.Settings.ACCOUNT_TYPE, addressBookAccount.type)
                    .build(),
                contactValues
            )
        } catch (e: Exception) {
            SyncLog.w(TAG, "Failed to set contacts settings: ${e.message}")
        }

        // Enable contacts sync on the ADDRESS BOOK account.
        // Syncable either way: that is what makes the account appear in Android's own sync settings
        // and lets the user turn it on. Only the automatic part waits for their answer.
        // Re-authentication reaches here too: a session that expired, or Android's own sign-in
        // prompt for an account that already exists. Applying enableSync=false there switched both
        // sync types off for somebody who had them on and never asked for anything to change, and
        // said nothing about it. Only a new account takes the flag.
        // enableSync=true is a caller SAYING switch this on, and backup restore is exactly that:
        // it wipes the account's local rows first and needs the sync that rebuilds them. Reading
        // the existing setting for every account that already exists meant a restore over a
        // same-named account emptied it and then left sync off. Only the re-authentication case,
        // which passes the default true but has nothing to rebuild, wants the stored value, and it
        // is distinguished by not asking: a false here never turns anything on.
        val applySync = restoreSyncContacts
            ?: if (isNewAccount || enableSync) enableSync else
                ContentResolver.getSyncAutomatically(addressBookAccount, SyncConstants.AUTHORITY_CONTACTS)
        ContentResolver.setIsSyncable(addressBookAccount, SyncConstants.AUTHORITY_CONTACTS, 1)
        ContentResolver.setSyncAutomatically(addressBookAccount, SyncConstants.AUTHORITY_CONTACTS, applySync)
        // Through the shared writer. Removing matters as much as arming: a restore can switch a type
        // off, and the old job then survived it, waking every hour to be declined by the adapter's
        // own guard, with a periodic-sync list that disagreed with the switch (misleading enough that
        // it misled this project's own testing). The writer removes it, arms at the account's own
        // interval rather than an hour, and arms nothing while the subscription is inactive.
        SyncUtils.applyPeriodicSync(context, addressBookAccount, SyncConstants.AUTHORITY_CONTACTS, displayName)

        // Enable calendar sync on the MAIN account.
        val applyCalendar = restoreSyncCalendar
            ?: if (isNewAccount || enableSync) enableSync else
                ContentResolver.getSyncAutomatically(account, SyncConstants.AUTHORITY_CALENDAR)
        ContentResolver.setIsSyncable(account, SyncConstants.AUTHORITY_CALENDAR, 1)
        ContentResolver.setSyncAutomatically(account, SyncConstants.AUTHORITY_CALENDAR, applyCalendar)
        SyncUtils.applyPeriodicSync(context, account, SyncConstants.AUTHORITY_CALENDAR, displayName)

        // The event loop and the fallback watchdogs. The subscription observer arms them only at
        // process start or when the subscription turns active, so a new account (signed in or
        // restored) had no event loop until the next start of the app.
        SyncScheduling.scheduleWorkers(context, displayName)

        if (requestInitialSync) {
            requestImmediateSync(account, addressBookAccount)
        }
    }

    /**
     * Turns one sync type on after the user has asked for it, and starts it.
     *
     * The mirror of [provision]'s `enableSync = false`: the account exists and is syncable, but
     * nothing has been copied to the phone yet. Kept here so the switch-on sequence is written once
     * and the setup chooser cannot drift from what provisioning does. The periodic sync follows the
     * account's schedule and the subscription, through the shared writer.
     *
     * @param parentAccountName the parent account whose schedule applies; for contacts [account] is
     *   its address-book child.
     */
    fun enableSyncFor(context: Context, account: Account, authority: String, parentAccountName: String) {
        ContentResolver.setIsSyncable(account, authority, 1)
        ContentResolver.setSyncAutomatically(account, authority, true)
        SyncUtils.applyPeriodicSync(context, account, authority, parentAccountName)
        ContentResolver.requestSync(account, authority, Bundle().apply {
            putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
            putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
        })
    }

    /** Fire an expedited manual sync for contacts (AB account) and calendar (main account). */
    fun requestImmediateSync(account: Account, addressBookAccount: Account) {
        val syncExtras = Bundle().apply {
            putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
            putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
        }
        ContentResolver.requestSync(addressBookAccount, SyncConstants.AUTHORITY_CONTACTS, syncExtras)
        ContentResolver.requestSync(account, SyncConstants.AUTHORITY_CALENDAR, syncExtras)
    }
}

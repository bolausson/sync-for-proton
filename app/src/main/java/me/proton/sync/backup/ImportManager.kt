package me.proton.sync.backup

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import android.net.Uri
import android.provider.CalendarContract
import android.provider.ContactsContract
import androidx.preference.PreferenceManager
import me.proton.sync.BuildConfig
import me.proton.sync.ProtonSyncApp
import me.proton.sync.SyncLog
import me.proton.sync.SyncPreferences
import me.proton.sync.account.AccountSession
import me.proton.sync.account.AccountSessionManager
import me.proton.sync.crypto.SecureStorage
import me.proton.sync.db.SyncDatabase
import org.json.JSONObject

/**
 * Reads a `.sppbackup` file and restores it: parse envelope → (decrypt) → validate
 * → recreate accounts (via [AccountProvisioner], Keystore rewrap happens there) →
 * restore the allowlisted settings.
 *
 * The flow is staged so the UI can interleave prompts: [readEnvelope] first (cheap,
 * tells us whether a passphrase is needed and the source flavor), then
 * [decryptPayload] once the passphrase is in hand, then [collidingAccounts] to drive
 * the Skip/Overwrite prompt, then [apply].
 *
 * Never logs the payload.
 */
object ImportManager {

    private const val TAG = "ImportManager"

    /** Envelope metadata read before decryption — enough to drive the pre-flight UI. */
    data class EnvelopeInfo(
        val createdAt: String?,
        val sourceFlavor: String?,
        val sourceVersionCode: Int,
        val encrypted: Boolean,
        val raw: JSONObject
    ) {
        val flavorMismatch: Boolean
            get() = sourceFlavor != null && sourceFlavor != BuildConfig.FLAVOR
    }

    data class ImportResult(
        val imported: Int,
        val skipped: Int,
        val overwritten: Int,
        val failed: Int,
        val settingsRestored: Boolean
    )

    sealed class ReadOutcome {
        data class Ok(val info: EnvelopeInfo) : ReadOutcome()
        /** [reason] is a stable code: bad_file, wrong_format, unsupported_version. */
        data class Error(val reason: String) : ReadOutcome()
    }

    /** Parse + validate the envelope header. Does NOT decrypt. */
    fun readEnvelope(context: Context, uri: Uri): ReadOutcome {
        return try {
            val text = context.contentResolver.openInputStream(uri)?.use {
                it.readBytes().toString(Charsets.UTF_8)
            } ?: return ReadOutcome.Error("bad_file")
            val root = JSONObject(text)
            if (root.optString("format") != BackupFormat.FORMAT) {
                return ReadOutcome.Error("wrong_format")
            }
            if (root.optInt("version", -1) != BackupFormat.VERSION) {
                return ReadOutcome.Error("unsupported_version")
            }
            ReadOutcome.Ok(
                EnvelopeInfo(
                    createdAt = root.optNullableString("created_at"),
                    sourceFlavor = root.optNullableString("source_flavor"),
                    sourceVersionCode = root.optInt("source_version_code", 0),
                    encrypted = root.optBoolean("encrypted", false),
                    raw = root
                )
            )
        } catch (e: Exception) {
            SyncLog.w(TAG, "Failed to read backup envelope: ${e.javaClass.simpleName}")
            ReadOutcome.Error("bad_file")
        }
    }

    /**
     * Resolve the envelope into a [BackupPayload]. For an encrypted envelope a
     * [passphrase] is required. Honors the KDF parameters recorded in the file so
     * a backup written with a different iteration count still decrypts.
     *
     * @throws BackupCrypto.WrongPassphraseException on a bad passphrase / corrupt file.
     */
    fun decryptPayload(info: EnvelopeInfo, passphrase: String?): BackupPayload {
        val root = info.raw
        if (!info.encrypted) {
            // A subscription id is only ever exported under a password; one in a cleartext file was
            // put there by hand, and is not honoured.
            return BackupPayload.fromJson(root.getJSONObject("payload")).copy(subscriptionId = null)
        }
        require(passphrase != null) { "passphrase required" }
        val kdf = root.getJSONObject("kdf")
        val algo = kdf.optString("algo", BackupCrypto.KDF_ALGO)
        check(algo == BackupCrypto.KDF_ALGO) { "unsupported kdf algo: $algo" }
        val iterations = kdf.optInt("iterations", BackupCrypto.ITERATIONS)
        val salt = BackupCrypto.unb64(kdf.getString("salt"))
        val iv = BackupCrypto.unb64(root.getString("iv"))
        val ciphertext = BackupCrypto.unb64(root.getString("payload"))
        val plain = BackupCrypto.decrypt(ciphertext, passphrase, salt, iv, iterations)
        return BackupPayload.fromJson(JSONObject(plain.toString(Charsets.UTF_8)))
    }

    /**
     * Backup display names that already exist on this device — either the same display
     * name, OR the same Proton account (uid) signed in under a different local name.
     * Matching on uid prevents importing a second copy of an already-present account.
     */
    fun collidingAccounts(context: Context, payload: BackupPayload): List<String> =
        payload.accounts.filter { findExistingMatch(context, it) != null }.map { it.displayName }

    /** The existing parent account that an incoming backup account collides with, if any. */
    private fun findExistingMatch(context: Context, acct: BackupAccount): Account? {
        val am = AccountManager.get(context)
        val accounts = am.getAccountsByType(ProtonSyncApp.ACCOUNT_TYPE)
        accounts.firstOrNull { it.name == acct.displayName }?.let { return it }   // exact name
        if (acct.uid.isNotEmpty()) {
            // uid is wrapped at rest since 1.6.18 and AES-GCM is non-deterministic, so
            // the comparison must decrypt the stored value, never compare ciphertext.
            accounts.firstOrNull {
                am.getUserData(it, "uid")?.let { raw -> SecureStorage.decryptStrict(raw) } == acct.uid
            }?.let { return it }  // same uid, other name
        }
        return null
    }

    /**
     * Recreate accounts and restore settings.
     *
     * Overwrite is done WITHOUT a destroy-then-rebuild: a same-name account is
     * re-provisioned in place (provisioning is idempotent) after its stale local sync
     * state is wiped, so a mid-way failure never leaves the user with no account. A
     * uid match under a *different* name provisions the new account first, then removes
     * the stale duplicate.
     *
     * @param overwriteNames colliding backup display names the user chose to overwrite; any
     *        colliding name NOT in this set is skipped. Non-colliding accounts are always imported.
     */
    suspend fun apply(
        context: Context,
        sessionManager: AccountSessionManager,
        keyManager: me.proton.sync.crypto.KeyManager,
        syncDatabase: SyncDatabase,
        payload: BackupPayload,
        overwriteNames: Set<String>,
        restoreSettings: Boolean
    ): ImportResult {
        var imported = 0
        var skipped = 0
        var overwritten = 0
        var failed = 0
        val provisioned = mutableListOf<String>()

        for (acct in payload.accounts) {
            val match = findExistingMatch(context, acct)
            val collides = match != null
            if (collides && acct.displayName !in overwriteNames) {
                skipped++
                continue
            }
            try {
                val session = AccountSession(
                    accountName = acct.displayName,
                    accessToken = "",                 // minted from the refresh token on first sync (preflight)
                    refreshToken = acct.refreshToken,
                    uid = acct.uid,
                    keySalt = acct.keySalt,
                    encPrivateKey = acct.encPrivateKey,
                    mailboxPassword = acct.mailboxPassword,
                    passwordMode = acct.passwordMode
                )
                if (match != null && match.name == acct.displayName) {
                    // Same-name overwrite: wipe stale mappings/provider rows, then re-provision in place.
                    wipeLocalAccountData(context, syncDatabase, acct.displayName)
                    AccountProvisioner.provision(
                        context, sessionManager, session, acct.protonUsername,
                        requestInitialSync = false,
                        // Not the default true. This branch overwrites an account that is ALREADY
                        // on the phone, and its sync switches are the user's, not the backup's: a
                        // contacts-only user who turned calendar sync off and cleared their events
                        // would get both switched back on by a restore, and everything they had
                        // removed would download again. AccountProvisioner keeps the stored value
                        // when this is false and the account exists.
                        enableSync = false,
                        restoreSyncContacts = acct.syncContacts,
                        restoreSyncCalendar = acct.syncCalendar,
                    )
                } else {
                    // Fresh import, or a uid match under a different name. Provision the new account
                    // FIRST (so a failure can't lose the existing account), then drop the stale duplicate.
                    AccountProvisioner.provision(
                        context, sessionManager, session, acct.protonUsername,
                        requestInitialSync = false,
                        // This branch serves two different situations and they want opposite
                        // answers, so it must not pass a constant. A uid match under a different
                        // name is the SAME account wearing a new display name, and its switches
                        // belong to the user. A genuinely fresh import has no switches to respect
                        // and must end up syncing - and AccountProvisioner does NOT rescue a false
                        // here, since applySync is `if (isNewAccount || enableSync) enableSync`,
                        // which for a new account with false is simply off. Getting that wrong
                        // would have imported an account that never synced at all.
                        enableSync = match == null,
                        restoreSyncContacts = acct.syncContacts,
                        restoreSyncCalendar = acct.syncCalendar,
                    )
                    if (match != null) {
                        wipeLocalAccountData(context, syncDatabase, match.name)
                        removeAccount(context, sessionManager, keyManager, match.name)
                    }
                }
                provisioned.add(acct.displayName)
                if (collides) overwritten++ else imported++
            } catch (e: Exception) {
                SyncLog.e(TAG, "Failed to import account ${acct.displayName}", e)
                failed++
            }
        }

        var settingsRestored = false
        if (restoreSettings) {
            settingsRestored = writeSettings(context, payload.settings)
        }

        // Now that settings (intervals, modes…) are in place, kick an initial sync per account.
        for (name in provisioned) {
            val account = Account(name, ProtonSyncApp.ACCOUNT_TYPE)
            val abAccount = Account(name, ProtonSyncApp.ACCOUNT_TYPE_ADDRESS_BOOK)
            AccountProvisioner.requestImmediateSync(account, abAccount)
        }

        SyncLog.i(TAG, "Import: imported=$imported overwritten=$overwritten skipped=$skipped failed=$failed settings=$settingsRestored")
        return ImportResult(imported, skipped, overwritten, failed, settingsRestored)
    }

    /**
     * The subscription step after [apply]: a subscription id from the backup is taken when this version
     * can use it and the install has none (direct version only, see SubscriptionManager.restoreFromBackup),
     * and validated at once; otherwise the existing subscription is refreshed, since new accounts can
     * shift the device's gate. Returns whether the backup's id was taken.
     */
    fun restoreSubscription(
        subscriptionManager: me.proton.sync.billing.SubscriptionManager,
        payload: BackupPayload,
    ): Boolean {
        val taken = payload.subscriptionId?.let { subscriptionManager.restoreFromBackup(it) } == true
        if (!taken) subscriptionManager.connect()
        return taken
    }

    /**
     * Remove a parent account + its address-book child + in-memory session. Caller is
     * responsible for wiping the account's Room/provider data first ([wipeLocalAccountData]).
     */
    private fun removeAccount(
        context: Context,
        sessionManager: AccountSessionManager,
        keyManager: me.proton.sync.crypto.KeyManager,
        displayName: String
    ) {
        val am = AccountManager.get(context)
        am.getAccountsByType(ProtonSyncApp.ACCOUNT_TYPE).firstOrNull { it.name == displayName }?.let {
            @Suppress("DEPRECATION")
            am.removeAccountExplicitly(it)
        }
        // Address-book child mirrors the parent display name (v1.4.14+).
        am.getAccountsByType(ProtonSyncApp.ACCOUNT_TYPE_ADDRESS_BOOK).firstOrNull { it.name == displayName }?.let {
            @Suppress("DEPRECATION")
            am.removeAccountExplicitly(it)
        }
        sessionManager.removeSession(displayName)
        // Cached private keys are keyed by account name; drop them with the account so a
        // re-import cannot reuse the replaced account's key material.
        keyManager.clearKeyRing(displayName)
    }

    /**
     * Clear an account's local sync state so a re-import re-downloads cleanly instead of
     * inheriting stale mappings. Mirrors the canonical wipe in
     * `SyncSettingsActivity.performFullResyncRemoteToLocal`: Room mappings (all three
     * tables, keyed by display name), the provider RawContacts (address-book child) and
     * calendar Events, plus the cached per-calendar sync-range prefs.
     */
    private suspend fun wipeLocalAccountData(context: Context, db: SyncDatabase, displayName: String) {
        // Read what came from Proton BEFORE clearing the mappings, for the same reason the removal
        // and both forced resyncs do: a row with no mapping was created on this phone and exists
        // nowhere else, so wiping it is final.
        val protonContactIds = db.contactSyncDao().getAllByAccount(displayName)
            .mapNotNull { it.androidRawContactId }
        val protonEventIds = db.calendarEventSyncDao().androidEventIdsByAccount(displayName).toSet()

        db.contactSyncDao().deleteAllByAccount(displayName)
        db.calendarEventSyncDao().deleteAllByAccount(displayName)
        db.calendarSyncDao().deleteAllByAccount(displayName)
        // Held server-to-local deletions asked about rows that no longer exist. The kept sets survive,
        // because those are decisions the user made, not bookkeeping.
        me.proton.sync.RemoteDeletionGuard.forgetAllHolds(context, displayName)

        val cr = context.contentResolver
        val abAccount = Account(displayName, ProtonSyncApp.ACCOUNT_TYPE_ADDRESS_BOOK)
        val parentAccount = Account(displayName, ProtonSyncApp.ACCOUNT_TYPE)
        try {
            // DELETE AS A SYNC ADAPTER. A plain delete only tombstones the row, and a tombstone is
            // an instruction to the next sync: for calendar events it is catastrophic, because an
            // event carries its Proton id in the provider's own _sync_id column, which outlives the
            // Room mapping this function clears. Importing a backup therefore wiped the events,
            // left 26 tombstones that still knew their remote ids, and the very next sync deleted
            // every one of them from Proton. Verified on a device: "Found 26 dirty/deleted events
            // to process" followed by 26 "Deleted event ... from Proton" lines, after which the
            // server reported 0 events. Contacts escaped only by accident, because a contact
            // tombstone needs the Room mapping to resolve a remote id and that was already gone.
            // A hard delete leaves nothing behind to be pushed anywhere.
            for (chunk in protonContactIds.chunked(400)) {
                val placeholders = chunk.joinToString(",") { "?" }
                cr.delete(
                    me.proton.sync.SyncUtils.asCallerSyncAdapter(ContactsContract.RawContacts.CONTENT_URI, abAccount),
                    "${ContactsContract.RawContacts.ACCOUNT_NAME} = ? AND " +
                        "${ContactsContract.RawContacts.ACCOUNT_TYPE} = ? AND " +
                        "${ContactsContract.RawContacts._ID} IN ($placeholders)",
                    (listOf(displayName, ProtonSyncApp.ACCOUNT_TYPE_ADDRESS_BOOK) +
                        chunk.map { it.toString() }).toTypedArray()
                )
            }
            val calCursor = cr.query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(CalendarContract.Calendars._ID),
                "${CalendarContract.Calendars.ACCOUNT_NAME} = ? AND ${CalendarContract.Calendars.ACCOUNT_TYPE} = ?",
                arrayOf(displayName, ProtonSyncApp.ACCOUNT_TYPE),
                null
            )
            calCursor?.use { c ->
                while (c.moveToNext()) {
                    val calId = c.getLong(0)
                    // Keep the calendar entry, delete its events (matches performFullResyncRemoteToLocal).
                    val ids = mutableListOf<Long>()
                    cr.query(
                        CalendarContract.Events.CONTENT_URI,
                        arrayOf(CalendarContract.Events._ID),
                        "${CalendarContract.Events.CALENDAR_ID} = ?",
                        arrayOf(calId.toString()),
                        null
                    )?.use { ec ->
                        while (ec.moveToNext()) ec.getLong(0).let { if (it in protonEventIds) ids.add(it) }
                    }
                    for (chunk in ids.chunked(400)) {
                        val placeholders = chunk.joinToString(",") { "?" }
                        cr.delete(
                            me.proton.sync.SyncUtils.asCallerSyncAdapter(CalendarContract.Events.CONTENT_URI, parentAccount),
                            "${CalendarContract.Events._ID} IN ($placeholders)",
                            chunk.map { it.toString() }.toTypedArray()
                        )
                    }
                }
            }
        } catch (e: Exception) {
            SyncLog.w(TAG, "Provider wipe for '$displayName' partially failed: ${e.message}")
        }

        // Drop cached per-calendar sync ranges so the next sync does a full fetch.
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val rangePrefix = SyncPreferences.lastSyncRangeKey(displayName, "")
        prefs.edit().apply {
            prefs.all.keys.filter { it.startsWith(rangePrefix) }.forEach { remove(it) }
            apply()
        }
    }

    /**
     * Apply allowlisted settings in one transaction, each written as the type its readers ask for
     * (BackupSettings.writeImported). A value that cannot be read as its key's type (only an edited backup
     * has one) is never stored as another type: Android's getters cast, so a String where a Boolean belongs
     * made every getBoolean reader of that key throw. It is skipped, or written as the key's safe value where
     * the default would do harm (BackupSettings.UNREADABLE_AS), and logged by key name, never by value.
     */
    private fun writeSettings(context: Context, settings: Map<String, Any?>): Boolean {
        if (settings.isEmpty()) return false
        val editor = PreferenceManager.getDefaultSharedPreferences(context).edit()
        val result = BackupSettings.writeImported(editor, settings)
        editor.apply()
        for (key in result.skipped) {
            SyncLog.w(TAG, "Skipped restored setting $key: a ${kindOf(settings[key])} is not a valid ${BackupSettings.describe(key)}")
        }
        for (key in result.assumed) {
            SyncLog.w(
                TAG,
                "Restored setting $key as ${BackupSettings.unreadableAs(key)}, its safe value: " +
                    "a ${kindOf(settings[key])} is not a valid ${BackupSettings.describe(key)}"
            )
        }
        // The language is stored twice on purpose: our own key so the backup can carry it, and
        // AppCompat's framework state, which is what actually takes effect. Writing only the
        // preference would restore a language the app then ignored.
        (settings[SyncPreferences.KEY_APP_LANGUAGE_TAG] as? String)?.let { tag ->
            try {
                val locales = if (tag.isBlank()) {
                    androidx.core.os.LocaleListCompat.getEmptyLocaleList()
                } else {
                    androidx.core.os.LocaleListCompat.forLanguageTags(tag)
                }
                androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(locales)
            } catch (e: Exception) {
                SyncLog.w(TAG, "Could not apply the restored language '$tag': ${e.javaClass.simpleName}")
            }
        }
        // "Update time zones online" takes effect at the next sync, which reads the preference written
        // above (TimeZoneRuleSource.snapshot) and re-times the events already stored when the rule source
        // changed. Ask for that sync now, as the switch itself does. Never ical4j's updater: that stays off
        // for good (IcalTimeZoneUpdates). Backups from before 1.11.5 do not carry it and change nothing.
        (BackupSettings.importValue(SyncPreferences.KEY_TIMEZONE_UPDATES, settings[SyncPreferences.KEY_TIMEZONE_UPDATES]) as? Boolean)?.let { enabled ->
            SyncLog.i(TAG, "Restored online time zone updates: ${if (enabled) "on" else "off"}")
            me.proton.sync.calendar.TimeZoneRuleSource.requestRetimeSyncs(context, "a restored time zone setting")
        }
        return result.written > 0
    }

    /** The kind of a restored value, for the log: its class, or "null" (JSONObject.NULL's class has no name). */
    private fun kindOf(raw: Any?): String =
        if (raw == null || raw == org.json.JSONObject.NULL) "null" else raw.javaClass.simpleName
}

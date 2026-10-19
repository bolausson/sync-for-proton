package me.proton.sync.backup

import android.accounts.AccountManager
import android.content.Context
import android.net.Uri
import androidx.preference.PreferenceManager
import me.proton.sync.BuildConfig
import me.proton.sync.SyncLog
import me.proton.sync.SyncUtils
import me.proton.sync.account.AccountSessionManager
import org.json.JSONObject

/**
 * Builds a `.sppbackup` file: gather settings (+ optionally accounts) → serialize
 * → optionally passphrase-encrypt → write to a SAF-chosen URI.
 *
 * Never logs the payload (it can carry credentials in full mode) — only counts
 * and outcomes. SAF means no storage permission is required.
 */
object ExportManager {

    private const val TAG = "ExportManager"

    sealed class Result {
        data class Success(val accountCount: Int, val encrypted: Boolean) : Result()
        data class Failure(val reason: String) : Result()
    }

    /**
     * @param includeAccounts when true, embed all accounts (requires [passphrase]) so the
     *        destination device can restore them without re-login; the whole payload is encrypted.
     * @param passphrase required (min 8) when [includeAccounts]; ignored otherwise.
     * @param createdAtIso ISO-8601 timestamp stamped into the envelope (caller supplies it;
     *        the activity passes the current time).
     * @param subscriptionId the direct version's subscription id, or null. Embedded only when
     *        [includeAccounts], so it never lands in a cleartext file.
     */
    fun export(
        context: Context,
        uri: Uri,
        sessionManager: AccountSessionManager,
        includeAccounts: Boolean,
        passphrase: String?,
        createdAtIso: String,
        subscriptionId: String? = null,
    ): Result {
        return try {
            if (includeAccounts && (passphrase == null || passphrase.length < BackupCrypto.MIN_PASSPHRASE_LENGTH)) {
                return Result.Failure("passphrase_too_short")
            }

            val settings = gatherSettings(context)
            val accounts = if (includeAccounts) gatherAccounts(context, sessionManager) else emptyList()
            val payload = BackupPayload(
                settings, accounts,
                subscriptionId = subscriptionId?.trim()?.takeIf { includeAccounts && it.isNotEmpty() }
            )
            val payloadJson = payload.toJson()

            val envelope = JSONObject().apply {
                put("format", BackupFormat.FORMAT)
                put("version", BackupFormat.VERSION)
                put("created_at", createdAtIso)
                put("source_flavor", BuildConfig.FLAVOR)
                put("source_version_code", BuildConfig.VERSION_CODE)
                put("encrypted", includeAccounts)
            }

            if (includeAccounts) {
                val enc = BackupCrypto.encrypt(payloadJson.toString().toByteArray(Charsets.UTF_8), passphrase!!)
                envelope.put("kdf", JSONObject().apply {
                    put("algo", BackupCrypto.KDF_ALGO)
                    put("iterations", BackupCrypto.ITERATIONS)
                    put("salt", BackupCrypto.b64(enc.salt))
                })
                envelope.put("cipher", BackupCrypto.CIPHER)
                envelope.put("iv", BackupCrypto.b64(enc.iv))
                envelope.put("payload", BackupCrypto.b64(enc.ciphertext))
            } else {
                // Cleartext settings-only backup: payload is a nested JSON object.
                envelope.put("payload", payloadJson)
            }

            context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                out.write(envelope.toString(2).toByteArray(Charsets.UTF_8))
                out.flush()
            } ?: return Result.Failure("write_failed")

            SyncLog.i(
                TAG,
                "Exported backup: ${accounts.size} account(s), encrypted=$includeAccounts, " +
                    "subscription id included=${payload.subscriptionId != null}"
            )
            Result.Success(accounts.size, includeAccounts)
        } catch (e: Exception) {
            SyncLog.e(TAG, "Export failed", e)
            Result.Failure(e.javaClass.simpleName)
        }
    }

    /** Collect the allowlisted subset of default SharedPreferences, preserving value types. */
    private fun gatherSettings(context: Context): Map<String, Any?> {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        return prefs.all.filterKeys { BackupSettings.isExportable(it) }
    }

    /** Decrypt-on-read every account into a self-contained [BackupAccount]. */
    private fun gatherAccounts(context: Context, sessionManager: AccountSessionManager): List<BackupAccount> {
        val am = AccountManager.get(context)
        return sessionManager.getAllAccounts().mapNotNull { account ->
            val session = sessionManager.getSession(account.name) ?: return@mapNotNull null
            val protonUsername = am.getUserData(account, "proton_username") ?: account.name
            BackupAccount(
                displayName = account.name,
                protonUsername = protonUsername,
                uid = session.uid,
                refreshToken = session.refreshToken,
                keySalt = session.keySalt,
                encPrivateKey = session.encPrivateKey,
                mailboxPassword = session.mailboxPassword,
                passwordMode = session.passwordMode,
                addressBookName = SyncUtils.addressBookAccountName(am, account),
                // The switches themselves, which live in the framework rather than in
                // SharedPreferences and so were the one part of an account's settings a
                // "settings backup" never captured.
                syncContacts = android.content.ContentResolver.getSyncAutomatically(
                    android.accounts.Account(
                        SyncUtils.addressBookAccountName(am, account),
                        me.proton.sync.ProtonSyncApp.ACCOUNT_TYPE_ADDRESS_BOOK
                    ),
                    me.proton.sync.SyncConstants.AUTHORITY_CONTACTS
                ),
                syncCalendar = android.content.ContentResolver.getSyncAutomatically(
                    account, me.proton.sync.SyncConstants.AUTHORITY_CALENDAR
                ),
            )
        }
    }
}

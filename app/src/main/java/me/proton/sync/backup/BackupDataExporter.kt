package me.proton.sync.backup

import android.accounts.AccountManager
import android.content.Context
import androidx.preference.PreferenceManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.proton.sync.ProtonSyncApp
import me.proton.sync.SyncLog
import me.proton.sync.SyncPreferences
import me.proton.sync.SyncUtils
import me.proton.sync.account.AccountSessionManager
import me.proton.sync.api.ProtonApiClient
import me.proton.sync.crypto.CalendarDecryptor
import me.proton.sync.crypto.ContactDecryptor
import me.proton.sync.crypto.KeyManager
import me.proton.sync.crypto.SecureStorage

/**
 * Orchestrates a full backup run: for every Proton account, pull authoritative
 * calendars + contacts from the server ([CalendarBackupBuilder] / [ContactsBackupBuilder]),
 * apply the emptiness guard, then write to the user's SAF folder — either as loose
 * `.ics`/`.vcf` files in a timestamped run folder, or, when encryption is on, as a
 * single gpg-compatible `proton-backup-<ts>.gpg` archive.
 *
 * Every run is its own timestamped unit, so a new (even partial) run never overwrites
 * a previous good one; retention pruning keeps the most recent N. Reads from Proton —
 * never the phone's providers — so a glitched local copy can't poison the backup.
 *
 * Runs regardless of subscription state: it's the user's data, never withheld.
 */
class BackupDataExporter(
    private val context: Context,
    private val apiClient: ProtonApiClient,
    private val calendarDecryptor: CalendarDecryptor,
    private val contactDecryptor: ContactDecryptor,
    private val sessionManager: AccountSessionManager,
    private val keyManager: KeyManager,
) {
    sealed class Result {
        data class Success(
            val accounts: Int, val calendars: Int, val events: Int, val contacts: Int,
            val encrypted: Boolean, val incomplete: Boolean
        ) : Result()
        /** Source came back empty while a prior run had data — kept the good backup, wrote nothing. */
        object SkippedEmptyGuard : Result()
        object NoFolder : Result()
        data class Failure(val reason: String) : Result()
    }

    /** A run either produced a [Result] or threw. Both have to be reported and recorded. */
    sealed class Outcome {
        data class Ran(val result: Result) : Outcome()
        data class Threw(val error: Throwable) : Outcome()
    }

    /** @param runTimestampLabel sortable label like `2026-06-03T031205` (caller supplies the clock). */
    suspend fun runBackup(runTimestampLabel: String): Result =
        // Serialize the whole run so a manual "Back up now" and a scheduled run can't race on
        // the same folder / prefs. The Mutex is a companion-object singleton, shared across the
        // per-call instances that BackupDataExporter.from() creates.
        runMutex.withLock { runBackupLocked(runTimestampLabel) }

    private suspend fun runBackupLocked(runTimestampLabel: String): Result {
        if (!SafBackupStorage.hasValidPermission(context)) return Result.NoFolder

        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val includeCalendars = prefs.getBoolean(SyncPreferences.KEY_BACKUP_INCLUDE_CALENDARS, true)
        val includeContacts = prefs.getBoolean(SyncPreferences.KEY_BACKUP_INCLUDE_CONTACTS, true)
        val encrypt = prefs.getBoolean(SyncPreferences.KEY_BACKUP_ENCRYPTION_ENABLED, false)

        val accounts = AccountManager.get(context).getAccountsByType(ProtonSyncApp.ACCOUNT_TYPE)
        if (accounts.isEmpty()) return Result.Failure("no_accounts")

        val calBuilder = CalendarBackupBuilder(apiClient, calendarDecryptor)
        val conBuilder = ContactsBackupBuilder(apiClient, contactDecryptor)

        // Relative path within the run ("<account>/<file>") → bytes.
        val files = LinkedHashMap<String, ByteArray>()
        var totalEvents = 0
        var totalContacts = 0
        var totalCalendars = 0

        var incomplete = false
        for (account in accounts) {
            // Unlock this account's keys exactly like the sync preflight does. Without it,
            // address-key-encrypted data (most contact cards) fails to decrypt and is silently
            // dropped — while user-key data (calendar) still succeeds, masking the loss.
            try {
                val session = sessionManager.getSession(account)
                SyncUtils.ensureKeysUnlocked(account, session, keyManager, apiClient, TAG)
            } catch (e: Exception) {
                SyncLog.w(TAG, "Backup INCOMPLETE: keys not unlocked for ${account.name} (${e.javaClass.simpleName}); skipping account")
                incomplete = true
                continue
            }

            val acctDir = safeFileName(account.name)
            // Each builder is wrapped on its own. A builder that throws used to abort the entire
            // run before a single byte reached disk, so a contact the vCard writer rejected cost
            // the user their CALENDARS as well, and every other account with them. A backup is the
            // one thing that has to degrade rather than fail: whatever can be written, is, and the
            // run is flagged incomplete so nobody mistakes a partial for a whole one.
            if (includeCalendars) {
                val calResult = try {
                    calBuilder.buildAll(account.name)
                } catch (e: Exception) {
                    SyncLog.e(TAG, "Backup INCOMPLETE: calendars failed for ${account.name}", e)
                    incomplete = true
                    null
                }
                if (calResult != null) {
                    if (calResult.fetchFailed || calResult.skippedCalendars > 0 || calResult.failedEvents > 0) {
                        incomplete = true
                    }
                    val usedNames = HashSet<String>()
                    for (c in calResult.calendars) {
                        var fn = c.fileBaseName
                        var i = 2
                        while (!usedNames.add(fn)) { fn = "${c.fileBaseName}-$i"; i++ }
                        files["$acctDir/$fn.ics"] = c.ics.toByteArray(Charsets.UTF_8)
                        totalEvents += c.eventCount
                        totalCalendars++
                    }
                }
            }
            if (includeContacts) {
                val vcf = try {
                    conBuilder.buildVcf(account.name)
                } catch (e: Exception) {
                    SyncLog.e(TAG, "Backup INCOMPLETE: contacts failed for ${account.name}", e)
                    incomplete = true
                    null
                }
                if (vcf != null) {
                    if (vcf.fetchFailed || vcf.count < vcf.attempted) incomplete = true
                    if (vcf.count > 0) {
                        files["$acctDir/contacts.vcf"] = vcf.vcf.toByteArray(Charsets.UTF_8)
                        totalContacts += vcf.count
                    }
                }
            }
        }

        // Emptiness guard: never let a transient all-empty fetch stand in for a real backup.
        val totalItems = totalEvents + totalContacts
        val lastTotal = prefs.getInt(SyncPreferences.KEY_BACKUP_LAST_TOTAL, 0)
        if (totalItems == 0 && lastTotal > 0) {
            SyncLog.w(TAG, "Emptiness guard: fetched 0 items but a prior run had $lastTotal - keeping last good backup")
            return Result.SkippedEmptyGuard
        }
        if (files.isEmpty()) return Result.Failure("nothing_to_back_up")

        val runName = "${SafBackupStorage.RUN_PREFIX}$runTimestampLabel"
        val written = if (encrypt) {
            val hasStoredPassphrase = prefs.getString(SyncPreferences.KEY_BACKUP_PASSPHRASE_ENC, null) != null
            val passphrase = loadPassphrase(prefs) ?: return Result.Failure(
                // Two different problems needing two different actions: set a password, versus set
                // it again because this device can no longer decrypt the one it stored.
                if (hasStoredPassphrase) "passphrase_unreadable" else "no_passphrase"
            )
            val archive = try {
                BackupArchiveCrypto.encrypt(files, passphrase)
            } finally {
                passphrase.fill('\u0000')
            }
            SafBackupStorage.writeRootFile(
                context, "$runName.${BackupArchiveCrypto.EXTENSION}", SafBackupStorage.GPG_MIME, archive
            )
        } else {
            SafBackupStorage.writeRun(context, runName, files)
        }
        if (!written) return Result.Failure("write_failed")

        // Only advance the emptiness-guard baseline on a COMPLETE run, so a transient partial
        // doesn't quietly lower the bar the guard later compares against.
        if (!incomplete) prefs.edit().putInt(SyncPreferences.KEY_BACKUP_LAST_TOTAL, totalItems).apply()
        val keep = (prefs.getString(SyncPreferences.KEY_BACKUP_RETENTION, SyncPreferences.DEFAULT_BACKUP_RETENTION)
            ?.toIntOrNull() ?: 14).coerceAtLeast(1)
        if (incomplete) {
            // Retention counts runs, not good runs, so a partial run prunes with one extra slot
            // (keep + 1) and deletes one run fewer than a complete run would. Refusing to prune AT
            // ALL was an over-correction: one permanently undecryptable event would then stop
            // retention for ever and the folder would grow without bound. The slack is one run
            // only. When a partial cause persists (a calendar whose keys will not fetch, one account
            // of two that cannot unlock), each partial run from the second on still evicts the
            // oldest run, complete or not, so the complete backups do rotate out in the end. The
            // user is told each run was incomplete.
            SafBackupStorage.pruneOldRuns(context, keep + 1)
            SyncLog.w(TAG, "Run is incomplete - pruned with one extra slot (keeping $keep + 1); repeated partial runs still evict the oldest run, complete or not")
        } else {
            SafBackupStorage.pruneOldRuns(context, keep)
        }

        return Result.Success(accounts.size, totalCalendars, totalEvents, totalContacts, encrypt, incomplete)
    }

    /**
     * The stored backup passphrase, or null if there is none or it can no longer be read.
     *
     * [SecureStorage.decrypt] fails OPEN: when Keystore decryption fails, which happens after a
     * device repair, a Secure Folder move or a Keystore reset, it falls back to Base64-decoding the
     * raw ciphertext. Those bytes are not valid UTF-8, so it hands back a string of U+FFFD
     * replacement characters, and a string of U+FFFD is not blank. The archive was then encrypted
     * under that garbage and written out perfectly successfully, leaving the user with a .gpg that
     * cannot be opened with the password they know and no indication anything had gone wrong. A
     * backup that silently becomes unreadable is worse than one that fails loudly.
     *
     * [SecureStorage.decryptStrict] has no such fallback, and it is safe here because encrypt()
     * always writes one of the two prefixes, so the unprefixed legacy branch cannot be reached by
     * this key. Its Base64 heuristic would in any case have corrupted a legacy plaintext passphrase
     * that happened to look like Base64.
     */
    private fun loadPassphrase(prefs: android.content.SharedPreferences): CharArray? {
        val stored = prefs.getString(SyncPreferences.KEY_BACKUP_PASSPHRASE_ENC, null) ?: return null
        val plain = SecureStorage.decryptStrict(stored)
        return if (plain.isBlank()) null else plain.toCharArray()
    }

    private fun safeFileName(s: String): String =
        s.replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1F]"), "_").trim().ifBlank { "account" }

    companion object {
        private const val TAG = "BackupExporter"

        /**
         * The one-line outcome the backup screen shows under "Back up now", and the only writer of
         * the two preferences behind it.
         *
         * It lives here because there are two ways to run a backup and only one of them used to
         * record anything. The scheduled worker recorded every outcome; the manual button recorded
         * none, while still calling refreshUi() afterwards as though it had. So the label could
         * only ever describe the last SCHEDULED run: a user who pressed the button and watched it
         * succeed was told "No backup yet", and a user whose scheduled run had failed days earlier
         * kept being shown that failure no matter how many manual backups succeeded since. A stale
         * failure could therefore look like the result of a backup the user had just run.
         *
         * Both callers now go through here, so the two paths cannot drift again.
         */
        fun summaryFor(context: Context, result: Result): String = when (result) {
            is Result.Success ->
                if (result.incomplete) context.getString(
                    me.proton.sync.R.string.backup_result_incomplete,
                    result.calendars, result.events, result.contacts
                ) else context.getString(
                    me.proton.sync.R.string.backup_result_success,
                    result.calendars, result.events, result.contacts
                )
            Result.SkippedEmptyGuard -> context.getString(me.proton.sync.R.string.backup_result_skipped)
            Result.NoFolder -> context.getString(me.proton.sync.R.string.backup_result_folder_lost)
            is Result.Failure -> context.getString(me.proton.sync.R.string.backup_result_failed, result.reason)
        }

        /** Stamp an outcome, so the screen describes the run that actually happened last. */
        fun record(context: Context, summary: String) {
            PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putLong(SyncPreferences.KEY_BACKUP_LAST_RUN_MS, System.currentTimeMillis())
                .putString(SyncPreferences.KEY_BACKUP_LAST_RESULT, summary)
                .apply()
        }

        /**
         * What a thrown exception should be called on screen.
         *
         * This used to be the class name alone, and a real report arrived reading only
         * "Backup failed: IllegalArgumentException" — a sentence that narrows the cause to several
         * hundred lines of code and cannot be acted on by anyone, user or developer. The message is
         * the part that says WHICH argument, so it is kept. Trimmed because some platform messages
         * run to several lines, which would wrap the label off the screen.
         */
        fun describe(e: Throwable): String {
            val msg = e.message?.lines()?.joinToString(" ") { it.trim() }?.trim()?.take(160)
            return if (msg.isNullOrBlank()) e.javaClass.simpleName else "${e.javaClass.simpleName}: $msg"
        }

        /**
         * Scope for runs started from a screen, deliberately NOT the screen's own.
         *
         * The manual button used to run inside lifecycleScope, which is cancelled at ON_DESTROY.
         * Backing out while a backup was in flight therefore killed it partway, and the recording
         * went with it, so the screen kept describing some older run. A backup is not the user's
         * to lose by navigating; the run finishes and is written down either way, and the activity
         * only awaits it to update its own dialog.
         */
        private val runScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /**
         * Runs a backup, records its outcome whatever happens, and hands back a handle the caller
         * may await purely to update its UI. Abandoning the handle does not abandon the run.
         */
        fun startRecorded(context: Context, label: String): Deferred<Outcome> {
            val app = context.applicationContext
            return runScope.async {
                val outcome = try {
                    Outcome.Ran(from(app).runBackup(label))
                } catch (c: kotlinx.coroutines.CancellationException) {
                    throw c
                } catch (e: Throwable) {
                    // Throwable, not Exception: the encrypted path holds several full copies of the
                    // archive at once, so a large account on a small phone can OutOfMemoryError, and
                    // an Error caught nowhere leaves the screen showing a stale result forever.
                    SyncLog.e(TAG, "Backup failed", e)
                    Outcome.Threw(e)
                }
                record(app, summaryOf(app, outcome))
                outcome
            }
        }

        /** The recorded line for an outcome, thrown or not. */
        fun summaryOf(context: Context, outcome: Outcome): String = when (outcome) {
            is Outcome.Ran -> summaryFor(context, outcome.result)
            is Outcome.Threw -> context.getString(
                me.proton.sync.R.string.backup_result_failed, describe(outcome.error)
            )
        }

        /** Process-wide lock serializing backup runs (manual vs scheduled). */
        private val runMutex = Mutex()

        /** Build an exporter by pulling the shared singletons from the Hilt application graph. */
        fun from(context: Context): BackupDataExporter {
            val ep = EntryPointAccessors.fromApplication(
                context.applicationContext, BackupDependenciesEntryPoint::class.java
            )
            return BackupDataExporter(
                context.applicationContext, ep.apiClient(), ep.calendarDecryptor(), ep.contactDecryptor(),
                ep.sessionManager(), ep.keyManager()
            )
        }
    }
}

/** Hilt bridge so a plain Worker / Activity can reach the sync singletons without field injection. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface BackupDependenciesEntryPoint {
    fun apiClient(): ProtonApiClient
    fun calendarDecryptor(): CalendarDecryptor
    fun contactDecryptor(): ContactDecryptor
    fun sessionManager(): AccountSessionManager
    fun keyManager(): KeyManager
}

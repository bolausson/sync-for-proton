package me.proton.sync

import android.accounts.Account
import android.accounts.AccountManager
import android.content.ContentProviderClient
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.CalendarContract
import android.provider.ContactsContract
import androidx.preference.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import android.content.SyncResult
import me.proton.sync.account.AccountSession
import me.proton.sync.account.AccountSessionManager
import me.proton.sync.api.ProtonApiClient
import me.proton.sync.api.TokenRefresher
import me.proton.sync.api.protonErrorSuffix
import me.proton.sync.billing.SubscriptionGate
import me.proton.sync.crypto.KeyManager
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.locks.ReentrantLock

/**
 * Shared utilities for sync adapters.
 *
 * Consolidates common patterns used across CalendarSyncAdapter, CalendarMapper,
 * ContactsSyncAdapter, and ContactsMapper to avoid DRY violations:
 *
 * - [asCallerSyncAdapter] — ContentProvider URI wrapping (4 duplicates → 1)
 * - [isAuthFailure] — exception classification for re-login detection
 * - [ensureKeysUnlocked] — PGP key re-unlock after process restart
 * - [runUninterruptible] — uninterruptible coroutine execution for sync adapters
 * - [withSyncLock] — per-account concurrent sync prevention
 */
object SyncUtils {

    /**
     * Wraps a ContentProvider URI with sync adapter parameters.
     *
     * Android's ContentProvider requires CALLER_IS_SYNCADAPTER=true plus the account
     * name/type when a sync adapter modifies data. Without this, the provider either
     * rejects the operation or marks the row as DIRTY (triggering an upload loop).
     *
     * This function replaces four duplicate private implementations:
     * - CalendarSyncAdapter.asCallerSyncAdapter()
     * - CalendarMapper.asCallerSyncAdapter()
     * - ContactsSyncAdapter.syncAdapterUri()
     * - ContactsMapper.addCallerIsSyncAdapter()
     *
     * @param uri The base ContentProvider URI (e.g., CalendarContract.Events.CONTENT_URI)
     * @param account The sync account (provides name and type for the query parameters)
     * @return URI with caller_is_syncadapter, account_name, and account_type appended
     */
    fun asCallerSyncAdapter(uri: Uri, account: Account): Uri {
        return uri.buildUpon()
            .appendQueryParameter("caller_is_syncadapter", "true")
            .appendQueryParameter("account_name", account.name)
            .appendQueryParameter("account_type", account.type)
            .build()
    }

    /**
     * Clear the DIRTY=1 flag on a contacts RawContacts row after a successful upload.
     *
     * Best-effort: any provider exception is logged and swallowed. Failure here is rare
     * (the upload itself just succeeded against the same provider) and not worth
     * propagating up the call stack — the next sync will retry the upload, which is
     * idempotent at the Proton API level (matched on UID).
     */
    fun clearContactDirtyFlag(
        provider: ContentProviderClient,
        account: Account,
        rawContactId: Long,
        tag: String,
        /** Leave a row the user deleted in the meantime alone, so its deletion is still uploaded. */
        onlyIfNotDeleted: Boolean = false
    ) {
        val values = ContentValues().apply { put(ContactsContract.RawContacts.DIRTY, 0) }
        try {
            provider.update(
                asCallerSyncAdapter(
                    ContentUris.withAppendedId(ContactsContract.RawContacts.CONTENT_URI, rawContactId),
                    account
                ),
                values,
                if (onlyIfNotDeleted) "${ContactsContract.RawContacts.DELETED} = 0" else null,
                null
            )
        } catch (e: Exception) {
            SyncLog.w(tag, "Could not clear dirty flag for raw contact $rawContactId", e)
        }
    }

    /**
     * Clear the DIRTY=1 flag on a calendar Events row after a successful upload.
     *
     * Best-effort: see [clearContactDirtyFlag] for rationale.
     */
    fun clearCalendarEventDirtyFlag(provider: ContentProviderClient, account: Account, androidEventId: Long, tag: String) {
        val values = ContentValues().apply { put(CalendarContract.Events.DIRTY, 0) }
        try {
            provider.update(
                ContentUris.withAppendedId(
                    asCallerSyncAdapter(CalendarContract.Events.CONTENT_URI, account),
                    androidEventId
                ),
                values, null, null
            )
        } catch (e: Exception) {
            SyncLog.w(tag, "Could not clear dirty flag for local event $androidEventId", e)
        }
    }

    /**
     * The address-book Account.name now always mirrors the parent account's display
     * name. Earlier versions (v1.3.79+) derived a separate email-shaped name from the
     * stored `proton_username` (e.g. parent="Alice" → child="alice@example.com"),
     * on the hypothesis that Samsung's "save new contact" picker subtitle requires
     * email-shaped names. We now know that subtitle is gated by Samsung's per-type
     * allowlist, which our type isn't
     * on — so the email-shape derivation gave us no benefit while making one logical
     * Proton account appear under two different names in Android Settings → Manage
     * accounts (one per provider).
     *
     * v1.4.14 collapses both to `parent.name`. The existing `migrateAddressBookAccount-
     * Names` runs on every app start and detects the new desired name (==parent.name);
     * mismatched children are renamed in-place and contact rows re-pointed to the new
     * account_name automatically.
     *
     * @param am unused now — kept in the signature so the caller pattern doesn't change.
     */
    @Suppress("UNUSED_PARAMETER")
    fun addressBookAccountName(am: AccountManager, parentAccount: Account): String {
        return parentAccount.name
    }

    /**
     * Checks whether an exception indicates an authentication failure (token expired/invalid).
     *
     * Used by both sync adapters to decide whether to bump numAuthExceptions
     * (which triggers Android's re-login flow) vs numIoExceptions.
     *
     * Also returns true for [KeysNotUnlockedException]: if we cannot decrypt the
     * user's data with the cached key material, the user must re-authenticate
     * (typically because the mailbox password has changed or the account moved
     * to two-password mode).
     */
    fun isAuthFailure(e: Exception): Boolean {
        if (e is KeysNotUnlockedException) return true
        val msg = e.message ?: ""
        return msg.contains("401") || msg.contains("403") ||
            msg.contains("Unauthorized") || msg.contains("Invalid access token") ||
            msg.contains("Expired access token")
    }

    /**
     * Thrown by [ensureKeysUnlocked] when stored credentials cannot unlock the user's
     * key ring after multiple attempts. Distinct from token-expiry exceptions because
     * the remediation differs: re-entering the same login password will not help if
     * the account moved to two-password mode or the mailbox password was rotated.
     */
    class KeysNotUnlockedException(val accountName: String, message: String) : Exception(message)

    /**
     * Per-account lock that serialises [ensureKeysUnlocked]. When ContactsSync and
     * CalendarSync run in parallel for the same account (a typical "Sync all" tap),
     * both used to call [ensureKeysUnlocked] simultaneously, the cache-miss check
     * was non-atomic, and both threads ended up doing the full unlock work
     * end-to-end. Observed in v1.3.54 logs as 38 KeyManager unlock lines for
     * an account with 19 subkeys (each one logged twice).
     *
     * The lock is held only across the cache check + unlock work; once one thread
     * populates KeyManager's cache, the other waits, sees the cache hit on
     * `isKeyUnlocked(...)`, and returns immediately.
     */
    private val unlockLocks = ConcurrentHashMap<String, ReentrantLock>()

    /**
     * Per-account lock serialising the proactive refresh-token → access-token exchange in
     * [runSyncPreflight]. Contacts and Calendar sync fire concurrently for the same account
     * on the first sync after a backup import; without this both would hit Proton's
     * token-rotating refresh endpoint at once and the loser would fail on the invalidated
     * token. Held only across the re-read + refresh; the winner's tokens are then visible to
     * the loser via the in-memory session.
     */
    private val refreshLocks = ConcurrentHashMap<String, ReentrantLock>()

    /**
     * How long an unlocked key ring is trusted before the keys are fetched again.
     *
     * Proton adds keys to a live account: opting into post-quantum crypto generates a v6 user key
     * AND v6 address keys, and adding an address generates more. Keys are additive, so nothing
     * already decryptable breaks, but anything encrypted to a NEW key is unreadable to a ring that
     * predates it. `setupCalendarKeys` wraps the calendar passphrase to the primary ADDRESS key,
     * so a new primary is enough to fail calendar unlock outright.
     *
     * Without a bound the ring was trusted for the whole process lifetime and only a process death
     * could pick new keys up.
     *
     * The refetch costs one `getAddresses` call; re-adding keys already in the ring is deduped
     * inside KeyManager, so a refresh that finds nothing new is close to free.
     */
    private const val KEY_CACHE_TTL_MS = 30 * 60 * 1000L
    private val keysLoadedAt = ConcurrentHashMap<String, Long>()

    /**
     * Force the next [ensureKeysUnlocked] for this account to refetch instead of trusting the
     * cached ring. Call this when something fails in a way a missing key would explain, so the
     * retry self-heals rather than waiting out [KEY_CACHE_TTL_MS].
     */
    fun invalidateKeyCache(accountName: String) {
        keysLoadedAt.remove(accountName)
    }

    private fun keyCacheIsFresh(accountName: String, keyManager: KeyManager): Boolean {
        if (!keyManager.isKeyUnlocked(accountName) || !keyManager.hasAddressKeys(accountName)) return false
        val loadedAt = keysLoadedAt[accountName] ?: return false
        return System.currentTimeMillis() - loadedAt in 0 until KEY_CACHE_TTL_MS
    }

    /**
     * Re-unlocks PGP user and address keys if the process has restarted, or if the cached ring is
     * older than [KEY_CACHE_TTL_MS] or was dropped by [invalidateKeyCache].
     *
     * Both sync adapters call this before performing sync operations because
     * Android may kill and restart the sync adapter process, losing in-memory
     * key material. Retries up to 3 times with 500ms delay between attempts.
     *
     * @param account The account whose keys to unlock
     * @param session The session containing mailboxPassword and encPrivateKey
     * @param keyManager The key manager to check/unlock keys
     * @param apiClient The API client to fetch address keys
     * @param tag Log tag for the calling adapter
     */
    fun ensureKeysUnlocked(
        account: Account,
        session: AccountSession,
        keyManager: KeyManager,
        apiClient: ProtonApiClient,
        tag: String
    ) {
        // Fast path: ring populated AND not yet stale, no lock needed.
        if (keyCacheIsFresh(account.name, keyManager)) return

        val lock = unlockLocks.getOrPut(account.name) { ReentrantLock() }
        lock.lock()
        try {
            // Re-check inside the lock — another thread may have unlocked while we
            // were waiting. This converts the second concurrent caller into a no-op
            // instead of duplicating ~20 PGP key extractions.
            if (keyCacheIsFresh(account.name, keyManager)) return

            val mbPassword = session.mailboxPassword
                ?: throw KeysNotUnlockedException(account.name, "No cached mailbox password for ${account.name}")
            val encPrivateKey = session.encPrivateKey
                ?: throw KeysNotUnlockedException(account.name, "No cached encrypted private key for ${account.name}")
            doEnsureKeysUnlocked(account, mbPassword, encPrivateKey, keyManager, apiClient, tag)
        } finally {
            lock.unlock()
        }
    }

    private fun doEnsureKeysUnlocked(
        account: Account,
        mbPassword: String,
        encPrivateKey: String,
        keyManager: KeyManager,
        apiClient: ProtonApiClient,
        tag: String
    ) {
        var lastError: String? = null
        for (attempt in 1..3) {
            try {
                keyManager.unlockUserKeyWithMailboxPassword(account.name, mbPassword.toCharArray(), encPrivateKey)
                SyncLog.i(tag, "Re-unlocked keys for ${account.name} from stored credentials")
                var addressKeysRefreshed = false
                runBlocking {
                    try {
                        val addressesResponse = apiClient.getAddresses(account.name)
                        if (addressesResponse.isSuccessful) {
                            val allAddressKeys = addressesResponse.body()?.addresses?.flatMap { addr ->
                                (addr.keys ?: emptyList()).onEach { it.addressEmail = addr.email }
                            } ?: emptyList()
                            if (allAddressKeys.isNotEmpty()) {
                                keyManager.unlockAddressKeysWithMailboxPassword(account.name, mbPassword.toCharArray(), allAddressKeys)
                                addressKeysRefreshed = true
                            }
                        } else {
                            // Silent until 2.0.2.
                            SyncLog.w(tag, "Address keys not refreshed: HTTP ${addressesResponse.code()}${addressesResponse.protonErrorSuffix()}")
                        }
                    } catch (e: Exception) {
                        SyncLog.w(tag, "Failed to re-unlock address keys: ${e.message}")
                    }
                }
                // Verify what we promised — guards against silent partial unlocks.
                if (!keyManager.isKeyUnlocked(account.name)) {
                    throw IllegalStateException("KeyManager reports ${account.name} still locked after unlock call")
                }
                // Only start the freshness clock when the keys really were re-read from the API.
                // The address fetch above swallows its own failures, and stamping after one would
                // pin a ring that is already known to be stale for a full KEY_CACHE_TTL_MS.
                if (addressKeysRefreshed) keysLoadedAt[account.name] = System.currentTimeMillis()
                return // Success
            } catch (e: Exception) {
                lastError = e.message
                SyncLog.w(tag, "Key unlock attempt $attempt/3 failed for ${account.name}: $lastError")
                if (attempt < 3) Thread.sleep(500) // Brief delay before retry — runs on sync adapter thread, not UI
            }
        }
        throw KeysNotUnlockedException(
            account.name,
            "Could not unlock keys for ${account.name} after 3 attempts: $lastError"
        )
    }

    /**
     * Runs a suspend [block] on [Dispatchers.IO] and waits uninterruptibly for completion.
     *
     * Android's sync framework interrupts the sync adapter thread to signal cancellation,
     * but we must finish in-progress API calls to avoid data corruption. This wrapper
     * launches the work on a separate coroutine scope and blocks the calling thread
     * with a [CountDownLatch] that ignores [InterruptedException].
     *
     * @return The exception thrown by [block], or null if it completed successfully.
     */
    fun runUninterruptible(block: suspend () -> Unit): Exception? {
        val latch = CountDownLatch(1)
        var error: Exception? = null
        CoroutineScope(Dispatchers.IO).launch {
            try {
                block()
            } catch (e: Exception) {
                error = e
            } finally {
                latch.countDown()
            }
        }
        while (true) {
            try { latch.await(); break } catch (_: InterruptedException) { /* keep waiting */ }
        }
        return error
    }

    // ---- Per-account sync locking ----

    /**
     * Shared lock map for preventing concurrent syncs of the same type+account.
     *
     * Keyed by `"$syncType:$accountName"` so that calendar and contacts locks
     * remain independent (they can sync concurrently for the same account).
     */
    private val syncLocks = ConcurrentHashMap<String, ReentrantLock>()

    // ---- Re-auth backoff ----
    //
    // After an auth/unlock failure the adapter writes `now + SYNC_PAUSE_AFTER_AUTH_FAIL_MS`
    // to a per-account pref. Subsequent scheduled syncs early-return until the timestamp
    // passes — which avoids waking the device every minute to retry a doomed call. The
    // pause is cleared on (re-)login completion (so the user's action immediately resumes
    // sync) and on manual sync requests (the user explicitly asked, respect that).

    /**
     * Returns true if this account's sync is currently paused due to a recent auth failure
     * AND the request is not user-initiated. Manual sync requests bypass the backoff.
     */
    fun isSyncPaused(context: Context, accountName: String, isManual: Boolean): Boolean {
        if (isManual) return false
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val until = prefs.getLong(SyncPreferences.syncPausedUntilKey(accountName), 0L)
        return until > System.currentTimeMillis()
    }

    /** Pause scheduled syncs for [accountName] for [SyncPreferences.SYNC_PAUSE_AFTER_AUTH_FAIL_MS]. */
    fun pauseSyncAfterAuthFailure(context: Context, accountName: String) {
        val until = System.currentTimeMillis() + SyncPreferences.SYNC_PAUSE_AFTER_AUTH_FAIL_MS
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .putLong(SyncPreferences.syncPausedUntilKey(accountName), until)
            .apply()
    }

    /** Clear any active sync-pause for [accountName] — call on (re-)login completion. */
    fun clearSyncPause(context: Context, accountName: String) {
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .remove(SyncPreferences.syncPausedUntilKey(accountName))
            .apply()
    }

    // ---- Sync preflight ----
    //
    // The shared chunk at the top of every doPerformSync: subscription gate, re-auth
    // backoff, session validation, key re-unlock. Identical between CalendarSyncAdapter
    // and ContactsSyncAdapter except for log labels and which Account ("session
    // account" — Calendar uses the sync-adapter Account directly; Contacts resolves
    // its parent via parent_account_name user data first).
    //
    // Returns:
    //  - [PreflightResult.Abort] — caller should `return` immediately. syncResult.stats
    //    has been bumped where appropriate; pause-pref / re-login notification have been
    //    fired where appropriate.
    //  - [PreflightResult.Proceed] with the validated session; caller continues with
    //    its sync body and post-sync persistence.

    /** Outcome of [runSyncPreflight]. */
    sealed class PreflightResult {
        /** Caller should return without doing any sync work. */
        object Abort : PreflightResult()
        /** Preflight passed; proceed with sync using this session. */
        data class Proceed(val session: AccountSession) : PreflightResult()
    }

    /**
     * Runs the shared preflight checks both sync adapters perform before their main
     * sync body. See top-of-file comment for the steps.
     *
     * @param parentAccount The "session account" — i.e. the main Proton account that
     *   owns the API tokens & key material. For Calendar this equals the sync-adapter
     *   Account; for Contacts it's the parent looked up from address-book user data.
     * @param syncTypeLabel Capitalized display label used in log messages (e.g.
     *   "Contacts" or "Calendar"). The pause-pref and re-login notification use
     *   parentAccount.name regardless.
     */
    fun runSyncPreflight(
        context: Context,
        parentAccount: Account,
        sessionManager: AccountSessionManager,
        keyManager: KeyManager,
        apiClient: ProtonApiClient,
        isManual: Boolean,
        syncResult: SyncResult,
        syncTypeLabel: String,
        tag: String
    ): PreflightResult {
        // Subscription gate — block sync if no active subscription (device-wide)
        if (!SubscriptionGate.isSyncAllowedCached(context)) {
            SyncLog.i(tag, "$syncTypeLabel sync skipped for ${parentAccount.name}: subscription inactive")
            return PreflightResult.Abort
        }

        // Re-auth backoff: scheduled syncs are skipped while a recent auth/unlock
        // failure is still in cooldown. Manual sync requests bypass.
        if (isSyncPaused(context, parentAccount.name, isManual)) {
            SyncLog.i(tag, "$syncTypeLabel sync skipped for ${parentAccount.name}: paused after recent auth failure")
            return PreflightResult.Abort
        }

        // Verify we have a valid, fully-authenticated session on the parent account.
        var session = sessionManager.getSession(parentAccount)
        if (session.accessToken.isEmpty()) {
            // A backup-restored session arrives with only a refresh token (no access
            // token). The reactive 401 path can't help here — the preflight would abort
            // before any request is made — so proactively exchange the refresh token for
            // an access token. Self-heals the very first sync after a backup import.
            //
            // Serialize per account: Calendar and Contacts sync fire concurrently on that
            // first post-restore sync. Proton's refresh endpoint ROTATES the refresh token,
            // so two unsynchronised calls would race — the second would fail on the
            // now-invalidated token and spuriously report "re-login required". The lock +
            // re-read make the loser observe the winner's freshly-minted token instead of
            // calling refresh at all.
            if (session.refreshToken.isNotEmpty()) {
                val lock = refreshLocks.getOrPut(parentAccount.name) { ReentrantLock() }
                lock.lock()
                try {
                    session = sessionManager.getSession(parentAccount)  // another adapter may have just refreshed
                    if (session.accessToken.isEmpty() && session.refreshToken.isNotEmpty()) {
                        val refreshed = TokenRefresher.refresh(session.uid, session.refreshToken)
                        if (refreshed != null) {
                            sessionManager.updateTokens(parentAccount.name, refreshed.accessToken, refreshed.refreshToken)
                            session = sessionManager.getSession(parentAccount)
                            SyncLog.i(tag, "$syncTypeLabel: minted access token from stored refresh token for ${parentAccount.name}")
                        }
                    }
                } finally {
                    lock.unlock()
                }
            }
            if (session.accessToken.isEmpty()) {
                SyncLog.i(tag, "$syncTypeLabel sync skipped for ${parentAccount.name}: no access token (re-auth required)")
                pauseSyncAfterAuthFailure(context, parentAccount.name)
                ProtonSyncApp.notifyReLoginRequired(context, parentAccount.name, ProtonSyncApp.AuthFailReason.SESSION_EXPIRED)
                syncResult.stats.numAuthExceptions++
                return PreflightResult.Abort
            }
        }
        if (session.mailboxPassword == null) {
            SyncLog.i(tag, "$syncTypeLabel sync skipped for ${parentAccount.name}: keys not unlocked (awaiting 2FA / mailbox password)")
            syncResult.stats.numAuthExceptions++
            return PreflightResult.Abort
        }

        // Re-unlock keys if needed (process may have restarted). Throws
        // KeysNotUnlockedException if cached credentials no longer work — surfaced
        // here as a re-auth-required case.
        try {
            ensureKeysUnlocked(parentAccount, session, keyManager, apiClient, tag)
        } catch (e: KeysNotUnlockedException) {
            SyncLog.w(tag, "$syncTypeLabel sync aborted for ${parentAccount.name}: ${e.message}")
            sessionManager.clearKeyMaterial(parentAccount.name)
            pauseSyncAfterAuthFailure(context, parentAccount.name)
            ProtonSyncApp.notifyReLoginRequired(context, parentAccount.name, ProtonSyncApp.AuthFailReason.KEYS_LOCKED)
            syncResult.stats.numAuthExceptions++
            return PreflightResult.Abort
        }

        return PreflightResult.Proceed(session)
    }

    /**
     * Handles error classification and post-sync persistence — both adapters do the
     * same dance after [runUninterruptible] returns:
     *   1. If err != null: log; if [isAuthFailure], bump numAuthExceptions, pause
     *      future scheduled syncs, and fire re-login notification (KEYS_LOCKED if
     *      [KeysNotUnlockedException], else SESSION_EXPIRED). Else bump numIoExceptions.
     *   2. Compute syncFailed = err != null OR syncResult.hasError() (the latter
     *      catches per-row failures swallowed into stats counters).
     *   3. Drain swallowed-failure capture for this (syncType, account).
     *   4. On failure: write lastSyncFailed timestamp + error message pref, fire
     *      failure notification.
     *   5. On success: write lastSync timestamp, clear failure notification.
     *   6. Log sync completion.
     *
     * The "Sync incomplete" fallback is intentional — it avoids the substrings
     * "auth", "401", "host" that would mis-classify in
     * [SyncNotificationHelper.friendlyMessage].
     */
    fun savePostSyncResult(
        context: Context,
        parentAccountName: String,
        syncType: String,           // "contacts" / "calendar" — used in pref keys
        syncTypeLabel: String,      // "Contacts" / "Calendar" — used in log messages
        err: Exception?,
        syncResult: SyncResult,
        tag: String
    ) {
        // Step 1: classify err (no-op if err is null)
        if (err != null) {
            SyncLog.e(tag, "$syncTypeLabel sync failed for $parentAccountName", err)
            if (isAuthFailure(err)) {
                SyncLog.w(tag, "Auth failure detected for $parentAccountName, notifying user")
                syncResult.stats.numAuthExceptions++
                pauseSyncAfterAuthFailure(context, parentAccountName)
                val reason = if (err is KeysNotUnlockedException)
                    ProtonSyncApp.AuthFailReason.KEYS_LOCKED
                else
                    ProtonSyncApp.AuthFailReason.SESSION_EXPIRED
                ProtonSyncApp.notifyReLoginRequired(context, parentAccountName, reason)
            } else {
                syncResult.stats.numIoExceptions++
            }
        }

        // Step 2-5: persist timestamps & notifications
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val now = System.currentTimeMillis()
        // Always drain the swallowed-failure slot so a stale entry from a previous
        // run can't bleed into a later success.
        val swallowed = consumeSwallowedFailure(syncType, parentAccountName)
        val syncFailed = err != null || syncResult.hasError()
        if (syncFailed) {
            val errMsg = err?.message ?: swallowed ?: "Sync incomplete"
            prefs.edit()
                .putLong(SyncPreferences.lastSyncFailedKey(parentAccountName, syncType), now)
                .putString(SyncPreferences.lastSyncErrorMsgKey(syncType, parentAccountName), errMsg)
                .apply()
            SyncNotificationHelper.notifyFailure(context, parentAccountName, syncType, errMsg)
        } else {
            prefs.edit().putLong(SyncPreferences.lastSyncKey(parentAccountName, syncType), now).apply()
            SyncNotificationHelper.clearFailure(context, parentAccountName, syncType)
        }

        // Says which it was. This line read "sync completed" whatever happened, so a run that
        // fetched nothing at all - every request 503 during Proton's outage - signed off in the log
        // exactly like a healthy one, two lines under the error that aborted it. Anyone reading
        // their own debug report saw a completed sync and no reason to look further.
        if (syncFailed) {
            SyncLog.w(tag, "$syncTypeLabel sync FAILED for $parentAccountName: ${err?.message ?: swallowed ?: "no reason recorded"}")
        } else {
            SyncLog.i(tag, "$syncTypeLabel sync completed for $parentAccountName")
        }
    }

    // ---- Swallowed-failure capture ----
    //
    // Inner sync loops sometimes catch an exception, bump a `SyncResult.stats` counter
    // (numIoExceptions++ / numAuthExceptions++), and continue — so the rest of the
    // sync still completes. The doPerformSync exit handler then needs to surface the
    // *real* error text in the failure notification & dialog. Without capturing the
    // first swallowed message we'd synthesize a string like "Sync incomplete (io=1)"
    // which (a) is unhelpful to users and (b) trips
    // [SyncNotificationHelper.friendlyMessage] keyword matching (e.g. "auth" maps to
    // "session expired"). Each adapter records the first message it swallows; the
    // exit handler drains it once via [consumeSwallowedFailure].
    //
    // Keyed by `"$syncType:$accountName"` so contacts and calendar failures stay
    // separate even when both ran for the same account in one sync cycle.

    private val swallowedFailures = ConcurrentHashMap<String, String>()

    /**
     * The single writer of a ContentResolver periodic sync, and the only one that knows the rule.
     *
     * The rule is: a periodic sync exists only while the subscription lets sync run, the type is
     * switched ON and the account's schedule is a real interval, and its period IS that interval.
     * It had been open-coded at eight call sites, six of which passed a hardcoded one hour and read
     * no preference at all. That is how "Manual only" kept coming back: fixing the picker left the
     * sync switches, provisioning, the rename paths and the restore each free to arm an hourly job
     * against a schedule that said none. The worst of them sat one row above the schedule setting:
     * toggle Contacts off and on and the hour was back, which is precisely the tap somebody
     * troubleshooting an unwanted sync would make. Even after that, provisioning, the setup
     * chooser, both rename paths and two dead settings helpers each still armed a hardcoded hour
     * until 2.0.2.
     *
     * The subscription is part of the rule because arming while it is inactive woke the phone for
     * syncs the gate only declined, and undid the removal the subscription observer had just made.
     *
     * @param parentAccountName the PARENT account the schedule belongs to. The contacts sync lives
     *   on the address-book child, whose preferences are keyed by the parent.
     * @param syncAllowed whether the subscription lets sync run. Read from the stored state by
     *   default; the subscription observer passes true, because it acts on the state it was just
     *   told and must not depend on when the store catches up.
     */
    fun applyPeriodicSync(
        context: Context,
        account: Account,
        authority: String,
        parentAccountName: String,
        syncAllowed: Boolean = SubscriptionGate.isSyncAllowedCached(context),
    ) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val seconds = SyncPreferences.getLongFromStringPref(
            prefs,
            SyncPreferences.eventLoopIntervalKey(parentAccountName),
            SyncConstants.DEFAULT_INITIAL_PERIODIC_SYNC_SECONDS,
        )
        val enabled = android.content.ContentResolver.getSyncAutomatically(account, authority)
        if (periodicSyncWanted(enabled, seconds, syncAllowed)) {
            android.content.ContentResolver.addPeriodicSync(
                account, authority, android.os.Bundle.EMPTY, seconds
            )
        } else {
            // Switched off, a manual schedule, or an inactive subscription. All mean no periodic
            // wakeup, and removePeriodicSync is a no-op when there is nothing to remove.
            android.content.ContentResolver.removePeriodicSync(account, authority, android.os.Bundle.EMPTY)
        }
    }

    /** [applyPeriodicSync]'s rule on its own, so the unit tests exercise the shipped one. */
    internal fun periodicSyncWanted(enabled: Boolean, seconds: Long, syncAllowed: Boolean): Boolean =
        syncAllowed && enabled && seconds > 0

    /** Record the first swallowed (counted-but-not-thrown) exception message for this sync run. */
    fun recordSwallowedFailure(syncType: String, accountName: String, message: String?) {
        if (message.isNullOrBlank()) return
        swallowedFailures.putIfAbsent("$syncType:$accountName", message)
    }

    /** Drain and return the first swallowed message, or null if none was recorded. */
    fun consumeSwallowedFailure(syncType: String, accountName: String): String? =
        swallowedFailures.remove("$syncType:$accountName")

    /**
     * Acquires a per-account sync lock, runs [block], then releases.
     *
     * If the lock is already held (concurrent sync for the same type+account),
     * logs a skip message and returns immediately without running [block].
     *
     * @param syncType "calendar" or "contacts", combined with [accountName] for the lock key
     * @param accountName The account name to lock on
     * @param tag Log tag for the "already in progress" message
     * @param block The sync work to execute under the lock
     */
    fun withSyncLock(syncType: String, accountName: String, tag: String, block: () -> Unit) {
        val lockKey = "$syncType:$accountName"
        val lock = syncLocks.getOrPut(lockKey) { ReentrantLock() }
        if (!lock.tryLock()) {
            SyncLog.i(tag, "${syncType.replaceFirstChar { it.uppercase() }} sync already in progress for $accountName, skipping")
            return
        }
        try {
            block()
        } finally {
            lock.unlock()
        }
    }
}

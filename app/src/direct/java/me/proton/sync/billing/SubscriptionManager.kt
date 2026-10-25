package me.proton.sync.billing

import android.content.Context
import androidx.preference.PreferenceManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.proton.sync.SyncLog
import me.proton.sync.SyncNotificationHelper
import me.proton.sync.SyncPreferences
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Paddle-backed subscription state for the `direct` (self-distribution / Obtainium)
 * flavor — no Google Play Billing.
 *
 * The Paddle subscription id (`sub_xxx`) is the license key: the user buys via web
 * checkout, then enters the id in settings ([activate], which checks it with the proxy before
 * storing it), or the transaction id from Paddle's invoice (`txn_xxx`), which [activate] has the
 * proxy turn into the subscription id first. We validate it against the stateless license proxy at
 * [LICENSE_BASE_URL].
 * Grace-period / terminal-vs-transient handling is in [applyVerdict].
 *
 * Exposes the same neutral surface as the play [SubscriptionManager] — `state`,
 * `currentState()`, `isSyncAllowed()`, `connect()`, `refreshState()` — so shared code
 * (ProtonSyncApp, SyncSettingsActivity, [SubscriptionGate]) stays flavor-agnostic.
 * The cached state is persisted under the same SharedPreferences keys, so the
 * device-wide gate works identically across flavors.
 */
@Singleton
class SubscriptionManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val _state = MutableStateFlow(loadCachedState())
    val state: StateFlow<SubscriptionState> = _state

    fun currentState(): SubscriptionState = _state.value

    /**
     * The play manager bumps this when Google's product details load, so the subscription card can
     * refresh its wording. Paddle has no product details to wait for, so it never changes here.
     */
    val productDetailsVersion: StateFlow<Int> = MutableStateFlow(0)

    /** Returns true when the cached state permits sync (Trial or Active). */
    fun isSyncAllowed(): Boolean = _state.value.isActive

    /** No billing client to connect — just kick a validation refresh. */
    fun connect() {
        scope.launch { refreshState() }
    }

    /** The currently stored Paddle subscription id, if any (for the manage UI). */
    fun currentSubId(): String? = prefs.getString(SyncPreferences.KEY_PADDLE_SUB_ID, null)

    /**
     * Store a Paddle subscription id without checking it first, and validate it straight after. For a
     * settings backup only ([restoreFromBackup]), where the install has no id that could be lost; ids typed
     * in the dialog go through [activate]. A new id is unconfirmed, so it is checked as reason="activation"
     * until the proxy accepts it once, and as reason="refresh" after that (see [licenseReason]).
     */
    @Synchronized
    private fun applyLicense(subId: String) {
        val cleaned = subId.trim()
        // Storing the id that is already stored is a retry, not a new id: keep its confirmation, or a proxy
        // fault during the retry could delete a paying id.
        val newId = cleaned != currentSubId()?.trim()
        val edit = prefs.edit().putString(SyncPreferences.KEY_PADDLE_SUB_ID, cleaned)
        if (newId) edit.putBoolean(SyncPreferences.KEY_LICENSE_CONFIRMED, false)
        edit.apply()
        clearGrace()
        // A different id starts clean: "paused", "payment failed" or a scheduled change described the old
        // one, and the proxy has not said anything about this one yet.
        if (newId) transition(_state.value.withoutPaddleDetails())
        scope.launch { refreshState() }
    }

    /** The subscription id for a password-protected settings backup, or null when none is entered. */
    fun subscriptionIdForBackup(): String? = currentSubId()?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * Take a subscription id from a password-protected settings backup, so a restore on a new phone
     * or after a reinstall keeps sync activated without pasting the id again.
     *
     * Only when this install has no id of its own: a restore never swaps a key the user entered here
     * for one from a file, which may be older. The id is stored and validated at once, unlike a typed
     * id, which [activate] checks before storing: this install has no id of its own here, so an id Paddle
     * no longer knows costs nothing when the check drops it again. It is reported as an activation like a
     * typed id, since it puts the key on an install the same way.
     *
     * The field is read as the dialog reads its text ([parseSubscriptionEntry]) and only one id is taken
     * from it, so text that is not an id (an email address, a transaction id, a hand-edited file) never
     * reaches the licence proxy. Logged by kind and length, never the text.
     *
     * @return true when the id was taken and the check started.
     */
    fun restoreFromBackup(subId: String): Boolean {
        val entry = parseSubscriptionEntry(subId)
        if (entry == SubscriptionEntry.Empty) return false
        if (entry !is SubscriptionEntry.Valid) {
            SyncLog.w(TAG, "Backup carries a subscription id field that is not an id (${entry.kind}, ${subId.length} characters); ignored")
            return false
        }
        if (!currentSubId().isNullOrBlank()) {
            SyncLog.i(TAG, "Backup carries a subscription id; this install already has one, keeping it")
            return false
        }
        SyncLog.i(TAG, "Subscription id restored from a backup; validating")
        applyLicense(entry.subId)
        return true
    }

    /** Forget the stored subscription id (user-initiated). */
    fun clearLicense() {
        prefs.edit().remove(SyncPreferences.KEY_PADDLE_SUB_ID).remove(SyncPreferences.KEY_LICENSE_CONFIRMED).apply()
        clearGrace()
        scope.launch { refreshState() }
    }

    /**
     * Whether the proxy has accepted the stored id before; see [SyncPreferences.KEY_LICENSE_CONFIRMED].
     *
     * An install from before the flag gets [licenseConfirmedByDefault] once, and it is stored right
     * away: recomputed on every read, a later grace expiry (state "Expired") would quietly turn a
     * confirmed id back into an unconfirmed one, which a proxy fault could then delete.
     */
    @Synchronized
    private fun licenseConfirmed(): Boolean {
        if (prefs.contains(SyncPreferences.KEY_LICENSE_CONFIRMED)) {
            return prefs.getBoolean(SyncPreferences.KEY_LICENSE_CONFIRMED, false)
        }
        val migrated = licenseConfirmedByDefault(prefs.getString(SyncPreferences.KEY_SUBSCRIPTION_STATE, null))
        if (!currentSubId().isNullOrBlank()) {
            prefs.edit().putBoolean(SyncPreferences.KEY_LICENSE_CONFIRMED, migrated).apply()
        }
        return migrated
    }

    /**
     * One licence check at a time. Settings' onResume starts two while the app's own startup check may
     * still be running; unserialised, an unconfirmed id went out as "activation" two or three times at
     * once. In a queue, the first answer confirms the id and the rest go out as "refresh".
     */
    private val refreshLock = Mutex()

    suspend fun refreshState() {
        refreshLock.withLock { checkStoredId() }
    }

    /**
     * [refreshState]'s check, for a caller that already holds [refreshLock].
     *
     * @return the verdict it applied, or null when no id is stored or a fixture answer was dropped.
     */
    private fun checkStoredId(): LicenseVerdict? {
        val subId = prefs.getString(SyncPreferences.KEY_PADDLE_SUB_ID, null)?.trim()
        if (subId.isNullOrEmpty()) {
            val had = prefs.getString(SyncPreferences.KEY_SUBSCRIPTION_PRODUCT, null)
            transition(
                if (had.isNullOrEmpty()) SubscriptionState.NoSubscription
                else SubscriptionState.Expired(had)
            )
            return null
        }

        val fixtureBefore = debugFixtureBody
        val verdict = callValidate(subId, licenseConfirmed())
        // Debug fixtures only (always null in release): a check that went to the network before a
        // fixture was set must not replace the fixture when its answer lands afterwards. On a cold
        // start the app's own startup check is exactly that, and it overwrote a fresh fixture.
        if (fixtureBefore == null && debugFixtureBody != null) return null
        applyVerdict(verdict, checkedId = subId)
        return verdict
    }

    /**
     * The subscription dialog's Activate, for an entry [parseSubscriptionEntry] accepted: a subscription id
     * ([SubscriptionEntry.Valid], lower case), or a transaction id ([SubscriptionEntry.ValidTransaction]) that
     * the licence proxy's /txn-to-sub first turns into the id of the subscription it paid for
     * ([lookUpTransaction]). A transaction that leads to no subscription id ends there ([transactionOutcome]),
     * with nothing stored and nothing changed; the id it does lead to goes on exactly as a typed one.
     *
     * The id is stored only once Paddle names it a subscription of this product (see
     * [planActivation]); a refused or unanswered entry leaves the stored id and the state exactly as they
     * were, and a paused or unpaid entry does not replace an id that syncs. The stored id entered again is a
     * re-check, handled as any settings visit's check.
     *
     * Runs in this manager's scope, not the caller's: the check is a blocking call that ignores cancellation,
     * and a screen closed mid-check (a rotation) must not drop an answer that was already accepted. The card
     * of the recreated screen shows it through [state]; only the dialog's own message is lost.
     *
     * [debugOffline] (debug builds only, from the license_enter verb) answers the check of a different id,
     * and every transaction lookup, with "no connection" without asking the proxy.
     */
    internal suspend fun activate(entry: SubscriptionEntry.Sendable, debugOffline: Boolean = false): ActivationOutcome =
        scope.async { refreshLock.withLock { activateLocked(entry, debugOffline) } }.await()

    private fun activateLocked(entry: SubscriptionEntry.Sendable, debugOffline: Boolean): ActivationOutcome {
        val candidate = when (entry) {
            is SubscriptionEntry.Valid -> entry.subId
            is SubscriptionEntry.ValidTransaction -> {
                val lookup = lookUpTransaction(entry.txnId, debugOffline)
                // Null exactly when the lookup found a subscription id.
                val stop = transactionOutcome(lookup)
                // The answer and what it means, never an id.
                SyncLog.i(
                    TAG,
                    "Entered transaction id looked up: $lookup" +
                        (stop?.let { " -> $it; nothing stored" } ?: "; its subscription id is checked as if entered")
                )
                if (stop != null) return stop
                (lookup as TransactionLookup.Resolved).subId
            }
        }
        val stored = currentSubId()?.trim()
        if (stored != null && stored.lowercase() == candidate) {
            if (debugOffline) SyncLog.i("DebugCommand", "license_enter: offline applies to a different id only; re-checking the stored one")
            val verdict = checkStoredId()
            val outcome = verdict?.let(::recheckOutcome) ?: ActivationOutcome.NotChecked("no_subscription_id")
            SyncLog.i(TAG, "Entered subscription id is the stored one; re-checked: $outcome")
            return outcome
        }
        val verdict = if (me.proton.sync.BuildConfig.DEBUG && debugOffline) {
            LicenseVerdict.Unknown("network_error")
        } else {
            callValidate(candidate, confirmed = false, useFixture = false)
        }
        return commitActivation(candidate, verdict)
    }

    /**
     * Act on one answer from the licence proxy. The only place a verdict changes the state, shared by
     * [refreshState], [commitActivation] and [debugApplyFixture], so a fixture drives exactly the code a
     * real answer does.
     * What each verdict means, and why, is in [LicenseVerdict].
     */
    // Synchronized with updateState: settings' onResume starts two refreshes at once, and a debug
    // fixture adds a main-thread writer, so the StateFlow and the persisted state must change together.
    @Synchronized
    private fun applyVerdict(verdict: LicenseVerdict, checkedId: String) {
        // The user can save a different id while a check for the old one is on the network. Its answer
        // is about the old id: applied now it could mark the new id confirmed, or delete it.
        if (prefs.getString(SyncPreferences.KEY_PADDLE_SUB_ID, null)?.trim() != checkedId) {
            SyncLog.i(TAG, "Licence answer dropped: the subscription id changed while it was checked")
            return
        }
        val before = _state.value
        when (verdict) {
            is LicenseVerdict.Entitled -> {
                clearGrace()
                markConfirmed()
                val expiry = verdict.periodEndsAtMs ?: (System.currentTimeMillis() + THIRTY_DAYS_MS)
                // Gate allows only Trial/Active. Paddle `past_due` is a dunning grace where
                // access should continue, so it maps to Active too (not the Grace state,
                // which would block sync), flagged so the card asks for a new payment method.
                val newState = if (verdict.trial) {
                    SubscriptionState.Trial(expiry, PRODUCT_ID, scheduledChange = verdict.scheduledChange)
                } else {
                    SubscriptionState.Active(
                        expiry, PRODUCT_ID, paymentFailed = verdict.paymentFailed, scheduledChange = verdict.scheduledChange,
                    )
                }
                val wasFailing = (_state.value as? SubscriptionState.Active)?.paymentFailed == true
                if (verdict.paymentFailed && !wasFailing) {
                    SyncLog.w(TAG, "Subscription past due: Paddle is retrying the payment; sync continues")
                } else if (!verdict.paymentFailed && wasFailing) {
                    SyncLog.i(TAG, "Subscription no longer past due")
                }
                // The way back, logged once like the way in (Unpaid and Suspended below): without it a field log
                // shows the stop but only the scheduler's "arming" line for the recovery.
                (before as? SubscriptionState.Expired)?.let { was ->
                    val from = if (was.paused) "paused" else if (was.paymentFailed) "unpaid" else "expired"
                    SyncLog.i(TAG, "Subscription ${if (verdict.trial) "in trial" else "active"} again (was $from); sync on")
                }
                // Logged when it changes only: every app start and every settings visit re-checks.
                val change = verdict.scheduledChange
                if (change != before.scheduledChangeOrNull()) {
                    SyncLog.i(
                        TAG,
                        if (change == null) "No change scheduled for the subscription any more"
                        else "Paddle has scheduled the subscription to ${change.action.wire} at ${Instant.ofEpochMilli(change.effectiveAtMs)}"
                    )
                }
                transition(newState)
            }

            LicenseVerdict.Unpaid -> {
                // Past due and never paid (or past due too long). No sync, but the id stays, so fixing
                // the card brings sync back at the next check without pasting the id again.
                clearGrace()
                markConfirmed()
                val wasUnpaid = (before as? SubscriptionState.Expired)?.paymentFailed == true
                transition(
                    SubscriptionState.Expired(
                        prefs.getString(SyncPreferences.KEY_SUBSCRIPTION_PRODUCT, null) ?: PRODUCT_ID,
                        paymentFailed = true,
                    )
                )
                if (!wasUnpaid) SyncLog.w(TAG, "Subscription unpaid: payment not collected; sync off, id kept")
            }

            LicenseVerdict.Suspended -> {
                // Paused, not ended: sync stops but the id stays and is checked again on every start.
                // Paddle can resume a paused subscription, and forgetting the id here would leave the
                // user to find and paste it again when it does.
                clearGrace()
                markConfirmed()
                // Logged on the way in only: every app start and every settings visit re-checks.
                val wasPaused = (_state.value as? SubscriptionState.Expired)?.paused == true
                transition(
                    SubscriptionState.Expired(
                        prefs.getString(SyncPreferences.KEY_SUBSCRIPTION_PRODUCT, null) ?: PRODUCT_ID,
                        paused = true,
                    )
                )
                if (!wasPaused) SyncLog.i(TAG, "Subscription paused; sync off, id kept and re-checked")
            }

            is LicenseVerdict.Ended -> {
                // Subscription gone/invalid — forget it so we stop re-checking.
                prefs.edit().remove(SyncPreferences.KEY_PADDLE_SUB_ID).remove(SyncPreferences.KEY_LICENSE_CONFIRMED).apply()
                clearGrace()
                val had = prefs.getString(SyncPreferences.KEY_SUBSCRIPTION_PRODUCT, null)
                transition(
                    if (had.isNullOrEmpty()) SubscriptionState.NoSubscription
                    else SubscriptionState.Expired(had)
                )
                SyncLog.i(TAG, "License cleared (terminal: ${verdict.error})")
            }

            is LicenseVerdict.Unknown -> {
                // Network/Paddle hiccup — keep the existing state working within a 7-day
                // grace window, then degrade so sync stops.
                val now = System.currentTimeMillis()
                val firstFailed = prefs.getLong(SyncPreferences.KEY_LICENSE_FIRST_FAILED, 0L)
                if (firstFailed == 0L) {
                    prefs.edit().putLong(SyncPreferences.KEY_LICENSE_FIRST_FAILED, now).apply()
                    SyncLog.w(TAG, "License validation transient failure (${verdict.error}); grace window started")
                } else if (now - firstFailed > GRACE_PERIOD_MS) {
                    // A paused or unpaid subscription stays that way: its id is still stored, and the
                    // card must not turn into an ordinary "expired" one because the proxy was out of
                    // reach. A failed renewal that was still syncing keeps its payment flag too. Only
                    // for a confirmed id, though: otherwise the flags may describe a previous id.
                    val keep = licenseConfirmed()
                    val wasPaused = keep && (before as? SubscriptionState.Expired)?.paused == true
                    val wasFailing = keep && ((before as? SubscriptionState.Expired)?.paymentFailed == true ||
                        (before as? SubscriptionState.Active)?.paymentFailed == true)
                    transition(
                        SubscriptionState.Expired(
                            prefs.getString(SyncPreferences.KEY_SUBSCRIPTION_PRODUCT, null) ?: PRODUCT_ID,
                            paused = wasPaused,
                            paymentFailed = wasFailing,
                        )
                    )
                    SyncLog.w(TAG, "License grace window exceeded; blocking sync")
                }
                // else: still within grace — leave the cached state unchanged.
            }
        }
    }

    /**
     * Store an entered id per [planActivation], from the stored id and state as they are now. A stored id
     * starts unconfirmed and clean, as [applyLicense] starts one: the old id's paused, past-due or scheduled
     * flags must not colour the log lines or the payment notice for this one. [applyVerdict] then confirms it
     * and sets the state, so it stays the only place a verdict changes the state. Runs under [refreshLock]
     * ([activate]), so no check of the old id still on the network can land after it.
     */
    @Synchronized
    private fun commitActivation(candidate: String, verdict: LicenseVerdict): ActivationOutcome {
        val hadId = !currentSubId().isNullOrBlank()
        val plan = planActivation(verdict, storedIdSyncing = hadId && _state.value.isActive)
        if (plan.store) {
            prefs.edit()
                .putString(SyncPreferences.KEY_PADDLE_SUB_ID, candidate)
                .putBoolean(SyncPreferences.KEY_LICENSE_CONFIRMED, false)
                .apply()
            clearGrace()
            transition(_state.value.withoutPaddleDetails())
            applyVerdict(verdict, checkedId = candidate)
        }
        // The outcome and what happened to the stored id, never an id.
        SyncLog.i(
            TAG,
            "Entered subscription id checked as an activation: ${plan.outcome}; " + when {
                plan.store && hadId -> "stored, replacing the previous id"
                plan.store -> "stored"
                hadId -> "the stored id is kept"
                else -> "nothing stored"
            }
        )
        return plan.outcome
    }

    /**
     * Every state change goes through here, so the payment notice follows the state whichever path
     * changed it: a verdict, a new id, or a check that found no id at all. The card says the same; the
     * notice is the part a user of a background sync app actually sees, and without Paddle's recovery
     * emails it may be the only prompt they get.
     */
    @Synchronized
    private fun transition(newState: SubscriptionState) {
        val before = _state.value
        if (newState == before) return
        updateState(newState)
        when (paymentNoticeChange(before, newState)) {
            PaymentNotice.SHOW -> SyncNotificationHelper.notifyPaymentFailed(context, syncOn = newState.isActive)
            PaymentNotice.CLEAR -> SyncNotificationHelper.cancelPaymentFailed(context)
            PaymentNotice.NONE -> {}
        }
    }

    private fun markConfirmed() {
        if (!prefs.getBoolean(SyncPreferences.KEY_LICENSE_CONFIRMED, false)) {
            prefs.edit().putBoolean(SyncPreferences.KEY_LICENSE_CONFIRMED, true).apply()
        }
    }

    /**
     * Debug-only stand-in for the proxy's answer, set by [debugApplyFixture]. In memory on purpose: a
     * process restart forgets it, so a fixture can never outlive the test that set it by more than one
     * app start.
     */
    @Volatile
    private var debugFixtureBody: String? = null

    /**
     * Debug builds only, reached through the DUMP-protected debug command: answer every licence check
     * with [status] as if the proxy had said it, until "refresh" (or a process restart) puts the real
     * proxy back. Lets the settings card and the sync gate be checked on a phone without a real failed
     * payment. The fixture has to stay in place, not be applied once: opening the settings screen
     * refreshes the licence, and the real answer would replace a one-off fixture straight away.
     *
     * There is deliberately no "canceled": that verdict deletes the stored subscription id, and on a
     * test phone that is the real licence. "scheduled_cancel" is safe: an active subscription that Paddle
     * will cancel in 30 days, which keeps the id like any active one. "scheduled_pause" is the same with a
     * pause. "trialing" is a Paddle trial that ends in 7 days, for the trial card and its day count.
     */
    fun debugApplyFixture(status: String) {
        if (!me.proton.sync.BuildConfig.DEBUG) return
        val storedId = currentSubId()?.trim()
        if (status != "refresh" && storedId.isNullOrEmpty()) {
            SyncLog.w("DebugCommand", "license_fixture: no subscription id is stored, so there is nothing to stand in for")
            return
        }
        val body = when (status) {
            "past_due" -> JSONObject()
                .put("ok", true).put("valid", true).put("status", "past_due")
                .put("current_period_ends_at", Instant.ofEpochMilli(System.currentTimeMillis() + THIRTY_DAYS_MS).toString())
                .toString()
            "trialing" -> JSONObject()
                .put("ok", true).put("valid", true).put("status", "trialing")
                .put("current_period_ends_at", Instant.ofEpochMilli(System.currentTimeMillis() + 7L * 24 * 60 * 60 * 1000).toString())
                .toString()
            "paused" -> JSONObject()
                .put("ok", false).put("error", "subscription_paused").put("status", "paused")
                .toString()
            "unpaid" -> JSONObject()
                .put("ok", false).put("error", "subscription_unpaid").put("status", "past_due")
                .toString()
            "scheduled_cancel", "scheduled_pause" -> {
                val periodEnds = Instant.ofEpochMilli(System.currentTimeMillis() + THIRTY_DAYS_MS).toString()
                JSONObject()
                    .put("ok", true).put("valid", true).put("status", "active")
                    .put("current_period_ends_at", periodEnds)
                    .put(
                        "scheduled_change",
                        JSONObject().put("action", status.removePrefix("scheduled_")).put("effective_at", periodEnds)
                    )
                    .toString()
            }
            "refresh" -> {
                debugFixtureBody = null
                SyncLog.i("DebugCommand", "license_fixture: cleared; asking the licence proxy again")
                scope.launch { refreshState() }
                return
            }
            else -> {
                SyncLog.w(
                    "DebugCommand",
                    "license_fixture: unknown status '$status' " +
                        "(past_due | unpaid | paused | scheduled_cancel | scheduled_pause | trialing | refresh)"
                )
                return
            }
        }
        debugFixtureBody = body
        val verdict = classifyLicenseAnswer(200, body, licenseConfirmed())
        SyncLog.i("DebugCommand", "license_fixture: '$status' stands in for the proxy until refresh ($verdict)")
        applyVerdict(verdict, checkedId = storedId!!)
    }

    private fun clearGrace() {
        if (prefs.contains(SyncPreferences.KEY_LICENSE_FIRST_FAILED)) {
            prefs.edit().remove(SyncPreferences.KEY_LICENSE_FIRST_FAILED).apply()
        }
    }

    private fun callValidate(subId: String, confirmed: Boolean, useFixture: Boolean = true): LicenseVerdict {
        // A debug fixture stands in for the proxy until "refresh", so the refresh every settings
        // onResume makes cannot replace it a second after it was applied. Only for the stored id: a typed
        // id is always checked by the real proxy, so a fixture standing in for the stored id can never
        // make an entry look accepted and replace it.
        if (useFixture) {
            debugFixtureBody?.let { if (me.proton.sync.BuildConfig.DEBUG) return classifyLicenseAnswer(200, it, confirmed) }
        }
        val url = "$LICENSE_BASE_URL/validate?sub=${URLEncoder.encode(subId, "UTF-8")}&reason=${licenseReason(confirmed)}"
        return try {
            val req = Request.Builder().url(url).get().build()
            http.newCall(req).execute().use { resp -> classifyLicenseAnswer(resp.code, resp.body?.string(), confirmed) }
        } catch (e: Exception) {
            SyncLog.w(TAG, "Validate call failed: ${e.message}")
            LicenseVerdict.Unknown("network_error")
        }
    }

    /**
     * Ask the licence proxy's /txn-to-sub which subscription [txnId] paid for, as [classifyTxnAnswer] reads the
     * answer. Read-only on the proxy's side, and never answered by a debug fixture, which stands in for the stored
     * id's check only. [debugOffline] (debug builds) answers "no connection" without asking.
     */
    private fun lookUpTransaction(txnId: String, debugOffline: Boolean): TransactionLookup {
        if (me.proton.sync.BuildConfig.DEBUG && debugOffline) return TransactionLookup.Unknown("network_error")
        val url = "$LICENSE_BASE_URL/txn-to-sub?txn=${URLEncoder.encode(txnId, "UTF-8")}"
        return try {
            val req = Request.Builder().url(url).get().build()
            http.newCall(req).execute().use { resp -> classifyTxnAnswer(resp.code, resp.body?.string()) }
        } catch (e: IOException) {
            // No network, an unknown host, a timeout. The class only: a message can carry the URL, and with it the id.
            SyncLog.w(TAG, "Transaction lookup failed: ${e.javaClass.simpleName}")
            TransactionLookup.Unknown("network_error")
        } catch (e: Exception) {
            SyncLog.w(TAG, "Transaction lookup failed: ${e.javaClass.simpleName}")
            TransactionLookup.Unknown("request_failed")
        }
    }

    /**
     * Fetch Paddle's customer-portal links for the current `sub_xxx` via the proxy ([LICENSE_BASE_URL]/manage-url),
     * as [classifyManageAnswer] reads them: the links the app may open, nothing to manage, the service unavailable,
     * or no connection. Each outcome is logged by kind, never with a link or the id: a link carries the id.
     */
    internal suspend fun fetchManageLinks(): ManageLinks = withContext(Dispatchers.IO) {
        val subId = currentSubId()?.trim()
        val links = if (subId.isNullOrEmpty()) {
            ManageLinks.NothingToManage("no_subscription_id")
        } else {
            val url = "$LICENSE_BASE_URL/manage-url?sub=${URLEncoder.encode(subId, "UTF-8")}"
            try {
                val req = Request.Builder().url(url).get().build()
                http.newCall(req).execute().use { resp -> classifyManageAnswer(resp.code, resp.body?.string()) }
            } catch (e: IOException) {
                // No network, an unknown host, a timeout, a connection dropped while reading the answer.
                ManageLinks.NoConnection(e.javaClass.simpleName)
            } catch (e: Exception) {
                ManageLinks.Unavailable(e.javaClass.simpleName)
            }
        }
        when (links) {
            is ManageLinks.Offered -> {
                val offered = listOfNotNull(
                    "update payment method".takeIf { links.updatePaymentMethod != null },
                    "cancel".takeIf { links.cancel != null },
                )
                SyncLog.i(TAG, "Manage links: ${offered.joinToString(" and ")} offered")
                links.refused.forEach { SyncLog.w(TAG, "Manage links: refused a link to $it (not https on an allowed host)") }
            }
            is ManageLinks.NothingToManage -> SyncLog.i(TAG, "Manage links: nothing to manage (${links.reason})")
            is ManageLinks.Unavailable -> SyncLog.w(TAG, "Manage links unavailable: ${links.reason}")
            is ManageLinks.NoConnection -> SyncLog.w(TAG, "Manage links: no connection (${links.reason})")
        }
        links
    }

    // The store first, then the flow. The gate (SubscriptionGate, the sync preflight, the event loop) reads
    // the stored state, and the scheduling observer acts as soon as the flow emits: emitting first let it
    // arm schedules and request syncs that the gate, still reading the old state, then declined.
    @Synchronized
    private fun updateState(newState: SubscriptionState) {
        persistState(newState)
        _state.value = newState
    }

    private fun persistState(s: SubscriptionState) {
        val editor = prefs.edit()
        editor.putLong(SyncPreferences.KEY_SUBSCRIPTION_LAST_CHECK, System.currentTimeMillis())
        editor.putString(SyncPreferences.KEY_SUBSCRIPTION_STATE, stateName(s))
        when (s) {
            is SubscriptionState.Trial -> {
                editor.putLong(SyncPreferences.KEY_SUBSCRIPTION_EXPIRY, s.expiresAtMs)
                editor.putString(SyncPreferences.KEY_SUBSCRIPTION_PRODUCT, s.productId)
            }
            is SubscriptionState.Active -> {
                editor.putLong(SyncPreferences.KEY_SUBSCRIPTION_EXPIRY, s.expiresAtMs)
                editor.putString(SyncPreferences.KEY_SUBSCRIPTION_PRODUCT, s.productId)
            }
            is SubscriptionState.Grace -> {
                editor.putLong(SyncPreferences.KEY_SUBSCRIPTION_EXPIRY, s.expiresAtMs)
                editor.putString(SyncPreferences.KEY_SUBSCRIPTION_PRODUCT, s.productId)
            }
            is SubscriptionState.Expired -> {
                editor.putLong(SyncPreferences.KEY_SUBSCRIPTION_EXPIRY, 0L)
                editor.putString(SyncPreferences.KEY_SUBSCRIPTION_PRODUCT, s.productId)
            }
            SubscriptionState.NoSubscription -> {
                editor.putLong(SyncPreferences.KEY_SUBSCRIPTION_EXPIRY, 0L)
                editor.remove(SyncPreferences.KEY_SUBSCRIPTION_PRODUCT)
            }
            SubscriptionState.Unknown -> { /* do not persist */ }
        }
        // Written on every persist, so a flag never outlives the state it describes.
        editor.putBoolean(
            SyncPreferences.KEY_SUBSCRIPTION_PAYMENT_FAILED,
            (s is SubscriptionState.Active && s.paymentFailed) || (s is SubscriptionState.Expired && s.paymentFailed),
        )
        editor.putBoolean(SyncPreferences.KEY_SUBSCRIPTION_PAUSED, s is SubscriptionState.Expired && s.paused)
        // The same for a scheduled change: gone from the card once /validate stops reporting it.
        ScheduledChangeStore.write(editor, s)
        editor.apply()
    }

    private fun loadCachedState(): SubscriptionState {
        val name = prefs.getString(SyncPreferences.KEY_SUBSCRIPTION_STATE, null) ?: return SubscriptionState.Unknown
        val expiry = prefs.getLong(SyncPreferences.KEY_SUBSCRIPTION_EXPIRY, 0L)
        val productId = prefs.getString(SyncPreferences.KEY_SUBSCRIPTION_PRODUCT, null) ?: ""
        return when (name) {
            "Trial" -> SubscriptionState.Trial(expiry, productId, scheduledChange = ScheduledChangeStore.read(prefs))
            "Active" -> SubscriptionState.Active(
                expiry, productId,
                paymentFailed = prefs.getBoolean(SyncPreferences.KEY_SUBSCRIPTION_PAYMENT_FAILED, false),
                scheduledChange = ScheduledChangeStore.read(prefs),
            )
            "Grace" -> SubscriptionState.Grace(expiry, productId)
            "Expired" -> SubscriptionState.Expired(
                productId,
                paused = prefs.getBoolean(SyncPreferences.KEY_SUBSCRIPTION_PAUSED, false),
                paymentFailed = prefs.getBoolean(SyncPreferences.KEY_SUBSCRIPTION_PAYMENT_FAILED, false),
            )
            "NoSubscription" -> SubscriptionState.NoSubscription
            else -> SubscriptionState.Unknown
        }
    }

    private fun stateName(s: SubscriptionState): String = when (s) {
        is SubscriptionState.Trial -> "Trial"
        is SubscriptionState.Active -> "Active"
        is SubscriptionState.Grace -> "Grace"
        is SubscriptionState.Expired -> "Expired"
        SubscriptionState.NoSubscription -> "NoSubscription"
        SubscriptionState.Unknown -> "Unknown"
    }

    companion object {
        private const val TAG = "SubscriptionManager"

        /** Paddle product id this build's subscriptions belong to (validated server-side too). */
        const val PRODUCT_ID = "pro_01ksf0a09fd3a9j0a70zeaeyme"

        /** License proxy base URL. */
        const val LICENSE_BASE_URL = "https://spp-license.olausson.de"

        /** Web checkout / manage page (Paddle-hosted checkout embedded). */
        const val CHECKOUT_URL = "https://apps.olausson.de/sync_provider_for_proton/checkout.html"

        /** Offline grace before a string of transient validation failures blocks sync. */
        private const val GRACE_PERIOD_MS = 7L * 24 * 60 * 60 * 1000

        /** Expiry floor when the proxy doesn't return a precise period end. */
        private const val THIRTY_DAYS_MS = 30L * 24 * 60 * 60 * 1000
    }
}

package me.proton.sync.billing

import android.app.Activity
import android.content.Context
import androidx.preference.PreferenceManager
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.InAppMessageParams
import com.android.billingclient.api.InAppMessageResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.proton.sync.SyncLog
import me.proton.sync.SyncPreferences
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for Google Play subscription state.
 *
 * The device-wide subscription is what gates sync. We cache the last known
 * state in SharedPreferences so that sync adapters can make decisions the
 * instant they run, without waiting for the BillingClient to connect.
 *
 * ## Product model
 * One product ID ([PRODUCT_ID]) with two base plans, [BASE_PLAN_MONTHLY] and [BASE_PLAN_ANNUAL].
 * Since 2026-09-17 each has a free-trial offer in Play Console: `trial-7d-monthly` and
 * `trial-7d-yearly`, one free P7D phase, for accounts that never had this subscription.
 * Google returns an offer only to eligible accounts; [PlayOfferSelection] picks what to launch
 * and what to promise.
 *
 * ## State resolution
 *  - no purchase, never subscribed on this install → [SubscriptionState.NoSubscription]
 *  - no purchase, subscribed before                → [SubscriptionState.Expired]
 *  - a suspended purchase (account hold or pause)  → [SubscriptionState.Expired] with suspended: sync off, and the
 *                                                    card sends the user to Google's page ([PlayPurchaseResolution])
 *  - a PURCHASED purchase, not suspended            → [SubscriptionState.Active], a grace period included
 *
 * The Play build never emits [SubscriptionState.Trial]: a Billing 8.3.0 `Purchase` carries no
 * offer id, pricing phase or expiry, so a trial purchase cannot be told apart from a paid one on
 * the device (that needs the server-side subscriptionsv2 API). Nor does it carry a renewal date, so
 * [SubscriptionState.Active] holds 0 for one and the settings card shows none
 * ([BillingUi.activeStatusText]); every active purchase counts as active now, refreshed on app resume.
 */
@Singleton
class SubscriptionManager @Inject constructor(
    @ApplicationContext private val context: Context
) : PurchasesUpdatedListener, BillingClientStateListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)

    private val billingClient: BillingClient = BillingClient.newBuilder(context)
        .setListener(this)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder()
                .enableOneTimeProducts()
                .build()
        )
        .enableAutoServiceReconnection()
        .build()

    private val _state = MutableStateFlow<SubscriptionState>(loadCachedState())
    val state: StateFlow<SubscriptionState> = _state

    /** Cached product details for showing prices in the purchase dialog. */
    @Volatile
    private var cachedProductDetails: ProductDetails? = null

    /** When [cachedProductDetails] last loaded successfully (elapsed realtime), 0 before that. */
    @Volatile
    private var productDetailsLoadedAt = 0L

    private fun productDetailsStale(): Boolean =
        productDetailsLoadedAt == 0L ||
            android.os.SystemClock.elapsedRealtime() - productDetailsLoadedAt > PRODUCT_DETAILS_MAX_AGE_MS

    private val _productDetailsVersion = MutableStateFlow(0)

    /**
     * Bumped whenever product details load, so the subscription card can switch to the trial wording
     * once Google has said this account is offered one (the card shows neutral wording until then).
     */
    val productDetailsVersion: StateFlow<Int> = _productDetailsVersion

    /**
     * Set of purchase tokens currently being acknowledged.
     *
     * Google Play's `PurchasesUpdatedListener` can fire multiple times for the same
     * purchase, and `refreshState()` also calls `acknowledge()` when it sees an
     * un-acknowledged purchase — but those overlap because the first ack is async.
     * Without de-duping, every successful purchase produced 1 OK + 3× "Server error"
     * warnings (observed in v1.3.54 logs as 1 OK + 3 fails for a single order).
     *
     * Tokens stay in the set until the ack callback fires (success or failure) so
     * a transient failure doesn't leak a token forever — the next refresh will retry.
     */
    private val acknowledgingTokens: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun connect() {
        if (billingClient.isReady) {
            scope.launch {
                refreshState()
                // Otherwise the offers load only when Billing connects: a failed first query left the card
                // on neutral wording for the whole process, and a cache from before a Play account switch
                // or a lapsed eligibility kept promising a trial.
                if (productDetailsStale()) queryProducts()
            }
            return
        }
        try {
            billingClient.startConnection(this)
        } catch (e: Exception) {
            SyncLog.w(TAG, "BillingClient connect failed: ${e.message}")
        }
    }

    override fun onBillingSetupFinished(result: BillingResult) {
        if (result.responseCode == BillingClient.BillingResponseCode.OK) {
            SyncLog.i(TAG, "BillingClient connected")
            scope.launch {
                queryProducts()
                refreshState()
            }
        } else {
            SyncLog.w(TAG, "BillingClient setup failed: ${result.debugMessage}")
        }
    }

    override fun onBillingServiceDisconnected() {
        // enableAutoServiceReconnection() (billing 8+) re-establishes the connection
        // on the next API call; a manual startConnection here would race it.
        SyncLog.d(TAG, "BillingClient disconnected (auto-reconnection armed)")
    }

    /**
     * Settings backups carry the direct version's subscription id; a Play subscription belongs to the
     * Google account and follows it by itself, so there is nothing to export.
     */
    fun subscriptionIdForBackup(): String? = null

    /** A direct version's subscription id means nothing to Google Play: never taken. */
    @Suppress("UNUSED_PARAMETER")
    fun restoreFromBackup(subId: String): Boolean = false

    /** Current cached state. Safe to call before BillingClient connects. */
    fun currentState(): SubscriptionState = _state.value

    /** Returns true when the cached state permits sync (Trial or Active). */
    fun isSyncAllowed(): Boolean = _state.value.isActive

    /** Whether Billing is connected; [BillingUi.showPaymentMessages] waits for it. */
    fun isBillingReady(): Boolean = billingClient.isReady

    suspend fun refreshState() {
        if (!billingClient.isReady) {
            SyncLog.d(TAG, "refreshState called before BillingClient ready, skipping")
            return
        }
        try {
            val result = billingClient.queryPurchasesAsync(purchasesQuery())
            if (result.billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
                SyncLog.w(TAG, "queryPurchasesAsync failed: ${result.billingResult.debugMessage}")
                return
            }
            updateState(stateFor(result.purchasesList))
        } catch (e: Exception) {
            SyncLog.w(TAG, "refreshState error: ${e.message}")
        }
    }

    /**
     * The subscriptions query, including suspended subscriptions (account hold, pause) where this Play Store can
     * return them. A Play Store that cannot answers FEATURE_NOT_SUPPORTED with no purchases at all, and refreshState
     * would then keep the last state for good: a lapsed subscription would go on syncing. Without the flag a
     * suspended subscription is simply missing and reads as expired, as before 2.0.4.
     */
    private fun purchasesQuery(): QueryPurchasesParams {
        val suspended = billingClient.isFeatureSupported(BillingClient.FeatureType.INCLUDE_SUSPENDED_SUBSCRIPTIONS)
            .responseCode == BillingClient.BillingResponseCode.OK
        return QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .includeSuspendedSubscriptions(suspended)
            .build()
    }

    /**
     * What [purchases] mean for sync ([PlayPurchaseResolution]). Acknowledges the purchase that grants sync if Google
     * still waits for that. A suspended purchase is never acknowledged: it was acknowledged when it was bought (an
     * unacknowledged one is refunded after three days, long before a renewal can fail), and it grants nothing.
     * Not a suspend function, so refreshState's coroutine stays small.
     */
    private fun stateFor(purchases: List<Purchase>): SubscriptionState {
        val owned = if (debugSuspendedFixture) PlayPurchaseResolution.DEBUG_SUSPENDED
            else purchases.map(PlayPurchaseResolution::ownedOf)
        // The debug stand-in never entitles, so an index always points into purchases.
        purchases.getOrNull(PlayPurchaseResolution.entitlingIndex(owned))?.let { purchase ->
            if (!purchase.isAcknowledged) acknowledge(purchase)
            return resolveActiveState(purchase)
        }
        val state = PlayPurchaseResolution.withoutEntitlement(owned, prefs.getString(SyncPreferences.KEY_SUBSCRIPTION_PRODUCT, null))
        if (state.suspended() && !_state.value.suspended()) {
            SyncLog.i(TAG, "Subscription suspended by Google Play (account hold or pause); sync off")
        }
        return state
    }

    private fun SubscriptionState.suspended() = this is SubscriptionState.Expired && suspended

    /**
     * Google Play's own message about a declined subscription payment, in the grace period or in account hold: a
     * snackbar in Google's words and the user's language, with the button that fixes the payment. Google decides
     * whether one is due and how often it shows. The app has no other way to see a grace period. Call it on the main
     * thread with Billing connected: otherwise the 8.3.0 library first waits up to 3 s for a reconnection on the
     * calling thread. A Play Store too old for it answers FEATURE_NOT_SUPPORTED, which is logged and ignored. Google
     * answers SUBSCRIPTION_STATUS_UPDATED when the status changed (a payment fixed from the message), and the
     * purchases are read again, so a recovered subscription turns sync back on without leaving the screen.
     */
    fun showPaymentMessages(activity: Activity) {
        if (!billingClient.isReady) return
        val params = InAppMessageParams.newBuilder()
            .addInAppMessageCategoryToShow(InAppMessageParams.InAppMessageCategoryId.TRANSACTIONAL)
            .build()
        val result = billingClient.showInAppMessages(activity, params) { answer ->
            if (answer.responseCode == InAppMessageResult.InAppMessageResponseCode.SUBSCRIPTION_STATUS_UPDATED) {
                SyncLog.i(TAG, "Google Play's payment message: subscription status updated; reading the purchases again")
                scope.launch { refreshState() }
            }
        }
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            SyncLog.d(TAG, "showInAppMessages: ${result.responseCode} ${result.debugMessage}")
        }
    }

    /**
     * Debug builds: `license-fixture suspended` makes every refresh read the purchases as Google returns a held or
     * paused subscription ([PlayPurchaseResolution.DEBUG_SUSPENDED]) until `license-fixture refresh`. That way the
     * card, the gate and the stored flag can be checked without a declined test card. It stays in place rather than
     * applying once, because every return to settings refreshes. In memory only: a new process reads the real
     * purchases. The real gate follows it, so sync is off meanwhile.
     */
    @Volatile
    private var debugSuspendedFixture = false

    fun debugApplyFixture(status: String) {
        if (!me.proton.sync.BuildConfig.DEBUG) return
        when (status) {
            "suspended" -> debugSuspendedFixture = true
            "refresh" -> debugSuspendedFixture = false
            else -> {
                SyncLog.w("DebugCommand", "license_fixture: unknown status '$status' in the play flavor (suspended | refresh)")
                return
            }
        }
        SyncLog.i("DebugCommand", "license_fixture: '$status'; reading the purchases again (skipped until Billing connects)")
        scope.launch { refreshState() }
    }

    private fun resolveActiveState(purchase: Purchase): SubscriptionState {
        val productId = purchase.products.firstOrNull() ?: PRODUCT_ID
        // No expiry: a Purchase carries no renewal date. Before 2.0.1 this stored now + 30 days, and the
        // card showed it as the renewal date, one Google never set. 0 is what persistState keeps for none.
        // Renewing or not, a PURCHASED purchase is Active: a cancelled one keeps its paid period until
        // Play drops it from queryPurchasesAsync.
        return SubscriptionState.Active(0L, productId)
    }

    private fun acknowledge(purchase: Purchase) {
        // Single-flight: skip if another caller is already acknowledging this exact
        // purchase token. The set is cleared in the callback so a transient failure
        // doesn't leak the token forever.
        if (!acknowledgingTokens.add(purchase.purchaseToken)) {
            SyncLog.d(TAG, "Acknowledge already in-flight for ${purchase.orderId}, skipping duplicate")
            return
        }
        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()
        billingClient.acknowledgePurchase(params) { result ->
            acknowledgingTokens.remove(purchase.purchaseToken)
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                SyncLog.i(TAG, "Acknowledged purchase ${purchase.orderId}")
            } else {
                SyncLog.w(TAG, "Acknowledge failed: ${result.debugMessage}")
            }
        }
    }

    suspend fun queryProducts(): ProductDetails? {
        if (!billingClient.isReady) return null
        val product = QueryProductDetailsParams.Product.newBuilder()
            .setProductId(PRODUCT_ID)
            .setProductType(BillingClient.ProductType.SUBS)
            .build()
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(listOf(product))
            .build()
        return try {
            val result = billingClient.queryProductDetails(params)
            if (result.billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
                SyncLog.w(TAG, "queryProductDetails failed: ${result.billingResult.debugMessage}")
                return null
            }
            val details = result.productDetailsList?.firstOrNull()
            cachedProductDetails = details
            productDetailsLoadedAt = android.os.SystemClock.elapsedRealtime()
            _productDetailsVersion.update { it + 1 }
            details
        } catch (e: Exception) {
            SyncLog.w(TAG, "queryProductDetails error: ${e.message}")
            null
        }
    }

    fun getCachedProductDetails(): ProductDetails? = cachedProductDetails

    /**
     * Launch the Play purchase sheet for the given base plan offer.
     * Result arrives in [onPurchasesUpdated].
     */
    fun launchPurchaseFlow(activity: Activity, productDetails: ProductDetails, offerToken: String) {
        val productParams = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(productDetails)
            .setOfferToken(offerToken)
            .build()
        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productParams))
            .build()
        val result = billingClient.launchBillingFlow(activity, flowParams)
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            SyncLog.w(TAG, "launchBillingFlow failed: ${result.debugMessage}")
        }
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                purchases?.forEach { purchase ->
                    // A suspended one is left to the refreshState launched below.
                    if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED && !purchase.isSuspended) {
                        if (!purchase.isAcknowledged) acknowledge(purchase)
                        updateState(resolveActiveState(purchase))
                    }
                }
                scope.launch {
                    refreshState()
                    // A purchase ends trial eligibility: reload the offers so nothing keeps promising one.
                    queryProducts()
                }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> {
                SyncLog.d(TAG, "Purchase canceled by user")
            }
            else -> {
                SyncLog.w(TAG, "Purchase failed: ${result.debugMessage}")
            }
        }
    }

    // The store first, then the flow. The gate (SubscriptionGate, the sync preflight, the event loop) reads
    // the stored state, and the scheduling observer acts as soon as the flow emits: emitting first let it
    // arm schedules and request syncs that the gate, still reading the old state, then declined.
    // Synchronized because there are two writers (onPurchasesUpdated on the main thread, refreshState on
    // IO), and they must not leave the flow and the store in different states.
    @Synchronized
    private fun updateState(newState: SubscriptionState) {
        persistState(newState)
        _state.value = newState
    }

    private fun persistState(s: SubscriptionState) {
        val editor = prefs.edit()
        PlayStateStore.write(editor, s, System.currentTimeMillis())
        editor.apply()
    }

    private fun loadCachedState(): SubscriptionState = PlayStateStore.read(prefs)

    companion object {
        private const val TAG = "SubscriptionManager"

        /** Product ID configured in Google Play Console. */
        const val PRODUCT_ID = "sync_provider_for_proton"

        /** Base plan IDs configured under the product in Play Console. */
        const val BASE_PLAN_MONTHLY = "monthly-auto"
        const val BASE_PLAN_ANNUAL = "yearly-auto"

        /** How old the cached offers may get before an app resume loads them again. */
        private const val PRODUCT_DETAILS_MAX_AGE_MS = 15 * 60 * 1000L

        // The device-wide sync gate now lives in [SubscriptionGate.isSyncAllowedCached]
        // (shared across flavors) so the play and direct implementations can't drift.
    }
}

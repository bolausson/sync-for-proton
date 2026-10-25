package me.proton.sync.billing

import android.app.Activity
import android.content.Context
import androidx.preference.PreferenceManager
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
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
 *  - a PURCHASED purchase, renewing or not          → [SubscriptionState.Active]
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

    suspend fun refreshState() {
        if (!billingClient.isReady) {
            SyncLog.d(TAG, "refreshState called before BillingClient ready — skipping")
            return
        }
        try {
            val params = QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.SUBS)
                .build()
            val result = billingClient.queryPurchasesAsync(params)
            if (result.billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
                SyncLog.w(TAG, "queryPurchasesAsync failed: ${result.billingResult.debugMessage}")
                return
            }

            val activePurchase = result.purchasesList.firstOrNull { p ->
                p.products.contains(PRODUCT_ID) &&
                    p.purchaseState == Purchase.PurchaseState.PURCHASED
            }

            val newState = when {
                activePurchase == null -> {
                    val had = prefs.getString(SyncPreferences.KEY_SUBSCRIPTION_PRODUCT, null)
                    if (had.isNullOrEmpty()) SubscriptionState.NoSubscription
                    else SubscriptionState.Expired(had)
                }
                else -> {
                    if (!activePurchase.isAcknowledged) acknowledge(activePurchase)
                    resolveActiveState(activePurchase)
                }
            }
            updateState(newState)
        } catch (e: Exception) {
            SyncLog.w(TAG, "refreshState error: ${e.message}")
        }
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
                    if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
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
        editor.apply()
    }

    private fun loadCachedState(): SubscriptionState {
        val name = prefs.getString(SyncPreferences.KEY_SUBSCRIPTION_STATE, null) ?: return SubscriptionState.Unknown
        val expiry = prefs.getLong(SyncPreferences.KEY_SUBSCRIPTION_EXPIRY, 0L)
        val productId = prefs.getString(SyncPreferences.KEY_SUBSCRIPTION_PRODUCT, null) ?: ""
        return when (name) {
            "Trial" -> SubscriptionState.Trial(expiry, productId)
            "Active" -> SubscriptionState.Active(expiry, productId)
            "Grace" -> SubscriptionState.Grace(expiry, productId)
            "Expired" -> SubscriptionState.Expired(productId)
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

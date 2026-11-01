package me.proton.sync.billing

import com.android.billingclient.api.Purchase

/**
 * What the Play purchases of this app's subscription mean for sync and for the settings card.
 *
 * Since 2.0.4 the purchases query also asks for suspended subscriptions (Billing 8.1's
 * includeSuspendedSubscriptions): one Google Play holds after a declined renewal payment once any grace period
 * is over (account hold), or one the subscriber paused. Without the flag Google leaves them out, and the card
 * called them expired, offered Renew (a new purchase) and hid Manage subscription, the way to Google's page
 * where the payment is fixed or the pause ended.
 *
 * A suspended purchase still reads PURCHASED: Billing 8.3.0's getPurchaseState returns PENDING for a pending
 * purchase and PURCHASED for every other. So only isSuspended keeps sync from running on it. Hold and pause look
 * the same on the phone, and both become [SubscriptionState.Expired] with [SubscriptionState.Expired.suspended].
 *
 * A grace period is not a suspension: Google returns that purchase as an ordinary one and grants access
 * meanwhile, so it stays Active. Only Google's in-app message ([SubscriptionManager.showPaymentMessages])
 * mentions it.
 */
object PlayPurchaseResolution {

    /** What the resolution reads of a Purchase. */
    data class Owned(val products: List<String>, val purchaseState: Int, val suspended: Boolean)

    fun ownedOf(purchase: Purchase) = Owned(purchase.products, purchase.purchaseState, purchase.isSuspended)

    /**
     * The index of the purchase that grants sync, or -1: this app's subscription, PURCHASED and not suspended.
     * A repurchase during account hold leaves the held purchase beside the new one, and the new one wins
     * wherever it is in the list.
     */
    fun entitlingIndex(owned: List<Owned>): Int = owned.indexOfFirst {
        SubscriptionManager.PRODUCT_ID in it.products && it.purchaseState == Purchase.PurchaseState.PURCHASED && !it.suspended
    }

    /**
     * The state when nothing grants sync ([entitlingIndex] is -1): suspended when Google returned this app's
     * subscription suspended, else expired when this install had one ([hadProduct]), else none. A suspended
     * subscription counts even without [hadProduct], as after a reinstall: it is still the user's, and the card
     * has to send them to Google's page instead of offering a trial.
     */
    fun withoutEntitlement(owned: List<Owned>, hadProduct: String?): SubscriptionState = when {
        owned.any { SubscriptionManager.PRODUCT_ID in it.products && it.suspended } ->
            SubscriptionState.Expired(SubscriptionManager.PRODUCT_ID, suspended = true)
        hadProduct.isNullOrEmpty() -> SubscriptionState.NoSubscription
        else -> SubscriptionState.Expired(hadProduct)
    }

    /** Debug builds' `license-fixture suspended`: what Google returns for a held or paused subscription. Never entitles. */
    val DEBUG_SUSPENDED = listOf(Owned(listOf(SubscriptionManager.PRODUCT_ID), Purchase.PurchaseState.PURCHASED, suspended = true))
}

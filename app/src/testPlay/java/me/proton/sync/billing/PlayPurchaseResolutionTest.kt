package me.proton.sync.billing

import com.android.billingclient.api.Purchase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What Google Play's purchases mean for sync: a held or paused subscription (Purchase.isSuspended) must never let
 * sync run, although Billing 8.3.0 reads it PURCHASED, and the card must still know it is the user's. Built from
 * real Purchase objects, parsed from the JSON Google sends, so the library's own reading is what is tested.
 */
class PlayPurchaseResolutionTest {

    private val p = SubscriptionManager.PRODUCT_ID

    /** Raw purchaseState 0 is purchased, 4 is pending; no order id, which the source snapshot would refuse as a real one. */
    private fun purchase(product: String = p, raw: Int = 0, suspended: Boolean? = false): Purchase {
        val suspendedField = if (suspended == null) "" else ",\"suspended\":$suspended"
        return Purchase(
            "{\"packageName\":\"me.proton.sync\",\"productIds\":[\"$product\"],\"purchaseTime\":1,\"purchaseState\":$raw," +
                "\"purchaseToken\":\"token-$product-$raw-$suspended\",\"quantity\":1,\"autoRenewing\":true," +
                "\"acknowledged\":true$suspendedField}",
            "",
        )
    }

    private fun owned(vararg purchases: Purchase) = purchases.map(PlayPurchaseResolution::ownedOf)

    @Test
    fun `a suspended purchase reads PURCHASED, so only isSuspended keeps sync off`() {
        val held = PlayPurchaseResolution.ownedOf(purchase(suspended = true))
        assertEquals(Purchase.PurchaseState.PURCHASED, held.purchaseState)
        assertTrue(held.suspended)
        assertEquals(-1, PlayPurchaseResolution.entitlingIndex(listOf(held)))
        val state = PlayPurchaseResolution.withoutEntitlement(listOf(held), hadProduct = p)
        assertEquals(SubscriptionState.Expired(p, suspended = true), state)
        assertFalse(state.isActive)
    }

    @Test
    fun `an ordinary purchase entitles, and a repurchase during a hold wins wherever it is`() {
        assertEquals(0, PlayPurchaseResolution.entitlingIndex(owned(purchase())))
        assertEquals(1, PlayPurchaseResolution.entitlingIndex(owned(purchase(suspended = true), purchase())))
    }

    @Test
    fun `another product's purchase, suspended or not, neither entitles nor suspends`() {
        val other = owned(purchase(product = "other_product"), purchase(product = "other_product", suspended = true))
        assertEquals(-1, PlayPurchaseResolution.entitlingIndex(other))
        assertEquals(SubscriptionState.Expired("x"), PlayPurchaseResolution.withoutEntitlement(other, hadProduct = "x"))
        assertEquals(SubscriptionState.NoSubscription, PlayPurchaseResolution.withoutEntitlement(other, hadProduct = null))
    }

    @Test
    fun `a pending purchase does not entitle`() {
        assertEquals(-1, PlayPurchaseResolution.entitlingIndex(owned(purchase(raw = 4))))
    }

    @Test
    fun `no purchases give none or expired, never suspended`() {
        assertEquals(SubscriptionState.NoSubscription, PlayPurchaseResolution.withoutEntitlement(emptyList(), hadProduct = null))
        assertEquals(SubscriptionState.NoSubscription, PlayPurchaseResolution.withoutEntitlement(emptyList(), hadProduct = ""))
        assertEquals(SubscriptionState.Expired("x"), PlayPurchaseResolution.withoutEntitlement(emptyList(), hadProduct = "x"))
    }

    @Test
    fun `a held subscription after a reinstall is the user's, not a new trial`() {
        assertEquals(
            SubscriptionState.Expired(p, suspended = true),
            PlayPurchaseResolution.withoutEntitlement(owned(purchase(suspended = true)), hadProduct = null),
        )
    }

    @Test
    fun `a purchase from a Play Store that sends no suspended field is not suspended`() {
        val old = PlayPurchaseResolution.ownedOf(purchase(suspended = null))
        assertFalse(old.suspended)
        assertEquals(0, PlayPurchaseResolution.entitlingIndex(listOf(old)))
    }

    @Test
    fun `the debug stand-in never entitles and reads as suspended`() {
        assertEquals(-1, PlayPurchaseResolution.entitlingIndex(PlayPurchaseResolution.DEBUG_SUSPENDED))
        assertEquals(
            SubscriptionState.Expired(p, suspended = true),
            PlayPurchaseResolution.withoutEntitlement(PlayPurchaseResolution.DEBUG_SUSPENDED, hadProduct = null),
        )
    }
}

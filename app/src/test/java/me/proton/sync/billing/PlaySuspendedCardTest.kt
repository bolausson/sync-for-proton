package me.proton.sync.billing

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A Google Play subscription on hold or paused (Purchase.isSuspended). Up to 2.0.3 Google left it out of the
 * purchases, so the card called it expired, offered Renew and hid Manage subscription, the one way to Google's
 * page where the payment is fixed or the pause ended. The code needs an Activity or Play Billing, so its source is
 * checked, as in [SubscriptionCardWordingTest]; the decisions themselves are tested in testPlay
 * (PlayPurchaseResolutionTest, PlayStateStoreTest).
 */
class PlaySuspendedCardTest {

    private fun source(path: String) = File("src/$path").readText().replace("\r\n", "\n")

    private fun function(source: String, signature: String) =
        source.substringAfter(signature).substringBefore("\n    }\n")

    @Test
    fun `the suspended card offers Manage subscription only`() {
        val banner = function(source("main/java/me/proton/sync/settings/SyncSettingsActivity.kt"), "private fun updateSubscriptionBanner(")
        val branch = banner.substringAfter("is SubscriptionState.Expired -> if (state.suspended) {").substringBefore("} else if (state.paused) {")
        assertTrue(banner, banner.contains("is SubscriptionState.Expired -> if (state.suspended) {"))
        assertTrue(branch, branch.contains("BillingUi.suspendedStatusText(this)"))
        assertTrue(branch, branch.contains("btnSubscriptionAction.visibility = View.GONE"))
        assertTrue(branch, branch.contains("btnSubscriptionManage.visibility = View.VISIBLE"))
        for (no in listOf("startPurchase(", "btn_renew", "showEnterIdButton(")) assertFalse(branch, branch.contains(no))
    }

    @Test
    fun `a return to settings checks a suspended subscription again and asks for Google's payment message`() {
        val settings = source("main/java/me/proton/sync/settings/SyncSettingsActivity.kt")
        val resume = function(settings, "override fun onResume(")
        assertTrue(resume, resume.contains("sub.paused || sub.paymentFailed || sub.suspended"))
        assertTrue(resume, resume.contains("BillingUi.showPaymentMessages(this@SyncSettingsActivity, subscriptionManager)"))
        assertTrue(function(settings, "override fun onPause("), function(settings, "override fun onPause(").contains("paymentMessages?.cancel()"))
    }

    @Test
    fun `the play manager asks for suspended purchases only where Google can answer, and never entitles one`() {
        val manager = source("play/java/me/proton/sync/billing/SubscriptionManager.kt")
        val query = function(manager, "private fun purchasesQuery(")
        assertTrue(query, query.contains("isFeatureSupported(BillingClient.FeatureType.INCLUDE_SUSPENDED_SUBSCRIPTIONS)"))
        assertTrue(query, query.contains("includeSuspendedSubscriptions(suspended)"))
        assertTrue(function(manager, "override fun onPurchasesUpdated("), manager.contains("Purchase.PurchaseState.PURCHASED && !purchase.isSuspended"))
        val messages = function(manager, "fun showPaymentMessages(")
        assertTrue(messages, messages.contains("if (!billingClient.isReady) return"))
        assertTrue(messages, messages.contains("InAppMessageCategoryId.TRANSACTIONAL"))
    }

    @Test
    fun `Google's message waits for the window, and the direct version has none`() {
        val play = function(source("play/java/me/proton/sync/billing/BillingUi.kt"), "suspend fun showPaymentMessages(")
        assertTrue(play, play.contains(".post {"))
        assertTrue(play, play.contains("Lifecycle.State.RESUMED"))
        val direct = source("direct/java/me/proton/sync/billing/BillingUi.kt")
        assertTrue(direct.contains("suspend fun showPaymentMessages(activity: SyncSettingsActivity, sm: SubscriptionManager) = Unit"))
        assertTrue(direct.contains("fun suspendedStatusText(context: Context): String? = null"))
    }
}

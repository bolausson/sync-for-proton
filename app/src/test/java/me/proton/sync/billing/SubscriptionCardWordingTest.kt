package me.proton.sync.billing

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The subscription card says what its buttons do. In the direct version "Start 7-day free trial" opened a dialog
 * that asked for a subscription id, whose "Subscribe in browser" was the real way to start; Upgrade on a Paddle
 * trial could start a second subscription; and nothing on the screen said sync was off. The play card keeps its
 * buttons. The code needs an Activity or Play Billing, so its source is checked, as in [ActiveStatusLineTest].
 */
class SubscriptionCardWordingTest {

    private fun source(path: String) = File("src/$path").readText().replace("\r\n", "\n")

    private fun function(source: String, signature: String) =
        source.substringAfter(signature).substringBefore("\n    }\n")

    private fun string(path: String, name: String): String? =
        Regex("<string name=\"$name\">(.*?)</string>").find(source(path))?.groupValues?.get(1)

    @Test
    fun `the play card opens the plan picker and shows no subscription id button`() {
        val play = source("play/java/me/proton/sync/billing/BillingUi.kt")
        assertTrue(play.contains("fun startPurchase(activity: SyncSettingsActivity, sm: SubscriptionManager) = showPurchaseOptions(activity, sm)"))
        assertTrue(play.contains("fun enterIdLabel(context: Context): String? = null"))
        assertTrue(play.contains("fun upgradeLabel(context: Context): String? = context.getString(R.string.btn_upgrade)"))
    }

    @Test
    fun `the direct trial button opens the checkout and the dialog only takes an id`() {
        val direct = source("direct/java/me/proton/sync/billing/BillingUi.kt")
        val start = function(direct, "fun startPurchase(")
        assertTrue(start, start.contains("openUrl(activity, SubscriptionManager.CHECKOUT_URL)"))
        assertTrue(direct.contains("fun upgradeLabel(context: Context): String? = null"))
        assertFalse(direct.contains("setNeutralButton"))
        assertFalse(direct.contains("spp_subscribe_open_checkout"))
    }

    @Test
    fun `every purchase button on the card goes through startPurchase`() {
        val banner = function(
            source("main/java/me/proton/sync/settings/SyncSettingsActivity.kt"),
            "private fun updateSubscriptionBanner("
        )
        assertFalse(banner, banner.contains("showPurchaseOptions("))
        assertTrue(banner, banner.contains("BillingUi.startPurchase("))
        assertTrue(banner, banner.contains("trialDaysLeft("))
        assertTrue(banner, banner.contains("btnSubscriptionEnterId.visibility = View.GONE"))
    }

    /**
     * The direct card's Enter subscription ID: where sync is off (no subscription, paused, unpaid, expired) and on
     * the trial, whose dialog shows the id a second phone needs; never on the active or grace cards. The play
     * version has no label for it ([enterIdLabel] is null there), so its cards keep their buttons.
     */
    @Test
    fun `the subscription id button is on the trial and every sync-off card, not on the active ones`() {
        val banner = function(
            source("main/java/me/proton/sync/settings/SyncSettingsActivity.kt"),
            "private fun updateSubscriptionBanner("
        )
        fun branch(from: String, to: String) = banner.substringAfter(from).substringBefore(to)
        val call = "showEnterIdButton(card)"
        val none = branch("SubscriptionState.NoSubscription -> ", "is SubscriptionState.Trial -> ")
        val trial = branch("is SubscriptionState.Trial -> ", "is SubscriptionState.Active -> ")
        val active = branch("is SubscriptionState.Active -> ", "is SubscriptionState.Grace -> ")
        val grace = branch("is SubscriptionState.Grace -> ", "is SubscriptionState.Expired -> ")
        val expired = banner.substringAfter("is SubscriptionState.Expired -> ")
        assertTrue(none, none.contains(call))
        assertTrue(trial, trial.contains(call))
        assertFalse(active, active.contains(call))
        assertFalse(grace, grace.contains(call))
        val unpaid = expired.substringAfter("else if (state.paymentFailed) {").substringBefore("} else {")
        assertTrue(unpaid, unpaid.contains(call))
        // Paused, unpaid and expired.
        assertTrue(expired, expired.split(call).size - 1 == 3)
    }

    @Test
    fun `the hero says sync is off after nothing enabled and before waiting or attention`() {
        val hero = function(
            source("main/java/me/proton/sync/settings/SyncSettingsActivity.kt"),
            "private fun updateHeroStatus("
        )
        val nothing = hero.indexOf("if (nothingEnabled)")
        val gate = hero.indexOf("subscriptionManager.isSyncAllowed()")
        val waiting = hero.indexOf("val awaitingFirstSync")
        val attention = hero.indexOf("if (needsAttention)")
        assertTrue(hero, nothing >= 0 && nothing < gate && gate < waiting && waiting < attention)
    }

    @Test
    fun `the English wording says sync is off, without a dash`() {
        val main = "main/res/values/strings.xml"
        val direct = "direct/res/values/strings.xml"
        assertTrue(string(main, "label_subscription_none")!!.startsWith("Sync is off"))
        assertTrue(string(main, "label_subscription_required")!!.startsWith("Sync is off"))
        val message = string(direct, "spp_subscribe_dialog_message")
        assertNotNull(message)
        assertTrue(message, message!!.contains("sub_") && message.contains("Google Play"))

        val trialDays = Regex("<plurals name=\"label_subscription_trial_days\">(.*?)</plurals>", RegexOption.DOT_MATCHES_ALL)
            .find(source(main))?.groupValues?.get(1)
        assertNotNull(trialDays)
        val texts = listOf(
            string(main, "label_subscription_none"),
            string(main, "label_subscription_required"),
            string(main, "setup_choose_saved_inactive"),
            string(main, "sfp_hero_sync_off"),
            string(main, "sfp_hero_subtitle_sync_off"),
            string(direct, "spp_btn_enter_subscription_id"),
            string(direct, "spp_subscribe_dialog_title"),
            message,
            trialDays,
        )
        for (text in texts) {
            assertNotNull(text)
            assertFalse(text, text!!.contains('—') || text.contains('–'))
        }
    }

    @Test
    fun `the old trial line is gone from every language`() {
        val dirs = File("src/main/res").listFiles()!!
            .filter { it.name == "values" || it.name.startsWith("values-") }
            .filter { File(it, "strings.xml").isFile }
        assertTrue(dirs.size >= 25)
        for (dir in dirs) {
            assertFalse(dir.name, File(dir, "strings.xml").readText().contains("name=\"label_subscription_trial\""))
        }
    }
}

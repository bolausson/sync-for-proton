package me.proton.sync.billing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The subscription card's line for an active subscription. A Google Play purchase tells the app no renewal date,
 * and before 2.0.1 the play version stored now + 30 days and showed it as "Active, renews <date>", which told a
 * yearly subscriber of a renewal a month away. The play version now keeps no date and its line names Google Play;
 * the direct version keeps Paddle's date. That code needs Play Billing or an Activity, so its source is checked.
 */
class ActiveStatusLineTest {

    private fun source(path: String) = File("src/$path").readText().replace("\r\n", "\n")

    @Test
    fun `the play version makes up no renewal date`() {
        val resolve = source("play/java/me/proton/sync/billing/SubscriptionManager.kt")
            .substringAfter("private fun resolveActiveState(").substringBefore("\n    }\n")
        assertTrue(resolve, resolve.contains("SubscriptionState.Active(0L, productId)"))
        assertFalse(resolve, resolve.contains("currentTimeMillis"))
    }

    @Test
    fun `the play card names Google Play and the direct card keeps Paddle's date`() {
        assertTrue(
            source("play/java/me/proton/sync/billing/BillingUi.kt").contains(
                "fun activeStatusText(context: Context): String? = context.getString(R.string.label_subscription_active_play)"
            )
        )
        assertTrue(
            source("direct/java/me/proton/sync/billing/BillingUi.kt")
                .contains("fun activeStatusText(context: Context): String? = null")
        )
        val active = source("main/java/me/proton/sync/settings/SyncSettingsActivity.kt")
            .substringAfter("private fun updateSubscriptionBanner(").substringBefore("\n    }\n")
            .substringAfter("is SubscriptionState.Active -> ").substringBefore("is SubscriptionState.Grace -> ")
        val seam = active.indexOf("BillingUi.activeStatusText(this)")
        assertTrue(active, seam >= 0 && seam < active.indexOf("R.string.label_subscription_active,"))
    }

    @Test
    fun `every language has the play line, without a date or a dash`() {
        val languages = File("src/main/res").listFiles()!!
            .filter { it.name == "values" || it.name.startsWith("values-") }
            .filter { File(it, "strings.xml").let { xml -> xml.isFile && xml.readText().contains("name=\"label_subscription_active\"") } }
        assertEquals(25, languages.size)
        for (dir in languages) {
            val text = Regex("<string name=\"label_subscription_active_play\">(.*?)</string>")
                .find(File(dir, "strings.xml").readText())?.groupValues?.get(1)
            assertNotNull(dir.name, text)
            assertTrue("${dir.name}: $text", text!!.contains("Google Play"))
            assertFalse("${dir.name}: $text", text.contains('%') || text.contains('—') || text.contains('–'))
        }
    }
}

package me.proton.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The strings 2.0.2 added or reworded exist once in every language, in the main set and in the direct flavour's
 * subscription set, and none of them has an em or en dash. A language without one falls back to English on that
 * screen, which lint reports only as a warning. The keys 2.0.2 removed stay removed. The files are read as in
 * [me.proton.sync.billing.ActiveStatusLineTest]; app/build.gradle.kts declares both sets as test inputs, so an
 * edited translation alone re-runs this test.
 */
class ReleaseStringsTest {

    private companion object {
        /** U+2014 and U+2013, written by code point so this source holds neither character. */
        val EM_DASH = Char(0x2014)
        val EN_DASH = Char(0x2013)
    }

    private val mainKeys = listOf(
        "error_login_verification_refused",
        "error_login_account_disabled",
        "error_login_sso_unsupported",
        "error_login_untrusted_server",
        "error_login_proton_refused",
        "error_login_unexpected",
        "label_subscription_none",
        "label_subscription_trial_days",
        "label_subscription_required",
        "setup_choose_saved_inactive",
        "sfp_hero_sync_off",
        "sfp_hero_subtitle_sync_off",
    )

    private val directKeys = listOf(
        "spp_btn_enter_subscription_id",
        "spp_subscribe_dialog_title",
        "spp_subscribe_dialog_message",
        "spp_subscribe_activated",
        "spp_subscribe_error_not_found",
        "spp_subscribe_error_wrong_product",
        "spp_subscribe_error_cancelled",
        "spp_subscribe_error_kept_current",
        "spp_subscribe_error_play_order",
        "spp_subscribe_error_transaction_mistyped",
        "spp_subscribe_error_transaction_not_found",
        "spp_subscribe_error_transaction_wrong_product",
        "spp_subscribe_error_transaction_not_ready",
        "spp_subscribe_error_email",
        "spp_subscribe_error_mistyped",
        "spp_subscribe_error_not_an_id",
        "spp_subscribe_error_several_ids",
    )

    // spp_subscribe_error_transaction said a transaction id was not a subscription id; the dialog takes one now, and
    // the transaction answers have their own keys.
    private val removedKeys = listOf("label_subscription_trial", "spp_subscribe_open_checkout", "spp_subscribe_error_transaction")

    /** values plus every values-xx that has a strings.xml: English and the 24 translations. */
    private fun languages(sourceSet: String): List<File> =
        File("src/$sourceSet/res").listFiles()!!
            .filter { it.name == "values" || Regex("values-[a-z]{2}").matches(it.name) }
            .map { File(it, "strings.xml") }
            .filter { it.isFile }
            .sortedBy { it.parentFile!!.name }

    private fun label(xml: File) = "${xml.parentFile!!.parentFile!!.parentFile!!.name}/${xml.parentFile!!.name}"

    /** The text of the string or plurals named [key], or null; fails when the name is declared more than once. */
    private fun value(xml: File, key: String): String? {
        val text = xml.readText()
        val declarations = Regex("<(string|plurals) name=\"${Regex.escape(key)}\"").findAll(text).count()
        assertTrue("${label(xml)}: $key is declared $declarations times", declarations <= 1)
        return Regex("<(string|plurals) name=\"${Regex.escape(key)}\"[^>]*>(.*?)</\\1>", RegexOption.DOT_MATCHES_ALL)
            .find(text)?.groupValues?.get(2)
    }

    private fun assertComplete(sourceSet: String, keys: List<String>) {
        val files = languages(sourceSet)
        assertEquals(files.joinToString { label(it) }, 25, files.size)
        for (xml in files) {
            for (key in keys) {
                val text = value(xml, key)
                assertNotNull("${label(xml)}: $key is missing", text)
                assertTrue("${label(xml)}: $key is empty", text!!.isNotBlank())
                assertFalse(
                    "${label(xml)}: $key has an em or en dash: $text",
                    text.contains(EM_DASH) || text.contains(EN_DASH),
                )
            }
        }
    }

    @Test
    fun `every language has the main strings, without a dash`() = assertComplete("main", mainKeys)

    @Test
    fun `every language has the direct subscription strings, without a dash`() = assertComplete("direct", directKeys)

    @Test
    fun `every plural form of the trial line shows the day count`() {
        for (xml in languages("main")) {
            val items = Regex("<item quantity=\"(\\w+)\">(.*?)</item>")
                .findAll(value(xml, "label_subscription_trial_days")!!).toList()
            assertTrue("${label(xml)}: no quantities", items.any { it.groupValues[1] == "other" })
            for (item in items) {
                assertTrue("${label(xml)}: ${item.groupValues[1]}: ${item.groupValues[2]}", item.groupValues[2].contains("%1\$d"))
            }
        }
    }

    @Test
    fun `the removed strings stay removed`() {
        for (xml in languages("main") + languages("direct")) {
            val text = xml.readText()
            for (key in removedKeys) {
                assertFalse("${label(xml)}: $key is back", text.contains("name=\"$key\""))
            }
        }
    }
}

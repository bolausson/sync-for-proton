package me.proton.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The strings 2.0.2 to 2.0.4 added or reworded, and the ones a later release put on screen, exist once in every language,
 * in the main set and in the direct flavour's subscription set, and none of them has an em or en dash. A language without
 * one falls back to English on that screen, which lint reports only as a warning. The keys 2.0.2, 2.0.4 and 2.2.0
 * removed stay removed. The files are read as in
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
        "label_subscription_suspended_play",
        "info_verbose_logging_body",
        "info_file_logging_body",
        "backup_result_done",
        "backup_result_partial",
        // 2.2.0: the interval row, its info dialog and its picker read "Sync interval".
        "sync_interval_title",
        // 2.2.0: the Default calendar row with its dialogs and toasts, a new event held in a shared calendar (its
        // notification, buttons and info text), and the reworded calendar sync info.
        "label_default_calendar",
        "info_default_calendar_body",
        "default_calendar_connecting",
        "default_calendar_changed",
        "default_calendar_unreachable",
        "default_calendar_refused",
        "default_calendar_none_own",
        "default_calendar_incomplete",
        "notification_new_event_hold_title",
        "notification_new_event_hold_body",
        "notification_new_event_hold_body_unknown_app",
        "action_new_event_move",
        "action_new_event_publish",
        "info_new_event_holds_body",
        "info_sync_calendar_body",
        // 2.2.0: Android's automatic sync switch (the overview line while it is off, the interval and Fallback full
        // sync texts, the Backups switch with its info text and skipped line), and the reworded Remove older events
        // info; info_verbose_logging_body, reworded too, is pinned above.
        "sfp_auto_sync_off",
        "sfp_event_loop_subtitle",
        "info_event_loop_body",
        "info_fallback_full_sync_body",
        "backup_when_auto_sync_off_label",
        "info_backup_when_auto_sync_off_body",
        "backup_last_skipped_auto_sync_off",
        "info_trim_old_events_body",
        // 2.2.0: the four failure texts that say "Tap Sync to try again." while Android retries no failed sync.
        "error_no_internet_tap_sync",
        "error_too_many_requests_tap_sync",
        "error_server_error_tap_sync",
        "error_connection_interrupted_tap_sync",
        // 2.2.0: the lasting notices of the contact and event deletions Proton refused three times in a row.
        "notification_contacts_delete_refused_title",
        "notification_contacts_delete_refused_summary",
        "notification_events_delete_refused_title",
        "notification_events_delete_refused_summary",
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
        "spp_subscribe_error_too_long",
        "spp_manage_note",
    )

    // spp_subscribe_error_transaction said a transaction id was not a subscription id; the dialog takes one now, and
    // the transaction answers have their own keys.
    private val removedKeys = listOf(
        "label_subscription_trial", "spp_subscribe_open_checkout", "spp_subscribe_error_transaction",
        // 2.0.4: three counts in one string; a template with three plurals replaces each.
        "backup_result_success", "backup_result_incomplete",
        // 2.2.0: the event loop is gone; the interval row reads sync_interval_title.
        "label_event_loop_interval", "dialog_title_event_loop_interval",
        // 2.2.0, before release: a skipped scheduled backup has a line of its own (backup_last_skipped_auto_sync_off)
        // instead of a result that took the last backup's place.
        "backup_result_skipped_auto_sync_off",
    )

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
    fun `no language names the seller or quotes a Manage subscription link Paddle's email does not have`() {
        // Checked against a real Paddle email (2.0.3): "Manage subscription" is a heading there, in the customer's
        // language, over a payment-method link and a cancel link; and the seller's display name is no longer BEO.
        for (xml in languages("direct")) {
            for (key in listOf("spp_subscribe_dialog_message", "spp_manage_note")) {
                val text = value(xml, key)!!
                assertFalse("${label(xml)}: $key names BEO: $text", text.contains("BEO"))
                assertFalse("${label(xml)}: $key quotes Manage subscription: $text", text.contains("Manage subscription"))
            }
            val message = value(xml, "spp_subscribe_dialog_message")!!
            for (kept in listOf("sub_", "txn_", "Paddle", "Google Play")) {
                assertTrue("${label(xml)}: the dialog lost $kept: $message", message.contains(kept))
            }
        }
    }

    /**
     * A quote opened with „ closes with a typographic mark (“ or ”). An unescaped ASCII " is Android's own quoting
     * character: the resource compiler drops it, and the screen showed an opened quote that never closed (15 texts
     * until 2.0.4). \u escapes are read decoded, as values-et writes them.
     */
    @Test
    fun `no quote opened with the low mark closes with an ASCII quote`() {
        val escape = Regex("\\\\u([0-9a-fA-F]{4})")
        val open = Char(0x201E)
        val bad = Regex("$open[^\"“”<]*?(?<!\\\\)\"")
        for (xml in languages("main") + languages("direct")) {
            val text = escape.replace(xml.readText()) { it.groupValues[1].toInt(16).toChar().toString() }
            val found = bad.findAll(text).map { it.value }.toList()
            assertTrue("${label(xml)}: $found", found.isEmpty())
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

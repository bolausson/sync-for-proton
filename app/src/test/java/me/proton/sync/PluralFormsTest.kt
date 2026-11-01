package me.proton.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every count the app shows is a plural with the forms its language has. Until 2.0.4 nineteen strings put a number
 * before a noun ("1 contacts are no longer on Proton", "Removed 1 event(s)"), and lint found plurals missing a form
 * Android picks (Spanish, French, Italian and Portuguese "many", Maltese "two"). The categories are CLDR's, as
 * Android's ICU uses them; an item without the number would print the word for one where the language's "one" also
 * covers 21 or 0.
 */
class PluralFormsTest {

    private val categories = mapOf(
        "values" to "one other", "bg" to "one other", "cs" to "one few many other", "da" to "one other",
        "de" to "one other", "el" to "one other", "es" to "one many other", "et" to "one other", "fi" to "one other",
        "fr" to "one many other", "ga" to "one two few many other", "hr" to "one few other", "hu" to "one other",
        "it" to "one many other", "lt" to "one few many other", "lv" to "zero one other",
        "mt" to "one two few many other", "nb" to "one other", "nl" to "one other", "pl" to "one few many other",
        "pt" to "one many other", "ro" to "one few other", "sk" to "one few many other", "sl" to "one two few other",
        "sv" to "one other",
    ).mapValues { it.value.split(" ").toSet() }

    /** The strings 2.0.4 turned into plurals, and the two the backup counts replaced. */
    private val converted = listOf(
        "dialog_title_full_resync_all", "label_days_format", "notification_bulk_delete_body",
        "remote_deletion_body_contacts", "remote_deletion_body_calendar", "contact_filter_removal_body", "label_log_count",
        "name_repair_found", "name_repair_done", "name_repair_partial", "contact_filter_preview_result",
        "contact_filter_preview_undecided", "contact_filter_preview_server_result", "contact_filter_preview_and_more",
        "contact_filter_preview_unreadable", "remove_synced_calendar_done", "remove_synced_contacts_done",
    )
    private val added = listOf("label_minutes_format", "backup_count_calendars", "backup_count_events", "backup_count_contacts")
    private val removed = listOf("backup_result_success", "backup_result_incomplete")

    private val plurals = Regex("<plurals name=\"([^\"]+)\"[^>]*>(.*?)</plurals>", RegexOption.DOT_MATCHES_ALL)
    private val item = Regex("<item quantity=\"(\\w+)\">(.*?)</item>", RegexOption.DOT_MATCHES_ALL)
    private val number = Regex("%(\\d+\\$)?d")

    private fun files(): List<Pair<String, File>> = listOf("main", "direct").flatMap { set ->
        File("src/$set/res").listFiles()!!
            .filter { it.name == "values" || Regex("values-[a-z]{2}").matches(it.name) }
            .map { (if (it.name == "values") "values" else it.name.removePrefix("values-")) to File(it, "strings.xml") }
            .filter { it.second.isFile }
    }

    @Test
    fun `every plural has its language's forms, each with the number`() {
        val problems = mutableListOf<String>()
        for ((lang, xml) in files()) {
            val expected = categories[lang]
            if (expected == null) {
                problems += "no categories listed for $lang"
                continue
            }
            for (p in plurals.findAll(xml.readText())) {
                val items = item.findAll(p.groupValues[2]).map { it.groupValues[1] to it.groupValues[2] }.toList()
                val got = items.map { it.first }.toSet()
                if (got != expected) problems += "${xml.parentFile!!.name} ${p.groupValues[1]}: $got, expected $expected"
                for ((q, text) in items) if (!number.containsMatchIn(text)) problems += "${xml.parentFile!!.name} ${p.groupValues[1]} [$q] has no number: $text"
            }
        }
        assertTrue(problems.joinToString("\n", "${problems.size} problems:\n"), problems.isEmpty())
    }

    @Test
    fun `every language names its own plural rules, and every plural goes through Plurals`() {
        // Plurals.text picks the form by the language a text is in, which each file names; a wrong name would pick
        // English forms for a translated text, or the phone's for English.
        for ((lang, xml) in files().filter { it.second.path.replace('\\', '/').contains("src/main/") }) {
            val named = Regex("<string name=\"plural_rules_language\">([^<]*)</string>").find(xml.readText())?.groupValues?.get(1)
            assertEquals(xml.path, if (lang == "values") "en" else lang, named)
        }
        val around = listOf("main", "direct", "play").flatMap { set ->
            File("src/$set/java").walkTopDown().filter { it.isFile && it.name.endsWith(".kt") && it.name != "Plurals.kt" }
                .filter { it.readText().contains("getQuantityString(") }.map { it.path }.toList()
        }
        assertTrue("getQuantityString outside Plurals.kt: $around", around.isEmpty())
    }

    @Test
    fun `the counted strings are plurals in every language, and no longer strings`() {
        val main = files().filter { it.second.path.replace('\\', '/').contains("src/main/") }
        assertEquals(25, main.size)
        for ((_, xml) in main) {
            val text = xml.readText()
            for (key in converted + added) {
                assertTrue("${xml.parentFile!!.name}: $key is not a plural", text.contains("<plurals name=\"$key\">"))
                assertTrue("${xml.parentFile!!.name}: $key is still a string", !text.contains("<string name=\"$key\">"))
            }
            for (key in removed) assertTrue("${xml.parentFile!!.name}: $key is back", !text.contains("name=\"$key\""))
        }
    }
}

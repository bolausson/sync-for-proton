package me.proton.sync.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The settings screen keeps none of Android's saved view state.
 *
 * Every control on it is drawn from the stored settings, the account cards again on every resume. Restored after a
 * rotation, a language or dark mode change or process death, the saved view state set each switch to its saved position
 * first, and the switch's listener stored that as a choice. The account cards share view ids, so the last card's
 * switches went to every account (contacts and calendar sync among them), and a position saved before a change made
 * elsewhere undid that change.
 */
class SettingsViewStateTest {

    private val activity = File("src/main/java/me/proton/sync/settings/SyncSettingsActivity.kt").readLines()

    /** The trimmed lines of the member function that starts with [signature], up to its closing brace. */
    private fun body(signature: String): List<String> {
        val start = activity.indexOfFirst { it.trim().startsWith(signature) }
        assertTrue("$signature not found", start >= 0)
        val end = (start + 1 until activity.size).first { activity[it] == "    }" }
        return activity.subList(start, end).map { it.trim() }
    }

    @Test
    fun `onCreate leaves the settings container out of the saved view state`() {
        assertTrue(
            body("override fun onCreate(savedInstanceState: Bundle?)")
                .contains("binding.settingsContainer.isSaveFromParentEnabled = false")
        )
    }

    @Test
    fun `the account cards are drawn inside the settings container`() {
        val build = body("private fun buildAccountCards()")
        assertTrue(build.contains("val container = binding.settingsContainer"))
        assertTrue(build.any { it.startsWith("val cardBinding = SettingsAccountCardBinding.inflate(") && it.endsWith("container, false)") })
        assertTrue(build.any { it.startsWith("container.addView(cardBinding.root") })
    }

    @Test
    fun `every control in the settings layout is inside the settings container`() {
        val controls = setOf(
            "SwitchMaterial", "MaterialSwitch", "SwitchCompat", "Switch", "CheckBox", "MaterialCheckBox", "RadioButton",
            "MaterialRadioButton", "RadioGroup", "ToggleButton", "CompoundButton", "MaterialButtonToggleGroup", "Chip",
            "ChipGroup", "EditText", "TextInputEditText", "AutoCompleteTextView", "MaterialAutoCompleteTextView", "Spinner",
            "AppCompatSpinner", "SeekBar", "Slider", "RangeSlider",
            // An included layout can hold controls too.
            "include",
        )
        val layout = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/main/res/layout/activity_settings.xml"))
        val all = layout.getElementsByTagName("*")
        val inside = mutableSetOf<String>()
        val outside = mutableListOf<String>()
        for (i in 0 until all.length) {
            val e = all.item(i) as Element
            if (e.tagName.substringAfterLast('.') !in controls) continue
            val id = e.getAttribute("android:id")
            if (inSettingsContainer(e)) inside += id else outside += "${e.tagName} $id"
        }
        assertEquals("controls outside settings_container keep Android's saved view state", emptyList<String>(), outside)
        assertTrue(
            inside.containsAll(setOf("@+id/switch_timezone_updates", "@+id/switch_verbose_logging", "@+id/switch_file_logging"))
        )
    }

    private fun inSettingsContainer(e: Element): Boolean {
        var parent = e.parentNode
        while (parent is Element) {
            if (parent.getAttribute("android:id") == "@+id/settings_container") return true
            parent = parent.parentNode
        }
        return false
    }

    @Test
    fun `the card-switch verb names the switches the debug command knows`() {
        // The debug script is not part of the public source, whose build check runs these tests from app/ alone.
        val file = File("../tools/debug/sync-debug.sh")
        Assume.assumeTrue("no debug script in this source tree", file.exists())
        val script = file.readLines()
        val case = script.first { it.trim().startsWith("case ") && it.contains("contacts|calendar|poll-attendees") }
        val scriptNames = case.substringAfter(" in ").substringBefore(")").split("|").map { it.replace('-', '_') }.toSet()
        val commandNames = Regex(""""([a-z_]+)" to it[.]switch""").findAll(activity.joinToString(" "))
            .map { it.groupValues[1] }.toSet()
        assertEquals(11, commandNames.size)
        assertEquals(commandNames, scriptNames)
    }
}

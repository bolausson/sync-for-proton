package me.proton.sync.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The log viewer and the data backup settings draw their settings from the stored values, never from Android's saved
 * view state, and the backup screen stores a number field only when the user changed it.
 *
 * Restored after a rotation, a language change or process death, a position saved before the setting changed elsewhere
 * (the settings screen, an import, another copy of the screen) fired the control's listener and stored the old value
 * again; and the backup screen wrote both number fields back whenever it was left, a restored old number included.
 */
class LogAndBackupViewStateTest {

    private fun source(name: String) = File("src/main/java/me/proton/sync/settings/$name").readLines()

    /** The trimmed lines of the member function that starts with [signature], up to its closing brace. */
    private fun body(lines: List<String>, signature: String): List<String> {
        val start = lines.indexOfFirst { it.trim().startsWith(signature) }
        assertTrue("$signature not found", start >= 0)
        val end = (start + 1 until lines.size).first { lines[it] == "    }" }
        return lines.subList(start, end).map { it.trim() }
    }

    private fun interval() = BackupNumberField(1..365, 7)

    @Test
    fun `a number field still showing what it was drawn with stores nothing`() {
        val f = interval()
        assertEquals("7", f.redraw("7", "", fresh = true))
        assertNull(f.toStore("7"))
    }

    @Test
    fun `a changed number field stores a whole number in its range, once`() {
        val f = interval()
        f.redraw("7", "", fresh = true)
        assertEquals("40", f.toStore("40"))
        assertNull(f.toStore("40"))
        assertEquals("1", f.toStore("0"))
        assertEquals("365", f.toStore("9999"))
        assertEquals("7", f.toStore("many"))
        val keep = BackupNumberField(1..999, 14)
        keep.redraw("3", "", fresh = true)
        assertEquals("14", keep.toStore(""))
        keep.redraw("3", "", fresh = true)
        assertEquals("14", keep.toStore(null))
    }

    @Test
    fun `an unchanged field takes a value stored elsewhere when the screen comes back, and leaving keeps it`() {
        val f = interval()
        f.redraw("14", "", fresh = true)
        // An import or another copy of the screen stores 30 while this one is open or stopped.
        assertEquals("30", f.redraw("30", "14", fresh = false))
        assertNull(f.toStore("30"))
        // Left before it came back: the old number is not written over the new one either.
        val g = interval()
        g.redraw("14", "", fresh = true)
        assertNull(g.toStore("14"))
    }

    @Test
    fun `an edit in progress survives the screen coming back`() {
        val f = interval()
        f.redraw("7", "", fresh = true)
        // Emptied, then the user switches apps: leaving stores the fallback, as before.
        assertEquals("7", f.toStore(""))
        // Back on the screen: the empty field stays empty, so the next digits are not added to a refilled 7.
        assertNull(f.redraw("7", "", fresh = false))
        assertEquals("3", f.toStore("3"))
    }

    @Test
    fun `a leftover edit is stored once, not again on every later pause`() {
        val f = BackupNumberField(1..999, 14)
        f.redraw("14", "", fresh = true)
        assertEquals("14", f.toStore(""))
        assertNull(f.redraw("14", "", fresh = false))
        assertNull(f.toStore(""))
        assertEquals("1", f.toStore("0"))
        assertNull(f.toStore("0"))
    }

    @Test
    fun `a leftover edit gives way to a value stored elsewhere since`() {
        // Emptied and left: the fallback 14 is stored and the field still shows nothing.
        val f = BackupNumberField(1..999, 14)
        f.redraw("14", "", fresh = true)
        assertEquals("14", f.toStore(""))
        // Another copy of the screen, or an import, stores 30; back on this copy the field shows 30 and leaving keeps it.
        assertEquals("30", f.redraw("30", "", fresh = false))
        assertNull(f.toStore("30"))
        // Left again before coming back: nothing was typed since, so the 30 is not overwritten with the fallback.
        val g = BackupNumberField(1..999, 14)
        g.redraw("14", "", fresh = true)
        assertEquals("14", g.toStore(""))
        assertNull(g.toStore(""))
    }

    @Test
    fun `after a re-creation an edit comes back only while its setting is unchanged`() {
        // Emptied and rotated: the pause stored 7 and the screen saved the empty text, drawn with 7.
        val f = interval()
        f.redraw("7", "", fresh = true)
        assertEquals("", f.restore("", "7", "7"))
        assertNull(f.redraw("7", "", fresh = false))
        // The setting changed after the screen saved its state (an import, another copy): the new value stands.
        val g = interval()
        g.redraw("15", "", fresh = true)
        assertNull(g.restore("14", "14", "15"))
        assertNull(g.toStore("15"))
        // Nothing was being edited: nothing to restore.
        val h = interval()
        h.redraw("14", "", fresh = true)
        assertNull(h.restore("14", "14", "14"))
        assertNull(h.restore(null, null, "14"))
    }

    @Test
    fun `the backup screen draws from the stored settings on create and on every resume, and saves its own edits`() {
        val src = source("BackupSettingsActivity.kt")
        val create = body(src, "override fun onCreate(savedInstanceState: Bundle?)")
        val order = listOf(
            "binding.backupContent.isSaveFromParentEnabled = false", "drawFromPrefs(fresh = true)",
            "savedInstanceState?.let { restoreNumberEdits(it) }", "wireListeners()",
        ).map { line -> create.indexOf(line).also { assertTrue("$line missing in onCreate", it >= 0) } }
        assertEquals(order.sorted(), order)
        assertTrue(body(src, "override fun onResume()").contains("drawFromPrefs(fresh = false)"))
        val save = body(src, "override fun onSaveInstanceState(outState: Bundle)")
        listOf(
            "outState.putString(KEY_INTERVAL_TEXT, binding.etIntervalDays.text?.toString())",
            "outState.putString(KEY_INTERVAL_DRAWN, intervalField.drawn)",
            "outState.putString(KEY_RETENTION_TEXT, binding.etRetention.text?.toString())",
            "outState.putString(KEY_RETENTION_DRAWN, retentionField.drawn)",
        ).forEach { assertTrue("$it missing in onSaveInstanceState", save.contains(it)) }
        val leave = body(src, "private fun saveIntervalAndRetention()")
        assertTrue(leave.any { it.startsWith("val days = intervalField.toStore(") })
        assertTrue(leave.any { it.startsWith("val keep = retentionField.toStore(") })
    }

    @Test
    fun `the backup screen's listeners store nothing while it draws, and its encryption switch keeps its dialog`() {
        val src = source("BackupSettingsActivity.kt")
        val draw = body(src, "private fun drawFromPrefs(fresh: Boolean)")
        assertEquals("drawing = true", draw[1])
        assertTrue(draw.indexOf("try {") < draw.indexOf("} finally {"))
        assertEquals("drawing = false", draw[draw.indexOf("} finally {") + 1])
        assertEquals("if (!encryptionAwaitingPassword) {", draw[draw.indexOfFirst { it.startsWith("binding.switchEncrypt.isChecked = ") } - 1])
        val wire = body(src, "private fun wireListeners()")
        val listeners = wire.indices.filter { wire[it].contains("setOnCheckedChangeListener {") }
        // Automatic backups, "Back up even when automatic sync is off", the two contents boxes, encryption.
        assertEquals(5, listeners.size)
        listeners.forEach { assertEquals("if (drawing) return@setOnCheckedChangeListener", wire[it + 1]) }
        // Set before the dialog opens, cleared by whichever of its two ends runs.
        assertEquals(3, wire.count { it.startsWith("encryptionAwaitingPassword = ") })
        val awaiting = wire.indexOf("encryptionAwaitingPassword = true")
        assertTrue(awaiting >= 0 && awaiting < wire.indexOfFirst { it.startsWith("promptSetPassphrase(") })
        // Cancel stores nothing: it shows the stored choice again with the listener held off.
        val cancel = wire.indexOf("onCancel = {")
        assertTrue(cancel >= 0)
        val tail = wire.subList(cancel, wire.size)
        val guard = tail.indexOf("drawing = true")
        val set = tail.indexOfFirst { it.startsWith("binding.switchEncrypt.isChecked =") }
        assertTrue("onCancel sets the switch under the drawing guard", guard >= 0 && guard < set)
        assertEquals(1, wire.count { it.contains("putBoolean(SyncPreferences.KEY_BACKUP_ENCRYPTION_ENABLED, true)") })
    }

    @Test
    fun `every control in the backup layout is inside its content container`() {
        val controls = setOf(
            "SwitchMaterial", "MaterialSwitch", "SwitchCompat", "Switch", "CheckBox", "MaterialCheckBox", "RadioButton",
            "MaterialRadioButton", "RadioGroup", "ToggleButton", "CompoundButton", "MaterialButtonToggleGroup", "Chip",
            "ChipGroup", "EditText", "TextInputEditText", "AutoCompleteTextView", "MaterialAutoCompleteTextView", "Spinner",
            "AppCompatSpinner", "SeekBar", "Slider", "RangeSlider", "include",
        )
        val layout = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/main/res/layout/activity_backup_settings.xml"))
        val all = layout.getElementsByTagName("*")
        val inside = mutableSetOf<String>()
        val outside = mutableListOf<String>()
        for (i in 0 until all.length) {
            val e = all.item(i) as Element
            if (e.tagName.substringAfterLast('.') !in controls) continue
            val id = e.getAttribute("android:id")
            if (inContainer(e, "@+id/backup_content")) inside += id else outside += "${e.tagName} $id"
        }
        assertEquals("controls outside backup_content keep Android's saved view state", emptyList<String>(), outside)
        assertTrue(
            inside.containsAll(
                setOf(
                    "@+id/switch_backup_enabled", "@+id/et_interval_days", "@+id/et_retention", "@+id/check_calendars",
                    "@+id/check_contacts", "@+id/switch_encrypt", "@+id/switch_backup_when_auto_sync_off",
                )
            )
        )
    }

    private fun inContainer(e: Element, containerId: String): Boolean {
        var parent = e.parentNode
        while (parent is Element) {
            if (parent.getAttribute("android:id") == containerId) return true
            parent = parent.parentNode
        }
        return false
    }

    @Test
    fun `the log viewer's verbose switch is drawn from the stored setting on create and on every resume`() {
        val src = source("LogViewerActivity.kt")
        val setup = body(src, "private fun setupVerboseLoggingToggle()")
        assertTrue(setup.contains("binding.switchVerboseLogging.isSaveFromParentEnabled = false"))
        assertTrue(setup.contains("refreshVerboseLoggingUi()"))
        assertTrue(body(src, "override fun onResume()").contains("refreshVerboseLoggingUi()"))
        val refresh = body(src, "private fun refreshVerboseLoggingUi()")
        val detach = refresh.indexOf("sw.setOnCheckedChangeListener(null)")
        val set = refresh.indexOfFirst { it.startsWith("sw.isChecked = ") }
        val attach = refresh.indexOf("sw.setOnCheckedChangeListener(verboseLoggingCheckedListener)")
        assertTrue("detach, set, attach", detach >= 0 && detach < set && set < attach)
    }
}

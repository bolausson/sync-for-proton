package me.proton.sync.settings

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every dialog of the app comes from [AppDialogBuilder], whose title and button labels are never cut off (German,
 * 2026-11-23: "Auch sichern, wenn die automatische Synchronisierung au..."), and no dialog is changed after create() in a
 * way that would miss the views AppDialogBuilder installs at create() rather than at show(). Comment lines are skipped.
 */
class DialogBuilderSourceTest {

    private val sources: List<File> = listOf("src/main/java", "src/direct/java", "src/play/java", "src/debug/java")
        .map(::File)
        .filter { it.isDirectory }
        .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }

    /** "<file>: <match>" for every match of [pattern] outside comment lines. */
    private fun matches(pattern: Regex, skip: (File) -> Boolean = { false }): List<String> =
        sources.filterNot(skip).flatMap { file ->
            file.readLines()
                .filterNot { line -> line.trimStart().let { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") } }
                .flatMap { line -> pattern.findAll(line).map { "${file.name}: ${it.value}" }.toList() }
        }

    @Test
    fun `the sources are found`() {
        // Gradle runs unit tests in the module folder; a wrong folder would make the checks below pass on nothing.
        assertTrue("${sources.size} Kotlin files", sources.size > 100)
        assertTrue(sources.any { it.name == "AppDialogBuilder.kt" })
    }

    @Test
    fun `no dialog is built or reached any other way`() {
        // AppCompat's or Material's builder, the platform's AlertDialog, other dialog classes (whose titles nothing
        // unclips), dialog fragments and casts to AlertDialog (a way to change a dialog after create()).
        val other = matches(
            Regex(
                """AlertDialog\.Builder\b|MaterialAlertDialogBuilder\(|android\.app\.AlertDialog\b|as\??\s+AlertDialog\b|""" +
                    """requireDialog\(|getDialog\(|\b(AppCompatDialog|BottomSheetDialog|ProgressDialog|DatePickerDialog|""" +
                    """TimePickerDialog|ComponentDialog|Dialog)\(|\bDialogFragment\b"""
            ),
            skip = { it.name == "AppDialogBuilder.kt" },
        )
        assertEquals("Build dialogs with AppDialogBuilder, so their titles and buttons wrap instead of being cut", "", other.joinToString("\n"))
    }

    @Test
    fun `no dialog gets a title, message, buttons, view or icon after create`() {
        // A call on a named object, not on a builder chain (whose lines start with the dot) and not on a builder kept in a
        // variable named builder, which is set up before create(). AppDialogBuilder installs the views at create(), so a
        // title, buttons, a view or an icon set later would not show, and a message only where the dialog had one from
        // the start. The one such call: a progress dialog created with a message.
        val later = matches(Regex("""\b(?!builder\b)[A-Za-z_][A-Za-z0-9_]*(\?|!!)?\.(setTitle|setMessage|setButton|setView|setIcon|setCustomTitle)\("""))
        assertEquals(listOf("ContactFilterPreviewUi.kt: progress.setMessage("), later)
    }
}

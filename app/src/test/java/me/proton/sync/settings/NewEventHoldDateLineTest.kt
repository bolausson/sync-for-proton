package me.proton.sync.settings

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hold dialog's date line comes from DateUtils.formatDateRange, which puts an en dash between the two ends of a
 * range in most languages. The app shows no en or em dash anywhere (house style), so the line goes through
 * [withoutLongDashes]. Texts here use escapes, never the dash characters themselves.
 */
class NewEventHoldDateLineTest {

    @Test
    fun `the dashes of a formatted range become hyphens`() {
        assertEquals("17:00-18:00", withoutLongDashes("17:00–18:00"))
        assertEquals("24.-25. November 2026", withoutLongDashes("24.–25. November 2026"))
        assertEquals("17:00 - 18:00", withoutLongDashes("17:00 – 18:00"))
        assertEquals("a-b-c-d", withoutLongDashes("a‒b—c―d"))
    }

    @Test
    fun `a text without such dashes is unchanged`() {
        val line = "Dienstag, 24. November 2026, 17:00 Uhr"
        assertEquals(line, withoutLongDashes(line))
        assertEquals("a-b", withoutLongDashes("a-b"))
    }

    @Test
    fun `every formatDateRange result the app shows goes through withoutLongDashes`() {
        val sources = File("src/main/java").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("no Kotlin sources found under src/main/java", sources.isNotEmpty())
        var calls = 0
        for (file in sources) {
            val text = file.readText()
            val all = Regex("""formatDateRange\(""").findAll(text).count()
            val wrapped = Regex("""withoutLongDashes\(DateUtils\.formatDateRange\(""").findAll(text).count()
            assertEquals("${file.name}: every DateUtils.formatDateRange( must be wrapped in withoutLongDashes(", all, wrapped)
            calls += all
        }
        assertTrue("the hold dialog's date line no longer uses formatDateRange; update this test", calls >= 1)
    }
}

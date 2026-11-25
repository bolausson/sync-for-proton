package me.proton.sync

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The owner's rule for the log lines 2.2.0 added (2026-11-21): calendar names and the apps that wrote events appear
 * only while Verbose logging is on; otherwise a calendar is its id and an app is "another app". Made-up names only.
 */
class LogNamesTest {

    @After
    fun verboseOff() = SyncLog.setVerboseEnabled(false)

    @Test
    fun `without Verbose logging no name reaches a line`() {
        SyncLog.setVerboseEnabled(false)
        assertFalse(LogNames.shown())
        assertEquals("calendar 7", LogNames.calendar("Home", 7L))
        assertEquals("calendar abcd1234", LogNames.calendar("Home", "abcd1234"))
        assertEquals("", LogNames.named("Home"))
        assertEquals("another app", LogNames.app("com.example.booking"))
        assertEquals("an app", LogNames.app("com.example.booking", instead = "an app"))
        // No writer recorded stays no writer recorded.
        assertNull(LogNames.app(null))
    }

    @Test
    fun `with Verbose logging the names are shown as before`() {
        SyncLog.setVerboseEnabled(true)
        assertTrue(LogNames.shown())
        assertEquals("'Home'", LogNames.calendar("Home", 7L))
        assertEquals(" ('Work')", LogNames.named("Work"))
        assertEquals("com.example.booking", LogNames.app("com.example.booking"))
        assertNull(LogNames.app(null))
        // Nothing to show: the id, or nothing after it.
        assertEquals("calendar 7", LogNames.calendar(null, 7L))
        assertEquals("calendar 7", LogNames.calendar(" ", 7L))
        assertEquals("", LogNames.named(null))
        assertEquals("a calendar", LogNames.calendar(null, null))
    }

    @Test
    fun `the Verbose logging info text says so, and keeps its other statements`() {
        // The switch says what the privacy policy says (owner, 2026-11-22).
        val strings = java.io.File("src/main/res/values/strings.xml").readText()
        val text = Regex("<string name=\"info_verbose_logging_body\">(.*?)</string>", RegexOption.DOT_MATCHES_ALL)
            .find(strings)?.groupValues?.get(1)
        assertTrue(text != null)
        for (kept in listOf(
            "While it is on, the log also names your calendars and the apps that add events to them.",
            "The app replaces email addresses in the log with short codes",
            "it may include account and calendar names, so only share logs with the developer.",
            "Captures extra diagnostic detail", "Leave off for normal use.",
        )) {
            assertTrue("$kept: $text", text!!.contains(kept))
        }
    }
}

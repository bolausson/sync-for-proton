package me.proton.sync.calendar

import android.database.Cursor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import me.proton.sync.SyncLog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * The upload's line for a new local event, which names the app(s) in the provider's MUTATORS column: one package
 * for a plain insert, a comma-separated list when the provider appended to one, nothing when it recorded none.
 * An app can write anything there, so only package-name characters reach the log. The app and the calendar are
 * named only while Verbose logging is on (the owner's rule, 2026-11-21): the lines below are that wording, and
 * `without Verbose logging ...` the default one.
 */
class LocalEventOriginTest {

    @Before
    fun verboseOn() = SyncLog.setVerboseEnabled(true)

    @After
    fun verboseOff() = SyncLog.setVerboseEnabled(false)

    @Test
    fun `without Verbose logging the line names neither the app nor the calendar`() {
        SyncLog.setVerboseEnabled(false)
        assertEquals(
            "Local event 4655 (new, written by an app); uploading to Proton",
            LocalEventOrigin.newEventLine(4655, "com.example.booking", "Team")
        )
        assertEquals("Local event 7 (new, no writer recorded); uploading to Proton", LocalEventOrigin.newEventLine(7, null, "Team"))
        assertEquals(
            "Local event 9 (new, written by an app); uploading to Proton",
            runBlocking { LocalEventOrigin.uploadStartLine(9, "com.example.booking", "Team") { null } }
        )
        // What the caller asks for wins over the switch.
        assertEquals(
            "Local event 1 (new, written by com.example.booking) in 'Team'; uploading to Proton",
            LocalEventOrigin.newEventLine(1, "com.example.booking", "Team", named = true)
        )
        SyncLog.setVerboseEnabled(true)
        assertEquals("Local event 2 (new, written by an app); uploading to Proton", LocalEventOrigin.newEventLine(2, "com.example.booking", "Team", named = false))
    }

    @Test
    fun `one package`() {
        assertEquals(
            "Local event 4655 (new, written by com.example.booking) in 'Team'; uploading to Proton",
            LocalEventOrigin.newEventLine(4655, "com.example.booking", "Team")
        )
    }

    @Test
    fun `a list is shown in the order the provider kept`() {
        assertEquals("com.example.booking, com.example.calendar", LocalEventOrigin.writers("com.example.booking,com.example.calendar"))
    }

    @Test
    fun `no writer recorded`() {
        assertNull(LocalEventOrigin.writers(null))
        assertNull(LocalEventOrigin.writers(""))
        assertNull(LocalEventOrigin.writers(" , ,"))
        assertEquals(
            "Local event 7 (new, no writer recorded); uploading to Proton",
            LocalEventOrigin.newEventLine(7, null, null)
        )
    }

    @Test
    fun `blank entries are dropped and the rest trimmed`() {
        assertEquals("com.example.a, android.uid.system:1000", LocalEventOrigin.writers(" com.example.a ,, android.uid.system:1000 "))
    }

    @Test
    fun `anything but package-name characters is masked, so nothing can fake a log line`() {
        assertEquals("com.example?Fake?line", LocalEventOrigin.writers("com.example\nFake line"))
        assertEquals("a_b-c.d", LocalEventOrigin.writers("a_b-c.d"))
    }

    @Test
    fun `entries split, trim and drop empty ones, in the provider's order`() {
        assertEquals(listOf("com.example.booking", "com.example.calendar"), LocalEventOrigin.entries(" com.example.booking , ,com.example.calendar "))
        assertEquals(emptyList<String>(), LocalEventOrigin.entries(null))
        assertEquals(emptyList<String>(), LocalEventOrigin.entries(""))
        assertEquals(emptyList<String>(), LocalEventOrigin.entries(" , ,"))
        assertEquals(listOf("android.uid.calendar:10045"), LocalEventOrigin.entries("android.uid.calendar:10045"))
    }

    @Test
    fun `one entry keeps package-name characters only`() {
        assertEquals("com.example.booking", LocalEventOrigin.sanitizeEntry("com.example.booking"))
        assertEquals("android.uid.system:1000", LocalEventOrigin.sanitizeEntry("android.uid.system:1000"))
        assertEquals("a_b-c.d", LocalEventOrigin.sanitizeEntry("a_b-c.d"))
        assertEquals("com?example?x?y", LocalEventOrigin.sanitizeEntry("com|example\nx y"))
        assertEquals(100, LocalEventOrigin.sanitizeEntry("x".repeat(500)).length)
    }

    @Test
    fun `a long list is cut`() {
        assertEquals("a, b, c, d and 2 more", LocalEventOrigin.writers("a,b,c,d,e,f"))
        assertEquals(100, LocalEventOrigin.writers("x".repeat(500))!!.length)
    }

    @Test
    fun `only a text value is read from the column`() {
        assertEquals("com.example.booking", LocalEventOrigin.mutatorsText(Cursor.FIELD_TYPE_STRING) { "com.example.booking" })
        // getString on a BLOB throws, and a sync adapter caller may store one: that row stopped every upload.
        for (type in listOf(Cursor.FIELD_TYPE_BLOB, Cursor.FIELD_TYPE_INTEGER, Cursor.FIELD_TYPE_FLOAT, Cursor.FIELD_TYPE_NULL)) {
            assertNull(LocalEventOrigin.mutatorsText(type) { throw IllegalStateException("Unable to convert BLOB to string") })
        }
    }

    @Test
    fun `the upload's first line says new or modified by the row's mapping`() = runBlocking {
        assertEquals(
            "Local event 9 (new, written by com.example.booking) in 'Team'; uploading to Proton",
            LocalEventOrigin.uploadStartLine(9, "com.example.booking", "Team") { null }
        )
        assertEquals("Local event 9 modified; uploading to Proton", LocalEventOrigin.uploadStartLine(9, null, "Team") { "event-1" })
    }

    @Test
    fun `a failed mapping lookup gives the modified line and never fails the row`() = runBlocking {
        // It ran outside the try that counts upload failures, so a database error skipped the row without a failure.
        assertEquals(
            "Local event 9 modified; uploading to Proton",
            LocalEventOrigin.uploadStartLine(9, "com.example.booking", "Team") { throw IllegalStateException("database is locked") }
        )
    }

    @Test
    fun `a cancellation is passed on`() {
        try {
            runBlocking { LocalEventOrigin.uploadStartLine(9, null, null) { throw CancellationException("stop") } }
            fail("the cancellation was swallowed")
        } catch (_: CancellationException) {
            // expected
        }
    }
}

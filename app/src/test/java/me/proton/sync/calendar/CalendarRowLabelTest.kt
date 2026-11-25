package me.proton.sync.calendar

import me.proton.sync.db.entities.CalendarSyncEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The name and colour a sync gives a calendar's row (CalendarRowLabel, CalendarSyncAdapter.prepareCalendarJob). Found
 * on a test phone on 2026-11-22: with the network cut during a calendar sync, the bootstrap reads that give Proton's
 * calendar names failed, and four existing rows were renamed to the placeholders "My Calendar" and "Proton Calendar"
 * until a later sync; since 2.2.0 that sync may wait for the user. Made-up calendars and accounts only.
 */
class CalendarRowLabelTest {

    private companion object {
        const val ACCOUNT = "account-a@example.test"
        const val ADAPTER = "src/main/java/me/proton/sync/calendar/CalendarSyncAdapter.kt"
    }

    private val adapter: String by lazy { File(ADAPTER).readText().replace("\r\n", "\n") }

    /** [text] without KDoc, block comment lines and line comments. */
    private fun code(text: String): String =
        text.lines()
            .filterNot { it.trim().let { t -> t.startsWith("*") || t.startsWith("/*") || t.startsWith("//") } }
            .joinToString("\n") { it.substringBefore(" // ") }

    private fun squash(text: String): String = text.replace(Regex("\\s+"), " ")

    /** The text of the function that starts with [signature], up to its closing brace at four spaces. */
    private fun body(signature: String): String {
        assertTrue("$signature not found", adapter.contains(signature))
        return adapter.substringAfter(signature).substringBefore("\n    }\n")
    }

    private fun mapping(protonId: String, row: Long, displayName: String = "") =
        CalendarSyncEntity(accountName = ACCOUNT, protonCalendarId = protonId, androidCalendarId = row, displayName = displayName)

    // ---- The rule -------------------------------------------------------------------------------------------------

    @Test
    fun `a failed read keeps an existing row's name and colour`() {
        for (type in listOf(0, 1, 2)) {
            val label = CalendarRowLabel.of(name = null, color = null, type = type, bootstrapFailed = true)
            assertTrue(label.keepRowName)
            assertTrue(label.keepRowColor)
            // What a NEW row gets all the same: it needs a name.
            assertEquals(if (type == 0) "My Calendar" else "Proton Calendar", label.name)
            assertEquals("#657ee4", label.color)
            assertTrue(label.placeholder)
        }
        // A colour the list or our member gave is known: only the name is kept.
        val colourKnown = CalendarRowLabel.of(name = null, color = "#3cbb3a", type = 0, bootstrapFailed = true)
        assertTrue(colourKnown.keepRowName)
        assertFalse(colourKnown.keepRowColor)
        assertEquals("#3cbb3a", colourKnown.color)
    }

    @Test
    fun `a calendar Proton answered for without a name still gets the placeholder and the default colour`() {
        for (type in listOf(0, 1)) {
            val label = CalendarRowLabel.of(name = null, color = null, type = type, bootstrapFailed = false)
            assertFalse(label.keepRowName)
            assertFalse(label.keepRowColor)
            assertEquals(CalendarRowLabel.placeholder(type), label.name)
            assertEquals(CalendarRowLabel.DEFAULT_COLOR, label.color)
            assertTrue(label.placeholder)
        }
    }

    @Test
    fun `a named calendar is written as Proton names it, even one really called My Calendar`() {
        val named = CalendarRowLabel.of(name = "Team", color = "#aabbcc", type = 0, bootstrapFailed = false)
        assertEquals(CalendarRowLabel.Label("Team", "#aabbcc", keepRowName = false, keepRowColor = false, placeholder = false), named)
        val uncoloured = CalendarRowLabel.of(name = "Team", color = null, type = 1, bootstrapFailed = false)
        assertEquals("#657ee4", uncoloured.color)
        assertFalse(uncoloured.keepRowColor)
        // Its own name, which happens to be the placeholder's: it finds its row by name as before.
        val own = CalendarRowLabel.of(name = "My Calendar", color = null, type = 0, bootstrapFailed = false)
        assertFalse(own.placeholder)
    }

    @Test
    fun `a kept row is recorded under the name and colour it has, else its mapping's, else the label's`() {
        assertEquals("account-a - Team", CalendarRowLabel.keptName("account-a - Team", "account-a - Old", "account-a - My Calendar"))
        assertEquals("account-a - Old", CalendarRowLabel.keptName(null, "account-a - Old", "account-a - My Calendar"))
        assertEquals("account-a - Old", CalendarRowLabel.keptName(" ", "account-a - Old", "account-a - My Calendar"))
        assertEquals("account-a - My Calendar", CalendarRowLabel.keptName(null, "", "account-a - My Calendar"))
        assertEquals(0x112233, CalendarRowLabel.keptColor(0x112233, 0x445566, 0x657ee4))
        assertEquals(0x445566, CalendarRowLabel.keptColor(null, 0x445566, 0x657ee4))
        // A mapping that never stored a colour holds 0.
        assertEquals(0x657ee4, CalendarRowLabel.keptColor(null, 0, 0x657ee4))
        assertEquals(0x657ee4, CalendarRowLabel.keptColor(null, null, 0x657ee4))
    }

    @Test
    fun `a placeholder name never takes another calendar's row`() {
        // Two rows an earlier failed read renamed, whose mappings are gone (a re-sync): the claims alone would hand the
        // first to whichever calendar asks by that name.
        val listed = mapOf("account-a - My Calendar" to listOf(42L, 44L))
        val claimed = CalendarRowClaims.reusableRow(listed["account-a - My Calendar"], "cal-b", emptyList(), setOf("cal-a", "cal-b"))
        assertEquals(42L, claimed)
        val unknown = CalendarRowLabel.of(name = null, color = null, type = 0, bootstrapFailed = true)
        assertNull(claimed.takeUnless { unknown.placeholder })
        // A real name still finds its own row, and still never one another listed calendar maps.
        val team = CalendarRowLabel.of(name = "Team", color = null, type = 0, bootstrapFailed = false)
        assertEquals(42L, claimed.takeUnless { team.placeholder })
        assertNull(
            CalendarRowClaims.reusableRow(listOf(42L), "cal-b", listOf(mapping("cal-a", 42L)), setOf("cal-a", "cal-b"))
                .takeUnless { team.placeholder }
        )
    }

    // ---- Where it is wired ------------------------------------------------------------------------------------------

    @Test
    fun `a failed bootstrap read, thrown or answered, is told apart from a nameless answer`() {
        val job = squash(code(body("private suspend fun prepareCalendarJob(")))
        // Both ways a read fails mark it: an answer that was not a success, and an exception.
        val failedAnswer = job.indexOf("} else { bootstrapFailed = true SyncLog.w(TAG, \"Failed to fetch bootstrap for \${calInfo.id}: HTTP")
        val thrown = job.indexOf("} catch (e: Exception) { bootstrapFailed = true SyncLog.w(TAG, \"Failed to fetch bootstrap for \${calInfo.id}: \${e.message}\")")
        assertTrue(job, failedAnswer >= 0 && thrown > failedAnswer)
        assertEquals(job, 2, Regex(Regex.escape("bootstrapFailed = true")).findAll(job).count())
        assertTrue(job, job.contains("val label = CalendarRowLabel.of(calName, calColorStr, calInfo.type, bootstrapFailed)"))
        assertTrue(job, job.contains("var calColor = parseColor(label.color)"))
        // The placeholders and the default colour live in CalendarRowLabel alone.
        val code = code(adapter)
        assertFalse(code.contains("\"My Calendar\"") || code.contains("\"Proton Calendar\"") || code.contains("\"#657ee4\""))
    }

    @Test
    fun `an existing row keeps its name and colour, a new row gets the label, and the mapping records what the row shows`() {
        val job = squash(code(body("private suspend fun prepareCalendarJob(")))
        // Both existing-row writes pass the label; the new row is written as before.
        assertEquals(job, 2, Regex(Regex.escape(", label = label)")).findAll(job).count())
        assertTrue(job, job.contains("isPrimary = primaryRun.forExistingRow(calInfo.id, existingMapping.androidCalendarId), label = label)"))
        assertTrue(job, job.contains("clearKeptMarker = reusedCalId == keptRow, label = label)"))
        assertTrue(job, job.contains("insertLocalCalendar(provider, account, displayName, calColor, ownerEmail, calInfo.type, memberInfo?.permissions,"))
        val update = squash(code(body("private fun updateLocalCalendar(")))
        assertTrue(update, update.contains("if (!label.keepRowName) put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, displayName)"))
        assertTrue(update, update.contains("if (!label.keepRowColor) put(CalendarContract.Calendars.CALENDAR_COLOR, color)"))
        val insert = squash(code(body("private fun insertLocalCalendar(")))
        assertTrue(insert, insert.contains("put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, displayName)"))
        assertTrue(insert, insert.contains("put(CalendarContract.Calendars.CALENDAR_COLOR, color)"))
        // Before the mapping is written, a kept row's own name and colour replace the label's for the mapping and the job.
        val kept = job.indexOf("if (label.keepRowName || label.keepRowColor) { val kept = keptRowLabel(provider, account, androidCalId, calInfo.id, label, existingMapping, displayName, calColor) displayName = kept.first calColor = kept.second }")
        val upsert = job.indexOf("calendarSyncDao.upsert(")
        assertTrue(job, job.indexOf("if (androidCalId == null) {") in 0 until kept && kept < upsert)
        assertTrue(job, job.contains("displayName = displayName, color = calColor,"))
        val keptLabel = squash(code(body("private fun keptRowLabel(")))
        assertTrue(keptLabel, keptLabel.contains("CalendarRowLabel.keptName(currentName, stored?.displayName, displayName)"))
        assertTrue(keptLabel, keptLabel.contains("CalendarRowLabel.keptColor(currentColor, stored?.color, color)"))
    }

    @Test
    fun `the reuse by name takes no row by a placeholder`() {
        val job = squash(code(body("private suspend fun prepareCalendarJob(")))
        assertTrue(job, job.contains(
            "val orphanedCalId = CalendarRowClaims.reusableRow(sameNameRows, calInfo.id, existingCalMappings.values, allServerCalIds) .takeUnless { label.placeholder }"
        ))
        // The row kept for this very calendar is found by its id, never its name, so it is still taken back.
        assertTrue(job, job.contains("val reusedCalId = keptRow ?: orphanedCalId"))
    }
}

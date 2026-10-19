package me.proton.sync.contacts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bookkeeping that keeps a contact created on the phone alive when Proton will not create it.
 *
 * Losing an entry here is not cosmetic: a refused contact that drops out of the refused set is, on
 * the next sync, a clean unmapped row, and the orphan sweep deletes it.
 */
class ContactUploadRefusalsTest {

    private val account = "acct"

    @Test
    fun `strikes round-trip and unreadable entries are dropped`() {
        val parsed = ContactUploadRefusals.parseStrikes("12:1, 40:2,bad,7:x,9:0,:3")
        assertEquals(mapOf(12L to 1, 40L to 2), parsed)
        assertEquals("12:1,40:2", ContactUploadRefusals.formatStrikes(parsed))
        assertEquals(emptyMap<Long, Int>(), ContactUploadRefusals.parseStrikes(null))
    }

    @Test
    fun `strikes count per contact`() {
        val prefs = FakePrefs()
        assertEquals(1, ContactUploadRefusals.recordStrike(prefs, account, 5, "r"))
        assertEquals(2, ContactUploadRefusals.recordStrike(prefs, account, 5, "r"))
        assertEquals(1, ContactUploadRefusals.recordStrike(prefs, account, 6, "r"))
        assertEquals(3, ContactUploadRefusals.recordStrike(prefs, account, 5, "r"))
        assertEquals(1, ContactUploadRefusals.recordStrike(prefs, "other", 5, "r"))
    }

    @Test
    fun `giving up protects the contact and resets its count`() {
        val prefs = FakePrefs()
        repeat(3) { ContactUploadRefusals.recordStrike(prefs, account, 5, "r") }
        ContactUploadRefusals.markRefused(prefs, account, 5)
        assertEquals(setOf(5L), ContactUploadRefusals.refusedIds(prefs, account))
        // An edit retries it from the first attempt, and it stays protected meanwhile.
        assertEquals(1, ContactUploadRefusals.recordStrike(prefs, account, 5, "r"))
        assertEquals(setOf(5L), ContactUploadRefusals.refusedIds(prefs, account))
    }

    @Test
    fun `a contact that reaches Proton is forgotten`() {
        val prefs = FakePrefs()
        ContactUploadRefusals.markRefused(prefs, account, 5)
        ContactUploadRefusals.recordStrike(prefs, account, 5, "r")
        ContactUploadRefusals.markRefused(prefs, account, 6)
        ContactUploadRefusals.forget(prefs, account, 5)
        assertEquals(setOf(6L), ContactUploadRefusals.refusedIds(prefs, account))
        assertEquals(1, ContactUploadRefusals.recordStrike(prefs, account, 5, "r"))
    }

    @Test
    fun `retain drops only what it is told to`() {
        val prefs = FakePrefs()
        ContactUploadRefusals.markRefused(prefs, account, 1)
        ContactUploadRefusals.markRefused(prefs, account, 2)
        ContactUploadRefusals.recordStrike(prefs, account, 3, "r")
        ContactUploadRefusals.recordStrike(prefs, account, 4, "r")
        ContactUploadRefusals.retain(prefs, account) { it == 2L || it == 4L }
        assertEquals(setOf(2L), ContactUploadRefusals.refusedIds(prefs, account))
        assertEquals(2, ContactUploadRefusals.recordStrike(prefs, account, 4, "r"))
        assertEquals(1, ContactUploadRefusals.recordStrike(prefs, account, 3, "r"))
    }

    @Test
    fun `reasons round-trip even with colons in them`() {
        val parsed = ContactUploadRefusals.parseReasons(setOf("12:Email too long (Proton code 2024)", "7:a:b", "bad", ":x"))
        assertEquals(mapOf(12L to "Email too long (Proton code 2024)", 7L to "a:b"), parsed)
        assertEquals(parsed, ContactUploadRefusals.parseReasons(ContactUploadRefusals.formatReasons(parsed)))
    }

    /** The notification lists every contact still stuck, given up or not, with its latest reason. */
    @Test
    fun `tracked covers retries and give-ups and follows forget`() {
        val prefs = FakePrefs()
        ContactUploadRefusals.recordStrike(prefs, account, 5, "first")
        ContactUploadRefusals.recordStrike(prefs, account, 5, "second")
        repeat(3) { ContactUploadRefusals.recordStrike(prefs, account, 9, "too long") }
        ContactUploadRefusals.markRefused(prefs, account, 9)
        assertEquals(listOf(5L to "second", 9L to "too long"), ContactUploadRefusals.tracked(prefs, account))
        ContactUploadRefusals.forget(prefs, account, 9)
        assertEquals(listOf(5L to "second"), ContactUploadRefusals.tracked(prefs, account))
        ContactUploadRefusals.retain(prefs, account) { false }
        assertEquals(emptyList<Pair<Long, String>>(), ContactUploadRefusals.tracked(prefs, account))
    }

    @Test
    fun `only failures about the connection or session are transient`() {
        listOf(401, 408, 429, 500, 502, 503).forEach { assertTrue("$it", ContactUploadRefusals.isTransientHttp(it)) }
        listOf(400, 403, 404, 409, 413, 422).forEach { assertFalse("$it", ContactUploadRefusals.isTransientHttp(it)) }
    }
}

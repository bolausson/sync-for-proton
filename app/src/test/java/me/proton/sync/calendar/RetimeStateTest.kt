package me.proton.sync.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The re-time pass's per-account bookkeeping. A check left incomplete because some zone's online rules could not
 * be had runs again, but only in another process (a zone is fetched at most once per process), and not for ever.
 */
class RetimeStateTest {

    private val id = "v1|on|2021a|2026d"

    @Test
    fun `checked means done until the identity changes`() {
        assertTrue(RetimeState.shouldRun(null, id, "p1"))
        assertFalse(RetimeState.shouldRun(id, id, "p1"))
        assertTrue("a new tz release seen", RetimeState.shouldRun(id, "v1|on|2021a|2026e", "p1"))
        assertTrue("the switch flipped", RetimeState.shouldRun(id, "v1|off|2021a", "p1"))
        assertEquals(id, RetimeState.afterRun(null, id, id, incomplete = false, process = "p1"))
    }

    @Test
    fun `incomplete runs again in another process only, and is given up after a bound`() {
        var stored: String? = RetimeState.afterRun(null, id, id, incomplete = true, process = "p1")
        assertEquals("$id#u1@p1", stored)
        assertFalse("nothing can change within the process whose fetch failed", RetimeState.shouldRun(stored, id, "p1"))
        assertTrue(RetimeState.shouldRun(stored, id, "p2"))
        for (n in 2 until RetimeState.MAX_INCOMPLETE) {
            stored = RetimeState.afterRun(stored, id, id, incomplete = true, process = "p$n")
            assertEquals("$id#u$n@p$n", stored)
        }
        stored = RetimeState.afterRun(stored, id, id, incomplete = true, process = "last")
        assertEquals("given up: checked as far as it can be", id, stored)
        // A complete run in another process ends it at once.
        assertEquals(id, RetimeState.afterRun("$id#u3@p1", id, id, incomplete = false, process = "p2"))
        // A newer release seen during the run starts the count over for the new identity.
        assertEquals("v1|on|2021a|2026e#u1@p2", RetimeState.afterRun("$id#u7@p1", id, "v1|on|2021a|2026e", incomplete = true, process = "p2"))
    }

    @Test
    fun `failures are retried at the next syncs, then given up until the identity changes`() {
        assertEquals("$id#1", RetimeState.afterFailure(null, id))
        assertEquals("$id#2", RetimeState.afterFailure("$id#1", id))
        assertEquals(id, RetimeState.afterFailure("$id#2", id))
        assertTrue(RetimeState.shouldRun("$id#2", id, "p1"))
        assertEquals("another identity's failures do not count", "$id#1", RetimeState.afterFailure("v0|off|2021a#2", id))
    }

    @Test
    fun `a download that fell back marks a checked account incomplete, once per process`() {
        assertEquals("$id#u0@p1", RetimeState.afterDownloadFallback(id, id, "p1"))
        assertNull("already marked in this process", RetimeState.afterDownloadFallback("$id#u0@p1", id, "p1"))
        assertEquals("keeps its count, moves to this process", "$id#u4@p2", RetimeState.afterDownloadFallback("$id#u4@p1", id, "p2"))
        assertNull("never checked: the pass runs anyway", RetimeState.afterDownloadFallback(null, id, "p1"))
        assertNull("failing: the pass runs anyway", RetimeState.afterDownloadFallback("$id#1", id, "p1"))
        assertNull("another identity: the pass runs anyway", RetimeState.afterDownloadFallback("v1|off|2021a", id, "p1"))
        assertFalse(RetimeState.shouldRun("$id#u0@p1", id, "p1"))
        assertTrue(RetimeState.shouldRun("$id#u0@p1", id, "p2"))
    }

    @Test
    fun `unreadable values start over`() {
        for (v in listOf("$id#", "$id#x", "$id#u", "$id#ux@p", "$id#u1")) {
            assertTrue(v, RetimeState.parse(v, id) is RetimeState.Stored.Other)
            assertTrue(v, RetimeState.shouldRun(v, id, "p1"))
        }
    }
}

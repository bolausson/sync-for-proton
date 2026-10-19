package me.proton.sync.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StaleEventCountersTest {

    private val keys = setOf(
        "upload_reject_count_14937",
        "upload_reject_count_14999",
        "upload_reject_count_15211",
        "delete_reject_count_14937",
        "invite_retry_count_abc==",
        // Neighbours that must never be touched.
        "calendar_sync_mode_alice",
        "conflict_held_alice",
        "upload_reject_count_",
    )

    @Test
    fun `a counter whose event is gone is stale`() {
        // The pair found on the test phone: two counters at 2 strikes naming rows deleted days before.
        val stale = staleCounterKeys(keys, "upload_reject_count_", liveIds = setOf("15211"))
        assertEquals(setOf("upload_reject_count_14937", "upload_reject_count_14999"), stale)
    }

    @Test
    fun `a counter whose event still exists is kept`() {
        val stale = staleCounterKeys(keys, "upload_reject_count_", liveIds = setOf("14937", "14999", "15211"))
        assertTrue(stale.isEmpty())
    }

    @Test
    fun `an unknown live set sweeps nothing`() {
        // Not knowing must never be read as "gone": removing a counter for an event that DOES exist
        // hands it a free strike, which is worse than leaving a dead key behind.
        assertTrue(staleCounterKeys(keys, "upload_reject_count_", liveIds = null).isEmpty())
    }

    @Test
    fun `an empty live set means every counter of that family is stale`() {
        // A phone with no events at all, after a wipe or a forced resync.
        val stale = staleCounterKeys(keys, "upload_reject_count_", liveIds = emptySet())
        assertEquals(
            setOf("upload_reject_count_14937", "upload_reject_count_14999", "upload_reject_count_15211"),
            stale,
        )
    }

    @Test
    fun `only the named family is considered`() {
        val stale = staleCounterKeys(keys, "delete_reject_count_", liveIds = emptySet())
        assertEquals(setOf("delete_reject_count_14937"), stale)
    }

    @Test
    fun `unrelated preferences are never touched`() {
        val stale = staleCounterKeys(keys, "upload_reject_count_", liveIds = emptySet())
        assertTrue(stale.none { it.startsWith("calendar_sync_mode") || it.startsWith("conflict_held") })
    }

    @Test
    fun `the bare prefix with no id is not a counter`() {
        // "upload_reject_count_" alone names no event, so it is not ours to delete.
        val stale = staleCounterKeys(keys, "upload_reject_count_", liveIds = emptySet())
        assertTrue("upload_reject_count_" !in stale)
    }

    @Test
    fun `a Proton id keyed family works the same, base64 padding and all`() {
        val stale = staleCounterKeys(keys, "invite_retry_count_", liveIds = setOf("other=="))
        assertEquals(setOf("invite_retry_count_abc=="), stale)
        assertTrue(staleCounterKeys(keys, "invite_retry_count_", liveIds = setOf("abc==")).isEmpty())
    }
}

package me.proton.sync.contacts.filter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FilterRemovalPlanTest {

    @Test
    fun `a rescued contact is never asked about again`() {
        // The regression: "kept" is recorded in stillExcluded but not in toAsk. Holding stillExcluded put the
        // rescued id back into the pending set on the next sync, so the prompt returned for ever.
        val plan = planFilterRemovals(
            pending = emptySet(),
            stillExcluded = setOf("rescued", "fresh"),
            toAsk = setOf("fresh"),
            approved = false
        )
        assertEquals(setOf("fresh"), plan.ask)
        assertFalse("rescued" in plan.ask)
        assertEquals(emptySet<String>(), plan.delete)
        assertFalse(plan.clear)
    }

    @Test
    fun `a rescued contact is never deleted, even when an approval is standing`() {
        // The data-loss shape: the id is in the pending set from before the fix, and the user approves a
        // removal. It must still not be deleted, because it is not something we may ask about.
        val plan = planFilterRemovals(
            pending = setOf("rescued", "fresh"),
            stillExcluded = setOf("rescued", "fresh"),
            toAsk = setOf("fresh"),
            approved = true
        )
        assertEquals(setOf("fresh"), plan.delete)
        assertFalse("rescued" in plan.delete)
    }

    @Test
    fun `an approval cannot be widened by a rule edited after the prompt`() {
        // "newly excluded" was never in the prompt the user answered, so it is held, not deleted.
        val plan = planFilterRemovals(
            pending = setOf("asked"),
            stillExcluded = setOf("asked", "newly excluded"),
            toAsk = setOf("asked", "newly excluded"),
            approved = true
        )
        assertEquals(setOf("asked"), plan.delete)
    }

    @Test
    fun `holds are released when the rules no longer exclude the contact`() {
        val plan = planFilterRemovals(
            pending = setOf("released", "still"),
            stillExcluded = setOf("still"),
            toAsk = setOf("still"),
            approved = false
        )
        assertEquals(setOf("released"), plan.prune)
        assertEquals(setOf("still"), plan.ask)
    }

    @Test
    fun `nothing to ask clears the guard even while contacts stay excluded`() {
        // Every excluded contact is rescued: there is no question to put, so the prompt must go away
        // rather than be re-posted with a count of zero.
        val plan = planFilterRemovals(
            pending = setOf("rescued"),
            stillExcluded = setOf("rescued"),
            toAsk = emptySet(),
            approved = false
        )
        assertTrue(plan.clear)
        assertEquals(emptySet<String>(), plan.ask)
        assertEquals(emptySet<String>(), plan.delete)
        // The rescued id is still excluded, so its hold is not pruned on that basis; clearing covers it.
        assertEquals(emptySet<String>(), plan.prune)
    }

    @Test
    fun `an inactive filter with nothing excluded clears and asks nothing`() {
        val plan = planFilterRemovals(emptySet(), emptySet(), emptySet(), approved = false)
        assertTrue(plan.clear)
        assertEquals(emptySet<String>(), plan.prune)
        assertEquals(emptySet<String>(), plan.ask)
        assertEquals(emptySet<String>(), plan.delete)
    }

    @Test
    fun `the three outputs never overlap and never invent an id`() {
        val pending = setOf("a", "b", "c")
        val stillExcluded = setOf("b", "c", "d")
        val toAsk = setOf("c", "d")
        for (approved in listOf(true, false)) {
            val plan = planFilterRemovals(pending, stillExcluded, toAsk, approved)
            assertTrue(plan.prune.all { it in pending })
            assertTrue(plan.ask.all { it in toAsk })
            assertTrue(plan.delete.all { it in toAsk && it in pending })
            assertTrue(plan.ask.intersect(plan.delete).isEmpty())
            assertTrue(plan.prune.intersect(plan.ask).isEmpty())
        }
    }
}

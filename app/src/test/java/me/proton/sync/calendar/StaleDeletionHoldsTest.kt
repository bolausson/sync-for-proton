package me.proton.sync.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StaleDeletionHoldsTest {

    @Test
    fun `a held id with neither a mapping nor a row on the phone is stale`() {
        // The state seen on hardware: four ids held for an account whose local rows were wiped by a
        // forced Remote-to-Local resync, so nothing on the phone is waiting to be deleted.
        val held = setOf("e1", "e2", "e3", "e4")
        val sweep = sweepHolds(held, stillMapped = emptySet(), stillOnPhone = emptySet())
        assertEquals(held, sweep.stale)
        assertEquals(emptySet<String>(), sweep.remaining)
    }

    @Test
    fun `a mapping alone keeps a hold`() {
        val sweep = sweepHolds(setOf("e1", "e2"), stillMapped = setOf("e1"), stillOnPhone = emptySet())
        assertEquals(setOf("e2"), sweep.stale)
        assertEquals(setOf("e1"), sweep.remaining)
    }

    @Test
    fun `a provider row alone keeps a hold, even with no mapping`() {
        // A sync that died between inserting the row and writing its mapping leaves a row that still
        // carries its Proton id, and "Delete from phone" would still have something to remove.
        val sweep = sweepHolds(setOf("e1", "e2"), stillMapped = emptySet(), stillOnPhone = setOf("e1"))
        assertEquals(setOf("e2"), sweep.stale)
        assertEquals(setOf("e1"), sweep.remaining)
    }

    @Test
    fun `an unreadable provider keeps every hold`() {
        // The caller's contract: a failed look passes the whole held set as stillOnPhone.
        val held = setOf("e1", "e2", "e3")
        val sweep = sweepHolds(held, stillMapped = emptySet(), stillOnPhone = held)
        assertEquals(emptySet<String>(), sweep.stale)
        assertEquals(held, sweep.remaining)
    }

    @Test
    fun `the two halves are exactly the held set and nothing else`() {
        val held = setOf("a", "b", "c", "d", "e")
        val sweep = sweepHolds(held, stillMapped = setOf("a", "x"), stillOnPhone = setOf("b", "y"))
        assertEquals(held, sweep.stale + sweep.remaining)
        assertTrue(sweep.stale.intersect(sweep.remaining).isEmpty())
        assertTrue(sweep.stale.all { it in held })
        // Evidence for ids that are not held must not invent entries.
        assertEquals(setOf("c", "d", "e"), sweep.stale)
    }

    @Test
    fun `holds are judged account-wide, so one calendar's evidence is not enough`() {
        // Both addPending sites store event ids in one per-account set, so evidence gathered for a
        // single calendar would call every other calendar's holds stale. This is what the caller
        // must avoid, and the rule itself makes it visible: pass everything, get nothing stale.
        val held = setOf("a1", "a2", "b1", "b2")
        assertEquals(emptySet<String>(), sweepHolds(held, stillMapped = held, stillOnPhone = emptySet()).stale)
        val onlyCalendarA = sweepHolds(held, stillMapped = setOf("a1", "a2"), stillOnPhone = emptySet())
        assertEquals(setOf("b1", "b2"), onlyCalendarA.stale)
    }

    @Test
    fun `an empty hold set sweeps to nothing`() {
        val sweep = sweepHolds(emptySet(), stillMapped = setOf("e1"), stillOnPhone = setOf("e2"))
        assertEquals(emptySet<String>(), sweep.stale)
        assertEquals(emptySet<String>(), sweep.remaining)
    }
}

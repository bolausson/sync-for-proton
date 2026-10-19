package me.proton.sync.contacts

import org.junit.Assert.assertEquals
import org.junit.Test

/** How a contact too large for one provider call is divided: in order, within budget, nothing lost. */
class ContactWriteBudgetTest {

    @Test
    fun `everything that fits goes in one call`() {
        assertEquals(listOf(0..2), ContactWriteBudget.chunk(listOf(10, 20, 30), budget = 60))
    }

    @Test
    fun `rows are split in order without exceeding the budget`() {
        // Ten notes of 220 KB each, the shape of the contact that used to fail on every sync.
        val sizes = listOf(2_000) + List(10) { 220_000 }
        val ranges = ContactWriteBudget.chunk(sizes, budget = 500_000)
        assertEquals(listOf(0..2, 3..4, 5..6, 7..8, 9..10), ranges)
        for (range in ranges) assertEquals(true, range.sumOf { sizes[it] } <= 500_000)
        assertEquals(sizes.indices.toList(), ranges.flatMap { it.toList() })
    }

    @Test
    fun `an oversized row gets a call of its own`() {
        assertEquals(listOf(0..0, 1..1, 2..2), ContactWriteBudget.chunk(listOf(5, 90, 5), budget = 50))
    }

    @Test
    fun `no rows, no calls`() {
        assertEquals(emptyList<IntRange>(), ContactWriteBudget.chunk(emptyList(), budget = 50))
    }
}

package me.proton.sync.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bulk-deletion guard's rules ([BulkDeletionGuard.isSuspicious]) with the local count it is given since 2.2.0:
 * only mapped rows still on the phone ([BulkDeletionGuard.mappedRowsPresent]). An event that exists only on the phone,
 * held for the user's choice or kept there, used to count as local, which switched the whole-calendar rule off for a
 * calendar whose every synced event had vanished.
 */
class BulkDeletionGuardTest {

    @Test
    fun `only mapped rows still on the phone are counted, each once`() {
        assertEquals(2, BulkDeletionGuard.mappedRowsPresent(listOf(1L, 2L, 3L), setOf(1L, 2L, 50L, 51L)))
        assertEquals(0, BulkDeletionGuard.mappedRowsPresent(listOf(1L), setOf(50L, 51L)))
        assertEquals(1, BulkDeletionGuard.mappedRowsPresent(listOf(1L, 1L), setOf(1L)))
        assertEquals(0, BulkDeletionGuard.mappedRowsPresent(emptyList(), setOf(1L)))
    }

    @Test
    fun `a calendar whose only synced event vanished is held although phone-only rows remain`() {
        val existing = setOf(50L, 51L)                 // a waiting and a kept event, never on Proton
        val present = BulkDeletionGuard.mappedRowsPresent(listOf(9L), existing)
        assertTrue(BulkDeletionGuard.isSuspicious(deletedCount = 1, localCount = present, totalMappings = 1))
        // Counted the old way, the two phone-only rows hid it.
        assertFalse(BulkDeletionGuard.isSuspicious(deletedCount = 1, localCount = existing.size, totalMappings = 1))
    }

    @Test
    fun `the other rules are unchanged`() {
        assertFalse(BulkDeletionGuard.isSuspicious(0, 0, 5))
        assertFalse(BulkDeletionGuard.isSuspicious(3, 7, 10))
        assertFalse(BulkDeletionGuard.isSuspicious(BulkDeletionGuard.ABS_MIN - 1, 1, BulkDeletionGuard.ABS_MIN))
        assertTrue(BulkDeletionGuard.isSuspicious(BulkDeletionGuard.ABS_MIN, 10, 20))
        assertFalse(BulkDeletionGuard.isSuspicious(BulkDeletionGuard.ABS_MIN, 30, 40))
        assertFalse(BulkDeletionGuard.isSuspicious(1, 0, 0))
    }
}

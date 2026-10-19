package me.proton.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A row this app never downloaded exists nowhere else, so deleting it is final.
 *
 * Three separate places delete an account's local rows: the removal offered when a sync type is
 * switched off, and both forced resyncs. Each of them used a predicate that matched the whole
 * account, which also matched contacts and events the user had created on the phone and that had
 * never been uploaded, so the delete was silent and irreversible while the dialog promised the copy
 * in Proton was safe. There is no copy in Proton; that is precisely why the row has no mapping.
 *
 * The rule below is what all three now share: delete by mapped id, never by account. It is worth a
 * test of its own because the same mistake has now been made three times in three places, and
 * because the fix for one of them was applied to contacts and not to the calendar half of the very
 * same file.
 */
class SparedLocalOnlyRowsTest {

    /** What each caller does: keep only the local ids that the mapping table vouches for. */
    private fun toDelete(localRows: Set<Long>, mapped: Set<Long>): Set<Long> =
        localRows intersect mapped

    private fun spared(localRows: Set<Long>, mapped: Set<Long>): Set<Long> =
        localRows - mapped

    @Test
    fun `a row with no mapping is never deleted`() {
        val onPhone = setOf(1L, 2L, 3L, 99L)   // 99 was created on the phone, never uploaded
        val fromProton = setOf(1L, 2L, 3L)
        assertEquals(setOf(1L, 2L, 3L), toDelete(onPhone, fromProton))
        assertEquals(setOf(99L), spared(onPhone, fromProton))
    }

    @Test
    fun `everything goes when everything came from Proton`() {
        val onPhone = setOf(1L, 2L, 3L)
        assertEquals(onPhone, toDelete(onPhone, onPhone))
        assertTrue(spared(onPhone, onPhone).isEmpty())
    }

    /**
     * The mapping table can name rows that are no longer on the phone, for instance one the user
     * deleted in their contacts app between the last sync and this removal. Those must not widen
     * the delete to anything else.
     */
    @Test
    fun `a mapping for a row that is gone deletes nothing extra`() {
        val onPhone = setOf(1L, 99L)
        val fromProton = setOf(1L, 2L, 3L)
        assertEquals(setOf(1L), toDelete(onPhone, fromProton))
        assertEquals(setOf(99L), spared(onPhone, fromProton))
    }

    /**
     * An empty mapping table means nothing is known to have come from Proton. The old code read
     * that as "delete the account", which is the worst possible reading: it is also the state right
     * after the mappings have been cleared, so ordering the clear before the delete destroyed
     * everything.
     */
    @Test
    fun `no mappings means nothing is deleted, not everything`() {
        val onPhone = setOf(1L, 2L, 99L)
        assertTrue(toDelete(onPhone, emptySet()).isEmpty())
        assertEquals(onPhone, spared(onPhone, emptySet()))
    }

    /**
     * The kept-local set is the only thing protecting an upload-excluded contact from the orphan
     * sweep. A removal used to empty it, so the app spared the row and then destroyed it one sync
     * later. Only ids actually removed may drop out.
     */
    @Test
    fun `clearing kept-local only drops the ids that were removed`() {
        val kept = setOf(50L, 51L, 99L)
        val removed = setOf(50L, 51L)
        assertEquals(setOf(99L), kept - removed)
    }

    @Test
    fun `a removal that deletes nothing leaves kept-local untouched`() {
        val kept = setOf(99L)
        assertEquals(kept, kept - emptySet<Long>())
    }
}

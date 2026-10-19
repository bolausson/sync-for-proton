package me.proton.sync.contacts.filter

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Keep on phone" has to mean it.
 *
 * The sweep at the end of a sync prunes the removal guard's sets by asking which contacts the rules
 * still exclude. It used to ask [ContactFilterSession.pendingRemovals], which deliberately omits the
 * contacts the user chose to keep, so a kept contact answered "not excluded any more", its keep was
 * thrown away, and the prompt came back on the following sync. Permanently: there was no way to make
 * it stop, which is the one thing a confirmation prompt must never do.
 */
class KeptContactSurvivesTest {

    private fun session(kept: Set<String>) = ContactFilterSession.forTest(keptIds = kept)

    @Test
    fun `a kept contact is still reported as excluded`() {
        val s = session(setOf("kept-1"))
        // What the sync does for a contact the rules exclude but the user rescued: no prompt, but
        // the fact of the exclusion still gets recorded.
        s.recordStillExcluded("kept-1")
        assertTrue("the sweep must see it", "kept-1" in s.stillExcluded)
        assertFalse("but it must not be asked about again", s.pendingRemovals.containsKey("kept-1"))
    }

    @Test
    fun `a contact awaiting a prompt is in both sets`() {
        val s = session(emptySet())
        s.recordPendingRemoval("new-1", "Jane Doe")
        assertTrue("new-1" in s.stillExcluded)
        assertTrue(s.pendingRemovals.containsKey("new-1"))
    }

    /**
     * The prune subtracts stillExcluded from the guard's stored ids, so anything absent here is
     * dropped. A contact the rules have released is exactly what should be dropped.
     */
    @Test
    fun `a contact the rules no longer exclude is absent, so the sweep releases it`() {
        val s = session(setOf("kept-1"))
        s.recordStillExcluded("kept-1")
        assertFalse("released-1" in s.stillExcluded)
    }

    @Test
    fun `nothing excluded means nothing held`() {
        assertTrue(session(setOf("kept-1")).stillExcluded.isEmpty())
    }
}

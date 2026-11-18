package me.proton.sync

import me.proton.sync.contacts.FakePrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Held server-to-local deletions dropped without the question (an external calendar replaced under new ids). The keys
 * are written out on purpose: they live in the user's preferences across versions.
 */
class RemoteDeletionGuardTest {

    private val pending = "remote_del_pending_calendar_acct"
    private val count = "remote_del_count_calendar_acct"
    private val approved = "remote_del_approved_calendar_acct"

    private fun held(vararg ids: String, approve: Boolean) = FakePrefs().apply {
        values[pending] = ids.toSet()
        values[count] = ids.size
        values[approved] = approve
    }

    @Test
    fun `forgetting some held ids keeps the others and their approval`() {
        val p = held("a", "b", "c", approve = true)
        RemoteDeletionGuard.forgetPending(p, RemoteDeletionGuard.TYPE_CALENDAR, "acct", setOf("a", "x"))
        assertEquals(setOf("b", "c"), p.values[pending])
        assertEquals(2, p.values[count])
        assertEquals(true, p.values[approved])
    }

    @Test
    fun `forgetting the last held id drops the approval with it`() {
        // Otherwise the next hold, which the user never saw, would already count as approved and be deleted unasked.
        val p = held("a", "b", approve = true)
        RemoteDeletionGuard.forgetPending(p, RemoteDeletionGuard.TYPE_CALENDAR, "acct", setOf("a", "b"))
        assertFalse(p.values.containsKey(pending))
        assertFalse(p.values.containsKey(count))
        assertFalse(p.values.containsKey(approved))
    }

    @Test
    fun `ids that are not held change nothing`() {
        val p = held("a", approve = true)
        RemoteDeletionGuard.forgetPending(p, RemoteDeletionGuard.TYPE_CALENDAR, "acct", setOf("x"))
        assertEquals(setOf("a"), p.values[pending])
        assertEquals(1, p.values[count])
        assertEquals(true, p.values[approved])
        val empty = FakePrefs()
        RemoteDeletionGuard.forgetPending(empty, RemoteDeletionGuard.TYPE_CALENDAR, "acct", setOf("x"))
        assertEquals(emptyMap<String, Any?>(), empty.values)
    }

    @Test
    fun `re-keyed marks add up, and only the ones still mapped are kept`() {
        val p = FakePrefs()
        RemoteDeletionGuard.markRekeyed(p, "acct", setOf("p1", "p2"))
        RemoteDeletionGuard.markRekeyed(p, "acct", setOf("p3"))
        assertEquals(setOf("p1", "p2", "p3"), p.values["remote_del_rekeyed_calendar_acct"])
        assertEquals(setOf("p1", "p2", "p3"), RemoteDeletionGuard.rekeyedIds(p, "acct"))
        RemoteDeletionGuard.keepOnlyRekeyed(p, "acct", setOf("p2", "x"))
        assertEquals(setOf("p2"), RemoteDeletionGuard.rekeyedIds(p, "acct"))
        RemoteDeletionGuard.keepOnlyRekeyed(p, "acct", emptySet())
        assertFalse(p.values.containsKey("remote_del_rekeyed_calendar_acct"))
        assertEquals(emptySet<String>(), RemoteDeletionGuard.rekeyedIds(p, "other"))
    }

    @Test
    fun `re-keyed marks are written through before the mappings move`() {
        // The re-key moves the mappings in Room right after marking them. Written with apply(), a crash in between
        // could lose the marks, and the moved events would then be replaced like a feed's without the question.
        val p = FakePrefs()
        RemoteDeletionGuard.markRekeyed(p, "acct", setOf("p1"))
        assertEquals(1, p.commits)
        RemoteDeletionGuard.markRekeyed(p, "acct", emptySet())
        assertEquals(1, p.commits)
    }

    @Test
    fun `another account's or type's holds are untouched`() {
        val p = held("a", approve = true)
        p.values["remote_del_pending_calendar_other"] = setOf("a")
        p.values["remote_del_pending_contacts_acct"] = setOf("a")
        RemoteDeletionGuard.forgetPending(p, RemoteDeletionGuard.TYPE_CALENDAR, "acct", setOf("a"))
        assertEquals(setOf("a"), p.values["remote_del_pending_calendar_other"])
        assertEquals(setOf("a"), p.values["remote_del_pending_contacts_acct"])
    }
}

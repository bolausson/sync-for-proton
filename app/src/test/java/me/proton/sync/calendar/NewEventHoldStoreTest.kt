package me.proton.sync.calendar

import me.proton.sync.SyncPreferences
import me.proton.sync.calendar.NewEventHoldRules.Entry
import me.proton.sync.calendar.NewEventHoldRules.State
import me.proton.sync.contacts.FakePrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The held new events of one account ([NewEventHoldStore]): one string per series in one StringSet, every change a
 * single write, the user's answers committed, and every transition a compare-and-set on the exact stored string, so
 * two answers at once, or an answer and a sync, can never both apply. Made-up account and values only.
 */
class NewEventHoldStoreTest {

    private val account = "user@example.net"

    private fun entry(id: Long, state: State = State.WAITING, cal: Long = 7, writer: String? = "com.example.booking", uid: String? = null, at: Long = 1000 + id) =
        Entry(state, id, cal, at, writer, uid)

    @Test
    fun `an entry survives the round trip, a colon in the writer and a bar in the UID included`() {
        val e = entry(5, State.KEPT, writer = "android.uid.calendar:10045", uid = "a|b|c@example.test")
        assertEquals("K|5|7|1005|android.uid.calendar:10045|a|b|c@example.test", NewEventHoldStore.encode(e))
        assertEquals(e, NewEventHoldStore.decode(NewEventHoldStore.encode(e)))
        // Empty fields read as null.
        val bare = entry(6, writer = null, uid = null)
        assertEquals("W|6|7|1006||", NewEventHoldStore.encode(bare))
        assertEquals(bare, NewEventHoldStore.decode("W|6|7|1006||"))
    }

    @Test
    fun `a malformed entry is ignored, and kept as it was`() {
        val prefs = FakePrefs()
        val good = NewEventHoldStore.encode(entry(8))
        val junk = setOf("", "W|8", "X|9|7|1|a|b", "W|x|7|1||", "W|9|7|soon||", "WW|9|7|1||", "W|0|7|1||")
        for (j in junk) assertNull(j, NewEventHoldStore.decode(j))
        prefs.edit().putStringSet(SyncPreferences.newEventHoldsKey(account), junk + good).apply()
        assertEquals(listOf(entry(8)), NewEventHoldStore.snapshot(prefs, account))
        NewEventHoldStore.addHolds(prefs, account, listOf(entry(10)))
        assertTrue(prefs.getStringSet(SyncPreferences.newEventHoldsKey(account), null)!!.containsAll(junk))
    }

    @Test
    fun `entries come oldest first, and a parent row gets one entry`() {
        val prefs = FakePrefs()
        NewEventHoldStore.addHolds(prefs, account, listOf(entry(3, at = 30), entry(1, at = 10), entry(2, at = 20)))
        assertEquals(listOf(1L, 2L, 3L), NewEventHoldStore.snapshot(prefs, account).map { it.eventId })
        // A second hold of the same row, in any state, is not added.
        NewEventHoldStore.addHolds(prefs, account, listOf(entry(1, State.KEPT, at = 40)))
        assertEquals(3, NewEventHoldStore.snapshot(prefs, account).size)
        assertEquals(State.WAITING, NewEventHoldStore.snapshot(prefs, account).first().state)
        // The same id in another calendar is another row.
        NewEventHoldStore.addHolds(prefs, account, listOf(entry(1, cal = 8, at = 50)))
        assertEquals(4, NewEventHoldStore.snapshot(prefs, account).size)
    }

    @Test
    fun `a transition applies only while the stored entry is unchanged`() {
        val prefs = FakePrefs()
        val w = entry(4)
        NewEventHoldStore.addHolds(prefs, account, listOf(w))
        val k = w.copy(state = State.KEPT)
        val p = k.copy(state = State.PUBLISHED)
        assertTrue(NewEventHoldStore.transition(prefs, account, w, k, commit = true))
        // The waiting entry is gone: a second answer to it loses.
        assertFalse(NewEventHoldStore.transition(prefs, account, w, w.copy(state = State.PUBLISHED), commit = true))
        assertFalse(NewEventHoldStore.transition(prefs, account, w, null, commit = true))
        assertTrue(NewEventHoldStore.transition(prefs, account, k, p, commit = true))
        assertEquals(listOf(p), NewEventHoldStore.snapshot(prefs, account))
        assertTrue(NewEventHoldStore.transition(prefs, account, p, null, commit = true))
        assertTrue(NewEventHoldStore.snapshot(prefs, account).isEmpty())
        // Waiting straight to published.
        NewEventHoldStore.addHolds(prefs, account, listOf(w))
        assertTrue(NewEventHoldStore.transition(prefs, account, w, w.copy(state = State.PUBLISHED), commit = true))
        assertEquals(State.PUBLISHED, NewEventHoldStore.snapshot(prefs, account).single().state)
    }

    @Test
    fun `the user's answers are committed, the sync's own writes applied`() {
        val prefs = FakePrefs()
        val w = entry(11)
        val other = entry(12)
        NewEventHoldStore.addHolds(prefs, account, listOf(w, other))
        // A drop that removes a stored entry, and one of nothing stored: neither commits.
        NewEventHoldStore.drop(prefs, account, listOf(other))
        NewEventHoldStore.drop(prefs, account, listOf(entry(99)))
        assertEquals(listOf(w), NewEventHoldStore.snapshot(prefs, account))
        assertEquals(0, prefs.commits)
        assertTrue(NewEventHoldStore.transition(prefs, account, w, w.copy(state = State.KEPT), commit = true))
        assertEquals(1, prefs.commits)
        val k = w.copy(state = State.KEPT)
        assertTrue(NewEventHoldStore.transition(prefs, account, k, null, commit = false))
        assertEquals(1, prefs.commits)
    }

    @Test
    fun `an answer whose disk write failed is in effect, and says so`() {
        // commit() changes what the process reads before it writes the file and reports the file write alone.
        val prefs = FakePrefs().apply { commitResult = false }
        val w = entry(13)
        NewEventHoldStore.addHolds(prefs, account, listOf(w))
        val p = w.copy(state = State.PUBLISHED)
        assertTrue(NewEventHoldStore.transition(prefs, account, w, p, commit = true))
        assertEquals(listOf(p), NewEventHoldStore.snapshot(prefs, account))
        assertTrue(NewEventHoldStore.transition(prefs, account, p, null, commit = true))
        assertTrue(NewEventHoldStore.snapshot(prefs, account).isEmpty())
        // A transition that found nothing to change still answers false.
        assertFalse(NewEventHoldStore.transition(prefs, account, w, p, commit = true))
    }

    @Test
    fun `a question passes to another row of its series only while it is unchanged and that row has none`() {
        val prefs = FakePrefs()
        val k = entry(14, State.KEPT, uid = "s14@example.test")
        NewEventHoldStore.addHolds(prefs, account, listOf(k, entry(16)))
        assertTrue(NewEventHoldStore.isStored(prefs, account, k))
        val next = k.copy(eventId = 15)
        assertTrue(NewEventHoldStore.passOn(prefs, account, k, next))
        assertFalse(NewEventHoldStore.isStored(prefs, account, k))
        assertTrue(NewEventHoldStore.isStored(prefs, account, next))
        // The old one is gone, so a second pass is refused; so is one onto a row that has an entry of its own.
        assertFalse(NewEventHoldStore.passOn(prefs, account, k, k.copy(eventId = 17)))
        assertFalse(NewEventHoldStore.passOn(prefs, account, next, next.copy(eventId = 16)))
        assertEquals(listOf(15L, 16L), NewEventHoldStore.snapshot(prefs, account).map { it.eventId }.sorted())
        assertEquals(0, prefs.commits)
    }

    @Test
    fun `published events of a calendar another Proton calendar took over, or Proton removed, wait again`() {
        val prefs = FakePrefs()
        val published = entry(18, State.PUBLISHED, cal = 7)
        val kept = entry(19, State.KEPT, cal = 7)
        val elsewhere = entry(20, State.PUBLISHED, cal = 8)
        val older = entry(17, State.PUBLISHED, cal = 7, at = 900)
        NewEventHoldStore.addHolds(prefs, account, listOf(published, kept, elsewhere, older))
        // The ones that wait again, as stored now, oldest first: the first is the prompt that alerts.
        assertEquals(
            listOf(older.copy(state = State.WAITING), published.copy(state = State.WAITING)),
            NewEventHoldStore.unpublishCalendar(prefs, account, 7)
        )
        assertEquals(
            listOf(older.copy(state = State.WAITING), published.copy(state = State.WAITING), kept, elsewhere),
            NewEventHoldStore.snapshot(prefs, account)
        )
        assertTrue(NewEventHoldStore.unpublishCalendar(prefs, account, 7).isEmpty())
        assertTrue(NewEventHoldStore.unpublishCalendar(prefs, account, 9).isEmpty())
        assertEquals(0, prefs.commits)
    }

    @Test
    fun `drop removes exactly the entries given, as stored`() {
        val prefs = FakePrefs()
        val a = entry(21)
        val b = entry(22)
        val c = entry(23, State.KEPT)
        NewEventHoldStore.addHolds(prefs, account, listOf(a, b, c))
        // b changed meanwhile: the drop of the old b leaves the new one.
        val b2 = b.copy(state = State.KEPT)
        assertTrue(NewEventHoldStore.transition(prefs, account, b, b2, commit = true))
        NewEventHoldStore.drop(prefs, account, listOf(a, b))
        assertEquals(listOf(b2, c), NewEventHoldStore.snapshot(prefs, account))
    }

    @Test
    fun `counts are waiting and kept series, and forget removes the account's key`() {
        val prefs = FakePrefs()
        NewEventHoldStore.addHolds(
            prefs, account,
            listOf(entry(31), entry(32), entry(33, State.KEPT), entry(34, State.PUBLISHED)),
        )
        assertEquals(NewEventHoldStore.Counts(waiting = 2, kept = 1), NewEventHoldStore.counts(prefs, account))
        assertEquals(NewEventHoldStore.Counts(0, 0), NewEventHoldStore.counts(prefs, "other@example.net"))
        NewEventHoldStore.forget(prefs, account)
        assertFalse(prefs.contains(SyncPreferences.newEventHoldsKey(account)))
        assertTrue(NewEventHoldStore.snapshot(prefs, account).isEmpty())
    }
}

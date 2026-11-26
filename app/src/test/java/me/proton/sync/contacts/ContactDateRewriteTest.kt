package me.proton.sync.contacts

import me.proton.sync.contacts.ContactDateRewrite.CANARY_SETTLE_MS
import me.proton.sync.contacts.ContactDateRewrite.EventRow
import me.proton.sync.contacts.ContactDateRewrite.Outcome
import me.proton.sync.contacts.ContactDateRewrite.RECHECK_MS
import me.proton.sync.contacts.ContactDateRewrite.Rewrite
import me.proton.sync.contacts.ContactDateRewrite.SETTLE_MS
import me.proton.sync.contacts.ContactDateRewrite.Write
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The contact-date pass ([ContactDateRewrite]). Its promise: re-spelling a date never makes Android see the contact as
 * edited, so nothing is uploaded; a date changed meanwhile is never overwritten; and if a provider marks such a contact
 * as edited after all, the pass notices during its waits and stops. The decisions are driven through a fake [Store];
 * the provider side, which no JVM test can run, is pinned by source guards.
 */
class ContactDateRewriteTest {

    private val birthday = android.provider.ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY
    private val anniversary = android.provider.ContactsContract.CommonDataKinds.Event.TYPE_ANNIVERSARY
    private val other = android.provider.ContactsContract.CommonDataKinds.Event.TYPE_OTHER
    private val custom = android.provider.ContactsContract.CommonDataKinds.Event.TYPE_CUSTOM

    // ---- Which rows ----

    @Test
    fun `plans the basic-form birthdays and anniversaries of contacts whose copy is current`() {
        val rows = listOf(
            EventRow(1, 10, birthday, "19900215"),
            EventRow(2, 10, anniversary, "20000101"),
            EventRow(3, 11, birthday, "--0215"),
            EventRow(4, 12, birthday, "1990-02-15"),
            EventRow(5, 13, other, "19900215"),
            EventRow(6, 14, custom, "19900215"),
            EventRow(7, 15, birthday, null),
            EventRow(8, 99, birthday, "19900215"),
            EventRow(9, 16, birthday, "15.02.1990"),
            EventRow(10, 17, anniversary, "19900230"),
        )
        val plan = ContactDateRewrite.plan(rows, currentRawIds = setOf(10L, 11L, 12L, 13L, 14L, 15L, 16L, 17L))
        assertEquals(
            listOf(
                Rewrite(1, 10, "19900215", "1990-02-15"),
                Rewrite(2, 10, "20000101", "2000-01-01"),
                Rewrite(3, 11, "--0215", "--02-15"),
            ),
            plan,
        )
    }

    @Test
    fun `a second plan over the re-spelled rows is empty`() {
        val rows = listOf(EventRow(1, 10, birthday, "19900215"), EventRow(2, 10, anniversary, "--0215"))
        val first = ContactDateRewrite.plan(rows, setOf(10L))
        assertEquals(2, first.size)
        val after = first.map { EventRow(it.dataId, it.rawContactId, rows.first { r -> r.dataId == it.dataId }.type, it.new) }
        assertEquals(emptyList<Rewrite>(), ContactDateRewrite.plan(after, setOf(10L)))
    }

    @Test
    fun `contacts not known to be current are left alone`() {
        assertEquals(emptyList<Rewrite>(), ContactDateRewrite.plan(listOf(EventRow(1, 10, birthday, "19900215")), emptySet()))
    }

    @Test
    fun `a copy counts as current from Proton's time up to a minute after it`() {
        assertTrue("a download records Proton's time", ContactDateRewrite.isCurrent(lastModified = 1_000, serverTime = 1_000))
        assertTrue("an upload, as its reply came back", ContactDateRewrite.isCurrent(1_060, 1_000))
        assertFalse("a phone clock running ahead", ContactDateRewrite.isCurrent(1_061, 1_000))
        assertFalse("changed on Proton since", ContactDateRewrite.isCurrent(999, 1_000))
    }

    @Test
    fun `full dates go first, dates without a year last`() {
        val rows = listOf(Rewrite(1, 10, "--0215", "--02-15"), Rewrite(2, 11, "19900215", "1990-02-15"), Rewrite(3, 12, "20000101", "2000-01-01"))
        assertEquals(listOf(2L, 3L, 1L), ContactDateRewrite.ordered(rows).map { it.dataId })
    }

    @Test
    fun `a row printed in a log line shows no date`() {
        val printed = listOf(Rewrite(1, 10, "19900215", "1990-02-15"), EventRow(1, 10, birthday, "19900215")).toString()
        assertFalse(printed, printed.contains("1990") || printed.contains("0215"))
    }

    // ---- What the pass does, against a fake provider ----

    /**
     * A provider holding [plan]'s rows, every contact clean but [editedAtStart], with a clock of its own: a wait moves
     * it on by the wait's length, a write call by [writeMsPerRow] per row, and the call's rows commit when it returns.
     * [marksOnWrite]: raw ids it marks as edited as soon as one of their rows is written (a second row of that contact
     * in the same call is then refused, as after the real batch's rollback and row-by-row retry). [lagMs], when set: it
     * marks every contact written [lagMs] after the write commits, or with [batchesOnly] only those written by a call of
     * more than one row. [beforeWrite] (by data id) and [duringPause] (by wait, counted from 1) are what else happens
     * meanwhile, such as a user's edits; [interruptAt] is a wait that is interrupted.
     */
    private class FakeStore(
        plan: List<Rewrite>,
        editedAtStart: Set<Long> = emptySet(),
        val marksOnWrite: Set<Long> = emptySet(),
        val lagMs: Long? = null,
        val batchesOnly: Boolean = false,
        val writeMsPerRow: Long = 8,
    ) : ContactDateRewrite.Store {
        val texts = HashMap<Long, String?>().apply { plan.forEach { put(it.dataId, it.old) } }
        val dirtyNow = HashSet(editedAtStart)
        val deleted = HashSet<Long>()
        val beforeWrite = HashMap<Long, () -> Unit>()
        val duringPause = HashMap<Int, () -> Unit>()
        var interruptAt = 0
        val batches = ArrayList<List<Long>>()
        val waits = ArrayList<Long>()
        var clock = 0L
            private set
        private val markAt = HashMap<Long, Long>()

        private fun advance(ms: Long) {
            clock += ms
            val due = markAt.filterValues { it <= clock }.keys
            dirtyNow.addAll(due)
            due.forEach { markAt.remove(it) }
        }

        override fun now(): Long = clock

        override fun clean(rawContactIds: Set<Long>): Set<Long> =
            rawContactIds.filterTo(HashSet()) { it !in dirtyNow && it !in deleted }

        override fun write(rows: List<Rewrite>): List<Write> {
            batches.add(rows.map { it.dataId })
            val out = rows.map { row ->
                beforeWrite[row.dataId]?.invoke()
                when {
                    row.rawContactId in dirtyNow || row.rawContactId in deleted -> Write.REFUSED
                    texts[row.dataId] != row.old -> Write.NO_MATCH
                    else -> {
                        texts[row.dataId] = row.new
                        if (row.rawContactId in marksOnWrite) dirtyNow.add(row.rawContactId)
                        Write.WRITTEN
                    }
                }
            }
            advance(writeMsPerRow * rows.size)
            val lag = lagMs
            if (lag != null && (!batchesOnly || rows.size > 1)) {
                rows.forEachIndexed { i, row -> if (out[i] == Write.WRITTEN) markAt.putIfAbsent(row.rawContactId, clock + lag) }
            }
            return out
        }

        override fun dirty(rawContactIds: Set<Long>): Set<Long> = rawContactIds.filterTo(HashSet()) { it in dirtyNow }

        override fun changedElsewhere(rawContactId: Long, expected: Map<Long, String>): Boolean =
            rawContactId in deleted || expected.any { (dataId, text) -> texts[dataId] != text }

        override fun pause(ms: Long): Boolean {
            waits.add(ms)
            if (waits.size == interruptAt) return false
            advance(ms)
            duringPause[waits.size]?.invoke()
            return true
        }

        /** A user's edit of something other than a date: the contact is marked as edited, its dates stay. */
        fun touch(rawContactId: Long) {
            dirtyNow.add(rawContactId)
        }

        /** A user's edit of one date. */
        fun editDate(dataId: Long, rawContactId: Long, text: String) {
            texts[dataId] = text
            dirtyNow.add(rawContactId)
        }
    }

    /** [n] full-date rows, one contact each, data id = raw id = 1..n. */
    private fun rows(n: Int): List<Rewrite> = (1..n).map { Rewrite(it.toLong(), it.toLong(), "19900215", "1990-02-15") }

    private class Result(val outcome: Outcome, val halts: Int, val strikes: Int)

    private fun run(store: FakeStore, plan: List<Rewrite>, struck: Boolean = false): Result {
        var halts = 0
        var strikes = 0
        val outcome = ContactDateRewrite.apply(store, plan, struck, strike = { strikes++ }, halt = { halts++ })
        return Result(outcome, halts, strikes)
    }

    @Test
    fun `a clean provider gets a canary, a long wait, then chunks of 50 each followed by a wait`() {
        val store = FakeStore(rows(120))
        val r = run(store, rows(120))
        assertEquals(listOf(1, 50, 50, 19), store.batches.map { it.size })
        assertEquals(listOf(CANARY_SETTLE_MS, SETTLE_MS, SETTLE_MS, SETTLE_MS), store.waits)
        assertEquals(Outcome(written = 120, waiting = 0, changed = 0), r.outcome)
        assertEquals(0, r.halts)
        assertEquals(0, r.strikes)
    }

    @Test
    fun `a run stops starting chunks after its budget, stays under a minute, and leaves the rest to the next sync`() {
        val store = FakeStore(rows(1000))
        val r = run(store, rows(1000))
        assertEquals(listOf(1) + List(7) { 50 }, store.batches.map { it.size })
        assertEquals(listOf(CANARY_SETTLE_MS) + List(7) { SETTLE_MS }, store.waits)
        assertEquals(Outcome(written = 351, waiting = 0, changed = 0, deferred = true), r.outcome)
        assertTrue("the run took ${store.clock} ms", store.clock + RECHECK_MS < 60_000)
        assertEquals(0, r.halts)
        assertEquals(0, r.strikes)
        val rest = rows(1000).drop(351)
        val next = FakeStore(rest)
        assertEquals("the next sync goes on", 351, run(next, rest).outcome.written)
    }

    @Test
    fun `a canary with nothing after it waits like a chunk`() {
        val store = FakeStore(rows(1))
        val r = run(store, rows(1))
        assertEquals(listOf(SETTLE_MS), store.waits)
        assertEquals(Outcome(written = 1, waiting = 0, changed = 0), r.outcome)
        val waiting = FakeStore(rows(2), editedAtStart = setOf(2L))
        run(waiting, rows(2))
        assertEquals("the only row it can write", listOf(SETTLE_MS), waiting.waits)
    }

    @Test
    fun `contacts with an unsent edit wait and are not written`() {
        val store = FakeStore(rows(3), editedAtStart = setOf(3L))
        val r = run(store, rows(3))
        assertEquals(listOf(listOf(1L), listOf(2L)), store.batches)
        assertEquals(Outcome(written = 2, waiting = 1, changed = 0), r.outcome)
    }

    @Test
    fun `a canary the provider marks as edited halts before anything else is written`() {
        val store = FakeStore(rows(80), marksOnWrite = setOf(1L))
        val r = run(store, rows(80))
        assertEquals(listOf(listOf(1L)), store.batches)
        assertEquals(1, r.halts)
        assertTrue(r.outcome.halted)
    }

    @Test
    fun `the canary is a full date when one is left`() {
        val plan = listOf(Rewrite(1, 1, "--0215", "--02-15"), Rewrite(2, 2, "19900215", "1990-02-15"))
        val store = FakeStore(plan)
        run(store, plan)
        assertEquals(listOf(2L), store.batches.first())
    }

    @Test
    fun `a refused canary is followed by the next row, never by the chunks`() {
        val store = FakeStore(rows(10))
        store.beforeWrite[1L] = { store.touch(1L) }
        val r = run(store, rows(10))
        assertEquals(listOf(listOf(1L), listOf(2L), (3L..10L).toList()), store.batches)
        assertEquals(0, r.halts)
        assertEquals(Outcome(written = 9, waiting = 0, changed = 1), r.outcome)
    }

    @Test
    fun `when no canary can be written the run stops without a halt and without the chunks`() {
        val store = FakeStore(rows(10))
        for (id in 1L..3L) store.beforeWrite[id] = { store.touch(id) }
        val r = run(store, rows(10))
        assertEquals(listOf(listOf(1L), listOf(2L), listOf(3L)), store.batches)
        assertEquals(0, r.halts)
        assertTrue(r.outcome.stopped)
        assertEquals(0, r.outcome.written)
    }

    @Test
    fun `a contact edited before its own write is skipped, not taken for the provider's`() {
        val store = FakeStore(rows(60))
        store.beforeWrite[30L] = { store.touch(30L) }
        val r = run(store, rows(60))
        assertEquals(0, r.halts)
        assertEquals(0, r.strikes)
        assertEquals(Outcome(written = 59, waiting = 0, changed = 1), r.outcome)
    }

    @Test
    fun `a contact the provider marks as edited in a chunk halts after that chunk`() {
        val store = FakeStore(rows(120), marksOnWrite = setOf(40L))
        val r = run(store, rows(120))
        assertEquals(listOf(1, 50), store.batches.map { it.size })
        assertEquals(1, r.halts)
        assertTrue(r.outcome.halted)
    }

    @Test
    fun `a dirty contact whose date someone else changed meanwhile ends the run without a halt`() {
        val store = FakeStore(rows(120))
        store.duringPause[2] = { store.editDate(40L, 40L, "1990-02-16") }
        val r = run(store, rows(120))
        assertEquals(listOf(1, 50), store.batches.map { it.size })
        assertEquals(0, r.halts)
        assertEquals(0, r.strikes)
        assertTrue(r.outcome.stopped)
    }

    @Test
    fun `a contact deleted meanwhile ends the run without a halt`() {
        val store = FakeStore(rows(120))
        store.duringPause[2] = {
            store.deleted.add(40L)
            store.dirtyNow.add(40L)
        }
        val r = run(store, rows(120))
        assertEquals(0, r.halts)
        assertTrue(r.outcome.stopped)
    }

    /** rows(60) with a second date for contact 10, right behind its first, so both go in chunk 1. */
    private fun twoDatesForTen(): List<Rewrite> =
        rows(60).toMutableList().apply { add(10, Rewrite(110, 10, "20000101", "2000-01-01")) }

    @Test
    fun `a contact found edited with one of its two dates changed shows the user's edit`() {
        val plan = twoDatesForTen()
        val store = FakeStore(plan)
        store.duringPause[2] = { store.editDate(110L, 10L, "2000-01-02") }
        val r = run(store, plan)
        assertTrue("both of contact 10's dates went in chunk 1", store.batches[1].containsAll(listOf(10L, 110L)))
        assertEquals(0, r.halts)
        assertTrue(r.outcome.stopped)
    }

    @Test
    fun `a contact found edited with both dates as written counts against the provider`() {
        val plan = twoDatesForTen()
        val store = FakeStore(plan)
        store.duringPause[2] = { store.touch(10L) }
        val r = run(store, plan)
        assertEquals(1, r.halts)
        assertTrue(r.outcome.halted)
    }

    /** rows(60) and a year-less date for contact 5, which ordered() puts in the last chunk: two steps for one contact. */
    private fun yearlessForFive(): List<Rewrite> = rows(60) + Rewrite(205, 5, "--0620", "--06-20")

    @Test
    fun `a date the pass has not reached yet, changed meanwhile, shows the user's edit`() {
        val plan = yearlessForFive()
        val store = FakeStore(plan)
        store.duringPause[2] = { store.editDate(205L, 5L, "--06-21") }
        val r = run(store, plan)
        assertEquals(listOf(1, 50), store.batches.map { it.size })
        assertEquals(0, r.halts)
        assertTrue(r.outcome.stopped)
    }

    @Test
    fun `a date refused because the user changed it shows the user's edit`() {
        val plan = yearlessForFive()
        val store = FakeStore(plan)
        store.beforeWrite[205L] = { store.editDate(205L, 5L, "--06-21") }
        val r = run(store, plan)
        assertEquals(listOf(1, 50, 10), store.batches.map { it.size })
        assertEquals(1, r.outcome.changed)
        assertTrue(r.outcome.stopped && !r.outcome.late)
        assertEquals(0, r.halts)
        assertEquals(0, r.strikes)
    }

    @Test
    fun `a date refused with its old text kept is no sign of the user's edit`() {
        val plan = yearlessForFive()
        val store = FakeStore(plan)
        store.beforeWrite[205L] = { store.touch(5L) }
        val r = run(store, plan)
        assertEquals(1, r.outcome.changed)
        assertTrue(r.outcome.late)
        assertEquals(0, r.halts)
        assertEquals(1, r.strikes)
    }

    @Test
    fun `a contact counts in the step of its latest written date`() {
        val plan = yearlessForFive()
        val store = FakeStore(plan)
        store.duringPause[3] = { store.touch(5L) }
        val r = run(store, plan)
        assertEquals(listOf(1, 50, 10), store.batches.map { it.size })
        assertEquals(1, r.halts)
        assertEquals(0, r.strikes)
        assertTrue(r.outcome.halted)
    }

    @Test
    fun `a date already changed at its write says nothing about a contact marked later`() {
        val plan = listOf(Rewrite(1, 1, "19900215", "1990-02-15"), Rewrite(101, 1, "20000101", "2000-01-01")) + rows(10).drop(1)
        val store = FakeStore(plan, marksOnWrite = setOf(1L))
        store.texts[1L] = "1990-03-01"
        val r = run(store, plan)
        assertEquals(listOf(listOf(1L), listOf(101L)), store.batches)
        assertEquals(1, r.halts)
        assertTrue(r.outcome.halted)
    }

    @Test
    fun `a user's edit of a contact written in an earlier step stops the run once, without a halt`() {
        val store = FakeStore(rows(150))
        store.duringPause[3] = { store.touch(1L) }
        val r = run(store, rows(150))
        assertEquals(listOf(1, 50, 50), store.batches.map { it.size })
        assertEquals("the check, then one more wait", listOf(CANARY_SETTLE_MS, SETTLE_MS, SETTLE_MS, RECHECK_MS), store.waits)
        assertEquals(0, r.halts)
        assertEquals(1, r.strikes)
        assertTrue(r.outcome.stopped && r.outcome.late)
        assertEquals(101, r.outcome.written)
    }

    @Test
    fun `a contact deleted during the second wait is not a further sign of the provider`() {
        val store = FakeStore(rows(150))
        store.duringPause[3] = { store.touch(1L) }
        store.duringPause[4] = {
            store.deleted.add(60L)
            store.dirtyNow.add(60L)
        }
        val r = run(store, rows(150))
        assertEquals(4, store.waits.size)
        assertEquals(0, r.halts)
        assertEquals(1, r.strikes)
        assertTrue(r.outcome.stopped && r.outcome.late)
    }

    @Test
    fun `the second such stop in one app version halts`() {
        val store = FakeStore(rows(150))
        store.duringPause[3] = { store.touch(1L) }
        val r = run(store, rows(150), struck = true)
        assertEquals(1, r.halts)
        assertEquals(0, r.strikes)
        assertTrue(r.outcome.halted)
    }

    @Test
    fun `a provider that marks within the canary's wait costs the canary alone`() {
        for (lag in listOf(0L, 100L, 2_000L, 8_000L, CANARY_SETTLE_MS - 100)) {
            val store = FakeStore(rows(101), lagMs = lag)
            val r = run(store, rows(101))
            assertEquals("lag $lag ms", listOf(listOf(1L)), store.batches)
            assertEquals("lag $lag ms", 1, r.halts)
        }
    }

    @Test
    fun `a provider that marks only chunks, at any steady lag, halts in the first run`() {
        // The canary goes alone, so this provider leaves it clean; each chunk's contacts are marked a steady lag later.
        for (lag in 0L..8_000L step 100) {
            val store = FakeStore(rows(300), lagMs = lag, batchesOnly = true)
            val r = run(store, rows(300))
            assertEquals("lag $lag ms", 1, r.halts)
            assertEquals("lag $lag ms", 0, r.strikes)
        }
    }

    @Test
    fun `a provider that marks later than the canary's wait is halted by its second run at the latest`() {
        val lag = CANARY_SETTLE_MS + 1_000
        val first = FakeStore(rows(300), lagMs = lag)
        val r1 = run(first, rows(300))
        assertEquals(listOf(1, 50), first.batches.map { it.size })
        assertEquals(1, r1.strikes)
        assertTrue(r1.outcome.stopped && r1.outcome.late)
        val rest = rows(300).drop(51)
        val r2 = run(FakeStore(rest, lagMs = lag), rest, struck = true)
        assertEquals(1, r2.halts)
        assertTrue(r2.outcome.halted)
    }

    @Test
    fun `a mark after the pass's last check is not seen, as documented`() {
        val store = FakeStore(rows(101), lagMs = CANARY_SETTLE_MS + 10_000)
        val r = run(store, rows(101))
        assertEquals(Outcome(written = 101, waiting = 0, changed = 0), r.outcome)
        assertEquals(0, r.halts)
        assertEquals(0, r.strikes)
    }

    @Test
    fun `an interrupted wait ends the run without a halt`() {
        val store = FakeStore(rows(120))
        store.interruptAt = 2
        val r = run(store, rows(120))
        assertEquals(listOf(1, 50), store.batches.map { it.size })
        assertEquals(0, r.halts)
        assertEquals(0, r.strikes)
        assertTrue(r.outcome.stopped)
    }

    // ---- Source guards (the provider side) ----

    private fun lines(path: String): List<String> = File("src/main/java/me/proton/sync/$path").readLines()

    /** The code lines only: no KDoc, block or line comments, each trimmed, joined by single spaces. */
    private fun code(lines: List<String>): String =
        lines.map { it.trim() }
            .filterNot { it.startsWith("*") || it.startsWith("/*") || it.startsWith("//") }
            .joinToString(" ") { it.substringBefore(" // ") }

    /** The lines of the member that starts with [signature], up to its closing brace at four spaces. */
    private fun member(all: List<String>, signature: String): List<String> {
        val start = all.indexOfFirst { it.startsWith(signature) }
        assertTrue("$signature not found", start >= 0)
        val end = (start + 1 until all.size).first { all[it] == "    }" }
        return all.subList(start, end + 1)
    }

    /** [text] with each "@{" turned into a Kotlin template opener, so the expected source reads like the source. */
    private fun src(text: String): String = text.replace("@{", "$" + "{")

    private fun count(haystack: String, needle: String): Int = haystack.split(needle).size - 1

    @Test
    fun `the provider side only re-spells the date, as the sync adapter, behind a clean check`() {
        val pass = code(lines("contacts/ContactDateRewrite.kt"))
        for (chain in listOf(
            """val dataUri = SyncUtils.asCallerSyncAdapter(ContactsContract.Data.CONTENT_URI, account)""",
            """ContentProviderOperation.newAssertQuery(ContactsContract.RawContacts.CONTENT_URI) .withSelection( "@{ContactsContract.RawContacts._ID} = ? AND @{ContactsContract.RawContacts.DIRTY} = 0 AND " + "@{ContactsContract.RawContacts.DELETED} = 0", arrayOf(row.rawContactId.toString()), ) .withExpectedCount(1) .build()""",
            """ContentProviderOperation.newUpdate(dataUri) .withSelection( "@{ContactsContract.Data._ID} = ? AND @{ContactsContract.Data.MIMETYPE} = ? AND " + "@{ContactsContract.CommonDataKinds.Event.START_DATE} = ?", arrayOf(row.dataId.toString(), ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE, row.old), ) .withValue(ContactsContract.CommonDataKinds.Event.START_DATE, row.new) .build()""",
        )) {
            assertTrue(src(chain), pass.contains(src(chain)))
        }
        assertEquals("one update", 1, count(pass, "newUpdate("))
        assertEquals("one assert", 1, count(pass, "newAssertQuery("))
        assertEquals("one batch call", 1, count(pass, "applyBatch("))
        assertEquals("one value written", 1, count(pass, "withValue("))
        for (forbidden in listOf("newInsert", "newDelete", "withValue(ContactsContract.RawContacts.", "Photo", "SyncLog")) {
            assertFalse(forbidden, pass.contains(forbidden))
        }
    }

    @Test
    fun `the pass runs just before the upload, on current copies only, and logs no date`() {
        val adapter = lines("contacts/ContactsSyncAdapter.kt")
        val flat = code(adapter)
        assertTrue(
            "called in the upload branch, right before the upload",
            flat.contains(
                "} else { rewriteBasicFormContactDates(account, parentAccountName, provider, allContacts, activeConflictIds) " +
                    "uploadDirtyContacts(account, parentAccountName, provider, syncResult,"
            ),
        )
        assertEquals("one call", 1, count(flat, "rewriteBasicFormContactDates(account, "))

        val body = code(member(adapter, "    private suspend fun rewriteBasicFormContactDates("))
        for (needed in listOf(
            "val version = me.proton.sync.BuildConfig.VERSION_CODE",
            "if (prefs.getInt(haltKey, 0) == version) { SyncLog.i( TAG,",
            """: not tried in this app version, because a contact it " + "re-spelled read as edited; tried again after an app update" ) return }""",
            "ContactDateRewrite.isCurrent(m.lastModified, serverTime)",
            "if (rawId != null && isCurrent && m.protonContactId !in activeConflictIds) rawId else null",
            "ContactDateRewrite.apply( ContactDateRewrite.ProviderStore(provider, account), plan, " +
                "struck = prefs.getInt(lateKey, 0) == version, " +
                "strike = { prefs.edit().putInt(lateKey, version).commit() }, " +
                "halt = { prefs.edit().putInt(haltKey, version).commit() }, )",
            "catch (e: kotlinx.coroutines.CancellationException) { throw e }",
        )) {
            assertTrue(needed, body.contains(needed))
        }
        // Every value a log line can carry: the account and counts, never a row, a plan or a date.
        val allowed = setOf(
            "parentAccountName", "outcome.written", "outcome.waiting", "outcome.changed", "counts", "held", "heldText",
            "e.javaClass.simpleName",
        )
        val templates = Regex("[$][{]([^}]*)[}]").findAll(body).map { it.groupValues[1] } +
            Regex("[$]([A-Za-z_][A-Za-z0-9_]*)").findAll(body).map { it.groupValues[1] }
        val stray = templates.filterNot { it in allowed }.toList()
        assertEquals("templates outside the allowed values", emptyList<String>(), stray)
    }

    @Test
    fun `the upload still sends Proton the dates without dashes`() {
        val flat = code(lines("contacts/ContactsSyncAdapter.kt"))
        assertEquals(2, count(flat, """text.replace("-", "").replace("/", "").take(8)"""))
        assertEquals(2, count(flat, """normalized.length == 8 && normalized.all { it.isDigit() }"""))
    }

    @Test
    fun `the download and the rule readers spell dates the same way`() {
        val mapper = code(lines("contacts/ContactsMapper.kt"))
        assertTrue(mapper.contains("prop.text?.let { return ContactDates.toExtended(it) ?: it }"))
        assertTrue(mapper.contains("date is java.time.Instant"))
        val local = code(lines("contacts/filter/LocalContactView.kt"))
        assertTrue(
            "birthdays and anniversaries dashed, custom dates verbatim",
            local.contains("put(field, if (field == RuleField.CUSTOM_DATE || date == null) date else ContactDates.toExtended(date) ?: date)"),
        )
    }
}

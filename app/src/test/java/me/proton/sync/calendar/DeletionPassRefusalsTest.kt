package me.proton.sync.calendar

import me.proton.sync.R
import me.proton.sync.SyncNotificationHelper
import me.proton.sync.SyncPreferences
import me.proton.sync.SyncUtils
import me.proton.sync.backup.BackupSettings
import me.proton.sync.contacts.FakePrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The deletion pass's refusals (DeletionPassRefusals; CalendarSyncAdapter.reconcileDeletedEvents, which deletes on Proton
 * the mapped events whose phone rows vanished without a tombstone, mainly an approved bulk deletion). Since 2.2.0 an HTTP
 * failure there fails the run with its status, and the mapping stays, so a refusal for good failed every sync. The owner's
 * decision (2026-11-23): a refusal counts a strike per Proton event id, the first two fail their
 * run as before, and the third gives the deletion up the way the pass gives up a rejected one: the mapping goes, the
 * calendar's cursor is reset, the event comes back with the next sync, and the rejected-events notice says so with its
 * existing text. Answers about the connection or the session, and requests that threw, fail every run and cost no strike.
 *
 * The owner's decision of 2026-11-23: the pass's REJECTED branch (Proton's 200 with the operation rejected) gives a
 * deletion up at its third rejection, as it was written to. 2.1.0 kept that count under delete_reject_count_<protonEventId>,
 * which the stale-counter sweep dropped at every sync start, so the third never came. The rejections are counted in
 * DeletionPassRefusals now, apart from the refusals; the tombstones' own counts by row id are untouched. And the REFUSED
 * (read-only, at once) and REJECTED give-ups name the event in the rejected-events notice, as the refusals' give-up does.
 *
 * The owner's decision of 2026-11-23: the REJECTED give-up's line gives Proton's own error, exactly as the upload's
 * delete path names its own third rejection (settleDeleteResponse), so it reads "(no title): <Proton's error>"; the
 * REFUSED give-up and the third refusal keep "this account cannot delete it on Proton, so it comes back to this phone with
 * the next sync". passDeletionGivenUp takes the text from its caller. No string is added or changed.
 * Made-up accounts and ids only; no event's content anywhere.
 */
class DeletionPassRefusalsTest {

    private val account = "deletion-pass-a@example.test"
    private val otherAccount = "deletion-pass-b@example.test"
    private val key = SyncPreferences.eventDeletePassStrikesKey(account)
    private val rejectsKey = SyncPreferences.eventDeletePassRejectsKey(account)

    private companion object {
        const val ADAPTER = "src/main/java/me/proton/sync/calendar/CalendarSyncAdapter.kt"

        /** Answers about the connection or the session (EventDeletionFailures.isRefusal is false for them), and refusals. */
        val PASSING = listOf(401, 408, 429, 500, 502, 503, 504)
        val REFUSING = listOf(400, 403, 404, 409, 410, 422)

        /** Made-up ids in Proton's shapes: base64 with padding, with '+' and '/', and base64url. */
        const val EVENT_A = "bWFkZS11cC1ldmVudC1h=="
        const val EVENT_B = "bWFkZS11cC+ldmVudC/i"
        const val EVENT_C = "bWFkZS11cC1ldmVudC1j-_Q"

        /**
         * Answers that are not HTTP statuses, for the simulation: Proton's 200 with the deletion's operation rejected
         * (DeleteOpVerdict.REJECTED), and the read-only calendar's refusal, 2011 or 2026 as HTTP 400 or as the operation's
         * code (DeleteOpVerdict.REFUSED).
         */
        const val OP_REJECTED = -1
        const val READ_ONLY = -2

        /** The per-row counters' family that 2.1.0's pass also wrote, by Proton id, for its rejections. */
        const val ROW_DELETE_FAMILY = "delete_reject_count_"

        /** Stands for the refused deletion's text in the simulation (deleteRefusalReason, a resource string). */
        const val REFUSAL_TEXT = "deleteRefusalReason()"
    }

    private val adapter: String by lazy { File(ADAPTER).readText().replace("\r\n", "\n") }

    /** [text] without KDoc, block comment lines and line comments. */
    private fun code(text: String): String =
        text.lines()
            .filterNot { it.trim().let { t -> t.startsWith("*") || t.startsWith("/*") || t.startsWith("//") } }
            .joinToString("\n") { it.substringBefore(" // ") }

    private fun squash(text: String): String = text.replace(Regex("\\s+"), " ")

    /** The text of the function that starts with [signature], up to its closing brace at four spaces. */
    private fun body(signature: String): String {
        assertTrue("$signature not found", adapter.contains(signature))
        return adapter.substringAfter(signature).substringBefore("\n    }\n")
    }

    private fun count(text: String, what: String): Int = Regex(Regex.escape(what)).findAll(text).count()

    private fun strikes(prefs: FakePrefs, accountName: String = account): Map<String, Int> =
        DeletionPassRefusals.parseStrikes(prefs.getString(SyncPreferences.eventDeletePassStrikesKey(accountName), null))

    private fun rejections(prefs: FakePrefs, accountName: String = account): Map<String, Int> =
        DeletionPassRefusals.parseStrikes(prefs.getString(SyncPreferences.eventDeletePassRejectsKey(accountName), null))

    /**
     * One account's deletion pass over syncs, step by step as CalendarSyncAdapter runs it (the wiring tests below hold the
     * code to each step). At the start of a sync the counts of deletions whose mapping is gone are pruned
     * (pruneDeletionPassRefusals, first in sweepStaleRetryCounters), then the per-row counters that name nothing the phone
     * has are swept (the rest of sweepStaleRetryCounters, by staleCounterKeys). The download then writes each event given
     * up at the sync before again, as a new row with a new mapping (each give-up dropped its mapping and reset its
     * calendar's cursor). Then the pass sends the deletion of every mapped event whose row is missing, unless the
     * bulk-deletion guard holds them, and settles Proton's answer: 200 takes it, null is a request that threw (its catch),
     * [READ_ONLY] is the read-only calendar's refusal (REFUSED: given up at once), [OP_REJECTED] a 200 whose operation
     * Proton rejected (REJECTED: passDeletionRejected), and any other status goes to reconcileDeletionFailed. Every give-up
     * ends in passDeletionGivenUp: both lists forget the event, a key 2.1.0 left for it goes, and its line waits, with
     * the text its caller gives ([reasons]): Proton's own error ([rejection], DeletionPassRefusals.rejectionReason) for
     * the third rejection, the refused deletion's text ([REFUSAL_TEXT]) for the others. The upload runs after the pass,
     * and only its end takes the lines of the deletions given up out, one each, into its rejected-events notice
     * (takeGaveUpList); an upload that stops before its end posts nothing and leaves them for the next one's. One account:
     * the sweep's questions to the other accounts of this app are StaleEventCountersTest's.
     */
    private class Pass(val prefs: FakePrefs, val accountName: String) {
        /** Mapped events whose row vanished: the deletions the pass sends. */
        val vanished = linkedSetOf<String>()
        /** Mapped events whose row is on the phone. */
        val onPhone = linkedSetOf<String>()
        /** Given up: no mapping and the cursor reset, so the next download writes them again. */
        val comingBack = linkedSetOf<String>()
        /** The deletions given up, in order. */
        val givenUp = mutableListOf<String>()
        /** Given up and not named yet (passGaveUpByAccount): a line each, never merged, until an upload ends. */
        val waiting = mutableListOf<String>()
        /** The rejected-events notices posted at the end of the uploads, each with the lines of the pass it took out. */
        val notices = mutableListOf<List<String>>()
        var sent = 0
        /** The phone's row ids as the sweep of per-row counters finds them; null when it could not read the provider. */
        var rows: Set<String>? = emptySet()
        /** What Proton's operation says when it rejects a deletion ([OP_REJECTED]), as firstSyncOpResult reads it. */
        var rejection = SyncOpResult(2000, "Made-up rejection of the deletion")
        /** Each deletion given up, in order, with the text its line gives. */
        val reasons = mutableListOf<Pair<String, String>>()

        /**
         * One sync: what became of each deletion, in order, and whether the run failed. [uploadEnds] false is an upload that
         * stopped before its end (a failed read of the dirty rows, say), which posts no notice.
         */
        fun sync(held: Boolean = false, uploadEnds: Boolean = true, answer: (String) -> Int?): Pair<List<String>, Boolean> {
            val mapped = vanished + onPhone
            // pruneDeletionPassRefusals: only the counted ids are looked up in the mappings, and those still mapped stay.
            val stillMapped = DeletionPassRefusals.countedIds(prefs, accountName).filterTo(HashSet()) { it in mapped }
            DeletionPassRefusals.retain(prefs, accountName) { it in stillMapped }
            sweepRowCounters(mapped)
            onPhone.addAll(comingBack)
            comingBack.clear()
            val result = if (held) vanished.map { "$it held" } to false else send(answer)
            if (uploadEnds && waiting.isNotEmpty()) {
                notices.add(waiting.toList())
                waiting.clear()
            }
            return result
        }

        /** The rest of sweepStaleRetryCounters: a key of its three families that names nothing the phone has goes. */
        private fun sweepRowCounters(mapped: Set<String>) {
            val keys = prefs.all.keys.toSet()
            val stale = staleCounterKeys(keys, "upload_reject_count_", rows) +
                staleCounterKeys(keys, ROW_DELETE_FAMILY, rows) +
                staleCounterKeys(keys, "invite_retry_count_", mapped)
            val edit = prefs.edit()
            for (k in stale) edit.remove(k)
            edit.apply()
        }

        private fun send(answer: (String) -> Int?): Pair<List<String>, Boolean> {
            var failed = false
            val outcomes = vanished.toList().map { id ->
                sent++
                when (val code = answer(id)) {
                    null -> {
                        failed = true
                        "$id threw"
                    }
                    200 -> {
                        vanished.remove(id)
                        prefs.edit().remove(ROW_DELETE_FAMILY + id).apply()
                        DeletionPassRefusals.forget(prefs, accountName, id)
                        "$id deleted"
                    }
                    READ_ONLY -> giveUp(id, REFUSAL_TEXT)
                    OP_REJECTED -> {
                        val rejections = DeletionPassRefusals.recordRejection(prefs, accountName, id)
                        if (DeletionPassRefusals.rejectionGivesUp(rejections)) {
                            giveUp(id, DeletionPassRefusals.rejectionReason(rejection))
                        } else "$id rejected"
                    }
                    else -> {
                        val refusals = if (EventDeletionFailures.isRefusal(code)) {
                            DeletionPassRefusals.recordRefusal(prefs, accountName, id)
                        } else 0
                        if (DeletionPassRefusals.givesUp(code, refusals)) {
                            giveUp(id, REFUSAL_TEXT)
                        } else {
                            failed = DeletionPassRefusals.failsRun(code, refusals) || failed
                            "$id failed $code"
                        }
                    }
                }
            }
            return outcomes to failed
        }

        /** Any give-up: the mapping goes and the cursor is reset, so it comes back; then passDeletionGivenUp. */
        private fun giveUp(id: String, reason: String): String {
            vanished.remove(id)
            comingBack.add(id)
            DeletionPassRefusals.forget(prefs, accountName, id)
            prefs.edit().remove(ROW_DELETE_FAMILY + id).apply()
            givenUp.add(id)
            waiting.add(id)
            reasons.add(id to reason)
            return "$id given up"
        }
    }

    // ---- The rule -----------------------------------------------------------------------------------------------------

    @Test
    fun `a refusal gives the deletion up at its third, and nothing else does`() {
        assertEquals(3, DeletionPassRefusals.GIVE_UP_AT)
        assertEquals(EventDeletionFailures.MAX_REPORTED_REFUSALS, DeletionPassRefusals.GIVE_UP_AT)
        for (status in REFUSING) {
            assertTrue("$status", EventDeletionFailures.isRefusal(status))
            // The first two fail their run with the status, as before; the third and any later one give it up.
            for (refusals in listOf(0, 1, 2)) {
                assertFalse("$status $refusals", DeletionPassRefusals.givesUp(status, refusals))
                assertTrue("$status $refusals", DeletionPassRefusals.failsRun(status, refusals))
            }
            for (refusals in listOf(3, 4, 100)) {
                assertTrue("$status $refusals", DeletionPassRefusals.givesUp(status, refusals))
                assertFalse("$status $refusals", DeletionPassRefusals.failsRun(status, refusals))
            }
        }
        for (status in PASSING) {
            assertFalse("$status", EventDeletionFailures.isRefusal(status))
            for (refusals in listOf(0, 1, 3, 4, 100)) {
                assertFalse("$status $refusals", DeletionPassRefusals.givesUp(status, refusals))
                assertTrue("$status $refusals", DeletionPassRefusals.failsRun(status, refusals))
            }
        }
    }

    @Test
    fun `a lasting refusal fails two syncs, gives the deletion up at the third, and the event comes back with the next`() {
        for (status in REFUSING) {
            val pass = Pass(FakePrefs(), account)
            pass.vanished.add(EVENT_A)
            // Twenty syncs refused: two failed runs, the third gives it up without failing, and then nothing is sent again.
            val runs = List(20) { pass.sync { status } }
            assertEquals(
                "$status",
                listOf(
                    listOf("$EVENT_A failed $status") to true,
                    listOf("$EVENT_A failed $status") to true,
                    listOf("$EVENT_A given up") to false,
                ) + List(17) { emptyList<String>() to false },
                runs
            )
            assertEquals("$status", 3, pass.sent)
            assertEquals("$status", listOf(EVENT_A), pass.givenUp)
            // Named once, in the notice of the sync that gave it up.
            assertEquals("$status", listOf(listOf(EVENT_A)), pass.notices)
            // Back on the phone from the fourth sync on, and nothing left counted.
            assertEquals("$status", setOf(EVENT_A), pass.onPhone)
            assertTrue("$status", pass.vanished.isEmpty())
            assertFalse("$status", pass.prefs.contains(key))
        }
    }

    @Test
    fun `answers about the connection or the session, and requests that threw, fail every run and cost no strike`() {
        for (status in PASSING) {
            val pass = Pass(FakePrefs(), account)
            pass.vanished.add(EVENT_A)
            assertEquals("$status", List(20) { listOf("$EVENT_A failed $status") to true }, List(20) { pass.sync { status } })
            assertEquals("$status", 20, pass.sent)
            assertTrue("$status", pass.givenUp.isEmpty())
            assertFalse("$status", pass.prefs.contains(key))
        }
        val network = Pass(FakePrefs(), account)
        network.vanished.add(EVENT_A)
        assertEquals(List(20) { listOf("$EVENT_A threw") to true }, List(20) { network.sync { null } })
        assertFalse(network.prefs.contains(key))
        // Between refusals they change nothing: the third refusal gives it up, whatever came between and whatever its status.
        val mixed = Pass(FakePrefs(), account)
        mixed.vanished.add(EVENT_A)
        val answers = listOf<Int?>(503, 422, null, 429, 401, 409, 408, 500, 404)
        val runs = answers.map { a -> mixed.sync { a } }
        assertEquals(List(8) { true } + false, runs.map { it.second })
        assertEquals(listOf("$EVENT_A given up"), runs.last().first)
        assertEquals(listOf(EVENT_A), mixed.givenUp)
    }

    @Test
    fun `each event and each account counts its own, and a deletion Proton takes is forgotten`() {
        val prefs = FakePrefs()
        val pass = Pass(prefs, account)
        pass.vanished.addAll(listOf(EVENT_A, EVENT_B))
        // A is refused twice and then taken; B is refused all along.
        val answers = mapOf(EVENT_A to listOf(422, 403, 200), EVENT_B to listOf(409, 409, 409))
        val runs = (0 until 3).map { i -> pass.sync { id -> answers.getValue(id)[i] } }
        assertEquals(listOf(true, true, false), runs.map { it.second })
        assertEquals(listOf("$EVENT_A deleted", "$EVENT_B given up"), runs.last().first)
        assertEquals(listOf(EVENT_B), pass.givenUp)
        assertEquals(emptyMap<String, Int>(), strikes(prefs))
        // Another account's counts are its own, under its own key.
        assertEquals(1, DeletionPassRefusals.recordRefusal(prefs, otherAccount, EVENT_C))
        assertEquals(1, DeletionPassRefusals.recordRefusal(prefs, account, EVENT_C))
        assertEquals(2, DeletionPassRefusals.recordRefusal(prefs, account, EVENT_C))
        assertEquals(mapOf(EVENT_C to 1), strikes(prefs, otherAccount))
        assertEquals(mapOf(EVENT_C to 2), strikes(prefs))
        // Proton took it at last: forgotten, so a later refusal of the same event counts from one again.
        DeletionPassRefusals.forget(prefs, account, EVENT_C)
        assertEquals(emptyMap<String, Int>(), strikes(prefs))
        assertFalse(prefs.contains(key))
        assertEquals(1, DeletionPassRefusals.recordRefusal(prefs, account, EVENT_C))
    }

    @Test
    fun `a held deletion costs no strike and keeps its count while its mapping stays, and a mapping gone takes its count`() {
        val prefs = FakePrefs()
        val pass = Pass(prefs, account)
        pass.vanished.add(EVENT_A)
        assertEquals(listOf("$EVENT_A failed 422") to true, pass.sync { 422 })
        // Held by the bulk-deletion guard until the user answers: nothing is sent or counted, and the count stays.
        repeat(5) { assertEquals(listOf("$EVENT_A held") to false, pass.sync(held = true) { error("nothing is sent") }) }
        assertEquals(mapOf(EVENT_A to 1), strikes(prefs))
        // Approved: the second refusal fails its run, the third gives the deletion up.
        assertEquals(listOf("$EVENT_A failed 422") to true, pass.sync { 422 })
        assertEquals(listOf("$EVENT_A given up") to false, pass.sync { 422 })
        assertEquals(setOf(EVENT_A), pass.comingBack)
        // A forced download clears every mapping: the next sync's start drops the counts before the download maps the event
        // again under the same Proton id, so a later deletion of it is refused twice, as any, before it is given up.
        pass.vanished.add(EVENT_B)
        assertEquals(listOf("$EVENT_B failed 409") to true, pass.sync { 409 })
        assertEquals(listOf("$EVENT_B failed 409") to true, pass.sync { 409 })
        assertEquals(mapOf(EVENT_B to 2), strikes(prefs))
        pass.comingBack.addAll(pass.vanished + pass.onPhone)
        pass.vanished.clear()
        pass.onPhone.clear()
        assertEquals(emptyList<String>() to false, pass.sync { error("nothing is sent") })
        assertFalse(prefs.contains(key))
        assertEquals(setOf(EVENT_A, EVENT_B), pass.onPhone)
        pass.onPhone.remove(EVENT_B)
        pass.vanished.add(EVENT_B)
        assertEquals(List(3) { it < 2 }, List(3) { pass.sync { 409 }.second })
        // The prune itself: only what is left goes, and nothing is written when nothing goes.
        DeletionPassRefusals.recordRefusal(prefs, account, EVENT_A)
        DeletionPassRefusals.recordRefusal(prefs, account, EVENT_C)
        DeletionPassRefusals.recordRefusal(prefs, account, EVENT_C)
        val before = prefs.getString(key, null)
        DeletionPassRefusals.retain(prefs, account) { it == EVENT_A || it == EVENT_C }
        assertTrue(prefs.getString(key, null) === before)
        DeletionPassRefusals.retain(prefs, account) { it == EVENT_C }
        assertEquals(mapOf(EVENT_C to 2), strikes(prefs))
        assertEquals(setOf(EVENT_C), DeletionPassRefusals.countedIds(prefs, account))
        DeletionPassRefusals.retain(prefs, account) { false }
        assertFalse(prefs.contains(key))
        // Nothing recorded: nothing to prune or forget, and no key appears.
        DeletionPassRefusals.forget(prefs, account, EVENT_A)
        DeletionPassRefusals.retain(prefs, account) { false }
        assertFalse(prefs.contains(key))
        assertEquals(emptySet<String>(), DeletionPassRefusals.countedIds(prefs, account))
    }

    @Test
    fun `each deletion given up is a line of its own, and one whose upload stopped before its end is named by the next`() {
        // Three events of a wiped calendar, refused at every sync: all three given up at the third, three lines, although
        // untitled they read the same, as three events come back.
        val pass = Pass(FakePrefs(), account)
        pass.vanished.addAll(listOf(EVENT_A, EVENT_B, EVENT_C))
        assertEquals(listOf(true, true, false), List(3) { pass.sync { 422 }.second })
        assertEquals(listOf(listOf(EVENT_A, EVENT_B, EVENT_C)), pass.notices)
        repeat(5) { assertEquals(emptyList<String>() to false, pass.sync { error("nothing is sent") }) }
        assertEquals(1, pass.notices.size)
        assertEquals(setOf(EVENT_A, EVENT_B, EVENT_C), pass.onPhone)
        // The upload of the sync that gives a deletion up stops before its end: no notice, and the line waits, through
        // another such sync, for the next upload that ends. Each later give-up is named by the notice of its own sync.
        val stopped = Pass(FakePrefs(), account)
        stopped.vanished.add(EVENT_A)
        repeat(2) { stopped.sync { 409 } }
        assertEquals(listOf("$EVENT_A given up") to false, stopped.sync(uploadEnds = false) { 409 })
        assertEquals(emptyList<String>() to false, stopped.sync(uploadEnds = false) { error("nothing is sent") })
        assertTrue(stopped.notices.isEmpty())
        stopped.vanished.add(EVENT_B)
        assertEquals(listOf("$EVENT_B failed 409") to true, stopped.sync { 409 })
        assertEquals(listOf(listOf(EVENT_A)), stopped.notices)
        assertEquals(listOf(true, false), List(2) { stopped.sync { 409 }.second })
        assertEquals(listOf(listOf(EVENT_A), listOf(EVENT_B)), stopped.notices)
        assertTrue(stopped.waiting.isEmpty())
    }

    // ---- The rejections, and the read-only refusal (owner, 2026-11-23) ------------------------------------------------

    @Test
    fun `in 2_1_0 a rejection was counted under delete_reject_count_, which the sweep took at every sync, so the third never came`() {
        // 2.1.0's REJECTED branch: bumpDeleteRejectionStrike under delete_reject_count_<protonEventId>; and at the start of
        // every sync the sweep of per-row counters drops each key of that family that names no row of the phone, which a
        // Proton id never does. Twenty syncs rejected: the count is one at every sync, and the deletion went at all twenty.
        val prefs = FakePrefs()
        val legacy = ROW_DELETE_FAMILY + EVENT_A
        val counts = List(20) {
            val stale = staleCounterKeys(prefs.all.keys.toSet(), ROW_DELETE_FAMILY, setOf("41", "42"))
            stale.forEach { prefs.edit().remove(it).apply() }
            val strike = prefs.getInt(legacy, 0) + 1
            if (strike >= 3) prefs.edit().remove(legacy).apply() else prefs.edit().putInt(legacy, strike).apply()
            strike
        }
        assertEquals(List(20) { 1 }, counts)
        // Proton's ids are never a row's number, so the sweep takes every such key, whatever rows the phone has; only a
        // provider it cannot read keeps them, as it keeps every key of the family.
        for (id in listOf(EVENT_A, EVENT_B, EVENT_C)) {
            assertTrue(id, id.toLongOrNull() == null)
            val keys = setOf(ROW_DELETE_FAMILY + id, ROW_DELETE_FAMILY + "41")
            assertEquals(id, setOf(ROW_DELETE_FAMILY + id), staleCounterKeys(keys, ROW_DELETE_FAMILY, setOf("41")))
            assertEquals(id, emptySet<String>(), staleCounterKeys(keys, ROW_DELETE_FAMILY, null))
        }
    }

    @Test
    fun `a rejection gives the deletion up at its third, counted apart and never swept, and fails no run`() {
        assertEquals(3, DeletionPassRefusals.REJECTION_GIVE_UP_AT)
        for (n in listOf(0, 1, 2)) assertFalse("$n", DeletionPassRefusals.rejectionGivesUp(n))
        for (n in listOf(3, 4, 100)) assertTrue("$n", DeletionPassRefusals.rejectionGivesUp(n))
        // Twenty syncs rejected, with the sweep of per-row counters at the start of each: tried three times, given up at the
        // third, never a failed run, named once, back on the phone from the fourth sync on, and then nothing is sent again.
        val pass = Pass(FakePrefs(), account)
        pass.vanished.add(EVENT_A)
        pass.rows = setOf("41", "42")
        val runs = List(20) { pass.sync { OP_REJECTED } }
        assertEquals(
            listOf(
                listOf("$EVENT_A rejected") to false,
                listOf("$EVENT_A rejected") to false,
                listOf("$EVENT_A given up") to false,
            ) + List(17) { emptyList<String>() to false },
            runs
        )
        assertEquals(3, pass.sent)
        assertEquals(listOf(EVENT_A), pass.givenUp)
        assertEquals(listOf(listOf(EVENT_A)), pass.notices)
        assertEquals(setOf(EVENT_A), pass.onPhone)
        assertTrue(pass.vanished.isEmpty())
        assertTrue(pass.prefs.values.isEmpty())
        // Between syncs the count is kept under its own key, and only there: the sweep takes nothing of it.
        val counted = Pass(FakePrefs(), account)
        counted.vanished.add(EVENT_A)
        counted.rows = setOf("41", "42")
        repeat(2) { counted.sync { OP_REJECTED } }
        assertEquals(mapOf(EVENT_A to 2), rejections(counted.prefs))
        assertEquals(setOf(rejectsKey), counted.prefs.values.keys)
        // Rejections at every sync while the provider cannot be read for the sweep change nothing either.
        val unread = Pass(FakePrefs(), account)
        unread.vanished.add(EVENT_A)
        unread.rows = null
        assertEquals(listOf(false, false, false), List(3) { unread.sync { OP_REJECTED }.second })
        assertEquals(listOf(EVENT_A), unread.givenUp)
    }

    @Test
    fun `the read-only refusal gives the deletion up at once, names it, and forgets both counts`() {
        val pass = Pass(FakePrefs(), account)
        pass.vanished.addAll(listOf(EVENT_A, EVENT_B))
        // A was refused and rejected before (a calendar shared to it that became view-only, say); B never was.
        DeletionPassRefusals.recordRefusal(pass.prefs, account, EVENT_A)
        DeletionPassRefusals.recordRejection(pass.prefs, account, EVENT_A)
        assertEquals(listOf("$EVENT_A given up", "$EVENT_B given up") to false, pass.sync { READ_ONLY })
        assertEquals(2, pass.sent)
        // A line each, in the notice of the sync that gave them up; nothing left counted.
        assertEquals(listOf(listOf(EVENT_A, EVENT_B)), pass.notices)
        assertTrue(pass.prefs.values.isEmpty())
        repeat(5) { assertEquals(emptyList<String>() to false, pass.sync { error("nothing is sent") }) }
        assertEquals(setOf(EVENT_A, EVENT_B), pass.onPhone)
        assertEquals(1, pass.notices.size)
        // Its upload stopped before its end: the line waits for the next upload that ends, as the refusals' do.
        val stopped = Pass(FakePrefs(), account)
        stopped.vanished.add(EVENT_C)
        assertEquals(listOf("$EVENT_C given up") to false, stopped.sync(uploadEnds = false) { READ_ONLY })
        assertTrue(stopped.notices.isEmpty())
        stopped.sync { error("nothing is sent") }
        assertEquals(listOf(listOf(EVENT_C)), stopped.notices)
    }

    @Test
    fun `refusals and rejections are counted apart, and every give-up and every deletion taken forgets both`() {
        // Alternating: the third refusal gives the deletion up, the two rejections between counting nothing towards it, and
        // the refusals keep their failed runs; the other way round the third rejection gives it up, and the two refusals
        // between still fail theirs.
        val a = Pass(FakePrefs(), account)
        a.vanished.add(EVENT_A)
        val runsA = listOf(422, OP_REJECTED, 422, OP_REJECTED, 422).map { x -> a.sync { x } }
        assertEquals(listOf(true, false, true, false, false), runsA.map { it.second })
        assertEquals(
            listOf("failed 422", "rejected", "failed 422", "rejected", "given up"),
            runsA.map { it.first.single().removePrefix("$EVENT_A ") }
        )
        assertTrue(a.prefs.values.isEmpty())
        val b = Pass(FakePrefs(), account)
        b.vanished.add(EVENT_B)
        val runsB = listOf(OP_REJECTED, 409, OP_REJECTED, 409, OP_REJECTED).map { x -> b.sync { x } }
        assertEquals(listOf(false, true, false, true, false), runsB.map { it.second })
        assertEquals("$EVENT_B given up", runsB.last().first.single())
        assertTrue(b.prefs.values.isEmpty())
        assertEquals(listOf(listOf(EVENT_B)), b.notices)
        // Taken at last after rejections and refusals: both forgotten.
        val c = Pass(FakePrefs(), account)
        c.vanished.add(EVENT_C)
        assertEquals(listOf(false, true, false), listOf(OP_REJECTED, 403, 200).map { x -> c.sync { x }.second })
        assertTrue(c.prefs.values.isEmpty())
        assertTrue(c.givenUp.isEmpty() && c.notices.isEmpty())
        // Recording one never moves the other, and each account's are its own.
        val prefs = FakePrefs()
        assertEquals(1, DeletionPassRefusals.recordRejection(prefs, account, EVENT_C))
        assertEquals(1, DeletionPassRefusals.recordRefusal(prefs, account, EVENT_C))
        assertEquals(2, DeletionPassRefusals.recordRejection(prefs, account, EVENT_C))
        assertEquals(1, DeletionPassRefusals.recordRejection(prefs, otherAccount, EVENT_C))
        assertEquals(mapOf(EVENT_C to 1), strikes(prefs))
        assertEquals(mapOf(EVENT_C to 2), rejections(prefs))
        assertEquals(mapOf(EVENT_C to 1), rejections(prefs, otherAccount))
        assertEquals(emptyMap<String, Int>(), strikes(prefs, otherAccount))
        DeletionPassRefusals.forget(prefs, account, EVENT_C)
        assertEquals(setOf(SyncPreferences.eventDeletePassRejectsKey(otherAccount)), prefs.values.keys)
    }

    @Test
    fun `rejections are pruned at the start of a sync once the mapping is gone, before a forced download maps it again`() {
        val prefs = FakePrefs()
        val pass = Pass(prefs, account)
        pass.vanished.addAll(listOf(EVENT_A, EVENT_B))
        repeat(2) { pass.sync { OP_REJECTED } }
        assertEquals(mapOf(EVENT_A to 2, EVENT_B to 2), rejections(prefs))
        // B is deleted on Proton meanwhile and the download drops its mapping; A is taken at the next sync.
        pass.vanished.remove(EVENT_B)
        assertEquals(listOf("$EVENT_A deleted") to false, pass.sync { 200 })
        assertTrue(prefs.values.isEmpty())
        // The prune asks about the ids of both lists, and keeps in each only what it is told to.
        DeletionPassRefusals.recordRefusal(prefs, account, EVENT_A)
        DeletionPassRefusals.recordRejection(prefs, account, EVENT_B)
        DeletionPassRefusals.recordRejection(prefs, account, EVENT_C)
        assertEquals(setOf(EVENT_A, EVENT_B, EVENT_C), DeletionPassRefusals.countedIds(prefs, account))
        val before = prefs.getString(rejectsKey, null)
        DeletionPassRefusals.retain(prefs, account) { it != EVENT_A }
        assertFalse(prefs.contains(key))
        assertTrue(prefs.getString(rejectsKey, null) === before)
        DeletionPassRefusals.retain(prefs, account) { it == EVENT_C }
        assertEquals(mapOf(EVENT_C to 1), rejections(prefs))
        DeletionPassRefusals.retain(prefs, account) { false }
        assertTrue(prefs.values.isEmpty())
        // A forced download clears every mapping: the next sync's start drops the rejections before the download maps the
        // event again under the same Proton id, so a later deletion of it is tried three times, as any.
        val forced = Pass(FakePrefs(), account)
        forced.vanished.add(EVENT_C)
        repeat(2) { forced.sync { OP_REJECTED } }
        forced.comingBack.addAll(forced.vanished)
        forced.vanished.clear()
        assertEquals(emptyList<String>() to false, forced.sync { error("nothing is sent") })
        assertTrue(forced.prefs.values.isEmpty())
        forced.onPhone.remove(EVENT_C)
        forced.vanished.add(EVENT_C)
        assertEquals(listOf("rejected", "rejected", "given up"), List(3) { forced.sync { OP_REJECTED }.first.single().removePrefix("$EVENT_C ") })
    }

    @Test
    fun `a key 2_1_0 left is harmless, and the tombstones' own counts by row id work as before`() {
        // A rejection count 2.1.0 left, at two (its sweep could not read the provider once): the new count never reads it,
        // so the deletion is still tried three times, and the key goes at the first sweep that reads the provider.
        val prefs = FakePrefs()
        val legacy = ROW_DELETE_FAMILY + EVENT_A
        prefs.edit().putInt(legacy, 2).apply()
        val pass = Pass(prefs, account)
        pass.vanished.add(EVENT_A)
        pass.rows = null
        assertEquals(listOf("$EVENT_A rejected") to false, pass.sync { OP_REJECTED })
        assertEquals(2, prefs.getInt(legacy, 0))
        assertEquals(mapOf(EVENT_A to 1), rejections(prefs))
        pass.rows = setOf("41")
        assertEquals(listOf("$EVENT_A rejected") to false, pass.sync { OP_REJECTED })
        assertFalse(prefs.contains(legacy))
        assertEquals(listOf("$EVENT_A given up") to false, pass.sync { OP_REJECTED })
        // Never swept at all, it still goes: with the give-up, or when Proton takes the deletion.
        for (last in listOf(OP_REJECTED, 200)) {
            val unswept = Pass(FakePrefs(), account)
            unswept.prefs.edit().putInt(legacy, 2).apply()
            unswept.vanished.add(EVENT_A)
            unswept.rows = null
            val outcomes = listOf(OP_REJECTED, OP_REJECTED, last).map { x -> unswept.sync { x }.first.single() }
            assertEquals("$last", if (last == 200) "$EVENT_A deleted" else "$EVENT_A given up", outcomes.last())
            assertTrue("$last", unswept.prefs.values.isEmpty())
        }
        // An event deleted on this phone (a tombstone, row 41) is rejected at every sync too, by the upload's delete path
        // (bumpDeleteRejectionStrike, delete_reject_count_41), next to the pass's rejections of B: the sweep keeps the row's
        // count while its row is there, the count reaches its third as before, and neither path's counts touch the other's.
        val both = Pass(FakePrefs(), account)
        both.vanished.add(EVENT_B)
        both.rows = setOf("41")
        val tombstone = ROW_DELETE_FAMILY + "41"
        val steps = List(3) {
            val outcome = both.sync { OP_REJECTED }.first.single()
            val strike = both.prefs.getInt(tombstone, 0) + 1
            if (strike >= 3) both.prefs.edit().remove(tombstone).apply() else both.prefs.edit().putInt(tombstone, strike).apply()
            outcome.removePrefix("$EVENT_B ") to strike
        }
        assertEquals(listOf("rejected" to 1, "rejected" to 2, "given up" to 3), steps)
        // One left at two whose row is gone (restored or removed) is swept as before.
        both.prefs.edit().putInt(tombstone, 2).apply()
        both.rows = emptySet()
        both.sync { error("nothing is sent") }
        assertFalse(both.prefs.contains(tombstone))
    }

    @Test
    fun `the rejections are a list of their own, of their account, never travelling and never swept as row counters`() {
        assertEquals("event_delete_pass_rejects_$account", rejectsKey)
        assertTrue(SyncPreferences.ACCOUNT_SCOPED_PREFIXES.contains("event_delete_pass_rejects_"))
        assertEquals(listOf(account), SyncPreferences.accountNamesOfKey(rejectsKey))
        assertEquals(mapOf(rejectsKey to account), SyncPreferences.keysOfRemovedAccounts(listOf(rejectsKey), setOf(otherAccount)))
        assertEquals(emptyMap<String, String>(), SyncPreferences.keysOfRemovedAccounts(listOf(rejectsKey), setOf(account)))
        assertEquals(
            SyncPreferences.eventDeletePassRejectsKey("renamed"),
            SyncPreferences.renamedAccountKey(rejectsKey, account, "renamed")
        )
        assertFalse(BackupSettings.isExportable(rejectsKey))
        // No other listed prefix begins it or is begun by it, so no key has two readings.
        for (prefix in SyncPreferences.ACCOUNT_SCOPED_PREFIXES.filter { it != "event_delete_pass_rejects_" }) {
            assertTrue(prefix, !prefix.startsWith("event_delete_pass_rejects_") && !"event_delete_pass_rejects_".startsWith(prefix))
        }
        for (family in listOf("upload_reject_count_", ROW_DELETE_FAMILY, "invite_retry_count_")) {
            assertTrue(family, staleCounterKeys(setOf(rejectsKey, key), family, emptySet()).isEmpty())
        }
        assertTrue(rejectsKey != key && rejectsKey != SyncPreferences.eventDeleteStrikesKey(account))
        // Each count writes its own key, and only that.
        val prefs = FakePrefs()
        DeletionPassRefusals.recordRejection(prefs, account, EVENT_A)
        assertEquals(setOf(rejectsKey), prefs.values.keys)
        DeletionPassRefusals.recordRefusal(prefs, account, EVENT_A)
        assertEquals(setOf(rejectsKey, key), prefs.values.keys)
        EventDeletionFailures.recordRefusal(prefs, account, 41)
        EventDeletionFailures.retain(prefs, account) { false }
        assertEquals(mapOf(EVENT_A to 1), rejections(prefs))
        // An id the list cannot hold: each rejection counts as the first, so it is never given up, as in 2.1.0.
        for (bad in listOf("", "made,up", "made up")) {
            val odd = FakePrefs()
            assertEquals(bad, List(5) { 1 }, List(5) { DeletionPassRefusals.recordRejection(odd, account, bad) })
            assertTrue(bad, odd.values.isEmpty())
            assertFalse(bad, DeletionPassRefusals.rejectionGivesUp(1))
        }
        // Only this object and the key's builder name the list.
        val users = listOf("src/main", "src/play", "src/direct", "src/debug").map { File(it) }.filter { it.isDirectory }
            .flatMap { root -> root.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }.toList() }
            .filter { f -> f.readText().let { it.contains("eventDeletePassRejectsKey") || it.contains("event_delete_pass_rejects_") } }
            .map { it.name }.toSet()
        assertEquals(setOf("SyncPreferences.kt", "DeletionPassRefusals.kt"), users)
    }

    @Test
    fun `refusals and rejections counted from several threads are all kept, each in its own list`() {
        val prefs = FakePrefs()
        val ids = listOf(EVENT_A, EVENT_B, EVENT_C, "bWFkZS11cC1k")
        val threads = ids.flatMap { id ->
            listOf(
                Thread { repeat(40) { DeletionPassRefusals.recordRefusal(prefs, account, id) } },
                Thread { repeat(30) { DeletionPassRefusals.recordRejection(prefs, account, id) } },
            )
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals(ids.associateWith { 40 }, strikes(prefs))
        assertEquals(ids.associateWith { 30 }, rejections(prefs))
    }

    // ---- The text shown -----------------------------------------------------------------------------------------------

    @Test
    fun `the first two refusals show their status's text as before`() {
        // reconcileDeletionFailed records EventDeletionFailures.reason for every run it fails; these texts have no Tap Sync
        // variant, so they read the same whether Android retries or not.
        val texts = mapOf(
            400 to R.string.error_request_rejected,
            404 to R.string.error_request_rejected,
            409 to R.string.error_request_rejected,
            410 to R.string.error_request_rejected,
            422 to R.string.error_request_rejected,
            403 to R.string.error_session_expired,
        )
        for ((status, text) in texts) {
            for (retries in listOf(true, false)) {
                val accountName = "deletion-pass-$status-$retries@example.test"
                SyncUtils.recordSwallowedFailure("calendar", accountName, EventDeletionFailures.reason(status))
                val recorded = SyncUtils.consumeSwallowedFailure("calendar", accountName)!!
                assertEquals("HTTP $status retries=$retries", text, SyncNotificationHelper.friendlyMessageRes(recorded, retries))
            }
        }
    }

    // ---- Stored per account, by Proton event id, never in a backup ---------------------------------------------------

    @Test
    fun `the counts are kept by Proton event id, read from the right, and an id that cannot be kept is never given up`() {
        val stored = mapOf(EVENT_A to 1, EVENT_B to 2, EVENT_C to 3, "made:up" to 2)
        assertEquals(stored, DeletionPassRefusals.parseStrikes(DeletionPassRefusals.formatStrikes(stored)))
        assertEquals("$EVENT_B:2,$EVENT_A:1,$EVENT_C:3,made:up:2", DeletionPassRefusals.formatStrikes(stored))
        // Anything unreadable is dropped.
        assertEquals(mapOf(EVENT_A to 2), DeletionPassRefusals.parseStrikes("$EVENT_A:2,,:3,x:,y:0,z:-1,$EVENT_B:two, "))
        assertEquals(emptyMap<String, Int>(), DeletionPassRefusals.parseStrikes(null))
        for (id in listOf(EVENT_A, EVENT_B, EVENT_C, "made:up")) assertTrue(id, DeletionPassRefusals.countable(id))
        // An id the list cannot hold is not stored: its refusals each count as the first, so every run fails, as before.
        for (bad in listOf("", "made,up", "made up", " madeup", "madeup\n")) {
            assertFalse(bad, DeletionPassRefusals.countable(bad))
            val prefs = FakePrefs()
            assertEquals(bad, List(5) { 1 }, List(5) { DeletionPassRefusals.recordRefusal(prefs, account, bad) })
            assertFalse(bad, prefs.contains(key))
            assertTrue(bad, DeletionPassRefusals.failsRun(422, 1))
        }
    }

    @Test
    fun `refusals counted from several threads are all kept`() {
        val prefs = FakePrefs()
        val ids = listOf(EVENT_A, EVENT_B, EVENT_C, "bWFkZS11cC1k", "bWFkZS11cC1l", "bWFkZS11cC1m")
        val threads = ids.map { id -> Thread { repeat(40) { DeletionPassRefusals.recordRefusal(prefs, account, id) } } }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals(ids.associateWith { 40 }, strikes(prefs))
    }

    @Test
    fun `the counts belong to their account, go with a removal, move with a rename, and never travel or get swept as row counters`() {
        assertEquals("event_delete_pass_strikes_$account", key)
        assertTrue(SyncPreferences.ACCOUNT_SCOPED_PREFIXES.contains("event_delete_pass_strikes_"))
        assertEquals(listOf(account), SyncPreferences.accountNamesOfKey(key))
        assertEquals(mapOf(key to account), SyncPreferences.keysOfRemovedAccounts(listOf(key), setOf(otherAccount)))
        assertEquals(emptyMap<String, String>(), SyncPreferences.keysOfRemovedAccounts(listOf(key), setOf(account)))
        assertEquals(SyncPreferences.eventDeletePassStrikesKey("renamed"), SyncPreferences.renamedAccountKey(key, account, "renamed"))
        // About this phone's rows: never in a settings backup.
        assertFalse(BackupSettings.isExportable(key))
        // No other listed prefix begins it or is begun by it, so no key has two readings.
        for (prefix in SyncPreferences.ACCOUNT_SCOPED_PREFIXES.filter { it != "event_delete_pass_strikes_" }) {
            assertTrue(prefix, !prefix.startsWith("event_delete_pass_strikes_") && !"event_delete_pass_strikes_".startsWith(prefix))
        }
        // The sweep of per-row counters, which drops every delete_reject_count_ key it cannot match to a row, never takes it.
        for (family in listOf("upload_reject_count_", "delete_reject_count_", "invite_retry_count_")) {
            assertTrue(family, staleCounterKeys(setOf(key), family, emptySet()).isEmpty())
        }
        // Not the tombstones' counts, which hold Android ids: neither path's writes drop the other's.
        assertTrue(key != SyncPreferences.eventDeleteStrikesKey(account))
        val prefs = FakePrefs()
        DeletionPassRefusals.recordRefusal(prefs, account, EVENT_A)
        EventDeletionFailures.recordRefusal(prefs, account, 41)
        EventDeletionFailures.retain(prefs, account) { false }
        assertEquals(setOf(EVENT_A), DeletionPassRefusals.countedIds(prefs, account))
        DeletionPassRefusals.retain(prefs, account) { false }
        EventDeletionFailures.recordRefusal(prefs, account, 41)
        assertEquals(mapOf(41L to 1), me.proton.sync.contacts.ContactUploadRefusals.parseStrikes(
            prefs.getString(SyncPreferences.eventDeleteStrikesKey(account), null)
        ))
    }

    // ---- Where it is wired --------------------------------------------------------------------------------------------

    @Test
    fun `the pass counts a refusal's strike, fails the run on the first two and gives the deletion up at the third`() {
        val pass = squash(code(body("private suspend fun reconcileDeletedEvents(")))
        assertTrue(pass, pass.contains(
            "null -> { SyncLog.e(TAG, \"Failed to delete event on Proton: HTTP \${response.code()}\${ProtonApiError.describe(errorBody)}\") " +
                "if (reconcileDeletionFailed(account, syncResult, response.code(), mapping.protonEventId)) { giveUpPassDeletion(account, mapping) } }"
        ))
        assertEquals(pass, 1, count(pass, "giveUpPassDeletion("))
        // The read-only calendar's 400 is still REFUSED, never counted here.
        assertTrue(pass, pass.contains(
            "response.code() == 400 && (hasErrorCode(errorBody!!, 2011) || hasErrorCode(errorBody, 2026)) -> DeleteOpVerdict.REFUSED"
        ))
        val failed = squash(code(body("private fun reconcileDeletionFailed(")))
        // A strike for a refusal alone, by the event's Proton id.
        assertTrue(failed, failed.contains(
            "val refusals = if (EventDeletionFailures.isRefusal(httpCode)) { DeletionPassRefusals.recordRefusal(" +
                "PreferenceManager.getDefaultSharedPreferences(context), account.name, protonEventId) } else 0"
        ))
        // The third gives it up before anything is counted; every other answer is counted with its status, as before.
        val givesUp = failed.indexOf("if (DeletionPassRefusals.givesUp(httpCode, refusals)) {")
        val returned = failed.indexOf("return true }")
        val counted = failed.indexOf(
            "synchronized(syncResult.stats) { syncResult.stats.numIoExceptions++ } " +
                "SyncUtils.recordSwallowedFailure(\"calendar\", account.name, EventDeletionFailures.reason(httpCode)) return false"
        )
        assertTrue(failed, givesUp >= 0 && returned > givesUp && counted > returned)
        assertEquals(failed, 1, count(failed, "numIoExceptions++"))
        assertEquals(failed, 1, count(failed, "recordRefusal("))
        assertFalse(adapter.contains("suspend fun reconcileDeletionFailed("))
        // A request that threw is still counted at every run, with its cause, and costs no strike.
        val caught = pass.substringAfter("} catch (e: Exception) { SyncLog.e(TAG, \"Network error deleting event")
            .substringBefore("if (heldDeletions > 0)")
        assertTrue(caught, caught.contains(
            "syncResult.stats.numIoExceptions++ SyncUtils.recordSwallowedFailure(\"calendar\", account.name, CalendarWriteFailures.DELETION_NOT_SENT, e)"
        ))
        for (strike in listOf("DeletionPassRefusals", "reconcileDeletionFailed(", "giveUpPassDeletion(")) {
            assertFalse(strike, caught.contains(strike))
        }
    }

    @Test
    fun `giving up drops the mapping and resets the cursor as the REJECTED branch does, deletes nothing and fails no run`() {
        val giveUp = squash(code(body("private suspend fun giveUpPassDeletion(")))
        // The cursor first: should dropping the mapping fail, the next sync sends the deletion again and gives it up again.
        // Named with the refused deletion's text (owner, 2026-11-23: the third refusal keeps it).
        assertTrue(giveUp, giveUp.contains(
            "resetCalendarCursor(account, mapping.protonCalendarId) " +
                "calendarEventSyncDao.deleteByProtonId(account.name, mapping.protonEventId) " +
                "passDeletionGivenUp(account.name, mapping.protonEventId, deleteRefusalReason())"
        ))
        // The pass's REJECTED branch gives up with the same two steps, and neither it nor this counts a failure.
        val pass = squash(code(body("private suspend fun reconcileDeletedEvents(")))
        val rejected = pass.substringAfter("DeleteOpVerdict.REJECTED -> {").substringBefore("null -> {")
        assertTrue(rejected, rejected.contains(
            "calendarEventSyncDao.deleteByProtonId(account.name, mapping.protonEventId) resetCalendarCursor(account, mapping.protonCalendarId)"
        ))
        val after = squash(code(body("private fun passDeletionGivenUp(")))
        for (text in listOf(rejected, giveUp, after)) {
            assertFalse(text, text.contains("numIoExceptions") || text.contains("recordSwallowedFailure"))
        }
        // Nothing is deleted: no row, no tombstone, no other mapping, no request to Proton; and nothing of the bulk guard.
        for (text in listOf(giveUp, after)) {
            for (never in listOf(
                "provider.delete(", "removeTombstone(", "deleteByCalendar(", "deleteByProtonIds(", "deleteAllByAccount(",
                "apiClient.", "BulkDeletionGuard", "heldDeletions",
            )) {
                assertFalse("$never: $text", text.contains(never))
            }
        }
        // The same reset as the REFUSED and REJECTED branches and restoreRefusedDelete: the stored cursor goes.
        val reset = squash(code(body("private suspend fun resetCalendarCursor(")))
        assertTrue(reset, reset.contains(
            "val calMapping = calendarSyncDao.getByProtonId(account.name, protonCalendarId) ?: return " +
                "if (calMapping.lastModelEventId != null) calendarSyncDao.upsert(calMapping.copy(lastModelEventId = null))"
        ))
        // The bookkeeping is a plain function, kept off the suspend ones for the ART limits.
        assertFalse(adapter.contains("suspend fun passDeletionGivenUp("))
        assertFalse(adapter.contains("suspend fun forgetDeletionPassRefusals("))
        assertFalse(adapter.contains("suspend fun takeGaveUpList("))
    }

    @Test
    fun `the event given up is named in a rejected-events notice, a line each, with existing texts only`() {
        val after = squash(code(body("private fun passDeletionGivenUp(")))
        // The line's text comes from the caller (owner, 2026-11-23), and is the only text in it besides "(no title)".
        assertTrue(after, after.startsWith(
            "accountName: String, protonEventId: String, reason: String) { " +
                "forgetDeletionPassRefusals(accountName, protonEventId) clearDeleteRejectionStrikes(deleteStrikeKey(null, protonEventId)) " +
                "val entry = rejectedEventTitle(\"\") to reason " +
                "val list = passGaveUpByAccount.computeIfAbsent(accountName) { java.util.Collections.synchronizedList(mutableListOf()) } " +
                "list.add(entry)"
        ))
        assertFalse(after, after.contains("deleteRefusalReason(") || after.contains("rejectionReason(") || after.contains("getString("))
        // A line for each deletion given up, never merged: untitled, the lines of different events read the same. The one
        // merge of lines, of one series' rows put back (restoreRefusedDelete), is on the upload's own list, which never
        // holds a line of the pass.
        assertFalse(after, after.contains(" in list") || after.contains("distinct") || after.contains("gaveUpByAccount["))
        val restore = squash(code(body("private suspend fun restoreRefusedDelete(")))
        assertTrue(restore, restore.contains(
            "gaveUpByAccount[account.name]?.let { list -> synchronized(list) { if (entry !in list) list.add(entry) } }"
        ))
        assertEquals(1, count(squash(code(adapter)), "if (entry !in list)"))
        // The row is gone with its title, and its id may name another event by now: nothing is read from the provider.
        assertFalse(after, after.contains("localEventTitle(") || after.contains("provider"))
        assertFalse(after, after.contains("SyncLog"))
        // The texts: "(no title)", then "this account cannot delete it on Proton, so it comes back to this phone with the next
        // sync" for a refusal, or Proton's own error for the third rejection (the owner's decision of 18:30, tested below).
        // No string is added for either.
        val all = squash(code(adapter))
        assertTrue(all.contains(
            "private fun deleteRefusalReason(): String = context.getString(me.proton.sync.R.string.notification_events_rejected_delete)"
        ))
        assertTrue(all.contains(
            "private fun rejectedEventTitle(eventTitle: String): String = " +
                "eventTitle.ifBlank { context.getString(me.proton.sync.R.string.notification_conflict_untitled_event) }"
        ))
        val english = File("src/main/res/values/strings.xml").readText()
        assertTrue(english.contains(
            "<string name=\"notification_events_rejected_delete\">this account cannot delete it on Proton, so it comes back to this phone with the next sync.</string>"
        ))
        assertTrue(english.contains("<string name=\"notification_conflict_untitled_event\">(no title)</string>"))
        val rule = File("src/main/java/me/proton/sync/calendar/DeletionPassRefusals.kt").readText()
        assertFalse(rule.contains("R.string") || rule.contains("Context"))
        // The pass runs before the upload, which opens its own list empty and, at its end, takes the pass's lines out with its
        // own into one notice, through the one place that posts it. Taken out there and nowhere else: an upload that stops
        // before its end leaves them for the next one's notice.
        val upload = squash(code(body("private suspend fun uploadDirtyEvents(")))
        assertTrue(upload, upload.contains(
            "calendars: List<CalendarInfo> ) { gaveUpByAccount[account.name] = java.util.Collections.synchronizedList(mutableListOf()) if ("
        ))
        assertTrue(upload, upload.trimEnd().endsWith(
            "val gaveUp = takeGaveUpList(account.name) if (gaveUp.isNotEmpty()) { " +
                "SyncNotificationHelper.notifyEventsRejected(context, account.name, gaveUp) }"
        ))
        val take = squash(code(adapter.substringAfter("private fun takeGaveUpList(").substringBefore("\n\n")))
        assertTrue(take, take.contains(
            "= passGaveUpByAccount.remove(accountName).orEmpty() + gaveUpByAccount.remove(accountName).orEmpty()"
        ))
        assertEquals(all, 3, count(all, "passGaveUpByAccount"))
        assertEquals(all, 1, count(all, "passGaveUpByAccount.remove("))
        assertEquals(all, 1, count(all, "gaveUpByAccount.remove("))
        assertEquals(all, 1, count(all, "takeGaveUpList(account.name)"))
        assertEquals(all, 1, count(all, "SyncNotificationHelper.notifyEventsRejected("))
        val perform = squash(code(body("private suspend fun performCalendarSync(")))
        val reconcile = perform.indexOf("reconcileDeletedEvents(account, provider, syncResult)")
        assertTrue(perform, reconcile >= 0 && reconcile < perform.indexOf("uploadDirtyEvents(account, provider, syncResult, triggerType, calendars)"))
    }

    @Test
    fun `the counts are forgotten when Proton takes the deletion, and pruned at the start of a sync once the mapping is gone`() {
        val pass = squash(code(body("private suspend fun reconcileDeletedEvents(")))
        val taken = pass.substringAfter("DeleteOpVerdict.DELETED, DeleteOpVerdict.ALREADY_GONE -> {").substringBefore("DeleteOpVerdict.REFUSED ->")
        assertTrue(taken, taken.contains(
            "calendarEventSyncDao.deleteByProtonId(account.name, mapping.protonEventId) " +
                "clearDeleteRejectionStrikes(deleteStrikeKey(null, mapping.protonEventId)) " +
                "forgetDeletionPassRefusals(account.name, mapping.protonEventId)"
        ))
        assertEquals(pass, 1, count(pass, "forgetDeletionPassRefusals("))
        val forget = squash(code(body("private fun forgetDeletionPassRefusals(")))
        assertTrue(forget, forget.contains(
            "DeletionPassRefusals.forget(PreferenceManager.getDefaultSharedPreferences(context), accountName, protonEventId)"
        ))
        // First thing in the sweep of stale counters, by the mapping table, for the counted ids alone.
        val sweep = squash(code(body("private suspend fun sweepStaleRetryCounters(")))
        assertTrue(sweep, sweep.startsWith(
            "account: Account, provider: ContentProviderClient) { pruneDeletionPassRefusals(account) val prefs = "
        ))
        val prune = squash(code(body("private suspend fun pruneDeletionPassRefusals(")))
        assertTrue(prune, prune.contains(
            "val counted = DeletionPassRefusals.countedIds(prefs, account.name) if (counted.isEmpty()) return " +
                "val mapped = HashSet<String>() for (chunk in counted.toList().chunked(400)) { " +
                "calendarEventSyncDao.mappedRows(account.name, chunk).forEach { mapped.add(it.protonEventId) } } " +
                "DeletionPassRefusals.retain(prefs, account.name) { it in mapped }"
        ))
        // The mappings it asks about name a row id: the only ones the pass can send.
        val daos = squash(File("src/main/java/me/proton/sync/db/daos/SyncDaos.kt").readText())
        assertTrue(daos, daos.contains(
            "\"SELECT proton_event_id, android_event_id FROM calendar_event_sync WHERE account_name = :accountName \" + " +
                "\"AND proton_event_id IN (:protonIds) AND android_event_id IS NOT NULL\""
        ))
        // At the start of every sync: before the download maps an event again, and before the pass.
        val perform = squash(code(body("private suspend fun performCalendarSync(")))
        val swept = perform.indexOf("sweepStaleRetryCounters(")
        val listed = perform.indexOf("fetchCalendarList(")
        val downloaded = perform.indexOf("syncCalendarEvents(")
        val reconciled = perform.indexOf("reconcileDeletedEvents(account, provider, syncResult)")
        assertTrue(perform, swept in 0 until listed && listed < downloaded && downloaded < reconciled)
        assertEquals(perform, 1, count(perform, "sweepStaleRetryCounters("))
    }

    @Test
    fun `the next sync downloads the event again, as a new row with a new mapping`() {
        // Its calendar's cursor is gone, so the calendar is not skipped as unchanged: that needs Proton's latest model
        // event to equal the stored one.
        val events = squash(code(body("private suspend fun syncCalendarEvents(")))
        assertTrue(events, events.contains("val calMapping = calendarSyncDao.getByProtonId(account.name, calInfo.id)"))
        assertTrue(events, events.contains("val storedModelEventId = calMapping?.lastModelEventId"))
        assertTrue(events, events.contains(
            "val unchanged = currentModelEventId != null && currentModelEventId == storedModelEventId && !forceRefetch val quiet = unchanged &&"
        ))
        assertTrue(events, events.contains("val wouldSkip = quiet && !dailySweepDue"))
        assertTrue(events, events.indexOf("if (wouldSkip) {") > events.indexOf("val wouldSkip = quiet && !dailySweepDue"))
        // Every event the listing has is fetched in "All" mode and in a calendar small enough for the flat path, whatever
        // is mapped; the windowed path fetches the sync range, as for any event.
        assertTrue(events, events.contains("fetchEventsFlat(account, calInfo, calDisplayName, totalEventCount, plan)"))
        // Its mapping is gone, so the per-event skip, which needs one, does not take it; nothing on the phone collides
        // with it, and no row carries its id to be taken over: it is written as a new row and mapped to it.
        val apply = squash(code(body("private suspend fun applyFetchedEvents(")))
        assertTrue(apply, apply.contains(
            "val existing = existingEventMappings[event.id] " +
                "if (existing != null && existing.lastModified >= event.lastEditTime && !isRetimeDue(existing, ctx, provider)) {"
        ))
        assertTrue(apply, apply.contains("val reclaimedId = if (existing == null) findLocalEventBySyncId(provider, androidCalId, event.id) else null"))
        assertTrue(apply, apply.contains("val effectiveExistingId = existing?.androidEventId ?: reclaimedId"))
        assertTrue(apply, apply.indexOf("calendarEventSyncDao.upsert( buildFetchedMapping(") > apply.indexOf("val androidEventId = writeFetchedEventRows("))
        val collision = squash(code(body("private fun classifyLocalCollision(")))
        assertTrue(collision, collision.contains(
            "val localRowId = existing?.androidEventId if (localRowId != null && localRowId in locallyDirtyIds) {"
        ))
        assertTrue(collision, collision.trimEnd().endsWith("return LocalCollision.APPLY_SERVER"))
        val write = squash(code(body("private fun writeFetchedEventRows(")))
        assertTrue(write, write.contains("existingEventId = effectiveExistingId,"))
        assertTrue(write, write.contains("val androidEventId = effectiveExistingId ?: ContentUris.parseId(results[0].uri!!)"))
        // At the pass after that, its row is there, so it is no deletion.
        val pass = squash(code(body("private suspend fun reconcileDeletedEvents(")))
        assertTrue(pass, pass.contains(
            "val deletedMappings = eventMappings.filter { mapping -> mapping.androidEventId != null && mapping.androidEventId !in existingAndroidIds }"
        ))
    }

    @Test
    fun `after giving up the bulk-deletion guard never asks about the event, and nothing unapproved is deleted`() {
        val pass = squash(code(body("private suspend fun reconcileDeletedEvents(")))
        // The guard's count and its question come from this pass's own read of the mappings: a mapping that went is in
        // neither, and the event's new mapping names a row that is there.
        val loop = pass.substringAfter("for (calMapping in allCalMappings) {")
        assertTrue(loop, loop.startsWith(" val eventMappings = calendarEventSyncDao.getAllByCalendar(account.name, calMapping.protonCalendarId) "))
        assertTrue(pass, pass.contains(
            "if (!deletionApproved && BulkDeletionGuard.isSuspicious( deletedMappings.size, " +
                "BulkDeletionGuard.mappedRowsPresent(eventMappings.mapNotNull { it.androidEventId }, existingAndroidIds), " +
                "eventMappings.size )) { heldDeletions += deletedMappings.size"
        ))
        assertEquals(pass, 1, count(pass, "heldDeletions +="))
        // A deletion goes to Proton only past the guard (approved, or not a bulk one), and the strikes only count what went.
        val guard = pass.indexOf("if (!deletionApproved && BulkDeletionGuard.isSuspicious(")
        assertTrue(pass, guard >= 0 && guard < pass.indexOf("apiClient.syncCalendarEvents(") &&
            pass.indexOf("apiClient.syncCalendarEvents(") < pass.indexOf("reconcileDeletionFailed("))
        // The guard keeps no event ids, so nothing of the event is left with it.
        val guardSource = File("src/main/java/me/proton/sync/calendar/BulkDeletionGuard.kt").readText().replace("\r\n", "\n")
        assertTrue(guardSource.contains("No event ids are persisted"))
        // The approval is settled at the end of the pass as before.
        assertTrue(pass, pass.contains(
            "} else if (deletionApproved) { BulkDeletionGuard.clear(context, account.name) SyncNotificationHelper.cancelBulkDeletionPrompt(context, account.name) }"
        ))
    }

    @Test
    fun `the pass counts a rejection by Proton id in its own list, gives the deletion up at the third, and fails no run`() {
        val pass = squash(code(body("private suspend fun reconcileDeletedEvents(")))
        val rejected = pass.substringAfter("DeleteOpVerdict.REJECTED -> {").substringBefore("null -> {")
        // Named with Proton's own error (owner, 2026-11-23), from the answer that decided the verdict.
        assertTrue(rejected, rejected.contains(
            "if (passDeletionRejected(account.name, mapping.protonEventId, op ?: SyncOpResult(null, null))) { " +
                "calendarEventSyncDao.deleteByProtonId(account.name, mapping.protonEventId) " +
                "resetCalendarCursor(account, mapping.protonCalendarId) " +
                "passDeletionGivenUp(account.name, mapping.protonEventId, DeletionPassRefusals.rejectionReason(op)) " +
                "SyncLog.w(TAG, \"Giving up the delete of event \${mapping.protonEventId}; it comes back with the next sync\") }"
        ))
        // No longer under the per-row counters' key, which the sweep drops at every sync start; and still no failed run.
        for (never in listOf(
            "deleteStrikeKey(", "bumpDeleteRejectionStrike(", "delete_reject_count_", "numIoExceptions", "recordSwallowedFailure",
        )) {
            assertFalse("$never: $rejected", rejected.contains(never))
        }
        val counted = squash(code(body("private fun passDeletionRejected(")))
        assertTrue(counted, counted.contains(
            "val rejections = DeletionPassRefusals.recordRejection( " +
                "PreferenceManager.getDefaultSharedPreferences(context), accountName, protonEventId ) " +
                "val givesUp = DeletionPassRefusals.rejectionGivesUp(rejections)"
        ))
        assertTrue(counted, counted.trimEnd().endsWith("return givesUp"))
        for (never in listOf(
            "numIoExceptions", "recordSwallowedFailure", "recordRefusal(", "deleteStrikeKey(", "bumpDeleteRejectionStrike(",
            "prefs.edit()", "apiClient.", "BulkDeletionGuard",
        )) {
            assertFalse("$never: $counted", counted.contains(never))
        }
        // A plain function, off the suspend ones for the ART limits, called once, from the REJECTED branch.
        assertFalse(adapter.contains("suspend fun passDeletionRejected("))
        val all = squash(code(adapter))
        assertEquals(all, 1, count(all, "passDeletionRejected(account.name, mapping.protonEventId, "))
        assertEquals(all, 1, count(all, "DeletionPassRefusals.recordRejection("))
        assertEquals(all, 1, count(all, "DeletionPassRefusals.rejectionGivesUp("))
    }

    @Test
    fun `the read-only refusal and the third rejection give up as before and are named in the notice, as the third refusal is`() {
        val pass = squash(code(body("private suspend fun reconcileDeletedEvents(")))
        val refused = pass.substringAfter("DeleteOpVerdict.REFUSED -> {").substringBefore("DeleteOpVerdict.REJECTED -> {")
        // Named with the refused deletion's text (owner, 2026-11-23: the read-only refusal keeps it).
        assertTrue(refused, refused.contains(
            "calendarEventSyncDao.deleteByProtonId(account.name, mapping.protonEventId) " +
                "resetCalendarCursor(account, mapping.protonCalendarId) " +
                "passDeletionGivenUp(account.name, mapping.protonEventId, deleteRefusalReason()) " +
                "SyncLog.w(TAG, \"Proton refuses deleting event \${mapping.protonEventId} (code=\${op?.code ?: 400}); \" + " +
                "\"it comes back with the next sync\") }"
        ))
        for (text in listOf("numIoExceptions", "recordSwallowedFailure", "DeletionPassRefusals.", "deleteStrikeKey(")) {
            assertFalse("$text: $refused", refused.contains(text))
        }
        // The pass's three give-ups, and only they, end in passDeletionGivenUp, which adds the line (the refused deletion's
        // text, or Proton's error for the third rejection), each after the mapping went and the calendar's cursor was
        // reset: what makes the next sync download the event again (the test of the download above).
        val rejected = pass.substringAfter("DeleteOpVerdict.REJECTED -> {").substringBefore("null -> {")
        val giveUp = squash(code(body("private suspend fun giveUpPassDeletion(")))
        for (branch in listOf(refused, rejected, giveUp)) {
            val dropped = branch.indexOf("calendarEventSyncDao.deleteByProtonId(account.name, mapping.protonEventId)")
            val reset = branch.indexOf("resetCalendarCursor(account, mapping.protonCalendarId)")
            val named = branch.indexOf("passDeletionGivenUp(account.name, mapping.protonEventId, ")
            assertTrue(branch, dropped >= 0 && reset >= 0 && named > dropped && named > reset)
            assertEquals(branch, 1, count(branch, "passDeletionGivenUp("))
        }
        val all = squash(code(adapter))
        // Its definition and those three calls; two of them in the pass itself.
        assertEquals(all, 4, count(all, "passDeletionGivenUp("))
        assertEquals(pass, 2, count(pass, "passDeletionGivenUp(account.name, mapping.protonEventId, "))
        // Proton taking the deletion is no give-up: no line.
        val taken = pass.substringAfter("DeleteOpVerdict.DELETED, DeleteOpVerdict.ALREADY_GONE -> {").substringBefore("DeleteOpVerdict.REFUSED ->")
        assertFalse(taken, taken.contains("passDeletionGivenUp("))
    }

    @Test
    fun `the tombstones' own counts by row id and the sweep of per-row counters are as they were`() {
        // The upload's delete path still counts its rejections under delete_reject_count_<androidEventId>, now the one
        // counting site of that family, and gives up at the third.
        val settle = squash(code(body("private suspend fun settleDeleteResponse(")))
        assertTrue(settle, settle.contains(
            "DeleteOpVerdict.REJECTED -> { if (bumpDeleteRejectionStrike(deleteStrikeKey(androidEventId, mapping.protonEventId), " +
                "mapping.protonEventId, op)) { restoreRefusedDelete(account, provider, mapping, androidEventId, " +
                "op.error ?: \"Proton error code \${op.code}\", syncResult) } else { " +
                "synchronized(syncResult.stats) { syncResult.stats.numSkippedEntries++ } } }"
        ))
        val all = squash(code(adapter))
        // Its definition and that one call.
        assertEquals(all, 2, count(all, "bumpDeleteRejectionStrike("))
        val bump = squash(code(body("private fun bumpDeleteRejectionStrike(")))
        assertTrue(bump, bump.contains(
            "val prefs = PreferenceManager.getDefaultSharedPreferences(context) val key = strikeKey " +
                "val strike = prefs.getInt(key, 0) + 1 if (strike >= 3) {"
        ))
        assertTrue(bump, bump.contains("prefs.edit().remove(key).apply() return true }"))
        assertTrue(bump, bump.trimEnd().endsWith("prefs.edit().putInt(key, strike).apply() return false"))
        // The key: the row's id while it exists. A Proton id's is only cleared now (the deletion taken, any give-up of the
        // pass), for what 2.1.0 left; the family is built nowhere else.
        assertTrue(all, all.contains(
            "private fun deleteStrikeKey(androidEventId: Long?, protonEventId: String): String = " +
                "if (androidEventId != null) \"delete_reject_count_\$androidEventId\" else \"delete_reject_count_\$protonEventId\""
        ))
        assertEquals(all, 2, count(all, "deleteStrikeKey(null, "))
        assertEquals(all, 2, count(all, "clearDeleteRejectionStrikes(deleteStrikeKey(null, "))
        assertEquals(all, 3, count(all, "delete_reject_count_"))
        // The sweep: the family read by row id, against the provider, as it was written for.
        val sweep = squash(code(body("private suspend fun sweepStaleRetryCounters(")))
        assertTrue(sweep, sweep.contains("val deletePrefix = \"delete_reject_count_\""))
        assertTrue(sweep, sweep.contains(
            "allKeys.filter { it.startsWith(deletePrefix) }.map { it.removePrefix(deletePrefix) }) " +
                ".mapNotNull { it.toLongOrNull() } .toSet() val liveAndroidIds = existingEventRowIds(provider, account, androidIds)"
        ))
        assertTrue(sweep, sweep.contains("staleCounterKeys(allKeys, deletePrefix, liveAndroidIds)"))
    }

    // ---- The third rejection's text (owner, 2026-11-23) ---------------------------------------------------------------

    /** Proton's answer to a deletion as events/sync gives it and firstSyncOpResult reads it (Gson's numbers are Doubles). */
    private fun answer(code: Number?, error: String?): SyncOpResult = firstSyncOpResult(
        mapOf(
            "Code" to 1001.0,
            "Responses" to listOf(mapOf("Index" to 0.0, "Response" to buildMap<String, Any> {
                if (code != null) put("Code", code)
                if (error != null) put("Error", error)
            })),
        )
    )

    @Test
    fun `the third rejection is named with Proton's own error, exactly as the upload's delete path names its own`() {
        // Rejections as Proton could give them, with made-up texts: an error, a code alone, a blank error, an error without
        // a code.
        val rejections = listOf(
            answer(2000.0, "Made-up invalid requirement"), answer(2061.0, "Made-up invalid id"), answer(2511.0, null),
            answer(9001.0, ""), answer(2000.0, "   "), answer(null, "Made-up error without a code"), answer(1002, "Made-up"),
        )
        for (op in rejections) {
            assertTrue("$op", deleteOpVerdict(op.code, op.error) == DeleteOpVerdict.REJECTED)
            val reason = DeletionPassRefusals.rejectionReason(op)
            // settleDeleteResponse's own text for its third rejection, character for character.
            assertEquals("$op", op.error ?: "Proton error code ${op.code}", reason)
            assertTrue("$op", reason.isNotBlank() && !reason.contains("null"))
        }
        assertEquals(
            listOf(
                "Made-up invalid requirement", "Made-up invalid id", "Proton error code 2511", "Proton error code 9001",
                "Proton error code 2000", "Made-up error without a code", "Made-up",
            ),
            rejections.map { DeletionPassRefusals.rejectionReason(it) }
        )
        // A blank error that did not come through firstSyncOpResult gives the code form too, as the upload's own give-up
        // does (handleUploadOpRejection): nothing in the line is ever empty.
        for (blank in listOf("", " ", "\t")) {
            assertEquals("Proton error code 2000", DeletionPassRefusals.rejectionReason(SyncOpResult(2000, blank)))
        }
        // What never reaches it: an answer with neither an error nor a code is a deletion Proton took, and 2011 and 2026
        // are the read-only refusal, which keeps the refused deletion's text.
        for (taken in listOf(answer(null, null), answer(null, " "))) {
            assertTrue("$taken", deleteOpVerdict(taken.code, taken.error) == DeleteOpVerdict.DELETED)
        }
        for (readOnly in listOf(2011.0, 2026.0)) {
            val refused = answer(readOnly, "Made-up")
            assertTrue("$refused", deleteOpVerdict(refused.code, refused.error) == DeleteOpVerdict.REFUSED)
        }
        // The upload's delete path still names its third rejection so, and the rule is the pure object's.
        val settle = squash(code(body("private suspend fun settleDeleteResponse(")))
        assertTrue(settle, settle.contains(
            "restoreRefusedDelete(account, provider, mapping, androidEventId, op.error ?: \"Proton error code \${op.code}\", syncResult)"
        ))
        val rule = squash(File("src/main/java/me/proton/sync/calendar/DeletionPassRefusals.kt").readText())
        assertTrue(rule, rule.contains(
            "fun rejectionReason(op: SyncOpResult?): String = " +
                "op?.error?.takeIf { it.isNotBlank() } ?: \"Proton error code \${op?.code}\""
        ))
    }

    @Test
    fun `the third rejection's line gives Proton's error, and the read-only refusal's and the third refusal's the refusal text`() {
        // A is rejected at every sync, B is on a read-only calendar, C is refused with a 409 at every sync.
        val pass = Pass(FakePrefs(), account)
        pass.vanished.addAll(listOf(EVENT_A, EVENT_B, EVENT_C))
        val answers = mapOf(EVENT_A to OP_REJECTED, EVENT_B to READ_ONLY, EVENT_C to 409)
        val runs = List(3) { pass.sync { answers.getValue(it) } }
        assertEquals(
            listOf(
                listOf("$EVENT_A rejected", "$EVENT_B given up", "$EVENT_C failed 409") to true,
                listOf("$EVENT_A rejected", "$EVENT_C failed 409") to true,
                listOf("$EVENT_A given up", "$EVENT_C given up") to false,
            ),
            runs
        )
        assertEquals(
            listOf(EVENT_B to REFUSAL_TEXT, EVENT_A to "Made-up rejection of the deletion", EVENT_C to REFUSAL_TEXT),
            pass.reasons
        )
        // A line each, in the notice of the upload after its give-up.
        assertEquals(listOf(listOf(EVENT_B), listOf(EVENT_A, EVENT_C)), pass.notices)
        // Proton's rejection with no error text: the line gives its code, never nothing.
        val bare = Pass(FakePrefs(), account)
        bare.rejection = SyncOpResult(2061, null)
        bare.vanished.add(EVENT_A)
        repeat(3) { bare.sync { OP_REJECTED } }
        assertEquals(listOf(EVENT_A to "Proton error code 2061"), bare.reasons)
    }

    @Test
    fun `each give-up of the pass gives its own text, and only the third rejection's is Proton's error`() {
        assertTrue(adapter.contains("private fun passDeletionGivenUp(accountName: String, protonEventId: String, reason: String) {"))
        val pass = squash(code(body("private suspend fun reconcileDeletedEvents(")))
        val refused = pass.substringAfter("DeleteOpVerdict.REFUSED -> {").substringBefore("DeleteOpVerdict.REJECTED -> {")
        val rejected = pass.substringAfter("DeleteOpVerdict.REJECTED -> {").substringBefore("null -> {")
        val giveUp = squash(code(body("private suspend fun giveUpPassDeletion(")))
        assertTrue(refused, refused.contains("passDeletionGivenUp(account.name, mapping.protonEventId, deleteRefusalReason())"))
        assertTrue(giveUp, giveUp.contains("passDeletionGivenUp(account.name, mapping.protonEventId, deleteRefusalReason())"))
        assertTrue(rejected, rejected.contains(
            "passDeletionGivenUp(account.name, mapping.protonEventId, DeletionPassRefusals.rejectionReason(op))"
        ))
        // The op is the answer that decided the verdict: the one val, read from the 200's body.
        assertTrue(pass, pass.contains("val op = if (response.isSuccessful) firstSyncOpResult(response.body()) else null"))
        assertTrue(pass, pass.contains("op != null -> deleteOpVerdict(op.code, op.error)"))
        assertEquals(pass, 1, count(pass, "val op = "))
        val all = squash(code(adapter))
        assertEquals(all, 1, count(all, "rejectionReason("))
        assertEquals(all, 2, count(all, "passDeletionGivenUp(account.name, mapping.protonEventId, deleteRefusalReason())"))
        // The refused deletion's text: its definition, the upload's two refusals (restoreRefusedDelete) and the pass's two.
        assertEquals(all, 5, count(all, "deleteRefusalReason()"))
        // The line reads "(no title): " and the text, in the notice's own untranslated format: no string is added or changed.
        val notices = squash(File("src/main/java/me/proton/sync/SyncNotificationHelper.kt").readText())
        assertTrue(notices.contains("context.getString(R.string.notification_events_rejected_line, it.first, it.second)"))
        val english = File("src/main/res/values/strings.xml").readText()
        assertTrue(english.contains(
            "<string name=\"notification_events_rejected_line\" translatable=\"false\">\u2022 %1\$s: %2\$s</string>"
        ))
    }
}

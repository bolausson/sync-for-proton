package me.proton.sync.contacts

import android.accounts.Account
import android.content.ContentProviderClient
import android.content.ContentProviderOperation
import android.content.OperationApplicationException
import android.provider.ContactsContract
import me.proton.sync.SyncUtils

/**
 * Re-spells the birthdays and anniversaries that versions up to 2.2.0 wrote in the basic form ("19900215", "--0215")
 * to the extended form the download writes since 2.2.1 ([ContactDates]), so calendar and birthday apps that skip the
 * basic form show them without waiting for each contact to change on Proton.
 *
 * Only one column changes: Event.START_DATE of the data row, updated in place and as the sync adapter, so Android
 * does not mark the contact as edited and nothing is uploaded. Never through a download, a delete and re-insert, or a
 * reset of the sync bookkeeping: a provider that marks rewritten rows as edited (Samsung's does for inserts, see
 * ContactsSyncAdapter.isRedundantFollowUp) would turn that into an upload of the whole address book.
 *
 * Each row goes in one transaction with a check that its contact is still clean (not edited, not deleted), and the
 * update matches only while the date still holds the old text, so an edit made meanwhile always wins. A contact with
 * an unsent edit is left for a later sync. The rows are their own progress record: once none is left in the basic
 * form, a pass costs one query.
 *
 * In case a provider marks such a contact as edited after all, the pass watches for it. The first row it manages to
 * write (a full date when one is left; at most [CANARY_TRIES] are tried) is a canary: after [CANARY_SETTLE_MS] its
 * contact must still be clean before anything else is written. Every later chunk is followed by a wait of [SETTLE_MS]
 * and a check of every contact written so far. A contact found edited shows someone else's change when it was
 * deleted, or when one of its dates no longer holds what the pass wrote there or left there; that only stops the run,
 * and the rest goes at a later sync. Any other contact found edited counts against the provider:
 * - Written in the step just checked: the pass halts, and the caller records the app version, so it is not tried again
 *   before an update.
 * - Written in an earlier step and found clean since: a user's edit, or a provider that marks contacts late. A wait of
 *   [RECHECK_MS] tells most of them apart: a provider goes on marking the contacts written after, so a further one
 *   found edited halts the pass. Otherwise the run stops, and the caller records that: the second such stop in one
 *   app version halts. When the canary is the only one found, the next contacts were written a whole canary wait
 *   later, so no wait tells the two apart, and the run takes that stop.
 *
 * A run stops starting chunks after [PASS_BUDGET_MS] and leaves the rest to the next sync: the pass moves no network
 * data, and Android cancels a sync that moves none for a whole minute.
 *
 * What then uploads is every contact the pass wrote that the provider marks: the canary alone for a provider that
 * marks within [CANARY_SETTLE_MS], every chunk written before the marks showed for one that marks later or in
 * batches. A full date goes up as the text Proton already has, a year-less one as "--MM-dd". A mark that comes after
 * a run's last check is not seen at all: those contacts upload at a later sync, and nothing records the provider.
 */
internal object ContactDateRewrite {

    /** One Event row of the account, as read from the provider. */
    data class EventRow(val dataId: Long, val rawContactId: Long, val type: Int, val startDate: String?) {
        /** Without the date, so a log line that prints a row can never print someone's birthday. */
        override fun toString() = "EventRow(dataId=$dataId, rawContactId=$rawContactId, type=$type)"
    }

    /** One row to re-spell: from [old] to [new], only while the row still holds [old]. */
    data class Rewrite(val dataId: Long, val rawContactId: Long, val old: String, val new: String) {
        /** Without the dates, as [EventRow]. */
        override fun toString() = "Rewrite(dataId=$dataId, rawContactId=$rawContactId)"
    }

    /**
     * What a pass did, for its log line. [halted]: a contact it wrote was found edited (the caller records the version).
     * [stopped]: it ended this run early without a halt, and the rest goes at a later sync. [late]: what ended it was a
     * contact written in an earlier step ([apply]). [deferred]: the run reached [PASS_BUDGET_MS] with rows left, which
     * go at the next sync.
     */
    data class Outcome(
        val written: Int,
        val waiting: Int,
        val changed: Int,
        val halted: Boolean = false,
        val stopped: Boolean = false,
        val late: Boolean = false,
        val deferred: Boolean = false,
    )

    /**
     * What one row's write did: the date was re-spelled; the contact was clean but the date no longer held the old
     * text; or the contact was no longer clean (edited or deleted), so nothing was written.
     */
    enum class Write { WRITTEN, NO_MATCH, REFUSED }

    /** The provider steps [apply] uses, so a JVM test can drive its decisions with a fake. [ProviderStore] is the real one. */
    interface Store {
        /** Of [rawContactIds], those neither edited nor deleted right now. */
        fun clean(rawContactIds: Set<Long>): Set<Long>

        /** Re-spells [rows], in one transaction where it can; one result per row, in order. */
        fun write(rows: List<Rewrite>): List<Write>

        /** Of [rawContactIds], those marked as edited right now. */
        fun dirty(rawContactIds: Set<Long>): Set<Long>

        /**
         * Whether the raw contact [rawContactId] was deleted, or one of its rows in [expected] (data id to the text it
         * should hold) is gone or holds another text: someone else's change.
         */
        fun changedElsewhere(rawContactId: Long, expected: Map<Long, String>): Boolean

        /** Waits [ms]; false when the wait was interrupted (the interrupt is left set). */
        fun pause(ms: Long): Boolean

        /** A clock in milliseconds that only moves forward, for [PASS_BUDGET_MS]. */
        fun now(): Long
    }

    /** Rows per provider call: two operations each, far below the Binder budget (ContactWriteBudget). */
    const val CHUNK_ROWS = 50

    /** How long to wait after a chunk before reading the flags back: a provider can set a flag after the call returns. */
    const val SETTLE_MS = 2_000L

    /**
     * How long the canary waits before anything else is written: far longer than any provider delay seen (about 0.1 s),
     * so a provider that marks contacts late costs one upload. Paid once per run that has more than the canary to
     * write; a canary with nothing after it waits [SETTLE_MS], as nothing more could upload.
     */
    const val CANARY_SETTLE_MS = 30_000L

    /**
     * The second wait when only contacts written in earlier steps were found edited: longer than one chunk step (a
     * chunk's write and [SETTLE_MS]), so a provider that marks chunks late has marked the next chunk's contacts too.
     */
    const val RECHECK_MS = 4_000L

    /**
     * How long a run may go on starting chunks, counted from its start. Android's SyncManager cancels a sync that moves
     * 10 bytes or fewer over the network in a 60 s window, and the pass moves none; this budget, plus the last chunk's
     * write, its wait and a [RECHECK_MS], stays under that minute.
     */
    const val PASS_BUDGET_MS = 45_000L

    /** How many rows the canary search tries at most (it writes one) before giving the run up. */
    const val CANARY_TRIES = 3

    /** How many seconds a contact's sync record may be ahead of Proton's last change and still count as current. */
    const val CURRENT_SLACK_S = 60L

    /** At most this many ids in one IN (...) list, as elsewhere in the contacts code. */
    private const val IN_CHUNK = 400

    /**
     * Whether a contact's phone copy counts as Proton's current one, from its sync record's time [lastModified] and
     * Proton's last change [serverTime], both in seconds. At or after Proton's time is the download's own unchanged
     * test. A download records Proton's time exactly and an upload the phone's clock as the reply came back, so a
     * record more than [CURRENT_SLACK_S] ahead comes from a phone clock running ahead, which can hide an edit made on
     * Proton since: such a contact waits for its next download.
     */
    fun isCurrent(lastModified: Long, serverTime: Long): Boolean =
        lastModified >= serverTime && lastModified - serverTime <= CURRENT_SLACK_S

    /**
     * The rows to re-spell: birthdays and anniversaries of the contacts in [currentRawIds] whose date is still in a basic
     * form. The caller passes the contacts whose phone copy is Proton's current one. Custom and other dates (X-ABDATE)
     * stay as they are: the upload copies those verbatim, so re-spelling them would change what Proton stores.
     */
    fun plan(rows: List<EventRow>, currentRawIds: Set<Long>): List<Rewrite> =
        rows.mapNotNull { row ->
            val old = row.startDate
            val new = old?.let { ContactDates.toExtended(it) }
            if (old == null || new == null || !isBirthdayOrAnniversary(row.type) || row.rawContactId !in currentRawIds) {
                null
            } else {
                Rewrite(row.dataId, row.rawContactId, old, new)
            }
        }

    /**
     * Full dates first, dates without a year last, otherwise in plan order. On a provider that marks the contact as
     * edited, the earliest rows written are what upload: the canary alone when it marks within [CANARY_SETTLE_MS],
     * every chunk written before its marks showed when it marks later. For a full date that sends the exact text
     * Proton has, while a year-less "--0215" would go up as "--02-15".
     */
    fun ordered(rows: List<Rewrite>): List<Rewrite> = rows.sortedBy { if (it.old.length == 8) 0 else 1 }

    private fun isBirthdayOrAnniversary(type: Int): Boolean =
        type == ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY ||
            type == ContactsContract.CommonDataKinds.Event.TYPE_ANNIVERSARY

    /**
     * The account's birthday and anniversary rows still in a basic form, whichever contact they belong to; empty when
     * there is nothing to do. Filtered here rather than in SQL, so the selection stays one every contacts provider is
     * known to accept (LocalContactView reads the same way).
     */
    fun candidates(provider: ContentProviderClient, account: Account): List<EventRow> {
        val out = ArrayList<EventRow>()
        val cursor = provider.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(
                ContactsContract.Data._ID,
                ContactsContract.Data.RAW_CONTACT_ID,
                ContactsContract.CommonDataKinds.Event.TYPE,
                ContactsContract.CommonDataKinds.Event.START_DATE,
            ),
            "${ContactsContract.RawContacts.ACCOUNT_NAME} = ? AND ${ContactsContract.RawContacts.ACCOUNT_TYPE} = ? AND " +
                "${ContactsContract.Data.MIMETYPE} = ?",
            arrayOf(account.name, account.type, ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE),
            null,
        ) ?: throw IllegalStateException("contacts provider returned no cursor")
        cursor.use { c ->
            while (c.moveToNext()) {
                val type = if (c.isNull(2)) -1 else c.getInt(2)
                val date = c.getString(3)
                if (isBirthdayOrAnniversary(type) && date != null && ContactDates.toExtended(date) != null) {
                    out.add(EventRow(c.getLong(0), c.getLong(1), type, date))
                }
            }
        }
        return out
    }

    /**
     * Writes [rewrites] as the object comment describes. [struck]: a run of this app version already stopped on a
     * contact written in an earlier step, so the next such stop halts. The pass calls [strike] for such a stop and
     * [halt] for a halt, and returns after either. A cancelled sync does not stop it: its only caller runs inside
     * SyncUtils.runUninterruptible, whose worker nothing interrupts, so it runs to its end (the waits, and one provider
     * call per chunk, or one per row when a row in it is refused); an interrupted wait would end the run without a
     * halt.
     */
    fun apply(store: Store, rewrites: List<Rewrite>, struck: Boolean, strike: () -> Unit, halt: () -> Unit): Outcome {
        val started = store.now()
        val clean = store.clean(rewrites.mapTo(HashSet()) { it.rawContactId })
        val run = Run(store, ordered(rewrites.filter { it.rawContactId in clean }))
        val waiting = rewrites.size - run.ready.size
        while (run.written == 0 && run.tried < minOf(run.ready.size, CANARY_TRIES)) run.write(1)
        if (run.written == 0) return run.outcome(waiting, stopped = run.ready.isNotEmpty())
        while (true) {
            when (run.settle()) {
                Settle.CLEAN -> when {
                    run.tried == run.ready.size -> return run.outcome(waiting)
                    store.now() - started >= PASS_BUDGET_MS -> return run.outcome(waiting, deferred = true)
                    else -> run.write(CHUNK_ROWS)
                }
                Settle.ELSEWHERE -> return run.outcome(waiting, stopped = true)
                Settle.EDITED -> {
                    halt()
                    return run.outcome(waiting, halted = true)
                }
                Settle.LATE -> if (struck) {
                    halt()
                    return run.outcome(waiting, halted = true, late = true)
                } else {
                    strike()
                    return run.outcome(waiting, stopped = true, late = true)
                }
            }
        }
    }

    /**
     * What a check found: nothing; a contact someone else changed; one written in the step just checked, or a further
     * one after the extra wait; only ones written in an earlier step.
     */
    private enum class Settle { CLEAN, ELSEWHERE, EDITED, LATE }

    /** One run over [ready]: which rows were tried and what each write did, in steps that each end with a check. */
    private class Run(val store: Store, val ready: List<Rewrite>) {
        var tried = 0
        var written = 0
        var changed = 0
        private val results = arrayOfNulls<Write>(ready.size)
        private val rowsOf: Map<Long, List<Int>> = ready.indices.groupBy { ready[it].rawContactId }

        /** Each contact written so far, with the step of its latest written row: 0 is the canary search, then one per chunk. */
        private val writtenIn = HashMap<Long, Int>()
        private var step = 0

        /** Writes the next [n] rows: one provider call, or one per row when a row in it is refused. */
        fun write(n: Int) {
            val from = tried
            val rows = ready.subList(from, minOf(from + n, ready.size))
            val out = store.write(rows)
            rows.forEachIndexed { i, row ->
                val result = out.getOrElse(i) { Write.REFUSED }
                results[from + i] = result
                if (result == Write.WRITTEN) {
                    written++
                    writtenIn[row.rawContactId] = step
                } else {
                    changed++
                }
            }
            tried = from + rows.size
        }

        /**
         * After [CANARY_SETTLE_MS] for a canary with rows after it and [SETTLE_MS] otherwise, whether a contact written so
         * far is found edited, and what that says. Ends the step: rows written next belong to the next one. An
         * interrupted wait counts as someone else's change.
         */
        fun settle(): Settle {
            val latest = step++
            val wait = if (latest == 0 && tried < ready.size) CANARY_SETTLE_MS else SETTLE_MS
            if (!store.pause(wait)) return Settle.ELSEWHERE
            val dirty = store.dirty(writtenIn.keys)
            if (dirty.isEmpty()) return Settle.CLEAN
            val suspects = unexplained(dirty)
            if (suspects.isEmpty()) return Settle.ELSEWHERE
            if (suspects.any { writtenIn[it] == latest }) return Settle.EDITED
            if (!store.pause(RECHECK_MS)) return Settle.ELSEWHERE
            return if (unexplained(store.dirty(writtenIn.keys)).any { it !in suspects }) Settle.EDITED else Settle.LATE
        }

        /** Of [dirty], the contacts that show no change by someone else. */
        private fun unexplained(dirty: Set<Long>): Set<Long> =
            dirty.filterTo(HashSet()) { !store.changedElsewhere(it, expected(it)) }

        /**
         * What the rows of [rawContactId] hold unless someone else changed them: the new text once written, the old one
         * before. Not a row whose old text was already gone at its write: that change came while the contact was clean,
         * so it says nothing about what marked the contact since.
         */
        private fun expected(rawContactId: Long): Map<Long, String> {
            val out = HashMap<Long, String>()
            for (i in rowsOf[rawContactId].orEmpty()) {
                when (results[i]) {
                    Write.WRITTEN -> out[ready[i].dataId] = ready[i].new
                    Write.NO_MATCH -> Unit
                    Write.REFUSED, null -> out[ready[i].dataId] = ready[i].old
                }
            }
            return out
        }

        fun outcome(
            waiting: Int,
            halted: Boolean = false,
            stopped: Boolean = false,
            late: Boolean = false,
            deferred: Boolean = false,
        ) = Outcome(written, waiting, changed, halted, stopped, late, deferred)
    }

    /** [Store] over the contacts provider, writing as the sync adapter of [account]. */
    class ProviderStore(private val provider: ContentProviderClient, private val account: Account) : Store {

        override fun clean(rawContactIds: Set<Long>): Set<Long> =
            rawContactIdsWhere(
                rawContactIds,
                "${ContactsContract.RawContacts.DIRTY} = 0 AND ${ContactsContract.RawContacts.DELETED} = 0",
            )

        override fun dirty(rawContactIds: Set<Long>): Set<Long> =
            rawContactIdsWhere(rawContactIds, "${ContactsContract.RawContacts.DIRTY} = 1")

        override fun write(rows: List<Rewrite>): List<Write> {
            if (rows.isEmpty()) return emptyList()
            return try {
                val results = provider.applyBatch(operations(rows))
                rows.indices.map { i -> if ((results.getOrNull(2 * i + 1)?.count ?: 0) > 0) Write.WRITTEN else Write.NO_MATCH }
            } catch (e: OperationApplicationException) {
                // Only an assert throws: a contact in the batch was no longer clean, and nothing in it was written.
                if (rows.size == 1) listOf(Write.REFUSED) else rows.map { write(listOf(it)).single() }
            }
        }

        /** A query that returns no cursor answers false: no sign of someone else's change, so the contact counts against the provider. */
        override fun changedElsewhere(rawContactId: Long, expected: Map<Long, String>): Boolean {
            val deleted = provider.query(
                ContactsContract.RawContacts.CONTENT_URI,
                arrayOf(ContactsContract.RawContacts.DELETED),
                "${ContactsContract.RawContacts._ID} = ?",
                arrayOf(rawContactId.toString()),
                null,
            )?.use { c -> !c.moveToFirst() || c.getInt(0) != 0 } ?: return false
            if (deleted) return true
            val held = HashMap<Long, String?>()
            for (ids in expected.keys.chunked(IN_CHUNK)) {
                provider.query(
                    ContactsContract.Data.CONTENT_URI,
                    arrayOf(ContactsContract.Data._ID, ContactsContract.CommonDataKinds.Event.START_DATE),
                    "${ContactsContract.Data._ID} IN (${ids.joinToString(",") { "?" }})",
                    ids.map { it.toString() }.toTypedArray(),
                    null,
                )?.use { c -> while (c.moveToNext()) held[c.getLong(0)] = c.getString(1) } ?: return false
            }
            // A row that is gone reads as null here, which no expected text equals.
            return expected.any { (dataId, text) -> held[dataId] != text }
        }

        override fun pause(ms: Long): Boolean =
            try {
                Thread.sleep(ms)
                true
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }

        override fun now(): Long = android.os.SystemClock.elapsedRealtime()

        /**
         * Per row, in this order: its contact is still clean (expected exactly once), then the date, matched by the data
         * row's id on the directory URI (an item URI would ignore the selection) and only while it still holds the old
         * text. Nothing else: no insert, no delete, no other column, no flag.
         */
        private fun operations(rows: List<Rewrite>): ArrayList<ContentProviderOperation> {
            val ops = ArrayList<ContentProviderOperation>(rows.size * 2)
            val dataUri = SyncUtils.asCallerSyncAdapter(ContactsContract.Data.CONTENT_URI, account)
            for (row in rows) {
                ops.add(
                    ContentProviderOperation.newAssertQuery(ContactsContract.RawContacts.CONTENT_URI)
                        .withSelection(
                            "${ContactsContract.RawContacts._ID} = ? AND ${ContactsContract.RawContacts.DIRTY} = 0 AND " +
                                "${ContactsContract.RawContacts.DELETED} = 0",
                            arrayOf(row.rawContactId.toString()),
                        )
                        .withExpectedCount(1)
                        .build()
                )
                ops.add(
                    ContentProviderOperation.newUpdate(dataUri)
                        .withSelection(
                            "${ContactsContract.Data._ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ? AND " +
                                "${ContactsContract.CommonDataKinds.Event.START_DATE} = ?",
                            arrayOf(row.dataId.toString(), ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE, row.old),
                        )
                        .withValue(ContactsContract.CommonDataKinds.Event.START_DATE, row.new)
                        .build()
                )
            }
            return ops
        }

        /** Of [ids], the raw contacts that meet [condition]. A failed query throws, which ends the pass without a halt. */
        private fun rawContactIdsWhere(ids: Set<Long>, condition: String): Set<Long> {
            val out = HashSet<Long>()
            for (chunk in ids.chunked(IN_CHUNK)) {
                val cursor = provider.query(
                    ContactsContract.RawContacts.CONTENT_URI,
                    arrayOf(ContactsContract.RawContacts._ID),
                    "${ContactsContract.RawContacts._ID} IN (${chunk.joinToString(",") { "?" }}) AND $condition",
                    chunk.map { it.toString() }.toTypedArray(),
                    null,
                ) ?: throw IllegalStateException("contacts provider returned no cursor")
                cursor.use { c -> while (c.moveToNext()) out.add(c.getLong(0)) }
            }
            return out
        }
    }
}

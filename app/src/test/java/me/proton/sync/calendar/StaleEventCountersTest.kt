package me.proton.sync.calendar

import me.proton.sync.SyncPreferences
import me.proton.sync.contacts.FakePrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class StaleEventCountersTest {

    private val keys = setOf(
        "upload_reject_count_14937",
        "upload_reject_count_14999",
        "upload_reject_count_15211",
        "delete_reject_count_14937",
        "invite_retry_count_abc==",
        // Neighbours that must never be touched.
        "calendar_sync_mode_alice",
        "conflict_held_alice",
        "upload_reject_count_",
    )

    @Test
    fun `a counter whose event is gone is stale`() {
        // The pair found on the test phone: two counters at 2 strikes naming rows deleted days before.
        val stale = staleCounterKeys(keys, "upload_reject_count_", liveIds = setOf("15211"))
        assertEquals(setOf("upload_reject_count_14937", "upload_reject_count_14999"), stale)
    }

    @Test
    fun `a counter whose event still exists is kept`() {
        val stale = staleCounterKeys(keys, "upload_reject_count_", liveIds = setOf("14937", "14999", "15211"))
        assertTrue(stale.isEmpty())
    }

    @Test
    fun `an unknown live set sweeps nothing`() {
        // Not knowing must never be read as "gone": removing a counter for an event that DOES exist
        // hands it a free strike, which is worse than leaving a dead key behind.
        assertTrue(staleCounterKeys(keys, "upload_reject_count_", liveIds = null).isEmpty())
    }

    @Test
    fun `an empty live set means every counter of that family is stale`() {
        // A phone with no events at all, after a wipe or a forced resync.
        val stale = staleCounterKeys(keys, "upload_reject_count_", liveIds = emptySet())
        assertEquals(
            setOf("upload_reject_count_14937", "upload_reject_count_14999", "upload_reject_count_15211"),
            stale,
        )
    }

    @Test
    fun `only the named family is considered`() {
        val stale = staleCounterKeys(keys, "delete_reject_count_", liveIds = emptySet())
        assertEquals(setOf("delete_reject_count_14937"), stale)
    }

    @Test
    fun `unrelated preferences are never touched`() {
        val stale = staleCounterKeys(keys, "upload_reject_count_", liveIds = emptySet())
        assertTrue(stale.none { it.startsWith("calendar_sync_mode") || it.startsWith("conflict_held") })
    }

    @Test
    fun `the bare prefix with no id is not a counter`() {
        // "upload_reject_count_" alone names no event, so it is not ours to delete.
        val stale = staleCounterKeys(keys, "upload_reject_count_", liveIds = emptySet())
        assertTrue("upload_reject_count_" !in stale)
    }

    @Test
    fun `a Proton id keyed family works the same, base64 padding and all`() {
        val stale = staleCounterKeys(keys, "invite_retry_count_", liveIds = setOf("other=="))
        assertEquals(setOf("invite_retry_count_abc=="), stale)
        assertTrue(staleCounterKeys(keys, "invite_retry_count_", liveIds = setOf("abc==")).isEmpty())
    }

    // ---- Every account of this app (owner, 2026-11-23) ----------------------------------------------------------------
    //
    // The three families name no account, and the sweep runs at the start of each account's calendar sync. Until 2.2.0 it
    // asked that account's rows and mappings alone, so with two Proton accounts every sync of one dropped the other's live
    // counters and their three strikes never came. Since 2.2.0 a counter the syncing account does not explain is asked of
    // every other account AccountManager lists. Made-up accounts, row ids and Proton ids only.

    private companion object {
        const val ADAPTER = "src/main/java/me/proton/sync/calendar/CalendarSyncAdapter.kt"
        const val UPLOAD = "upload_reject_count_"
        const val DELETE = "delete_reject_count_"
        const val INVITE = "invite_retry_count_"
        const val A = "stale-counters-a@example.test"
        const val B = "stale-counters-b@example.test"
        const val C = "stale-counters-c@example.test"
        const val EVENT_A = "bWFkZS11cC1ldmVudC1h=="
        const val EVENT_B = "bWFkZS11cC+ldmVudC/i"
        const val EVENT_GONE = "bWFkZS11cC1nb25l-_Q"
    }

    /**
     * This app's accounts on one phone, and the sweep of per-row counters at the start of an account's calendar sync, step
     * by step as CalendarSyncAdapter.sweepStaleRetryCounters runs it (the wiring test below holds the code to each step).
     * The row families: the syncing account's own rows first (existingEventRowIds, as its sync adapter, so its tombstones
     * count), then, for the ids those lack, the rows of every other account AccountManager lists (withOtherAccountsRows,
     * by otherAccountsToAsk and liveInAnyAccount). The invitation family: the syncing account's mappings, then the other
     * accounts' for the ids those lack (mappedInOtherAccounts). Then staleCounterKeys decides. [alone] is 2.1.0's sweep,
     * which asked the syncing account alone.
     */
    private class Phone(val alone: Boolean = false) {
        val prefs = FakePrefs()
        /** The accounts AccountManager lists. */
        val listed = linkedSetOf<String>()
        /** Each account's event rows, tombstones included: the provider numbers the rows of all accounts in one sequence. */
        val rows = HashMap<String, MutableSet<Long>>()
        /** Each account's mappings that name a row, by Proton event id. */
        val mappings = HashMap<String, MutableMap<String, Long>>()
        /** The accounts whose rows the provider could not be read for. */
        val unreadable = HashSet<String>()
        /** Every read the sweep made, in order: "rows <account>" and "mappings <account>". */
        val reads = mutableListOf<String>()

        val counters: Set<String> get() = prefs.values.keys.toSet()

        /** A listed account with these rows. */
        fun add(account: String, vararg rowIds: Long) {
            listed.add(account)
            rows.getOrPut(account) { linkedSetOf() }.addAll(rowIds.toList())
        }

        /** A row of [account] that its mapping names [protonId]. */
        fun map(account: String, protonId: String, rowId: Long) {
            rows.getOrPut(account) { linkedSetOf() }.add(rowId)
            mappings.getOrPut(account) { linkedMapOf() }[protonId] = rowId
        }

        fun put(key: String, strikes: Int = 1) = prefs.edit().putInt(key, strikes).apply()

        /** existingEventRowIds: which of [ids] are [account]'s rows, or null when they could not be read. */
        private fun existingRows(account: String, ids: Set<Long>): Set<Long>? {
            if (ids.isEmpty()) return emptySet()
            reads.add("rows $account")
            if (account in unreadable) return null
            return ids.filterTo(HashSet()) { it in rows[account].orEmpty() }
        }

        /** calendarEventSyncDao.mappedRows: which of [ids] [account] maps to a row. */
        private fun mappedRows(account: String, ids: List<String>): List<String> {
            reads.add("mappings $account")
            return ids.filter { it in mappings[account].orEmpty() }
        }

        /** The sweep at the start of [account]'s calendar sync: the keys it dropped. */
        fun sweep(account: String): Set<String> {
            val allKeys = prefs.all.keys.toSet()
            if (allKeys.none { it.startsWith(UPLOAD) || it.startsWith(DELETE) || it.startsWith(INVITE) }) return emptySet()
            val androidIds = (allKeys.filter { it.startsWith(UPLOAD) }.map { it.removePrefix(UPLOAD) } +
                allKeys.filter { it.startsWith(DELETE) }.map { it.removePrefix(DELETE) })
                .mapNotNull { it.toLongOrNull() }
                .toSet()
            val liveAndroidIds = existingRows(account, androidIds)
                ?.let { if (alone) it else withOtherAccountsRows(account, androidIds, it) }
                ?.mapTo(mutableSetOf()) { it.toString() }
            val countedProtonIds = allKeys.filter { it.startsWith(INVITE) }.map { it.removePrefix(INVITE) }.filter { it.isNotEmpty() }
            val mappedProtonIds = mutableSetOf<String>()
            for (chunk in countedProtonIds.chunked(400)) mappedProtonIds.addAll(mappedRows(account, chunk))
            if (!alone) mappedProtonIds.addAll(mappedInOtherAccounts(account, countedProtonIds - mappedProtonIds))
            val stale = staleCounterKeys(allKeys, UPLOAD, liveAndroidIds) +
                staleCounterKeys(allKeys, DELETE, liveAndroidIds) +
                staleCounterKeys(allKeys, INVITE, mappedProtonIds)
            val edit = prefs.edit()
            for (key in stale) edit.remove(key)
            edit.apply()
            return stale
        }

        private fun withOtherAccountsRows(account: String, androidEventIds: Set<Long>, own: Set<Long>): Set<Long>? {
            val missing = androidEventIds - own
            if (missing.isEmpty()) return own
            val answers = mutableListOf<Set<Long>?>(own)
            for (other in otherAccountsToAsk(account, listed)) answers.add(existingRows(other, missing))
            return liveInAnyAccount(answers)
        }

        private fun mappedInOtherAccounts(account: String, protonIds: List<String>): Set<String> {
            if (protonIds.isEmpty()) return emptySet()
            val mapped = HashSet<String>()
            for (other in otherAccountsToAsk(account, listed)) {
                for (chunk in protonIds.chunked(400)) mapped.addAll(mappedRows(other, chunk))
            }
            return mapped
        }

        /**
         * One more strike on a three-strike counter, as its own account's sync counts one (handleUploadOpRejection,
         * bumpDeleteRejectionStrike, recordInviteRetryStrike): true at the third, which gives up and removes the key.
         */
        fun strike(key: String): Boolean {
            val strikes = prefs.getInt(key, 0) + 1
            if (strikes >= 3) {
                prefs.edit().remove(key).apply()
                return true
            }
            prefs.edit().putInt(key, strikes).apply()
            return false
        }
    }

    @Test
    fun `the other accounts asked are every listed one but the syncing one, each once, in order`() {
        assertEquals(listOf(B, C), otherAccountsToAsk(A, listOf(A, B, C)))
        assertEquals(listOf(B, C), otherAccountsToAsk(A, listOf(B, A, C, B)))
        // One account: nothing more is asked.
        assertEquals(emptyList<String>(), otherAccountsToAsk(A, listOf(A)))
        assertEquals(emptyList<String>(), otherAccountsToAsk(A, emptyList()))
        // The syncing account is asked first by the sweep itself, listed or not (removed while it syncs, say).
        assertEquals(listOf(B), otherAccountsToAsk(A, listOf(B)))
    }

    @Test
    fun `what is live in any account is the union of the answers, and unknown as soon as one could not be read`() {
        assertEquals(setOf(41L, 42L, 43L), liveInAnyAccount(listOf(setOf(41L), setOf(42L, 43L))))
        assertEquals(setOf(41L), liveInAnyAccount(listOf(setOf(41L))))
        assertEquals(setOf(41L), liveInAnyAccount(listOf(setOf(41L), emptySet())))
        assertEquals(emptySet<Long>(), liveInAnyAccount(listOf(emptySet<Long>(), emptySet())))
        assertNull(liveInAnyAccount(listOf(setOf(41L), null)))
        assertNull(liveInAnyAccount(listOf(null, setOf(41L))))
        assertNull(liveInAnyAccount(listOf(setOf("abc=="), null, setOf("def=="))))
        // Unknown sweeps nothing.
        assertTrue(staleCounterKeys(keys, "upload_reject_count_", liveInAnyAccount(listOf(setOf("15211"), null))).isEmpty())
    }

    @Test
    fun `a counter of another account's live row or mapping survives this account's sweep`() {
        // B's sync counted an upload of its row 501 rejected twice, a deletion of its tombstone 502 rejected once and the
        // invitations of its event EVENT_B failed twice; A's own row 101 had an upload rejected once.
        fun phone(alone: Boolean) = Phone(alone).apply {
            add(A, 101)
            add(B, 501, 502)
            map(B, EVENT_B, 503)
            put(UPLOAD + 501, 2)
            put(DELETE + 502, 1)
            put(INVITE + EVENT_B, 2)
            put(UPLOAD + 101, 1)
        }
        val counted = mapOf(UPLOAD + 501 to 2, DELETE + 502 to 1, INVITE + EVENT_B to 2, UPLOAD + 101 to 1)
        // 2.1.0: A's sweep asked A's rows and mappings alone, and took all three of B's.
        assertEquals(setOf(UPLOAD + 501, DELETE + 502, INVITE + EVENT_B), phone(alone = true).sweep(A))
        // Now each is asked of B, which still has it: nothing goes, whichever account syncs.
        val now = phone(alone = false)
        assertEquals(emptySet<String>(), now.sweep(A))
        assertEquals(counted, now.prefs.values)
        assertEquals(listOf("rows $A", "rows $B", "mappings $A", "mappings $B"), now.reads)
        assertEquals(emptySet<String>(), now.sweep(B))
        assertEquals(counted, now.prefs.values)
        // With a third account, listed between them: asked as well, and A's rows explain row 101 to B's sweep.
        val three = Phone()
        three.add(A, 101)
        three.add(C, 301)
        three.add(B, 501)
        three.put(UPLOAD + 101)
        three.put(UPLOAD + 301)
        three.put(UPLOAD + 501)
        assertEquals(emptySet<String>(), three.sweep(B))
        assertEquals(listOf("rows $B", "rows $A", "rows $C"), three.reads)
        assertEquals(setOf(UPLOAD + 101, UPLOAD + 301, UPLOAD + 501), three.counters)
    }

    @Test
    fun `a counter whose row or event is gone in every account is still swept`() {
        val phone = Phone()
        phone.add(A, 101)
        phone.add(B, 501)
        phone.map(B, EVENT_B, 501)
        phone.put(UPLOAD + 999)
        phone.put(DELETE + 998)
        phone.put(INVITE + EVENT_GONE)
        // The rejection count 2.1.0's deletion pass left under a Proton id names no row anywhere.
        phone.put(DELETE + EVENT_GONE)
        phone.put(UPLOAD + 501)
        phone.put(INVITE + EVENT_B)
        assertEquals(setOf(UPLOAD + 999, DELETE + 998, INVITE + EVENT_GONE, DELETE + EVENT_GONE), phone.sweep(A))
        assertEquals(setOf(UPLOAD + 501, INVITE + EVENT_B), phone.counters)
        // Gone from B as well (deleted, and its deletion taken by Proton, say): the next sweep takes them, whoever runs it.
        phone.rows.getValue(B).remove(501L)
        phone.mappings.getValue(B).remove(EVENT_B)
        assertEquals(setOf(UPLOAD + 501, INVITE + EVENT_B), phone.sweep(A))
        assertTrue(phone.counters.isEmpty())
        // The same with B's sweep.
        val other = Phone()
        other.add(A, 101)
        other.add(B, 501)
        other.put(UPLOAD + 999)
        other.put(UPLOAD + 101)
        assertEquals(setOf(UPLOAD + 999), other.sweep(B))
        assertEquals(setOf(UPLOAD + 101), other.counters)
    }

    @Test
    fun `a provider that cannot be read sweeps nothing of the row families, whichever account it fails for`() {
        // This account's own rows, as in 2.1.0: nothing of the row families goes. The invitations are the mappings', which
        // it still reads, also of the other accounts.
        val own = Phone()
        own.add(A, 101)
        own.add(B, 501)
        own.put(UPLOAD + 999)
        own.put(DELETE + 998)
        own.put(INVITE + EVENT_GONE)
        own.unreadable.add(A)
        assertEquals(setOf(INVITE + EVENT_GONE), own.sweep(A))
        assertEquals(setOf(UPLOAD + 999, DELETE + 998), own.counters)
        assertEquals(listOf("rows $A", "mappings $A", "mappings $B"), own.reads)
        // Another account's: a counter A does not explain may be one of B's rows, so nothing of the families goes either,
        // A's own counters with them.
        val other = Phone()
        other.add(A, 101)
        other.add(B, 501)
        other.put(UPLOAD + 999)
        other.put(DELETE + 101)
        other.put(INVITE + EVENT_GONE)
        other.unreadable.add(B)
        assertEquals(setOf(INVITE + EVENT_GONE), other.sweep(A))
        assertEquals(setOf(UPLOAD + 999, DELETE + 101), other.counters)
        // Once B can be read again, the one gone everywhere goes, and A's own stays.
        other.unreadable.clear()
        assertEquals(setOf(UPLOAD + 999), other.sweep(A))
        assertEquals(setOf(DELETE + 101), other.counters)
        // A third account that cannot be read, listed after one that can: still nothing.
        val third = Phone()
        third.add(A, 101)
        third.add(B, 501)
        third.add(C, 301)
        third.put(UPLOAD + 999)
        third.unreadable.add(C)
        assertEquals(emptySet<String>(), third.sweep(A))
        assertEquals(setOf(UPLOAD + 999), third.counters)
        // An account that cannot be read is not even asked while A's own rows explain every counter.
        val explained = Phone()
        explained.add(A, 101, 102)
        explained.add(B, 501)
        explained.put(UPLOAD + 101)
        explained.put(DELETE + 102)
        explained.unreadable.add(B)
        assertEquals(emptySet<String>(), explained.sweep(A))
        assertEquals(listOf("rows $A"), explained.reads)
    }

    @Test
    fun `with one account the sweep is 2_1_0's, decision for decision and read for read`() {
        // Every mix of these counters, with A's rows readable or not, with A listed or not (removed while it syncs): what
        // goes, what stays and what is read are exactly what 2.1.0's sweep did. No other account is ever read.
        val candidates = listOf(
            UPLOAD + 101, UPLOAD + 999, DELETE + 102, DELETE + 998, DELETE + EVENT_GONE,
            INVITE + EVENT_A, INVITE + EVENT_GONE, UPLOAD, "calendar_sync_mode_$A",
        )
        var runs = 0
        for (mask in 0 until (1 shl candidates.size)) {
            val present = candidates.filterIndexed { i, _ -> mask and (1 shl i) != 0 }
            for (readable in listOf(true, false)) {
                for (isListed in listOf(true, false)) {
                    val phones = listOf(Phone(alone = true), Phone(alone = false)).onEach { phone ->
                        phone.add(A, 101, 102)
                        phone.map(A, EVENT_A, 101)
                        if (!isListed) phone.listed.remove(A)
                        if (!readable) phone.unreadable.add(A)
                        for (key in present) phone.put(key, 2)
                    }
                    val (before, now) = phones.map { it.sweep(A) }
                    val case = "$present readable=$readable listed=$isListed"
                    assertEquals(case, before, now)
                    assertEquals(case, phones[0].prefs.values, phones[1].prefs.values)
                    assertEquals(case, phones[0].reads, phones[1].reads)
                    assertTrue(case, phones[1].reads.all { it.endsWith(" $A") })
                    runs++
                }
            }
        }
        assertEquals(2048, runs)
    }

    @Test
    fun `a removed account's counters go at the next sweep of an account that remains, which nothing else does`() {
        val phone = Phone()
        phone.add(A, 101)
        phone.add(B, 501, 502)
        phone.map(B, EVENT_B, 503)
        phone.put(UPLOAD + 501, 2)
        phone.put(DELETE + 502)
        phone.put(INVITE + EVENT_B, 2)
        phone.put(UPLOAD + 101)
        assertEquals(emptySet<String>(), phone.sweep(A))
        // B is removed. The preferences that name it go (ProtonSyncApp.forgetRemovedAccounts), but not these: no account
        // is in their keys, and none of the account-scoped prefixes begins them.
        phone.listed.remove(B)
        assertEquals(emptyMap<String, String>(), SyncPreferences.keysOfRemovedAccounts(phone.counters, setOf(A)))
        for (family in listOf(UPLOAD, DELETE, INVITE)) {
            assertTrue(family, SyncPreferences.ACCOUNT_SCOPED_PREFIXES.none { family.startsWith(it) || it.startsWith(family) })
        }
        // A's next sweep takes them, even while the provider and the app's database still hold B's rows and mappings
        // (Android removes the one and the app the other as the account goes, each in its own time), and never reads B.
        phone.reads.clear()
        assertEquals(setOf(UPLOAD + 501, DELETE + 502, INVITE + EVENT_B), phone.sweep(A))
        assertEquals(setOf(UPLOAD + 101), phone.counters)
        assertEquals(listOf("rows $A", "mappings $A"), phone.reads)
        // As 2.1.0's sweep took them, along with the live ones of an account that remained.
        val before = Phone(alone = true)
        before.add(A, 101)
        before.put(UPLOAD + 501)
        before.put(INVITE + EVENT_B)
        assertEquals(setOf(UPLOAD + 501, INVITE + EVENT_B), before.sweep(A))
        // Nothing in the app's removal of an account names the families; its mappings go there, its rows with Android.
        val app = File("src/main/java/me/proton/sync/ProtonSyncApp.kt").readText()
        for (family in listOf(UPLOAD, DELETE, INVITE)) assertFalse(family, app.contains(family))
        assertTrue(app.contains("syncDatabase.calendarEventSyncDao().deleteByAccountNotIn(activeNames)"))
    }

    @Test
    fun `each account's three strikes come again with two accounts`() {
        // B's sync rejects the upload of its row 501 and the deletion of its tombstone 502 at every sync, and an invitation
        // of its event EVENT_B fails at every sync (as when the event is edited before each), and A syncs between B's
        // syncs; every sync starts with its sweep. In 2.1.0 A's sweep reset all three every time: none ever reached its
        // third, and B sent the upload and the deletion again at every sync, for ever. Now each count reaches its third at
        // B's third sync, as with one account; the invitation counter's third strike only logs and drops the key
        // (StaleEventCounters).
        val retried = listOf(UPLOAD + 501, DELETE + 502, INVITE + EVENT_B)
        for (alone in listOf(true, false)) {
            val phone = Phone(alone)
            phone.add(A, 101)
            phone.add(B, 501, 502)
            phone.map(B, EVENT_B, 503)
            val pending = retried.toMutableSet()
            val gaveUp = linkedMapOf<String, Int>()
            val highest = mutableMapOf<String, Int>()
            for (sync in 1..10) {
                phone.sweep(B)
                for (key in retried.filter { it in pending }) {
                    if (phone.strike(key)) {
                        pending.remove(key)
                        gaveUp[key] = sync
                    } else {
                        highest[key] = maxOf(highest[key] ?: 0, phone.prefs.getInt(key, 0))
                    }
                }
                phone.sweep(A)
            }
            if (alone) {
                assertEquals(emptyMap<String, Int>(), gaveUp)
                assertEquals(retried.associateWith { 1 }, highest)
            } else {
                assertEquals(retried.associateWith { 3 }, gaveUp)
                assertEquals(retried.associateWith { 2 }, highest)
                assertTrue(phone.counters.isEmpty())
            }
        }
    }

    // ---- Where it is wired --------------------------------------------------------------------------------------------

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

    @Test
    fun `the sweep asks this account first, then the other accounts for what it does not explain, and fails closed`() {
        val sweep = squash(code(body("private suspend fun sweepStaleRetryCounters(")))
        // The row families: this account's rows exactly as before, then the others' (null stays null: nothing swept).
        assertTrue(sweep, sweep.contains(
            "val liveAndroidIds = existingEventRowIds(provider, account, androidIds) " +
                "?.let { withOtherAccountsRows(provider, account, androidIds, it) } " +
                "?.mapTo(mutableSetOf()) { it.toString() }"
        ))
        // The invitations: this account's mappings exactly as before, then the others' for the ids those lack; and the
        // decision as before.
        assertTrue(sweep, sweep.contains(
            "for (chunk in countedProtonIds.chunked(400)) { " +
                "calendarEventSyncDao.mappedRows(account.name, chunk).forEach { mappedProtonIds.add(it.protonEventId) } } " +
                "mappedProtonIds.addAll(mappedInOtherAccounts(account, countedProtonIds - mappedProtonIds)) " +
                "val stale = staleCounterKeys(allKeys, uploadPrefix, liveAndroidIds) + " +
                "staleCounterKeys(allKeys, deletePrefix, liveAndroidIds) + " +
                "staleCounterKeys(allKeys, invitePrefix, mappedProtonIds) if (stale.isEmpty()) return"
        ))
        val rows = squash(code(body("private fun withOtherAccountsRows(")))
        assertTrue(rows, rows.contains(
            "): Set<Long>? { val missing = androidEventIds - own if (missing.isEmpty()) return own " +
                "val answers = mutableListOf<Set<Long>?>(own) " +
                "for (other in otherAccounts(account)) answers.add(existingEventRowIds(provider, other, missing)) " +
                "return liveInAnyAccount(answers)"
        ))
        val mapped = squash(code(body("private suspend fun mappedInOtherAccounts(")))
        assertTrue(mapped, mapped.contains(
            "if (protonIds.isEmpty()) return emptySet() val mapped = HashSet<String>() " +
                "for (other in otherAccounts(account)) { for (chunk in protonIds.chunked(400)) { " +
                "calendarEventSyncDao.mappedRows(other.name, chunk).forEach { mapped.add(it.protonEventId) } } } return mapped"
        ))
        val others = squash(code(body("private fun otherAccounts(")))
        assertTrue(others, others.contains(
            "val listed = AccountManager.get(context).getAccountsByType(ProtonSyncApp.ACCOUNT_TYPE).map { it.name } " +
                "return otherAccountsToAsk(account.name, listed).map { Account(it, ProtonSyncApp.ACCOUNT_TYPE) }"
        ))
        // Each account's rows are read as its own sync adapter, so its tombstones count; a failed read is null.
        val existing = squash(code(body("private fun existingEventRowIds(")))
        assertTrue(existing, existing.contains("if (androidEventIds.isEmpty()) return emptySet()"))
        assertTrue(existing, existing.contains("SyncUtils.asCallerSyncAdapter(CalendarContract.Events.CONTENT_URI, account)"))
        assertTrue(existing, existing.contains(") ?: return null"))
        assertTrue(existing, existing.contains(
            "} catch (e: Exception) { SyncLog.w(TAG, \"Could not check which mapped event rows still exist: \${e.message}\") return null }"
        ))
        // Plain functions where they can be, for the ART limits, each called once.
        assertFalse(adapter.contains("suspend fun withOtherAccountsRows("))
        assertFalse(adapter.contains("suspend fun otherAccounts("))
        val all = squash(code(adapter))
        assertEquals(all, 2, count(all, "withOtherAccountsRows("))
        assertEquals(all, 2, count(all, "mappedInOtherAccounts("))
        assertEquals(all, 3, count(all, "otherAccounts("))
        assertEquals(all, 1, count(all, "otherAccountsToAsk("))
        assertEquals(all, 1, count(all, "liveInAnyAccount("))
    }
}

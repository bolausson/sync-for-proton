package me.proton.sync.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The server-to-local deletion pass for one calendar ([planServerDeletions]). An event of a read-only external calendar
 * that Proton re-imported under a new id, and that is on the phone again with its row's content to the letter, loses its
 * old copy without the question: a feed of ten events asked after every refresh, every few hours, and showed each event
 * twice until the answer. Everything else is decided as before, an event held for the question is never deleted before
 * the answer, and events moved from a deleted calendar or holding something of the user's are always asked about.
 */
class ExternalReplacementTest {

    private fun ev(id: String, uid: String?, rid: String? = null, print: String? = "fp-$uid") = EventIdentity(id, uid, rid, print)

    private val old = (1..10).map { ev("old$it", "pickup-$it@feed") }
    private val reimported = (1..10).map { ev("new$it", "pickup-$it@feed") }
    private val none = emptySet<String>()

    private fun plan(
        vanished: List<EventIdentity>,
        current: List<EventIdentity>,
        isExternal: Boolean = true,
        rekeyed: Set<String> = emptySet(),
        held: Set<String> = emptySet(),
        approved: Boolean = false,
        serverCount: Int = current.size,
        mapped: List<EventIdentity> = vanished,
        guarded: Set<String> = emptySet(),
        outsideRange: Set<String> = emptySet(),
        cautious: Boolean = false,
    ) = planServerDeletions(vanished, current, isExternal, rekeyed, held, approved, serverCount, mapped, guarded, outsideRange, cautious)

    private fun ids(range: IntRange, prefix: String) = range.map { "$prefix$it" }.toSet()

    @Test
    fun `a feed re-imported under new ids is replaced and nothing is asked`() {
        // "of 10 mapped, 0 still on the server; server now lists 10".
        assertEquals(DeletionPlan(ids(1..10, "old"), none, none, none, none), plan(old, reimported))
        // Also in a calendar's cautious first pass: the copies are the old events to the letter.
        assertEquals(DeletionPlan(ids(1..10, "old"), none, none, none, none), plan(old, reimported, cautious = true))
    }

    @Test
    fun `the same UID is not enough, a copy whose content differs leaves the old one to the threshold`() {
        val changed = (1..10).map { ev("new$it", "pickup-$it@feed", print = "fp-changed-$it") }
        val p = plan(old, changed)
        assertEquals(none, p.replaced)
        assertEquals(ids(1..10, "old"), p.hold)
        // A feed that gives its events new UIDs on every download still keeps their content.
        val freshUids = (1..10).map { ev("new$it", "random-$it-${it * 7}", print = "fp-pickup-$it@feed") }
        assertEquals(ids(1..10, "old"), plan(old, freshUids).replaced)
    }

    @Test
    fun `a 2_0_4 hold goes only for copies of the same content, the edited and own events stay held`() {
        // Under 2.0.4 a personal 'Waste pickups' held 10 events imported from the council's .ics, 3 of them edited, and
        // 5 of the user's own. It was deleted on Proton and the feed subscribed under the same name; 2.0.4 re-keyed the
        // 15 onto the subscription without a mark and held them, unanswered at the upgrade. The 7 unedited ones are the
        // feed's events to the letter and go; the phone has the only copy of the others, so the question stays.
        val personal = (1..3).map { ev("p$it", "pickup-$it@feed", print = "fp-edited-$it") } +
            (4..10).map { ev("p$it", "pickup-$it@feed") } +
            (11..15).map { ev("p$it", "own-$it@example.org") }
        val p = plan(personal, reimported, held = ids(1..15, "p"), serverCount = 10, mapped = personal + reimported.take(9))
        assertEquals(ids(4..10, "p"), p.replaced)
        assertEquals(ids(1..3, "p") + ids(11..15, "p"), p.hold)
        assertEquals(none, p.deleteNow)
    }

    @Test
    fun `a 2_0_4 re-key that was never held is held in the calendar's cautious first pass`() {
        // The same calendar, but 2.0.4's pass after the re-key was deferred (a failed GET): no mark and no hold. The
        // first complete pass since 2.1.0 holds what is not on the phone again; the later passes find it held.
        val personal = (1..3).map { ev("p$it", "pickup-$it@feed", print = "fp-edited-$it") } +
            (4..10).map { ev("p$it", "pickup-$it@feed") } +
            (11..15).map { ev("p$it", "own-$it@example.org") }
        val p = plan(personal, reimported, serverCount = 10, mapped = personal + reimported.take(9), cautious = true)
        assertEquals(ids(4..10, "p"), p.replaced)
        assertEquals(ids(1..3, "p") + ids(11..15, "p"), p.hold)
        assertEquals(none, p.deleteNow)
        // Rows that hold something of the user's (an edit not on Proton) are asked about in any pass.
        val trusted = plan(personal, reimported, serverCount = 10, mapped = personal + reimported.take(9), guarded = ids(11..15, "p"))
        assertTrue(trusted.hold.containsAll(ids(11..15, "p")))
    }

    @Test
    fun `an approved question leaves the cautious pass to the next one, which still holds`() {
        // The 2.0.4 re-key of the case above, but the account's question (for another calendar) is approved when this
        // calendar's first complete pass runs: nothing is held under an approval the user gave for other events, the
        // rest waits, and the calendar stays cautious, so the next pass holds it instead of judging it by the threshold.
        val personal = (11..16).map { ev("p$it", "own-$it@example.org") }
        val approvedPass = plan(personal, reimported, approved = true, serverCount = 30, mapped = personal, cautious = true)
        assertEquals(ids(11..16, "p"), approvedPass.deferred)
        assertEquals(none, approvedPass.hold)
        assertTrue(!endsCautiousPass(isExternal = true, deferredForFailures = false, plan = approvedPass))
        val next = plan(personal, reimported, serverCount = 30, mapped = personal, cautious = true)
        assertEquals(ids(11..16, "p"), next.hold)
        assertEquals(none, next.deleteNow)
        assertTrue(endsCautiousPass(isExternal = true, deferredForFailures = false, plan = next))
        // Without the cautious pass, 6 of 30 is under the threshold: the user's events would go unasked.
        assertEquals(ids(11..16, "p"), plan(personal, reimported, serverCount = 30, mapped = personal).deleteNow)
    }

    @Test
    fun `the cautious pass ends only with a pass that judged every vanished event`() {
        assertTrue(endsCautiousPass(isExternal = true, deferredForFailures = false, plan = null))
        assertTrue(!endsCautiousPass(isExternal = true, deferredForFailures = true, plan = null))
        assertTrue(!endsCautiousPass(isExternal = false, deferredForFailures = false, plan = null))
        val held = DeletionPlan(none, none, none, setOf("a"), none)
        assertTrue(endsCautiousPass(isExternal = true, deferredForFailures = false, plan = held))
        val deferred = DeletionPlan(none, none, none, none, setOf("a"))
        assertTrue(!endsCautiousPass(isExternal = true, deferredForFailures = false, plan = deferred))
    }

    @Test
    fun `a row holding something of the user's is never replaced nor removed unasked`() {
        // Its row is DIRTY, differs from its last synced content (a refused edit), could not be read, or is the user's
        // own event: guarded, whatever the copies look like.
        val p = plan(old, reimported, guarded = ids(1..3, "old"))
        assertEquals(ids(4..10, "old"), p.replaced)
        assertEquals(ids(1..3, "old"), p.hold)
        assertEquals(none, p.deleteNow)
        val outside = plan(old, reimported.take(5), guarded = ids(8..10, "old"), outsideRange = ids(6..10, "old"))
        assertEquals(ids(6..7, "old"), outside.outOfRange)
        assertEquals(ids(8..10, "old"), outside.hold)
    }

    @Test
    fun `a rolling feed that drops a past event at each refresh is never asked about`() {
        // Nine pickups come back under new ids, one past pickup is gone and a new one appeared.
        val current = (1..9).map { ev("new$it", "pickup-$it@feed") } + ev("new11", "pickup-11@feed")
        val p = plan(old, current)
        assertEquals(ids(1..9, "old"), p.replaced)
        assertEquals(setOf("old10"), p.deleteNow)
        assertEquals(none, p.hold)
        // Except in the calendar's cautious first pass, which asks once.
        assertEquals(setOf("old10"), plan(old, current, cautious = true).hold)
    }

    @Test
    fun `a real shrink is held, and stays held on the next sync, also when the calendar grew`() {
        // 20 events, 12 dropped, the 8 left keep their ids.
        val survivors = (1..8).map { ev("e$it", "match-$it") }
        val dropped = (9..20).map { ev("e$it", "match-$it") }
        val sync1 = plan(dropped, survivors, mapped = survivors + dropped)
        assertEquals(ids(9..20, "e"), sync1.hold)
        // Sync 2, nothing answered: the same twelve are held again.
        val sync2 = plan(dropped, survivors, held = sync1.hold, mapped = survivors + dropped)
        assertEquals(ids(9..20, "e"), sync2.hold)
        assertEquals(none, sync2.deleteNow)
        // The calendar grew to 40 in between, so twelve of them no longer look suspicious: still held, never deleted.
        val grown = survivors + (21..40).map { ev("e$it", "match-$it") }
        val sync3 = plan(dropped, grown, held = sync1.hold, mapped = grown + dropped)
        assertEquals(ids(9..20, "e"), sync3.hold)
        assertEquals(none, sync3.deleteNow)
    }

    @Test
    fun `a mixed re-import is judged on the calendar's size before the download, like a feed that keeps its ids`() {
        // 30 events; Proton re-imports 20 under new ids and the feed dropped 10. Ten of thirty is no suspicious
        // shrink (2.0.4 deleted them at once for a feed that keeps its ids), and nothing is left held for a later
        // sync to act on before the answer.
        val olds = (1..30).map { ev("o$it", "m-$it") }
        val news = (1..20).map { ev("n$it", "m-$it") }
        val p = plan(olds, news)
        assertEquals(ids(1..20, "o"), p.replaced)
        assertEquals(ids(21..30, "o"), p.deleteNow)
        assertEquals(none, p.hold)
    }

    @Test
    fun `after deferred passes each event is counted once, over any number of re-imports`() {
        // 20 events; Proton re-imported them and the feed dropped 10. The first sync's fetch failed half way, so its
        // deletion pass was deferred after it had downloaded 9 of the new copies. The next sync judges the 10 dropped
        // ones as a clean sync would (10 of 20: held), not against 29 mapped events (deleted unasked).
        val olds = (1..20).map { ev("o$it", "m-$it") }
        val news = (1..10).map { ev("n$it", "m-$it") }
        val once = plan(olds, news, serverCount = 10, mapped = olds + news.take(9))
        assertEquals(ids(1..10, "o"), once.replaced)
        assertEquals(ids(11..20, "o"), once.hold)
        assertEquals(ids(11..20, "o"), plan(olds, news, serverCount = 10).hold)
        // Proton re-imported once more before that sync: both generations vanished, a third came down.
        val third = (1..10).map { ev("t$it", "m-$it") }
        val twice = plan(olds + news.take(9), third, serverCount = 10, mapped = olds + news.take(9))
        assertEquals(ids(1..10, "o") + ids(1..9, "n"), twice.replaced)
        assertEquals(ids(11..20, "o"), twice.hold)
    }

    @Test
    fun `events re-keyed from a deleted calendar are never replaced, even when they share the feed's content`() {
        // A personal 'Waste pickups' held 10 events imported from the council's .ics plus 5 of the user's own. It was
        // deleted on Proton and the same feed subscribed under the same name: the 15 are re-keyed onto the
        // subscription. Ten are the feed's events to the letter, but they are the user's and the last copy: all 15 are
        // asked about.
        val personal = (1..10).map { ev("p$it", "pickup-$it@feed") } + (11..15).map { ev("p$it", "own-$it@example.org") }
        val p = plan(personal, reimported, rekeyed = ids(1..15, "p"))
        assertEquals(none, p.replaced)
        assertEquals(ids(1..15, "p"), p.hold)
    }

    @Test
    fun `re-keyed events are always asked about, also below the threshold and beside a bigger calendar`() {
        // The feed was subscribed while the personal calendar still existed, which was deleted later: its 15 re-keyed
        // events are judged beside the feed's 40. Fifteen of 55 is no suspicious shrink, but a calendar deleted on
        // Proton is always asked about, and these are its events.
        val personal = (1..15).map { ev("p$it", "own-$it@example.org") }
        val feed = (1..40).map { ev("f$it", "feed-$it@feed") }
        val p = plan(personal, feed, rekeyed = ids(1..15, "p"), serverCount = 40, mapped = personal + feed)
        assertEquals(ids(1..15, "p"), p.hold)
        assertEquals(none, p.deleteNow)
        // Six, under the threshold of ten.
        val few = plan(personal.take(6), feed.take(2), rekeyed = ids(1..6, "p"), serverCount = 2, mapped = personal.take(6) + feed.take(2))
        assertEquals(ids(1..6, "p"), few.hold)
        // On a personal calendar too.
        val own = plan(personal.take(6), emptyList(), isExternal = false, rekeyed = ids(1..6, "p"), serverCount = 3,
            mapped = personal.take(9))
        assertEquals(ids(1..6, "p"), own.hold)
    }

    @Test
    fun `outside a fetched range, a re-imported feed's old copies go without the question once one inside was replaced`() {
        // A custom range and a feed of 120 events: the windowed fetch downloads the 50 new copies inside the range,
        // never the 70 new copies of past events, so those old copies match nothing.
        val inside = (1..50).map { ev("w$it", "w-$it@feed") }
        val past = (1..70).map { ev("x$it", "x-$it@feed") }
        val newInside = (1..50).map { ev("nw$it", "w-$it@feed") }
        val p = plan(inside + past, newInside, serverCount = 120, outsideRange = ids(1..70, "x"))
        assertEquals(DeletionPlan(ids(1..50, "w"), ids(1..70, "x"), none, none, none), p)
    }

    @Test
    fun `outside the range nothing goes unasked without a replacement, in the cautious pass, or when held or re-keyed`() {
        val inside = (1..50).map { ev("w$it", "w-$it@feed") }
        val past = (1..70).map { ev("x$it", "x-$it@feed") }
        val newInside = (1..50).map { ev("nw$it", "w-$it@feed") }
        // Nothing inside the range was replaced, so nothing shows a re-import: the 70 are judged (70 of 120, held).
        val unrelated = (1..50).map { ev("k$it", "unrelated-$it@feed") }
        val noReimport = plan(past, unrelated, serverCount = 120, mapped = past + unrelated, outsideRange = ids(1..70, "x"))
        assertEquals(none, noReimport.outOfRange)
        assertEquals(ids(1..70, "x"), noReimport.hold)
        // The cautious first pass asks instead.
        val cautious = plan(inside + past, newInside, serverCount = 120, outsideRange = ids(1..70, "x"), cautious = true)
        assertEquals(none, cautious.outOfRange)
        assertEquals(ids(1..70, "x"), cautious.hold)
        // Held and re-keyed events outside the range are judged as everywhere else.
        val guarded = plan(
            inside + past, newInside, held = ids(1..3, "x"), rekeyed = ids(4..5, "x"), serverCount = 120,
            outsideRange = ids(1..70, "x")
        )
        assertEquals(ids(6..70, "x"), guarded.outOfRange)
        assertEquals(ids(1..5, "x"), guarded.hold)
        // A personal calendar never.
        val personal = plan(inside + past, newInside, isExternal = false, serverCount = 120, outsideRange = ids(1..70, "x"))
        assertEquals(none, personal.outOfRange)
    }

    @Test
    fun `a personal calendar is never replaced, and its base is its plain count`() {
        val p = plan(old, reimported, isExternal = false)
        assertEquals(none, p.replaced)
        assertEquals(ids(1..10, "old"), p.hold)
        // Ten of 30 identical events: an external calendar would count them as one event, a personal one counts 30.
        val same = (1..30).map { ev("s$it", null, print = "fp-same") }
        assertEquals(ids(1..10, "s"), plan(same.take(10), emptyList(), isExternal = false, serverCount = 20, mapped = same).deleteNow)
    }

    @Test
    fun `an approved question leaves the held events to the confirmed pass and acts on nothing in this run`() {
        val p = plan(old, emptyList(), held = ids(1..10, "old"), approved = true, serverCount = 0)
        assertEquals(DeletionPlan(none, none, none, none, ids(1..10, "old")), p)
    }

    @Test
    fun `a feed Proton lists as empty matches nothing and is asked about`() {
        val p = plan(old, emptyList(), serverCount = 0, outsideRange = ids(1..10, "old"))
        assertEquals(none, p.replaced)
        assertEquals(none, p.outOfRange)
        assertEquals(ids(1..10, "old"), p.hold)
    }

    @Test
    fun `an override is replaced by its own copy, and a cancelled one without a copy is not`() {
        val oldMaster = ev("m-old", "series@feed", print = "fp-master")
        val oldOverride = ev("o-old", "series@feed", rid = "RECURRENCE-ID:20261120T090000Z", print = "fp-moved")
        val oldCancelled = ev("c-old", "series@feed", rid = "RECURRENCE-ID:20261127T090000Z", print = "fp-cancelled")
        val current = listOf(
            ev("m-new", "series@feed", print = "fp-master"),
            ev("o-new", "series@feed", rid = "RECURRENCE-ID:20261120T090000Z", print = "fp-moved"),
        )
        assertEquals(setOf("m-old", "o-old"), replacedByNewCopies(listOf(oldMaster, oldOverride, oldCancelled), current))
    }

    @Test
    fun `missing or blank content never matches, and an id still listed is not replaced by itself`() {
        val anonymous = listOf(ev("a1", null, print = null), ev("a2", "", print = ""))
        val alsoAnonymous = listOf(ev("b1", null, print = null), ev("b2", "", print = ""))
        assertEquals(none, replacedByNewCopies(anonymous, alsoAnonymous))
        assertEquals(none, replacedByNewCopies(listOf(old[0]), listOf(old[0])))
    }

    @Test
    fun `distinct events join by UID and RECURRENCE-ID or by content`() {
        assertEquals(10, distinctEvents(old + reimported))
        // A master and its override share the UID but not the RECURRENCE-ID.
        assertEquals(2, distinctEvents(listOf(ev("m", "s@x", print = "a"), ev("o", "s@x", rid = "R1", print = "b"))))
        // Same content under new UIDs, and a chain: a~b by UID, b~c by content.
        assertEquals(1, distinctEvents(listOf(ev("a", "u1", print = "p1"), ev("b", "u1", print = "p2"), ev("c", "u3", print = "p2"))))
        assertEquals(2, distinctEvents(listOf(ev("a", null, print = null), ev("b", null, print = null))))
    }

    @Test
    fun `guarded rows are unreadable, missing, DIRTY, or changed since the last sync`() {
        val baselines = mapOf("clean" to "fp1", "nobase" to null, "dirty" to "fp3", "changed" to "fp4", "gone" to "fp6")
        val rows = mapOf(
            "clean" to VanishedRow("fp1", false, null, null),
            "nobase" to VanishedRow("fpX", false, null, null),
            "dirty" to VanishedRow("fp3", true, null, null),
            "changed" to VanishedRow("fp4-edited", false, null, null),
        )
        assertEquals(setOf("dirty", "changed", "gone"), guardedVanished(baselines, rows))
        assertEquals(baselines.keys, guardedVanished(baselines, null))
    }

    @Test
    fun `outside the range is wholly before its start or after its end`() {
        val range = 1_000L..2_000L
        val rows = mapOf(
            "ended" to VanishedRow("a", false, 100_000, 1_000_000),
            "touches" to VanishedRow("b", false, 100_000, 1_000_001),
            "endless" to VanishedRow("c", false, 100_000, null),
            "later" to VanishedRow("d", false, 2_000_000, 2_000_000),
            "inside" to VanishedRow("e", false, 1_500_000, 1_600_000),
        )
        assertEquals(setOf("ended", "later"), outsideRangeOf(rows, range))
        assertEquals(none, outsideRangeOf(rows, null))
        assertEquals(none, outsideRangeOf(null, range))
    }

    @Test
    fun `the five parts never overlap and add up to the vanished events`() {
        val cases = listOf(
            plan(old, reimported),
            plan(old, reimported.take(9)),
            plan(old, emptyList(), serverCount = 0),
            plan(old, emptyList(), held = ids(1..10, "old"), approved = true, serverCount = 0),
            plan(old, reimported.take(3), held = ids(4..6, "old"), mapped = old + reimported),
            plan(
                old, reimported.take(3), rekeyed = ids(1..2, "old"), held = ids(9..10, "old"),
                outsideRange = ids(5..10, "old"), mapped = old + reimported
            ),
            plan(old, reimported.take(5), mapped = old + reimported.take(5), outsideRange = ids(6..8, "old"), guarded = ids(7..7, "old")),
            plan(old, reimported.take(5), outsideRange = ids(6..8, "old"), cautious = true),
        )
        for (p in cases) {
            val parts = listOf(p.replaced, p.outOfRange, p.deleteNow, p.hold, p.deferred)
            assertEquals(ids(1..10, "old"), parts.flatten().toSet())
            assertEquals(10, parts.sumOf { it.size })
        }
    }

    private val src = File("src/main/java/me/proton/sync/calendar/CalendarSyncAdapter.kt").readText().replace("\r\n", "\n")

    private fun body(start: String) = src.substringAfter(start).substringBefore("\n    }\n")

    @Test
    fun `the deletion pass runs the plan with the right inputs and acts on every part`() {
        val pass = body("private suspend fun applyServerDeletions(")
        for (line in listOf(
            "val trustedKey = SyncPreferences.replacementTrustedKey(account.name, protonCalendarId)",
            "val plan = planDeletions(",
            "deleteVanishedEvents(account, provider, syncResult, androidCalId, evToDelete.filterKeys { it in plan.replaced })",
            "RemoteDeletionGuard.forgetPending(context, gType, account.name, plan.replaced)",
            "deleteVanishedEvents(account, provider, syncResult, androidCalId, evToDelete.filterKeys { it in plan.outOfRange })",
            "RemoteDeletionGuard.addPending(context, gType, account.name, plan.hold)",
            "deleteVanishedEvents(account, provider, syncResult, androidCalId, evToDelete.filterKeys { it in plan.deleteNow })",
            // The cautious first pass ends only with a pass that judged everything (endsCautiousPass).
            ").also { judgedPlan = it }",
            "if (endsCautiousPass(isExternalCalendar, evToDelete.isNotEmpty() && fetchHadFailures, judgedPlan)) {",
            ".putBoolean(SyncPreferences.replacementTrustedKey(account.name, protonCalendarId), true).apply()",
        )) assertTrue(line, pass.contains(line))
        // No other deletion and no other threshold in the pass: the plan decides everything.
        assertEquals(pass, 3, Regex("deleteVanishedEvents\\(").findAll(pass).count())
        assertTrue(pass, !pass.contains("isSuspicious("))
        val inputs = body("private suspend fun planDeletions(")
        for (line in listOf(
            "val rows = if (isExternalCalendar) readVanishedRows(provider, account, androidCalId, evToDelete) else emptyMap()",
            "if (isExternalCalendar) m.identity().copy(fingerprint = rows?.get(m.protonEventId)?.fingerprint) else m.identity()",
            "current = if (isExternalCalendar) currentOnPhone(account, protonCalendarId, serverEventIds) else emptyList(),",
            "rekeyed = RemoteDeletionGuard.rekeyedIds(context, account.name),",
            "held = RemoteDeletionGuard.pendingIds(context, gType, account.name),",
            "approved = RemoteDeletionGuard.isApproved(context, gType, account.name),",
            "serverCount = serverEventIds.size,",
            "mapped = existingEventMappings.values.map { it.identity() },",
            "guarded = if (isExternalCalendar) guardedVanished(evToDelete.mapValues { it.value.sharedFingerprint }, rows) else emptySet(),",
            "outsideRange = if (isExternalCalendar) outsideRangeOf(rows, fetchedRange) else emptySet(),",
            "cautious = cautious",
        )) assertTrue(line, inputs.contains(line))
        // The range goes to the pass only from the windowed fetch, the one that leaves events outside it unfetched.
        assertTrue(src.contains(
            "val fetchedRange = if (syncMode == SyncPreferences.SYNC_MODE_ALL || totalEventCount in 0..sparseThreshold) null else syncStart..syncEnd"
        ))
        assertTrue(src.contains("val fetch = if (fetchedRange == null) {\n            fetchEventsFlat("))
        // The rows are read as the phone holds them now, in their calendar.
        val read = body("private fun readVanishedRows(")
        assertTrue(read, read.contains("EventConflictGuard.fingerprintOf(values)"))
        assertTrue(read, read.contains("\"\${CalendarContract.Events.CALENDAR_ID} = ? AND \" +"))
        val current = src.substringAfter("private suspend fun currentOnPhone(").substringBefore("\n\n")
        assertTrue(current, current.contains(".filter { it.androidEventId != null && it.protonEventId in serverEventIds }"))
    }

    @Test
    fun `every re-key marks the moved events before it moves them, and the reuse is 2_0_4's`() {
        val rekey = src.substringAfter("for (old in supersededMappings) {").substringBefore("calendarSyncDao.deleteByProtonId")
        assertTrue(rekey, rekey.indexOf("RemoteDeletionGuard.markRekeyed(") in 0 until rekey.indexOf("reassignCalendar("))
        assertTrue(rekey, !rekey.contains("if ("))
        assertTrue(src.contains("val orphanedCalId = existingLocalCalendars[displayName]?.firstOrNull()\n"))
        assertTrue(!src.contains("takenOverWritable"))
    }

    @Test
    fun `a calendar that still holds events is never deleted as a duplicate`() {
        val dedupe = body("private fun readAndDedupeLocalCalendars(")
        assertTrue(dedupe, dedupe.contains("val rows = calendarRowCount(provider, account, dupId)"))
        assertTrue(dedupe, dedupe.contains("rows == 0"))
        val count = src.substringAfter("private fun calendarRowCount(").substringBefore("\n\n")
        // Rows that hold something: not deleted rows, which an unmapped calendar can never send, nor the download's
        // own cancelled-date placeholders.
        assertTrue(count, count.contains("\"\${CalendarContract.Events.CALENDAR_ID} = ? AND \${CalendarContract.Events.DELETED} = 0 AND (\" +"))
        assertTrue(count, count.contains("\"\${CalendarContract.Events.STATUS} IS NULL OR \${CalendarContract.Events.STATUS} != \$cancelled OR \" +"))
        assertTrue(count, count.contains("\"\${CalendarContract.Events.DIRTY} = 1)\","))
    }

    @Test
    fun `no local deletion goes to Proton while a removal of older events is unfinished, and recovery comes first`() {
        val reconcile = body("private suspend fun reconcileDeletedEvents(")
        val guard = reconcile.indexOf("if (RangeTrimRunner.removalUnfinished(context, account.name)) {")
        assertTrue(reconcile, guard >= 0 && guard < reconcile.indexOf("apiClient.syncCalendarEvents("))
        val sync = body("private suspend fun performCalendarSync(")
        val recover = sync.indexOf("RangeTrimRunner.recover(context, provider, account, calendarEventSyncDao)")
        assertTrue(sync, recover >= 0 && recover < sync.indexOf("applyBulkDeletionRestore("))
        assertEquals(1, Regex("RangeTrimRunner\\.recover\\(").findAll(src).count())
    }

    @Test
    fun `every calendar's type is noted before the skip of external calendars, for the removal dialog`() {
        val sync = body("private suspend fun performCalendarSync(")
        val note = sync.indexOf("RangeTrimRunner.noteCalendarType(prefs, account.name, calInfo.id, calInfo.type != 0)")
        val skip = sync.indexOf("if (!includeExternal && calInfo.type != 0) {")
        assertTrue(sync, note in 0 until skip)
        assertTrue(sync, sync.substring(sync.lastIndexOf("for (calInfo in calendars) {", note), note).count { it == '{' } == 1)
    }

    @Test
    fun `a calendar's removal choice goes with its mapping, never while its events are only held`() {
        val gone = body("private suspend fun removeCalendarsGoneFromServer(")
        val drops = Regex("calendarSyncDao\\.deleteByProtonId\\(account\\.name, protonId\\)").findAll(gone).count()
        assertEquals(gone, 3, drops)
        val followed = Regex("calendarSyncDao\\.deleteByProtonId\\(account\\.name, protonId\\)\\n\\s*forgetTrimChoice\\(\\)\\n").findAll(gone).count()
        assertEquals(gone, drops, followed)
        assertEquals(gone, 1 + drops, Regex("forgetTrimChoice\\(\\)").findAll(gone).count())
        val hold = gone.substringAfter("if (nonKept.isNotEmpty() && !approvedHere) {").substringBefore("continue")
        assertTrue(hold, hold.contains("RemoteDeletionGuard.addPending(") && !hold.contains("forgetTrimChoice"))
    }

    @Test
    fun `rows go only from their own calendar, never while another mapping names them, and with their placeholders`() {
        val delete = body("private suspend fun deleteVanishedEvents(")
        assertTrue(delete, delete.contains("calendarEventSyncDao.protonIdsOfRow(account.name, evId).filter { it !in events }"))
        assertTrue(delete, delete.contains("deleteEventRow(provider, account, evId, androidCalId)"))
        assertTrue(delete, delete.contains("purgeAfterDelete(provider, account, androidCalId, protonId)"))
        val confirmed = body("private suspend fun applyConfirmedRemoteDeletions(")
        assertTrue(confirmed, confirmed.contains("calendarEventSyncDao.protonIdsOfRow(account.name, evId).any { it !in confirmed }"))
        assertTrue(confirmed, confirmed.contains("deleteEventRow(provider, account, evId, androidCalId)"))
        assertTrue(confirmed, confirmed.contains("purgeAfterDelete(provider, account, androidCalId, protonId)"))
        // Never through Events/<id>, which ignores the account.
        for (b in listOf(delete, confirmed)) assertTrue(b, !b.contains("ContentUris.withAppendedId("))
        val row = src.substringAfter("private fun deleteEventRow(").substringBefore("\n\n")
        assertTrue(row, row.contains("\"\${CalendarContract.Events._ID} = ? AND \${CalendarContract.Events.CALENDAR_ID} = ?\""))
        // The placeholders: cancelled rows this app wrote (DIRTY = 0) that have no Proton id of their own.
        val purge = src.substringAfter("private fun purgeExdatePlaceholders(").substringBefore("\n\n")
        for (clause in listOf(
            "\${CalendarContract.Events.CALENDAR_ID} = ? AND \${CalendarContract.Events.ORIGINAL_SYNC_ID} = ? AND ",
            "\${CalendarContract.Events.STATUS} = ? AND \${CalendarContract.Events.DIRTY} = 0 AND ",
            "\${CalendarContract.Events._SYNC_ID} IS NULL",
        )) assertTrue(clause, purge.contains(clause))
        // And the re-keyed marks of events gone from the phone are swept at every sync.
        assertTrue(src.contains("        sweepRekeyedMarks(account)\n"))
    }

    @Test
    fun `the churn fixture holds the sync lock, stays in its account and calendar, and leaves the guard's events alone`() {
        val fixture = File("src/main/java/me/proton/sync/calendar/ChurnFixture.kt").readText().replace("\r\n", "\n")
        assertTrue(fixture.contains("SyncUtils.withSyncLock(\"calendar\", account.name, TAG) {"))
        assertTrue(src.contains("SyncUtils.withSyncLock(\"calendar\", account.name, TAG) {"))
        assertTrue(!fixture.contains("isSyncPending"))
        assertTrue(!fixture.contains("ContentUris"))
        assertTrue(fixture.contains("arrayOf(androidCalId.toString(), account.name, account.type), null"))
        for (set in listOf("RemoteDeletionGuard.keptIds(", "RemoteDeletionGuard.pendingIds(", "RemoteDeletionGuard.rekeyedIds(")) {
            assertTrue(set, fixture.contains(set))
        }
        assertTrue(fixture.contains(".partition { it.protonEventId in tracked }"))
        // The mapping is renamed before the row.
        val loop = fixture.substringAfter("for (event in events) {")
        assertTrue(loop, loop.indexOf("eventDao.renameEvent(") in 0 until loop.indexOf("CalendarContract.Events._SYNC_ID, madeUp"))
    }
}

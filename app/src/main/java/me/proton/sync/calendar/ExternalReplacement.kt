package me.proton.sync.calendar

import me.proton.sync.RemoteDeletionGuard

/**
 * One event as the deletion pass compares it: its Proton id, its iCal UID and RECURRENCE-ID, and the fingerprint of its
 * shared content (EventConflictGuard: title, description, location, times, recurrence, status, availability). For an
 * event gone from Proton the fingerprint is the row's as it is on the phone now; for every other one it is the baseline
 * the last sync stored.
 */
internal data class EventIdentity(val protonId: String, val uid: String?, val recurrenceId: String?, val fingerprint: String?)

/**
 * The ids of the [vanished] events of a read-only external calendar (subscribed or holiday) that are on the phone again,
 * to the letter, under a new id, so their old copies go without the deletion question. Pure.
 *
 * Proton re-imports some subscribed feeds under new event ids every time it refreshes them, every few hours. A log
 * showed it as "Held 10 server-side event deletion(s) ... (of 10 mapped, 0 still on the server; server now lists 10)"
 * for a feed of ten events. To this app every event of such a calendar vanishes and a new one appears. A calendar of
 * ten or more events passed RemoteDeletionGuard's threshold, so the user was asked after every refresh whether to
 * delete events that were in fact still there, and until the answer the calendar showed every event twice, because
 * the new copies had already been downloaded.
 *
 * An old copy counts as replaced only when one of [current] (the events Proton lists now that have a row on the phone,
 * after this run's download) has exactly the shared content its row has now. The same UID is not enough: a copy that
 * differs is not the same event to the user, and the row may hold something of theirs.
 *
 * Known limit, on the safe side: the time columns are the phone's own computation, so when a re-import coincides with
 * a change of the rules that computation uses (a tz data update, "Update time zones online" switched, the phone's zone
 * for floating times), the timed events whose times moved match nothing and are judged as plain deletions: that
 * refresh asks once when they reach RemoteDeletionGuard's threshold, and below it their old copies go as a normal small
 * shrink would, their new copies already on the phone. Columns a download UPDATE leaves when Proton drops a field (the
 * parked field clearing) can keep an updated-in-place copy from matching its next re-import in the same way.
 */
internal fun replacedByNewCopies(vanished: Collection<EventIdentity>, current: Collection<EventIdentity>): Set<String> {
    val currentIds = current.mapTo(HashSet()) { it.protonId }
    val byContent = current.mapNotNullTo(HashSet()) { e -> e.fingerprint?.takeIf { it.isNotBlank() } }
    return vanished.filter { v -> v.protonId !in currentIds && !v.fingerprint.isNullOrBlank() && v.fingerprint in byContent }
        .mapTo(HashSet()) { it.protonId }
}

/**
 * How many different events [mapped] holds: identities with the same UID and RECURRENCE-ID, or the same content, count
 * once. A pass that was deferred (a failed GET) leaves an old copy and a new one of the same event mapped side by side,
 * one generation per deferred refresh.
 */
internal fun distinctEvents(mapped: Collection<EventIdentity>): Int {
    val list = mapped.toList()
    val parent = IntArray(list.size) { it }
    fun find(x: Int): Int {
        var r = x
        while (parent[r] != r) r = parent[r]
        var c = x
        while (parent[c] != r) { val n = parent[c]; parent[c] = r; c = n }
        return r
    }
    val byKey = HashMap<String, Int>()
    for ((i, e) in list.withIndex()) {
        val keys = listOfNotNull(
            e.uid?.takeIf { it.isNotBlank() }?.let { "u:$it|${e.recurrenceId.orEmpty()}" },
            e.fingerprint?.takeIf { it.isNotBlank() }?.let { "f:$it" },
        )
        for (k in keys) {
            val j = byKey.putIfAbsent(k, i)
            if (j != null) { val a = find(i); val b = find(j); if (a != b) parent[a] = b }
        }
    }
    return list.indices.count { find(it) == it }
}

/** The row of an event gone from Proton as the phone holds it now: its shared content, DIRTY, DTSTART and LAST_DATE. */
internal class VanishedRow(val fingerprint: String, val dirty: Boolean, val dtStart: Long?, val lastDate: Long?)

/**
 * The events gone from Proton whose rows must never leave without the question ([planServerDeletions]'s guarded): a row
 * that could not be read ([rows] null, or no entry), a DIRTY row, and a row whose shared content is no longer its last
 * synced [baselines] (an edit not on Proton, a refused upload kept on the phone). Proton's IsOrganizer tells nothing
 * here: on a test phone every event of two subscribed calendars carried 1, and every event of the owner's own calendar 0.
 */
internal fun guardedVanished(baselines: Map<String, String?>, rows: Map<String, VanishedRow>?): Set<String> =
    baselines.keys.filterTo(HashSet()) { id ->
        val r = rows?.get(id)
        val baseline = baselines[id]
        r == null || r.dirty || (baseline != null && r.fingerprint != baseline)
    }

/**
 * Of [rows], the ones that lie wholly outside [range] (epoch seconds; null for a run that fetched everything): the
 * event, or its series' last occurrence, ends before the range starts (LAST_DATE, which the provider computes from the
 * recurrence and leaves null for a series without an end), or it starts after the range ends.
 */
internal fun outsideRangeOf(rows: Map<String, VanishedRow>?, range: LongRange?): Set<String> {
    if (rows == null || range == null) return emptySet()
    val start = range.first * 1000
    val end = range.last * 1000
    return rows.filterTo(HashMap()) { (_, r) -> (r.lastDate != null && r.lastDate <= start) || (r.dtStart != null && r.dtStart >= end) }.keys
}

/**
 * What one calendar's server-to-local deletion pass does with the events gone from Proton. [replaced] go now and leave
 * the held set; [outOfRange] go now; both without the question. [deleteNow] go now; [hold] are held for the question;
 * [deferred] are not acted on in this run: the post-parallel pass of an approved question deletes the held ones among
 * them, and the others are judged again at the next sync. The five never overlap and together are exactly the vanished
 * events.
 */
internal data class DeletionPlan(
    val replaced: Set<String>,
    val outOfRange: Set<String>,
    val deleteNow: Set<String>,
    val hold: Set<String>,
    val deferred: Set<String>,
)

/**
 * The deletion pass's decision for one calendar, pure so every case is tested without a phone.
 *
 * [vanished] are the events mapped before this run's download that Proton no longer lists, with a row on the phone and
 * not kept by the user, each with its row's content as it is now; [mapped] are all the calendar's mappings before the
 * download; [current] the events Proton lists that have a row after the download. Never removed without the question:
 *  - [rekeyed] events, moved onto this calendar from one deleted on Proton under the same name: the user's own, now
 *    gone from Proton, asked about as the events of a calendar deleted under another name are;
 *  - [guarded] events: a row with a change not on Proton (DIRTY, or content other than the last synced baseline), or a
 *    row that could not be read ([guardedVanished]);
 *  - an event already [held] for the question, also when the calendar grew between two syncs so that the same shrink no
 *    longer looks suspicious (it may still be replaced, when its content is the new copy's to the letter).
 * For a read-only external calendar ([isExternal]) the other vanished events go at once when they are on the phone
 * again ([replacedByNewCopies]). A run that fetched only the sync range never fetches the new copies Proton made
 * outside it, so once the run replaced at least one event, a vanished event whose row lies wholly outside the range
 * ([outsideRange]) goes as well: nothing in it is the user's, and the range says the phone need not have it.
 * The rest is judged as in 2.0.4, by RemoteDeletionGuard's threshold against Proton's count ([serverCount]) and the
 * calendar's mapped events, for an external calendar each counted once ([distinctEvents]).
 * [cautious] is an external calendar's first complete pass in 2.1.0 or later: 2.0.4 moved re-keyed events without a
 * mark, and a pass that 2.0.4 deferred left them neither marked nor held. In that pass nothing outside the range goes
 * unasked, and what is not replaced is held, unless the question is approved: then it waits like the rest, and the
 * calendar stays cautious ([endsCautiousPass]).
 * Once the user approved ([approved]), the rest waits for the post-parallel pass, which deletes exactly the held events
 * the user saw.
 */
internal fun planServerDeletions(
    vanished: Collection<EventIdentity>,
    current: Collection<EventIdentity>,
    isExternal: Boolean,
    rekeyed: Set<String>,
    held: Set<String>,
    approved: Boolean,
    serverCount: Int,
    mapped: Collection<EventIdentity>,
    guarded: Set<String> = emptySet(),
    outsideRange: Set<String> = emptySet(),
    cautious: Boolean = false,
): DeletionPlan {
    val candidates = if (isExternal) vanished.filter { it.protonId !in rekeyed && it.protonId !in guarded } else emptyList()
    val replaced = replacedByNewCopies(candidates, current)
    val vanishedIds = vanished.mapTo(LinkedHashSet()) { it.protonId }
    val outOfRange = if (replaced.isEmpty() || cautious) emptySet() else vanishedIds.filterTo(LinkedHashSet()) {
        it in outsideRange && it !in replaced && it !in held && it !in rekeyed && it !in guarded
    }
    val rest = vanishedIds - replaced - outOfRange
    val base = if (isExternal) distinctEvents(mapped) else mapped.size
    return when {
        rest.isEmpty() -> DeletionPlan(replaced, outOfRange, emptySet(), emptySet(), emptySet())
        approved -> DeletionPlan(replaced, outOfRange, emptySet(), emptySet(), rest)
        cautious || RemoteDeletionGuard.isSuspicious(rest.size, serverCount, base) ->
            DeletionPlan(replaced, outOfRange, emptySet(), rest, emptySet())
        else -> {
            val stillAsked = rest.filterTo(LinkedHashSet()) { it in held || it in rekeyed || it in guarded }
            DeletionPlan(replaced, outOfRange, rest - stillAsked, stillAsked, emptySet())
        }
    }
}

/**
 * Whether this deletion pass ends an external calendar's cautious first pass ([planServerDeletions]): only a pass that
 * judged every vanished event. One that put them off, for an incomplete fetch ([deferredForFailures]) or because the
 * question was approved ([DeletionPlan.deferred], where nothing is held and the rest is judged at the next pass), leaves
 * the calendar cautious; ending it there would let the next pass delete, without the question, what 2.0.4 moved onto it.
 * A pass with nothing vanished has no [plan] and ends it.
 */
internal fun endsCautiousPass(isExternal: Boolean, deferredForFailures: Boolean, plan: DeletionPlan?): Boolean =
    isExternal && !deferredForFailures && (plan == null || plan.deferred.isEmpty())

package me.proton.sync.calendar

/**
 * Which held server-to-local deletions have nothing left to delete on this phone. Pure.
 *
 * A hold asks one question about LOCAL rows: these are gone from Proton, remove them here too, or
 * keep them? The only thing that ever drops a held id is its REAPPEARING ON PROTON
 * (`RemoteDeletionGuard.prunePending` against the server's ids), so an id that is on neither side
 * stays held for ever. Paths that wipe local rows behind the guard's back put ids into exactly that
 * state: the forced Remote-to-Local resync, switching calendar sync off, a backup import, removing
 * the account.
 *
 * The user is not stuck: either notification button ends the prompt for good (`keep` empties the set,
 * and the approve pass skips ids without a mapping and then clears it). What a stranded hold costs is
 * paid while nobody taps: the prompt is re-posted from the stored count on every sync, it asks about
 * events that are no longer there, and the non-zero count deliberately vetoes the modelevents
 * unchanged-skip fast path, so every calendar of that account is re-fetched in full on every sync.
 * Seen on a test device with four such ids in `remote_del_pending_calendar_<account>`.
 *
 * Keeping the rule pure keeps it testable without a Context or a provider. It only ever names ids the
 * caller already holds, and only when BOTH kinds of local evidence are absent, so sweeping can never
 * make the guard more willing to delete.
 */
internal data class HoldSweep(val stale: Set<String>, val remaining: Set<String>)

/**
 * [held] split into the ids with nothing left on the phone and the ids still worth asking about.
 *
 * [stillMapped] are the held ids whose Room mapping still points at a local row: that is exactly what
 * both notification buttons act through, so a mapped id is always actionable. [stillOnPhone] are the
 * held ids a provider row still carries in `_SYNC_ID`, which is how a row whose mapping was lost
 * (a sync that died between the insert and the mapping write) proves it came from Proton. A caller
 * that could not read the provider passes [held] here, so "I could not look" never reads as "there is
 * nothing there".
 */
internal fun sweepHolds(
    held: Set<String>,
    stillMapped: Set<String>,
    stillOnPhone: Set<String>
): HoldSweep {
    val stale = held.filterTo(mutableSetOf()) { it !in stillMapped && it !in stillOnPhone }
    return HoldSweep(stale, held - stale)
}

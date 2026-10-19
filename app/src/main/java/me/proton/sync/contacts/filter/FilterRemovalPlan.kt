package me.proton.sync.contacts.filter

/**
 * What one sync should do about the contacts a filter rule excludes but the phone still holds. Pure.
 *
 * Three sets go in and three come out, and the whole point is that they are NOT the same set:
 *  - `pending` is what the guard is currently holding, from earlier runs.
 *  - `stillExcluded` is every contact the rules still exclude, INCLUDING the ones the user rescued.
 *    It is the right answer to "may this hold be released?", so it is what prunes `pending`.
 *  - `toAsk` is only the contacts it is legitimate to ask about: excluded, on the phone, and NOT rescued.
 *    It is the only set that may be held, counted in the prompt, or deleted.
 *
 * Using `stillExcluded` where `toAsk` belongs is what made a rescue impossible: the rescued ids went
 * straight back into the pending set on the next sync, so "Keep on phone" cleared a prompt that
 * immediately came back, and a later "Delete from phone" then deleted contacts the user had rescued,
 * because they were sitting in the confirmed set.
 */
internal data class FilterRemovalPlan(
    /** Holds to release: the rules no longer exclude these. */
    val prune: Set<String>,
    /** Ids to hold and count in the prompt. Empty when there is nothing to ask. */
    val ask: Set<String>,
    /** Ids to remove from the phone now, because the user approved being asked about exactly these. */
    val delete: Set<String>,
    /** True when the guard's state for this account should be cleared and the prompt cancelled. */
    val clear: Boolean
)

/**
 * [pending] pruned by [stillExcluded], then either nothing to ask, the approved deletions, or the ask.
 *
 * [approved] is the standing "Delete from phone" answer. Deletion is intersected with [pending] so a rule
 * edited after the prompt cannot widen an approval the user never gave, and it is taken from [toAsk] so a
 * rescued contact can never be deleted even if a stale [pending] set still names it.
 */
internal fun planFilterRemovals(
    pending: Set<String>,
    stillExcluded: Set<String>,
    toAsk: Set<String>,
    approved: Boolean
): FilterRemovalPlan {
    val prune = pending - stillExcluded
    return when {
        toAsk.isEmpty() -> FilterRemovalPlan(prune, emptySet(), emptySet(), clear = true)
        approved -> FilterRemovalPlan(prune, emptySet(), toAsk.intersect(pending), clear = true)
        else -> FilterRemovalPlan(prune, toAsk, emptySet(), clear = false)
    }
}

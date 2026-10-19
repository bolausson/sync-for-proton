package me.proton.sync.calendar

/**
 * Which per-event retry counters name an event that no longer exists. Pure, so it can be tested.
 *
 * Three families count strikes against one event, and each is cleared only when its operation finally
 * succeeds or gives up:
 *
 *  - `upload_reject_count_<androidEventId>`
 *  - `delete_reject_count_<androidEventId>`
 *  - `invite_retry_count_<protonEventId>`
 *
 * An event that disappears before either of those happens leaves its counter behind for the life of
 * the install. Two were found on a test phone at 2 strikes each, naming rows deleted days earlier,
 * and they survived two account removals: nothing in the app has ever cleaned them up.
 *
 * Mostly that is preference bloat. The sharper edge is the Android-id families: a row id can in
 * principle be handed to a different event later, and a `delete_reject_count_` left at 2 would then
 * cut a perfectly good delete short on its first attempt.
 *
 * [liveIds] must be the ids that still exist, established positively. A caller that could not find
 * out passes null and nothing is swept, because deleting a counter for an event that DOES exist
 * hands it a free strike, which is the failure this must never cause.
 */
internal fun staleCounterKeys(
    allKeys: Set<String>,
    prefix: String,
    liveIds: Set<String>?,
): Set<String> {
    if (liveIds == null) return emptySet()
    return allKeys.filterTo(mutableSetOf()) { key ->
        key.startsWith(prefix) && key.removePrefix(prefix).let { it.isNotEmpty() && it !in liveIds }
    }
}

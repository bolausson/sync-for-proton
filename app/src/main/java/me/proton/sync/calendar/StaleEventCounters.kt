package me.proton.sync.calendar

/**
 * Which per-event retry counters name an event that no longer exists. Pure, so it can be tested.
 *
 * Three families count strikes against one event, and each is cleared only when its operation finally
 * succeeds or gives up, but a later send without a failure leaves the invitation counter as it is:
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
 * Until 2.2.0 the deletion pass also kept its rejections as `delete_reject_count_<protonEventId>`,
 * which names no row and so was swept at every sync: they never got past one. They are counted in
 * DeletionPassRefusals now, whose keys match none of these families.
 *
 * The keys name no account. The provider numbers the event rows of all its accounts in one sequence,
 * so `upload_reject_count_41` may be row 41 of another account of this app, and an invitation counter
 * may be about an event another account maps. Until 2.2.0 the sweep at the start of each account's
 * calendar sync asked that account's rows and mappings alone: with two Proton accounts, every sync of
 * one dropped the other's counters, so while both accounts' calendars synced in turn, an upload, or
 * the deletion of an event deleted on the phone, that Proton kept rejecting never reached its third
 * strike and was sent again at every sync. Since 2.2.0 a counter its own account does not explain is
 * asked of every other account of this app ([otherAccountsToAsk], [liveInAnyAccount]). An account
 * that is gone is not asked, so its counters still go at the next sweep of an account that remains:
 * nothing else removes them, as no account is in their keys (ProtonSyncApp.forgetRemovedAccounts
 * knows only the account-scoped ones).
 *
 * The invitation counter limits no send: its third strike writes a log line and drops the key.
 * Besides recordInviteRetryStrike and the sweep, only RangeTrimPass reads it: an event that has the
 * key is not trimmed ("invitation work left"). A guest whose invitation failed is tried again when
 * the event is next uploaded (`invite_failed_`), whatever the counter says, and a failed send does
 * not keep the event dirty, so nothing is tried again at every sync.
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

/**
 * The other accounts of this app a sweep run at [syncing]'s sync asks about the counters [syncing]'s
 * own rows and mappings do not explain: every name AccountManager lists ([listed]) but [syncing], each
 * once, in order. The caller asks [syncing] itself first, listed or not, so with one account nothing
 * more is asked and the sweep is the one of 2.1.0. An account that is gone is not listed: its rows and
 * mappings, while the provider and the app's database still hold them, keep no counter alive.
 */
internal fun otherAccountsToAsk(syncing: String, listed: Iterable<String>): List<String> =
    listed.filterTo(LinkedHashSet()) { it != syncing }.toList()

/**
 * What is live in any account asked, from each one's own answer ([answers], the syncing account's
 * first): their union, or null as soon as one answer is null. An answer is null when that account's
 * rows could not be read, and the row a counter names may be one of them, so nothing of the family is
 * swept, as when the syncing account's own rows could not be read.
 */
internal fun <T> liveInAnyAccount(answers: Iterable<Set<T>?>): Set<T>? {
    val live = HashSet<T>()
    for (answer in answers) live.addAll(answer ?: return null)
    return live
}

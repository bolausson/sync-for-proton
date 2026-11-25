package me.proton.sync.calendar

import android.content.SharedPreferences
import me.proton.sync.SyncPreferences

/**
 * Refusals met by the deletion pass (CalendarSyncAdapter.reconcileDeletedEvents), which deletes on Proton the mapped
 * events whose phone rows vanished without a tombstone, mainly an approved bulk deletion (BulkDeletionGuard). In 2.1.0 an
 * HTTP failure of one of its deletions was only logged. Since 2.2.0 one other than the read-only calendar's 400 with 2011
 * or 2026 fails the run with Proton's status (CalendarWriteFailures), and the mapping stays, so the deletion is sent again
 * at every sync: without a limit, a refusal for good, a 409 or a 422 say, would fail every sync for as long as it lasted.
 *
 * The owner's decision (2026-11-23): a refusal of the deletion itself
 * ([EventDeletionFailures.isRefusal]: any status but 401, 408, 429 and 5xx) counts a strike per Proton event id. The first
 * two fail their run with the status, as before ([failsRun]). The third gives the deletion up ([givesUp]) the way the pass
 * gives up one Proton refuses on a read-only calendar or rejects three times (its REFUSED and REJECTED branches): the
 * mapping goes and the calendar's download cursor is reset, so the next sync downloads the event again, as Proton has it.
 * The user is told with the rejected-events notice and its existing text ("this account cannot delete it on Proton, so it
 * comes back to this phone with the next sync"). That run does not fail for it, as the REJECTED branch's does not, and
 * nothing is deleted anywhere by giving up. A status about the connection or the session, and a request that threw, fail
 * every run they come in and count no strike, as for an event deleted on the phone (EventDeletionFailures).
 *
 * Its rejections too (owner, 2026-11-23): a deletion Proton answers 200 with its operation rejected
 * (DeleteOpVerdict.REJECTED) is given up at the third rejection ([rejectionGivesUp]), as the upload's delete path gives
 * one up, and a rejection fails no run, as before. 2.1.0 counted them under delete_reject_count_<protonEventId>, which the
 * stale-counter sweep (CalendarSyncAdapter.sweepStaleRetryCounters) drops at the start of every sync because it names no
 * row of the phone: the count never got past one, and the deletion was sent again at every sync, silently, for ever. They
 * are counted here now ([recordRejection]), apart from the refusals, so neither rule counts the other's answers: a refusal
 * still fails its first two runs, and a rejection is still tried three times. Every deletion the pass gives up, the one
 * refused on a read-only calendar included, is named in the rejected-events notice: a refused one with the text above,
 * one rejected for the third time with Proton's own error ([rejectionReason], owner 2026-11-23), as the upload's
 * delete path names its own third rejection, since a rejection may pass at a later attempt and so does not say that this
 * account cannot delete the event.
 *
 * The strikes are kept per Proton event id, as the row is gone and its Android id names nothing any more: the refusals
 * under event_delete_pass_strikes_<account>, the rejections under event_delete_pass_rejects_<account> (both
 * "protonEventId:n,..."). Both are forgotten when Proton takes the deletion or the deletion is given up, and pruned at the
 * start of a sync once the event's mapping is gone ([retain]). Neither is in the backup allowlist, and the sweep of
 * per-row counters matches neither. The pass runs alone, after the parallel downloads; the counts are still read and
 * written under this object's lock. Pure, so it is tested without a device.
 */
internal object DeletionPassRefusals {

    /** The refusal that gives a deletion up: the third, as many as an event deleted on the phone fails syncs for. */
    const val GIVE_UP_AT = EventDeletionFailures.MAX_REPORTED_REFUSALS

    /**
     * Whether a deletion Proton answered [httpCode] is given up: a refusal of the deletion itself that [refusals], this one
     * included ([recordRefusal]), brings to [GIVE_UP_AT].
     */
    fun givesUp(httpCode: Int, refusals: Int): Boolean =
        EventDeletionFailures.isRefusal(httpCode) && refusals >= GIVE_UP_AT

    /**
     * Whether a deletion Proton answered [httpCode] fails its run: every answer about the connection or the session, and a
     * refusal until the one that gives the deletion up.
     */
    fun failsRun(httpCode: Int, refusals: Int): Boolean = !givesUp(httpCode, refusals)

    /**
     * The rejection that gives a deletion up: the third, as for an event deleted on the phone
     * (CalendarSyncAdapter.bumpDeleteRejectionStrike) and as the pass's REJECTED branch has meant to since 2.1.0.
     */
    const val REJECTION_GIVE_UP_AT = 3

    /**
     * Whether a deletion Proton rejected (DeleteOpVerdict.REJECTED) is given up: [rejections], this one included
     * ([recordRejection]), have reached [REJECTION_GIVE_UP_AT]. Its refusals do not count here, nor its rejections there.
     */
    fun rejectionGivesUp(rejections: Int): Boolean = rejections >= REJECTION_GIVE_UP_AT

    /**
     * What the rejected-events notice gives after "(no title)" for a deletion given up at its third rejection
     * ([rejectionGivesUp]): Proton's own error, untranslated, as the upload names its third rejection of a deletion
     * (CalendarSyncAdapter.settleDeleteResponse, `op.error ?: "Proton error code ${op.code}"`) or of an upload
     * (handleUploadOpRejection), else the operation's code in that same form. Never empty: firstSyncOpResult keeps no
     * blank error and a blank one gives the code form here too, and an operation with neither an error nor a code is a
     * deletion Proton took (deleteOpVerdict), which never reaches this. The pass's [op] is null only for an HTTP
     * failure, which is never a rejection either.
     */
    fun rejectionReason(op: SyncOpResult?): String =
        op?.error?.takeIf { it.isNotBlank() } ?: "Proton error code ${op?.code}"

    /**
     * Whether [protonEventId] can be counted. Proton's ids are base64 and hold no comma or blank; one that did could not
     * be stored in the list, so it is never given up: its refusals fail every run, as they all did before, and its
     * rejections are sent again at every sync, as in 2.1.0.
     */
    fun countable(protonEventId: String): Boolean =
        protonEventId.isNotEmpty() && protonEventId.none { it == ',' || it.isWhitespace() }

    // ---- Stored per account (the key suffix keeps account renames working) ---------------------------------------

    /** "abc==:1,def-_:2" to {abc==: 1, def-_: 2}, the count read after the last colon; anything unreadable is dropped. */
    fun parseStrikes(raw: String?): Map<String, Int> =
        raw.orEmpty().split(',').mapNotNull { entry ->
            val colon = entry.lastIndexOf(':')
            if (colon <= 0) return@mapNotNull null
            val id = entry.substring(0, colon).trim()
            val n = entry.substring(colon + 1).trim().toIntOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
            if (id.isEmpty()) null else id to n
        }.toMap()

    fun formatStrikes(strikes: Map<String, Int>): String =
        strikes.entries.sortedBy { it.key }.joinToString(",") { "${it.key}:${it.value}" }

    /**
     * Count one more refusal of the deletion of [protonEventId], and return how many it has met. One that cannot be
     * counted ([countable]) is not stored and always meets its first.
     */
    @Synchronized
    fun recordRefusal(prefs: SharedPreferences, accountName: String, protonEventId: String): Int =
        record(prefs, SyncPreferences.eventDeletePassStrikesKey(accountName), protonEventId)

    /**
     * Count one more rejection of the deletion of [protonEventId] (DeleteOpVerdict.REJECTED), in a list of their own, and
     * return how many it has met. One that cannot be counted ([countable]) is not stored and always meets its first.
     */
    @Synchronized
    fun recordRejection(prefs: SharedPreferences, accountName: String, protonEventId: String): Int =
        record(prefs, SyncPreferences.eventDeletePassRejectsKey(accountName), protonEventId)

    /** One more for [protonEventId] in the list under [key], and how many it has now. */
    private fun record(prefs: SharedPreferences, key: String, protonEventId: String): Int {
        if (!countable(protonEventId)) return 1
        val strikes = parseStrikes(prefs.getString(key, null)).toMutableMap()
        val count = (strikes[protonEventId] ?: 0) + 1
        strikes[protonEventId] = count
        prefs.edit().putString(key, formatStrikes(strikes)).apply()
        return count
    }

    /** Forget the refusals and the rejections of [protonEventId]: Proton took its deletion, or it was given up. */
    @Synchronized
    fun forget(prefs: SharedPreferences, accountName: String, protonEventId: String) {
        retain(prefs, accountName) { it != protonEventId }
    }

    /** Keep only the refusals and the rejections of the deletions [keep] accepts; a list that loses none is not written. */
    @Synchronized
    fun retain(prefs: SharedPreferences, accountName: String, keep: (String) -> Boolean) {
        for (key in keysOf(accountName)) retainIn(prefs, key, keep)
    }

    private fun retainIn(prefs: SharedPreferences, key: String, keep: (String) -> Boolean) {
        val strikes = parseStrikes(prefs.getString(key, null) ?: return)
        val kept = strikes.filterKeys(keep)
        if (kept.size == strikes.size) return
        val edit = prefs.edit()
        if (kept.isEmpty()) edit.remove(key) else edit.putString(key, formatStrikes(kept))
        edit.apply()
    }

    /** The Proton event ids with refusals or rejections counted, for the prune at the start of a sync. */
    @Synchronized
    fun countedIds(prefs: SharedPreferences, accountName: String): Set<String> =
        keysOf(accountName).flatMapTo(LinkedHashSet()) { parseStrikes(prefs.getString(it, null)).keys }

    /** The lists of [accountName]: its refusals, then its rejections. */
    private fun keysOf(accountName: String): List<String> =
        listOf(SyncPreferences.eventDeletePassStrikesKey(accountName), SyncPreferences.eventDeletePassRejectsKey(accountName))
}

package me.proton.sync.calendar

import android.content.SharedPreferences
import me.proton.sync.SyncPreferences
import me.proton.sync.contacts.ContactDeletionFailures
import me.proton.sync.contacts.ContactUploadRefusals

/**
 * An event deleted on this phone whose deletion Proton answered with a failure (CalendarSyncAdapter.settleDeleteResponse:
 * any HTTP status but the read-only calendar's 400, whose event is put back). The tombstone stays and the deletion is
 * sent again at every sync, as before. Until 2.2.0 every such answer counted an I/O failure with no reason: each sync
 * failed with "unexpected error", and a refusal for good did so at every sync for ever (owner, 2026-11-23).
 *
 * It now gets the treatment of a contact deleted on the phone (ContactDeletionFailures): the reason carries Proton's
 * status, which picks the text shown; a status about the connection or the session fails every run it comes in; a
 * refusal of the deletion itself counts a strike per event, and only its first [MAX_REPORTED_REFUSALS] fail their run.
 * A request that threw is counted where it is caught (uploadDeletedRow), with its network cause, at every run as before.
 * The strikes are kept per Android event id under event_delete_strikes_<account>, forgotten when Proton takes the
 * deletion and pruned when the row is no longer a pending deletion. The uploads run in parallel, so the stored counts
 * are read and written under this object's lock.
 *
 * From the third refusal on, a lasting notice names the event instead ([refusedForGood], [notice]), built exactly like the
 * contacts' one (owner, 2026-11-23): with Android's automatic sync on, its own retries of the failed runs used the
 * three up within minutes, and then only the log said that the event was still on Proton and on the user's other devices.
 * It is posted once, brought up to date while shown, and withdrawn once no such deletion is left; what it named is kept
 * under event_delete_noticed_<account> ([noticedIds]). The deletion stays pending and the event does not come back, so
 * the notice never says it does.
 */
internal object EventDeletionFailures {

    /** How many syncs one refused deletion fails, at most: as many as a contact's. */
    const val MAX_REPORTED_REFUSALS = ContactDeletionFailures.MAX_REPORTED_REFUSALS

    /**
     * The reason the run records (SyncUtils.recordSwallowedFailure), with Proton's HTTP status [httpCode], which picks the
     * text shown: the server error, too many requests, an expired session, a rejected request (SyncNotificationHelper
     * .friendlyMessageRes). Nothing of the event is in it.
     */
    fun reason(httpCode: Int): String = "Proton did not delete an event deleted on this phone: HTTP $httpCode"

    /** Whether Proton's answer [httpCode] refuses the deletion itself: the contacts' split (ContactDeletionFailures). */
    fun isRefusal(httpCode: Int): Boolean = ContactDeletionFailures.isRefusal(httpCode)

    /**
     * Whether a deletion Proton answered [httpCode] fails its run: every time for a status about the connection or the
     * session, and for a refusal ([isRefusal]) only while [refusals], this one included ([recordRefusal]), is one of the
     * first [MAX_REPORTED_REFUSALS].
     */
    fun failsRun(httpCode: Int, refusals: Int): Boolean = ContactDeletionFailures.failsRun(httpCode, null, refusals)

    // ---- Stored per account (the key suffix keeps account renames working) ---------------------------------------

    /** Count one more refusal of the deletion of [androidEventId], and return how many it has met. */
    @Synchronized
    fun recordRefusal(prefs: SharedPreferences, accountName: String, androidEventId: Long): Int {
        val key = SyncPreferences.eventDeleteStrikesKey(accountName)
        val strikes = ContactUploadRefusals.parseStrikes(prefs.getString(key, null)).toMutableMap()
        val refusals = (strikes[androidEventId] ?: 0) + 1
        strikes[androidEventId] = refusals
        prefs.edit().putString(key, ContactUploadRefusals.formatStrikes(strikes)).apply()
        return refusals
    }

    /** Forget the refusals of [androidEventId]: Proton took its deletion. */
    @Synchronized
    fun forget(prefs: SharedPreferences, accountName: String, androidEventId: Long) {
        retain(prefs, accountName) { it != androidEventId }
    }

    /** Keep only the refusals of the deletions [keep] accepts; writes nothing when none goes. */
    @Synchronized
    fun retain(prefs: SharedPreferences, accountName: String, keep: (Long) -> Boolean) {
        val key = SyncPreferences.eventDeleteStrikesKey(accountName)
        val strikes = ContactUploadRefusals.parseStrikes(prefs.getString(key, null) ?: return)
        val kept = strikes.filterKeys(keep)
        if (kept.size == strikes.size) return
        val edit = prefs.edit()
        if (kept.isEmpty()) edit.remove(key) else edit.putString(key, ContactUploadRefusals.formatStrikes(kept))
        edit.apply()
    }

    // ---- The lasting notice (CalendarSyncAdapter.noticeRefusedEventDeletions) --------------------------------------

    /**
     * The Android event ids of the deletions refused [MAX_REPORTED_REFUSALS] times or more: their refusals fail no sync
     * any more, so the lasting notice names them, until each reaches Proton ([forget]) or is no longer pending ([retain]).
     */
    @Synchronized
    fun refusedForGood(prefs: SharedPreferences, accountName: String): Set<Long> =
        ContactUploadRefusals.parseStrikes(prefs.getString(SyncPreferences.eventDeleteStrikesKey(accountName), null))
            .filterValues { it >= MAX_REPORTED_REFUSALS }.keys

    /**
     * What becomes of the lasting notice at the end of an upload: the contacts' rule itself (ContactDeletionFailures
     * .notice), with [noticed] the deletions it named when an upload last settled it ([noticedIds]) and [now] those refused
     * for good at the end of this one. Posted when one joined, listing all of them; brought up to date when one left while
     * the notice is still up ([shown], asked only then); withdrawn once none is left; otherwise left as it is.
     */
    fun notice(noticed: Set<Long>, now: Set<Long>, shown: () -> Boolean): ContactDeletionFailures.Notice =
        ContactDeletionFailures.notice(noticed, now, shown)

    /** The Android event ids the lasting notice named when an upload last settled it ([rememberNoticed]). */
    fun noticedIds(prefs: SharedPreferences, accountName: String): Set<Long> =
        prefs.getString(SyncPreferences.eventDeleteNoticedKey(accountName), null).orEmpty()
            .split(',').mapNotNull { it.trim().toLongOrNull() }.toSet()

    /**
     * Keep [now] as what the notice names, after every upload's [notice], whatever it decided: a deletion that left is no
     * longer named, and one the user dismissed is not brought back. Writes nothing when that is unchanged.
     */
    fun rememberNoticed(prefs: SharedPreferences, accountName: String, now: Set<Long>) {
        if (noticedIds(prefs, accountName) == now) return
        val key = SyncPreferences.eventDeleteNoticedKey(accountName)
        if (now.isEmpty()) prefs.edit().remove(key).apply()
        else prefs.edit().putString(key, now.sorted().joinToString(",")).apply()
    }

    /**
     * Forget what the notice named, so the next upload posts it again: for an account rename, which takes the notice
     * down (its id is built from the account name) while the counts move to the new name (ProtonSyncApp).
     */
    fun forgetNoticed(prefs: SharedPreferences, accountName: String) {
        prefs.edit().remove(SyncPreferences.eventDeleteNoticedKey(accountName)).apply()
    }
}

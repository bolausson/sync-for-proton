package me.proton.sync.contacts

import android.content.SharedPreferences
import me.proton.sync.NetworkCause
import me.proton.sync.SyncPreferences

/**
 * A contact deleted on the phone whose deletion did not reach Proton (ContactsSyncAdapter.uploadLocalDeletion, and the
 * upload loop's catch for a request that threw). It stays pending, as before: the deleted row keeps its dirty flag and
 * is sent again at the next sync. What changed (2.2.0, owner 2026-11-23) is that the run says so. It used to report
 * success, so with Android's automatic sync off nothing told the user to tap Sync, and the contact stayed on Proton
 * and on every other device.
 *
 * A failure that says nothing about the deletion itself fails every run it happens in, as such a failure does anywhere
 * in the sync: a request the network broke (NetworkCause), which shows the network's text, and an answer about the
 * connection or the session (ContactUploadRefusals.isTransientHttp), which shows the text its status gives. A refusal
 * of the deletion itself (any other status) is not cured by trying again, so it counts a strike, and only the first
 * [MAX_REPORTED_REFUSALS] fail their run: a deletion Proton never takes cannot fail every sync. It is still sent again
 * at every sync, as before, and its strikes are forgotten once it reaches Proton or is no longer pending. The same
 * three strikes as a contact created on the phone that Proton refuses (ContactUploadRefusals) and the calendar's
 * rejected uploads, which also stop at the third.
 *
 * From the third refusal on, a lasting notice names the contact instead ([refusedForGood], [notice]): with Android's
 * automatic sync on, its own retries of the failed runs used the three up within minutes, the next run cleared the
 * failure notification, and nothing said any more that the contact was still on Proton and on the user's other devices
 * (owner, 2026-11-23). It is posted once, brought up to date while shown, and withdrawn once no such deletion is left.
 * What it named is kept ([noticedIds]), so a deletion is named also when the upload of its third refusal stopped before
 * the notice was settled, or its count came from a build that posted no notice.
 */
internal object ContactDeletionFailures {

    /** How many syncs one refused deletion fails, at most. */
    const val MAX_REPORTED_REFUSALS = ContactUploadRefusals.MAX_ATTEMPTS

    /**
     * The reason the run records (SyncUtils.recordSwallowedFailure, which adds a request's network cause), with Proton's
     * HTTP status [httpCode] where Proton answered: the status picks the text shown (SyncNotificationHelper
     * .friendlyMessageRes). Nothing of the contact is in it.
     */
    fun reason(httpCode: Int?): String =
        if (httpCode == null) "Could not send a deleted contact to Proton"
        else "Proton did not delete a contact deleted on this phone: HTTP $httpCode"

    /**
     * Whether Proton's answer [httpCode] refuses the deletion itself, rather than describing the connection or the
     * session (ContactUploadRefusals.isTransientHttp), which a later sync gets past.
     */
    fun isRefusal(httpCode: Int): Boolean = !ContactUploadRefusals.isTransientHttp(httpCode)

    /**
     * Whether a deletion that did not reach Proton fails its run. [httpCode]: Proton's answer, or null when the request
     * threw [cause] instead. [refusals]: for a refusal ([isRefusal]), how many this deletion has met, this one included
     * ([recordRefusal]).
     */
    fun failsRun(httpCode: Int?, cause: Throwable?, refusals: Int): Boolean = when {
        httpCode == null -> NetworkCause.of(cause) != null
        isRefusal(httpCode) -> refusals in 1..MAX_REPORTED_REFUSALS
        else -> true
    }

    // ---- Stored per parent account (the key suffix keeps account renames working) ----------

    /** Count one more refusal of the deletion of [rawContactId], and return how many it has met. */
    fun recordRefusal(prefs: SharedPreferences, accountName: String, rawContactId: Long): Int {
        val key = SyncPreferences.contactDeleteStrikesKey(accountName)
        val strikes = ContactUploadRefusals.parseStrikes(prefs.getString(key, null)).toMutableMap()
        val refusals = (strikes[rawContactId] ?: 0) + 1
        strikes[rawContactId] = refusals
        prefs.edit().putString(key, ContactUploadRefusals.formatStrikes(strikes)).apply()
        return refusals
    }

    /** Forget the refusals of [rawContactId]: its deletion reached Proton. */
    fun forget(prefs: SharedPreferences, accountName: String, rawContactId: Long) {
        retain(prefs, accountName) { it != rawContactId }
    }

    /** Keep only the refusals of the deletions [keep] accepts; writes nothing when none goes. */
    fun retain(prefs: SharedPreferences, accountName: String, keep: (Long) -> Boolean) {
        val key = SyncPreferences.contactDeleteStrikesKey(accountName)
        val strikes = ContactUploadRefusals.parseStrikes(prefs.getString(key, null) ?: return)
        val kept = strikes.filterKeys(keep)
        if (kept.size == strikes.size) return
        val edit = prefs.edit()
        if (kept.isEmpty()) edit.remove(key) else edit.putString(key, ContactUploadRefusals.formatStrikes(kept))
        edit.apply()
    }

    // ---- The lasting notice (ContactsSyncAdapter.noticeRefusedDeletions) ----------------------------------------------

    /**
     * The raw contact ids of the deletions refused [MAX_REPORTED_REFUSALS] times or more: their refusals fail no sync any
     * more, so the lasting notice names them, until each reaches Proton ([forget]) or is no longer pending ([retain]).
     */
    fun refusedForGood(prefs: SharedPreferences, accountName: String): Set<Long> =
        ContactUploadRefusals.parseStrikes(prefs.getString(SyncPreferences.contactDeleteStrikesKey(accountName), null))
            .filterValues { it >= MAX_REPORTED_REFUSALS }.keys

    /** What an upload does with the lasting notice. */
    enum class Notice {
        /** Post it, naming every deletion refused for good: one notice, replaced, never a second one. */
        POST,
        /** Withdraw it: no deletion is refused for good any more. */
        CANCEL,
        /** Leave it as it is: shown, dismissed by the user, or never posted. */
        KEEP,
    }

    /**
     * What becomes of the lasting notice at the end of an upload: [noticed] the deletions it named when an upload last
     * settled it ([noticedIds]), [now] those refused for good at the end of this one. Posted when one joined (its third
     * refusal came in this sync, or in one that stopped before it settled the notice), listing all of them; brought up to
     * date when one left while the notice is still up ([shown], asked only then); withdrawn once none is left. Otherwise
     * left as it is: not posted again at every sync, nor again after the user dismissed it.
     */
    fun notice(noticed: Set<Long>, now: Set<Long>, shown: () -> Boolean): Notice = when {
        now.isEmpty() -> Notice.CANCEL
        !noticed.containsAll(now) -> Notice.POST
        now != noticed && shown() -> Notice.POST
        else -> Notice.KEEP
    }

    /** The raw contact ids the lasting notice named when an upload last settled it ([rememberNoticed]). */
    fun noticedIds(prefs: SharedPreferences, accountName: String): Set<Long> =
        prefs.getString(SyncPreferences.contactDeleteNoticedKey(accountName), null).orEmpty()
            .split(',').mapNotNull { it.trim().toLongOrNull() }.toSet()

    /**
     * Keep [now] as what the notice names, after every upload's [notice], whatever it decided: a deletion that left is
     * no longer named, and one the user dismissed is not brought back. Writes nothing when that is unchanged.
     */
    fun rememberNoticed(prefs: SharedPreferences, accountName: String, now: Set<Long>) {
        if (noticedIds(prefs, accountName) == now) return
        val key = SyncPreferences.contactDeleteNoticedKey(accountName)
        if (now.isEmpty()) prefs.edit().remove(key).apply()
        else prefs.edit().putString(key, now.sorted().joinToString(",")).apply()
    }

    /**
     * Forget what the notice named, so the next upload posts it again: for an account rename, which takes the notice
     * down (its id is built from the account name) while the counts move to the new name (ProtonSyncApp).
     */
    fun forgetNoticed(prefs: SharedPreferences, accountName: String) {
        prefs.edit().remove(SyncPreferences.contactDeleteNoticedKey(accountName)).apply()
    }
}

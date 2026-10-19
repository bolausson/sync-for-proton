package me.proton.sync

import android.content.Context
import androidx.preference.PreferenceManager

/**
 * Confirm-before-delete guard for the **server → local** direction.
 *
 * When items vanish from Proton (contacts, or calendar events), removing them locally is
 * normally correct — but a *transient / partial* server response could wrongly wipe local
 * data, and silently skipping (the pre-v1.5.11 behaviour) leaves the user unable to remove
 * genuinely-deleted items. So for a *suspiciously large* shrink this guard HOLDS the local
 * deletion and asks the user via a notification:
 *  - **Delete from phone** → approve; the held deletions are carried out.
 *  - **Keep on phone**     → remember those ids as *kept*; never delete them, never re-prompt —
 *                            until they reappear on Proton (then they're pruned from *kept*).
 *
 * Parameterised by [type] ([TYPE_CONTACTS] / [TYPE_CALENDAR]) so both sync adapters share it.
 * State lives in SharedPreferences (survives process death). All mutations are synchronized
 * because the calendar adapter deletes per-calendar across parallel coroutines.
 */
object RemoteDeletionGuard {

    const val TYPE_CONTACTS = "contacts"
    const val TYPE_CALENDAR = "calendar"

    /**
     * Contacts a **filter rule** would remove from the phone while they remain on Proton.
     *
     * A separate type rather than a reuse of [TYPE_CONTACTS], because the two differ in every
     * respect that matters here. The pending and kept sets are pruned against ids still present on
     * the server, and a filter-excluded contact is *always* on the server, so sharing the type
     * would erase the pending set before the user could answer and let the kept set evaporate and
     * re-prompt for ever. [isSuspicious] is also deliberately not consulted for this type: its
     * ten-item, fifty-percent threshold exists to catch a server glitch, whereas here the
     * destructive input is a rule the user typed, so every removal is confirmed however small.
     *
     * Sharing the type would also mean approving a server deletion silently approved a filter
     * removal, since the pending and approved keys are per type.
     */
    const val TYPE_CONTACTS_FILTER = "contacts_filter"

    /** Below this many deletions we don't prompt — removing a handful is normal user activity. */
    const val ABS_MIN = 10
    const val FRACTION = 0.5

    private val lock = Any()

    private fun countKey(t: String, a: String) = "remote_del_count_${t}_$a"
    private fun pendingKey(t: String, a: String) = "remote_del_pending_${t}_$a"
    private fun approvedKey(t: String, a: String) = "remote_del_approved_${t}_$a"
    private fun keptKey(t: String, a: String) = "remote_del_kept_${t}_$a"

    private fun prefs(c: Context) = PreferenceManager.getDefaultSharedPreferences(c)

    /** A shrink large enough to be a likely glitch rather than genuine intent. */
    fun isSuspicious(deleteCount: Int, serverCount: Int, mappingCount: Int): Boolean {
        if (deleteCount == 0) return false
        if (serverCount == 0 && mappingCount > 0) return true          // everything vanished from the server
        return deleteCount >= ABS_MIN && deleteCount >= mappingCount * FRACTION
    }

    fun isApproved(c: Context, t: String, a: String): Boolean =
        prefs(c).getBoolean(approvedKey(t, a), false)

    fun keptIds(c: Context, t: String, a: String): Set<String> =
        prefs(c).getStringSet(keptKey(t, a), emptySet())?.toSet() ?: emptySet()

    fun pendingIds(c: Context, t: String, a: String): Set<String> =
        prefs(c).getStringSet(pendingKey(t, a), emptySet())?.toSet() ?: emptySet()

    fun pendingCount(c: Context, t: String, a: String): Int =
        prefs(c).getInt(countKey(t, a), 0)

    /** Thread-safe: union [ids] into the held-pending set (calendars hold concurrently). */
    fun addPending(c: Context, t: String, a: String, ids: Set<String>) = synchronized(lock) {
        if (ids.isEmpty()) return
        val cur = (prefs(c).getStringSet(pendingKey(t, a), emptySet())?.toMutableSet() ?: mutableSetOf())
        cur.addAll(ids)
        prefs(c).edit().putStringSet(pendingKey(t, a), cur).putInt(countKey(t, a), cur.size).apply()
    }

    /** User tapped "Delete from phone": the next sync carries out the held deletions. */
    fun approve(c: Context, t: String, a: String) =
        prefs(c).edit().putBoolean(approvedKey(t, a), true).apply()

    /** User tapped "Keep on phone": move pending ids into the kept set; stop deleting + prompting. */
    fun keep(c: Context, t: String, a: String) = synchronized(lock) {
        val p = prefs(c)
        val pending = p.getStringSet(pendingKey(t, a), emptySet()) ?: emptySet()
        val kept = (p.getStringSet(keptKey(t, a), emptySet())?.toMutableSet() ?: mutableSetOf())
        kept.addAll(pending)
        p.edit()
            .putStringSet(keptKey(t, a), kept)
            .remove(pendingKey(t, a)).remove(countKey(t, a)).remove(approvedKey(t, a))
            .apply()
    }

    /** After the held deletions are applied (or there's nothing to do): clear pending + approved. */
    fun clearPending(c: Context, t: String, a: String) = synchronized(lock) {
        prefs(c).edit()
            .remove(pendingKey(t, a)).remove(countKey(t, a)).remove(approvedKey(t, a))
            .apply()
    }

    /**
     * The local rows this account's holds were about are gone, so the question is void: drop the held set and
     * take the prompt down. For every path that wipes local data behind the guard's back, which is sync being
     * switched off, a forced Remote-to-Local resync, a backup import, and the account being removed.
     *
     * The KEPT set is deliberately left alone. It records a decision the user made ("keep this on the phone"),
     * it holds no count, posts nothing, and a stale entry costs nothing, whereas dropping it silently throws
     * the decision away and asks again the next time the rules are evaluated.
     */
    fun forgetHolds(c: Context, t: String, a: String) {
        clearPending(c, t, a)
        SyncNotificationHelper.cancelRemoteDeletionPrompt(c, a, t)
    }

    /** Every type, for a path that wipes an account wholesale. */
    fun forgetAllHolds(c: Context, a: String) {
        for (t in listOf(TYPE_CALENDAR, TYPE_CONTACTS, TYPE_CONTACTS_FILTER)) forgetHolds(c, t, a)
    }

    /** Drop *pending* ids that are present again on the server (the item came back — don't delete it). */
    fun prunePending(c: Context, t: String, a: String, presentIds: Set<String>) =
        pruneSet(c, pendingKey(t, a), countKey(t, a), presentIds)

    /** Drop *kept* ids that are present again on the server (resume normal sync for them). */
    fun pruneKept(c: Context, t: String, a: String, presentIds: Set<String>) =
        pruneSet(c, keptKey(t, a), null, presentIds)

    private fun pruneSet(c: Context, setKey: String, countKey: String?, presentIds: Set<String>) = synchronized(lock) {
        val cur = prefs(c).getStringSet(setKey, emptySet()) ?: return
        if (cur.isEmpty() || presentIds.isEmpty()) return
        val remaining = cur.filter { it !in presentIds }.toSet()
        if (remaining.size != cur.size) {
            val e = prefs(c).edit().putStringSet(setKey, remaining)
            if (countKey != null) e.putInt(countKey, remaining.size)
            e.apply()
        }
    }
}

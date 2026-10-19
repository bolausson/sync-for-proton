package me.proton.sync.contacts

import android.content.SharedPreferences
import me.proton.sync.SyncPreferences

/**
 * Contacts on Proton too large to write to this phone's contacts storage (see
 * [ContactsMapper.writeContact]), remembered with the modify time they had then.
 *
 * While that time has not moved, the download skips the contact instead of fetching and decrypting a
 * card of hundreds of KB or more on every sync only to fail again. A change on Proton moves the time
 * and the contact is tried afresh. A pending local edit to the phone's older copy is never skipped: it
 * goes through the normal conflict check, so Proton's complete version is not silently replaced.
 *
 * Stored per parent account as "modifyTime:kind:protonId", kind "update" when the phone keeps an older
 * version of the contact and "new" when the contact is not on the phone at all. Proton ids contain no
 * colon. Not in the settings backup: it describes this phone's storage, not a choice of the user's.
 */
object ContactDownloadHolds {

    data class Hold(val protonId: String, val modifyTime: Long, val onPhone: Boolean)

    internal fun parse(entries: Set<String>?): Map<String, Hold> {
        val holds = LinkedHashMap<String, Hold>()
        for (entry in entries ?: return holds) {
            val parts = entry.split(':', limit = 3)
            if (parts.size != 3 || parts[2].isEmpty()) continue
            val time = parts[0].toLongOrNull() ?: continue
            val onPhone = when (parts[1]) {
                "update" -> true
                "new" -> false
                else -> continue
            }
            holds[parts[2]] = Hold(parts[2], time, onPhone)
        }
        return holds
    }

    internal fun format(holds: Collection<Hold>): Set<String> =
        holds.mapTo(LinkedHashSet()) { "${it.modifyTime}:${if (it.onPhone) "update" else "new"}:${it.protonId}" }

    /**
     * Whether the download skips this contact: held at exactly this modify time, in the same situation
     * (a held update needs the local row it was held for; after a forced reload it is a new contact
     * again and worth a try), and with no local edit waiting to be checked for a conflict.
     */
    fun skips(hold: Hold?, modifyTime: Long, hasLocalRow: Boolean, localEditPending: Boolean): Boolean =
        hold != null && hold.modifyTime == modifyTime && hold.onPhone == hasLocalRow && !localEditPending

    fun all(prefs: SharedPreferences, account: String): Map<String, Hold> =
        parse(prefs.getStringSet(SyncPreferences.contactDownloadTooLargeKey(account), null))

    fun hold(prefs: SharedPreferences, account: String, hold: Hold) {
        val holds = all(prefs, account).toMutableMap()
        holds[hold.protonId] = hold
        write(prefs, account, holds.values)
    }

    fun release(prefs: SharedPreferences, account: String, protonId: String) {
        val holds = all(prefs, account)
        if (protonId !in holds) return
        write(prefs, account, holds.values.filter { it.protonId != protonId })
    }

    /** Drops every hold whose contact [keep] rejects, such as one no longer on Proton. */
    fun retain(prefs: SharedPreferences, account: String, keep: (String) -> Boolean) {
        val holds = all(prefs, account)
        val kept = holds.values.filter { keep(it.protonId) }
        if (kept.size != holds.size) write(prefs, account, kept)
    }

    fun clear(prefs: SharedPreferences, account: String) {
        prefs.edit().remove(SyncPreferences.contactDownloadTooLargeKey(account)).apply()
    }

    private fun write(prefs: SharedPreferences, account: String, holds: Collection<Hold>) {
        val key = SyncPreferences.contactDownloadTooLargeKey(account)
        if (holds.isEmpty()) prefs.edit().remove(key).apply()
        else prefs.edit().putStringSet(key, format(holds)).apply()
    }
}

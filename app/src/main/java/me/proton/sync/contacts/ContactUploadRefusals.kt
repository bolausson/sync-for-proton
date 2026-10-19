package me.proton.sync.contacts

import android.content.SharedPreferences
import me.proton.sync.SyncPreferences

/**
 * Contacts created on the phone that could not be created on Proton.
 *
 * A contact made on the phone has no mapping until Proton accepts it. The orphan sweep deletes
 * every contact that has no mapping and is not dirty, which is right for a row whose mapping was
 * lost (it would otherwise be downloaded a second time) and wrong for a contact that simply has not
 * reached Proton yet. The upload used to clear the dirty flag whenever a create failed (the whole
 * batch refused over HTTP, one contact refused inside the batch, a dropped connection, or any error
 * building its card), so the next sync deleted a contact that existed nowhere else.
 *
 * Now a failed create keeps the contact pending. A failure that says nothing about the contact
 * (no connection, a timeout, a server error, rate limiting, an expired session, the contacts
 * provider dying) costs nothing and is simply retried. A refusal of the contact itself, or an error
 * preparing its card, counts a strike; after [MAX_ATTEMPTS] the app stops retrying, clears the
 * flag, and records the contact here, which exempts it from the sweep: it stays on the phone, the
 * user is told, and an edit dirties it again for another round of attempts. Mirrors the calendar's
 * three-strike give-up, which also leaves the event on the phone.
 *
 * The latest reason per contact is kept as well, so the notification can list every contact still
 * stuck, not only the ones that failed in the most recent sync, and disappear once none are left.
 */
internal object ContactUploadRefusals {

    const val MAX_ATTEMPTS = 3

    /** HTTP statuses that describe the connection or the session, not the contact. */
    fun isTransientHttp(code: Int): Boolean = code == 401 || code == 408 || code == 429 || code >= 500

    // ---- Pure parts, unit-tested -----------------------------------------------------------

    /** "12:1,40:2" to {12=1, 40=2}; anything unreadable is dropped. */
    fun parseStrikes(raw: String?): Map<Long, Int> =
        raw.orEmpty().split(',').mapNotNull { entry ->
            val (id, count) = entry.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
            val rawId = id.trim().toLongOrNull() ?: return@mapNotNull null
            val n = count.trim().toIntOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
            rawId to n
        }.toMap()

    fun formatStrikes(strikes: Map<Long, Int>): String =
        strikes.entries.sortedBy { it.key }.joinToString(",") { "${it.key}:${it.value}" }

    /** {"12:Email too long"} to {12="Email too long"}; the reason may itself contain colons. */
    fun parseReasons(raw: Set<String>?): Map<Long, String> =
        raw.orEmpty().mapNotNull { entry ->
            val sep = entry.indexOf(':').takeIf { it > 0 } ?: return@mapNotNull null
            val rawId = entry.substring(0, sep).toLongOrNull() ?: return@mapNotNull null
            rawId to entry.substring(sep + 1)
        }.toMap()

    fun formatReasons(reasons: Map<Long, String>): Set<String> =
        reasons.entries.mapTo(HashSet()) { "${it.key}:${it.value}" }

    // ---- Stored per parent account (the key suffix keeps account renames working) ----------

    /** Count one more failure of [rawContactId], remember [reason], and return the attempt number. */
    fun recordStrike(prefs: SharedPreferences, accountName: String, rawContactId: Long, reason: String): Int {
        val strikesKey = SyncPreferences.contactCreateStrikesKey(accountName)
        val reasonsKey = SyncPreferences.contactCreateReasonsKey(accountName)
        val strikes = parseStrikes(prefs.getString(strikesKey, null)).toMutableMap()
        val attempt = (strikes[rawContactId] ?: 0) + 1
        strikes[rawContactId] = attempt
        val reasons = parseReasons(prefs.getStringSet(reasonsKey, null)).toMutableMap()
        reasons[rawContactId] = reason
        prefs.edit()
            .putString(strikesKey, formatStrikes(strikes))
            .putStringSet(reasonsKey, formatReasons(reasons))
            .apply()
        return attempt
    }

    /** Stop tracking [rawContactId]: it reached Proton, or it is gone. */
    fun forget(prefs: SharedPreferences, accountName: String, rawContactId: Long) {
        retain(prefs, accountName) { it != rawContactId }
    }

    /**
     * Record that the app gave up on [rawContactId]: the sweep must leave it alone.
     *
     * Written with commit(), not apply(): the caller clears the dirty flag straight after, and if the
     * process died before an asynchronous write reached disk, the next sync would find a clean,
     * unmapped, unprotected row and delete the only copy.
     */
    fun markRefused(prefs: SharedPreferences, accountName: String, rawContactId: Long): Boolean {
        val strikesKey = SyncPreferences.contactCreateStrikesKey(accountName)
        val refusedKey = SyncPreferences.contactCreateRefusedKey(accountName)
        val strikes = parseStrikes(prefs.getString(strikesKey, null)) - rawContactId
        val refused = prefs.getStringSet(refusedKey, emptySet()).orEmpty() + rawContactId.toString()
        return prefs.edit()
            .putString(strikesKey, formatStrikes(strikes))
            .putStringSet(refusedKey, refused)
            .commit()
    }

    fun refusedIds(prefs: SharedPreferences, accountName: String): Set<Long> =
        prefs.getStringSet(SyncPreferences.contactCreateRefusedKey(accountName), emptySet())
            .orEmpty().mapNotNull { it.toLongOrNull() }.toSet()

    /** Every contact still stuck, given up or still being retried, with its latest reason. */
    fun tracked(prefs: SharedPreferences, accountName: String): List<Pair<Long, String>> {
        val ids = parseStrikes(prefs.getString(SyncPreferences.contactCreateStrikesKey(accountName), null)).keys +
            refusedIds(prefs, accountName)
        val reasons = parseReasons(prefs.getStringSet(SyncPreferences.contactCreateReasonsKey(accountName), null))
        return ids.sorted().map { it to (reasons[it] ?: "") }
    }

    /** Keep only the ids [keep] accepts, in the strike counts, the refused set and the reasons. */
    fun retain(prefs: SharedPreferences, accountName: String, keep: (Long) -> Boolean) {
        val strikesKey = SyncPreferences.contactCreateStrikesKey(accountName)
        val refusedKey = SyncPreferences.contactCreateRefusedKey(accountName)
        val reasonsKey = SyncPreferences.contactCreateReasonsKey(accountName)
        val strikes = parseStrikes(prefs.getString(strikesKey, null))
        val refused = prefs.getStringSet(refusedKey, emptySet()).orEmpty()
        val reasons = parseReasons(prefs.getStringSet(reasonsKey, null))
        val keptStrikes = strikes.filterKeys(keep)
        val keptRefused = refused.filter { id -> id.toLongOrNull()?.let(keep) == true }.toSet()
        val keptReasons = reasons.filterKeys(keep)
        if (keptStrikes.size == strikes.size && keptRefused.size == refused.size && keptReasons.size == reasons.size) return
        prefs.edit()
            .putString(strikesKey, formatStrikes(keptStrikes))
            .putStringSet(refusedKey, keptRefused)
            .putStringSet(reasonsKey, formatReasons(keptReasons))
            .apply()
    }
}

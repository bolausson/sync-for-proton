package me.proton.sync.calendar

import android.content.SharedPreferences
import me.proton.sync.SyncPreferences
import me.proton.sync.calendar.NewEventHoldRules.Entry
import me.proton.sync.calendar.NewEventHoldRules.State

/**
 * The held new events of one account ([NewEventHoldRules]): one StringSet per account,
 * SyncPreferences.newEventHoldsKey, one string per series, `<state>|<eventId>|<calendarId>|<heldAtMillis>|<writer>|<uid>`.
 * The writer is sanitized ([LocalEventOrigin.sanitizeEntry]), so it holds no '|'; the uid goes last and a decode
 * splits into six parts at most, so a '|' inside it survives. An empty field reads as null; a malformed string is
 * ignored (and kept as it is).
 *
 * Every read-modify-write is ONE putStringSet under [lock]. The user's answers commit, so a decision survives a crash;
 * the sync's own writes apply. Every write fails safe when it is lost: a lost K or P leaves W, so the user is asked
 * again; a lost removal leaves a W whose row has moved, which the next sweep drops.
 *
 * Device-local Android ids, so not in the settings backup's allowlist. The account name is the key's suffix, so an
 * account rename carries it and an account removal drops it (SyncPreferences.ACCOUNT_SCOPED_PREFIXES).
 */
internal object NewEventHoldStore {

    private val lock = Any()

    /** Waiting and kept series, for the account card. Published ones are counted in neither. */
    data class Counts(val waiting: Int, val kept: Int)

    fun encode(e: Entry): String =
        "${e.state.code}|${e.eventId}|${e.calendarId}|${e.heldAtMillis}|${e.writer.orEmpty()}|${e.uid.orEmpty()}"

    fun decode(text: String): Entry? {
        val p = text.split('|', limit = 6)
        if (p.size != 6) return null
        val state = p[0].singleOrNull()?.let { State.of(it) } ?: return null
        val eventId = p[1].toLongOrNull()?.takeIf { it > 0 } ?: return null
        val calendarId = p[2].toLongOrNull() ?: return null
        val heldAt = p[3].toLongOrNull() ?: return null
        return Entry(state, eventId, calendarId, heldAt, p[4].ifEmpty { null }, p[5].ifEmpty { null })
    }

    private fun raw(prefs: SharedPreferences, accountName: String): Set<String> =
        prefs.getStringSet(SyncPreferences.newEventHoldsKey(accountName), null)?.toSet().orEmpty()

    /** Every readable entry of [accountName], oldest first. */
    fun snapshot(prefs: SharedPreferences, accountName: String): List<Entry> =
        raw(prefs, accountName).mapNotNull { decode(it) }.sortedWith(compareBy({ it.heldAtMillis }, { it.eventId }))

    /**
     * Stores the sync's new holds (apply). One entry per parent row: an entry whose row already has one, in any state,
     * is not added.
     */
    fun addHolds(prefs: SharedPreferences, accountName: String, entries: Collection<Entry>) {
        if (entries.isEmpty()) return
        synchronized(lock) {
            val current = raw(prefs, accountName)
            val taken = current.mapNotNullTo(HashSet()) { decode(it)?.let { e -> e.eventId to e.calendarId } }
            val added = entries.filter { taken.add(it.eventId to it.calendarId) }.map { encode(it) }
            if (added.isEmpty()) return
            prefs.edit().putStringSet(SyncPreferences.newEventHoldsKey(accountName), current + added).apply()
        }
    }

    /**
     * Compare-and-set on the exact stored string: replaces [from] with [to] (null removes it) only while [from] is
     * stored unchanged, and returns whether it did. [commit] for the user's answers.
     *
     * commit() changes what this process reads before it writes the file, and its result is the file write's alone: a
     * full or failing disk answers false although the change is in effect. So a false is checked against what is
     * stored now, and true means the change applies. Lost on disk, it falls back to the old entry after a restart,
     * which only asks again.
     */
    fun transition(prefs: SharedPreferences, accountName: String, from: Entry, to: Entry?, commit: Boolean): Boolean =
        synchronized(lock) {
            val current = raw(prefs, accountName)
            val old = encode(from)
            if (old !in current) return@synchronized false
            val new = to?.let { encode(it) }
            val next = current - old + listOfNotNull(new)
            val edit = prefs.edit().putStringSet(SyncPreferences.newEventHoldsKey(accountName), next)
            if (!commit) {
                edit.apply()
                return@synchronized true
            }
            if (edit.commit()) return@synchronized true
            val now = raw(prefs, accountName)
            old !in now && (new == null || new in now)
        }

    /** Whether [entry] is stored exactly as given: an answer or a sweep has not changed or removed it meanwhile. */
    fun isStored(prefs: SharedPreferences, accountName: String, entry: Entry): Boolean =
        encode(entry) in raw(prefs, accountName)

    /**
     * Moves the question of [from] to the row of [to] (NewEventHoldRules.passedOn), a compare-and-set like
     * [transition] (apply). Refused, false, when [from] changed meanwhile or [to]'s row already has an entry.
     */
    fun passOn(prefs: SharedPreferences, accountName: String, from: Entry, to: Entry): Boolean = synchronized(lock) {
        val current = raw(prefs, accountName)
        val old = encode(from)
        if (old !in current) return@synchronized false
        if (current.any { s -> s != old && decode(s)?.let { it.eventId == to.eventId && it.calendarId == to.calendarId } == true }) {
            return@synchronized false
        }
        prefs.edit().putStringSet(SyncPreferences.newEventHoldsKey(accountName), current - old + encode(to)).apply()
        true
    }

    /**
     * Turns every published entry of Android calendar [calendarId] back into a waiting one (apply), and returns them as
     * they are stored now, oldest first: the row belongs to another Proton calendar than the one the user published
     * to, or to none any more (removed on Proton before they went up), so the user is asked again.
     */
    fun unpublishCalendar(prefs: SharedPreferences, accountName: String, calendarId: Long): List<Entry> = synchronized(lock) {
        val current = raw(prefs, accountName)
        val published = current.mapNotNull { s -> decode(s)?.takeIf { it.state == State.PUBLISHED && it.calendarId == calendarId }?.let { s to it } }
        if (published.isEmpty()) return@synchronized emptyList()
        val waiting = published.map { it.second.copy(state = State.WAITING) }
        val next = current - published.map { it.first }.toSet() + waiting.map { encode(it) }
        prefs.edit().putStringSet(SyncPreferences.newEventHoldsKey(accountName), next).apply()
        waiting.sortedWith(compareBy({ it.heldAtMillis }, { it.eventId }))
    }

    /** Removes exactly [expected], as stored (apply); an entry that changed meanwhile stays. */
    fun drop(prefs: SharedPreferences, accountName: String, expected: Collection<Entry>) {
        if (expected.isEmpty()) return
        synchronized(lock) {
            val current = raw(prefs, accountName)
            val gone = expected.mapTo(HashSet()) { encode(it) }
            if (current.none { it in gone }) return
            prefs.edit().putStringSet(SyncPreferences.newEventHoldsKey(accountName), current - gone).apply()
        }
    }

    fun counts(prefs: SharedPreferences, accountName: String): Counts {
        val all = snapshot(prefs, accountName)
        return Counts(all.count { it.state == State.WAITING }, all.count { it.state == State.KEPT })
    }

    /** Removes every entry of [accountName] (apply). */
    fun forget(prefs: SharedPreferences, accountName: String) = synchronized(lock) {
        prefs.edit().remove(SyncPreferences.newEventHoldsKey(accountName)).apply()
    }
}

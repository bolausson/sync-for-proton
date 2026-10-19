package me.proton.sync.crypto

/**
 * Bookkeeping for calendar keys: which ones are already unlocked, and which one each calendar
 * encrypts new events with.
 *
 * Pure JVM, with no crypto in it, so the rules can be tested without the native bridge.
 * [KeyManager.unlockCalendarKey] makes exactly these calls, in this order: [isSettled] to decide
 * whether to do anything, then after a successful unlock [takesRing] under the ring lock, then
 * [recordUnlocked]. Only the decrypt and the ring objects themselves live in KeyManager.
 *
 * It replaces a single "already added" set with two flaws, both found in review:
 *
 * 1. A key was marked added BEFORE its passphrase decrypted. Our loops try each MemberPassphrase in
 *    turn and expect the wrong ones to throw, so when another member's entry came first, the right
 *    attempt was skipped as "already added". On a calendar with several keys that could leave the
 *    primary key out of the encryption slot, and out of the decryption ring, for the whole process.
 * 2. Once a calendar had any encryption key, every already-seen key returned early, so a primary key
 *    could never replace a non-primary one that happened to be unlocked first.
 *
 * The rule now: the key flagged primary IN THE CURRENT LISTING takes the encryption slot from any
 * other key. A first version compared against the holder's primary flag as RECORDED earlier, which
 * went stale after a key reset: with the new primary listed before the demoted old key, the old key
 * still counted as primary, kept the slot for that sync, and kept it for the life of the process if
 * the old key was no longer offered at all (the active-keys listing drops it). Trusting the current
 * listing removes that state. Proton guarantees a single primary key per calendar; if a listing ever
 * flagged two, the last one offered would win on each pass, which is noisy but never wrong about
 * which keys are primary.
 */
internal class CalendarKeySelection {

    private data class Holder(val keyId: String, val isPrimary: Boolean)

    private val unlocked = HashSet<String>()
    private val holders = HashMap<String, Holder>()

    /**
     * True when unlocking [keyId] again could change nothing, so the caller may return early.
     *
     * @param slotHasRing whether the calendar currently has an encryption ring at all. Without one,
     *   nothing is ever settled.
     */
    @Synchronized
    fun isSettled(keyId: String, slot: String, isPrimary: Boolean, slotHasRing: Boolean): Boolean {
        if (!slotHasRing) return false
        if (keyId !in unlocked) return false
        val holder = holders[slot] ?: return false
        // This key holds the slot: settled unless its primary flag changed since it was recorded.
        if (holder.keyId == keyId) return holder.isPrimary == isPrimary
        // Another key holds it: a primary key must take over; a non-primary one has nothing to do.
        return !isPrimary
    }

    /**
     * Whether [keyId], now unlocked, becomes [slot]'s encryption key: when the calendar has no ring
     * yet, or when this key is flagged primary in the current listing and a different key holds the
     * slot. Deliberately NOT gated on the holder's recorded primary flag; see the class comment.
     */
    @Synchronized
    fun takesRing(keyId: String, slot: String, isPrimary: Boolean, slotHasRing: Boolean): Boolean {
        if (!slotHasRing) return true
        val holder = holders[slot] ?: return true
        if (holder.keyId == keyId) return false
        return isPrimary
    }

    /**
     * Record a key that has actually unlocked. Call only after the unlock and any ring change
     * succeeded, never before, or a failed attempt masquerades as success on the next one.
     *
     * @param tookRing true when the caller installed this key as [slot]'s encryption key.
     */
    @Synchronized
    fun recordUnlocked(keyId: String, slot: String, isPrimary: Boolean, tookRing: Boolean) {
        unlocked.add(keyId)
        val holder = holders[slot]
        if (tookRing || holder?.keyId == keyId) holders[slot] = Holder(keyId, isPrimary)
    }

    /** Forget one account's keys (sign-out). Key ids and slots are both prefixed "account:". */
    @Synchronized
    fun clearAccount(accountName: String) {
        val prefix = "$accountName:"
        unlocked.removeAll { it.startsWith(prefix) }
        holders.keys.removeAll { it.startsWith(prefix) }
    }

    @Synchronized
    fun clear() {
        unlocked.clear()
        holders.clear()
    }
}

package me.proton.sync.contacts

/**
 * How much one write to the contacts provider may carry, and how a contact too big for one write is
 * divided.
 *
 * Every applyBatch or insert travels over Binder, whose buffer in the provider's process is about 1 MB
 * and is shared by every app talking to the contacts provider at that moment. A call larger than what
 * is free fails with TransactionTooLargeException ("data parcel size N bytes"). Text travels as UTF-16,
 * two bytes per character, so 1.1 MB of notes made a 2.2 MB call; before this budget such a contact
 * failed on every sync with only a log line to show for it.
 *
 * "What is free" is the catch: on a Note 10+ a 500 KB call was refused once during a forced reload and
 * accepted on the next, so the budget keeps well clear of the limit and a refused call is retried
 * ([RETRY_DELAYS_MS]) before a contact counts as too large.
 */
internal object ContactWriteBudget {

    /** Parcel bytes one provider call may carry; an ordinary contact is a few KB. */
    const val CALL_BYTES = 256_000

    /** Pauses before trying a refused call again, a busy buffer usually being free again at once. */
    val RETRY_DELAYS_MS = longArrayOf(100, 400, 1_200)

    /**
     * The most one call is ever tried with: an existing contact (never split) or a single row. Short of
     * the buffer's ~1 MB for the call's own framing, and usually free; when it is not, the provider is
     * never reached and nothing is applied.
     */
    const val SINGLE_CALL_MAX_BYTES = 900_000

    /**
     * Consecutive index ranges over [sizes], in order (rows keep their order), each summing to at most
     * [budget]: greedy, a new range starting whenever the next element would overflow the current one.
     * An element that alone exceeds the budget still gets a range of its own; callers reject anything
     * over [SINGLE_CALL_MAX_BYTES] before writing, since no call can carry it.
     */
    fun chunk(sizes: List<Int>, budget: Int = CALL_BYTES): List<IntRange> {
        val ranges = mutableListOf<IntRange>()
        var start = 0
        var sum = 0L
        for ((i, size) in sizes.withIndex()) {
            if (i > start && sum + size > budget) {
                ranges += start until i
                start = i
                sum = 0
            }
            sum += size
        }
        if (start < sizes.size) ranges += start until sizes.size
        return ranges
    }
}

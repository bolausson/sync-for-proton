package me.proton.sync.calendar

/**
 * The re-time pass's bookkeeping for one account, stored under SyncPreferences.timeZoneRetimeKey: which rule
 * identity (TimeZoneRuleSource.retimeIdentity) its stored event times were last checked against, and how that
 * went. Pure, so the JVM tests pin it.
 *
 *  - "<identity>": checked; nothing more to do until the identity changes.
 *  - "<identity>#<n>": the pass failed n times (an exception); it runs again at the next sync, and is given up
 *    after [MAX_FAILURES] until the identity changes.
 *  - "<identity>#u<n>@<process>": incomplete. Some rows needed rules that could not be had in process <process>
 *    (a tzurl.org fetch that failed, or rules from an older tz release than the newest one seen), or the download
 *    wrote such rows with the phone's rules instead. A zone is fetched at most once per process, so nothing can
 *    change within that process: the pass runs again in the next one, at most [MAX_INCOMPLETE] times, then the
 *    identity is stored as checked.
 */
internal object RetimeState {
    const val MAX_FAILURES = 3
    const val MAX_INCOMPLETE = 10

    sealed class Stored {
        object Never : Stored()
        object Done : Stored()
        class Failed(val count: Int) : Stored()
        class Incomplete(val count: Int, val process: String) : Stored()

        /** Another identity's state, or unreadable: the identity changed, so it starts over. */
        object Other : Stored()
    }

    fun parse(stored: String?, identity: String): Stored {
        if (stored == null) return Stored.Never
        if (stored == identity) return Stored.Done
        if (!stored.startsWith("$identity#")) return Stored.Other
        val suffix = stored.substring(identity.length + 1)
        if (suffix.startsWith("u")) {
            val at = suffix.indexOf('@')
            if (at < 0) return Stored.Other
            val count = suffix.substring(1, at).toIntOrNull() ?: return Stored.Other
            return Stored.Incomplete(count, suffix.substring(at + 1))
        }
        return suffix.toIntOrNull()?.let { Stored.Failed(it) } ?: Stored.Other
    }

    /** Whether the pass runs now, in the process [process]. */
    fun shouldRun(stored: String?, identity: String, process: String): Boolean = when (val s = parse(stored, identity)) {
        Stored.Done -> false
        is Stored.Incomplete -> s.process != process
        else -> true
    }

    /**
     * The value after a pass that finished. [identityAfter] is taken after it (its fetches may have seen a newer
     * tzurl release); [incomplete] when it left rows unchecked for want of rules.
     */
    fun afterRun(stored: String?, identityBefore: String, identityAfter: String, incomplete: Boolean, process: String): String {
        if (!incomplete) return identityAfter
        val previous = if (identityAfter == identityBefore) (parse(stored, identityBefore) as? Stored.Incomplete)?.count ?: 0 else 0
        val count = previous + 1
        return if (count >= MAX_INCOMPLETE) identityAfter else "$identityAfter#u$count@$process"
    }

    /** The value after the pass threw. */
    fun afterFailure(stored: String?, identity: String): String {
        val failures = ((parse(stored, identity) as? Stored.Failed)?.count ?: 0) + 1
        return if (failures >= MAX_FAILURES) identity else "$identity#$failures"
    }

    /**
     * The value after this process's download wrote times with fallback rules, or null to leave it as it is: a
     * checked account is marked incomplete, so the next process checks those rows again.
     */
    fun afterDownloadFallback(stored: String?, identity: String, process: String): String? =
        when (val s = parse(stored, identity)) {
            Stored.Done -> "$identity#u0@$process"
            is Stored.Incomplete -> if (s.process == process) null else "$identity#u${s.count}@$process"
            // Never checked, failed, or another identity: the pass runs at the next sync anyway.
            else -> null
        }
}

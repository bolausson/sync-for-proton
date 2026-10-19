package me.proton.sync.calendar

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.zone.ZoneRules
import java.util.concurrent.ConcurrentHashMap

/**
 * The "ON" rule source of "Update time zones online": current rules for one zone, fetched from www.tzurl.org
 * and interpreted by [VtimezoneRules], or null to say "use the phone's own rules".
 *
 * Pure: the fetch, the phone's tz data version and its rules are passed in, so the JVM tests drive every gate.
 * The rules are used only when ALL of these hold, and any doubt falls back to the phone (and is logged once):
 *  - the TZID is on Proton's own list (ProtonTimezones.SUPPORTED) and looks like a zone name, so nothing an
 *    invitation's text could invent reaches the request URL;
 *  - the fetch worked and [VtimezoneRules] accepted the text (anything it throws, an Error included, is a
 *    failed answer);
 *  - the answer is for the zone asked for: the same TZID, or (tzurl answers a link with its target, Europe/Oslo
 *    as Europe/Berlin) a zone the phone's own data has identical to it since 1970, which is exactly IANA's rule
 *    for turning a zone into a link, or the zone's other name after an IANA rename;
 *  - tzurl's tz release is NEWER than the phone's own: tzurl lags IANA at times (2026e, Manitoba, was not on it
 *    a week after release), so on an up-to-date phone it would make some zones worse;
 *  - the rules agree with the phone's on the 15th of every month from 2000 to 2015, which settled history no
 *    current tz release disagrees on (only Antarctica/Vostok and Asia/Choibalsan were rewritten since, and fall
 *    back), and stay within [MAX_FUTURE_DIFFERENCE_HOURS] hours of the phone's every month after that: a
 *    garbage answer that still parses, or a wrong zone that happens to share 2000-2015, is caught here.
 *
 * At most one attempt per TZID per process, success or failure (the map below, never evicted). Once a response
 * shows tzurl is not newer than the phone, nothing more is fetched in this process at all: tzurl serves one
 * release for every zone, so every further request would only reveal more zone names for nothing.
 *
 * tzurl.org sits behind a CDN that caches each zone for hours, so right after a tz release one process can get
 * different releases for different zones. [hasStaleEntries] tells: rules from an older release than the newest
 * seen are still used (they are newer than the phone's), but the re-time pass then runs again in the next
 * process, which fetches every zone afresh (RetimeState).
 */
internal class OnlineTimeZoneRules(
    /** The body of https://www.tzurl.org/zoneinfo/<tzid>; throws on any failure. */
    private val fetch: (String) -> String,
    /** The phone's own tz data version ("2026c"), null when unknown. */
    private val phoneVersion: () -> String?,
    /** The phone's own rules for a TZID, null when it does not know it. */
    private val phoneRules: (String) -> ZoneRules?,
    /** False where a request must not be made (the main thread): answer from the cache only, cache nothing. */
    private val canFetch: () -> Boolean = { true },
    /** Each tz release a response carried, so the re-time pass can tell when tzurl's data moved on. */
    private val onVersionSeen: (String) -> Unit = {},
    private val log: (String) -> Unit = {}
) {
    sealed class Entry {
        class Ok(val rules: ZoneRules, val olsonVersion: String, val servedTzid: String) : Entry()
        /** [transient]: the fetch itself failed (network, HTTP), not a deliberate fallback to the phone. */
        class Failed(val reason: String, val transient: Boolean = false) : Entry()
    }

    private val cache = ConcurrentHashMap<String, Entry>()
    private val locks = ConcurrentHashMap<String, Any>()

    @Volatile
    private var stoppedBecause: String? = null

    /** The newest tz release any answer in this process carried. */
    @Volatile
    private var newestSeen: String? = null

    /** The online rules for [tzid], or null for the phone's own. May fetch: never call on the main thread. */
    fun rulesFor(tzid: String): ZoneRules? {
        cache[tzid]?.let { return (it as? Entry.Ok)?.rules }
        if (stoppedBecause != null || !canFetch()) return null
        if (!isFetchable(tzid)) {
            cache.putIfAbsent(tzid, Entry.Failed("not a zone on Proton's list"))
            return null
        }
        val lock = locks.getOrPut(tzid) { Any() }
        synchronized(lock) {
            cache[tzid]?.let { return (it as? Entry.Ok)?.rules }
            if (stoppedBecause != null) return null
            val entry = load(tzid)
            cache[tzid] = entry
            return (entry as? Entry.Ok)?.rules
        }
    }

    /** What happened to each TZID in this process, for the debug verbs. */
    fun snapshot(): Map<String, String> = cache.mapValues { (_, e) ->
        when (e) {
            is Entry.Ok -> "online ${e.olsonVersion}" + if (e.servedTzid.isNotEmpty()) " (as ${e.servedTzid})" else ""
            is Entry.Failed -> "phone: ${e.reason}"
        }
    }

    /** Why nothing more is fetched in this process, or null. */
    val stopReason: String? get() = stoppedBecause

    /**
     * Whether rules in use came from an older tz release than the newest one seen in this process (the CDN still
     * served the old file for that zone). They stay in use, being newer than the phone's, but times computed with
     * them may change once the zone is fetched afresh in another process.
     */
    fun hasStaleEntries(): Boolean {
        val newest = newestSeen ?: return false
        return cache.values.any { it is Entry.Ok && (compareVersions(it.olsonVersion, newest) ?: 0) < 0 }
    }

    /**
     * Whether [tzid]'s online rules should apply but cannot be had right now: its fetch failed in this run, or
     * no fetch may be made here. A deliberate fallback (not newer, sanity check, not on Proton's list) is not.
     */
    fun isTransientlyUnavailable(tzid: String): Boolean = when (val entry = cache[tzid]) {
        is Entry.Ok -> false
        is Entry.Failed -> entry.transient
        null -> stoppedBecause == null && isFetchable(tzid) && !canFetch()
    }

    private fun load(tzid: String): Entry {
        val body = try {
            fetch(tzid)
        } catch (e: Exception) {
            return failed(tzid, "fetch failed (${e.javaClass.simpleName}${e.message?.let { ": $it" } ?: ""})", transient = true)
        }
        val built = try {
            VtimezoneRules.build(body)
        } catch (e: Throwable) {
            // Anything, an Error included: an answer this reader cannot take is a failed answer, never a crash.
            return failed(tzid, "unusable answer (${e.javaClass.simpleName}: ${e.message})")
        }
        val online = built.olsonVersion ?: return failed(tzid, "the answer names no tz release")
        noteVersion(online)
        onVersionSeen(online)
        val phone = phoneVersion()
        if (phone == null || compareVersions(online, phone)?.let { it > 0 } != true) {
            val reason = "tzurl.org has $online, not newer than this phone's ${phone ?: "unknown"}"
            stoppedBecause = reason
            log("Online time zone rules not used: $reason; no more requests in this run")
            return Entry.Failed(reason)
        }
        val phoneZone = phoneRules(tzid) ?: return failed(tzid, "the phone does not know the zone")
        if (built.tzid != tzid && !sameZone(tzid, built.tzid)) {
            return failed(tzid, "the answer is for ${built.tzid}, which this phone does not have identical to it")
        }
        disagreement(built.rules, phoneZone)?.let { return failed(tzid, "disagrees with this phone on $it") }
        futureDisagreement(built.rules, phoneZone)?.let {
            return failed(tzid, "differs from this phone by more than $MAX_FUTURE_DIFFERENCE_HOURS hours on $it")
        }
        val served = if (built.tzid != tzid) built.tzid else ""
        log("Online time zone rules for $tzid: tzurl.org $online over this phone's $phone" +
            if (served.isNotEmpty()) " (served as $served)" else "")
        return Entry.Ok(built.rules, online, served)
    }

    private fun failed(tzid: String, reason: String, transient: Boolean = false): Entry {
        log("Online time zone rules for $tzid not used, the phone's apply: $reason")
        return Entry.Failed(reason, transient)
    }

    @Synchronized
    private fun noteVersion(version: String) {
        val current = newestSeen
        if (current == null || (compareVersions(version, current) ?: 0) > 0) newestSeen = version
    }

    /**
     * Whether tzurl's answer for [requested], given for [served], is the same zone: IANA renamed it (Kiev and
     * Kyiv), or the phone's own data has both identical since 1970, the rule by which IANA makes a zone a link
     * (Oslo answered as Berlin). A zone the phone does not know is never the same.
     */
    private fun sameZone(requested: String, served: String): Boolean {
        if (EventZones.isRename(requested, served)) return true
        val a = phoneRules(requested) ?: return false
        val b = phoneRules(served) ?: return false
        return identicalSince1970(a, b)
    }

    companion object {
        /** How far, in hours, the online rules may move a time from the phone's after 2015 (Samoa's 24 was 2011). */
        const val MAX_FUTURE_DIFFERENCE_HOURS = 3

        private val EPOCH_1970: Instant = Instant.parse("1970-01-01T00:00:00Z")
        private val UNTIL_2038: Instant = Instant.parse("2038-01-01T00:00:00Z")

        /** Whether [a] and [b] give the same offset at every instant from 1970 to 2037. */
        fun identicalSince1970(a: ZoneRules, b: ZoneRules): Boolean {
            if (a.getOffset(EPOCH_1970) != b.getOffset(EPOCH_1970)) return false
            // Both are constant between their transitions, so equal at every transition of either means equal.
            for (rules in listOf(a, b)) {
                var t = EPOCH_1970
                while (true) {
                    val next = rules.nextTransition(t) ?: break
                    if (!next.instant.isBefore(UNTIL_2038)) break
                    t = next.instant
                    if (a.getOffset(t) != b.getOffset(t)) return false
                }
            }
            return true
        }

        /**
         * The first probe (the 15th of each month from 2016 to the horizon, at 12:00 UTC) where [online] is more
         * than [MAX_FUTURE_DIFFERENCE_HOURS] hours from [phone]. A real rule change moves a zone by an hour or
         * two; more is a garbage answer.
         */
        fun futureDisagreement(online: ZoneRules, phone: ZoneRules): String? {
            for (year in 2016 until VtimezoneRules.HORIZON_YEAR) for (month in 1..12) {
                val probe: Instant = LocalDate.of(year, month, 15).atTime(12, 0).toInstant(ZoneOffset.UTC)
                val diff = Math.abs(online.getOffset(probe).totalSeconds - phone.getOffset(probe).totalSeconds)
                if (diff > MAX_FUTURE_DIFFERENCE_HOURS * 3600) {
                    return String.format(java.util.Locale.ROOT, "%04d-%02d-15", year, month)
                }
            }
            return null
        }

        private val ZONE_NAME = Regex("^[A-Za-z]+(/[A-Za-z0-9_+-]+)+$")

        fun isFetchable(tzid: String): Boolean =
            tzid != "UTC" && tzid in ProtonTimezones.SUPPORTED && ZONE_NAME.matches(tzid)

        private val VERSION = Regex("^(\\d{4})([a-z]{1,2})$")

        /** Compare two tz releases ("2026c" < "2026d" < "2027a"); null when either is not one. */
        fun compareVersions(a: String, b: String): Int? {
            val ma = VERSION.matchEntire(a.trim()) ?: return null
            val mb = VERSION.matchEntire(b.trim()) ?: return null
            val years = ma.groupValues[1].toInt().compareTo(mb.groupValues[1].toInt())
            if (years != 0) return years
            val la = ma.groupValues[2]
            val lb = mb.groupValues[2]
            return if (la.length != lb.length) la.length.compareTo(lb.length) else la.compareTo(lb)
        }

        /** The first probe (the 15th of each month, 2000 to 2015, at 12:00 UTC) where the offsets differ. */
        fun disagreement(online: ZoneRules, phone: ZoneRules): String? {
            for (year in 2000..2015) for (month in 1..12) {
                val probe: Instant = LocalDate.of(year, month, 15).atTime(12, 0).toInstant(ZoneOffset.UTC)
                if (online.getOffset(probe) != phone.getOffset(probe)) {
                    return String.format(java.util.Locale.ROOT, "%04d-%02d-15", year, month)
                }
            }
            return null
        }
    }
}

/**
 * One sync run's choice of zone rules ([ZoneRulesLookup]), taken once at its start so a switch flipped
 * mid-run cannot mix sources inside one run.
 *
 * OFF ([online] false): the phone's own rules for everything. ON: [OnlineTimeZoneRules] for STANDALONE times
 * only (a one-off event's start and end, an override's own start and end) in local years 1900 to 2099, and
 * the phone's for everything Android expands itself. A series written with other rules than the phone's would
 * shift every occurrence's wall time and unlink its overrides and exclusions, which the provider matches by
 * the exact millisecond: duplicates and cancelled occurrences coming back.
 */
internal class RuleSnapshot(val online: Boolean, private val onlineRules: OnlineTimeZoneRules?) : ZoneRulesLookup {

    private val fallbacks: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    override fun rulesFor(zone: java.time.ZoneId, standalone: Boolean, local: java.time.LocalDateTime): ZoneRules {
        if (!standalone) return zone.rules
        onlineFor(zone, local.year)?.let { return it }
        if (onlineUnavailable(zone, local.year)) fallbacks.add(zone.id)
        return zone.rules
    }

    /**
     * Zones whose standalone times this run wrote with the phone's rules although the online rules should have
     * applied (their fetch failed): those rows must be checked again once the rules can be had (RetimeState).
     */
    val transientFallbacks: Set<String> get() = fallbacks

    /** Whether this run used online rules from an older tz release than the newest one it saw (a CDN's stale copy). */
    fun usedStaleOnlineRules(): Boolean = online && onlineRules?.hasStaleEntries() == true

    /** The online rules for a standalone time in [zone] in local [year], or null for the phone's own. */
    fun onlineFor(zone: java.time.ZoneId, year: Int): ZoneRules? =
        if (online && onlineRules != null && year in 1900 until VtimezoneRules.HORIZON_YEAR) {
            onlineRules.rulesFor(zone.id)
        } else {
            null
        }

    /**
     * True when a standalone time in [zone] should use the online rules but they failed to load in this run.
     * The download then falls back to the phone's (as decided); the re-time pass leaves such rows alone.
     */
    fun onlineUnavailable(zone: java.time.ZoneId, year: Int): Boolean {
        if (!online || onlineRules == null || year !in 1900 until VtimezoneRules.HORIZON_YEAR) return false
        if (onlineRules.rulesFor(zone.id) != null) return false
        return onlineRules.isTransientlyUnavailable(zone.id)
    }

    companion object {
        /** The phone's rules for everything: OFF, and anything that must not touch the network. */
        val PHONE_ONLY = RuleSnapshot(false, null)
    }
}

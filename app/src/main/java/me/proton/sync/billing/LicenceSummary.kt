package me.proton.sync.billing

import android.content.SharedPreferences
import me.proton.sync.SyncPreferences
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * One line for the debug log's header: the subscription as this install last stored it, read like
 * [SubscriptionGate] from the keys both flavours write, so it says whether sync is off for licence
 * reasons without a question to the user. For example
 * `Active until 2026-11-23, cancel scheduled for 2026-11-23; id stored, confirmed; state saved 2026-10-24 08:15 UTC`.
 *
 * Read-only: it never writes or migrates a setting (the direct version's licence check migrates
 * [SyncPreferences.KEY_LICENSE_CONFIRMED] when it reads it; a log must not change what it reports). And
 * never the subscription id: only whether one is stored and whether it has the form of one, so a
 * mistyped entry shows up without being printed.
 *
 * "State saved" is when the state was last written: on a change in the direct version, on every
 * successful check in the play version. Times are in UTC, with the zone written out.
 */
object LicenceSummary {

    /** The licence proxy's own check of a Paddle subscription id (its SUB_ID pattern). */
    private val PADDLE_SUB_ID = Regex("^sub_[0-7][0-9a-hjkmnp-tv-z]{25}$")

    /** The state names both subscription managers store; anything else is not printed as it is. */
    private val STATE_NAMES = setOf("Unknown", "NoSubscription", "Trial", "Active", "Grace", "Expired")

    private val DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC)
    private val MINUTE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC)

    /**
     * The line for [prefs]. [paddle] adds the direct version's part: the stored id, its confirmation and a
     * run of failing checks. A setting of the wrong type gives "unreadable (<exception class>)" rather than
     * an exception, because the log has to be built whatever state the settings are in.
     */
    fun line(prefs: SharedPreferences, paddle: Boolean): String = try {
        buildString {
            val state = prefs.getString(SyncPreferences.KEY_SUBSCRIPTION_STATE, null)
            append(
                when (state) {
                    null -> "not checked yet"
                    in STATE_NAMES -> state
                    else -> "unrecognised state"
                }
            )
            val expiry = prefs.getLong(SyncPreferences.KEY_SUBSCRIPTION_EXPIRY, 0L)
            if (expiry > 0L) append(" until ").append(day(expiry))
            if (prefs.getBoolean(SyncPreferences.KEY_SUBSCRIPTION_PAUSED, false)) append(", paused")
            if (prefs.getBoolean(SyncPreferences.KEY_SUBSCRIPTION_PAYMENT_FAILED, false)) append(", payment failed")
            ScheduledChangeStore.read(prefs)?.let {
                append(", ").append(it.action.wire).append(" scheduled for ").append(day(it.effectiveAtMs))
            }
            if (paddle) {
                append("; ").append(idPart(prefs))
                val firstFailed = prefs.getLong(SyncPreferences.KEY_LICENSE_FIRST_FAILED, 0L)
                if (firstFailed > 0L) append("; checks failing since ").append(minute(firstFailed))
            }
            val saved = prefs.getLong(SyncPreferences.KEY_SUBSCRIPTION_LAST_CHECK, 0L)
            if (saved > 0L) append("; state saved ").append(minute(saved))
        }
    } catch (e: Exception) {
        "unreadable (${e.javaClass.simpleName})"
    }

    /** Whether an id is stored, whether it has the form of one (else its length), and whether the proxy accepted it. */
    private fun idPart(prefs: SharedPreferences): String {
        val id = prefs.getString(SyncPreferences.KEY_PADDLE_SUB_ID, null)?.trim().orEmpty()
        if (id.isEmpty()) return "no subscription id stored"
        val shape = if (PADDLE_SUB_ID.matches(id.lowercase())) "" else ", not in sub_ form (${id.length} characters)"
        val confirmation = when {
            !prefs.contains(SyncPreferences.KEY_LICENSE_CONFIRMED) -> "confirmation not recorded"
            prefs.getBoolean(SyncPreferences.KEY_LICENSE_CONFIRMED, false) -> "confirmed"
            else -> "unconfirmed"
        }
        return "id stored$shape, $confirmation"
    }

    private fun day(ms: Long): String = DAY.format(Instant.ofEpochMilli(ms))

    private fun minute(ms: Long): String = MINUTE.format(Instant.ofEpochMilli(ms))
}

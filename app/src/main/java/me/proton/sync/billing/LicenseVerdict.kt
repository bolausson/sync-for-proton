package me.proton.sync.billing

import org.json.JSONObject
import java.time.Instant

/**
 * What one answer from the licence proxy means for this install. Pure, so it can be tested.
 *
 * The proxy (`/validate`, see [SubscriptionManager] in the direct flavour) passes Paddle's view of the
 * subscription through. Each outcome does something different to the stored subscription id and to sync:
 *
 *  - [Entitled]: sync runs. Paddle's `past_due` lands here too, flagged [Entitled.paymentFailed]: for a
 *    subscription that has paid before, Paddle keeps retrying the card for up to 30 days and its own
 *    guidance is to keep full access meanwhile, so sync continues and the app asks for a new card. A
 *    cancellation or pause Paddle has scheduled comes along as [Entitled.scheduledChange].
 *  - [Unpaid]: past due on a subscription that has never paid (the first charge after the trial
 *    failed), or has been past due for more than 35 days; the proxy reports both as
 *    `subscription_unpaid`. No sync, by design, but the id is KEPT: once the card is fixed and Paddle
 *    collects, the next licence check brings sync back without the user finding and pasting the id again.
 *  - [Suspended]: a paused subscription. Sync stops, the id is kept and re-checked, because Paddle can
 *    resume it. A seller can set Paddle's dunning to end in a pause instead of a cancel.
 *  - [Ended]: gone for good. The id is forgotten; a new purchase brings a new one.
 *  - [Unknown]: nothing was decided. The proxy or Paddle could not be reached, or the answer is one
 *    this app cannot trust. The cached state holds within the offline grace window.
 */
internal sealed class LicenseVerdict {
    data class Entitled(
        val trial: Boolean,
        val paymentFailed: Boolean,
        val periodEndsAtMs: Long?,
        val scheduledChange: ScheduledChange? = null,
    ) : LicenseVerdict()
    // data objects, so a log line that prints a verdict reads "Unpaid", not "LicenseVerdict$Unpaid@bf254cc".
    data object Unpaid : LicenseVerdict()
    data object Suspended : LicenseVerdict()
    data class Ended(val error: String) : LicenseVerdict()
    data class Unknown(val error: String) : LicenseVerdict()
}

/** Proxy errors that mean "could not find out", as opposed to an answer about the subscription. */
internal val TRANSIENT_LICENSE_ERRORS = setOf("paddle_unreachable", "paddle_server_error", "network_error", "not_ready")

/**
 * The `reason` sent to the proxy. "activation" until the proxy has confirmed this id once, "refresh"
 * after. The proxy answers the two differently: on a refresh it reports a subscription it cannot find
 * as a server fault rather than a refusal, because a subscription that validated before cannot
 * disappear. An id pasted while the proxy was out of reach has not validated yet, so it keeps asking
 * as an activation, and a mistyped id still gets its plain "not found".
 */
internal fun licenseReason(confirmed: Boolean): String = if (confirmed) "refresh" else "activation"

/**
 * Whether an install that predates the confirmed flag had its id confirmed. Only a cached Trial or
 * Active proves the proxy accepted it; anything else asks as an activation once. Decided once, from
 * the state at upgrade time, and then stored: see SubscriptionManager.licenseConfirmed.
 */
internal fun licenseConfirmedByDefault(cachedStateName: String?): Boolean =
    cachedStateName == "Trial" || cachedStateName == "Active"

/**
 * Classify one proxy response. [httpCode] is the HTTP status, [body] the response text, [confirmed]
 * whether the proxy has accepted this id before.
 *
 * Only refusals the proxy really sends can end a licence, and most of them only for an id that has
 * never been confirmed. Once it has, Paddle cannot lose a subscription or move it to another product,
 * so "not found", "wrong product", "inactive" or "invalid request" can only come from a proxy or
 * configuration fault, and deleting the id then would make every user paste it again. Those become
 * [LicenseVerdict.Unknown] and fall under the offline grace instead. A cancellation is Paddle's own
 * status and ends the licence either way.
 *
 * A 5xx, an empty or non-JSON body, and JSON without an `error` are [LicenseVerdict.Unknown] too.
 */
internal fun classifyLicenseAnswer(httpCode: Int, body: String?, confirmed: Boolean): LicenseVerdict {
    if (httpCode >= 500) return LicenseVerdict.Unknown("paddle_server_error")
    if (body.isNullOrBlank()) return LicenseVerdict.Unknown("bad_response")
    val json = try {
        JSONObject(body)
    } catch (e: Exception) {
        return LicenseVerdict.Unknown("bad_response")
    }
    if (json.optBoolean("ok", false) && json.optBoolean("valid", false)) {
        val status = json.stringOrNull("status") ?: "active"
        return LicenseVerdict.Entitled(
            trial = status == "trialing",
            paymentFailed = status == "past_due",
            periodEndsAtMs = parseInstantMs(json.stringOrNull("current_period_ends_at")),
            scheduledChange = scheduledChangeOf(json),
        )
    }
    val error = json.stringOrNull("error") ?: ""
    return when {
        error in TRANSIENT_LICENSE_ERRORS -> LicenseVerdict.Unknown(error)
        httpCode == 200 && error == "subscription_paused" -> LicenseVerdict.Suspended
        httpCode == 200 && error == "subscription_unpaid" -> LicenseVerdict.Unpaid
        httpCode == 200 && error == "subscription_canceled" -> LicenseVerdict.Ended(error)
        !confirmed && httpCode == 200 && error.startsWith("subscription_") -> LicenseVerdict.Ended(error)
        !confirmed && httpCode == 400 && error == "invalid_request" -> LicenseVerdict.Ended(error)
        confirmed && error.isNotBlank() -> LicenseVerdict.Unknown("unconfirmed_$error")
        else -> LicenseVerdict.Unknown(if (error.isBlank()) "bad_response" else "unexpected_$error")
    }
}

/** What the payment-failed notification should do when the state changes from [before] to [after]. */
internal enum class PaymentNotice { SHOW, CLEAR, NONE }

/**
 * Shown on the way into a failed payment, and not repeated while it lasts (the state is persisted, so
 * an app restart does not count as "on the way in"). Shown again, replacing the first, when sync turns
 * off or back on during the episode: a renewal that stayed past due too long stops syncing, and the
 * notice that said "update payment method to keep syncing" would then be wrong and the stop silent.
 * Cleared on the way out.
 */
internal fun paymentNoticeChange(before: SubscriptionState, after: SubscriptionState): PaymentNotice {
    val was = before.paymentFailed()
    val now = after.paymentFailed()
    return when {
        now && !was -> PaymentNotice.SHOW
        now && before.isActive != after.isActive -> PaymentNotice.SHOW
        was && !now -> PaymentNotice.CLEAR
        else -> PaymentNotice.NONE
    }
}

private fun SubscriptionState.paymentFailed(): Boolean =
    (this is SubscriptionState.Active && paymentFailed) || (this is SubscriptionState.Expired && paymentFailed)

/**
 * The text of [name] when it is a JSON string that is not blank, and null otherwise: missing, a JSON null,
 * another type, or blank. Every text the direct flavour reads from the licence proxy goes through here.
 *
 * Not optString: for a JSON null, Android's org.json returns the text "null" from it (a link "null", an error
 * code "null"), where the org.json the unit tests run on returns the fallback, so no test would notice.
 */
internal fun JSONObject.stringOrNull(name: String): String? = (opt(name) as? String)?.takeIf { it.isNotBlank() }

/**
 * `scheduled_change` of a /validate answer, `{action, effective_at}` or null. Only a cancellation or a pause
 * with a date that can be read is kept; see [ScheduledChange].
 */
private fun scheduledChangeOf(json: JSONObject): ScheduledChange? {
    val change = json.optJSONObject("scheduled_change") ?: return null
    val action = ScheduledChange.Action.fromWire(change.stringOrNull("action")) ?: return null
    val at = parseInstantMs(change.stringOrNull("effective_at")) ?: return null
    return ScheduledChange(action, at)
}

private fun parseInstantMs(s: String?): Long? =
    if (s.isNullOrBlank()) null else try { Instant.parse(s).toEpochMilli() } catch (e: Exception) { null }

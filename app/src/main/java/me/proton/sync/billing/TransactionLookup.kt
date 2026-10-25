package me.proton.sync.billing

import org.json.JSONObject

/**
 * What the licence proxy's /txn-to-sub said about a transaction id entered in the direct version's subscription
 * dialog ([SubscriptionEntry.ValidTransaction]). Pure, so it can be tested; SubscriptionManager.activate asks.
 *
 * Paddle's invoice carries the transaction id (txn_), not the subscription id (sub_) the app needs. The proxy
 * looks up, read-only, which subscription of this product the transaction paid for:
 *  - [Resolved]: that subscription's id, which is then checked as if it had been entered.
 *  - [Refused]: no such subscription, or one of another product.
 *  - [NotReady]: a transaction of this product that names no subscription. Right after a payment that is
 *    Paddle still creating it, worth another try in a minute; a checkout never paid (abandoned, declined,
 *    canceled) stays so for good. The proxy does not tell the two apart, so the dialog says both.
 *  - [Unknown]: nothing was decided. A fault on the proxy's or Paddle's side, no connection, or an answer that
 *    is not the proxy's.
 * None of them stores anything: only the check of a [Resolved] id can.
 */
internal sealed class TransactionLookup {
    data class Resolved(val subId: String) : TransactionLookup() {
        // The id is a licence key: logging the object must never print it.
        override fun toString() = "Resolved"
    }
    data class Refused(val error: String) : TransactionLookup()
    data object NotReady : TransactionLookup()
    data class Unknown(val error: String) : TransactionLookup()
}

// The proxy's SUB_ID, the shape [parseSubscriptionEntry] takes a subscription id in: lower case, Crockford base32,
// first character 0-7.
private val SUB_ID = Regex("sub_[0-7][0-9a-hjkmnp-tv-z]{25}")

/**
 * Classify one /txn-to-sub response. [httpCode] is the HTTP status, [body] the response text.
 *
 * The proxy (contract 3) answers 200 `{ok:true, subscription_id, status}`; 200 `{ok:false, error}` with
 * `subscription_not_found` (a transaction Paddle does not know), `subscription_wrong_product` or `not_ready`
 * (one of this product with no subscription, whatever its status); 400 `invalid_request` for text that is not
 * shaped like a transaction id; and a 5xx for a fault. Only that subscription_id is ever sent on, so one that is not exactly
 * the shape a typed id must have is not trusted: a missing or broken one is [TransactionLookup.Unknown], like a
 * 5xx, a body that is not JSON, or a refusal the proxy does not send.
 */
internal fun classifyTxnAnswer(httpCode: Int, body: String?): TransactionLookup {
    val json = try {
        if (body.isNullOrBlank()) null else JSONObject(body)
    } catch (e: Exception) {
        null
    }
    if (httpCode >= 500) return TransactionLookup.Unknown(json?.stringOrNull("error") ?: "server_error")
    if (json == null) return TransactionLookup.Unknown("bad_response")
    if (json.optBoolean("ok", false)) {
        if (httpCode != 200) return TransactionLookup.Unknown("unexpected_ok")
        val subId = json.stringOrNull("subscription_id")
        return if (subId != null && SUB_ID.matches(subId)) TransactionLookup.Resolved(subId)
        else TransactionLookup.Unknown("bad_response")
    }
    val error = json.stringOrNull("error")
    return when {
        httpCode == 200 && (error == "subscription_not_found" || error == "subscription_wrong_product") ->
            TransactionLookup.Refused(error)
        httpCode == 400 && error == "invalid_request" -> TransactionLookup.Refused(error)
        httpCode == 200 && error == "not_ready" -> TransactionLookup.NotReady
        else -> TransactionLookup.Unknown(if (error == null) "bad_response" else "unexpected_$error")
    }
}

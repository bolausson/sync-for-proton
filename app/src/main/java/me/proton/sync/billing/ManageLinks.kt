package me.proton.sync.billing

import org.json.JSONObject
import java.net.URI
import java.util.Locale

/**
 * What one /manage-url answer from the licence proxy offers the direct flavour's Manage subscription and Update
 * payment method buttons. Pure, so it can be tested.
 *
 * Since the proxy's contract 3 the links are Paddle customer-portal pages without a sign-in token: whoever opens
 * one is asked for the email address the subscription was bought with, and Paddle emails a sign-in link to it.
 */
internal sealed class ManageLinks {
    /**
     * The links the app may open ([isAllowedBillingLink]), at least one of them; a link Paddle does not offer is
     * null. [refused] names the links the answer also carried that the app does not open, for the log.
     */
    data class Offered(
        val updatePaymentMethod: String?,
        val cancel: String?,
        val refused: List<String> = emptyList(),
    ) : ManageLinks()

    /** Paddle offers nothing to manage: the proxy's `manage_urls_unavailable`, or no link at all. */
    data class NothingToManage(val reason: String) : ManageLinks()

    /**
     * No usable answer: a fault on the proxy's or Paddle's side (any 5xx, links the proxy itself found unusable
     * among them), a refusal, a body that is not the proxy's JSON, or only links the app does not open.
     */
    data class Unavailable(val reason: String) : ManageLinks()

    /** The proxy could not be reached: no network, an unknown host, a timeout. */
    data class NoConnection(val reason: String) : ManageLinks()
}

/**
 * Classify one /manage-url response. [httpCode] is the HTTP status, [body] the response text.
 *
 * The proxy answers `{ok:true, update_payment_method?, cancel?}` and leaves out a link Paddle does not give. A
 * proxy before contract 3 could send a JSON null for one instead, which counts as left out. Every refusal is
 * [ManageLinks.Unavailable] except `manage_urls_unavailable`, the proxy's way of saying there is nothing to
 * manage. Links are checked here as well as when they are opened, so a chooser never offers one the app would
 * then refuse.
 */
internal fun classifyManageAnswer(httpCode: Int, body: String?): ManageLinks {
    val json = try {
        if (body.isNullOrBlank()) null else JSONObject(body)
    } catch (e: Exception) {
        null
    }
    if (httpCode >= 500) return ManageLinks.Unavailable("${json?.stringOrNull("error") ?: "server_error"} (HTTP $httpCode)")
    if (json == null) return ManageLinks.Unavailable("bad_response (HTTP $httpCode)")
    if (!json.optBoolean("ok", false)) {
        val error = json.stringOrNull("error")
        return if (httpCode == 200 && error == "manage_urls_unavailable") ManageLinks.NothingToManage(error)
        else ManageLinks.Unavailable("${error ?: "bad_response"} (HTTP $httpCode)")
    }
    if (httpCode != 200) return ManageLinks.Unavailable("unexpected_ok (HTTP $httpCode)")
    val sentUpdate = json.stringOrNull("update_payment_method")
    val sentCancel = json.stringOrNull("cancel")
    val update = sentUpdate?.takeIf { isAllowedBillingLink(it) }
    val cancel = sentCancel?.takeIf { isAllowedBillingLink(it) }
    val refused = listOfNotNull(sentUpdate, sentCancel).filterNot { isAllowedBillingLink(it) }.map { linkForLog(it) }
    return when {
        update != null || cancel != null -> ManageLinks.Offered(update, cancel, refused)
        refused.isNotEmpty() -> ManageLinks.Unavailable("refused_links: ${refused.joinToString()}")
        else -> ManageLinks.NothingToManage("no_links")
    }
}

/**
 * The hosts the direct flavour opens in a browser: Paddle's customer portal, where the management links lead,
 * and this app's website, which has the checkout. Not Paddle's sandbox portal: the app has no sandbox mode and
 * talks only to the production licence proxy, whose links are never on it.
 */
internal val BILLING_LINK_HOSTS = setOf("customer-portal.paddle.com", "apps.olausson.de")

/**
 * Whether the direct flavour may open [url]: https on one of [BILLING_LINK_HOSTS], without user info or a port.
 * Anything else is refused, whoever sent it.
 *
 * Read with java.net.URI, which rejects what a browser would quietly repair (a backslash, a space, a tab), so a
 * link that passes here names the same host to the browser that opens it.
 */
internal fun isAllowedBillingLink(url: String): Boolean {
    val uri = try {
        URI(url)
    } catch (e: Exception) {
        return false
    }
    return uri.scheme.equals("https", ignoreCase = true) &&
        uri.rawUserInfo == null &&
        uri.port == -1 &&
        uri.host?.lowercase(Locale.ROOT) in BILLING_LINK_HOSTS
}

/** [url] as it may be logged: scheme and host only. The path and the query carry the subscription id. */
internal fun linkForLog(url: String): String = try {
    val uri = URI(url)
    val userInfo = if (uri.rawUserInfo != null) "(user info)@" else ""
    val port = if (uri.port != -1) ":${uri.port}" else ""
    "${uri.scheme ?: "?"}://$userInfo${uri.host ?: "?"}$port"
} catch (e: Exception) {
    "an unreadable link"
}

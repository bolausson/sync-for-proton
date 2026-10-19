package me.proton.sync.billing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the direct flavour makes of a /manage-url answer, and which links it opens at all.
 *
 * The bodies are the shapes the proxy returns (contract 3): `{ok:true}` with the links Paddle gave, a link it
 * does not give left out, `{ok:false, error}` for a refusal, and HTTP 502 for a fault. The ids are made up.
 */
class ManageLinksTest {

    private val portal = "https://customer-portal.paddle.com/cpl_00000000000000000000000000"
    private val subId = "sub_00000000000000000000000000"
    private val updateLink = "$portal?action=update_subscription_payment_method&subscription_id=$subId"
    private val cancelLink = "$portal?action=cancel_subscription&subscription_id=$subId"

    private fun links(update: String? = null, cancel: String? = null): String =
        """{"ok":true""" + (update?.let { ""","update_payment_method":$it""" } ?: "") +
            (cancel?.let { ""","cancel":$it""" } ?: "") + "}"

    private fun quoted(s: String) = "\"$s\""

    // ---------------------------------------------------------------- offered

    @Test
    fun `both links are offered`() {
        assertEquals(
            ManageLinks.Offered(updateLink, cancelLink),
            classifyManageAnswer(200, links(quoted(updateLink), quoted(cancelLink)))
        )
    }

    @Test
    fun `a link Paddle does not give is left out, and so is a JSON null for it`() {
        assertEquals(ManageLinks.Offered(null, cancelLink), classifyManageAnswer(200, links(cancel = quoted(cancelLink))))
        // A proxy before contract 3 passed Paddle's null on; Android's optString made it the link "null".
        assertEquals(ManageLinks.Offered(null, cancelLink), classifyManageAnswer(200, links("null", quoted(cancelLink))))
        assertEquals(ManageLinks.Offered(updateLink, null), classifyManageAnswer(200, links(quoted(updateLink), "null")))
        // The org.json these tests run on returns optString's fallback for a JSON null, where Android's returns "null",
        // so the null cases above would pass with optString too. A value that is not a string pins the call sites to
        // stringOrNull on both: optString would make it the link "42".
        assertEquals(ManageLinks.Offered(null, cancelLink), classifyManageAnswer(200, links("42", quoted(cancelLink))))
        assertEquals(ManageLinks.Unavailable("bad_response (HTTP 200)"), classifyManageAnswer(200, """{"ok":false,"error":42}"""))
    }

    // ---------------------------------------------------------------- nothing to manage

    @Test
    fun `the proxy's nothing-to-manage refusal, and an answer without links, are nothing to manage`() {
        assertEquals(
            ManageLinks.NothingToManage("manage_urls_unavailable"),
            classifyManageAnswer(200, """{"ok":false,"error":"manage_urls_unavailable"}""")
        )
        assertEquals(ManageLinks.NothingToManage("no_links"), classifyManageAnswer(200, links()))
        assertEquals(ManageLinks.NothingToManage("no_links"), classifyManageAnswer(200, links("null", "null")))
        assertEquals(ManageLinks.NothingToManage("no_links"), classifyManageAnswer(200, links(quoted(""), quoted("  "))))
    }

    // ---------------------------------------------------------------- unavailable

    @Test
    fun `a fault on the proxy's or Paddle's side is unavailable`() {
        // The proxy itself refuses links that fail its check: 502 with detail unusable_links.
        assertEquals(
            ManageLinks.Unavailable("paddle_server_error (HTTP 502)"),
            classifyManageAnswer(502, """{"ok":false,"error":"paddle_server_error","detail":"unusable_links"}""")
        )
        assertEquals(
            ManageLinks.Unavailable("paddle_unreachable (HTTP 502)"),
            classifyManageAnswer(502, """{"ok":false,"error":"paddle_unreachable"}""")
        )
        assertEquals(ManageLinks.Unavailable("server_error (HTTP 503)"), classifyManageAnswer(503, "<html>Bad gateway</html>"))
        // Even links in a 5xx body are not opened.
        assertEquals(ManageLinks.Unavailable("server_error (HTTP 500)"), classifyManageAnswer(500, links(quoted(updateLink))))
    }

    @Test
    fun `a refusal is unavailable, except nothing-to-manage with its 200`() {
        assertEquals(
            ManageLinks.Unavailable("subscription_not_found (HTTP 200)"),
            classifyManageAnswer(200, """{"ok":false,"error":"subscription_not_found"}""")
        )
        assertEquals(
            ManageLinks.Unavailable("subscription_wrong_product (HTTP 200)"),
            classifyManageAnswer(200, """{"ok":false,"error":"subscription_wrong_product"}""")
        )
        assertEquals(
            ManageLinks.Unavailable("invalid_request (HTTP 400)"),
            classifyManageAnswer(400, """{"ok":false,"error":"invalid_request"}""")
        )
        // The proxy's answer to a path it does not serve.
        assertEquals(ManageLinks.Unavailable("not_found (HTTP 404)"), classifyManageAnswer(404, """{"ok":false,"error":"not_found"}"""))
        assertEquals(
            ManageLinks.Unavailable("manage_urls_unavailable (HTTP 403)"),
            classifyManageAnswer(403, """{"ok":false,"error":"manage_urls_unavailable"}""")
        )
    }

    @Test
    fun `an answer that is not the proxy's JSON is unavailable`() {
        assertEquals(ManageLinks.Unavailable("bad_response (HTTP 200)"), classifyManageAnswer(200, "<html>captive portal</html>"))
        assertEquals(ManageLinks.Unavailable("bad_response (HTTP 200)"), classifyManageAnswer(200, ""))
        assertEquals(ManageLinks.Unavailable("bad_response (HTTP 200)"), classifyManageAnswer(200, null))
        assertEquals(ManageLinks.Unavailable("bad_response (HTTP 200)"), classifyManageAnswer(200, """{"ok":false}"""))
        assertEquals(ManageLinks.Unavailable("bad_response (HTTP 200)"), classifyManageAnswer(200, """{"ok":false,"error":null}"""))
        assertEquals(ManageLinks.Unavailable("bad_response (HTTP 200)"), classifyManageAnswer(200, """{"message":"Not Found"}"""))
        assertEquals(ManageLinks.Unavailable("unexpected_ok (HTTP 202)"), classifyManageAnswer(202, links(quoted(updateLink))))
    }

    @Test
    fun `a link the app does not open is dropped, and with no other link the answer is unavailable`() {
        val other = "https://evil.example/cpl_00000000000000000000000000?subscription_id=$subId"
        assertEquals(
            ManageLinks.Offered(null, cancelLink, refused = listOf("https://evil.example")),
            classifyManageAnswer(200, links(quoted(other), quoted(cancelLink)))
        )
        assertEquals(
            ManageLinks.Unavailable("refused_links: http://customer-portal.paddle.com, https://evil.example"),
            classifyManageAnswer(200, links(quoted(updateLink.replace("https:", "http:")), quoted(other)))
        )
        // The text "null" as a link, what optString would have made of a JSON null on a phone.
        assertEquals(ManageLinks.Unavailable("refused_links: ?://?"), classifyManageAnswer(200, links(quoted("null"))))
    }

    // ---------------------------------------------------------------- which links open

    @Test
    fun `Paddle's customer portal and the app's website open`() {
        for (url in listOf(
            updateLink,
            cancelLink,
            "https://apps.olausson.de/sync_provider_for_proton/checkout.html",
            "HTTPS://Customer-Portal.Paddle.com/cpl_00000000000000000000000000",
            "$cancelLink#section",
        )) {
            assertTrue(url, isAllowedBillingLink(url))
        }
    }

    @Test
    fun `nothing else opens`() {
        for (url in listOf(
            updateLink.replace("https:", "http:"),
            // No sandbox mode: the app only ever talks to the production licence proxy.
            "https://sandbox-customer-portal.paddle.com/cpl_00000000000000000000000000",
            "https://evil.example/cpl_00000000000000000000000000",
            "https://customer-portal.paddle.com.evil.example/",
            "https://x.customer-portal.paddle.com/",
            "https://apps.olausson.de.evil.example/checkout.html",
            "https://customer-portal.paddle.com@evil.example/",
            "https://evil.example@customer-portal.paddle.com/",
            "https://customer-portal.paddle.com:8443/",
            "https://customer-portal.paddle.com:443/",
            "https://evil.example\\@customer-portal.paddle.com/",
            "https://customer-portal.paddle.com /",
            "https://customer-portal.paddle.com\t/",
            "//customer-portal.paddle.com/cpl_00000000000000000000000000",
            "/cpl_00000000000000000000000000",
            "intent://customer-portal.paddle.com/#Intent;scheme=https;end",
            "javascript:alert(1)",
            "file:///sdcard/page.html",
            "content://me.proton.sync/page",
            "https:customer-portal.paddle.com",
            "null",
            "",
        )) {
            assertFalse(url, isAllowedBillingLink(url))
        }
    }

    @Test
    fun `a link is logged by scheme and host only, never with the subscription id`() {
        assertEquals("https://customer-portal.paddle.com", linkForLog(updateLink))
        assertFalse(linkForLog(updateLink).contains(subId))
        assertEquals("https://(user info)@customer-portal.paddle.com", linkForLog("https://user:secret@customer-portal.paddle.com/"))
        assertEquals("https://customer-portal.paddle.com:8443", linkForLog("https://customer-portal.paddle.com:8443/x?subscription_id=$subId"))
        assertEquals("an unreadable link", linkForLog("https://evil.example\\@customer-portal.paddle.com/?subscription_id=$subId"))
    }
}

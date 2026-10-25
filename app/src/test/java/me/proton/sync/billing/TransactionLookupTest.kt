package me.proton.sync.billing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * What the direct flavour makes of a /txn-to-sub answer, the licence proxy's lookup of the subscription a Paddle
 * transaction paid for. Only [TransactionLookup.Resolved] leads anywhere: its id is checked with /validate and
 * stored if Paddle accepts it, so it must be exactly the shape of a subscription id.
 *
 * The bodies are the shapes the proxy returns (contract 3): 200 `{ok:true, subscription_id, status}`, 200
 * `{ok:false, error}` for an answer about the transaction, 400 `invalid_request`, and a 5xx for a fault. [V] is
 * made up, as in SubscriptionEntryTest.
 */
class TransactionLookupTest {

    private val V = "sub_07h04vsc0qhwtsbsxh3422wjs4"

    private fun found(subId: String, status: String = "active") =
        """{"ok":true,"subscription_id":$subId,"status":"$status"}"""

    private fun refused(error: String) = """{"ok":false,"error":"$error"}"""

    private fun quoted(s: String) = "\"$s\""

    // ---------------------------------------------------------------- resolved

    @Test
    fun `a subscription id of the right shape is resolved, whatever its status`() {
        assertEquals(TransactionLookup.Resolved(V), classifyTxnAnswer(200, found(quoted(V))))
        // The status is /validate's to judge, on the check that follows.
        assertEquals(TransactionLookup.Resolved(V), classifyTxnAnswer(200, found(quoted(V), "trialing")))
        assertEquals(TransactionLookup.Resolved(V), classifyTxnAnswer(200, found(quoted(V), "canceled")))
    }

    @Test
    fun `a subscription id that is not exactly that shape is not trusted`() {
        for (subId in listOf(
            quoted(V.uppercase()),
            quoted("$V "),
            quoted(" $V"),
            quoted(V.dropLast(1)),
            quoted(V + "x"),
            quoted(V.dropLast(1) + "u"),
            quoted("sub_8" + V.drop(5)),
            quoted("txn_" + V.drop(4)),
            quoted("$V&sub=$V"),
            quoted(""),
            "null",
            "42",
        )) {
            assertEquals(subId, TransactionLookup.Unknown("bad_response"), classifyTxnAnswer(200, found(subId)))
        }
        assertEquals(TransactionLookup.Unknown("bad_response"), classifyTxnAnswer(200, """{"ok":true,"status":"active"}"""))
    }

    @Test
    fun `a resolved lookup never prints its id`() {
        assertEquals("Resolved", TransactionLookup.Resolved(V).toString())
        assertFalse(TransactionLookup.Resolved(V).toString().contains("sub_"))
    }

    // ---------------------------------------------------------------- refused and not ready

    @Test
    fun `the proxy's answers about the transaction are refusals, or not ready`() {
        assertEquals(TransactionLookup.Refused("subscription_not_found"), classifyTxnAnswer(200, refused("subscription_not_found")))
        assertEquals(TransactionLookup.Refused("subscription_wrong_product"), classifyTxnAnswer(200, refused("subscription_wrong_product")))
        assertEquals(TransactionLookup.Refused("invalid_request"), classifyTxnAnswer(400, refused("invalid_request")))
        assertEquals(TransactionLookup.NotReady, classifyTxnAnswer(200, refused("not_ready")))
    }

    @Test
    fun `a refusal with a status the proxy does not send it with is unknown`() {
        assertEquals(TransactionLookup.Unknown("unexpected_invalid_request"), classifyTxnAnswer(200, refused("invalid_request")))
        assertEquals(TransactionLookup.Unknown("unexpected_subscription_not_found"), classifyTxnAnswer(400, refused("subscription_not_found")))
        assertEquals(TransactionLookup.Unknown("unexpected_not_ready"), classifyTxnAnswer(400, refused("not_ready")))
        // The answer of a proxy that does not serve /txn-to-sub at all.
        assertEquals(TransactionLookup.Unknown("unexpected_not_found"), classifyTxnAnswer(404, refused("not_found")))
        assertEquals(TransactionLookup.Unknown("unexpected_subscription_canceled"), classifyTxnAnswer(200, refused("subscription_canceled")))
    }

    // ---------------------------------------------------------------- unknown

    @Test
    fun `a fault on the proxy's or Paddle's side is unknown, even with an id in it`() {
        assertEquals(TransactionLookup.Unknown("paddle_server_error"), classifyTxnAnswer(502, refused("paddle_server_error")))
        assertEquals(TransactionLookup.Unknown("paddle_unreachable"), classifyTxnAnswer(502, refused("paddle_unreachable")))
        assertEquals(TransactionLookup.Unknown("server_error"), classifyTxnAnswer(503, "<html>Bad gateway</html>"))
        assertEquals(TransactionLookup.Unknown("server_error"), classifyTxnAnswer(500, null))
        assertEquals(TransactionLookup.Unknown("server_error"), classifyTxnAnswer(500, found(quoted(V))))
    }

    @Test
    fun `an answer that is not the proxy's JSON is unknown`() {
        assertEquals(TransactionLookup.Unknown("bad_response"), classifyTxnAnswer(200, "<html>captive portal</html>"))
        assertEquals(TransactionLookup.Unknown("bad_response"), classifyTxnAnswer(200, ""))
        assertEquals(TransactionLookup.Unknown("bad_response"), classifyTxnAnswer(200, null))
        assertEquals(TransactionLookup.Unknown("bad_response"), classifyTxnAnswer(200, """{"ok":false}"""))
        // A JSON null or a number for the error, which optString would turn into the text "null" or "42" on a phone.
        assertEquals(TransactionLookup.Unknown("bad_response"), classifyTxnAnswer(200, """{"ok":false,"error":null}"""))
        assertEquals(TransactionLookup.Unknown("bad_response"), classifyTxnAnswer(200, """{"ok":false,"error":42}"""))
        assertEquals(TransactionLookup.Unknown("unexpected_ok"), classifyTxnAnswer(202, found(quoted(V))))
    }
}

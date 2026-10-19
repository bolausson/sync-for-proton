package me.proton.sync.billing

import me.proton.sync.R
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What each licence-proxy answer does to the stored subscription id and to sync.
 *
 * The bodies are the shapes the proxy returns (contract v2): a validate_ok body for
 * active, trialing and past_due, `{ ok:false, error, status }` for refusals, and HTTP 502 for anything
 * that is not an answer about the subscription. The expensive mistakes are the ones that forget a paying
 * customer's id ([LicenseVerdict.Ended]), so most of these check that only a real ending gets there.
 */
class LicenseVerdictTest {

    private fun ok(status: String, ends: String? = "2026-10-23T03:00:00Z") =
        """{"ok":true,"valid":true,"status":"$status","next_billed_at":null,"billing_interval":"month",""" +
            """"current_period_starts_at":"2026-09-23T03:00:00Z","current_period_ends_at":${ends?.let { "\"$it\"" } ?: "null"}}"""

    private fun refused(error: String, status: String? = null) =
        """{"ok":false,"error":"$error"${status?.let { ",\"status\":\"$it\"" } ?: ""}}"""

    /** Both sides of the confirmed flag, for answers that must not depend on it. */
    private fun both(httpCode: Int, body: String?, expected: LicenseVerdict) {
        assertEquals("unconfirmed", expected, classifyLicenseAnswer(httpCode, body, confirmed = false))
        assertEquals("confirmed", expected, classifyLicenseAnswer(httpCode, body, confirmed = true))
    }

    // ---------------------------------------------------------------- entitled

    @Test
    fun `an active subscription is entitled with no payment problem`() {
        val v = classifyLicenseAnswer(200, ok("active"), confirmed = true) as LicenseVerdict.Entitled
        assertFalse(v.trial)
        assertFalse(v.paymentFailed)
        assertEquals(1792724400000L, v.periodEndsAtMs)
    }

    @Test
    fun `a trial is entitled and marked as a trial`() {
        val v = classifyLicenseAnswer(200, ok("trialing"), confirmed = false) as LicenseVerdict.Entitled
        assertTrue(v.trial)
        assertFalse(v.paymentFailed)
    }

    @Test
    fun `past due that has paid before stays entitled, flagged as a failed payment`() {
        // Paddle retries the card for up to 30 days and recommends full access meanwhile. The proxy
        // adds ever_paid, which changes nothing here.
        val body = ok("past_due").dropLast(1) + ""","ever_paid":true}"""
        val v = classifyLicenseAnswer(200, body, confirmed = true) as LicenseVerdict.Entitled
        assertTrue(v.paymentFailed)
        assertFalse(v.trial)
    }

    // ---------------------------------------------------------------- kept, not ended

    @Test
    fun `never paid is unpaid, not an ending, so the id survives until the card is fixed`() {
        both(200, refused("subscription_unpaid", "past_due"), LicenseVerdict.Unpaid)
    }

    @Test
    fun `paused is suspended, never ended, so the id survives a resume`() {
        both(200, refused("subscription_paused", "paused"), LicenseVerdict.Suspended)
        // The error code is the contract; the status field is informational.
        both(200, refused("subscription_paused"), LicenseVerdict.Suspended)
    }

    // ---------------------------------------------------------------- endings

    @Test
    fun `a cancellation ends the licence whether or not the id was confirmed`() {
        both(200, refused("subscription_canceled", "canceled"), LicenseVerdict.Ended("subscription_canceled"))
    }

    @Test
    fun `an unconfirmed id is refused on every subscription answer`() {
        // A pasted id that never validated: a typo gets its plain "not found" and is dropped.
        for (error in listOf(
            "subscription_not_found", "subscription_wrong_product", "subscription_inactive",
            "subscription_expired", "subscription_scheduled_cancel",
        )) {
            assertEquals(error, LicenseVerdict.Ended(error), classifyLicenseAnswer(200, refused(error), confirmed = false))
        }
        assertEquals(LicenseVerdict.Ended("invalid_request"), classifyLicenseAnswer(400, refused("invalid_request"), confirmed = false))
    }

    @Test
    fun `a confirmed id is never deleted by an answer only a fault can produce`() {
        // Paddle cannot lose a subscription or move it to another product. An older proxy or a wrong
        // product id in its configuration would say so to every install at once.
        for (error in listOf(
            "subscription_not_found", "subscription_wrong_product", "subscription_inactive",
            "subscription_expired", "subscription_scheduled_cancel",
        )) {
            assertEquals(error, LicenseVerdict.Unknown("unconfirmed_$error"), classifyLicenseAnswer(200, refused(error), confirmed = true))
        }
        assertEquals(
            LicenseVerdict.Unknown("unconfirmed_invalid_request"),
            classifyLicenseAnswer(400, refused("invalid_request"), confirmed = true)
        )
    }

    // ---------------------------------------------------------------- decides nothing

    @Test
    fun `the proxy's own route miss and other error JSON decide nothing`() {
        // The proxy answers a path it does not serve with 404 not_found. A real missing subscription
        // is subscription_not_found with HTTP 200, so this one must not delete the id.
        assertEquals(LicenseVerdict.Unknown("unexpected_not_found"), classifyLicenseAnswer(404, refused("not_found"), confirmed = false))
        assertEquals(LicenseVerdict.Unknown("unexpected_rate limited"), classifyLicenseAnswer(429, """{"error":"rate limited"}""", confirmed = false))
        // A subscription_* code only counts with the 200 the proxy always sends it with.
        assertEquals(LicenseVerdict.Unknown("unexpected_subscription_canceled"), classifyLicenseAnswer(403, refused("subscription_canceled"), confirmed = false))
        assertEquals(LicenseVerdict.Unknown("unexpected_subscription_paused"), classifyLicenseAnswer(404, refused("subscription_paused"), confirmed = false))
        // invalid_request only counts with its 400.
        assertEquals(LicenseVerdict.Unknown("unexpected_invalid_request"), classifyLicenseAnswer(200, refused("invalid_request"), confirmed = false))
    }

    @Test
    fun `contract v2 faults arrive as 502 and decide nothing, whatever the detail`() {
        both(502, """{"ok":false,"error":"paddle_server_error","detail":"unconfirmed"}""", LicenseVerdict.Unknown("paddle_server_error"))
        both(502, """{"ok":false,"error":"paddle_server_error","detail":"bad_request"}""", LicenseVerdict.Unknown("paddle_server_error"))
    }

    @Test
    fun `an explicit null error decides nothing`() {
        both(200, """{"ok":false,"error":null}""", LicenseVerdict.Unknown("bad_response"))
        // Not a string: pins the call site to stringOrNull on the JVM too, whose optString hides the null case.
        both(200, """{"ok":false,"error":42}""", LicenseVerdict.Unknown("bad_response"))
    }

    @Test
    fun `JSON without an error code decides nothing`() {
        // Every real refusal from the proxy names its reason. Anything else, such as a gateway's
        // own JSON error page, must not delete the id.
        both(200, """{"ok":false}""", LicenseVerdict.Unknown("bad_response"))
        both(404, """{"message":"Not Found"}""", LicenseVerdict.Unknown("bad_response"))
    }

    @Test
    fun `ok without valid is not entitlement, and not an ending either`() {
        both(200, """{"ok":true,"status":"active"}""", LicenseVerdict.Unknown("bad_response"))
    }

    @Test
    fun `transient proxy errors decide nothing`() {
        for (error in TRANSIENT_LICENSE_ERRORS) {
            // At 5xx the body is not even read; at any other code the transient list still applies.
            both(502, refused(error), LicenseVerdict.Unknown("paddle_server_error"))
            both(401, refused(error), LicenseVerdict.Unknown(error))
            both(200, refused(error), LicenseVerdict.Unknown(error))
        }
    }

    @Test
    fun `a server error is unknown whatever its body says`() {
        both(503, refused("subscription_canceled"), LicenseVerdict.Unknown("paddle_server_error"))
        both(500, "<html>Bad gateway</html>", LicenseVerdict.Unknown("paddle_server_error"))
    }

    @Test
    fun `a body that is not JSON is unknown, not an ending`() {
        both(200, "<html>captive portal</html>", LicenseVerdict.Unknown("bad_response"))
        both(404, "Not Found", LicenseVerdict.Unknown("bad_response"))
    }

    @Test
    fun `an empty body decides nothing`() {
        // The original client read an empty 2xx body as "inactive" and deleted the id.
        both(200, "", LicenseVerdict.Unknown("bad_response"))
        both(200, null, LicenseVerdict.Unknown("bad_response"))
        both(200, "   ", LicenseVerdict.Unknown("bad_response"))
    }

    @Test
    fun `a missing or malformed period end falls back to null`() {
        assertNull((classifyLicenseAnswer(200, ok("active", ends = null), confirmed = true) as LicenseVerdict.Entitled).periodEndsAtMs)
        assertNull((classifyLicenseAnswer(200, ok("active", ends = "not a date"), confirmed = true) as LicenseVerdict.Entitled).periodEndsAtMs)
    }

    @Test
    fun `a null status is an ordinary active subscription, not a trial or a failed payment`() {
        val v = classifyLicenseAnswer(200, """{"ok":true,"valid":true,"status":null}""", confirmed = true) as LicenseVerdict.Entitled
        assertFalse(v.trial)
        assertFalse(v.paymentFailed)
    }

    // ---------------------------------------------------------------- reading the proxy's JSON

    @Test
    fun `stringOrNull gives the text of a string and null for everything else`() {
        val json = JSONObject(
            """{"text":"cancel","null":null,"blank":"  ","empty":"","number":42,"flag":true,"object":{},"list":[]}"""
        )
        assertEquals("cancel", json.stringOrNull("text"))
        for (name in listOf("missing", "null", "blank", "empty", "number", "flag", "object", "list")) {
            assertNull(name, json.stringOrNull(name))
        }
        // JSONObject.NULL put by hand, as well as parsed: never the text "null", which Android's optString returns.
        assertNull(JSONObject().put("x", JSONObject.NULL).stringOrNull("x"))
    }

    // ---------------------------------------------------------------- scheduled change

    private val novemberTwelfth = "2026-11-12T03:00:00Z"
    private val novemberTwelfthMs = 1794452400000L

    private fun withChange(status: String, change: String) = ok(status).dropLast(1) + ""","scheduled_change":$change}"""

    private fun changeOf(body: String) =
        (classifyLicenseAnswer(200, body, confirmed = true) as LicenseVerdict.Entitled).scheduledChange

    @Test
    fun `a scheduled cancellation comes with its date`() {
        assertEquals(
            ScheduledChange(ScheduledChange.Action.CANCEL, novemberTwelfthMs),
            changeOf(withChange("active", """{"action":"cancel","effective_at":"$novemberTwelfth"}"""))
        )
    }

    @Test
    fun `a scheduled pause comes with its date, on a trial as well`() {
        val v = classifyLicenseAnswer(
            200, withChange("trialing", """{"action":"pause","effective_at":"$novemberTwelfth"}"""), confirmed = false
        ) as LicenseVerdict.Entitled
        assertTrue(v.trial)
        assertEquals(ScheduledChange(ScheduledChange.Action.PAUSE, novemberTwelfthMs), v.scheduledChange)
    }

    @Test
    fun `no scheduled change when the proxy reports none`() {
        // The proxy sends null when Paddle has nothing scheduled; an older one leaves the field out.
        assertNull(changeOf(withChange("active", "null")))
        assertNull(changeOf(ok("active")))
    }

    @Test
    fun `a scheduled change the card cannot show is left out`() {
        for (change in listOf(
            """{"action":"resume","effective_at":"$novemberTwelfth"}""",   // a paused subscription's resume
            """{"action":"upgrade","effective_at":"$novemberTwelfth"}""",
            """{"action":null,"effective_at":"$novemberTwelfth"}""",
            """{"action":"cancel","effective_at":null}""",
            """{"action":"cancel"}""",
            """{"action":"cancel","effective_at":"next month"}""",
            """"cancel"""",
            """[]""",
        )) {
            assertNull(change, changeOf(withChange("active", change)))
        }
    }

    @Test
    fun `the stored action name reads back, and only a known one`() {
        for (action in ScheduledChange.Action.entries) assertEquals(action, ScheduledChange.Action.fromWire(action.wire))
        assertEquals(ScheduledChange.Action.CANCEL, ScheduledChange.Action.fromWire("cancel"))
        assertEquals(ScheduledChange.Action.PAUSE, ScheduledChange.Action.fromWire("pause"))
        for (wire in listOf("resume", "CANCEL", "", "null", null)) assertNull(wire, ScheduledChange.Action.fromWire(wire))
    }

    @Test
    fun `the card says the subscription ends or pauses on the date`() {
        assertEquals(
            R.string.label_subscription_ends,
            scheduledChangeLabel(ScheduledChange(ScheduledChange.Action.CANCEL, novemberTwelfthMs))
        )
        assertEquals(
            R.string.label_subscription_pauses,
            scheduledChangeLabel(ScheduledChange(ScheduledChange.Action.PAUSE, novemberTwelfthMs))
        )
    }

    // ---------------------------------------------------------------- reason and upgrade default

    @Test
    fun `an id asks as an activation until it is confirmed`() {
        assertEquals("activation", licenseReason(confirmed = false))
        assertEquals("refresh", licenseReason(confirmed = true))
    }

    @Test
    fun `only a cached trial or active state counts as confirmed on an upgrade`() {
        assertTrue(licenseConfirmedByDefault("Trial"))
        assertTrue(licenseConfirmedByDefault("Active"))
        for (name in listOf("Expired", "Grace", "NoSubscription", "Unknown", null)) {
            assertFalse(name.toString(), licenseConfirmedByDefault(name))
        }
    }

    // ---------------------------------------------------------------- the notification

    private val active = SubscriptionState.Active(0L, "p")
    private val activeFailing = SubscriptionState.Active(0L, "p", paymentFailed = true)
    private val unpaid = SubscriptionState.Expired("p", paymentFailed = true)
    private val paused = SubscriptionState.Expired("p", paused = true)
    private val expired = SubscriptionState.Expired("p")

    @Test
    fun `the payment notice shows on the way into a failed payment`() {
        assertEquals(PaymentNotice.SHOW, paymentNoticeChange(active, activeFailing))
        assertEquals(PaymentNotice.SHOW, paymentNoticeChange(SubscriptionState.Trial(0L, "p"), unpaid))
        assertEquals(PaymentNotice.SHOW, paymentNoticeChange(SubscriptionState.NoSubscription, unpaid))
    }

    @Test
    fun `the payment notice is not repeated while the episode lasts`() {
        // The state is persisted, so a restart restores the flag and does not count as a new episode.
        assertEquals(PaymentNotice.NONE, paymentNoticeChange(activeFailing, activeFailing))
        assertEquals(PaymentNotice.NONE, paymentNoticeChange(unpaid, unpaid))
    }

    @Test
    fun `the payment notice is replaced when sync turns off or back on during the episode`() {
        // A renewal past due for over 35 days becomes unpaid: sync stops, and the notice that said
        // "keep syncing" must not be left standing, nor the stop go unannounced.
        assertEquals(PaymentNotice.SHOW, paymentNoticeChange(activeFailing, unpaid))
        assertEquals(PaymentNotice.SHOW, paymentNoticeChange(unpaid, activeFailing))
    }

    @Test
    fun `the payment notice clears once the payment problem is over`() {
        assertEquals(PaymentNotice.CLEAR, paymentNoticeChange(activeFailing, active))
        assertEquals(PaymentNotice.CLEAR, paymentNoticeChange(unpaid, active))
        assertEquals(PaymentNotice.CLEAR, paymentNoticeChange(activeFailing, expired))
        assertEquals(PaymentNotice.CLEAR, paymentNoticeChange(unpaid, paused))
    }

    @Test
    fun `unrelated state changes leave the payment notice alone`() {
        assertEquals(PaymentNotice.NONE, paymentNoticeChange(active, paused))
        assertEquals(PaymentNotice.NONE, paymentNoticeChange(expired, active))
        assertEquals(PaymentNotice.NONE, paymentNoticeChange(SubscriptionState.Unknown, active))
    }
}

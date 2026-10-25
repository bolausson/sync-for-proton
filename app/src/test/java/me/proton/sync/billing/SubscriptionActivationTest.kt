package me.proton.sync.billing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What entering a subscription id does to the id this phone has. Before 2.0.2 the dialog stored the text
 * first and checked it after, so a typo or an outage replaced a working id and the refusal then deleted it,
 * with nothing on screen. Now only an id Paddle accepts is stored, and a paused or unpaid one never replaces
 * an id that syncs. A transaction id from Paddle's invoice is looked up first, and only the subscription id it
 * leads to goes on to that check.
 */
class SubscriptionActivationTest {

    private fun refused(error: String, status: String? = null) =
        """{"ok":false,"error":"$error"${status?.let { ",\"status\":\"$it\"" } ?: ""}}"""

    private val entitled = LicenseVerdict.Entitled(trial = false, paymentFailed = false, periodEndsAtMs = null)

    // ---------------------------------------------------------------- planActivation

    @Test
    fun `an entitled entry is stored, even over an id that syncs`() {
        assertEquals(
            ActivationPlan(true, ActivationOutcome.Activated(trial = true)),
            planActivation(entitled.copy(trial = true), storedIdSyncing = false),
        )
        assertEquals(
            ActivationPlan(true, ActivationOutcome.Activated(trial = false)),
            planActivation(entitled, storedIdSyncing = true),
        )
    }

    @Test
    fun `an unpaid or paused entry is stored only when sync is off anyway`() {
        assertEquals(ActivationPlan(true, ActivationOutcome.StoredUnpaid), planActivation(LicenseVerdict.Unpaid, storedIdSyncing = false))
        assertEquals(ActivationPlan(true, ActivationOutcome.StoredPaused), planActivation(LicenseVerdict.Suspended, storedIdSyncing = false))
        assertEquals(
            ActivationPlan(false, ActivationOutcome.KeptCurrent(paused = false)),
            planActivation(LicenseVerdict.Unpaid, storedIdSyncing = true),
        )
        assertEquals(
            ActivationPlan(false, ActivationOutcome.KeptCurrent(paused = true)),
            planActivation(LicenseVerdict.Suspended, storedIdSyncing = true),
        )
    }

    @Test
    fun `a refusal or no answer is never stored`() {
        for (syncing in listOf(false, true)) {
            assertEquals(
                ActivationPlan(false, ActivationOutcome.Refused("subscription_not_found")),
                planActivation(LicenseVerdict.Ended("subscription_not_found"), syncing),
            )
            assertEquals(
                ActivationPlan(false, ActivationOutcome.NotChecked("network_error")),
                planActivation(LicenseVerdict.Unknown("network_error"), syncing),
            )
        }
    }

    @Test
    fun `no refusal and no fault ever replaces the stored id`() {
        // The answers the proxy gives for a typed id it does not accept, and the faults on the way to it.
        val answers = listOf(
            400 to refused("invalid_request"),
            200 to refused("subscription_not_found"),
            200 to refused("subscription_wrong_product"),
            200 to refused("subscription_canceled", "canceled"),
            502 to refused("paddle_server_error"),
            502 to refused("paddle_unreachable"),
            200 to "",
            200 to "not json",
            404 to refused("not_found"),
            503 to null,
        )
        for ((code, body) in answers) {
            for (syncing in listOf(false, true)) {
                val verdict = classifyLicenseAnswer(code, body, confirmed = false)
                assertFalse("$code $body syncing=$syncing -> $verdict", planActivation(verdict, syncing).store)
            }
        }
    }

    // ---------------------------------------------------------------- recheckOutcome

    @Test
    fun `the stored id entered again names what its check found`() {
        assertEquals(ActivationOutcome.Activated(trial = true), recheckOutcome(entitled.copy(trial = true)))
        assertEquals(ActivationOutcome.StoredUnpaid, recheckOutcome(LicenseVerdict.Unpaid))
        assertEquals(ActivationOutcome.StoredPaused, recheckOutcome(LicenseVerdict.Suspended))
        assertEquals(
            ActivationOutcome.Refused("subscription_canceled"),
            recheckOutcome(classifyLicenseAnswer(200, refused("subscription_canceled", "canceled"), confirmed = true)),
        )
        assertEquals(ActivationOutcome.NotChecked("paddle_server_error"), recheckOutcome(LicenseVerdict.Unknown("paddle_server_error")))
    }

    // ---------------------------------------------------------------- transactionOutcome

    @Test
    fun `a transaction id leads on only when the proxy names its subscription`() {
        val v = "sub_07h04vsc0qhwtsbsxh3422wjs4"
        assertNull(transactionOutcome(TransactionLookup.Resolved(v)))
        assertNull(transactionOutcome(classifyTxnAnswer(200, """{"ok":true,"subscription_id":"$v","status":"active"}""")))
    }

    @Test
    fun `each transaction answer that leads to no subscription id has its own outcome`() {
        assertEquals(ActivationOutcome.TransactionNotFound, transactionOutcome(TransactionLookup.Refused("subscription_not_found")))
        assertEquals(ActivationOutcome.TransactionNotFound, transactionOutcome(TransactionLookup.Refused("invalid_request")))
        assertEquals(ActivationOutcome.TransactionWrongProduct, transactionOutcome(TransactionLookup.Refused("subscription_wrong_product")))
        assertEquals(ActivationOutcome.TransactionNotReady, transactionOutcome(TransactionLookup.NotReady))
        // Faults on the way are the dialog's usual "no connection" and "unavailable".
        assertEquals(ActivationOutcome.NotChecked("network_error"), transactionOutcome(TransactionLookup.Unknown("network_error")))
        assertEquals(ActivationOutcome.NotChecked("paddle_unreachable"), transactionOutcome(TransactionLookup.Unknown("paddle_unreachable")))
    }

    @Test
    fun `no transaction refusal and no fault leads to a subscription id`() {
        // The answers the proxy gives for a transaction it cannot map, and the faults on the way to it. Each one ends
        // the entry with an outcome, so nothing is checked and nothing stored.
        val answers = listOf(
            400 to refused("invalid_request"),
            200 to refused("subscription_not_found"),
            200 to refused("subscription_wrong_product"),
            200 to refused("not_ready"),
            502 to refused("paddle_server_error"),
            502 to refused("paddle_unreachable"),
            200 to "",
            200 to "not json",
            200 to """{"ok":true,"subscription_id":null,"status":"active"}""",
            200 to """{"ok":true,"subscription_id":"SUB_07H04VSC0QHWTSBSXH3422WJS4","status":"active"}""",
            404 to refused("not_found"),
            503 to null,
        )
        for ((code, body) in answers) {
            assertNotNull("$code $body", transactionOutcome(classifyTxnAnswer(code, body)))
        }
    }

    @Test
    fun `an outcome carries no id`() {
        assertEquals("Refused(error=subscription_not_found)", ActivationOutcome.Refused("subscription_not_found").toString())
        assertEquals("KeptCurrent(paused=true)", ActivationOutcome.KeptCurrent(paused = true).toString())
        assertEquals("TransactionNotFound", ActivationOutcome.TransactionNotFound.toString())
        assertEquals("TransactionNotReady", ActivationOutcome.TransactionNotReady.toString())
    }

    // ---------------------------------------------------------------- the wiring (needs an Activity, so its source)

    private fun source(path: String) = File("src/$path").readText().replace("\r\n", "\n")

    @Test
    fun `the dialog checks an id before it is stored, and never against a debug fixture`() {
        val ui = source("direct/java/me/proton/sync/billing/BillingUi.kt")
        assertFalse(ui.contains("applyLicense("))
        assertTrue(ui.contains("sm.activate("))
        val manager = source("direct/java/me/proton/sync/billing/SubscriptionManager.kt")
        assertTrue(manager.contains("private fun applyLicense("))
        assertTrue(manager.contains("useFixture = false"))
    }

    @Test
    fun `a transaction id is looked up under the licence lock, never against a debug fixture, and logged without an id`() {
        val ui = source("direct/java/me/proton/sync/billing/BillingUi.kt")
        // Both kinds the dialog sends go to the same activate, behind the same spinner.
        assertTrue(ui.contains("if (entry !is SubscriptionEntry.Sendable) {"))
        assertTrue(ui.contains("sm.activate(entry, "))
        val manager = source("direct/java/me/proton/sync/billing/SubscriptionManager.kt")
        assertTrue(manager.contains("scope.async { refreshLock.withLock { activateLocked(entry, debugOffline) } }"))
        val lookup = manager.substringAfter("private fun lookUpTransaction(").substringBefore("\n    }\n")
        assertTrue(lookup, lookup.contains("/txn-to-sub?txn=\${URLEncoder.encode(txnId, \"UTF-8\")}"))
        assertFalse(lookup, lookup.contains("debugFixtureBody"))
        // The offline flag answers before anything is sent.
        assertTrue(lookup, lookup.indexOf("debugOffline) return TransactionLookup.Unknown(\"network_error\")") in 0 until lookup.indexOf("http.newCall("))
        // Exceptions are logged by class: a message can carry the URL, and with it the id.
        assertFalse(lookup, lookup.contains("e.message"))
        val activate = manager.substringAfter("private fun activateLocked(").substringBefore("\n    }\n")
        assertTrue(activate, activate.contains("\"Entered transaction id looked up: \$lookup\""))
        assertFalse(activate, Regex("""\$\{?(?:entry\.)?txnId""").containsMatchIn(activate))
    }
}

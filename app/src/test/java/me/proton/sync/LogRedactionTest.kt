package me.proton.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one redaction list for every copy of the log that leaves memory: the debug log and the opt-in file copy.
 * Until 2.0.2 the file copy kept the tokens the debug log removed.
 */
class LogRedactionTest {

    @Test
    fun `a token query parameter is redacted, whatever its case`() {
        assertEquals("token=<redacted>", LogRedaction.redact("token=Ab3_x-9~Zq"))
        assertEquals("Token=<redacted>", LogRedaction.redact("Token=Ab3_x-9~Zq"))
        assertEquals(
            "https://verify.proton.me/?methods=captcha&token=<redacted>",
            LogRedaction.redact("https://verify.proton.me/?methods=captcha&token=abcdefgh123"),
        )
    }

    @Test
    fun `the token keys of Proton's JSON and OAuth forms are redacted`() {
        assertEquals("{\"HumanVerificationToken\":\"<redacted>\"}", LogRedaction.redact("{\"HumanVerificationToken\":\"abcdefgh1234\"}"))
        assertEquals("\"AccessToken\": \"<redacted>\"", LogRedaction.redact("\"AccessToken\": \"abcdefgh1234\""))
        assertEquals("refresh_token=<redacted>", LogRedaction.redact("refresh_token=abcdefgh1234"))
    }

    @Test
    fun `a bearer credential is redacted`() {
        assertEquals("Authorization: Bearer <redacted>", LogRedaction.redact("Authorization: Bearer abcdefgh.ij"))
    }

    @Test
    fun `a value shorter than eight characters is left alone`() {
        // The threshold keeps words like "token=expired" readable; a real token is far longer.
        assertEquals("token=abc1234", LogRedaction.redact("token=abc1234"))
    }

    @Test
    fun `a line without a trigger word comes back as the very same string`() {
        val line = "10-24 08:15:00.000 I/CalendarSync: 12 events fetched, 0 changed"
        assertSame(line, LogRedaction.redact(line))
    }

    @Test
    fun `a stack frame that names a token class passes the trigger check and comes back unchanged`() {
        val frame = "at me.proton.sync.api.TokenRefreshAuthenticator.authenticate(TokenRefreshAuthenticator.kt:29)"
        assertEquals(frame, LogRedaction.redact(frame))
    }

    @Test
    fun `a Paddle subscription id is redacted, other ids and fragments are not`() {
        val id = "sub_" + "0".repeat(26)
        assertEquals("sub_<redacted>", LogRedaction.redact(id))
        assertEquals("id=sub_<redacted>.", LogRedaction.redact("id=$id."))
        assertEquals(
            "https://example.com/?subscription_id=sub_<redacted>&x=1",
            LogRedaction.redact("https://example.com/?subscription_id=$id&x=1"),
        )
        assertEquals("sub_123", LogRedaction.redact("sub_123"))
        assertEquals("pro_01ksf0a09fd3a9j0a70zeaeyme", LogRedaction.redact("pro_01ksf0a09fd3a9j0a70zeaeyme"))
        // One character too many is not Paddle's shape, so it is not an id.
        assertEquals("${id}0", LogRedaction.redact("${id}0"))
    }

    @Test
    fun `a Paddle subscription id after a letter or a digit is redacted, as in a URL-encoded link`() {
        // 07, not the 01 of every real Paddle id (see SubscriptionEntryTest): the source snapshot refuses real shapes.
        val id = "sub_07h04vsc0qhwtsbsxh3422wjs4"
        // A link wrapped by a mail scanner (an Outlook safelink, say) carries the id URL-encoded: "=" is %3D and
        // "/" is %2F, so a digit or a letter comes right before "sub_", where a word boundary never matched.
        assertEquals(
            "https://example.net/?url=https%3A%2F%2Fexample.com%2F%3Fsubscription_id%3Dsub_<redacted>%26x%3D1",
            LogRedaction.redact("https://example.net/?url=https%3A%2F%2Fexample.com%2F%3Fsubscription_id%3D$id%26x%3D1"),
        )
        assertEquals(
            "https%3A%2F%2Fexample.com%2Fsubscriptions%2Fsub_<redacted>",
            LogRedaction.redact("https%3A%2F%2Fexample.com%2Fsubscriptions%2F$id"),
        )
        assertEquals("%3dsub_<redacted>", LogRedaction.redact("%3d$id"))
        assertEquals("%3Dsub_<redacted>", LogRedaction.redact("%3D${id.uppercase()}"))
        // Still whole tokens only: one character more after the id is no id.
        assertEquals("%3D${id}x", LogRedaction.redact("%3D${id}x"))
    }

    @Test
    fun `a Paddle transaction id is redacted as a subscription id is, since it redeems to one`() {
        // 07, not the 01 of every real Paddle id: the source snapshot refuses real shapes.
        val id = "txn_07h04vsc0qhwtsbsxh3422wjs4"
        assertEquals("txn_<redacted>", LogRedaction.redact(id))
        assertEquals("txn_<redacted>", LogRedaction.redact(id.uppercase()))
        assertEquals("Transaction ID: txn_<redacted>.", LogRedaction.redact("Transaction ID: $id."))
        assertEquals(
            "https://example.com/txn-to-sub?txn=txn_<redacted>&x=1",
            LogRedaction.redact("https://example.com/txn-to-sub?txn=$id&x=1"),
        )
        // After a letter or a digit, as in a URL-encoded link (a checkout link's _ptxn behind a mail scanner).
        assertEquals("%3Ftxn%3Dtxn_<redacted>%26x%3D1", LogRedaction.redact("%3Ftxn%3D$id%26x%3D1"))
        // Run straight into a subscription id, each is masked.
        assertEquals(
            "txn_<redacted> sub_<redacted>",
            LogRedaction.redact("$id sub_07" + "c".repeat(24)),
        )
        // Not Paddle's shape: too short, one character too many, a letter Crockford base32 leaves out.
        assertEquals("txn_123", LogRedaction.redact("txn_123"))
        assertEquals("${id}0", LogRedaction.redact("${id}0"))
        assertEquals("txn_07h04vsc0qhwtsbsxh3422wjso", LogRedaction.redact("txn_07h04vsc0qhwtsbsxh3422wjso"))
        // The trigger word alone lets a line through to the patterns, which leave it as it was.
        val line = "TransactionLookup: txn_to_sub not_ready"
        assertEquals(line, LogRedaction.redact(line))
    }

    @Test
    fun `every form at once, as the debug verb log-redaction-probe logs them`() {
        val probe = "log_redaction_probe: token=probe0000000001 \"AccessToken\":\"probe0000000002\" " +
            "Bearer probe0000000003 sub_${"0".repeat(26)} txn_${"0".repeat(26)}"
        val out = LogRedaction.redact(probe)
        assertFalse(out, out.contains("probe000000000"))
        assertFalse(out, out.contains("0".repeat(26)))
        assertEquals(5, Regex("<redacted>").findAll(out).count())
    }

    @Test
    fun `the debug verb log-redaction-probe logs the same line as the test above`() {
        val verb = source("settings/SyncSettingsActivity.kt").substringAfter("\"log_redaction_probe\" -> {").substringBefore("FileLogController")
        assertTrue("a sub_ id in the probe", verb.contains("sub_${'$'}{\"0\".repeat(26)}"))
        assertTrue("a txn_ id in the probe", verb.contains("txn_${'$'}{\"0\".repeat(26)}"))
    }

    // --- source guards: the redaction is wired where the copies leave memory ------------------------------

    private fun source(path: String): String =
        java.io.File("src/main/java/me/proton/sync/$path").readText().replace("\r\n", "\n")

    @Test
    fun `the file copy redacts each line before it is buffered`() {
        val write = source("FileLogSink.kt").substringAfter("fun write(line: String) {").substringBefore("\n    }\n")
        val redact = write.indexOf("LogRedaction.redact(")
        assertTrue("write() redacts", redact >= 0)
        assertTrue("before the line is buffered", write.indexOf("lines.addLast(") > redact)
    }

    @Test
    fun `the debug log uses the shared list for its body and its system section`() {
        val debugLog = source("DebugLog.kt")
        assertFalse("no pattern list of its own", debugLog.contains("Regex("))
        assertEquals(2, Regex("""LogRedaction\.redact\(""").findAll(debugLog).count())
    }

    @Test
    fun `the debug log header names the install source and the licence`() {
        val header = source("DebugLog.kt").substringAfter("private fun header(").substringBefore("\n    }\n")
        assertTrue(header.contains("Install:"))
        assertTrue(header.contains("Licence:"))
        assertTrue(header.contains("LicenceSummary.line("))
    }
}

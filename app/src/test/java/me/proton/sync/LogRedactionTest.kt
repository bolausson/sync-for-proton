package me.proton.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one redaction for every copy of the log: since 2.0.4 SyncLog runs it on every line before logcat, the viewer's
 * ring and the file copy, and the debug log runs it again. Until 2.0.2 the file copy kept the tokens the debug log
 * removed, and until 2.0.4 every copy kept the email addresses of guests, organisers and the account itself.
 */
class LogRedactionTest {

    /** A fixed salt, so codes are the same in every run of the test. */
    private val salt = ByteArray(16) { it.toByte() }
    private val code = Regex("<email ([0-9a-f]{8}) at ([^>]+)>")

    private fun codes(text: String) = code.findAll(text).map { it.groupValues[1] to it.groupValues[2] }.toList()

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

    // --- email addresses ----------------------------------------------------------------------------------------

    @Test
    fun `an address becomes a code that keeps a shared provider's domain`() {
        val out = LogRedaction.redact("Sent invitation (1 E2E) to a.b_c-d+x@proton.me for event 7")
        assertTrue(out, Regex("""Sent invitation \(1 E2E\) to <email [0-9a-f]{8} at proton\.me> for event 7""").matches(out))
    }

    @Test
    fun `a domain of its own shows only its ending`() {
        assertEquals(listOf("*.com", "*.org"), codes(LogRedaction.maskAddresses("guest@example.com and x@sub.example.org", salt)).map { it.second })
    }

    @Test
    fun `one address keeps one code in any case or encoding, another address gets another`() {
        val one = listOf("Guest@Example.com", "guest@example.com", "guest%40example.com", "GUEST%40EXAMPLE.COM")
            .map { codes(LogRedaction.maskAddresses(it, salt)).single().first }.toSet()
        assertEquals(1, one.size)
        assertEquals(
            codes(LogRedaction.maskAddresses("a.b_c-d+x@proton.me", salt)).single().first,
            codes(LogRedaction.maskAddresses("a.b_c-d%2Bx%40proton.me", salt)).single().first,
        )
        val other = codes(LogRedaction.maskAddresses("abcd@proton.me", salt)).single().first
        assertFalse(other in one)
    }

    @Test
    fun `the code comes from the run's salt`() {
        val a = codes(LogRedaction.maskAddresses("guest@example.com", salt)).single().first
        val b = codes(LogRedaction.maskAddresses("guest@example.com", ByteArray(16) { 7 })).single().first
        assertFalse(a == b)
        val unsalted = java.security.MessageDigest.getInstance("SHA-256").digest("guest@example.com".toByteArray())
            .take(4).joinToString("") { "%02x".format(it) }
        assertFalse(a == unsalted)
    }

    @Test
    fun `addresses in a list, a mailto and an encoded link are masked, the text around them kept`() {
        val list = LogRedaction.maskAddresses("[a@example.com, b@example.org]", salt)
        assertTrue(list, Regex("""\[<email [0-9a-f]{8} at \*\.com>, <email [0-9a-f]{8} at \*\.org>]""").matches(list))
        val organizer = LogRedaction.maskAddresses("ORGANIZER;CN=Guest:mailto:guest@example.com", salt)
        assertTrue(organizer, organizer.startsWith("ORGANIZER;CN=Guest:mailto:<email "))
        val ics = LogRedaction.maskAddresses("https://calendar.example.com/ical/guest%40example.com/basic.ics", salt)
        assertTrue(ics, Regex("""https://calendar\.example\.com/ical/<email [0-9a-f]{8} at \*\.com>/basic\.ics""").matches(ics))
        // An encoded list is one run of address characters, so it is masked whole: nothing of either name is left.
        val encodedList = LogRedaction.maskAddresses("guest%40example.com%2Cabcd%40proton.me", salt)
        assertEquals(1, codes(encodedList).size)
        assertFalse(encodedList, encodedList.contains("guest") || encodedList.contains("abcd"))
    }

    @Test
    fun `an address with letters beyond ASCII or a punycode ending is masked whole`() {
        for (address in listOf("\u00fcn\u00efcode.t\u00ebst@example.com", "u\u0308ser@example.com", "guest@example.xn--p1ai")) {
            val out = LogRedaction.maskAddresses("to $address now", salt)
            assertEquals(out, 1, codes(out).size)
            assertTrue(out, out.startsWith("to <email ") && out.endsWith("> now"))
        }
    }

    @Test
    fun `this app's UUID event uid is no address, any other uid is masked`() {
        val uuid = "uid=123e4567-e89b-12d3-a456-426614174000@proton.me"
        assertEquals(uuid, LogRedaction.maskAddresses(uuid, salt))
        assertEquals(1, codes(LogRedaction.maskAddresses("uid=event-uid@proton.me", salt)).size)
        // The same shape anywhere else is an alias service's address (SimpleLogin, addy.io): a person, masked; and
        // this app never encodes its own uid, so an encoded one is masked too.
        for (alias in listOf("123e4567-e89b-12d3-a456-426614174000@example.com", "123e4567-e89b-12d3-a456-426614174000%40proton.me")) {
            assertEquals(alias, 1, codes(LogRedaction.maskAddresses("to $alias", salt)).size)
        }
    }

    @Test
    fun `what only looks like an address is left alone`() {
        for (text in listOf("me.proton.sync.Foo@1a2b3c", "user@localhost", "@everyone", "a@b.c", "%40example.com")) {
            assertEquals(text, LogRedaction.redact(text))
        }
    }

    @Test
    fun `a second pass changes nothing`() {
        val line = "token=probe0000000001 sub_${"0".repeat(26)} to Guest@Example.com and guest%40example.com " +
            "uid=123e4567-e89b-12d3-a456-426614174000@proton.me"
        val once = LogRedaction.redact(line)
        assertEquals(once, LogRedaction.redact(once))
    }

    @Test
    fun `long runs and deep domains neither overflow the stack nor leave the address`() {
        assertEquals(1, codes(LogRedaction.maskAddresses("a".repeat(50_000) + "@example.com", salt)).size)
        LogRedaction.maskAddresses("x@" + "a.".repeat(50_000) + "com", salt)
        assertEquals(5_000, codes(LogRedaction.maskAddresses("guest@example.com,".repeat(5_000), salt)).size)
    }

    @Test(timeout = 60_000)
    fun `a run of percent escapes is read once, not again from every escape in it`() {
        // Tried again from every '%', this took about 20 s each; measured against a plain text of the same length on
        // the same machine (as SubscriptionEntryTest's prefix runs), so a slow or busy machine does not fail it.
        fun best(text: String): Long {
            repeat(3) { LogRedaction.maskAddresses(text, salt) }
            return (1..7).minOf { kotlin.system.measureNanoTime { LogRedaction.maskAddresses(text, salt) } }
        }
        for (run in listOf("%7Ba".repeat(16_000) + " @x", "%2Ba".repeat(16_000) + " @x")) {
            val plain = best("a".repeat(run.length - 3) + " @x")
            val ratio = best(run).toDouble() / plain
            assertTrue("${run.take(8)}... took ${"%.1f".format(ratio)} times the plain text", ratio < 10)
        }
    }

    @Test
    fun `the address pattern uses no class the phone reads wider than the JVM`() {
        for (wide in listOf("\\w", "\\W", "\\s", "\\S", "\\b", "\\B", "\\d", "\\D", "(?i)", "(?u)")) {
            assertFalse(wide, LogRedaction.EMAIL_PATTERN.contains(wide))
        }
    }

    @Test
    fun `a value with no address is logged only by its length`() {
        assertEquals("<9 characters, no '@'>", LogRedaction.addressOrShape("Some Name"))
        assertEquals("a.b_c-d+x@proton.me", LogRedaction.addressOrShape("a.b_c-d+x@proton.me"))
        assertEquals("null", LogRedaction.addressOrShape(null))
    }

    @Test
    fun `every form at once, as the debug verb log-redaction-probe logs them`() {
        val probe = "log_redaction_probe: token=probe0000000001 \"AccessToken\":\"probe0000000002\" " +
            "Bearer probe0000000003 sub_${"0".repeat(26)} txn_${"0".repeat(26)} " +
            "guest=a.b_c-d+x@proton.me case=Guest@Example.com encoded=guest%40example.com " +
            "plain=guest@example.com uid=123e4567-e89b-12d3-a456-426614174000@proton.me"
        val out = LogRedaction.redact(probe)
        assertFalse(out, out.contains("probe000000000"))
        assertFalse(out, out.contains("0".repeat(26)))
        assertEquals(5, Regex("<redacted>").findAll(out).count())
        val found = codes(out)
        assertEquals(out, 4, found.size)
        assertEquals(out, 2, found.map { it.first }.toSet().size)
        assertEquals(out, listOf("proton.me", "*.com", "*.com", "*.com"), found.map { it.second })
        assertTrue(out, out.contains("uid=123e4567-e89b-12d3-a456-426614174000@proton.me"))
    }

    @Test
    fun `the debug verb log-redaction-probe logs the same line as the test above`() {
        val verb = source("settings/SyncSettingsActivity.kt").substringAfter("\"log_redaction_probe\" -> {").substringBefore("FileLogController")
        assertTrue("a sub_ id in the probe", verb.contains("sub_${'$'}{\"0\".repeat(26)}"))
        assertTrue("a txn_ id in the probe", verb.contains("txn_${'$'}{\"0\".repeat(26)}"))
        for (address in listOf("a.b_c-d+x@proton.me", "Guest@Example.com", "guest%40example.com", "plain=guest@example.com",
            "123e4567-e89b-12d3-a456-426614174000@proton.me")) {
            assertTrue(address, verb.contains(address))
        }
    }

    @Test
    fun `SyncLog redacts each line once, before logcat, the ring and the file copy see it`() {
        val syncLog = source("SyncLog.kt")
        val emit = syncLog.substringAfter("private fun emit(").substringBefore("\n    }\n")
        val redact = emit.indexOf("LogRedaction.redact(")
        assertTrue("emit() redacts", redact >= 0)
        assertTrue("before logcat", emit.indexOf("Log.println(") > redact)
        assertTrue("before the ring", emit.indexOf("LogBuffer.append(") > redact)
        // Code only: the comments name the Log.e(tag, msg, tr) this replaced.
        val code = syncLog.replace(Regex("""(?s)/\*.*?\*/"""), "").lines().joinToString("\n") { it.substringBefore("//") }
        assertFalse("no raw logcat call left", Regex("""\bLog\.[diwev]\(""").containsMatchIn(code))
    }

    @Test
    fun `every log line goes through SyncLog`() {
        val offenders = listOf("main", "direct", "play", "debug").flatMap { set ->
            java.io.File("src/$set/java").walkTopDown().filter { it.isFile && it.name.endsWith(".kt") && it.name != "SyncLog.kt" }
                .filter { f ->
                    val t = f.readText()
                    t.contains("import android.util.Log\n") || t.contains("import android.util.Log\r\n") ||
                        Regex("""android\.util\.Log\.[a-z]""").containsMatchIn(t) || t.contains("printStackTrace(") ||
                        t.contains("System.out.") || t.contains("System.err.")
                }
                .map { it.path }
                .toList()
        }
        assertTrue(offenders.joinToString("\n"), offenders.isEmpty())
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

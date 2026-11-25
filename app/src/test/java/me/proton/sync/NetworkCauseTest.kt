package me.proton.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.EOFException
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import okhttp3.internal.http2.ConnectionShutdownException
import okhttp3.internal.http2.ErrorCode
import okhttp3.internal.http2.StreamResetException as OkHttpStreamReset

/**
 * The network cause a failed sync carries in the text it records (NetworkCause), and the reason the failure notification
 * and the account card's dialog then show (SyncNotificationHelper.friendlyMessageRes). Found on a test phone on
 * 2026-11-22: the network cut during a calendar sync, a calendar whose keys could not be fetched recorded only that it
 * "could not be opened", and the notification said an unexpected error had occurred. Made-up hosts, addresses (TEST-NET)
 * and calendars only.
 */
class NetworkCauseTest {

    /**
     * A look-alike of OkHttp's HTTP/2 exception (OkHttpStreamReset): the same simple name and message, in another package.
     * Only OkHttp's own class counts, by type: R8 renames OkHttp's classes in a release build, so a check by name matched
     * in debug builds and tests only, and would match this one.
     */
    private class StreamResetException(message: String) : IOException(message)

    private companion object {
        const val NOT_OPENED = "Calendar 'cal-own1' could not be opened, so none of its events were downloaded"
        val NO_HOST = UnknownHostException("Unable to resolve host \"api.example.test\": No address associated with hostname")

        /** Each network failure as the platform or OkHttp throws it, with its cause. */
        val NETWORK = listOf(
            NO_HOST to NetworkCause.NO_CONNECTION,
            ConnectException("Failed to connect to api.example.test/192.0.2.10:443") to NetworkCause.NO_CONNECTION,
            NoRouteToHostException("No route to host") to NetworkCause.NO_CONNECTION,
            PortUnreachableException("ICMP Port Unreachable") to NetworkCause.NO_CONNECTION,
            SocketException("socket failed: EPERM (Operation not permitted)") to NetworkCause.NO_CONNECTION,
            SocketException("connect failed: ENETUNREACH (Network is unreachable)") to NetworkCause.NO_CONNECTION,
            SocketTimeoutException("timeout") to NetworkCause.TIMEOUT,
            SocketTimeoutException("failed to connect to api.example.test/192.0.2.10 (port 443) after 10000ms") to NetworkCause.TIMEOUT,
            SocketTimeoutException("Read timed out") to NetworkCause.TIMEOUT,
            InterruptedIOException("timeout") to NetworkCause.TIMEOUT,
            SocketException("Connection reset") to NetworkCause.INTERRUPTED,
            SocketException("Broken pipe") to NetworkCause.INTERRUPTED,
            SocketException("Software caused connection abort") to NetworkCause.INTERRUPTED,
            IOException("unexpected end of stream on https://api.example.test/...") to NetworkCause.INTERRUPTED,
            OkHttpStreamReset(ErrorCode.CANCEL) to NetworkCause.INTERRUPTED,
            OkHttpStreamReset(ErrorCode.REFUSED_STREAM) to NetworkCause.INTERRUPTED,
            ConnectionShutdownException() to NetworkCause.INTERRUPTED,
            SSLException("Read error: ssl=0x7b00: I/O error during system call, Connection reset by peer") to NetworkCause.INTERRUPTED,
            SSLHandshakeException("Chain validation failed") to NetworkCause.SECURE_CONNECTION,
            CertificateException("Trust anchor for certification path not found.") to NetworkCause.SECURE_CONNECTION,
        )

        /** Failures that have nothing to do with the network: an answer from Proton, the phone's own trouble. */
        val NOT_NETWORK: List<Throwable> = listOf(
            IOException("keys fetch failed for calendar cal-own1: HTTP 500"),
            IOException("passphrase fetch failed for calendar cal-own1: HTTP 422, code 2001"),
            IllegalStateException("KeyManager reports account-a still locked after unlock call"),
            IllegalArgumentException("vCard cannot be written"),
            FileNotFoundException("/data/example/missing"),
            // Gson's word for an empty or cut body: not a connection that broke off as far as anything here can tell.
            EOFException("End of input at line 1 column 1 path \$"),
            InterruptedIOException("thread interrupted"),
            RuntimeException(),
        )
    }

    private fun source(path: String) = File("src/$path").readText().replace("\r\n", "\n")

    /** [text] without KDoc, block comment lines and line comments. */
    private fun code(text: String): String =
        text.lines()
            .filterNot { it.trim().let { t -> t.startsWith("*") || t.startsWith("/*") || t.startsWith("//") } }
            .joinToString("\n") { it.substringBefore(" // ") }

    private fun squash(text: String): String = text.replace(Regex("\\s+"), " ")

    /** The text of the function that starts with [signature], up to its closing brace at four spaces. */
    private fun body(src: String, signature: String): String {
        assertTrue("$signature not found", src.contains(signature))
        return src.substringAfter(signature).substringBefore("\n    }\n")
    }

    /** [text] from the brace at [open] to the one that closes it. A template's braces pair up, so counting them does. */
    private fun block(text: String, open: Int): String {
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return text.substring(open, i + 1) }
            }
        }
        throw AssertionError("no closing brace after $open")
    }

    /** The arguments, squashed, of every call in [text] that starts with [call] (its name and the open parenthesis). */
    private fun callArgs(text: String, call: String): List<String> {
        val out = mutableListOf<String>()
        var at = text.indexOf(call)
        while (at >= 0) {
            val open = at + call.length - 1
            var depth = 0
            var end = open
            while (end < text.length) {
                when (text[end]) {
                    '(' -> depth++
                    ')' -> { depth--; if (depth == 0) break }
                }
                end++
            }
            out += squash(text.substring(open + 1, end)).trim()
            at = text.indexOf(call, end)
        }
        return out
    }

    // ---- The cause of an exception ----------------------------------------------------------------------------

    @Test
    fun `each network failure has its cause, and nothing else has one`() {
        for ((error, cause) in NETWORK) assertEquals("$error", cause, NetworkCause.of(error))
        for (error in NOT_NETWORK) assertNull("$error", NetworkCause.of(error))
        assertNull(NetworkCause.of(null))
    }

    @Test
    fun `the cause is found under the exceptions that wrap it, outermost first, and a loop of causes ends`() {
        assertEquals(NetworkCause.NO_CONNECTION, NetworkCause.of(IllegalStateException("wrapped", RuntimeException(NO_HOST))))
        // OkHttp's "unexpected end of stream" wraps an EOFException: the outer one decides.
        assertEquals(
            NetworkCause.INTERRUPTED,
            NetworkCause.of(IOException("unexpected end of stream on https://api.example.test/...", EOFException("\\n not found")))
        )
        // A refused key fetch whose exception carries no network cause stays without one.
        assertNull(NetworkCause.of(IOException("keys fetch failed for calendar cal-own1: HTTP 503", IllegalStateException("body"))))
        val a = IOException("a")
        val b = IOException("b", a)
        a.initCause(b)
        assertNull(NetworkCause.of(a))
    }

    @Test
    fun `OkHttp's reset and shutdown count by their type, which R8 keeps, and a look-alike by name does not`() {
        assertEquals(NetworkCause.INTERRUPTED, NetworkCause.of(OkHttpStreamReset(ErrorCode.PROTOCOL_ERROR)))
        assertEquals(NetworkCause.INTERRUPTED, NetworkCause.of(IllegalStateException("wrapped", ConnectionShutdownException())))
        assertNull(NetworkCause.of(StreamResetException("stream was reset: CANCEL")))
        // No check by class name stands in for the type: in a release build R8 has renamed OkHttp's classes.
        val rule = squash(code(source("main/java/me/proton/sync/NetworkCause.kt")))
        assertTrue(rule, rule.contains("is StreamResetException, is ConnectionShutdownException -> INTERRUPTED"))
        val byName = Regex("simpleName\\s*(in|==|!=)|simpleName\\s*\\.\\s*(equals|startsWith|endsWith|contains)|\"(StreamResetException|ConnectionShutdownException)\"")
        assertFalse(rule, byName.containsMatchIn(rule))
    }

    // ---- The text a run records ---------------------------------------------------------------------------------

    @Test
    fun `the recorded text keeps its own words and adds the cause last, once`() {
        val described = NetworkCause.describe(NOT_OPENED, NO_HOST)
        assertEquals("$NOT_OPENED (network: no connection, UnknownHostException)", described)
        assertEquals(NetworkCause.NO_CONNECTION, NetworkCause.carriedBy(described!!))
        // Described again (a recorded reason passed on), it does not grow.
        assertEquals(described, NetworkCause.describe(described, SocketException("Connection reset")))
        // No network cause: the text is the caller's, untouched, null included.
        assertEquals(NOT_OPENED, NetworkCause.describe(NOT_OPENED, IOException("keys fetch failed for calendar cal-own1: HTTP 500")))
        assertEquals(NOT_OPENED, NetworkCause.describe(NOT_OPENED, null))
        assertNull(NetworkCause.describe(null, null))
        assertNull(NetworkCause.describe(null, IllegalStateException("boom")))
        // An exception without a message still says what it was.
        assertEquals("SocketException (network: connection interrupted, SocketException)", NetworkCause.describe(null, SocketException()))
        assertEquals("SocketException (network: connection interrupted, SocketException)", NetworkCause.describe("  ", SocketException()))
        // The class named is the one that carried the cause, not its wrapper.
        assertEquals(
            "boom (network: timeout, SocketTimeoutException)",
            NetworkCause.describe("boom", IllegalStateException("boom", SocketTimeoutException("timeout")))
        )
    }

    @Test
    fun `only the cause at the end counts, so a calendar's name can neither hide nor fake it`() {
        assertNull(NetworkCause.carriedBy(NOT_OPENED))
        assertNull(NetworkCause.carriedBy(""))
        // A name that spells a cause is inside the text, never at its end.
        val named = "Calendar 'Team (network: no connection, X)' could not be opened, so none of its events were downloaded"
        assertNull(NetworkCause.carriedBy(named))
        assertEquals(NetworkCause.TIMEOUT, NetworkCause.carriedBy(NetworkCause.describe(named, SocketTimeoutException("timeout"))!!))
        // Every cause reads back as itself.
        for (cause in NetworkCause.entries) {
            val error = NETWORK.first { it.second == cause }.first
            assertEquals(cause, NetworkCause.carriedBy(NetworkCause.describe(NOT_OPENED, error)!!))
        }
    }

    // ---- The reason shown -----------------------------------------------------------------------------------------

    @Test
    fun `a calendar that could not be opened for want of a network shows the network text, and asks for Sync where nothing retries`() {
        val recorded = NetworkCause.describe(NOT_OPENED, NO_HOST)!!
        assertEquals(R.string.error_no_internet, SyncNotificationHelper.friendlyMessageRes(recorded, retries = true))
        assertEquals(R.string.error_no_internet_tap_sync, SyncNotificationHelper.friendlyMessageRes(recorded, retries = false))
        // What 2.1.0 recorded, which named no cause.
        assertEquals(R.string.error_unexpected, SyncNotificationHelper.friendlyMessageRes(NOT_OPENED, retries = true))
    }

    @Test
    fun `each cause shows its own text, with the Tap Sync variant exactly where Android retries no sync`() {
        val texts = mapOf(
            NetworkCause.NO_CONNECTION to (R.string.error_no_internet to R.string.error_no_internet_tap_sync),
            NetworkCause.INTERRUPTED to (R.string.error_connection_interrupted to R.string.error_connection_interrupted_tap_sync),
            // Their own texts, which promise no retry and so have no variant.
            NetworkCause.TIMEOUT to (R.string.error_timeout to R.string.error_timeout),
            NetworkCause.SECURE_CONNECTION to (R.string.error_ssl to R.string.error_ssl),
        )
        assertEquals(NetworkCause.entries.toSet(), texts.keys)
        for ((error, cause) in NETWORK) {
            val recorded = NetworkCause.describe(NOT_OPENED, error)!!
            for (masterSyncOn in listOf(true, false)) {
                for (manualOnly in listOf(true, false)) {
                    val retries = SyncScheduling.userSyncRetries(masterSyncOn = masterSyncOn, manualOnly = manualOnly)
                    val (retrying, tap) = texts.getValue(cause)
                    assertEquals("$error master=$masterSyncOn manualOnly=$manualOnly", if (retries) retrying else tap,
                        SyncNotificationHelper.friendlyMessageRes(recorded, retries))
                }
            }
        }
    }

    @Test
    fun `a failure the network did not cause keeps its text`() {
        // A key or passphrase refused by Proton, a row that could not be written: no cause added, the words decide.
        for (error in NOT_NETWORK) {
            val recorded = NetworkCause.describe(NOT_OPENED, error)!!
            for (retries in listOf(true, false)) {
                assertEquals("$error", R.string.error_unexpected, SyncNotificationHelper.friendlyMessageRes(recorded, retries))
            }
        }
        // A thrown error keeps its words' text: the session, Proton's answers.
        val kept = listOf(
            "Auth failed fetching contacts: HTTP 401" to R.string.error_session_expired,
            "Failed to fetch calendars: HTTP 422" to R.string.error_request_rejected,
            "Failed to fetch calendars: HTTP 503" to R.string.error_server_error,
        )
        for ((message, res) in kept) {
            assertEquals(message, res, SyncNotificationHelper.friendlyMessageRes(NetworkCause.describe(message, Exception(message))!!, true))
        }
        // An exception's message alone, as before: the words still decide where no cause was written.
        assertEquals(R.string.error_timeout, SyncNotificationHelper.friendlyMessageRes("java.net.SocketTimeoutException: timeout", true))
        assertEquals(R.string.error_unexpected, SyncNotificationHelper.friendlyMessageRes("Connection reset", true))
        assertEquals(
            R.string.error_connection_interrupted,
            SyncNotificationHelper.friendlyMessageRes(NetworkCause.describe("Connection reset", SocketException("Connection reset"))!!, true)
        )
    }

    @Test
    fun `the cause decides before the words, which a calendar's name can hold`() {
        val named = "Could not read the keys for the external calendar 'Unauthorized visits'; its events may be missing details this run"
        // The words alone mistake it for an expired session, and a name holding "timeout" for a timeout.
        assertEquals(R.string.error_session_expired, SyncNotificationHelper.friendlyMessageRes(named, true))
        assertEquals(R.string.error_timeout, SyncNotificationHelper.friendlyMessageRes(named.replace("Unauthorized", "Timeout"), true))
        assertEquals(R.string.error_no_internet_tap_sync, SyncNotificationHelper.friendlyMessageRes(NetworkCause.describe(named, NO_HOST)!!, false))
        assertEquals(
            R.string.error_connection_interrupted_tap_sync,
            SyncNotificationHelper.friendlyMessageRes(NetworkCause.describe(named, SocketException("Connection reset"))!!, false)
        )
    }

    // ---- Recording --------------------------------------------------------------------------------------------------

    @Test
    fun `a recorded failure carries its exception's cause, and the first one recorded still wins`() {
        SyncUtils.recordSwallowedFailure("calendar", "net-cause-a@example.test", NOT_OPENED, NO_HOST)
        SyncUtils.recordSwallowedFailure("calendar", "net-cause-a@example.test", "later", SocketException("Connection reset"))
        assertEquals("$NOT_OPENED (network: no connection, UnknownHostException)", SyncUtils.consumeSwallowedFailure("calendar", "net-cause-a@example.test"))
        // Without an exception, or with one the network did not cause, the message as given.
        SyncUtils.recordSwallowedFailure("contacts", "net-cause-b@example.test", "plain", IllegalStateException("x"))
        assertEquals("plain", SyncUtils.consumeSwallowedFailure("contacts", "net-cause-b@example.test"))
        // A network exception without a message is still a reason.
        SyncUtils.recordSwallowedFailure("contacts", "net-cause-c@example.test", null, SocketException())
        assertEquals("SocketException (network: connection interrupted, SocketException)", SyncUtils.consumeSwallowedFailure("contacts", "net-cause-c@example.test"))
        SyncUtils.recordSwallowedFailure("contacts", "net-cause-d@example.test", null, IllegalStateException())
        assertNull(SyncUtils.consumeSwallowedFailure("contacts", "net-cause-d@example.test"))
    }

    // ---- Where it is wired ------------------------------------------------------------------------------------------

    @Test
    fun `the thrown error's text carries its cause too, and the first recorded reason stands in for none`() {
        val post = squash(code(body(source("main/java/me/proton/sync/SyncUtils.kt"), "fun savePostSyncResult(")))
        assertTrue(post, post.contains("val reason = NetworkCause.describe(err?.message, err) ?: swallowed"))
        assertTrue(post, post.contains("val errMsg = reason ?: \"Sync incomplete\""))
        assertTrue(post, post.contains("SyncLog.w(tag, \"\$syncTypeLabel sync FAILED for \$parentAccountName: \${reason ?: \"no reason recorded\"}\")"))
        val record = squash(code(body(source("main/java/me/proton/sync/SyncUtils.kt"), "fun recordSwallowedFailure(")))
        assertTrue(record, record.contains("val text = NetworkCause.describe(message, cause) if (text.isNullOrBlank()) return swallowedFailures.putIfAbsent(\"\$syncType:\$accountName\", text)"))
        val rule = squash(code(body(source("main/java/me/proton/sync/SyncNotificationHelper.kt"), "internal fun friendlyMessageRes(")))
        // The cause first, before the first of the words.
        assertTrue(rule, rule.indexOf("when (NetworkCause.carriedBy(rawError))") in 0 until rule.indexOf("val lower = rawError.lowercase()"))
    }

    @Test
    fun `a calendar that could not be opened records its reason where the exception is`() {
        val adapter = source("main/java/me/proton/sync/calendar/CalendarSyncAdapter.kt")
        val job = squash(code(body(adapter, "private suspend fun prepareCalendarJob(")))
        val recorded = job.indexOf("recordCalendarNotOpened(account, calInfo, e) return null")
        assertTrue(job, recorded >= 0 && job.indexOf("Skipping ENTIRE personal calendar") in 0 until recorded)
        // The external calendar's reason carries it as well.
        assertTrue(job, job.contains("its events may be missing details this run\", e )"))
        // The caller's own record stays, for a row that could not be created; a no-op once a reason is in.
        val perform = squash(code(body(adapter, "private suspend fun performCalendarSync(")))
        assertTrue(perform, perform.contains("synchronized(syncResult.stats) { syncResult.stats.numIoExceptions++ } recordCalendarNotOpened(account, calInfo, null) continue"))
        val notOpened = squash(code(adapter.substringAfter("private fun recordCalendarNotOpened(").substringBefore("\n\n")))
        assertTrue(notOpened, notOpened.contains("\"Calendar '\${calInfo.name ?: calInfo.id.take(8)}' could not be opened, so none of its events were downloaded\", cause )"))
    }

    @Test
    fun `every recorded reason that quotes an exception's message passes the exception`() {
        val files = listOf(
            "main/java/me/proton/sync/calendar/CalendarSyncAdapter.kt",
            "main/java/me/proton/sync/contacts/ContactsSyncAdapter.kt",
        )
        var quoting = 0
        for (path in files) {
            val text = code(source(path))
            var at = text.indexOf("SyncUtils.recordSwallowedFailure(")
            while (at >= 0) {
                val open = text.indexOf('(', at)
                var depth = 0
                var end = open
                while (end < text.length) {
                    when (text[end]) {
                        '(' -> depth++
                        ')' -> { depth--; if (depth == 0) break }
                    }
                    end++
                }
                val args = squash(text.substring(open + 1, end)).trim()
                if (args.contains("e.message")) {
                    quoting++
                    assertTrue("$path: $args", args.endsWith(", e"))
                }
                at = text.indexOf("SyncUtils.recordSwallowedFailure(", end)
            }
        }
        // The four calendar sites that quote one today: a calendar's sync, the upload context, an event upload, a
        // deleted occurrence.
        assertEquals(4, quoting)
    }

    @Test
    fun `a batch of new contacts that could not be sent names its cause`() {
        val flush = squash(code(body(source("main/java/me/proton/sync/contacts/ContactsSyncAdapter.kt"), "private suspend fun flushBatchCreate(")))
        val counted = flush.indexOf("syncResult.stats.numIoExceptions++ SyncUtils.recordSwallowedFailure(\"contacts\", parentAccountName, \"Could not send \${creates.size} new contact(s) to Proton\", e) return")
        assertTrue(flush, counted >= 0 && flush.indexOf("Could not reach Proton to create") in 0 until counted)
    }

    @Test
    fun `every catch that counts a failure the network can cause records its reason with the exception`() {
        // A catch of any exception is where a network failure lands. Counted with no reason, the run read "Sync
        // incomplete", an unexpected error, whatever the cause: a deleted event's DELETE was the last calendar one.
        val expected = mapOf(
            // A calendar's sync, the upload context, a deleted event, an event upload, a deleted occurrence, and since
            // 2.2.0 (owner, 2026-11-23) a deletion of the deletion pass (reconcileDeletedEvents), which counted
            // nothing before.
            "main/java/me/proton/sync/calendar/CalendarSyncAdapter.kt" to 6,
            // The upload loop (an edited contact) and a batch of new contacts.
            "main/java/me/proton/sync/contacts/ContactsSyncAdapter.kt" to 2,
        )
        val general = Regex("catch \\(e: (Exception|java\\.io\\.IOException|IOException|Throwable)\\) \\{")
        for ((path, count) in expected) {
            val text = code(source(path))
            var counting = 0
            for (match in general.findAll(text)) {
                val caught = block(text, match.range.last)
                if (!caught.contains("numIoExceptions++")) continue
                counting++
                val recorded = callArgs(caught, "SyncUtils.recordSwallowedFailure(") + callArgs(caught, "recordCalendarNotOpened(")
                assertTrue("$path: ${squash(caught)}", recorded.any { it.endsWith(", e") })
            }
            assertEquals(path, count, counting)
        }
    }

    @Test
    fun `an edited contact the connection did not carry stays pending and names its cause`() {
        val loop = squash(code(body(source("main/java/me/proton/sync/contacts/ContactsSyncAdapter.kt"), "private suspend fun uploadDirtyContacts(")))
        val start = "} else if (me.proton.sync.NetworkCause.of(e) != null) {"
        val at = loop.indexOf(start)
        // After the deletion's and the new contact's branches, ahead of the one that clears the flag for anything else.
        assertTrue(loop, loop.indexOf("} else if (mapping == null) {") in 0 until at)
        val clearing = loop.indexOf("clearing dirty flag\", e) SyncUtils.clearContactDirtyFlag(")
        assertTrue(loop, at < clearing)
        val branch = loop.substring(at + start.length, loop.lastIndexOf("} else {", clearing))
        assertTrue(branch, branch.contains(
            "syncResult.stats.numIoExceptions++ SyncUtils.recordSwallowedFailure(\"contacts\", parentAccountName, \"Could not send an edited contact to Proton\", e)"
        ))
        assertFalse(branch, branch.contains("clearContactDirtyFlag"))
    }
}

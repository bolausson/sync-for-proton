package me.proton.sync.calendar

import me.proton.sync.NetworkCause
import me.proton.sync.R
import me.proton.sync.SyncNotificationHelper
import me.proton.sync.SyncScheduling
import me.proton.sync.SyncUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

/**
 * A calendar whose keys or passphrases could not be read (CalendarKeysFailure, CalendarSyncAdapter.prepareCalendarJob).
 * An external calendar (subscribed or holiday) whose keys the NETWORK kept from it went on without them: the events that
 * run downloaded were rebuilt from their unencrypted part alone and replaced what the phone showed, and the run reported
 * success. Since 2.2.0 it is skipped for the run like a personal calendar (owner, 2026-11-23), and so is one Proton
 * answered with a transient status (408, 429, 5xx, and 401 since the owner's decision of 2026-11-23); one Proton
 * gives no keys still syncs as before. Made-up hosts, addresses (TEST-NET) and calendars only.
 */
class CalendarKeysFailureTest {

    private companion object {
        const val ADAPTER = "src/main/java/me/proton/sync/calendar/CalendarSyncAdapter.kt"

        /** How the platform and OkHttp throw a request the network broke. */
        val NETWORK: List<Throwable> = listOf(
            UnknownHostException("Unable to resolve host \"api.example.test\": No address associated with hostname"),
            ConnectException("Failed to connect to api.example.test/192.0.2.10:443"),
            SocketTimeoutException("timeout"),
            SocketException("Connection reset"),
            SSLHandshakeException("Chain validation failed"),
            // As a coroutine or a wrapper hands it on.
            IllegalStateException("wrapped", SocketException("Software caused connection abort")),
        )

        /**
         * Proton's answers about the moment rather than the calendar (CalendarKeysFailure.isTransientStatus): a timeout,
         * too many requests, a server error. No network cause, yet an external calendar is skipped for them too (owner,
         * 2026-11-23), where it used to sync on and rebuild its events from their unencrypted part.
         */
        val TRANSIENT: List<CalendarKeysFailure.Refused> = listOf(
            // Moved here from NOT_NETWORK below (owner, 2026-11-23): a 401 the token refresh did not cure says no
            // more about the calendar's keys than a 503 does.
            CalendarKeysFailure.Refused(401, "keys fetch failed for calendar cal-ext1: HTTP 401"),
            CalendarKeysFailure.Refused(408, "keys fetch failed for calendar cal-ext1: HTTP 408"),
            CalendarKeysFailure.Refused(429, "keys fetch failed for calendar cal-ext1: HTTP 429"),
            // Moved here from NOT_NETWORK below, where it synced on without keys until this change.
            CalendarKeysFailure.Refused(500, "passphrase fetch failed for calendar cal-ext1: HTTP 500"),
            CalendarKeysFailure.Refused(502, "keys fetch failed for calendar cal-ext1: HTTP 502"),
            CalendarKeysFailure.Refused(503, "passphrase fetch failed for calendar cal-ext1: HTTP 503"),
            CalendarKeysFailure.Refused(504, "keys fetch failed for calendar cal-ext1: HTTP 504"),
        )

        /**
         * Answers from Proton that say something about the calendar (the expected answer of one without keys among them)
         * and failures on the phone: no network cause and no transient status, so an external calendar syncs as before.
         */
        val NOT_NETWORK: List<Throwable> = listOf(
            // fetchCalendarKeys and fetchCalendarPassphrases throw these for an unsuccessful answer, and threw them as
            // plain IOExceptions with the same message before the status was carried.
            CalendarKeysFailure.Refused(422, "keys fetch failed for calendar cal-ext1: HTTP 422"),
            CalendarKeysFailure.Refused(404, "keys fetch failed for calendar cal-ext1: HTTP 404"),
            // Not among the statuses the owner's decisions name (401, 408, 429, 5xx): an external calendar syncs on, as before.
            CalendarKeysFailure.Refused(403, "passphrase fetch failed for calendar cal-ext1: HTTP 403"),
            IOException("keys fetch failed for calendar cal-ext1: HTTP 422"),
            IOException("keys fetch failed for calendar cal-ext1: HTTP 404"),
            IOException("passphrase fetch failed for calendar cal-ext1: HTTP 500"),
            IllegalStateException("no member for this address"),
            EOFException("End of input at line 1 column 1 path \$"),
        )
    }

    private val adapter: String by lazy { File(ADAPTER).readText().replace("\r\n", "\n") }

    /** [text] without KDoc, block comment lines and line comments. */
    private fun code(text: String): String =
        text.lines()
            .filterNot { it.trim().let { t -> t.startsWith("*") || t.startsWith("/*") || t.startsWith("//") } }
            .joinToString("\n") { it.substringBefore(" // ") }

    private fun squash(text: String): String = text.replace(Regex("\\s+"), " ")

    /** The text of the function that starts with [signature], up to its closing brace at four spaces. */
    private fun body(signature: String): String {
        assertTrue("$signature not found", adapter.contains(signature))
        return adapter.substringAfter(signature).substringBefore("\n    }\n")
    }

    // ---- The rule -------------------------------------------------------------------------------------------------

    @Test
    fun `a personal calendar whose keys could not be read is skipped, whatever the cause`() {
        for (error in NETWORK + TRANSIENT + NOT_NETWORK) assertTrue("$error", CalendarKeysFailure.skipsCalendar(external = false, error = error))
    }

    @Test
    fun `an external calendar whose keys the network kept from it is skipped like a personal one`() {
        for (error in NETWORK) {
            assertTrue("$error", NetworkCause.of(error) != null)
            assertTrue("$error", CalendarKeysFailure.skipsCalendar(external = true, error = error))
        }
    }

    @Test
    fun `an external calendar Proton gave no keys still syncs from the unencrypted part, as before`() {
        for (error in NOT_NETWORK) {
            assertTrue("$error", NetworkCause.of(error) == null)
            assertFalse("$error", CalendarKeysFailure.skipsCalendar(external = true, error = error))
        }
    }

    @Test
    fun `an external calendar Proton answered with a transient status is skipped like one the network kept its keys from`() {
        for (error in TRANSIENT) {
            // No network cause: the status alone decides.
            assertNull("$error", NetworkCause.of(error))
            assertTrue("$error", CalendarKeysFailure.isTransientStatus(CalendarKeysFailure.refusedStatus(error)))
            assertTrue("$error", CalendarKeysFailure.skipsCalendar(external = true, error = error))
        }
        // Exactly 401, 408, 429 and 5xx: every other status, and no status at all, is about the calendar.
        val transient = (100..599).filter { CalendarKeysFailure.isTransientStatus(it) }
        assertEquals(listOf(401, 408, 429) + (500..599).toList(), transient)
        assertFalse(CalendarKeysFailure.isTransientStatus(null))
        // A plain IOException carries no status whatever its words say, as before the status was carried.
        assertFalse(CalendarKeysFailure.skipsCalendar(external = true, error = IOException("keys fetch failed for calendar cal-ext1: HTTP 503")))
    }

    @Test
    fun `an external calendar skipped for a transient status names the status, which picks the text`() {
        // The skip records through recordCalendarNotOpened, whose first reason carries Proton's status (guarded below).
        val texts = mapOf(
            503 to (R.string.error_server_error to R.string.error_server_error_tap_sync),
            429 to (R.string.error_too_many_requests to R.string.error_too_many_requests_tap_sync),
            // friendlyMessageRes's existing rule for a 4xx it has no text of its own for.
            408 to (R.string.error_request_rejected to R.string.error_request_rejected),
            // The session text, which promises no retry and has no variant.
            401 to (R.string.error_session_expired to R.string.error_session_expired),
        )
        for ((status, pair) in texts) {
            val refused = TRANSIENT.first { it.httpCode == status }
            for (retries in listOf(true, false)) {
                val account = "keys-transient-$status-$retries@example.test"
                SyncUtils.recordSwallowedFailure("calendar", account, CalendarKeysFailure.reason("calExtTransient01", CalendarKeysFailure.refusedStatus(refused)!!))
                SyncUtils.recordSwallowedFailure("calendar", account, "Calendar 'cal-ext1' could not be opened, so none of its events were downloaded", refused)
                assertEquals("HTTP $status retries=$retries", if (retries) pair.first else pair.second, shown(account, retries))
            }
        }
    }

    // ---- Where it is wired ----------------------------------------------------------------------------------------

    @Test
    fun `the keys failure is decided by the rule, and only the skipping branch returns null`() {
        val job = squash(code(body("private suspend fun prepareCalendarJob(")))
        val decide = "} catch (e: Exception) { if (!CalendarKeysFailure.skipsCalendar(isExternalCalendar, e)) {"
        val at = job.indexOf(decide)
        assertTrue(job, at >= 0)
        // The external branch alone no longer decides: a network failure would still sync without keys.
        assertFalse(job, job.contains("if (isExternalCalendar) { SyncLog.w(TAG, \"No keys for external calendar"))
        val rest = job.substring(at + decide.length)
        val noKeys = rest.substringBefore("} else {")
        // No keys from Proton: today's behaviour. The reason is recorded, nothing is counted, and the calendar syncs on.
        assertTrue(noKeys, noKeys.contains("SyncLog.w(TAG, \"No keys for external calendar \${calInfo.id} (expected): \${e.message}\")"))
        assertTrue(noKeys, noKeys.contains("its events may be missing details this run\", e )"))
        // As a note, which fails nothing and never takes the place of a failure the run records (SyncUtils).
        assertTrue(noKeys, noKeys.contains("SyncUtils.recordSwallowedNote( \"calendar\", account.name, \"Could not read the keys for the external calendar"))
        assertFalse(noKeys, noKeys.contains("SyncUtils.recordSwallowedFailure("))
        assertFalse(noKeys, noKeys.contains("return null"))
        assertFalse(noKeys, noKeys.contains("numIoExceptions"))
        assertFalse(noKeys, noKeys.contains("recordCalendarNotOpened("))
        // Skipped: the external calendar and the personal one end the same way, the reason with its cause, then null,
        // which performCalendarSync counts as a failed run (NetworkCauseTest guards that count and this record).
        val skip = rest.substringAfter("} else {")
        val external = skip.indexOf("if (isExternalCalendar) { SyncLog.e(TAG, \"Skipping external calendar")
        val personal = skip.indexOf("} else { SyncLog.e(TAG, \"Skipping ENTIRE personal calendar")
        val returned = skip.indexOf("} recordCalendarNotOpened(account, calInfo, e) return null }")
        assertTrue(skip, external >= 0 && personal > external && returned > personal)
    }

    @Test
    fun `the rule reads only the calendar's kind, the network cause and Proton's transient status`() {
        val rule = squash(code(File("src/main/java/me/proton/sync/calendar/CalendarKeysFailure.kt").readText().replace("\r\n", "\n")))
        assertTrue(rule, rule.contains(
            "fun skipsCalendar(external: Boolean, error: Throwable): Boolean = " +
                "!external || NetworkCause.of(error) != null || isTransientStatus(refusedStatus(error))"
        ))
        assertTrue(rule, rule.contains(
            "fun isTransientStatus(httpCode: Int?): Boolean = " +
                "httpCode != null && (httpCode == 401 || httpCode == 408 || httpCode == 429 || httpCode >= 500)"
        ))
    }

    @Test
    fun `an external calendar whose keys read ends in 401 is skipped, while the expected no-keys answer still syncs on`() {
        // Owner decision of 2026-11-23: the token refresh did not help, so the 401 reached the keys fetch, which
        // threw it as Proton's refusal. It says nothing about the keys: skipped like 408, 429 and 5xx, for both fetches.
        for (fetch in listOf("keys", "passphrase")) {
            val refused = CalendarKeysFailure.Refused(401, "$fetch fetch failed for calendar cal-ext1: HTTP 401")
            assertNull(NetworkCause.of(refused))
            assertTrue(fetch, CalendarKeysFailure.skipsCalendar(external = true, error = refused))
            assertTrue(fetch, CalendarKeysFailure.skipsCalendar(external = false, error = refused))
        }
        // The same statuses the contacts and the event deletions count as passing.
        for (status in 100..599) {
            assertEquals("$status", me.proton.sync.contacts.ContactUploadRefusals.isTransientHttp(status), CalendarKeysFailure.isTransientStatus(status))
        }
        // The expected answer of a calendar that has no keys, and every other status about the calendar, sync on from the
        // unencrypted part, as before: nothing else moved.
        for (status in listOf(400, 403, 404, 409, 410, 422)) {
            val aboutTheCalendar = CalendarKeysFailure.Refused(status, "keys fetch failed for calendar cal-ext1: HTTP $status")
            assertFalse("$status", CalendarKeysFailure.skipsCalendar(external = true, error = aboutTheCalendar))
        }
        // A plain IOException with the words of a 401 carries no status: as before.
        assertFalse(CalendarKeysFailure.skipsCalendar(external = true, error = IOException("keys fetch failed for calendar cal-ext1: HTTP 401")))
    }

    // ---- Proton's refusal, and the note of an external calendar without keys (2.2.0, 2026-11-23) --------------------
    //
    // Both records come in the preparation, ahead of every calendar's download, and a run names the first reason it
    // recorded. The external calendar's note fails nothing, yet it took the place of every later failure's reason (a
    // listing's HTTP 429, a network cause), and a personal calendar Proton refused the keys of recorded no status: the
    // user read an unexpected error either way.

    /** The text the first reason a run records, read back as savePostSyncResult does, shows. */
    private fun shown(account: String, retries: Boolean): Int =
        SyncNotificationHelper.friendlyMessageRes(SyncUtils.consumeSwallowedFailure("calendar", account)!!, retries)

    @Test
    fun `Proton's refusal of a calendar's keys carries its status, keeps its message and has no network cause`() {
        val message = "keys fetch failed for calendar cal-own1: HTTP 503"
        val refused = CalendarKeysFailure.Refused(503, message)
        // Still an IOException with the old message, so every catch and every recorded e.message reads as before.
        val asThrown: IOException = refused
        assertEquals(message, asThrown.message)
        assertNull(NetworkCause.of(refused))
        assertEquals(503, CalendarKeysFailure.refusedStatus(refused))
        assertEquals(429, CalendarKeysFailure.refusedStatus(CalendarKeysFailure.Refused(429, "passphrase fetch failed for calendar cal-own1: HTTP 429")))
        // Nothing else carries one: not the same words in a plain IOException, not a network failure, not nothing.
        assertNull(CalendarKeysFailure.refusedStatus(IOException(message)))
        for (error in NETWORK) assertNull("$error", CalendarKeysFailure.refusedStatus(error))
        assertNull(CalendarKeysFailure.refusedStatus(null))
        // A personal calendar is skipped, as before. An external one is skipped for this 503 too since the owner's
        // decision of 2026-11-23 (a transient status), and syncs on, as before, for a status about the calendar.
        assertTrue(CalendarKeysFailure.skipsCalendar(external = false, error = refused))
        assertTrue(CalendarKeysFailure.skipsCalendar(external = true, error = refused))
        val aboutTheCalendar = CalendarKeysFailure.Refused(422, "keys fetch failed for calendar cal-own1: HTTP 422")
        assertTrue(CalendarKeysFailure.skipsCalendar(external = false, error = aboutTheCalendar))
        assertFalse(CalendarKeysFailure.skipsCalendar(external = true, error = aboutTheCalendar))
    }

    @Test
    fun `a calendar skipped for Proton's refusal names its status by the start of its id, which picks the text`() {
        assertEquals(
            "The keys of calendar 'calKeysT' could not be read, so none of its events were downloaded: HTTP 503",
            CalendarKeysFailure.reason("calKeysTest0123456789abcdefghijklmnopqrstuvwxyz==", 503)
        )
        val texts = mapOf(
            500 to (R.string.error_server_error to R.string.error_server_error_tap_sync),
            503 to (R.string.error_server_error to R.string.error_server_error_tap_sync),
            429 to (R.string.error_too_many_requests to R.string.error_too_many_requests_tap_sync),
            // The texts that promise no retry have no variant.
            401 to (R.string.error_session_expired to R.string.error_session_expired),
            422 to (R.string.error_request_rejected to R.string.error_request_rejected),
        )
        for ((status, pair) in texts) {
            for (masterSyncOn in listOf(true, false)) {
                for (manualOnly in listOf(true, false)) {
                    val retries = SyncScheduling.userSyncRetries(masterSyncOn = masterSyncOn, manualOnly = manualOnly)
                    val account = "keys-refused-$status-$masterSyncOn-$manualOnly@example.test"
                    SyncUtils.recordSwallowedFailure("calendar", account, CalendarKeysFailure.reason("calKeysTest0123", status))
                    assertEquals("HTTP $status retries=$retries", if (retries) pair.first else pair.second, shown(account, retries))
                }
            }
        }
        // Without the status, as before: an unexpected error, whatever Proton answered.
        val before = "Calendar 'cal-own1' could not be opened, so none of its events were downloaded"
        assertEquals(R.string.error_unexpected, SyncNotificationHelper.friendlyMessageRes(before, true))
    }

    @Test
    fun `the no-keys note never takes the place of a failure, and stands in only for a run that recorded none`() {
        val note = "Could not read the keys for the external calendar 'cal-ext1'; its events may be missing details this run"
        val listing = EventListingFailure.reason("cal-own2-0123456789", 429)
        // Recorded first, as the preparation does, then a listing Proton rate-limited: the listing's status shows.
        val a = "keys-note-a@example.test"
        SyncUtils.recordSwallowedNote("calendar", a, note, CalendarKeysFailure.Refused(422, "keys fetch failed for calendar cal-ext1: HTTP 422"))
        SyncUtils.recordSwallowedFailure("calendar", a, listing, null)
        assertEquals(listing, SyncUtils.consumeSwallowedFailure("calendar", a))
        // Both drained: nothing of this run reaches the next one.
        assertNull(SyncUtils.consumeSwallowedFailure("calendar", a))
        SyncUtils.recordSwallowedNote("calendar", a, note)
        SyncUtils.recordSwallowedFailure("calendar", a, listing)
        assertEquals(R.string.error_too_many_requests_tap_sync, shown(a, retries = false))
        // An external calendar skipped for want of a network after the note: the network's text.
        val b = "keys-note-b@example.test"
        SyncUtils.recordSwallowedNote("calendar", b, note)
        SyncUtils.recordSwallowedFailure(
            "calendar", b, "Calendar 'cal-ext2' could not be opened, so none of its events were downloaded",
            UnknownHostException("Unable to resolve host \"api.example.test\": No address associated with hostname")
        )
        assertEquals(R.string.error_no_internet_tap_sync, shown(b, retries = false))
        // A failure recorded first wins too, and of two notes the first.
        val c = "keys-note-c@example.test"
        SyncUtils.recordSwallowedFailure("calendar", c, listing)
        SyncUtils.recordSwallowedNote("calendar", c, note)
        assertEquals(listing, SyncUtils.consumeSwallowedFailure("calendar", c))
        assertNull(SyncUtils.consumeSwallowedFailure("calendar", c))
        // Alone, the note is what a run that failed without a reason names, once, as before.
        val d = "keys-note-d@example.test"
        SyncUtils.recordSwallowedNote("calendar", d, note)
        SyncUtils.recordSwallowedNote("calendar", d, "a later note")
        assertEquals(note, SyncUtils.consumeSwallowedFailure("calendar", d))
        assertNull(SyncUtils.consumeSwallowedFailure("calendar", d))
        // Written as a failure is: a network cause after it, nothing for an empty text, and per sync type.
        val e = "keys-note-e@example.test"
        SyncUtils.recordSwallowedNote("calendar", e, note, SocketException("Connection reset"))
        assertEquals("$note (network: connection interrupted, SocketException)", SyncUtils.consumeSwallowedFailure("calendar", e))
        SyncUtils.recordSwallowedNote("calendar", e, "  ")
        SyncUtils.recordSwallowedNote("contacts", e, note)
        assertNull(SyncUtils.consumeSwallowedFailure("calendar", e))
        assertEquals(note, SyncUtils.consumeSwallowedFailure("contacts", e))
    }

    @Test
    fun `the keys fetches throw Proton's refusal with its status, and a skipped calendar records the status first`() {
        for (fetch in listOf("private suspend fun fetchCalendarKeys(", "private suspend fun fetchCalendarPassphrases(")) {
            val body = squash(code(body(fetch)))
            assertTrue(body, body.contains("if (!response.isSuccessful) {"))
            assertTrue(body, body.contains("throw CalendarKeysFailure.Refused( response.code(),"))
            assertFalse(body, body.contains("IOException("))
        }
        assertTrue(squash(code(body("private suspend fun fetchCalendarKeys("))).contains(
            "\"keys fetch failed for calendar \$calendarId: HTTP \${response.code()}\${response.protonErrorSuffix()}\" )"
        ))
        assertTrue(squash(code(body("private suspend fun fetchCalendarPassphrases("))).contains(
            "\"passphrase fetch failed for calendar \$calendarId: HTTP \${response.code()}\" + ProtonApiError.describe(errorBody) )"
        ))
        // The status's reason ahead of "could not be opened" (NetworkCauseTest guards that one), which then adds nothing.
        val notOpened = squash(code(adapter.substringAfter("private fun recordCalendarNotOpened(").substringBefore("\n\n")))
        val status = notOpened.indexOf(
            "CalendarKeysFailure.refusedStatus(cause)?.let { status -> " +
                "SyncUtils.recordSwallowedFailure(\"calendar\", account.name, CalendarKeysFailure.reason(calInfo.id, status)) }"
        )
        assertTrue(notOpened, status >= 0 && status < notOpened.indexOf("could not be opened, so none of its events were downloaded"))
        assertFalse(adapter.contains("suspend fun recordCalendarNotOpened("))
        // The note goes to its own slot, which the run reads only after its failures, and both are drained.
        val syncUtils = File("src/main/java/me/proton/sync/SyncUtils.kt").readText().replace("\r\n", "\n")
        val record = squash(code(syncUtils.substringAfter("fun recordSwallowedNote(").substringBefore("\n    }\n")))
        assertTrue(record, record.contains("swallowedNotes.putIfAbsent(\"\$syncType:\$accountName\", text)"))
        assertFalse(record, record.contains("swallowedFailures"))
        val consume = squash(code(syncUtils.substringAfter("fun consumeSwallowedFailure(").substringBefore("\n    }\n")))
        assertTrue(consume, consume.contains("val note = swallowedNotes.remove(key) return swallowedFailures.remove(key) ?: note"))
    }
}

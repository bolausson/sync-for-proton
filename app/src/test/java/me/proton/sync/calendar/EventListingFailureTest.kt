package me.proton.sync.calendar

import me.proton.sync.R
import me.proton.sync.SyncNotificationHelper
import me.proton.sync.SyncScheduling
import me.proton.sync.SyncUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.EOFException
import java.io.File
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * A calendar whose event listing failed (CalendarSyncAdapter: fetchEventsFlat and fetchEventsDense return null, then
 * syncCalendarEvents stops before anything of that calendar is written). It returned without counting a failure or
 * recording a reason, so the run reported success although that calendar downloaded nothing, and with Android's
 * automatic sync off nothing healed it until the user tapped Sync. Since 2.2.0 the run fails with a reason that
 * carries the cause: the network's for an exception, Proton's status for an answer (owner, 2026-11-23). Made-up
 * hosts, accounts and calendar ids only.
 */
class EventListingFailureTest {

    private companion object {
        const val ADAPTER = "src/main/java/me/proton/sync/calendar/CalendarSyncAdapter.kt"
        const val CALENDAR = "calListTest0123456789abcdefghijklmnopqrstuvwxyz=="
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

    /** The text Proton's [code] or the exception [error] gives the failure, recorded and read back as a run does. */
    private fun shown(code: Int?, error: Throwable?, retries: Boolean, account: String): Int {
        SyncUtils.recordSwallowedFailure("calendar", account, EventListingFailure.reason(CALENDAR, code), error)
        val recorded = SyncUtils.consumeSwallowedFailure("calendar", account)!!
        return SyncNotificationHelper.friendlyMessageRes(recorded, retries)
    }

    // ---- The reason ----------------------------------------------------------------------------------------------

    @Test
    fun `the reason names the calendar by the start of its id and carries Proton's status`() {
        assertEquals(
            "The events of calendar 'calListT' could not be listed, so none of them were downloaded: HTTP 503",
            EventListingFailure.reason(CALENDAR, 503)
        )
        assertEquals(
            "The events of calendar 'calListT' could not be listed, so none of them were downloaded",
            EventListingFailure.reason(CALENDAR, null)
        )
    }

    @Test
    fun `Proton's status picks the text, with Tap Sync exactly where Android retries no sync`() {
        val texts = mapOf(
            500 to (R.string.error_server_error to R.string.error_server_error_tap_sync),
            502 to (R.string.error_server_error to R.string.error_server_error_tap_sync),
            503 to (R.string.error_server_error to R.string.error_server_error_tap_sync),
            429 to (R.string.error_too_many_requests to R.string.error_too_many_requests_tap_sync),
            // The texts that promise no retry have no variant.
            401 to (R.string.error_session_expired to R.string.error_session_expired),
            422 to (R.string.error_request_rejected to R.string.error_request_rejected),
            // The windowed fetch's "Time window is too big" at the smallest window.
            400 to (R.string.error_request_rejected to R.string.error_request_rejected),
        )
        for ((code, pair) in texts) {
            for (masterSyncOn in listOf(true, false)) {
                for (manualOnly in listOf(true, false)) {
                    val retries = SyncScheduling.userSyncRetries(masterSyncOn = masterSyncOn, manualOnly = manualOnly)
                    assertEquals("HTTP $code retries=$retries", if (retries) pair.first else pair.second,
                        shown(code, null, retries, "listing-http-$code@example.test"))
                }
            }
        }
    }

    @Test
    fun `a listing the network broke shows the network's text`() {
        val account = "listing-net@example.test"
        assertEquals(R.string.error_no_internet_tap_sync,
            shown(null, UnknownHostException("Unable to resolve host \"api.example.test\": No address associated with hostname"), false, account))
        assertEquals(R.string.error_no_internet, shown(null, UnknownHostException("api.example.test"), true, account))
        assertEquals(R.string.error_timeout, shown(null, SocketTimeoutException("timeout"), false, account))
        assertEquals(R.string.error_connection_interrupted_tap_sync, shown(null, SocketException("Connection reset"), false, account))
        // An exception the network did not cause: the words decide, as for any failure without a cause.
        assertEquals(R.string.error_unexpected, shown(null, EOFException("End of input at line 1 column 1 path \$"), true, account))
    }

    // ---- Where it is wired ----------------------------------------------------------------------------------------

    @Test
    fun `every way the flat listing fails records its cause before it stops`() {
        val flat = squash(code(body("private suspend fun fetchEventsFlat(")))
        assertEquals(flat, 2, Regex(Regex.escape("return null")).findAll(flat).count())
        assertTrue(flat, flat.contains(
            "} catch (e: Exception) { SyncLog.e(TAG, \"Failed to fetch event IDs for '\$calDisplayName': \${e.message}, aborting sync\") " +
                "recordEventListingFailed(account, calInfo, null, e) return null }"
        ))
        assertTrue(flat, flat.contains("aborting sync\") recordEventListingFailed(account, calInfo, resp.code(), null) return null }"))
    }

    @Test
    fun `the windowed listing records Proton's status where it stops, and lets an exception through to the caller`() {
        val dense = squash(code(body("private suspend fun fetchEventsDense(")))
        val typeRange = dense.substringAfter("suspend fun fetchTypeRange(").substringBefore("suspend fun fetchAllEvents(")
        val allEvents = dense.substringAfter("suspend fun fetchAllEvents(").substringBefore("return merged.values.toList()")
        // Its own null: the Type bucket whose answer was a failure, with Proton's status. squash() leaves one space of
        // the log line's two.
        assertTrue(typeRange, typeRange.contains(
            "SyncLog.e(TAG, \" Request URL: \$requestUrl\") recordEventListingFailed(account, calInfo, resp.code(), null) return null }"
        ))
        assertEquals(typeRange, 1, Regex(Regex.escape("return null")).findAll(typeRange).count())
        // The other three hand that one on: fetchAllEvents returns null only when a bucket did, and the two calls of it
        // stop with it.
        assertTrue(allEvents, allEvents.contains("if (perTypeResults.any { it == null }) return null"))
        assertEquals(dense, 4, Regex(Regex.escape("return null")).findAll(dense).count())
        val calls = dense.substringAfter("return merged.values.toList()")
        assertEquals(calls, 2, Regex(Regex.escape("fetchAllEvents(")).findAll(calls).count())
        assertEquals(calls, 2, Regex(Regex.escape("?: return null")).findAll(calls).count())
        // A request that throws is caught by neither: the exception ends the calendar's sync, and performCalendarSync's
        // catch counts it and records it with its cause (NetworkCauseTest guards that catch). The bucket's one catch is
        // the error body's.
        assertTrue(typeRange, typeRange.contains("val resp = apiClient.getCalendarEvents("))
        assertFalse(typeRange, typeRange.substringBefore("val resp = apiClient.getCalendarEvents(").contains("try {"))
        assertEquals(
            typeRange, listOf("try { resp.errorBody()?.string()?.take(500) } catch (_: Exception) { null }"),
            Regex("try \\{[^}]*\\} catch \\([^)]*\\) \\{[^}]*\\}").findAll(typeRange).map { it.value }.toList()
        )
        assertEquals(typeRange, 1, Regex("catch \\(").findAll(typeRange).count())
        assertFalse(allEvents, allEvents.contains("catch"))
        val perform = squash(code(body("private suspend fun performCalendarSync(")))
        assertTrue(perform, perform.contains(
            "triggerType, trimSeeds) } catch (e: Exception) { SyncLog.e(TAG, \"Failed to sync calendar '\${job.displayName}': \${e.message}\", e)"
        ))
    }

    @Test
    fun `a failed listing fails the run, and nothing of the calendar is written`() {
        val sync = squash(code(body("private suspend fun syncCalendarEvents(")))
        val stop = "fetchEventsDense(account, calInfo, calDisplayName, plan, existingEventMappings) } ?: run { countFailedEventListing(syncResult) return }"
        val at = sync.indexOf(stop)
        assertTrue(sync, at >= 0)
        // Before anything is written: the events, the deletions, the cursor.
        for (later in listOf("applyFetchedEvents(", "applyServerDeletions(", "persistModelEventId(")) {
            assertTrue(later, sync.indexOf(later) > at)
        }
        val count = squash(code(body("private fun countFailedEventListing(")))
        assertTrue(count, count.contains("synchronized(syncResult.stats) { syncResult.stats.numIoExceptions++ }"))
        val record = squash(code(adapter.substringAfter("private fun recordEventListingFailed(").substringBefore("\n\n")))
        assertTrue(record, record.contains(
            "SyncUtils.recordSwallowedFailure(\"calendar\", account.name, EventListingFailure.reason(calInfo.id, httpCode), cause)"
        ))
        // Both are plain functions: kept off the suspend functions that call them, for the ART limits.
        assertFalse(adapter.contains("suspend fun countFailedEventListing(") || adapter.contains("suspend fun recordEventListingFailed("))
    }
}

package me.proton.sync.calendar

import me.proton.sync.R
import me.proton.sync.SyncNotificationHelper
import me.proton.sync.SyncScheduling
import me.proton.sync.SyncUtils
import me.proton.sync.api.models.CalendarInfo
import me.proton.sync.api.models.CalendarMember
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.SocketException
import java.net.UnknownHostException

/**
 * Writes to Proton the sync could not make and kept quiet about (CalendarWriteFailures; 2.1.0 has them). The upload
 * context of a calendar (CalendarSyncAdapter.prepareCalendarUploadContext) that its own read of the calendars, a member on
 * no loaded address, or keys that do not unlock left null, and the deletion pass (reconcileDeletedEvents, mainly an
 * approved bulk deletion) whose deletions Proton answered with a failure, threw, or could not be sent for want of the
 * calendar's member. Each now fails the run with its reason, and nothing else about it changes (owner, 2026-11-23).
 * Made-up accounts and calendar ids only; no event or calendar name anywhere.
 */
class CalendarWriteFailuresTest {

    private companion object {
        const val ADAPTER = "src/main/java/me/proton/sync/calendar/CalendarSyncAdapter.kt"
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

    private fun count(text: String, what: String): Int = Regex(Regex.escape(what)).findAll(text).count()

    private fun member(id: String) = CalendarMember(
        id = id, email = "$id@example.test", permissions = 127, addressId = "addr-$id", calendarId = "cal1",
        color = "#111111", display = 1
    )

    private fun calendar(members: List<CalendarMember>?) = CalendarInfo(
        id = "calWriteTest0123", name = null, description = null, color = null, display = 1, type = 0, flags = 1,
        members = members
    )

    /** The text a reason shows, recorded and read back as a run does. */
    private fun shown(reason: String, cause: Throwable?, retries: Boolean, account: String): Int {
        SyncUtils.recordSwallowedFailure("calendar", account, reason, cause)
        return SyncNotificationHelper.friendlyMessageRes(SyncUtils.consumeSwallowedFailure("calendar", account)!!, retries)
    }

    // ---- The reasons, and the text each shows -------------------------------------------------------------------------

    @Test
    fun `the reasons name no event and no calendar, and carry Proton's status`() {
        assertEquals(
            "The calendars could not be read for an upload, so nothing was uploaded to one calendar: HTTP 503",
            CalendarWriteFailures.calendarsNotReadForUpload(503)
        )
        assertEquals(
            "The calendars could not be read, so events deleted on this phone were not deleted on Proton: HTTP 429",
            CalendarWriteFailures.calendarsNotReadForDeletions(429)
        )
        // The same words as an event deleted on the phone whose request threw (uploadDeletedRow).
        assertEquals("Could not send a deleted event to Proton", CalendarWriteFailures.DELETION_NOT_SENT)
        assertTrue(squash(code(body("private suspend fun uploadDeletedRow("))).contains(
            "SyncUtils.recordSwallowedFailure(\"calendar\", account.name, \"Could not send a deleted event to Proton\", e)"
        ))
    }

    @Test
    fun `each failure's status or network cause picks the text, with Tap Sync exactly where Android retries no sync`() {
        val texts = mapOf(
            503 to (R.string.error_server_error to R.string.error_server_error_tap_sync),
            500 to (R.string.error_server_error to R.string.error_server_error_tap_sync),
            429 to (R.string.error_too_many_requests to R.string.error_too_many_requests_tap_sync),
            // The texts that promise no retry have no variant.
            401 to (R.string.error_session_expired to R.string.error_session_expired),
            422 to (R.string.error_request_rejected to R.string.error_request_rejected),
        )
        val reasons = listOf<(Int) -> String>(
            CalendarWriteFailures::calendarsNotReadForUpload,
            CalendarWriteFailures::calendarsNotReadForDeletions,
            EventDeletionFailures::reason,
        )
        for ((status, pair) in texts) {
            for ((n, reason) in reasons.withIndex()) {
                for (masterSyncOn in listOf(true, false)) {
                    for (manualOnly in listOf(true, false)) {
                        val retries = SyncScheduling.userSyncRetries(masterSyncOn = masterSyncOn, manualOnly = manualOnly)
                        assertEquals(
                            "HTTP $status reason $n retries=$retries", if (retries) pair.first else pair.second,
                            shown(reason(status), null, retries, "write-$status-$n-$masterSyncOn-$manualOnly@example.test")
                        )
                    }
                }
            }
        }
        // A deletion the network broke shows the network's text.
        val noHost = UnknownHostException("Unable to resolve host \"api.example.test\": No address associated with hostname")
        assertEquals(R.string.error_no_internet_tap_sync, shown(CalendarWriteFailures.DELETION_NOT_SENT, noHost, false, "write-net-a@example.test"))
        assertEquals(R.string.error_no_internet, shown(CalendarWriteFailures.DELETION_NOT_SENT, noHost, true, "write-net-b@example.test"))
        assertEquals(
            R.string.error_connection_interrupted_tap_sync,
            shown(CalendarWriteFailures.DELETION_NOT_SENT, SocketException("Connection reset"), false, "write-net-c@example.test")
        )
        // The upload context's other two reasons, as recorded where they happen: words alone, the plain failure.
        for (recorded in listOf(
            "None of the keys for one calendar could be unlocked, so nothing was uploaded to it. This usually means the account's address keys changed; the next sync re-reads them.",
            "One calendar belongs to an address this app has not loaded yet, so nothing was uploaded to it. The next sync re-reads the account's addresses.",
        )) {
            assertEquals(R.string.error_unexpected, shown(recorded, null, true, "write-words@example.test"))
        }
    }

    // ---- Which missing member fails the run ---------------------------------------------------------------------------

    @Test
    fun `a calendar with members none of which is the account's own fails the run, a memberless one does not`() {
        assertTrue(CalendarWriteFailures.countsMissingOwnMember(listOf(member("m1"))))
        assertTrue(CalendarWriteFailures.countsMissingOwnMember(listOf(member("m1"), member("m2"))))
        assertFalse(CalendarWriteFailures.countsMissingOwnMember(emptyList()))
        assertFalse(CalendarWriteFailures.countsMissingOwnMember(null))
        // The deletion pass: a failed read of the calendars always fails the run; a successful one only for a calendar
        // listed with members, none of them on a loaded address. One gone from Proton's list fails nothing.
        assertTrue(CalendarWriteFailures.missingMemberFails(listRead = false, calendar = null))
        assertTrue(CalendarWriteFailures.missingMemberFails(listRead = true, calendar = calendar(listOf(member("m1")))))
        assertFalse(CalendarWriteFailures.missingMemberFails(listRead = true, calendar = null))
        assertFalse(CalendarWriteFailures.missingMemberFails(listRead = true, calendar = calendar(emptyList())))
        assertFalse(CalendarWriteFailures.missingMemberFails(listRead = true, calendar = calendar(null)))
    }

    // ---- Where it is wired: the upload context ------------------------------------------------------------------------

    @Test
    fun `an upload context its own calendar read, its member or its keys left empty fails the run, and its rows still wait`() {
        val context = squash(code(body("private suspend fun prepareCalendarUploadContext(")))
        // Its own read of the calendars failed: counted with Proton's status, before anything is read from the answer.
        val failedRead = "if (!calResponse.isSuccessful) { uploadContextFailed(account, syncResult, " +
            "CalendarWriteFailures.calendarsNotReadForUpload(calResponse.code())) return null }"
        assertTrue(context, context.contains(failedRead))
        assertTrue(context, context.indexOf(failedRead) < context.indexOf("val calInfo = calResponse.body()"))
        // A calendar a list read whole does not have is left as it was: null, nothing counted.
        assertTrue(context, context.contains(
            "val calInfo = calResponse.body()?.calendars?.firstOrNull { it.id == protonCalendarId } ?: return null"
        ))
        // No member on a loaded address (its reason recorded by reloadKeysForUnknownMembership), and no key unlocked
        // (its reason recorded just before): counted, with no second reason.
        assertTrue(context, context.contains(
            "if (memberId == null) { if (CalendarWriteFailures.countsMissingOwnMember(calInfo.members)) " +
                "uploadContextFailed(account, syncResult, null) return null }"
        ))
        val noKey = context.substringAfter("if (calKeys.isNotEmpty() && !anyCalendarKeyUnlocked) {").substringBefore("return CalendarUploadContext(")
        assertTrue(noKey, noKey.contains("SyncUtils.invalidateKeyCache(account.name)"))
        assertTrue(noKey, noKey.contains("could be unlocked, so nothing was uploaded to it."))
        assertTrue(noKey, noKey.trimEnd().endsWith("uploadContextFailed(account, syncResult, null) return null }"))
        assertEquals(context, 3, count(context, "uploadContextFailed("))
        // The no-member reason is still recorded where the member is looked for.
        val reload = squash(code(body("private fun reloadKeysForUnknownMembership(")))
        assertTrue(reload, reload.contains("if (calInfo.members.isNullOrEmpty()) return SyncUtils.invalidateKeyCache(account.name)"))
        // The count, once, and the reason only where one is given; a plain function, kept off the suspend one.
        val failed = squash(code(body("private fun uploadContextFailed(")))
        assertTrue(failed, failed.contains(
            "synchronized(syncResult.stats) { syncResult.stats.numIoExceptions++ } " +
                "if (reason != null) SyncUtils.recordSwallowedFailure(\"calendar\", account.name, reason)"
        ))
        assertEquals(failed, 1, count(failed, "numIoExceptions++"))
        assertFalse(adapter.contains("suspend fun uploadContextFailed("))
        // The contexts are prepared with the run's result, and a thrown failure is counted as before.
        val prepare = squash(code(body("private suspend fun prepareUploadContexts(")))
        assertTrue(prepare, prepare.contains("prepareCalendarUploadContext(account, androidCalId, syncResult)"))
        assertTrue(prepare, prepare.contains(
            "synchronized(syncResult.stats) { syncResult.stats.numIoExceptions++ } SyncUtils.recordSwallowedFailure(\"calendar\", account.name, e.message, e) null"
        ))
        // Nothing else changes for the rows: without a context they are skipped and stay pending (AddressKeysMissingTest).
        assertTrue(squash(code(body("private suspend fun uploadDeletedRow("))).contains(
            "if (uploadContext == null) { SyncLog.e(TAG, \"Could not find member ID for calendar \${mapping.protonCalendarId}\") return }"
        ))
    }

    // ---- Where it is wired: the deletion pass -------------------------------------------------------------------------

    @Test
    fun `the deletion pass fails the run for a failed calendar read, a missing member, a failed answer and a request that threw`() {
        val pass = squash(code(body("private suspend fun reconcileDeletedEvents(")))
        // No member of the account's own: the read's status, or the member's reason, then on to the next calendar.
        assertTrue(pass, pass.contains(
            "if (memberId == null) { SyncLog.e(TAG, \"Could not find member ID for calendar \${calMapping.protonCalendarId}\") " +
                "deletionsWithoutMember(account, syncResult, calResponse, calInfo) continue }"
        ))
        val without = squash(code(body("private fun deletionsWithoutMember(")))
        assertTrue(without, without.contains(
            "if (!CalendarWriteFailures.missingMemberFails(calResponse.isSuccessful, calInfo)) return " +
                "synchronized(syncResult.stats) { syncResult.stats.numIoExceptions++ } if (!calResponse.isSuccessful) { " +
                "SyncUtils.recordSwallowedFailure(\"calendar\", account.name, CalendarWriteFailures.calendarsNotReadForDeletions(calResponse.code())) }"
        ))
        // Proton answered a deletion with a failure (any but the read-only 400, which stays REFUSED): its status. Since the
        // owner's decision of 2026-11-23, the call also passes the event's Proton id, for its strikes, and a refusal's
        // third gives the deletion up instead of failing the run (DeletionPassRefusalsTest).
        assertTrue(pass, pass.contains(
            "null -> { SyncLog.e(TAG, \"Failed to delete event on Proton: HTTP \${response.code()}\${ProtonApiError.describe(errorBody)}\") " +
                "if (reconcileDeletionFailed(account, syncResult, response.code(), mapping.protonEventId)) { giveUpPassDeletion(account, mapping) } }"
        ))
        assertTrue(pass, pass.contains(
            "response.code() == 400 && (hasErrorCode(errorBody!!, 2011) || hasErrorCode(errorBody, 2026)) -> DeleteOpVerdict.REFUSED"
        ))
        val failed = squash(code(body("private fun reconcileDeletionFailed(")))
        assertTrue(failed, failed.contains(
            "synchronized(syncResult.stats) { syncResult.stats.numIoExceptions++ } " +
                "SyncUtils.recordSwallowedFailure(\"calendar\", account.name, EventDeletionFailures.reason(httpCode))"
        ))
        // A request that threw: counted with its cause (NetworkCauseTest counts this catch among the six).
        assertTrue(pass, pass.contains(
            "} catch (e: Exception) { SyncLog.e(TAG, \"Network error deleting event \${mapping.protonEventId}\", e) " +
                "syncResult.stats.numIoExceptions++ SyncUtils.recordSwallowedFailure(\"calendar\", account.name, CalendarWriteFailures.DELETION_NOT_SENT, e) }"
        ))
        // Exactly these: the deletions Proton took, refused or rejected count as before, and nothing else is new.
        assertEquals(pass, 1, count(pass, "numIoExceptions++"))
        assertEquals(pass, 1, count(pass, "deletionsWithoutMember("))
        assertEquals(pass, 1, count(pass, "reconcileDeletionFailed("))
        for (helper in listOf("deletionsWithoutMember(", "reconcileDeletionFailed(")) assertFalse(helper, adapter.contains("suspend fun $helper"))
        // The approval of a bulk deletion is cleared at the end of the pass as before, failures or not; the deletions that
        // failed keep their mappings and go again at the next sync, asked about again if they still look like a bulk one
        // (a refusal until its third, which gives it up: DeletionPassRefusalsTest).
        assertTrue(pass, pass.contains(
            "} else if (deletionApproved) { BulkDeletionGuard.clear(context, account.name) SyncNotificationHelper.cancelBulkDeletionPrompt(context, account.name) }"
        ))
        // With no address loaded the whole pass waits, leaving an approval alone, as before.
        assertTrue(pass, pass.indexOf("if (keyManager.getAddressEmails(account.name).isEmpty()) {") in 0 until pass.indexOf("val deletionApproved"))
    }
}

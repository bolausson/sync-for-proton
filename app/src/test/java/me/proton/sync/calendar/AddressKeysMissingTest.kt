package me.proton.sync.calendar

import me.proton.sync.R
import me.proton.sync.SyncNotificationHelper
import me.proton.sync.SyncScheduling
import me.proton.sync.SyncUtils
import me.proton.sync.api.models.CalendarMember
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * A calendar with keys while none of the account's address keys is loaded (AddressKeysMissing, CalendarSyncAdapter
 * .prepareCalendarJob, CalendarBackupBuilder). When the account's addresses could not be read at the start of a sync, the
 * sync went on with the user key alone; no calendar key unlocked, every event that run downloaded was written without
 * its title, location and description (or as "[Encrypted Event]"), the run reported success, and later syncs kept the
 * copies as unchanged. Since 2.2.0 such a calendar is skipped for the run like one whose keys could not be read, and the
 * run fails with the read's status or network cause (owner, 2026-11-23). Made-up hosts, accounts and calendar ids only.
 */
class AddressKeysMissingTest {

    private companion object {
        const val ADAPTER = "src/main/java/me/proton/sync/calendar/CalendarSyncAdapter.kt"
        const val CALENDAR = "calAddrTest0123456789abcdefghijklmnopqrstuvwxyz=="
        val NO_HOST = UnknownHostException("Unable to resolve host \"api.example.test\": No address associated with hostname")
    }

    private val adapter: String by lazy { File(ADAPTER).readText().replace("\r\n", "\n") }

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

    private fun count(text: String, what: String): Int = Regex(Regex.escape(what)).findAll(text).count()

    // ---- The rule ---------------------------------------------------------------------------------------------------

    @Test
    fun `a calendar with keys is skipped while no address key is loaded, and syncs as before once one is`() {
        assertTrue(AddressKeysMissing.skipsCalendar(calendarHasKeys = true, addressKeysLoaded = false))
        // The next run, with the addresses read and their keys loaded, syncs the same calendar as before.
        assertFalse(AddressKeysMissing.skipsCalendar(calendarHasKeys = true, addressKeysLoaded = true))
        // A calendar without keys (an external one Proton gives none) has nothing to unlock: as before, either way.
        assertFalse(AddressKeysMissing.skipsCalendar(calendarHasKeys = false, addressKeysLoaded = false))
        assertFalse(AddressKeysMissing.skipsCalendar(calendarHasKeys = false, addressKeysLoaded = true))
    }

    @Test
    fun `a failed read of the addresses is kept per account until a read succeeds`() {
        val a = "address-read-a@example.test"
        val b = "address-read-b@example.test"
        assertNull(SyncUtils.addressReadFailure(a))
        SyncUtils.recordAddressRead(a, SyncUtils.AddressReadFailure(null, NO_HOST))
        assertSame(NO_HOST, SyncUtils.addressReadFailure(a)?.cause)
        assertNull(SyncUtils.addressReadFailure(a)?.httpCode)
        assertNull(SyncUtils.addressReadFailure(b))
        // A later failed read replaces it: the reason names the read that left the keys out.
        SyncUtils.recordAddressRead(a, SyncUtils.AddressReadFailure(503, null))
        assertEquals(503, SyncUtils.addressReadFailure(a)?.httpCode)
        assertNull(SyncUtils.addressReadFailure(a)?.cause)
        // A read that succeeds forgets it, so a later run records no stale cause.
        SyncUtils.recordAddressRead(a, null)
        assertNull(SyncUtils.addressReadFailure(a))
        SyncUtils.recordAddressRead(b, null)
        assertNull(SyncUtils.addressReadFailure(b))
    }

    // ---- The text shown ---------------------------------------------------------------------------------------------

    /** The text the reason shows, recorded and read back as a run does. */
    private fun shown(httpCode: Int?, cause: Throwable?, retries: Boolean, account: String): Int {
        SyncUtils.recordSwallowedFailure("calendar", account, AddressKeysMissing.reason(CALENDAR, httpCode), cause)
        return SyncNotificationHelper.friendlyMessageRes(SyncUtils.consumeSwallowedFailure("calendar", account)!!, retries)
    }

    @Test
    fun `the reason names the calendar by the start of its id and carries Proton's status`() {
        val words = "No address key of the account could be loaded, so calendar 'calAddrT' could not be opened and none of " +
            "its events were downloaded"
        assertEquals("$words: HTTP 503", AddressKeysMissing.reason(CALENDAR, 503))
        assertEquals(words, AddressKeysMissing.reason(CALENDAR, null))
    }

    @Test
    fun `the read's status or network cause picks the text, with Tap Sync exactly where Android retries no sync`() {
        val texts = listOf(
            Triple(503, null, R.string.error_server_error to R.string.error_server_error_tap_sync),
            Triple(500, null, R.string.error_server_error to R.string.error_server_error_tap_sync),
            Triple(429, null, R.string.error_too_many_requests to R.string.error_too_many_requests_tap_sync),
            // The texts that promise no retry have no variant.
            Triple(401, null, R.string.error_session_expired to R.string.error_session_expired),
            Triple(422, null, R.string.error_request_rejected to R.string.error_request_rejected),
            Triple(null, NO_HOST, R.string.error_no_internet to R.string.error_no_internet_tap_sync),
            Triple(null, SocketException("Connection reset"), R.string.error_connection_interrupted to R.string.error_connection_interrupted_tap_sync),
            Triple(null, SocketTimeoutException("timeout"), R.string.error_timeout to R.string.error_timeout),
        )
        for ((code, cause, pair) in texts) {
            for (masterSyncOn in listOf(true, false)) {
                for (manualOnly in listOf(true, false)) {
                    val retries = SyncScheduling.userSyncRetries(masterSyncOn = masterSyncOn, manualOnly = manualOnly)
                    assertEquals("$code $cause retries=$retries", if (retries) pair.first else pair.second,
                        shown(code, cause, retries, "address-keys-$code-$masterSyncOn-$manualOnly@example.test"))
                }
            }
        }
        // No cause known (the addresses were read, yet none of their keys unlocked): the words alone, as for any failure
        // without one.
        assertEquals(R.string.error_unexpected, shown(null, null, true, "address-keys-none@example.test"))
        assertEquals(R.string.error_unexpected, shown(null, IllegalStateException("body"), false, "address-keys-none@example.test"))
    }

    // ---- Where it is wired ------------------------------------------------------------------------------------------

    @Test
    fun `the preflight records how the read of the addresses ended`() {
        val unlock = squash(code(body(source("main/java/me/proton/sync/SyncUtils.kt"), "private fun doEnsureKeysUnlocked(")))
        assertTrue(unlock, unlock.contains("if (addressesResponse.isSuccessful) { recordAddressRead(account.name, null)"))
        assertTrue(unlock, unlock.contains(
            "\${addressesResponse.protonErrorSuffix()}\") recordAddressRead(account.name, AddressReadFailure(addressesResponse.code(), null)) }"
        ))
        assertTrue(unlock, unlock.contains(
            "} catch (e: Exception) { SyncLog.w(tag, \"Failed to re-unlock address keys: \${e.message}\") " +
                "recordAddressRead(account.name, AddressReadFailure(null, e)) }"
        ))
        assertEquals(unlock, 3, count(unlock, "recordAddressRead("))
    }

    @Test
    fun `a calendar with keys is skipped once its keys are read, before its job exists, so the run fails`() {
        val job = squash(code(body(adapter, "private suspend fun prepareCalendarJob(")))
        val skip = "if (skippedForMissingAddressKeys(account, calInfo, calKeys, displayName)) return null"
        val at = job.indexOf(skip)
        assertTrue(job, at >= 0)
        assertEquals(job, 1, count(job, "skippedForMissingAddressKeys("))
        // After the keys were read and a failure to read them had its own say (CalendarKeysFailureTest), before the job
        // is made: a calendar left out here is never downloaded. performCalendarSync counts the null as a failed run
        // (NetworkCauseTest guards that count).
        assertTrue(job, job.indexOf("calKeys = fetchCalendarKeys(account.name, calInfo.id)") in 0 until at)
        assertTrue(job, job.indexOf("recordCalendarNotOpened(account, calInfo, e) return null") in 0 until at)
        assertTrue(job, at < job.indexOf("return CalendarJob(calInfo, androidCalId, calKeys, calPassphrases, isExternalCalendar, displayName)"))
        // The rule decides, from the keys read and KeyManager, and the reason carries the read's status or cause.
        val helper = squash(code(body(adapter, "private fun skippedForMissingAddressKeys(")))
        assertTrue(helper, helper.contains(
            "if (!AddressKeysMissing.skipsCalendar(calKeys.isNotEmpty(), keyManager.hasAddressKeys(account.name))) return false"
        ))
        assertTrue(helper, helper.contains("val failure = SyncUtils.addressReadFailure(account.name)"))
        assertTrue(helper, helper.contains(
            "SyncUtils.recordSwallowedFailure(\"calendar\", account.name, AddressKeysMissing.reason(calInfo.id, failure?.httpCode), failure?.cause) return true"
        ))
        // A plain function, kept off the suspend function that calls it, for the ART limits.
        assertFalse(adapter.contains("suspend fun skippedForMissingAddressKeys("))
    }

    @Test
    fun `no other way down writes an event, since the sync decrypts in one place and only for the calendars it prepared`() {
        val text = code(adapter)
        // The one decryption of an event, in the one function that turns a downloaded event into the phone's.
        assertEquals(1, count(text, "calendarDecryptor.decryptEvent("))
        assertTrue(squash(code(body(adapter, "private fun decryptAndParseVEvent("))).contains("calendarDecryptor.decryptEvent("))
        // Called from applyFetchedEvents alone, which syncCalendarEvents alone calls, which runs only for a prepared job.
        assertEquals(2, count(text, "decryptAndParseVEvent("))
        assertTrue(squash(code(body(adapter, "private suspend fun applyFetchedEvents("))).contains("decryptAndParseVEvent("))
        assertEquals(2, count(text, "applyFetchedEvents("))
        assertTrue(squash(code(body(adapter, "private suspend fun syncCalendarEvents("))).contains("applyFetchedEvents("))
        assertEquals(1, count(text, "syncCalendarEvents(account, provider, job.calInfo, job.androidCalId,"))
        val perform = squash(code(body(adapter, "private suspend fun performCalendarSync(")))
        assertTrue(perform, perform.contains("jobs.map { job -> async(Dispatchers.IO) { try { syncCalendarEvents(account, provider, job.calInfo, job.androidCalId,"))
        assertTrue(perform, perform.indexOf("if (job == null) {") < perform.indexOf("jobs.add(job)"))
    }

    @Test
    fun `the backup leaves such a calendar out and calls itself incomplete, rather than write its events without their contents`() {
        val builder = squash(code(source("main/java/me/proton/sync/backup/CalendarBackupBuilder.kt")))
        val skip = builder.indexOf("if (AddressKeysMissing.skipsCalendar(keys.isNotEmpty(), addressKeysLoaded(accountName))) {")
        assertTrue(builder, skip >= 0)
        assertTrue(builder, builder.substring(skip).contains("cannot be unlocked; skipping\") skipped++ continue }"))
        // Before the calendar's events are fetched and decrypted.
        assertTrue(builder, skip < builder.indexOf("fetchAllEvents(accountName, cal.id)"))
        assertTrue(builder, skip < builder.indexOf("calendarDecryptor.decryptEvent("))
        assertEquals(builder, 1, count(builder, "calendarDecryptor.decryptEvent("))
        // The exporter hands it KeyManager's answer, and a skipped calendar makes the run incomplete.
        val exporter = squash(code(source("main/java/me/proton/sync/backup/BackupDataExporter.kt")))
        assertTrue(exporter, exporter.contains("CalendarBackupBuilder(apiClient, calendarDecryptor, keyManager::hasAddressKeys)"))
        assertTrue(exporter, exporter.contains("calResult.skippedCalendars > 0"))
    }

    @Test
    fun `the upload keeps refusing a calendar whose keys cannot be unlocked or whose member is on no loaded address`() {
        val context = squash(code(body(adapter, "private suspend fun prepareCalendarUploadContext(")))
        // Refused as before; since 2.2.0 (owner, 2026-11-23) counted too, where the calendar has members
        // (CalendarWriteFailuresTest guards the count).
        assertTrue(context, context.contains(
            "val memberId = ownMemberIdForWrites(account, calInfo) if (memberId == null) { " +
                "if (CalendarWriteFailures.countsMissingOwnMember(calInfo.members)) uploadContextFailed(account, syncResult, null) return null }"
        ))
        val noKey = "if (calKeys.isNotEmpty() && !anyCalendarKeyUnlocked) {"
        assertTrue(context, context.contains(noKey))
        assertTrue(context, context.substringAfter(noKey).substringBefore("return CalendarUploadContext(").contains("return null }"))
        val member = squash(code(body(adapter, "private fun ownMemberIdForWrites(")))
        assertTrue(member, member.contains(
            "val userAddresses = keyManager.getAddressEmails(account.name) val member = ownMember(calInfo.members, userAddresses)"
        ))
        // With no address key loaded the account has no address, so no member is its own: nothing is written.
        val members = listOf(
            CalendarMember(id = "m1", email = "owner-a@example.test", permissions = 127, addressId = "addr-1",
                calendarId = "cal1", color = "#111111", display = 1),
        )
        assertNull(ownMember(members, emptySet()))
        assertEquals("m1", ownMember(members, setOf("owner-a@example.test"))?.id)
        // Rows of a calendar without a context are skipped and stay pending.
        val deleted = squash(code(body(adapter, "private suspend fun uploadDeletedRow(")))
        assertTrue(deleted, deleted.contains(
            "if (uploadContext == null) { SyncLog.e(TAG, \"Could not find member ID for calendar \${mapping.protonCalendarId}\") return }"
        ))
        val modified = squash(code(body(adapter, "private suspend fun uploadModifiedRow(")))
        assertTrue(modified, modified.contains(
            "if (uploadContext == null) { SyncLog.e(TAG, \"Skipping event \${row.androidEventId}: no calendar context for androidCalId=\${row.androidCalId}\") } else {"
        ))
    }
}

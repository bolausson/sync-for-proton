package me.proton.sync.contacts

import me.proton.sync.NetworkCause
import me.proton.sync.R
import me.proton.sync.SyncNotificationHelper
import me.proton.sync.SyncPreferences
import me.proton.sync.SyncScheduling
import me.proton.sync.backup.BackupSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.EOFException
import java.io.File
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * A contact deleted on the phone whose deletion did not reach Proton (ContactDeletionFailures, ContactsSyncAdapter
 * .uploadLocalDeletion and the upload loop's catch). It stays pending, as before, but the run reported success, so with
 * Android's automatic sync off nothing told the user to tap Sync. Since 2.2.0 the run fails with the right text: the
 * network's for a request the network broke, the status's for an answer from Proton (owner, 2026-11-23). A refusal of
 * the deletion itself fails its first three syncs only, so a deletion Proton never takes cannot fail every sync. From the
 * third refusal on, a lasting notice names the contact instead, until its deletion reaches Proton or is no longer pending.
 * Made-up hosts, addresses (TEST-NET) and accounts only; no contact's content anywhere.
 */
class ContactDeletionFailuresTest {

    private val account = "deletion-a@example.test"

    private companion object {
        const val ADAPTER = "src/main/java/me/proton/sync/contacts/ContactsSyncAdapter.kt"
        val NO_HOST = UnknownHostException("Unable to resolve host \"api.example.test\": No address associated with hostname")

        /** Answers about the connection or the session (ContactUploadRefusals.isTransientHttp), and refusals. */
        val PASSING = listOf(401, 408, 429, 500, 502, 503, 504)
        val REFUSING = listOf(400, 403, 404, 409, 422)
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

    /** The text the recorded reason shows, as SyncUtils.recordSwallowedFailure writes it. */
    private fun shown(httpCode: Int?, cause: Throwable?, retries: Boolean): Int =
        SyncNotificationHelper.friendlyMessageRes(NetworkCause.describe(ContactDeletionFailures.reason(httpCode), cause)!!, retries)

    /** What one sync does with a deletion Proton answered [httpCode]: whether the run fails (reportDeletionNotSent's steps). */
    private fun sync(prefs: FakePrefs, rawContactId: Long, httpCode: Int): Boolean {
        val refusals = if (ContactDeletionFailures.isRefusal(httpCode)) {
            ContactDeletionFailures.recordRefusal(prefs, account, rawContactId)
        } else 0
        return ContactDeletionFailures.failsRun(httpCode, null, refusals)
    }

    private fun strikes(prefs: FakePrefs): Map<Long, Int> =
        ContactUploadRefusals.parseStrikes(prefs.getString(SyncPreferences.contactDeleteStrikesKey(account), null))

    // ---- When a run fails ---------------------------------------------------------------------------------------------

    @Test
    fun `a request the network broke fails the run every time, and any other exception does not`() {
        val network = listOf(NO_HOST, ConnectException("Failed to connect to api.example.test/192.0.2.10:443"),
            SocketTimeoutException("timeout"), SocketException("Connection reset"),
            IllegalStateException("wrapped", SocketException("Broken pipe")))
        for (e in network) assertTrue("$e", ContactDeletionFailures.failsRun(null, e, refusals = 0))
        // As before: a failure on the phone, or a reply that could not be read, stays pending without failing the run.
        for (e in listOf(IllegalStateException("provider"), EOFException("End of input at line 1 column 1 path \$"), null)) {
            assertFalse("$e", ContactDeletionFailures.failsRun(null, e, refusals = 0))
        }
    }

    @Test
    fun `an answer about the connection or the session fails every run it comes in`() {
        for (code in PASSING) {
            assertFalse("$code", ContactDeletionFailures.isRefusal(code))
            for (refusals in listOf(0, 1, 3, 4, 100)) assertTrue("$code", ContactDeletionFailures.failsRun(code, null, refusals))
        }
    }

    @Test
    fun `a refusal fails its first three syncs only`() {
        assertEquals(3, ContactDeletionFailures.MAX_REPORTED_REFUSALS)
        for (code in REFUSING) {
            assertTrue("$code", ContactDeletionFailures.isRefusal(code))
            for (refusals in 1..3) assertTrue("$code $refusals", ContactDeletionFailures.failsRun(code, null, refusals))
            for (refusals in listOf(0, 4, 5, 1000)) assertFalse("$code $refusals", ContactDeletionFailures.failsRun(code, null, refusals))
        }
    }

    @Test
    fun `a deletion Proton never takes cannot fail every sync, and one that reaches Proton starts again`() {
        val prefs = FakePrefs()
        // Twenty syncs refused: three failed runs, then the deletion stays pending without failing another.
        assertEquals(listOf(true, true, true) + List(17) { false }, List(20) { sync(prefs, 7, 422) })
        // A passing answer in between still fails its run and costs no strike.
        assertTrue(sync(prefs, 7, 503))
        assertEquals(mapOf(7L to 20), strikes(prefs))
        // Each deletion has its own count, and each account its own.
        assertTrue(sync(prefs, 8, 422))
        assertEquals(1, ContactDeletionFailures.recordRefusal(prefs, "deletion-b@example.test", 7))
        // Sent at last: forgotten, so a later refusal of the same row is reported again.
        ContactDeletionFailures.forget(prefs, account, 7)
        assertEquals(mapOf(8L to 1), strikes(prefs))
        assertTrue(sync(prefs, 7, 422))
    }

    @Test
    fun `refusals of deletions no longer pending are forgotten, and nothing is written when none go`() {
        val prefs = FakePrefs()
        val key = SyncPreferences.contactDeleteStrikesKey(account)
        // Nothing recorded: nothing to forget, and no key appears.
        ContactDeletionFailures.forget(prefs, account, 5)
        ContactDeletionFailures.retain(prefs, account) { false }
        assertFalse(prefs.contains(key))
        ContactDeletionFailures.recordRefusal(prefs, account, 5)
        ContactDeletionFailures.recordRefusal(prefs, account, 6)
        ContactDeletionFailures.recordRefusal(prefs, account, 6)
        val before = prefs.getString(key, null)
        ContactDeletionFailures.retain(prefs, account) { it == 5L || it == 6L }
        assertTrue(prefs.getString(key, null) === before)
        ContactDeletionFailures.retain(prefs, account) { it == 6L }
        assertEquals(mapOf(6L to 2), strikes(prefs))
        ContactDeletionFailures.forget(prefs, account, 6)
        assertNull(prefs.getString(key, null))
    }

    // ---- The text shown -----------------------------------------------------------------------------------------------

    @Test
    fun `the reason names no contact and carries Proton's status`() {
        assertEquals("Could not send a deleted contact to Proton", ContactDeletionFailures.reason(null))
        assertEquals("Proton did not delete a contact deleted on this phone: HTTP 503", ContactDeletionFailures.reason(503))
    }

    @Test
    fun `a failed deletion shows the network's or the status's text, with Tap Sync exactly where Android retries no sync`() {
        val texts = listOf(
            Triple(null, NO_HOST, R.string.error_no_internet to R.string.error_no_internet_tap_sync),
            Triple(null, SocketException("Connection reset"), R.string.error_connection_interrupted to R.string.error_connection_interrupted_tap_sync),
            Triple(null, SocketTimeoutException("timeout"), R.string.error_timeout to R.string.error_timeout),
            Triple(503, null, R.string.error_server_error to R.string.error_server_error_tap_sync),
            Triple(500, null, R.string.error_server_error to R.string.error_server_error_tap_sync),
            Triple(429, null, R.string.error_too_many_requests to R.string.error_too_many_requests_tap_sync),
            Triple(401, null, R.string.error_session_expired to R.string.error_session_expired),
            Triple(422, null, R.string.error_request_rejected to R.string.error_request_rejected),
            Triple(404, null, R.string.error_request_rejected to R.string.error_request_rejected),
        )
        for ((code, cause, pair) in texts) {
            for (masterSyncOn in listOf(true, false)) {
                for (manualOnly in listOf(true, false)) {
                    val retries = SyncScheduling.userSyncRetries(masterSyncOn = masterSyncOn, manualOnly = manualOnly)
                    assertEquals("$code $cause retries=$retries", if (retries) pair.first else pair.second, shown(code, cause, retries))
                }
            }
        }
    }

    // ---- Stored per account, never in a backup ------------------------------------------------------------------------

    @Test
    fun `the refusal counts belong to their account and never travel`() {
        val key = SyncPreferences.contactDeleteStrikesKey(account)
        assertEquals("contact_delete_strikes_$account", key)
        assertEquals(listOf(account), SyncPreferences.accountNamesOfKey(key))
        assertEquals(mapOf(key to account), SyncPreferences.keysOfRemovedAccounts(listOf(key), setOf("other@example.test")))
        assertEquals(emptyMap<String, String>(), SyncPreferences.keysOfRemovedAccounts(listOf(key), setOf(account)))
        assertEquals(SyncPreferences.contactDeleteStrikesKey("renamed"), SyncPreferences.renamedAccountKey(key, account, "renamed"))
        // Raw contact ids of this phone: meaningless on another.
        assertFalse(BackupSettings.isExportable(key))
        // So does what the lasting notice named (ContactDeletionFailures.noticedIds), the same raw ids.
        val noticed = SyncPreferences.contactDeleteNoticedKey(account)
        assertEquals("contact_delete_noticed_$account", noticed)
        assertEquals(listOf(account), SyncPreferences.accountNamesOfKey(noticed))
        assertEquals(mapOf(noticed to account), SyncPreferences.keysOfRemovedAccounts(listOf(noticed), setOf("other@example.test")))
        assertEquals(emptyMap<String, String>(), SyncPreferences.keysOfRemovedAccounts(listOf(noticed), setOf(account)))
        assertEquals(SyncPreferences.contactDeleteNoticedKey("renamed"), SyncPreferences.renamedAccountKey(noticed, account, "renamed"))
        assertFalse(BackupSettings.isExportable(noticed))
    }

    // ---- Where it is wired --------------------------------------------------------------------------------------------

    @Test
    fun `a deletion Proton refused or did not get is reported, and one it took is forgotten`() {
        val deletion = squash(code(body("private suspend fun uploadLocalDeletion(")))
        val forgotten = deletion.indexOf(
            "if (response.isSuccessful) { ContactDeletionFailures.forget( androidx.preference.PreferenceManager.getDefaultSharedPreferences(context), parentAccountName, rawContactId )"
        )
        assertTrue(deletion, forgotten >= 0 && forgotten < deletion.indexOf("contactSyncDao.deleteByProtonId(parentAccountName, mapping.protonContactId)"))
        assertTrue(deletion, deletion.contains("it stays pending\" ) reportDeletionNotSent(parentAccountName, syncResult, rawContactId, response.code(), null) }"))
        // The tombstone is still removed only once Proton took the deletion.
        assertEquals(deletion, 1, Regex(Regex.escape("provider.delete(")).findAll(deletion).count())
        assertFalse(deletion, deletion.contains("clearContactDirtyFlag"))

        val loop = squash(code(body("private suspend fun uploadDirtyContacts(")))
        assertTrue(loop, loop.contains(
            "if (deleted == 1) { SyncLog.e(TAG, \"Failed to send the deletion of contact \$rawContactId; retrying next sync\", e) " +
                "reportDeletionNotSent(parentAccountName, syncResult, rawContactId, null, e) } else if (mapping == null) {"
        ))
        // After the loop, the counts of deletions no longer pending go.
        assertTrue(loop, loop.contains("deleteUnmappedTombstones(account, provider, orphanedDeletedIds) pruneDeletionRefusals(parentAccountName, dirtyContacts)"))
    }

    @Test
    fun `the run fails exactly where the rule says, with the reason and its cause`() {
        val report = squash(code(body("private fun reportDeletionNotSent(")))
        assertTrue(report, report.contains(
            "val refusals = if (httpCode != null && ContactDeletionFailures.isRefusal(httpCode)) { ContactDeletionFailures.recordRefusal("
        ))
        assertTrue(report, report.contains(
            "if (ContactDeletionFailures.failsRun(httpCode, cause, refusals)) { syncResult.stats.numIoExceptions++ " +
                "SyncUtils.recordSwallowedFailure(\"contacts\", parentAccountName, ContactDeletionFailures.reason(httpCode), cause) }"
        ))
        assertEquals(report, 1, Regex(Regex.escape("numIoExceptions++")).findAll(report).count())
        val prune = squash(code(body("private fun pruneDeletionRefusals(")))
        assertTrue(prune, prune.contains("for (dc in dirtyContacts) if (dc.deleted == 1) pending.add(dc.rawContactId)"))
        assertTrue(prune, prune.contains("ContactDeletionFailures.retain("))
        // Plain functions: kept off the suspend functions that call them, for the ART limits.
        assertFalse(adapter.contains("suspend fun reportDeletionNotSent(") || adapter.contains("suspend fun pruneDeletionRefusals("))
    }

    // ---- The lasting notice from the third refusal on (2.2.0, owner 2026-11-23) --------------------------------------
    //
    // With Android's automatic sync on, its own retries of the failed runs used the three reported refusals up within
    // minutes; the next run cleared the failure notification, and nothing said any more that the contact was still on
    // Proton and on the user's other devices.

    /** One sync of [rawContactId]'s deletion and what becomes of the notice at its end, as uploadDirtyContacts runs them. */
    private fun syncWithNotice(prefs: FakePrefs, rawContactId: Long, httpCode: Int, shown: Boolean): ContactDeletionFailures.Notice {
        sync(prefs, rawContactId, httpCode)
        return settle(prefs, shown)
    }

    /** The end of an upload, as noticeRefusedDeletions runs it: the verdict, and what the notice names from then on. */
    private fun settle(prefs: FakePrefs, shown: Boolean): ContactDeletionFailures.Notice {
        val now = ContactDeletionFailures.refusedForGood(prefs, account)
        val verdict = ContactDeletionFailures.notice(ContactDeletionFailures.noticedIds(prefs, account), now) { shown }
        ContactDeletionFailures.rememberNoticed(prefs, account, now)
        return verdict
    }

    @Test
    fun `the lasting notice is posted at the third refusal, and not again at every sync`() {
        val prefs = FakePrefs()
        val post = ContactDeletionFailures.Notice.POST
        val keep = ContactDeletionFailures.Notice.KEEP
        val cancel = ContactDeletionFailures.Notice.CANCEL
        // Refusals one and two fail their runs and leave nothing to show; the third posts the notice; it is up from then on.
        val verdicts = List(10) { syncWithNotice(prefs, 7, 422, shown = it >= 3) }
        assertEquals(listOf(cancel, cancel, post) + List(7) { keep }, verdicts)
        assertEquals(setOf(7L), ContactDeletionFailures.refusedForGood(prefs, account))
        // A passing answer changes nothing, and a notice the user dismissed is not brought back while nothing changes.
        assertEquals(keep, syncWithNotice(prefs, 7, 503, shown = true))
        assertEquals(keep, syncWithNotice(prefs, 7, 422, shown = false))
        // Only deletions of this account, refused three times or more.
        ContactDeletionFailures.recordRefusal(prefs, account, 8)
        ContactDeletionFailures.recordRefusal(prefs, "deletion-b@example.test", 9)
        assertEquals(setOf(7L), ContactDeletionFailures.refusedForGood(prefs, account))
        assertEquals(emptySet<Long>(), ContactDeletionFailures.refusedForGood(prefs, "deletion-b@example.test"))
    }

    @Test
    fun `a second contact updates the one notice, and the notice goes once the deletions land or stop being pending`() {
        val prefs = FakePrefs()
        assertEquals(
            listOf(ContactDeletionFailures.Notice.CANCEL, ContactDeletionFailures.Notice.CANCEL, ContactDeletionFailures.Notice.POST),
            List(3) { syncWithNotice(prefs, 7, 422, shown = false) }
        )
        assertEquals(List(2) { ContactDeletionFailures.Notice.KEEP }, List(2) { syncWithNotice(prefs, 8, 422, shown = false) })
        // The third refusal of 8: the notice is posted again, the same one, naming both, even after the user dismissed it.
        assertEquals(ContactDeletionFailures.Notice.POST, syncWithNotice(prefs, 8, 422, shown = false))
        assertEquals(setOf(7L, 8L), ContactDeletionFailures.refusedForGood(prefs, account))
        assertEquals(setOf(7L, 8L), ContactDeletionFailures.noticedIds(prefs, account))
        // 7's deletion reaches Proton: forgotten. The notice, still up, is brought up to date to name 8 alone; one the
        // user dismissed meanwhile stays dismissed.
        ContactDeletionFailures.forget(prefs, account, 7)
        val afterLanding = ContactDeletionFailures.refusedForGood(prefs, account)
        assertEquals(setOf(8L), afterLanding)
        assertEquals(
            ContactDeletionFailures.Notice.KEEP,
            ContactDeletionFailures.notice(ContactDeletionFailures.noticedIds(prefs, account), afterLanding) { false }
        )
        assertEquals(ContactDeletionFailures.Notice.POST, settle(prefs, shown = true))
        assertEquals(setOf(8L), ContactDeletionFailures.noticedIds(prefs, account))
        assertEquals(ContactDeletionFailures.Notice.KEEP, settle(prefs, shown = true))
        // 8 is no longer a pending deletion (not among the dirty contacts): pruned, and the notice is withdrawn.
        ContactDeletionFailures.retain(prefs, account) { false }
        assertEquals(emptySet<Long>(), ContactDeletionFailures.refusedForGood(prefs, account))
        assertEquals(ContactDeletionFailures.Notice.CANCEL, settle(prefs, shown = true))
        assertFalse(prefs.contains(SyncPreferences.contactDeleteNoticedKey(account)))
        // A deletion refused again from the start is reported by failed runs again, and named at its third refusal.
        assertEquals(listOf(true, true, true), List(3) { sync(prefs, 7, 422) })
        assertEquals(ContactDeletionFailures.Notice.POST, settle(prefs, shown = false))
    }

    @Test
    fun `a deletion refused for good is named by the next upload when the one of its third refusal stopped early, or an earlier build counted it`() {
        val post = ContactDeletionFailures.Notice.POST
        val keep = ContactDeletionFailures.Notice.KEEP
        // The upload of the third refusal ends before the notice is settled (the process dies, the sync is cancelled):
        // the next one, whose refusal fails no run any more, names it, and then leaves it as it is.
        val prefs = FakePrefs()
        repeat(2) { syncWithNotice(prefs, 7, 422, shown = false) }
        assertTrue(sync(prefs, 7, 422))
        assertFalse(sync(prefs, 7, 422))
        assertEquals(post, settle(prefs, shown = false))
        assertEquals(setOf(7L), ContactDeletionFailures.noticedIds(prefs, account))
        assertEquals(listOf(keep, keep, keep), List(3) { syncWithNotice(prefs, 7, 422, shown = it == 0) })
        // Counted by a build that kept the counts but posted no notice: named at the first upload, then left as it is,
        // and named again with a second deletion at that one's third refusal.
        val earlier = FakePrefs()
        earlier.values[SyncPreferences.contactDeleteStrikesKey(account)] = "7:5,8:2"
        assertEquals(post, syncWithNotice(earlier, 7, 422, shown = false))
        assertEquals(setOf(7L), ContactDeletionFailures.noticedIds(earlier, account))
        assertEquals(keep, syncWithNotice(earlier, 7, 422, shown = false))
        assertEquals(post, syncWithNotice(earlier, 8, 422, shown = false))
        assertEquals(setOf(7L, 8L), ContactDeletionFailures.noticedIds(earlier, account))
    }

    @Test
    fun `what the notice named is written only when it changes, and per account`() {
        val prefs = FakePrefs()
        val key = SyncPreferences.contactDeleteNoticedKey(account)
        ContactDeletionFailures.rememberNoticed(prefs, account, emptySet())
        assertFalse(prefs.contains(key))
        ContactDeletionFailures.rememberNoticed(prefs, account, setOf(9L, 7L))
        assertEquals("7,9", prefs.getString(key, null))
        val stored = prefs.getString(key, null)
        ContactDeletionFailures.rememberNoticed(prefs, account, setOf(7L, 9L))
        assertTrue(prefs.getString(key, null) === stored)
        assertEquals(setOf(7L, 9L), ContactDeletionFailures.noticedIds(prefs, account))
        assertEquals(emptySet<Long>(), ContactDeletionFailures.noticedIds(prefs, "deletion-b@example.test"))
        ContactDeletionFailures.rememberNoticed(prefs, account, emptySet())
        assertFalse(prefs.contains(key))
        // Unreadable entries are dropped.
        prefs.values[key] = "7,x,,9"
        assertEquals(setOf(7L, 9L), ContactDeletionFailures.noticedIds(prefs, account))
        ContactDeletionFailures.forgetNoticed(prefs, account)
        assertFalse(prefs.contains(key))
    }

    /** ProtonSyncApp without comments, squashed. */
    private val app: String by lazy {
        squash(code(File("src/main/java/me/proton/sync/ProtonSyncApp.kt").readText().replace("\r\n", "\n")))
    }

    @Test
    fun `an account renamed takes the notice down before its counts move, and the next sync posts it under the new name`() {
        // Its id is built from the account name, while its counts move with the preferences: taken down before they move.
        val rename = app.substringAfter("fun renameParentAccount(").substringBefore("renameAccountInPrefs(oldParent.name, desired)")
        assertTrue(rename, rename.contains(
            "if (SyncNotificationHelper.isContactDeletionsRefusedShown(this, oldParent.name)) { " +
                "SyncNotificationHelper.cancelContactDeletionsRefused(this, oldParent.name) " +
                "me.proton.sync.contacts.ContactDeletionFailures.forgetNoticed(prefsForHolds, oldParent.name) }"
        ))
        assertTrue(rename, rename.indexOf("ContactDeletionFailures.forgetNoticed(") < rename.indexOf("return try {"))
        // The next upload, under the new name: nothing named any more, so the deletions refused for good are posted again.
        val prefs = FakePrefs()
        repeat(3) { syncWithNotice(prefs, 7, 422, shown = true) }
        assertEquals(ContactDeletionFailures.Notice.KEEP, settle(prefs, shown = true))
        ContactDeletionFailures.forgetNoticed(prefs, account)
        assertEquals(ContactDeletionFailures.Notice.POST, settle(prefs, shown = false))
    }

    @Test
    fun `an account removed takes the notice down with its other notices`() {
        // No sync runs for that name again, so nothing else would ever withdraw it; its counts go with its prefixes.
        val removal = app.substringAfter("private fun forgetRemovedAccounts(").substringBefore("private fun renameAccountInPrefs(")
        assertTrue(removal, removal.substringAfter("for (name in stale) {").contains("SyncNotificationHelper.cancelContactDeletionsRefused(this, name)"))
        // Taken down there and at a rename, and nowhere else in the app's start-up code.
        assertEquals(app, 2, Regex(Regex.escape("SyncNotificationHelper.cancelContactDeletionsRefused(")).findAll(app).count())
    }

    @Test
    fun `whether the notice is still up is asked only when a contact left it`() {
        var asked = 0
        val ask = { asked++; true }
        assertEquals(ContactDeletionFailures.Notice.POST, ContactDeletionFailures.notice(emptySet(), setOf(7L), ask))
        assertEquals(ContactDeletionFailures.Notice.POST, ContactDeletionFailures.notice(setOf(7L), setOf(8L), ask))
        assertEquals(ContactDeletionFailures.Notice.KEEP, ContactDeletionFailures.notice(setOf(7L), setOf(7L), ask))
        assertEquals(ContactDeletionFailures.Notice.CANCEL, ContactDeletionFailures.notice(setOf(7L), emptySet(), ask))
        assertEquals(ContactDeletionFailures.Notice.CANCEL, ContactDeletionFailures.notice(emptySet(), emptySet(), ask))
        assertEquals(0, asked)
        assertEquals(ContactDeletionFailures.Notice.POST, ContactDeletionFailures.notice(setOf(7L, 8L), setOf(8L), ask))
        assertEquals(1, asked)
    }

    @Test
    fun `the notice is settled last, after the counts of deletions no longer pending go, from what it named when last settled`() {
        val loop = squash(code(body("private suspend fun uploadDirtyContacts(")))
        // The upload's last step; nothing of the notice is read at the upload's start any more.
        assertTrue(loop, loop.trimEnd().endsWith(
            "deleteUnmappedTombstones(account, provider, orphanedDeletedIds) pruneDeletionRefusals(parentAccountName, dirtyContacts) " +
                "noticeRefusedDeletions(parentAccountName, provider)"
        ))
        assertFalse(loop, loop.contains("refusedForGood") || loop.contains("noticedIds("))
        val notice = squash(code(body("private fun noticeRefusedDeletions(")))
        assertTrue(notice, notice.contains(
            "val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context) " +
                "val now = ContactDeletionFailures.refusedForGood(prefs, parentAccountName) " +
                "val notice = ContactDeletionFailures.notice(ContactDeletionFailures.noticedIds(prefs, parentAccountName), now) { " +
                "SyncNotificationHelper.isContactDeletionsRefusedShown(context, parentAccountName) }"
        ))
        // What it names is kept last, whatever it decided: an upload that stops before this step leaves it to the next.
        assertTrue(notice, notice.trimEnd().endsWith("ContactDeletionFailures.rememberNoticed(prefs, parentAccountName, now)"))
        assertTrue(notice, notice.indexOf("ContactDeletionFailures.rememberNoticed(") > notice.indexOf("SyncNotificationHelper.notifyContactDeletionsRefused("))
        assertTrue(notice, notice.contains(
            "if (notice == ContactDeletionFailures.Notice.CANCEL) { SyncNotificationHelper.cancelContactDeletionsRefused(context, parentAccountName) }"
        ))
        assertTrue(notice, notice.contains(
            "SyncNotificationHelper.notifyContactDeletionsRefused( context, parentAccountName, now.sorted().map { deletedContactNameOf(provider, it) } )"
        ))
        // The names go to the notification only, never to the log; and the deletions stay pending.
        assertFalse(notice, notice.contains("SyncLog"))
        assertFalse(notice, notice.contains("clearContactDirtyFlag") || notice.contains("provider.delete("))
        // A name as the rejected-contacts notice reads it, else the deleted row's own, only where it came from the
        // contact's name: never an address, a number or a company shown in its place.
        val name = squash(code(adapter.substringAfter("private fun deletedContactNameOf(").substringBefore("\n\n")))
        assertTrue(name, name.contains("displayNameOf(provider, rawContactId) ?: try {"))
        assertTrue(name, name.contains(
            "arrayOf(ContactsContract.RawContacts.DISPLAY_NAME_PRIMARY, ContactsContract.RawContacts.DISPLAY_NAME_SOURCE)"
        ))
        assertTrue(name, name.contains(
            "val fromName = c.moveToFirst() && !c.isNull(1) && c.getInt(1) == ContactsContract.DisplayNameSources.STRUCTURED_NAME " +
                "if (fromName) c.getString(0) else null"
        ))
        assertFalse(name, name.contains("SyncLog"))
        assertTrue(squash(code(body("private fun displayNameOf("))).contains("ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME"))
        assertFalse(adapter.contains("suspend fun noticeRefusedDeletions(") || adapter.contains("suspend fun deletedContactNameOf("))
    }

    @Test
    fun `the notice names each contact alone, as the rejected-contacts notice names them, in one notification per account`() {
        val helper = squash(code(File("src/main/java/me/proton/sync/SyncNotificationHelper.kt").readText().replace("\r\n", "\n")))
        val post = helper.substringAfter("fun notifyContactDeletionsRefused(").substringBefore("fun isContactDeletionsRefusedShown(")
        assertTrue(post, post.contains("postContactList( context, accountName, names.map { it to null },"))
        assertTrue(post, post.contains("notificationKey = \"contacts_delete_refused_\$accountName\","))
        assertTrue(post, post.contains("title = context.getString(R.string.notification_contacts_delete_refused_title),"))
        assertTrue(post, post.contains("summary = context.getString(R.string.notification_contacts_delete_refused_summary, accountName),"))
        // Posted, asked about and withdrawn under one id.
        assertEquals(helper, 3, Regex(Regex.escape("\"contacts_delete_refused_\$accountName\"")).findAll(helper).count())
        // A null reason lists the name alone; every other notice keeps its reason; the contacts' failure switch gates it.
        val list = helper.substringAfter("private fun postContactList(")
        assertTrue(list, list.contains("contacts: List<Pair<String?, String?>>,"))
        assertTrue(list, list.contains("val shownName = name?.takeIf { it.isNotBlank() } ?: unnamed if (reason == null) "))
        assertTrue(list, list.contains("else context.getString(R.string.notification_events_rejected_line, shownName, reason) }"))
        assertTrue(list, list.contains("SyncPreferences.notifySyncFailureKey(\"contacts\", accountName)"))
        // Its English texts, each under its translator note; the translations follow later.
        val xml = File("src/main/res/values/strings.xml").readText().replace("\r\n", "\n")
        for (key in listOf("notification_contacts_delete_refused_title", "notification_contacts_delete_refused_summary")) {
            val at = xml.indexOf("<string name=\"$key\">")
            assertTrue(key, at >= 0)
            assertTrue(key, xml.substring(0, at).trimEnd().endsWith("-->"))
        }
        assertTrue(xml.contains("<string name=\"notification_contacts_delete_refused_title\">Some contacts could not be deleted on Proton</string>"))
        assertTrue(xml.contains(
            "<string name=\"notification_contacts_delete_refused_summary\">These contacts on %1\$s were deleted on this phone, " +
                "but Proton refused to delete them. They still exist on Proton and on your other devices:</string>"
        ))
        // No clause a translator could take for the account: its note says what "them" and "They" stand for.
        val summaryNote = xml.substringBefore("<string name=\"notification_contacts_delete_refused_summary\">").substringAfterLast("<!--")
        assertTrue(summaryNote, summaryNote.contains("\"them\"") && summaryNote.contains("mean these contacts, the ones listed below"))
    }
}

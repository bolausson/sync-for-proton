package me.proton.sync.calendar

import me.proton.sync.R
import me.proton.sync.SyncNotificationHelper
import me.proton.sync.SyncPreferences
import me.proton.sync.SyncScheduling
import me.proton.sync.SyncUtils
import me.proton.sync.backup.BackupSettings
import me.proton.sync.contacts.ContactUploadRefusals
import me.proton.sync.contacts.FakePrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * An event deleted on the phone whose deletion Proton answered with a failure (EventDeletionFailures, CalendarSyncAdapter
 * .settleDeleteResponse). Every such answer counted a failure with no reason, so each sync showed "unexpected error", and
 * a deletion Proton refused for good did so at every sync for ever. Since 2.2.0 it gets a contact deletion's treatment
 * (owner, 2026-11-23): the reason carries Proton's status, a status about the connection or the session fails every run,
 * a refusal fails its first three syncs per event and is then only logged. The tombstone stays pending as before. Made-up
 * accounts only; no event's content anywhere.
 */
class EventDeletionFailuresTest {

    private val account = "event-deletion-a@example.test"

    private companion object {
        const val ADAPTER = "src/main/java/me/proton/sync/calendar/CalendarSyncAdapter.kt"

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

    private fun count(text: String, what: String): Int = Regex(Regex.escape(what)).findAll(text).count()

    /** What one sync does with a deletion Proton answered [httpCode]: whether the run fails (reportDeletionNotTaken's steps). */
    private fun sync(prefs: FakePrefs, androidEventId: Long, httpCode: Int, accountName: String = account): Boolean {
        val refusals = if (EventDeletionFailures.isRefusal(httpCode)) {
            EventDeletionFailures.recordRefusal(prefs, accountName, androidEventId)
        } else 0
        return EventDeletionFailures.failsRun(httpCode, refusals)
    }

    private fun strikes(prefs: FakePrefs, accountName: String = account): Map<Long, Int> =
        ContactUploadRefusals.parseStrikes(prefs.getString(SyncPreferences.eventDeleteStrikesKey(accountName), null))

    // ---- The text shown ---------------------------------------------------------------------------------------------

    @Test
    fun `the reason names no event and carries Proton's status`() {
        assertEquals("Proton did not delete an event deleted on this phone: HTTP 503", EventDeletionFailures.reason(503))
        assertEquals("Proton did not delete an event deleted on this phone: HTTP 422", EventDeletionFailures.reason(422))
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
            403 to (R.string.error_session_expired to R.string.error_session_expired),
            422 to (R.string.error_request_rejected to R.string.error_request_rejected),
            404 to (R.string.error_request_rejected to R.string.error_request_rejected),
            400 to (R.string.error_request_rejected to R.string.error_request_rejected),
        )
        for ((status, pair) in texts) {
            for (masterSyncOn in listOf(true, false)) {
                for (manualOnly in listOf(true, false)) {
                    val retries = SyncScheduling.userSyncRetries(masterSyncOn = masterSyncOn, manualOnly = manualOnly)
                    val accountName = "event-deletion-$status-$masterSyncOn-$manualOnly@example.test"
                    SyncUtils.recordSwallowedFailure("calendar", accountName, EventDeletionFailures.reason(status))
                    val recorded = SyncUtils.consumeSwallowedFailure("calendar", accountName)!!
                    assertEquals("HTTP $status retries=$retries", if (retries) pair.first else pair.second,
                        SyncNotificationHelper.friendlyMessageRes(recorded, retries))
                }
            }
        }
        // What 2.1.0 showed for every one of them: the run counted, no reason recorded.
        assertEquals(R.string.error_unexpected, SyncNotificationHelper.friendlyMessageRes("Sync incomplete", true))
    }

    // ---- When a run fails -------------------------------------------------------------------------------------------

    @Test
    fun `an answer about the connection or the session fails every run it comes in`() {
        for (status in PASSING) {
            assertFalse("$status", EventDeletionFailures.isRefusal(status))
            for (refusals in listOf(0, 1, 3, 4, 100)) assertTrue("$status", EventDeletionFailures.failsRun(status, refusals))
        }
    }

    @Test
    fun `a refusal fails its first three syncs only`() {
        assertEquals(3, EventDeletionFailures.MAX_REPORTED_REFUSALS)
        for (status in REFUSING) {
            assertTrue("$status", EventDeletionFailures.isRefusal(status))
            for (refusals in 1..3) assertTrue("$status $refusals", EventDeletionFailures.failsRun(status, refusals))
            for (refusals in listOf(0, 4, 5, 1000)) assertFalse("$status $refusals", EventDeletionFailures.failsRun(status, refusals))
        }
    }

    @Test
    fun `a deletion Proton never takes fails three syncs, then is only logged, and one that reaches Proton starts again`() {
        val prefs = FakePrefs()
        // Twenty syncs refused: three failed runs, then the deletion stays pending without failing another.
        assertEquals(listOf(true, true, true) + List(17) { false }, List(20) { sync(prefs, 41, 422) })
        // A passing answer in between still fails its run and costs no strike.
        assertTrue(sync(prefs, 41, 503))
        assertTrue(sync(prefs, 41, 429))
        assertEquals(mapOf(41L to 20), strikes(prefs))
        // Each event has its own count, and each account its own.
        assertTrue(sync(prefs, 42, 403))
        assertTrue(sync(prefs, 41, 422, accountName = "event-deletion-b@example.test"))
        assertEquals(mapOf(41L to 1), strikes(prefs, "event-deletion-b@example.test"))
        // Proton took it at last: forgotten, so a later refusal of the same row is reported again.
        EventDeletionFailures.forget(prefs, account, 41)
        assertEquals(mapOf(42L to 1), strikes(prefs))
        assertTrue(sync(prefs, 41, 422))
    }

    @Test
    fun `refusals of deletions no longer pending are pruned, and nothing is written when none go`() {
        val prefs = FakePrefs()
        val key = SyncPreferences.eventDeleteStrikesKey(account)
        // Nothing recorded: nothing to forget, and no key appears.
        EventDeletionFailures.forget(prefs, account, 5)
        EventDeletionFailures.retain(prefs, account) { false }
        assertFalse(prefs.contains(key))
        EventDeletionFailures.recordRefusal(prefs, account, 5)
        EventDeletionFailures.recordRefusal(prefs, account, 6)
        EventDeletionFailures.recordRefusal(prefs, account, 6)
        val before = prefs.getString(key, null)
        EventDeletionFailures.retain(prefs, account) { it == 5L || it == 6L }
        assertTrue(prefs.getString(key, null) === before)
        // Row 5 is no longer a pending deletion: its count goes, row 6 keeps its own.
        EventDeletionFailures.retain(prefs, account) { it == 6L }
        assertEquals(mapOf(6L to 2), strikes(prefs))
        EventDeletionFailures.forget(prefs, account, 6)
        assertNull(prefs.getString(key, null))
    }

    @Test
    fun `refusals counted by uploads in parallel are all kept`() {
        val prefs = FakePrefs()
        // The uploads run three at a time: each count is read and written under the rule's lock.
        val threads = (1L..6L).map { id ->
            Thread { repeat(40) { EventDeletionFailures.recordRefusal(prefs, account, id) } }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals((1L..6L).associateWith { 40 }, strikes(prefs))
    }

    // ---- Stored per account, never in a backup ----------------------------------------------------------------------

    @Test
    fun `the refusal counts belong to their account and never travel`() {
        val key = SyncPreferences.eventDeleteStrikesKey(account)
        assertEquals("event_delete_strikes_$account", key)
        assertEquals(listOf(account), SyncPreferences.accountNamesOfKey(key))
        assertEquals(mapOf(key to account), SyncPreferences.keysOfRemovedAccounts(listOf(key), setOf("other@example.test")))
        assertEquals(emptyMap<String, String>(), SyncPreferences.keysOfRemovedAccounts(listOf(key), setOf(account)))
        assertEquals(SyncPreferences.eventDeleteStrikesKey("renamed"), SyncPreferences.renamedAccountKey(key, account, "renamed"))
        // Android event ids of this phone: meaningless on another.
        assertFalse(BackupSettings.isExportable(key))
    }

    // ---- Where it is wired ------------------------------------------------------------------------------------------

    @Test
    fun `a deletion Proton answered with a failure is reported by its status, and its tombstone stays`() {
        val settle = squash(code(body("private suspend fun settleDeleteResponse(")))
        val failed = settle.substringAfter("} else { SyncLog.e(TAG, \"Failed to delete event")
        assertTrue(settle, failed.length < settle.length)
        assertTrue(failed, failed.contains("reportDeletionNotTaken(account, syncResult, androidEventId, response.code()) }"))
        // Counted where the rule says, never at every sync with no reason, and nothing removes the tombstone or puts the
        // event back: the deletion is sent again at the next sync, as before.
        assertEquals(settle, 0, count(settle, "numIoExceptions"))
        assertFalse(failed, failed.contains("removeTombstone(") || failed.contains("restoreRefusedDelete("))
        // The read-only calendar's 400 still puts the event back, as before.
        assertTrue(settle, settle.contains("if (isReadOnlyCalendar) {"))
        // Proton took it, or it was gone already: forgotten with the event's other records, before its mapping goes.
        val taken = settle.substringBefore("DeleteOpVerdict.REFUSED ->")
        assertTrue(taken, taken.contains(
            "clearDeleteRejectionStrikes(deleteStrikeKey(androidEventId, mapping.protonEventId)) " +
                "forgetEventDeletionRefusals(account.name, androidEventId) calendarEventSyncDao.deleteByProtonId(account.name, mapping.protonEventId)"
        ))
        // A request that threw is still counted at every run, with its cause (NetworkCauseTest guards that catch).
        val deleted = squash(code(body("private suspend fun uploadDeletedRow(")))
        assertTrue(deleted, deleted.contains(
            "synchronized(syncResult.stats) { syncResult.stats.numIoExceptions++ } " +
                "SyncUtils.recordSwallowedFailure(\"calendar\", account.name, \"Could not send a deleted event to Proton\", e) return }"
        ))
    }

    @Test
    fun `the run fails exactly where the rule says, with Proton's status, and the counts of deletions no longer pending go`() {
        val report = squash(code(body("private fun reportDeletionNotTaken(")))
        assertTrue(report, report.contains(
            "val refusals = if (EventDeletionFailures.isRefusal(httpCode)) { EventDeletionFailures.recordRefusal("
        ))
        assertTrue(report, report.contains(
            "if (EventDeletionFailures.failsRun(httpCode, refusals)) { synchronized(syncResult.stats) { syncResult.stats.numIoExceptions++ } " +
                "SyncUtils.recordSwallowedFailure(\"calendar\", account.name, EventDeletionFailures.reason(httpCode)) }"
        ))
        assertEquals(report, 1, count(report, "numIoExceptions++"))
        val prune = squash(code(body("private fun pruneEventDeletionRefusals(")))
        assertTrue(prune, prune.contains("for (row in dirtyRows) if (row.deleted == 1) pending.add(row.androidEventId)"))
        assertTrue(prune, prune.contains("EventDeletionFailures.retain("))
        // After every upload of the run, from the rows it read: a deletion held back keeps its count.
        val upload = squash(code(body("private suspend fun uploadDirtyEvents(")))
        val pruned = upload.indexOf("pruneEventDeletionRefusals(account.name, dirtyRows)")
        assertTrue(upload, pruned > upload.indexOf("}.awaitAll() }"))
        assertEquals(upload, 1, count(upload, "pruneEventDeletionRefusals("))
        // Plain functions: kept off the suspend functions that call them, for the ART limits.
        for (name in listOf("reportDeletionNotTaken(", "forgetEventDeletionRefusals(", "pruneEventDeletionRefusals(")) {
            assertFalse(name, adapter.contains("suspend fun $name"))
        }
    }

    @Test
    fun `no notice reuses the rejected-delete text, which promises the event back`() {
        // notification_events_rejected_delete tells the user the event comes back with the next sync, which is true of an
        // event put back (restoreRefusedDelete) and not of a deletion that stays pending. No notice goes up from the
        // report itself; the lasting notice of the deletions refused for good (2.2.0) has texts of its own (below).
        val english = File("src/main/res/values/strings.xml").readText()
        assertTrue(english.contains("so it comes back to this phone with the next sync.</string>"))
        val report = squash(code(body("private fun reportDeletionNotTaken(")))
        for (notice in listOf("deleteRefusalReason(", "gaveUpByAccount", "notifyEventsRejected(", "R.string.")) {
            assertFalse(notice, report.contains(notice))
        }
        val settled = squash(code(body("private fun noticeRefusedEventDeletions(")))
        for (notice in listOf("deleteRefusalReason(", "gaveUpByAccount", "notifyEventsRejected(", "notification_events_rejected_delete")) {
            assertFalse(notice, settled.contains(notice))
        }
    }

    // ---- The lasting notice from the third refusal on (2.2.0, owner 2026-11-23) ----------------------------------------
    //
    // Built exactly like the contacts' one (ContactDeletionFailuresTest): with Android's automatic sync on, its own retries
    // of the failed runs used the three reported refusals up within minutes, and then only the log said that the event was
    // still on Proton and on the user's other devices.

    private val post = me.proton.sync.contacts.ContactDeletionFailures.Notice.POST
    private val keep = me.proton.sync.contacts.ContactDeletionFailures.Notice.KEEP
    private val cancel = me.proton.sync.contacts.ContactDeletionFailures.Notice.CANCEL

    /** The end of an upload, as noticeRefusedEventDeletions runs it: the verdict, and what the notice names from then on. */
    private fun settle(prefs: FakePrefs, shown: Boolean, accountName: String = account): me.proton.sync.contacts.ContactDeletionFailures.Notice {
        val now = EventDeletionFailures.refusedForGood(prefs, accountName)
        val verdict = EventDeletionFailures.notice(EventDeletionFailures.noticedIds(prefs, accountName), now) { shown }
        EventDeletionFailures.rememberNoticed(prefs, accountName, now)
        return verdict
    }

    /** One sync of [androidEventId]'s deletion and what becomes of the notice at its end, as uploadDirtyEvents runs them. */
    private fun syncWithNotice(prefs: FakePrefs, androidEventId: Long, httpCode: Int, shown: Boolean) =
        sync(prefs, androidEventId, httpCode).let { settle(prefs, shown) }

    @Test
    fun `the lasting notice is posted at the third refusal, and not again at every sync`() {
        val prefs = FakePrefs()
        // Refusals one and two fail their runs and leave nothing to show; the third posts the notice; it is up from then on.
        val verdicts = List(10) { syncWithNotice(prefs, 31, 422, shown = it >= 3) }
        assertEquals(listOf(cancel, cancel, post) + List(7) { keep }, verdicts)
        assertEquals(setOf(31L), EventDeletionFailures.refusedForGood(prefs, account))
        // A passing answer changes nothing, and a notice the user dismissed is not brought back while nothing changes.
        assertEquals(keep, syncWithNotice(prefs, 31, 503, shown = true))
        assertEquals(keep, syncWithNotice(prefs, 31, 422, shown = false))
        // Only deletions of this account, refused three times or more.
        EventDeletionFailures.recordRefusal(prefs, account, 32)
        EventDeletionFailures.recordRefusal(prefs, "event-deletion-b@example.test", 33)
        assertEquals(setOf(31L), EventDeletionFailures.refusedForGood(prefs, account))
        assertEquals(emptySet<Long>(), EventDeletionFailures.refusedForGood(prefs, "event-deletion-b@example.test"))
    }

    @Test
    fun `a second event updates the one notice, and the notice goes once the deletions land or stop being pending`() {
        val prefs = FakePrefs()
        assertEquals(listOf(cancel, cancel, post), List(3) { syncWithNotice(prefs, 31, 422, shown = false) })
        assertEquals(List(2) { keep }, List(2) { syncWithNotice(prefs, 32, 404, shown = false) })
        // The third refusal of 32: the notice is posted again, the same one, naming both, even after the user dismissed it.
        assertEquals(post, syncWithNotice(prefs, 32, 404, shown = false))
        assertEquals(setOf(31L, 32L), EventDeletionFailures.noticedIds(prefs, account))
        // 31's deletion reaches Proton (DELETED or ALREADY_GONE): forgotten, and the notice, still up, names 32 alone.
        EventDeletionFailures.forget(prefs, account, 31)
        assertEquals(post, settle(prefs, shown = true))
        assertEquals(setOf(32L), EventDeletionFailures.noticedIds(prefs, account))
        assertEquals(keep, settle(prefs, shown = true))
        // 32 is no longer a pending deletion (put back by restoreRefusedDelete, or gone from the dirty rows): withdrawn.
        EventDeletionFailures.retain(prefs, account) { false }
        assertEquals(cancel, settle(prefs, shown = true))
        assertFalse(prefs.contains(SyncPreferences.eventDeleteNoticedKey(account)))
        // A deletion refused again from the start is reported by failed runs again, and named at its third refusal.
        assertEquals(listOf(true, true, true), List(3) { sync(prefs, 31, 422) })
        assertEquals(post, settle(prefs, shown = false))
    }

    @Test
    fun `a deletion refused for good is named by the next upload when the one of its third refusal stopped early, or an earlier build counted it`() {
        val prefs = FakePrefs()
        repeat(2) { syncWithNotice(prefs, 31, 422, shown = false) }
        // The upload of the third refusal ends before the notice is settled: the next one names it, then leaves it.
        assertTrue(sync(prefs, 31, 422))
        assertFalse(sync(prefs, 31, 422))
        assertEquals(post, settle(prefs, shown = false))
        assertEquals(listOf(keep, keep, keep), List(3) { syncWithNotice(prefs, 31, 422, shown = it == 0) })
        // Counted by an earlier, unreleased test build, which kept the counts but posted no notice: named at the first upload.
        val earlier = FakePrefs()
        earlier.values[SyncPreferences.eventDeleteStrikesKey(account)] = "31:7,32:1"
        assertEquals(post, syncWithNotice(earlier, 31, 422, shown = false))
        assertEquals(setOf(31L), EventDeletionFailures.noticedIds(earlier, account))
    }

    @Test
    fun `an account renamed takes the notice down, the next sync posts it under the new name, and a dismissed one stays dismissed`() {
        val renamed = "event-deletion-renamed@example.test"
        fun moved(prefs: FakePrefs) {
            for ((key, value) in HashMap(prefs.values)) {
                val to = SyncPreferences.renamedAccountKey(key, account, renamed) ?: continue
                prefs.values.remove(key)
                prefs.values[to] = value
            }
        }
        // Shown at the rename: renameParentAccount takes it down under the old name and forgets what it named there, then
        // the preferences move; the next upload under the new name posts it again from the moved counts.
        val shownAtRename = FakePrefs()
        repeat(3) { syncWithNotice(shownAtRename, 31, 422, shown = true) }
        EventDeletionFailures.forgetNoticed(shownAtRename, account)
        moved(shownAtRename)
        assertEquals(setOf(31L), EventDeletionFailures.refusedForGood(shownAtRename, renamed))
        assertEquals(post, settle(shownAtRename, shown = false, accountName = renamed))
        assertEquals(keep, settle(shownAtRename, shown = true, accountName = renamed))
        // Dismissed before the rename: what it named moves too, and the next upload leaves it dismissed.
        val dismissed = FakePrefs()
        repeat(3) { syncWithNotice(dismissed, 31, 422, shown = false) }
        moved(dismissed)
        assertEquals(keep, settle(dismissed, shown = false, accountName = renamed))
        // And the source: taken down before the preferences move, at a rename and at a removal, and nowhere else.
        val app = squash(code(File("src/main/java/me/proton/sync/ProtonSyncApp.kt").readText().replace("\r\n", "\n")))
        val rename = app.substringAfter("fun renameParentAccount(").substringBefore("renameAccountInPrefs(oldParent.name, desired)")
        val takenDown = "if (SyncNotificationHelper.isEventDeletionsRefusedShown(this, oldParent.name)) { " +
            "SyncNotificationHelper.cancelEventDeletionsRefused(this, oldParent.name) " +
            "me.proton.sync.calendar.EventDeletionFailures.forgetNoticed(prefsForHolds, oldParent.name) }"
        assertTrue(rename, rename.contains(takenDown))
        assertTrue(rename, rename.indexOf(takenDown) < rename.indexOf("return try {"))
        val removal = app.substringAfter("private fun forgetRemovedAccounts(").substringBefore("private fun renameAccountInPrefs(")
        assertTrue(removal, removal.substringAfter("for (name in stale) {").contains("SyncNotificationHelper.cancelEventDeletionsRefused(this, name)"))
        assertEquals(app, 2, Regex(Regex.escape("SyncNotificationHelper.cancelEventDeletionsRefused(")).findAll(app).count())
    }

    @Test
    fun `what the notice named belongs to its account, goes with a removal, and is written only when it changes`() {
        val key = SyncPreferences.eventDeleteNoticedKey(account)
        assertEquals("event_delete_noticed_$account", key)
        assertTrue(SyncPreferences.ACCOUNT_SCOPED_PREFIXES.contains("event_delete_noticed_"))
        assertEquals(listOf(account), SyncPreferences.accountNamesOfKey(key))
        // A removed account's goes with its other keys, so a re-added account of the same name names nothing old.
        assertEquals(mapOf(key to account), SyncPreferences.keysOfRemovedAccounts(listOf(key), setOf("other@example.test")))
        assertEquals(emptyMap<String, String>(), SyncPreferences.keysOfRemovedAccounts(listOf(key), setOf(account)))
        assertEquals(SyncPreferences.eventDeleteNoticedKey("renamed"), SyncPreferences.renamedAccountKey(key, account, "renamed"))
        // Android event ids of this phone: never in a settings backup.
        assertFalse(BackupSettings.isExportable(key))
        val prefs = FakePrefs()
        EventDeletionFailures.rememberNoticed(prefs, account, emptySet())
        assertFalse(prefs.contains(key))
        EventDeletionFailures.rememberNoticed(prefs, account, setOf(9L, 7L))
        assertEquals("7,9", prefs.getString(key, null))
        val stored = prefs.getString(key, null)
        EventDeletionFailures.rememberNoticed(prefs, account, setOf(7L, 9L))
        assertTrue(prefs.getString(key, null) === stored)
        assertEquals(emptySet<Long>(), EventDeletionFailures.noticedIds(prefs, "event-deletion-b@example.test"))
        prefs.values[key] = "7,x,,9"
        assertEquals(setOf(7L, 9L), EventDeletionFailures.noticedIds(prefs, account))
        EventDeletionFailures.forgetNoticed(prefs, account)
        assertFalse(prefs.contains(key))
    }

    @Test
    fun `the notice is settled after the prune, from what it named, and names each event as the rejected-events notice does`() {
        val upload = squash(code(body("private suspend fun uploadDirtyEvents(")))
        val pruned = upload.indexOf("pruneEventDeletionRefusals(account.name, dirtyRows) noticeRefusedEventDeletions(account.name, provider)")
        assertTrue(upload, pruned > upload.indexOf("}.awaitAll() }"))
        assertEquals(upload, 1, count(upload, "noticeRefusedEventDeletions("))
        val notice = squash(code(body("private fun noticeRefusedEventDeletions(")))
        assertTrue(notice, notice.contains(
            "val prefs = PreferenceManager.getDefaultSharedPreferences(context) " +
                "val now = EventDeletionFailures.refusedForGood(prefs, accountName) " +
                "val notice = EventDeletionFailures.notice(EventDeletionFailures.noticedIds(prefs, accountName), now) { " +
                "SyncNotificationHelper.isEventDeletionsRefusedShown(context, accountName) }"
        ))
        assertTrue(notice, notice.contains(
            "if (notice == me.proton.sync.contacts.ContactDeletionFailures.Notice.CANCEL) { SyncNotificationHelper.cancelEventDeletionsRefused(context, accountName) }"
        ))
        // Each event by its title as the rejected-events notice shows it ("(no title)" for none), once per title.
        assertTrue(notice, notice.contains(
            "SyncNotificationHelper.notifyEventDeletionsRefused( context, accountName, " +
                "now.sorted().map { rejectedEventTitle(localEventTitle(provider, it)) }.distinct() )"
        ))
        // What it names is kept last, whatever it decided; titles never reach the log; the deletions stay pending.
        assertTrue(notice, notice.trimEnd().endsWith("EventDeletionFailures.rememberNoticed(prefs, accountName, now)"))
        assertFalse(notice, notice.contains("SyncLog"))
        assertFalse(notice, notice.contains("removeTombstone(") || notice.contains("restoreRefusedDelete(") || notice.contains("provider.delete("))
        assertFalse(adapter.contains("suspend fun noticeRefusedEventDeletions("))
        // The rule is the contacts' own.
        val rule = squash(code(File("src/main/java/me/proton/sync/calendar/EventDeletionFailures.kt").readText().replace("\r\n", "\n")))
        assertTrue(rule, rule.contains(
            "fun notice(noticed: Set<Long>, now: Set<Long>, shown: () -> Boolean): ContactDeletionFailures.Notice = " +
                "ContactDeletionFailures.notice(noticed, now, shown)"
        ))
        // An event put back is no longer a pending deletion: its refusals go there, so it is not named that run.
        val restore = squash(code(body("private suspend fun restoreRefusedDelete(")))
        assertTrue(restore, restore.contains(
            "clearDeleteRejectionStrikes(deleteStrikeKey(androidEventId, mapping.protonEventId)) forgetEventDeletionRefusals(account.name, androidEventId)"
        ))
    }

    @Test
    fun `the notice has texts of its own, silent and gated by the calendar failure switch, and logs counts only`() {
        val helper = squash(code(File("src/main/java/me/proton/sync/SyncNotificationHelper.kt").readText().replace("\r\n", "\n")))
        val posting = helper.substringAfter("fun notifyEventDeletionsRefused(").substringBefore("fun isEventDeletionsRefusedShown(")
        assertTrue(posting, posting.contains("if (!prefs.getBoolean(SyncPreferences.notifySyncFailureKey(\"calendar\", accountName), true)) {"))
        assertTrue(posting, posting.contains("val nId = (\"events_delete_refused_\$accountName\").hashCode()"))
        assertTrue(posting, posting.contains("context.getString(R.string.notification_events_delete_refused_summary, accountName)"))
        assertTrue(posting, posting.contains(".setContentTitle(context.getString(R.string.notification_events_delete_refused_title))"))
        assertTrue(posting, posting.contains("val lines = titles.joinToString(\"\\n\") { \"\\u2022 \$it\" }"))
        assertTrue(posting, posting.contains(".setAutoCancel(true) .setSilent(true)"))
        // Never the rejected-delete text, which promises the event back; and no title in a log line: the titles are read
        // once, into the lines, and the lines go into the notification alone.
        assertFalse(posting, posting.contains("notification_events_rejected_delete") || posting.contains("notification_events_rejected_summary"))
        assertEquals(posting, 1, count(posting, "titles.joinToString"))
        assertEquals(posting, 1, count(posting, "\$lines"))
        assertTrue(posting, posting.contains(".bigText(\"\$summary\\n\$lines\")"))
        assertFalse(posting, posting.contains("\$titles") || posting.contains("titles[") || posting.contains("titles.first"))
        // Posted, asked about and withdrawn under one id.
        assertEquals(helper, 3, count(helper, "\"events_delete_refused_\$accountName\""))
        // Its English texts, each under its translator note; the translations follow later.
        val xml = File("src/main/res/values/strings.xml").readText().replace("\r\n", "\n")
        for (key in listOf("notification_events_delete_refused_title", "notification_events_delete_refused_summary")) {
            val at = xml.indexOf("<string name=\"$key\">")
            assertTrue(key, at >= 0)
            assertTrue(key, xml.substring(0, at).trimEnd().endsWith("-->"))
        }
        assertTrue(xml.contains("<string name=\"notification_events_delete_refused_title\">Some events could not be deleted on Proton</string>"))
        assertTrue(xml.contains(
            "<string name=\"notification_events_delete_refused_summary\">These events on %1\$s were deleted on this phone, " +
                "but Proton refused to delete them. They still exist on Proton and on your other devices:</string>"
        ))
        val summaryNote = xml.substringBefore("<string name=\"notification_events_delete_refused_summary\">").substringAfterLast("<!--")
        assertTrue(summaryNote, summaryNote.contains("\"them\"") && summaryNote.contains("mean these events, the ones listed below"))
        assertTrue(summaryNote, summaryNote.contains("Do not say that the events come back to this phone"))
        assertFalse(summaryNote, xml.substringAfter("<string name=\"notification_events_delete_refused_summary\">").substringBefore("</string>").contains("comes back"))
    }
}

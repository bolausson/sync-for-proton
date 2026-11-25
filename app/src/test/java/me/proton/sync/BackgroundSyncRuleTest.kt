package me.proton.sync

import me.proton.sync.SyncScheduling.BackgroundRequest
import me.proton.sync.SyncScheduling.StaleRun
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The owner's rule since the event loop went (2.2.0): with Android's automatic sync off the app syncs nothing by itself,
 * and with it on it syncs at the account's interval.
 *
 * Every sync the app asks for on its own (a calendar setting changed, a Fallback full sync, a re-time) goes through one
 * rule, SyncScheduling.backgroundRequest, as an ordinary request: never SYNC_EXTRAS_MANUAL, which gets past Android's
 * switch, the pause after a sign-in problem and the backoff, and which Android retries; never SYNC_EXTRAS_EXPEDITED,
 * which pre-empts a running calendar sync; and never while a full re-sync of that type is being prepared. A sync the user
 * starts is manual and not expedited, and while automatic sync is off or the account is on Manual only it carries
 * SYNC_EXTRAS_DO_NOT_RETRY, because Android retried a failed one in the background with the switch still ignored; a
 * retry of one asked before the switch changed, and a manual background request an older version left queued, end at
 * their start (SyncScheduling.staleManualRun). The parts that need Android are pinned in the source, as
 * PeriodicSyncWriterTest does, on the code with its comments taken out, so a comment never satisfies a check.
 */
class BackgroundSyncRuleTest {

    private val account = "me@example.com"

    /** FullResyncHold is process-wide: nothing a test marked stays for the next one. */
    @After
    fun clearHold() {
        for (name in FullResyncHold.preparing()) FullResyncHold.end(name)
    }

    private fun rule(
        syncAllowed: Boolean = true,
        switchedOn: Boolean = true,
        manualOnly: Boolean = false,
        masterSyncOn: Boolean = true,
        paused: Boolean = false,
        resyncPreparing: Boolean = false,
    ) = SyncScheduling.backgroundRequest(syncAllowed, switchedOn, manualOnly, masterSyncOn, paused, resyncPreparing)

    @Test
    fun `a background sync is asked for only when nothing stands against it`() {
        assertEquals(BackgroundRequest.SEND, rule())
        for (bits in 0 until 64) {
            val decision = rule(
                syncAllowed = bits and 1 == 0,
                switchedOn = bits and 2 == 0,
                manualOnly = bits and 4 != 0,
                masterSyncOn = bits and 8 == 0,
                paused = bits and 16 != 0,
                resyncPreparing = bits and 32 != 0,
            )
            assertEquals("combination $bits", bits == 0, decision == BackgroundRequest.SEND)
        }
    }

    @Test
    fun `each condition against it gives its own reason`() {
        assertEquals(BackgroundRequest.SUBSCRIPTION_INACTIVE, rule(syncAllowed = false))
        assertEquals(BackgroundRequest.SWITCHED_OFF, rule(switchedOn = false))
        assertEquals(BackgroundRequest.MANUAL_ONLY, rule(manualOnly = true))
        assertEquals(BackgroundRequest.AUTO_SYNC_OFF, rule(masterSyncOn = false))
        assertEquals(BackgroundRequest.PAUSED, rule(paused = true))
        assertEquals(BackgroundRequest.RESYNC_PREPARING, rule(resyncPreparing = true))
    }

    @Test
    fun `the first reason wins, subscription, switch, Manual only, automatic sync, pause, re-sync`() {
        assertEquals(BackgroundRequest.SUBSCRIPTION_INACTIVE, rule(false, false, true, false, true, true))
        assertEquals(BackgroundRequest.SWITCHED_OFF, rule(true, false, true, false, true, true))
        assertEquals(BackgroundRequest.MANUAL_ONLY, rule(true, true, true, false, true, true))
        assertEquals(BackgroundRequest.AUTO_SYNC_OFF, rule(true, true, false, false, true, true))
        assertEquals(BackgroundRequest.PAUSED, rule(true, true, false, true, true, true))
        assertEquals(BackgroundRequest.RESYNC_PREPARING, rule(true, true, false, true, false, true))
    }

    @Test
    fun `no decision is a manual or an expedited request`() {
        assertEquals(
            listOf(
                BackgroundRequest.SEND, BackgroundRequest.SUBSCRIPTION_INACTIVE, BackgroundRequest.SWITCHED_OFF,
                BackgroundRequest.MANUAL_ONLY, BackgroundRequest.AUTO_SYNC_OFF, BackgroundRequest.PAUSED,
                BackgroundRequest.RESYNC_PREPARING,
            ),
            BackgroundRequest.entries.toList(),
        )
        for (d in BackgroundRequest.entries) {
            assertFalse(d.name, d.name == "MANUAL" || d.name.contains("EXPEDITED"))
        }
    }

    @Test
    fun `the reasons read as the log lines use them`() {
        assertEquals("the subscription is inactive", BackgroundRequest.SUBSCRIPTION_INACTIVE.reason("calendar"))
        assertEquals("contacts sync is switched off", BackgroundRequest.SWITCHED_OFF.reason("contacts"))
        assertEquals("the sync interval is Manual only", BackgroundRequest.MANUAL_ONLY.reason("calendar"))
        assertEquals("Android's automatic sync is off", BackgroundRequest.AUTO_SYNC_OFF.reason("calendar"))
        assertEquals("paused after a sign-in problem", BackgroundRequest.PAUSED.reason("calendar"))
        assertEquals("a full re-sync of its calendar is being prepared", BackgroundRequest.RESYNC_PREPARING.reason("calendar"))
        assertEquals("a full re-sync of its contacts is being prepared", BackgroundRequest.RESYNC_PREPARING.reason("contacts"))
    }

    @Test
    fun `the Fallback full sync runs on Manual only, but only while automatic sync is on and not paused`() {
        // Its own interval: SyncWorker asks with manualOnlyApplies = false, which requestBackgroundSync turns into
        // manualOnly = false whatever the account's interval is (pinned in the source below).
        assertEquals(BackgroundRequest.SEND, rule(manualOnly = false))
        assertEquals(BackgroundRequest.AUTO_SYNC_OFF, rule(manualOnly = false, masterSyncOn = false))
        assertEquals(BackgroundRequest.PAUSED, rule(manualOnly = false, paused = true))
        val runOnce = squash(code(body(source(WORKER), "internal fun runOnce(", indent = "        ")))
        assertTrue(runOnce, Regex("""SyncScheduling\.requestBackgroundSync\([^)]*manualOnlyApplies = false""").containsMatchIn(runOnce))
        val background = squash(code(body(scheduling(), "internal fun requestBackgroundSync(")))
        assertTrue(background.contains("val manualOnly = manualOnlyApplies && isManualOnly(context, parentAccountName)"))
    }

    @Test
    fun `a sync the user started is retried by Android only with automatic sync on and an interval`() {
        assertTrue(SyncScheduling.userSyncRetries(masterSyncOn = true, manualOnly = false))
        assertFalse(SyncScheduling.userSyncRetries(masterSyncOn = false, manualOnly = false))
        assertFalse(SyncScheduling.userSyncRetries(masterSyncOn = true, manualOnly = true))
        assertFalse(SyncScheduling.userSyncRetries(masterSyncOn = false, manualOnly = true))
    }

    @Test
    fun `a manual request is manual, never expedited, and carries no retry when the rule says no`() {
        // ContentResolver's constants, written out: SYNC_EXTRAS_MANUAL is "force", DO_NOT_RETRY "do_not_retry".
        assertEquals(listOf("force"), SyncScheduling.manualExtraFlags(retries = true))
        assertEquals(listOf("force", "do_not_retry"), SyncScheduling.manualExtraFlags(retries = false))
        for (retries in listOf(true, false)) {
            assertFalse("expedited pre-empts a running sync", SyncScheduling.manualExtraFlags(retries).contains("expedited"))
        }
    }

    private fun stale(
        manual: Boolean = true,
        scheduledByWorker: Boolean = false,
        firstAttempt: Boolean = false,
        doNotRetry: Boolean = false,
        forced: Boolean = false,
        masterSyncOn: Boolean = true,
        manualOnly: Boolean = false,
    ) = SyncScheduling.staleManualRun(manual, scheduledByWorker, firstAttempt, doNotRetry, forced, masterSyncOn, manualOnly)

    @Test
    fun `Android's retry of a manual sync runs only while the retry rule still allows one`() {
        assertEquals("retries allowed: the retry runs", StaleRun.NONE, stale())
        assertEquals("automatic sync switched off since the tap", StaleRun.RETRY_NOT_ALLOWED, stale(masterSyncOn = false))
        assertEquals("Manual only chosen since the tap", StaleRun.RETRY_NOT_ALLOWED, stale(manualOnly = true))
        assertEquals(StaleRun.RETRY_NOT_ALLOWED, stale(masterSyncOn = false, manualOnly = true))
        // The first attempt is the user's own request, whatever the switches say now.
        assertEquals(StaleRun.NONE, stale(firstAttempt = true, masterSyncOn = false))
        assertEquals(StaleRun.NONE, stale(firstAttempt = true, manualOnly = true))
        // Asked with DO_NOT_RETRY, Android brings it back only after SYNC_ALREADY_IN_PROGRESS: a tap during a running sync.
        assertEquals(StaleRun.NONE, stale(doNotRetry = true, masterSyncOn = false))
        assertEquals(StaleRun.NONE, stale(doNotRetry = true, manualOnly = true))
        // A full re-sync keeps its retries, so a half-done one finishes as one.
        assertEquals(StaleRun.NONE, stale(forced = true, masterSyncOn = false))
        assertEquals(StaleRun.NONE, stale(forced = true, manualOnly = true))
        // An ordinary run is never this case: Android's own switch applies to it.
        assertEquals(StaleRun.NONE, stale(manual = false, masterSyncOn = false))
        assertEquals(StaleRun.NONE, stale(manual = false, scheduledByWorker = true, masterSyncOn = false))
    }

    @Test
    fun `a manual background request an older version left queued never runs`() {
        // 2.1.0's event loop and Fallback full sync asked manual with the watchdog's flag; this version never does.
        for (firstAttempt in listOf(true, false)) {
            for (masterSyncOn in listOf(true, false)) {
                assertEquals(
                    "firstAttempt=$firstAttempt master=$masterSyncOn",
                    StaleRun.OLD_BACKGROUND_REQUEST,
                    stale(scheduledByWorker = true, firstAttempt = firstAttempt, masterSyncOn = masterSyncOn),
                )
            }
        }
        assertEquals(StaleRun.OLD_BACKGROUND_REQUEST, stale(scheduledByWorker = true, doNotRetry = true))
        val putters = kotlinSources().filter { f -> codeLines(f).any { it.contains("EXTRA_SCHEDULED_BY_WORKER, true") } }
        assertEquals(listOf("SyncWorker.kt"), putters.map { it.name })
    }

    @Test
    fun `a full re-sync's preparation is held process-wide, and a change made meanwhile is asked for once`() {
        assertFalse(FullResyncHold.isPreparing(account, CALENDAR))
        assertFalse("nothing to hold for", FullResyncHold.holdIfPreparing(account, "range"))
        assertNull(FullResyncHold.end(account))

        FullResyncHold.start(account, "all")
        assertTrue(FullResyncHold.isPreparing(account, CALENDAR))
        assertTrue(FullResyncHold.isPreparing(account, CONTACTS))
        assertEquals(setOf(account), FullResyncHold.preparing())
        assertFalse("another account is not held", FullResyncHold.isPreparing("other@example.com", CALENDAR))
        assertFalse("another account is not held", FullResyncHold.holdIfPreparing("other@example.com", "range"))
        assertTrue(FullResyncHold.holdIfPreparing(account, "range"))
        assertTrue(FullResyncHold.holdIfPreparing(account, "external calendars"))
        assertTrue(FullResyncHold.holdIfPreparing(account, "range"))
        assertEquals("one request, each change named once", "range, external calendars", FullResyncHold.end(account))
        assertFalse(FullResyncHold.isPreparing(account, CALENDAR))
        assertFalse(FullResyncHold.isPreparing(account, CONTACTS))
        assertNull("asked once only", FullResyncHold.end(account))

        FullResyncHold.start(account, "all")
        val seen = FullResyncHold.preparing()
        assertNull("a preparation with nothing held asks for nothing", FullResyncHold.end(account))
        assertEquals("preparing() is a copy", setOf(account), seen)
        assertEquals(emptySet<String>(), FullResyncHold.preparing())
    }

    @Test
    fun `a full re-sync holds only the types it re-syncs`() {
        // Contacts only: the calendar syncs on, its re-time and settings syncs included, and nothing is held for it.
        FullResyncHold.start(account, "contacts")
        assertTrue(FullResyncHold.isPreparing(account, CONTACTS))
        assertFalse(FullResyncHold.isPreparing(account, CALENDAR))
        assertFalse("a calendar setting is asked for at once", FullResyncHold.holdIfPreparing(account, "range"))
        assertEquals("the sync buttons still refuse", setOf(account), FullResyncHold.preparing())
        assertNull(FullResyncHold.end(account))

        // Calendar only: the contacts sync goes on.
        FullResyncHold.start(account, "calendar")
        assertTrue(FullResyncHold.isPreparing(account, CALENDAR))
        assertFalse(FullResyncHold.isPreparing(account, CONTACTS))
        assertTrue(FullResyncHold.holdIfPreparing(account, "range"))
        assertEquals("range", FullResyncHold.end(account))

        // A second preparation of the same account (the debug verbs do not ask) adds its type.
        FullResyncHold.start(account, "contacts")
        FullResyncHold.start(account, "calendar")
        assertTrue(FullResyncHold.isPreparing(account, CONTACTS) && FullResyncHold.isPreparing(account, CALENDAR))
        assertNull(FullResyncHold.end(account))

        assertEquals(setOf(CONTACTS), FullResyncHold.authoritiesOf("contacts"))
        assertEquals(setOf(CALENDAR), FullResyncHold.authoritiesOf("calendar"))
        assertEquals(setOf(CONTACTS, CALENDAR), FullResyncHold.authoritiesOf("all"))
        assertEquals("an unknown section holds both", setOf(CONTACTS, CALENDAR), FullResyncHold.authoritiesOf("both"))
    }

    @Test
    fun `an answer's calendar sync waits for a calendar re-sync's preparation, and is asked for once after it`() {
        // Nothing prepared, or only the contacts: the answer asks at once.
        assertFalse(FullResyncHold.holdAnswerIfPreparing(account))
        FullResyncHold.start(account, "contacts")
        assertFalse("a contacts re-sync touches no calendar row", FullResyncHold.holdAnswerIfPreparing(account))
        assertNull(FullResyncHold.end(account))
        assertNull(FullResyncHold.takeHeldAnswer(account))

        FullResyncHold.start(account, "calendar")
        assertFalse("another account is not held", FullResyncHold.holdAnswerIfPreparing("other@example.com"))
        assertTrue(FullResyncHold.holdAnswerIfPreparing(account))
        assertTrue(FullResyncHold.holdAnswerIfPreparing(account))
        // Not a settings change: the settings request is not asked for them.
        assertNull(FullResyncHold.end(account))
        assertEquals("one sync for every answer, without EXTRA_USER_SYNC_NOW", false, FullResyncHold.takeHeldAnswer(account))
        assertNull("taken once", FullResyncHold.takeHeldAnswer(account))
        assertFalse("after the preparation an answer asks at once", FullResyncHold.holdAnswerIfPreparing(account))

        // A collision's answer keeps its EXTRA_USER_SYNC_NOW for the one request, whatever came before or after it.
        FullResyncHold.start(account, "all")
        assertTrue(FullResyncHold.holdAnswerIfPreparing(account))
        assertTrue(FullResyncHold.holdAnswerIfPreparing(account, userSyncNow = true))
        assertTrue(FullResyncHold.holdAnswerIfPreparing(account))
        assertTrue(FullResyncHold.holdIfPreparing(account, "range"))
        assertEquals("range", FullResyncHold.end(account))
        assertEquals(true, FullResyncHold.takeHeldAnswer(account))
        assertNull(FullResyncHold.takeHeldAnswer(account))
        assertNull(FullResyncHold.takeHeldAnswer("other@example.com"))
    }

    // ---- The request id that keeps a request from being lost to a running sync ----

    @Test
    fun `the request id is the newest running sync's start, and only while the account's own sync runs`() {
        assertNull("nothing of this account runs", SyncScheduling.runningSyncId(accountSyncActive = false, startTimes = listOf(500L), newId = 7L))
        assertNull(SyncScheduling.runningSyncId(accountSyncActive = false, startTimes = emptyList(), newId = 7L))
        assertEquals(900L, SyncScheduling.runningSyncId(accountSyncActive = true, startTimes = listOf(500L, 900L, 700L), newId = 7L))
        // Running, but no start time listed (it ended meanwhile, or the list is withheld): a new id, never none, which
        // could equal the running sync's own extras.
        assertEquals(7L, SyncScheduling.runningSyncId(accountSyncActive = true, startTimes = emptyList(), newId = 7L))
    }

    @Test
    fun `answers given while one sync runs wait as one request, never equal to the running sync's`() {
        // Android's own rule (SyncManager.scheduleSyncOperationH): a request equal to a running sync's is dropped, and one
        // equal to a waiting request is merged into it. A sync running since 1000 was itself asked while an earlier one,
        // started at 400, ran: its own id is 400.
        val runningOwnId = 400L
        val answers = List(3) { i -> SyncScheduling.runningSyncId(accountSyncActive = true, startTimes = listOf(1000L), newId = 5000L + i) }
        assertEquals("three answers, one waiting request", setOf(1000L), answers.toSet())
        assertTrue("never dropped against the running sync", answers.none { it == runningOwnId })
        // That request runs from 2500: an answer given now is not its 1000, so it is not dropped against it either.
        assertEquals(2500L, SyncScheduling.runningSyncId(accountSyncActive = true, startTimes = listOf(2500L), newId = 6000L))
    }

    @Test
    fun `an answer's id is read for the sync its account runs`() {
        assertEquals(CONTACTS, SyncScheduling.syncAuthorityOf(ProtonSyncApp.ACCOUNT_TYPE_ADDRESS_BOOK))
        assertEquals(CALENDAR, SyncScheduling.syncAuthorityOf(ProtonSyncApp.ACCOUNT_TYPE))
    }

    // ---- What the removed event loop left behind ----

    @Test
    fun `the start-up clean-up removes the event loop's two settings and nothing else`() {
        val kept = listOf(
            "event_loop_interval_$account",
            "fallback_calendar_interval_$account",
            "calendar_sync_mode_$account",
            "calendar_trim_past_$account",
            "contact_filter_dirty_$account",
        )
        val retired = listOf("event_loop_id_$account", "calendar_settings_dirty_$account")
        assertEquals(retired, SyncScheduling.retiredKeys(kept + retired))
        assertEquals(emptyList<String>(), SyncScheduling.retiredKeys(kept))
        // The interval stays: it is the period of Android's periodic syncs, and every settings backup carries it.
        assertEquals("event_loop_interval_$account", SyncPreferences.eventLoopIntervalKey(account))
    }

    @Test
    fun `a calendar setting changed on 2_1_0 still gets its sync, for an account that is still here`() {
        val all = mapOf(
            "calendar_settings_dirty_a@example.com" to true,
            "calendar_settings_dirty_b@example.com" to false,
            "calendar_settings_dirty_gone@example.com" to true,
        )
        assertEquals(
            listOf("a@example.com"),
            SyncScheduling.accountsWithPendingSettings(all, listOf("a@example.com", "b@example.com")),
        )
    }

    @Test
    fun `a restore asks for the settings sync of an account whose calendar keys it carries, not for its interval alone`() {
        val calendarKeys = listOf(
            SyncPreferences.calendarSyncModeKey(account),
            SyncPreferences.calendarPastDaysKey(account),
            SyncPreferences.calendarFutureDaysKey(account),
            SyncPreferences.includeExternalCalendarsKey(account),
        )
        for (key in calendarKeys) {
            assertEquals(key, listOf(account), SyncScheduling.accountsWithRestoredCalendarSettings(listOf(key), listOf(account)))
        }
        assertEquals(
            emptyList<String>(),
            SyncScheduling.accountsWithRestoredCalendarSettings(listOf(SyncPreferences.eventLoopIntervalKey(account)), listOf(account)),
        )
        assertEquals(
            "another account's keys are not this one's",
            emptyList<String>(),
            SyncScheduling.accountsWithRestoredCalendarSettings(calendarKeys, listOf("other@example.com")),
        )
    }

    @Test
    fun `the scheduled jobs are cancelled by 2_1_0's class name`() {
        // WorkRequest.Builder tagged every event-loop request with the worker's class name, which its R8 rule kept.
        assertEquals("me.proton.sync.EventLoopWorker", SyncScheduling.RETIRED_EVENT_LOOP_TAG)
    }

    // ---- Source guards ----

    private companion object {
        const val CALENDAR = SyncConstants.AUTHORITY_CALENDAR
        const val CONTACTS = SyncConstants.AUTHORITY_CONTACTS
        const val SCHEDULING = "main/java/me/proton/sync/SyncScheduling.kt"
        const val WORKER = "main/java/me/proton/sync/SyncWorker.kt"
        const val TZ_RULES = "main/java/me/proton/sync/calendar/TimeZoneRuleSource.kt"
        const val ACTIVITY = "main/java/me/proton/sync/settings/SyncSettingsActivity.kt"
        const val DIALOG = "main/java/me/proton/sync/settings/RangeTrimDialog.kt"
        const val CALENDAR_ADAPTER = "main/java/me/proton/sync/calendar/CalendarSyncAdapter.kt"
        const val CONTACTS_ADAPTER = "main/java/me/proton/sync/contacts/ContactsSyncAdapter.kt"
        const val IMPORT = "main/java/me/proton/sync/backup/ImportManager.kt"
        const val APP = "main/java/me/proton/sync/ProtonSyncApp.kt"
        val ANSWER_RECEIVERS = listOf(
            "main/java/me/proton/sync/calendar/EventConflictReceiver.kt",
            "main/java/me/proton/sync/RemoteDeletionReceiver.kt",
            "main/java/me/proton/sync/calendar/BulkDeletionReceiver.kt",
            // The one handler of every answer about a new event in a shared calendar (its receiver, its dialog, the
            // debug verbs), which asks for the sync of Publish and Move itself.
            "main/java/me/proton/sync/calendar/NewEventHoldActions.kt",
        )

        /** The answers' entry points: each goes through NewEventHoldActions.answer and asks for no sync of its own. */
        val NEW_EVENT_HOLD_ENTRY_POINTS = listOf(
            "main/java/me/proton/sync/calendar/NewEventHoldReceiver.kt",
            "main/java/me/proton/sync/settings/NewEventHoldDialog.kt",
        )
    }

    private fun source(path: String) = File("src/$path").readText().replace("\r\n", "\n")

    private fun scheduling() = source(SCHEDULING)

    /** The text of the function that starts with [signature], up to its closing brace at [indent]. */
    private fun body(src: String, signature: String, indent: String = "    "): String {
        assertTrue("$signature not found", src.contains(signature))
        return src.substringAfter(signature).substringBefore("\n$indent}\n")
    }

    /** A declaration without braces (an expression body, a list), from [signature] up to the next blank line. */
    private fun declaration(src: String, signature: String): String {
        assertTrue("$signature not found", src.contains(signature))
        return signature + src.substringAfter(signature).substringBefore("\n\n")
    }

    /** [text] without KDoc, block comment lines and line comments; the code lines keep their indentation. */
    private fun code(text: String): String =
        text.lines()
            .filterNot { it.trim().let { t -> t.startsWith("*") || t.startsWith("/*") || t.startsWith("//") } }
            .joinToString("\n") { it.substringBefore(" // ") }

    /** Every run of whitespace as one space, so a check reads the code, not its layout. */
    private fun squash(text: String): String = text.replace(Regex("\\s+"), " ")

    /** Every Kotlin file of the app's own sources, as the public snapshot exports them. */
    private fun kotlinSources(): List<File> =
        listOf("main", "direct", "play", "debug")
            .map { File("src/$it/java") }
            .filter { it.isDirectory }
            .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }

    private fun filesContaining(text: String): List<String> =
        kotlinSources().filter { it.readText().contains(text) }.map { it.name }.sorted()

    /** The code of [file] line by line, trimmed, without KDoc, block comment lines and line comments. */
    private fun codeLines(file: File): List<String> =
        file.readLines().map { it.trim() }
            .filterNot { it.startsWith("*") || it.startsWith("/*") || it.startsWith("//") }
            .map { it.substringBefore(" // ") }

    private fun failWith(message: String): Nothing = throw AssertionError(message)

    /** The arguments of the call that starts right after [open] in [line], split at its own commas; null when it does not end there. */
    private fun callArguments(line: String, open: String): List<String>? {
        val rest = line.substringAfter(open)
        val args = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        for (c in rest) {
            when {
                c == '(' || c == '{' || c == '[' -> { depth++; current.append(c) }
                (c == ')' || c == '}' || c == ']') && depth > 0 -> { depth--; current.append(c) }
                c == ')' -> { args.add(current.toString().trim()); return args.filter { it.isNotEmpty() } }
                c == ',' && depth == 0 -> { args.add(current.toString().trim()); current.clear() }
                else -> current.append(c)
            }
        }
        return null
    }

    @Test
    fun `only SyncScheduling's manualExtraFlags asks for a manual sync`() {
        assertEquals(emptyList<String>(), filesContaining("putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL"))
        val src = code(scheduling())
        // In SyncScheduling the constant is named where it is put (manualExtraFlags), where an ordinary request loses it
        // (NOT_ORDINARY), and otherwise only read.
        val flags = declaration(src, "internal fun manualExtraFlags(")
        assertTrue(flags, squash(flags).contains("if (retries) listOf(ContentResolver.SYNC_EXTRAS_MANUAL)"))
        val rest = src.replace(flags, "").replace(declaration(src, "private val NOT_ORDINARY = listOf("), "")
        for (line in rest.lines().filter { it.contains("SYNC_EXTRAS_MANUAL") }) {
            assertTrue("SyncScheduling.kt: $line", line.contains("getBoolean(ContentResolver.SYNC_EXTRAS_MANUAL"))
        }
        assertTrue(squash(code(body(scheduling(), "private fun manualExtras("))).contains("for (flag in manualExtraFlags(retries)) putBoolean(flag, true)"))
        // Not in any other form either: elsewhere the constant is only read, so a request the user starts (a new
        // notification answer too) has to take SyncScheduling.userSyncExtras.
        for (file in kotlinSources().filter { it.name != "SyncScheduling.kt" }) {
            for (line in codeLines(file).filter { it.contains("SYNC_EXTRAS_MANUAL") }) {
                assertTrue(
                    "${file.name}: $line",
                    line.contains("getBoolean(ContentResolver.SYNC_EXTRAS_MANUAL") || line.contains("flag(ContentResolver.SYNC_EXTRAS_MANUAL)"),
                )
            }
        }
    }

    @Test
    fun `no request is expedited`() {
        assertEquals(emptyList<String>(), filesContaining("putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED"))
        for (file in kotlinSources()) {
            val named = codeLines(file).filter { it.contains("SYNC_EXTRAS_EXPEDITED") }
            if (file.name == "SyncScheduling.kt") {
                // Only in NOT_ORDINARY, the list requestBackgroundSync takes out of what it sends.
                assertEquals(listOf("ContentResolver.SYNC_EXTRAS_EXPEDITED,"), named)
                assertTrue(declaration(code(scheduling()), "private val NOT_ORDINARY = listOf(").contains("ContentResolver.SYNC_EXTRAS_EXPEDITED,"))
            } else {
                assertTrue("${file.name}: $named", named.isEmpty())
            }
        }
    }

    @Test
    fun `only the debug verb auto-sync changes Android's automatic sync switch`() {
        // The app obeys the switch and never changes it. The one exception is the debug verb (debug builds), so that a
        // device test can switch it without tapping Settings: one call, in debugAutoSync, called only from the
        // auto_sync branch of handleDebugCommand, which returns at once outside a debug build.
        assertEquals(listOf("SyncSettingsActivity.kt"), filesContaining("setMasterSyncAutomatically("))
        val activity = code(source("main/java/me/proton/sync/settings/SyncSettingsActivity.kt"))
        assertEquals(1, activity.lines().count { it.contains("setMasterSyncAutomatically(") })
        assertTrue(body(activity, "private fun debugAutoSync(").contains("ContentResolver.setMasterSyncAutomatically(state == \"on\")"))
        val calls = activity.lines().filter { it.contains("debugAutoSync(") && !it.contains("private fun debugAutoSync(") }
        assertEquals(calls.toString(), 1, calls.size)
        val debug = body(activity, "private fun handleDebugCommand(")
        assertTrue(squash(debug).contains("if (!me.proton.sync.BuildConfig.DEBUG || intent == null) return"))
        assertTrue(debug.substringAfter("\"auto_sync\" -> {").substringBefore("\n            }\n").contains("debugAutoSync(state)"))
    }

    @Test
    fun `the retry rule is wired as the pure functions say`() {
        val src = scheduling()
        val user = squash(code(body(src, "internal fun userSyncExtras(")))
        assertTrue(
            user,
            user.contains(
                "val retries = userSyncRetries( masterSyncOn = ContentResolver.getMasterSyncAutomatically(), " +
                    "manualOnly = isManualOnly(context, parentAccountName), )"
            ),
        )
        assertTrue(user, user.contains("return manualExtras(retries).apply {"))
        assertTrue(user, user.contains("if (answering != null) putRunningSyncId(this, answering, syncAuthorityOf(answering.type))"))
        // The forced re-syncs keep Android's retries, so a half-done re-sync finishes as one.
        assertTrue(
            Regex("""fun forcedResyncExtras\(flag: String\)[^{=]*(=|\{)[^}]*manualExtras\(retries = true\)""").containsMatchIn(squash(code(src))),
        )
        // The catch-up's manual first sync follows the same rule; its other request is ordinary.
        assertTrue(squash(code(body(src, "private fun catchUpExtras("))).contains("val extras = if (manual) manualExtras(retries) else Bundle()"))
        assertTrue(squash(code(body(src, "fun requestCatchUpSyncs("))).contains("retries = userSyncRetries(masterSyncOn = masterSync, manualOnly = interval < 0)"))
        // The half at the start of a run reads the flags it decides by.
        val stale = squash(code(body(src, "fun endsStaleManualRun(")))
        for (wired in listOf(
            "if (!extras.getBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, false)) return false",
            "val masterSyncOn = ContentResolver.getMasterSyncAutomatically()",
            "scheduledByWorker = extras.getBoolean(SyncConstants.EXTRA_SCHEDULED_BY_WORKER, false)",
            "firstAttempt = extras.getBoolean(ContentResolver.SYNC_EXTRAS_IGNORE_BACKOFF, false)",
            "doNotRetry = extras.getBoolean(ContentResolver.SYNC_EXTRAS_DO_NOT_RETRY, false)",
            "forced = extras.getBoolean(SyncConstants.EXTRA_FORCE_REMOTE_TO_LOCAL, false) || " +
                "extras.getBoolean(SyncConstants.EXTRA_FORCE_LOCAL_TO_REMOTE, false)",
            "masterSyncOn = masterSyncOn",
            "manualOnly = isManualOnly(context, parentAccountName)",
            "StaleRun.NONE -> return false",
        )) {
            assertTrue(wired, stale.contains(wired))
        }
    }

    @Test
    fun `both adapters end a stale manual run before the ongoing notification`() {
        for ((path, label) in listOf(CALENDAR_ADAPTER to "Calendar", CONTACTS_ADAPTER to "Contacts")) {
            val perform = code(body(source(path), "override fun onPerformSync("))
            val check = perform.indexOf("SyncScheduling.endsStaleManualRun(")
            assertTrue(path, check >= 0)
            assertTrue("$path: after the type's switch", perform.indexOf("ContentResolver.getSyncAutomatically(") in 0 until check)
            assertTrue("$path: before the ongoing notification", check < perform.indexOf("SyncNotificationHelper.showOngoing("))
            assertTrue(path, perform.contains("extras, \"$label\", TAG)) return"))
        }
    }

    @Test
    fun `background requests are ordinary ones, each settings request has its own id, and a preparation holds them`() {
        val src = scheduling()
        val background = code(body(src, "internal fun requestBackgroundSync("))
        val settings = code(body(src, "internal fun requestSettingsSync("))
        for (forbidden in listOf(
            "SYNC_EXTRAS_MANUAL", "SYNC_EXTRAS_EXPEDITED", "EXTRA_USER_SYNC_NOW",
            "manualExtras(", "userSyncExtras(", "forcedResyncExtras(", "catchUpExtras(",
        )) {
            assertFalse("requestBackgroundSync: $forbidden", background.contains(forbidden))
            assertFalse("requestSettingsSync: $forbidden", settings.contains(forbidden))
        }
        val bg = squash(background)
        assertTrue(bg.contains("backgroundRequest("))
        assertTrue(bg.contains("paused = SyncUtils.isSyncPaused(context, parentAccountName, isManual = false)"))
        assertTrue(bg.contains("resyncPreparing = FullResyncHold.isPreparing(parentAccountName, authority)"))
        // What goes out is ordinary, whatever a caller passed.
        val taken = bg.indexOf("for (key in NOT_ORDINARY) extras.remove(key)")
        assertTrue(bg, taken >= 0 && taken < bg.indexOf("ContentResolver.requestSync(account, authority, extras)"))
        val notOrdinary = squash(declaration(code(src), "private val NOT_ORDINARY = listOf("))
        for (key in listOf("SYNC_EXTRAS_MANUAL", "SYNC_EXTRAS_EXPEDITED", "SYNC_EXTRAS_IGNORE_SETTINGS", "SYNC_EXTRAS_IGNORE_BACKOFF")) {
            assertTrue(key, notOrdinary.contains("ContentResolver.$key,"))
        }
        val st = squash(settings)
        assertTrue(st.contains("putBoolean(SyncConstants.EXTRA_SETTINGS_CHANGED, true)"))
        assertTrue(st.contains("putLong(SyncConstants.EXTRA_SETTINGS_CHANGE_ID, SystemClock.elapsedRealtimeNanos())"))
        // Held while a full re-sync of the account's calendar is prepared, before anything is asked.
        val held = st.indexOf("if (FullResyncHold.holdIfPreparing(account.name, change)) {")
        val asked = st.indexOf("requestBackgroundSync(")
        assertTrue(st, held >= 0 && asked > held)
        assertTrue(st, st.indexOf("return", held) in held until asked)
    }

    @Test
    fun `the Fallback full sync and the re-time request go through the background rule`() {
        for (path in listOf(WORKER, TZ_RULES)) {
            val src = source(path)
            assertFalse(path, src.contains("SYNC_EXTRAS_MANUAL"))
            assertFalse(path, src.contains("SYNC_EXTRAS_EXPEDITED"))
            assertTrue(path, src.contains("SyncScheduling.requestBackgroundSync("))
            assertFalse(path, src.contains("ContentResolver.requestSync("))
        }
        // The running sync's id on a re-time request: Android drops one equal to a running sync's, and merges the ones
        // made while it runs.
        val retime = squash(code(body(source(TZ_RULES), "fun requestRetimeSyncs(")))
        assertTrue(retime, retime.contains("val extras = SyncScheduling.putRunningSyncId(Bundle(), account, SyncConstants.AUTHORITY_CALENDAR)"))
        assertTrue(retime, retime.contains("context, account, SyncConstants.AUTHORITY_CALENDAR, account.name, extras,"))
        assertFalse(retime, retime.contains("elapsedRealtimeNanos"))
        // No caller hands the background rule a user's extras.
        val callers = kotlinSources().filter { it.name != "SyncScheduling.kt" && codeLines(it).any { l -> l.contains("requestBackgroundSync(") } }
        assertTrue(callers.map { it.name }.toString(), callers.map { it.name }.containsAll(listOf("SyncWorker.kt", "TimeZoneRuleSource.kt")))
        for (file in callers) {
            for (helper in listOf("userSyncExtras(", "forcedResyncExtras(")) {
                assertTrue("${file.name} calls requestBackgroundSync and builds $helper", codeLines(file).none { it.contains(helper) })
            }
        }
    }

    @Test
    fun `every sync the user starts takes its extras from SyncScheduling`() {
        val helper = Regex("""^SyncScheduling\.(userSyncExtras|forcedResyncExtras)\(""")
        // A function that only passes its caller's bundle on; its callers are checked like any other.
        val passesOn = mapOf("SyncSettingsActivity.kt" to "fun requestSyncIfEnabled(")
        var examined = 0
        for (file in kotlinSources().filter { it.name != "SyncScheduling.kt" }) {
            val lines = codeLines(file)
            for ((i, line) in lines.withIndex()) {
                val open = when {
                    line.contains("ContentResolver.requestSync(") -> "ContentResolver.requestSync("
                    line.contains("requestSyncIfEnabled(") && !line.contains("fun requestSyncIfEnabled(") -> "requestSyncIfEnabled("
                    else -> continue
                }
                examined++
                val args = callArguments(line, open) ?: failWith("${file.name}: write the call on one line: $line")
                val extras = args.getOrNull(2) ?: failWith("${file.name}: no extras in: $line")
                if (helper.containsMatchIn(extras)) continue
                assertTrue("${file.name}: $line", Regex("""^[A-Za-z_][A-Za-z0-9_]*$""").matches(extras))
                // The nearest definition above the call: a val built by the helpers, or a parameter of a pass-on function.
                val definition = (i - 1 downTo 0).map { lines[it] }.firstOrNull { it.contains("val $extras =") || it.contains("$extras: Bundle") }
                    ?: failWith("${file.name}: where does '$extras' come from? $line")
                if (definition.contains("$extras: Bundle")) {
                    assertTrue("${file.name}: $definition", passesOn[file.name]?.let { definition.contains(it) } == true)
                } else {
                    assertTrue("${file.name}: $definition", helper.containsMatchIn(definition.substringAfter("val $extras = ")))
                }
            }
        }
        // The buttons, the re-syncs, sign-in, provisioning, the receivers and the debug verbs: none was missed by the scan.
        assertTrue("only $examined calls examined", examined >= 15)
    }

    @Test
    fun `an answer in one of the app's notifications carries the running sync's id of the account it goes to, a sync button's none`() {
        for (path in ANSWER_RECEIVERS) {
            val lines = codeLines(File("src/$path"))
            val requests = lines.withIndex().filter { it.value.contains("ContentResolver.requestSync(") }
            assertTrue(path, requests.isNotEmpty())
            for ((i, line) in requests) {
                val args = callArguments(line, "ContentResolver.requestSync(") ?: failWith("$path: write the call on one line: $line")
                val definition = (i - 1 downTo 0).map { lines[it] }.firstOrNull { it.contains("val ${args[2]} =") }
                    ?: failWith("$path: where does '${args[2]}' come from? $line")
                // answering names the account the request goes to, so the id is read for the sync that may be running.
                val answering = Regex("""^SyncScheduling\.userSyncExtras\([^)]*answering = ([A-Za-z_][A-Za-z0-9_.]*)\)$""")
                    .find(definition.substringAfter("val ${args[2]} = "))?.groupValues?.get(1)
                assertEquals("$path: $definition", args[0], answering)
            }
        }
        // A double tap on a sync button stays one sync.
        val manual = squash(code(body(source(ACTIVITY), "private fun requestManualSync(")))
        assertTrue(manual, manual.contains("val bundle = SyncScheduling.userSyncExtras(this, account.name, userSyncNow = true)"))
        assertFalse(manual, manual.contains("answering"))
        // The id is put in one place only, read from the sync state; a new id per call would queue one sync per answer.
        assertEquals(listOf("SyncScheduling.kt"), filesContaining("putLong(SyncConstants.EXTRA_REQUEST_ID"))
        val put = squash(code(body(scheduling(), "internal fun putRunningSyncId(")))
        for (wired in listOf(
            "val active = ContentResolver.isSyncActive(account, authority)",
            "accountSyncActive = active,",
            "ContentResolver.getCurrentSyncs().filter { it.authority == authority }.map { it.startTime },",
            "newId = SystemClock.elapsedRealtimeNanos(),",
            "catch (e: Exception) { SystemClock.elapsedRealtimeNanos() }",
            "if (id != null) extras.putLong(SyncConstants.EXTRA_REQUEST_ID, id)",
        )) {
            assertTrue(put, put.contains(wired))
        }
        assertEquals(1, Regex(Regex.escape("putLong(SyncConstants.EXTRA_REQUEST_ID")).findAll(code(scheduling())).count())
    }

    @Test
    fun `an answer about a new event in a shared calendar asks for its sync in one place only`() {
        for (path in NEW_EVENT_HOLD_ENTRY_POINTS) {
            val code = code(source(path))
            assertTrue(path, code.contains("NewEventHoldActions.answer("))
            assertFalse(path, code.contains("requestSync("))
            assertFalse(path, code.contains("userSyncExtras("))
        }
        // The debug verbs run the same handler, and the activity asks for no sync about these answers itself.
        val activity = code(source(ACTIVITY))
        assertTrue(activity.contains("me.proton.sync.calendar.NewEventHoldActions.answer("))
        // Publish and Move ask; Keep never does: one request, built for the account it answers.
        val actions = code(source("main/java/me/proton/sync/calendar/NewEventHoldActions.kt"))
        assertEquals(1, Regex(Regex.escape("ContentResolver.requestSync(")).findAll(actions).count())
        val applied = actions.substringAfter("private fun applyChoice(")
        val keep = applied.substringAfter("Choice.KEEP ->").substringBefore("Choice.PUBLISH -> {")
        assertTrue(keep, keep.contains("State.KEPT") && keep.length < applied.length)
        assertFalse(keep, keep.contains("requestCalendarSync("))
        assertTrue(applied.substringAfter("Choice.PUBLISH -> {").substringBefore("Choice.MOVE ->").contains("requestCalendarSync("))
    }

    @Test
    fun `every answer checks the re-sync hold before it asks, and the screen asks for a held one after the re-sync's own`() {
        // A sync started inside a full re-sync's preparation would upload the rows it marks as ordinary changes: each
        // answer's calendar request returns before anything is asked while the account's calendar is prepared.
        val requests = listOf(
            Triple(ANSWER_RECEIVERS[3], "private fun requestCalendarSync(", "FullResyncHold.holdAnswerIfPreparing(account.name)"),
            Triple(ANSWER_RECEIVERS[2], "private fun requestCalendarSync(", "FullResyncHold.holdAnswerIfPreparing(account.name)"),
            Triple(ANSWER_RECEIVERS[1], "private fun requestSync(", "FullResyncHold.holdAnswerIfPreparing(accountName)"),
            Triple(ANSWER_RECEIVERS[0], "private fun requestSync(", "FullResyncHold.holdAnswerIfPreparing(accountName, userSyncNow = true)"),
        )
        for ((path, signature, hold) in requests) {
            val fn = squash(code(body(source(path), signature)))
            val held = fn.indexOf("if ($hold) {")
            val asked = fn.indexOf("ContentResolver.requestSync(account, SyncConstants.AUTHORITY_CALENDAR,")
            assertTrue("$path: $fn", held >= 0 && asked > held)
            assertTrue("$path: $fn", fn.indexOf("return", held) in held until asked)
        }
        // The new event's answers need no sweep of every calendar: the upload runs whether or not they are skipped.
        val answer = squash(code(body(source(ANSWER_RECEIVERS[3]), "private fun requestCalendarSync(")))
        assertTrue(answer, answer.contains("val extras = SyncScheduling.userSyncExtras(context, account.name, answering = account)"))
        assertFalse(answer, answer.contains("userSyncNow"))
        // The screen: the held answers' one sync after the settings one, both after the preparation's end.
        val activity = source(ACTIVITY)
        val end = squash(code(body(activity, "private fun markResyncEnd(")))
        val ended = end.indexOf("val held = FullResyncHold.end(accountName)")
        val taken = end.indexOf(
            "FullResyncHold.takeHeldAnswer(accountName)?.let { requestHeldAnswerSync(Account(accountName, ProtonSyncApp.ACCOUNT_TYPE), it) }"
        )
        assertTrue(end, ended >= 0 && taken > ended)
        val heldSync = squash(code(body(activity, "private fun requestHeldAnswerSync(")))
        assertTrue(
            heldSync,
            heldSync.contains("val bundle = SyncScheduling.userSyncExtras(applicationContext, account.name, userSyncNow = userSyncNow, answering = account)")
        )
        assertTrue(heldSync, heldSync.contains("requestSyncIfEnabled(account, SyncConstants.AUTHORITY_CALENDAR, bundle)"))
        // The card's waiting and kept rows refuse while a re-sync is prepared, as the sync buttons do.
        val screen = squash(code(activity))
        for (row in listOf("rowNewEventHolds", "rowNewEventKept")) {
            val listener = screen.substringAfter("cardBinding.$row.setOnClickListener {").substringBefore("newEventHoldDialog.show(")
            assertTrue(listener, listener.contains("if (account.name in fullResyncInFlightAccounts) {"))
            assertTrue(listener, listener.contains("getString(R.string.toast_resync_in_flight)"))
            assertTrue(listener, listener.contains("return@setOnClickListener"))
        }
    }

    @Test
    fun `the event loop is gone, and only the start-up clean-up still names it`() {
        assertFalse(File("src/main/java/me/proton/sync/EventLoopWorker.kt").exists())
        assertEquals(listOf("SyncScheduling.kt"), filesContaining("EventLoopWorker"))
        assertEquals(listOf("SyncScheduling.kt"), filesContaining("calendar_settings_dirty_"))
        assertEquals(listOf("SyncScheduling.kt"), filesContaining("event_loop_id_"))
        // Not left to forgetRemovedAccounts: the clean-up removes them for every name, also a removed account's.
        assertFalse(SyncPreferences.ACCOUNT_SCOPED_PREFIXES.contains("event_loop_id_"))
        assertFalse(SyncPreferences.ACCOUNT_SCOPED_PREFIXES.contains("calendar_settings_dirty_"))
        assertTrue(SyncPreferences.ACCOUNT_SCOPED_PREFIXES.contains("event_loop_interval_"))
        // Run at every start, off the main thread.
        val onCreate = source(APP).substringAfter("override fun onCreate() {").substringBefore("\n    }\n")
        val thread = code(onCreate.substringAfter("Thread {").substringBefore("}.start()"))
        assertTrue(thread.contains("SyncScheduling.removeEventLoopLeftovers(this)"))
    }

    @Test
    fun `the start-up clean-up asks for a pending setting's sync before it deletes the flag`() {
        val cleanup = squash(code(body(scheduling(), "fun removeEventLoopLeftovers(")))
        val pending = cleanup.indexOf("accountsWithPendingSettings(prefs.all, names)")
        val asked = cleanup.indexOf("requestSettingsSync(", pending)
        val retired = cleanup.indexOf("val retired = retiredKeys(prefs.all.keys)")
        val removed = cleanup.indexOf("edit.remove(key)")
        assertTrue(cleanup, pending >= 0 && asked > pending && retired > asked && removed > retired)
        assertTrue(cleanup, cleanup.contains("if (live != 0) wm.cancelAllWorkByTag(RETIRED_EVENT_LOOP_TAG)"))
    }

    @Test
    fun `a restore asks for the settings sync of the accounts already on this phone`() {
        val import = code(source(IMPORT))
        assertEquals(1, Regex(Regex.escape("if (settingsRestored) {")).findAll(import).count())
        val block = squash(import.substringAfter("if (settingsRestored) {").substringBefore("\n        }\n"))
        assertTrue(block, block.contains("SyncScheduling.reconcileAll(context)"))
        assertTrue(block, block.contains(".filter { it !in provisioned }"))
        val restored = block.indexOf("SyncScheduling.accountsWithRestoredCalendarSettings(payload.settings.keys, stayed)")
        val asked = block.indexOf("SyncScheduling.requestSettingsSync(context, Account(name, ProtonSyncApp.ACCOUNT_TYPE)")
        assertTrue(block, restored >= 0 && asked > restored)
    }

    @Test
    fun `a calendar setting asks for its sync through one handler, held while a full re-sync is prepared`() {
        val activity = source(ACTIVITY)
        val trimSaved = code(body(activity, "private fun afterTrimSaved("))
        assertTrue(trimSaved.contains("onCalendarSettingStored("))
        assertFalse(trimSaved.contains("requestManualSync("))
        assertTrue(code(body(activity, "private fun onCalendarSettingStored(")).contains("SyncScheduling.requestSettingsSync(applicationContext, account, change)"))
        // The hold is FullResyncHold's, process-wide: the screen keeps no set of its own. It holds the narrowed section's
        // types only, so a contacts re-sync leaves the calendar's re-time and settings syncs alone.
        assertTrue(squash(code(body(activity, "private fun markResyncStart("))).contains("FullResyncHold.start(accountName, section)"))
        for (resync in listOf("private fun performFullResyncRemoteToLocal(", "private fun performFullResyncLocalToRemote(")) {
            val prep = squash(code(body(activity, resync)))
            val narrowed = prep.indexOf("val section = sectionsStillEnabled(account, abAccount, requested)")
            assertTrue(prep, narrowed >= 0 && prep.indexOf("markResyncStart(account.name, section)") > narrowed)
        }
        val end = squash(code(body(activity, "private fun markResyncEnd(")))
        val ended = end.indexOf("val held = FullResyncHold.end(accountName)")
        assertTrue(end, ended >= 0 && end.indexOf("if (held != null) SyncScheduling.requestSettingsSync(", ended) > ended)
        val all = squash(code(activity))
        assertTrue(all.contains("private val fullResyncInFlightAccounts: Set<String> get() = FullResyncHold.preparing()"))
        assertFalse(all.contains("settingsChangedDuringResync"))
        // A screen built while another one's preparation runs re-checks it until it ends.
        assertTrue(squash(code(body(activity, "private fun refreshSyncIndicators("))).contains("if (anyActive || anyPending || anyResyncInFlight) {"))
        assertTrue(code(body(activity, "private fun setIncludeExternalCalendars(")).contains("onCalendarSettingStored(account, \"external calendars\")"))
        // A narrowed range's sync waits for the "Remove older events" answer, or runs at once when no dialog shows.
        val range = squash(code(body(activity, "private fun afterRangeStored(")))
        assertTrue(range, range.contains("onNotShown = { onCalendarSettingStored(account, \"range\") }"))
        assertTrue(range, range.contains("afterTrimSaved(account, newlyOn, offered = true)"))
        // The dialog itself asks for no sync, and every way out without it reaches onNotShown.
        val dialog = code(source(DIALOG))
        assertFalse(dialog.contains("requestSync"))
        assertTrue(Regex("""\}\s*finally\s*\{\s*if \(!shown\) \{?\s*onNotShown\(\)\s*\}?\s*\}""").containsMatchIn(dialog))
        val presented = squash(dialog).indexOf("val dialog = present(")
        assertEquals(1, Regex(Regex.escape("shown = true")).findAll(dialog).count())
        assertTrue(presented >= 0 && squash(dialog).indexOf("shown = true") > presented)
    }

    // ---- Runs Android starts by itself, on Manual only (owner, 2026-11-22) ----

    private fun unasked(
        manual: Boolean = false,
        upload: Boolean = false,
        appRequest: Boolean = false,
        manualOnly: Boolean = true,
    ) = SyncScheduling.unaskedRun(manual, upload, appRequest, manualOnly)

    @Test
    fun `on Manual only a sync Android started by itself ends at its start, and nothing else does`() {
        // Android's own run, with an empty bundle, once its automatic sync or a type's switch is turned on.
        assertTrue("Manual only: ended", unasked())
        assertFalse("an interval: it runs as before", unasked(manualOnly = false))
        // Android's upload after an edit on the phone, which the owner accepted.
        assertFalse("the upload after an edit", unasked(upload = true))
        // Every request of the app: the Fallback full sync (it runs on Manual only), the catch-up, a settings change.
        assertFalse("the app's own request", unasked(appRequest = true))
        // A manual run is the stale-run rule's: a sync button, a notification's answer, Android's own "Sync now".
        assertFalse("a manual run", unasked(manual = true))
        // Only that one combination ends.
        for (bits in 0 until 16) {
            val ends = SyncScheduling.unaskedRun(
                manual = bits and 1 != 0,
                upload = bits and 2 != 0,
                appRequest = bits and 4 != 0,
                manualOnly = bits and 8 != 0,
            )
            assertEquals("combination $bits", bits == 8, ends)
        }
        // And the stale-run rule still leaves every ordinary run alone, so the two never decide the same run.
        assertEquals(StaleRun.NONE, stale(manual = false, manualOnly = true))
    }

    @Test
    fun `every request the app makes carries the app's marker, so none of its own runs is taken for Android's`() {
        val src = code(scheduling())
        // Put in SyncScheduling only, in the three places every request is built.
        assertEquals(listOf("SyncScheduling.kt"), filesContaining("putBoolean(SyncConstants.EXTRA_APP_REQUEST"))
        assertEquals(3, Regex(Regex.escape("putBoolean(SyncConstants.EXTRA_APP_REQUEST, true)")).findAll(src).count())
        // The syncs the user starts, the full re-syncs and the catch-up's manual first sync: manualExtras, which
        // userSyncExtras and forcedResyncExtras build on.
        val manual = squash(code(body(scheduling(), "private fun manualExtras(")))
        assertTrue(manual, manual.contains("for (flag in manualExtraFlags(retries)) putBoolean(flag, true) putBoolean(SyncConstants.EXTRA_APP_REQUEST, true)"))
        assertTrue(squash(code(body(scheduling(), "internal fun userSyncExtras("))).contains("return manualExtras(retries).apply {"))
        assertTrue(Regex("""fun forcedResyncExtras\(flag: String\)[^{=]*(=|\{)[^}]*manualExtras\(retries = true\)""").containsMatchIn(squash(src)))
        // Every background request (a settings change, the Fallback full sync, a re-time) on its way out.
        val bg = squash(code(body(scheduling(), "internal fun requestBackgroundSync(")))
        val marked = bg.indexOf("extras.putBoolean(SyncConstants.EXTRA_APP_REQUEST, true)")
        assertTrue(bg, marked > bg.indexOf("for (key in NOT_ORDINARY) extras.remove(key)"))
        assertTrue(bg, marked < bg.indexOf("ContentResolver.requestSync(account, authority, extras)"))
        // The catch-up, both kinds.
        val catchUp = squash(code(body(scheduling(), "private fun catchUpExtras(")))
        assertTrue(catchUp, catchUp.contains("putBoolean(SyncConstants.EXTRA_CATCH_UP, true) putBoolean(SyncConstants.EXTRA_APP_REQUEST, true)"))
        // SyncScheduling asks Android in those two places only. Every request made anywhere else takes userSyncExtras or
        // forcedResyncExtras ("every sync the user starts takes its extras from SyncScheduling", above).
        assertEquals(2, src.lines().count { it.contains("ContentResolver.requestSync(") })
        assertTrue(squash(code(body(scheduling(), "fun requestCatchUpSyncs("))).contains("ContentResolver.requestSync( account, authority, catchUpExtras("))
        assertEquals(emptyList<String>(), filesContaining("SyncRequest.Builder"))
        assertEquals(emptyList<String>(), filesContaining("requestSyncAsUser("))
        // Android's periodic syncs carry none (Bundle.EMPTY, from one writer), and on Manual only there is none to carry it.
        assertEquals(listOf("SyncUtils.kt"), filesContaining("addPeriodicSync("))
        assertFalse(SyncUtils.periodicSyncWanted(enabled = true, seconds = -1L, syncAllowed = true))
    }

    @Test
    fun `both adapters end a sync Android started by itself on Manual only, after the stale-run check and before the ongoing notification`() {
        for ((path, label) in listOf(CALENDAR_ADAPTER to "Calendar", CONTACTS_ADAPTER to "Contacts")) {
            val perform = code(body(source(path), "override fun onPerformSync("))
            val stale = perform.indexOf("SyncScheduling.endsStaleManualRun(")
            val check = perform.indexOf("SyncScheduling.endsUnaskedRun(")
            assertTrue("$path: after the stale-run check", stale >= 0 && check > stale)
            assertTrue("$path: before the ongoing notification", check < perform.indexOf("SyncNotificationHelper.showOngoing("))
            // For the account whose interval applies, as the stale-run check has it: an address book's parent.
            val name = if (label == "Calendar") "account.name" else "parentForNotif"
            assertTrue(path, perform.contains("SyncScheduling.endsStaleManualRun(context, $name, extras, \"$label\", TAG)) return"))
            assertTrue(path, perform.contains("SyncScheduling.endsUnaskedRun(context, $name, extras, \"$label\", TAG)) return"))
        }
        // The check reads the flags it decides by, leaves a manual run to the stale-run rule, logs what it ends as the
        // stale-run lines do, and lets the run go on when anything cannot be read.
        val ends = squash(code(body(scheduling(), "fun endsUnaskedRun(")))
        for (wired in listOf(
            "if (extras.getBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, false)) return false",
            "manual = false,",
            "upload = extras.getBoolean(ContentResolver.SYNC_EXTRAS_UPLOAD, false),",
            "appRequest = extras.getBoolean(SyncConstants.EXTRA_APP_REQUEST, false),",
            "manualOnly = isManualOnly(context, parentAccountName),",
            "if (ends) {",
            "sync for \$parentAccountName not run: ",
            "ended without a result, so Android does not retry it",
        )) {
            assertTrue(wired, ends.contains(wired))
        }
        assertTrue(ends, ends.substringAfter("} catch (e: Exception) {").contains("false"))
    }
}

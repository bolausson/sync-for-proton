package me.proton.sync.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * How the hold of new events in shared calendars is wired into the sync and the provider, read from the app's own
 * sources as the public snapshot exports them: the parts no JVM test can run. The order inside readDirtyRows is what
 * makes an answer safe against a sync that runs meanwhile; the plain provider update is what keeps a recurring row's
 * end; the manifest's queries are what lets the app see the calendar editors and nothing else.
 */
class NewEventHoldWiringTest {

    private fun source(path: String) = File("src/main/$path").readText().replace("\r\n", "\n")

    /** [text] without KDoc, block comment lines and line comments. */
    private fun code(text: String): String =
        text.lines().filterNot { it.trim().let { t -> t.startsWith("*") || t.startsWith("/*") || t.startsWith("//") } }
            .joinToString("\n") { it.substringBefore(" // ") }

    private fun squash(text: String) = text.replace(Regex("\\s+"), " ")

    private fun body(src: String, signature: String): String {
        assertTrue("$signature not found", src.contains(signature))
        return src.substringAfter(signature).substringBefore("\n    }\n")
    }

    private val adapter by lazy { source("java/me/proton/sync/calendar/CalendarSyncAdapter.kt") }

    @Test
    fun `the upload takes its snapshot of the holds before it reads the rows, and holds before it counts them`() {
        val read = squash(code(body(adapter, "private suspend fun readDirtyRows(")))
        val snapshot = read.indexOf("val holdSnapshot = NewEventHoldStore.snapshot(")
        val query = read.indexOf("queryDirtyEvents(provider, calIds)")
        val collisions = read.indexOf("EventConflictGuard.heldIds(context, account.name)")
        val hold = read.indexOf("holdNewEventsFromOtherApps(account, provider, dirtyRows, calendars, holdSnapshot)")
        val found = read.indexOf("Found \${dirtyRows.size} dirty/deleted events to process")
        assertTrue(read, snapshot >= 0 && query > snapshot && collisions > query && hold > collisions && found > hold)
        // Read by name, never by position.
        assertTrue(read, read.contains("DirtyRowReader.columns(cursor.columnNames)"))
        assertFalse(read, read.contains("cursor.getLong(0)"))
    }

    @Test
    fun `the sync sweeps the holds before the calendar list, and gives the upload this run's list`() {
        val perform = squash(code(body(adapter, "private suspend fun performCalendarSync(")))
        val sweep = perform.indexOf("sweepNewEventHolds(account, provider)")
        assertTrue(perform, sweep >= 0 && sweep < perform.indexOf("val calendars = fetchCalendarList("))
        assertTrue(perform, perform.contains("uploadDirtyEvents(account, provider, syncResult, triggerType, calendars)"))
    }

    @Test
    fun `Move is one plain provider batch, never the sync adapter's`() {
        val actions = code(source("java/me/proton/sync/calendar/NewEventHoldActions.kt"))
        val move = squash(actions.substringAfter("private fun move(").substringBefore("private fun seriesRows("))
        assertTrue(move, move.contains("ContentProviderOperation.newUpdate(CalendarContract.Events.CONTENT_URI)"))
        assertTrue(move, move.contains("client.applyBatch(ops)"))
        assertFalse(actions, actions.contains("asCallerSyncAdapter"))
        // The entry goes only once the provider has the move.
        assertTrue(move, move.indexOf("client.applyBatch(ops)") < move.indexOf("NewEventHoldStore.transition(prefs, account.name, entry, null, commit = true)"))
    }

    @Test
    fun `a calendar removed on Proton loses only Proton's rows while rows of its own remain`() {
        val removed = squash(code(body(adapter, "private suspend fun removeCalendarsGoneFromServer(")))
        val leftovers = removed.indexOf("deleteLeftoverProtonRows(provider, account, androidCalId)")
        val counted = removed.indexOf("val phoneOnly = phoneOnlyRowCount(provider, account, androidCalId)")
        val kept = removed.indexOf("val keepRow = PhoneOnlyRows.keepRemovedCalendarRow(phoneOnly)")
        val guarded = removed.indexOf("if (!keepRow) {")
        val deleted = removed.indexOf("SyncUtils.asCallerSyncAdapter(CalendarContract.Calendars.CONTENT_URI, account)")
        assertTrue(removed, leftovers >= 0 && counted > leftovers && kept > counted && guarded > kept && deleted > guarded)
        // The restore deletes by the same rule, never a whole calendar.
        val restore = squash(code(body(adapter, "private suspend fun applyBulkDeletionRestore(")))
        assertTrue(restore, restore.contains("deleteProtonRowsOfCalendar(provider, account, androidCalId, mappedIds)"))
        assertFalse(restore, restore.contains("\"\${CalendarContract.Events.CALENDAR_ID} = ?\","))
    }

    private val actions by lazy { code(source("java/me/proton/sync/calendar/NewEventHoldActions.kt")) }
    private val activity by lazy { source("java/me/proton/sync/settings/SyncSettingsActivity.kt") }

    @Test
    fun `the prompt's buttons ask for the unlock, its intents are immutable, and a locked screen shows the title only`() {
        val prompt = squash(code(body(source("java/me/proton/sync/SyncNotificationHelper.kt"), "internal fun showNewEventHoldPrompt(")))
        val intents = Regex("""PendingIntent\.get(Broadcast|Activity|Service|ForegroundService)\(""").findAll(prompt).count()
        assertEquals(prompt, 2, intents)
        assertEquals(prompt, intents, Regex(Regex.escape("PendingIntent.FLAG_IMMUTABLE")).findAll(prompt).count())
        // Every action through the one builder that asks for the unlock.
        assertEquals(prompt, 1, Regex(Regex.escape("NotificationCompat.Action.Builder(")).findAll(prompt).count())
        assertTrue(prompt, prompt.contains("NotificationCompat.Action.Builder(icon, label, pending).setAuthenticationRequired(true).build()"))
        assertEquals(prompt, 3, Regex(Regex.escape("builder.addAction(button(")).findAll(prompt).count())
        assertEquals(prompt, 3, Regex(Regex.escape("addAction(")).findAll(prompt).count())
        assertTrue(prompt, prompt.contains(".setVisibility(NotificationCompat.VISIBILITY_PRIVATE)"))
        assertTrue(prompt, prompt.contains(".setPublicVersion(publicVersion)"))
        val publicVersion = prompt.substringAfter("val publicVersion = ").substringBefore(".build()")
        assertFalse(publicVersion, publicVersion.contains("setContentText") || publicVersion.contains("body"))
        // Publish only where Android asks for the unlock first; the Move target is part of the intent's identity.
        assertTrue(prompt, prompt.contains("if (prompt.canPublish && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)"))
        assertTrue(prompt, prompt.contains("identifier = \"\${entry.eventId}:\${target?.calendarId ?: -1L}\""))
    }

    @Test
    fun `below Android 12 the prompt offers Keep only, and Move and Publish are left to the dialog`() {
        // Android asks for the unlock before an action only from 12 on: below it a button runs from the lock screen, and
        // Move asks for the user's own sync, which with "Send invitations" on emails the guests another app wrote.
        val prompt = squash(code(body(source("java/me/proton/sync/SyncNotificationHelper.kt"), "internal fun showNewEventHoldPrompt(")))
        val move = prompt.indexOf(
            "if (target != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { builder.addAction(button( android.R.drawable.ic_menu_revert,"
        )
        val keep = prompt.indexOf("builder.addAction(button( android.R.drawable.ic_menu_save,")
        val publish = prompt.indexOf(
            "if (prompt.canPublish && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { builder.addAction(button( android.R.drawable.ic_menu_upload,"
        )
        assertTrue(prompt, move >= 0 && keep > move && publish > keep)
        // Keep is the one action outside a version check: Move's block closes right before it.
        assertTrue(prompt, prompt.substring(move, keep).trimEnd().endsWith("}"))
        assertEquals(prompt, 2, Regex(Regex.escape("Build.VERSION.SDK_INT >= Build.VERSION_CODES.S")).findAll(prompt).count())
        // The dialog, which the phone has to be unlocked to open, offers Move and Publish on every version.
        val dialog = code(source("java/me/proton/sync/settings/NewEventHoldDialog.kt"))
        assertFalse(dialog, dialog.contains("SDK_INT"))
    }

    @Test
    fun `Move refuses a series whose exception sits in any calendar but the target`() {
        // An app that moved only the parent leaves its exceptions where they were, possibly in a third calendar, where the
        // next upload would send the series a second time; the closing assert counts every live exception of a moved row
        // outside the target, in the one transaction, so such a Move writes nothing and fails.
        val move = squash(actions.substringAfter("private fun move(").substringBefore("private fun gone("))
        assertTrue(move, move.contains("\"${"$"}{CalendarContract.Events.CALENDAR_ID} != ? AND ${"$"}{CalendarContract.Events.DELETED} = 0 AND \""))
        assertTrue(move, move.contains("(listOf(target.calendarId.toString()) + moved.map { it.toString() } + seen.map { it.toString() }).toTypedArray()"))
        assertFalse(move, move.contains("(listOf(from) + moved.map"))
    }

    @Test
    fun `a row an earlier version kept for a removed calendar is marked before the rows are listed by name`() {
        val perform = squash(code(body(adapter, "private suspend fun performCalendarSync(")))
        val mappings = perform.indexOf("val existingCalMappings = calendarSyncDao.getAllByAccount(account.name)")
        val marked = perform.indexOf("markCalendarsKeptByOlderVersions(account, provider, existingCalMappings, allServerCalIds)")
        val listed = perform.indexOf("val existingLocalCalendars = readAndDedupeLocalCalendars(")
        assertTrue(perform, mappings >= 0 && marked > mappings && listed > marked)
        val pass = squash(code(body(adapter, "private suspend fun markCalendarsKeptByOlderVersions(")))
        val eventCalendars = pass.indexOf("val eventCalendarIds = calendarEventSyncDao.mappedCalendarIdsByAccount(account.name).toSet()")
        val removed = pass.indexOf("val removed = CalendarRowClaims.removedCalendarsWithEvents(eventCalendarIds, existingCalMappings.keys, listedIds)")
        assertTrue(pass, eventCalendars >= 0 && removed > eventCalendars)
        // A calendar whose row carries its marker already costs no more reads.
        assertTrue(pass, pass.contains("val unmarked = removed - marked.values.toSet()"))
        val rows = squash(code(body(adapter, "private fun markRowsKeptByOlderVersions(")))
        assertTrue(rows, rows.contains("SyncUtils.asCallerSyncAdapter(CalendarContract.Events.CONTENT_URI, account)"))
        assertTrue(rows, rows.contains("for ((row, protonId) in CalendarRowClaims.unmarkedKeptRows(rowsOfRemoved, mappedRows, markedRows)) {"))
        assertTrue(rows, rows.contains("if (!markKeptCalendarRow(provider, account, row, protonId)) continue"))
    }

    @Test
    fun `the marking leaves alone a row a listed calendar uses, also in a Local to Remote run, and a stale marker lists no mapped row as kept`() {
        // A Local to Remote re-sync deletes the calendar mappings and keeps the events': in its run the rows the listed
        // calendars use are known by where their mapped events sit, read with the removed calendars' ones, before anything
        // is marked.
        val pass = squash(code(body(adapter, "private suspend fun markCalendarsKeptByOlderVersions(")))
        val unmarked = pass.indexOf("val unmarked = removed - marked.values.toSet()")
        val listed = pass.indexOf("val eventsOfListed = eventsOfListedWithoutMapping(account, existingCalMappings, listedIds)")
        val marking = pass.indexOf("markRowsKeptByOlderVersions(account, provider, eventsOfRemoved, eventsOfListed, existingCalMappings, marked.keys)")
        assertTrue(pass, unmarked >= 0 && listed > unmarked && marking > listed)
        val events = squash(code(body(adapter, "private suspend fun eventsOfListedWithoutMapping(")))
        assertTrue(
            events,
            events.contains(
                "CalendarRowClaims.listedWithoutMapping( calendarEventSyncDao.mappedCalendarIdsByAccount(account.name), " +
                    "existingCalMappings.keys, listedIds )"
            )
        )
        val rows = squash(code(body(adapter, "private fun markRowsKeptByOlderVersions(")))
        val read = rows.indexOf("rowsOf(eventsOfListed)")
        val inUse = rows.indexOf("val mappedRows = CalendarRowClaims.rowsInUse(existingCalMappings.values, rowsOfListed)")
        val mark = rows.indexOf("for ((row, protonId) in CalendarRowClaims.unmarkedKeptRows(rowsOfRemoved, mappedRows, markedRows)) {")
        assertTrue(rows, read >= 0 && inUse > read && mark > inUse)
        // A marked row a listed calendar maps stays under its name (a version up to 2.1.0, installed over this one, reused
        // it by name), so the removed calendar, shared again, never takes it as well; unless events kept for that calendar
        // still have their mappings (CalendarRowClaimsTest has both).
        val listing = squash(code(body(adapter, "private fun readAndDedupeLocalCalendars(")))
        val guard = listing.indexOf("if (!CalendarRowClaims.listedAsKept(id, marker, existingCalMappings.values, listedIds, eventCalendarIds)) continue")
        val moved = listing.indexOf("existingLocalCalendars.getOrPut(CalendarRowClaims.listingKey(name, marker)) { mutableListOf() }.add(id)")
        assertTrue(listing, guard >= 0 && moved > guard)
        val perform = squash(code(body(adapter, "private suspend fun performCalendarSync(")))
        assertTrue(
            perform,
            perform.contains("existingCalMappings = existingCalMappings, listedIds = allServerCalIds, eventCalendarIds = eventCalendarIds )")
        )
    }

    @Test
    fun `the listing learns which removed calendars still have kept events from the marking, with no read of its own`() {
        // The marking reads the calendars the event mappings name first and hands them back on every path, so a row
        // whose removed calendar still has kept events goes back to it (CalendarRowClaims.listedAsKept) at no extra read.
        val pass = squash(code(body(adapter, "private suspend fun markCalendarsKeptByOlderVersions(")))
        assertTrue(pass, pass.contains("listedIds: Set<String> ): Set<String> { val eventCalendarIds = calendarEventSyncDao.mappedCalendarIdsByAccount(account.name).toSet()"))
        assertEquals(pass, 3, Regex(Regex.escape("return eventCalendarIds")).findAll(pass).count())
        assertTrue(pass, pass.contains("if (removed.isEmpty()) return eventCalendarIds"))
        assertTrue(pass, pass.contains("if (unmarked.isEmpty()) return eventCalendarIds"))
        assertTrue(pass, pass.trimEnd().endsWith("markRowsKeptByOlderVersions(account, provider, eventsOfRemoved, eventsOfListed, existingCalMappings, marked.keys) return eventCalendarIds"))
        val perform = squash(code(body(adapter, "private suspend fun performCalendarSync(")))
        val marked = perform.indexOf("val eventCalendarIds = markCalendarsKeptByOlderVersions(account, provider, existingCalMappings, allServerCalIds)")
        val listed = perform.indexOf("val existingLocalCalendars = readAndDedupeLocalCalendars(")
        assertTrue(perform, marked >= 0 && listed > marked)
        // Used by the listing alone, right after: nothing later in the run keeps it (the val, and the listing's argument).
        assertEquals(perform, 3, Regex("\\beventCalendarIds\\b").findAll(perform).count())
        assertTrue(perform, perform.contains("eventCalendarIds = eventCalendarIds )"))
    }

    @Test
    fun `the log lines 2_2_0 added name calendars and writing apps only through LogNames`() {
        // The owner's rule (2026-11-21): calendar names and the apps that wrote events only while Verbose logging is on.
        val quoted = Regex("""'\$\{(?!account\.name\}|debug\.namePart\})[^}]*\}'""")
        val checked = mapOf(
            "applyNewEventHolds" to body(adapter, "private fun applyNewEventHolds("),
            "writeSettled" to body(adapter, "private fun writeSettled("),
            "showNewEventHoldPrompt" to body(source("java/me/proton/sync/SyncNotificationHelper.kt"), "internal fun showNewEventHoldPrompt("),
            "NewEventHoldActions" to source("java/me/proton/sync/calendar/NewEventHoldActions.kt"),
            "DefaultCalendarService" to source("java/me/proton/sync/calendar/DefaultCalendarService.kt"),
            "DefaultCalendarPicker" to source("java/me/proton/sync/settings/DefaultCalendarPicker.kt"),
        )
        for ((what, text) in checked) {
            assertEquals(what, emptyList<String>(), quoted.findAll(code(text)).map { it.value }.toList())
        }
        val hold = squash(code(body(adapter, "private fun applyNewEventHolds(")))
        assertTrue(hold, hold.contains("(me.proton.sync.LogNames.app(e.writer)?.let { \"written by \$it\" } ?: \"no writer recorded\")"))
        assertFalse(hold, hold.contains("e.writer?.let"))
        // The upload's first line takes the default, which follows the switch (LocalEventOriginTest has both wordings).
        val upload = squash(code(body(adapter, "private suspend fun uploadModifiedRow(")))
        assertTrue(upload, upload.contains("LocalEventOrigin.uploadStartLine(row.androidEventId, row.mutators, uploadContext.displayName) {"))
        val origin = squash(code(source("java/me/proton/sync/calendar/LocalEventOrigin.kt")))
        assertTrue(origin, origin.contains("named: Boolean = LogNames.shown(),"))
        assertTrue(origin, origin.contains("named: Boolean = LogNames.shown()): String {"))
    }

    @Test
    fun `the screen opened from a prompt only shows its dialog, never presses for it, and shows it again once re-created`() {
        val handle = squash(code(body(activity, "private fun handleNewEventHoldIntent(")))
        val removedAccount = handle.indexOf("intent.removeExtra(me.proton.sync.calendar.NewEventHolds.EXTRA_ACCOUNT)")
        val removedEvent = handle.indexOf("intent.removeExtra(me.proton.sync.calendar.NewEventHolds.EXTRA_EVENT)")
        val shown = handle.indexOf("newEventHoldDialog.show(account, eventId)")
        assertTrue(handle, removedAccount >= 0 && removedEvent >= 0 && shown > removedAccount && shown > removedEvent)
        assertTrue(handle, handle.contains("if (restored || fromHistory || eventId <= 0) return"))
        assertFalse(handle, handle.contains("press"))
        val save = squash(code(body(activity, "override fun onSaveInstanceState(")))
        assertTrue(save, save.contains("outState.putBoolean(KEY_OPENED_FOR_NEW_EVENT_HOLD, openedForNewEventHold)"))
        assertTrue(save, save.contains("newEventHoldDialog.saveState(outState)"))
        val create = squash(code(body(activity, "override fun onCreate(")))
        assertTrue(create, create.contains("openedForNewEventHold = savedInstanceState?.getBoolean(KEY_OPENED_FOR_NEW_EVENT_HOLD, false) == true"))
        val restored = create.indexOf("if (newEventHoldDialog.restoreState(savedInstanceState, accounts) { refreshNewEventHolds(it) }) openedForNewEventHold = true")
        val battery = create.indexOf("batteryDialogShown = if (openedForNewEventHold) false else requestBatteryOptimizationIfNeeded()")
        assertTrue(create, restored >= 0 && battery > restored)
        val dialog = code(source("java/me/proton/sync/settings/NewEventHoldDialog.kt"))
        val restore = squash(dialog.substringAfter("fun restoreState(").substringBefore("\n    }\n"))
        assertFalse(restore, restore.contains("press"))
    }

    @Test
    fun `every answer names the state and the Move target its button was drawn for`() {
        val receiver = squash(code(source("java/me/proton/sync/calendar/NewEventHoldReceiver.kt")))
        assertTrue(receiver, receiver.contains("val target = intent.getLongExtra(EXTRA_TARGET_CALENDAR_ID, -1L).takeIf { it >= 0 }"))
        assertTrue(receiver, receiver.contains("eventId, choice, expectedTargetCalendarId = target, origin = \"notification\", expectedState = NewEventHoldRules.State.WAITING"))
        val dialog = squash(code(source("java/me/proton/sync/settings/NewEventHoldDialog.kt")))
        assertTrue(dialog, dialog.contains("answer(account, entry.eventId, entry.state, choice, target?.calendarId, single, walk, requestSync, onAnswered)"))
        assertTrue(dialog, dialog.contains("expectedTargetCalendarId = if (choice == Choice.MOVE) shownTarget else null"))
        assertTrue(dialog, dialog.contains("expectedState = shownState"))
        val decide = squash(actions.substringAfter("private suspend fun decide(").substringBefore("private fun applyChoice("))
        assertTrue(decide, decide.contains("if (expectedState != null && entry.state != expectedState) {"))
        // A stale Move label is refused before anything is read or written.
        val move = squash(actions.substringAfter("private fun move(").substringBefore("private fun gone("))
        val compared = move.indexOf("if (expectedTarget != null && expectedTarget != target.calendarId) {")
        val read = move.indexOf("seriesRows(client, entry, mappedEventIds)")
        assertTrue(move, compared >= 0 && read > compared)
        // The debug verb can carry a Move target as the notification does.
        assertTrue(activity, squash(code(activity)).contains("expectedTargetCalendarId = expectedTarget, origin = \"debug\", requestSync = !nosync"))
    }

    @Test
    fun `answers are committed, whose calendar it is comes from the account's addresses, and nosync skips only the request`() {
        val applied = squash(actions.substringAfter("private fun applyChoice(").substringBefore("private fun move("))
        val keep = applied.substringAfter("Choice.KEEP ->").substringBefore("Choice.PUBLISH -> {")
        assertTrue(keep, keep.contains("entry.copy(state = State.KEPT), commit = true)"))
        val publish = applied.substringAfter("Choice.PUBLISH -> {").substringBefore("Choice.MOVE ->")
        assertTrue(publish, publish.contains("entry.copy(state = State.PUBLISHED), commit = true)"))
        val hold = squash(code(body(adapter, "private fun applyNewEventHolds(")))
        assertTrue(hold, hold.contains("val userAddresses = keyManager.getAddressEmails(account.name)"))
        assertTrue(hold, hold.contains("NewEventHoldRules.holdDecision(info.type, info.members, userAddresses)"))
        assertFalse(hold, hold.contains("setOf(account.name)"))
        // Undecided calendars ask for the keys again. An entry whose parent's tombstone goes up passes its question on to
        // a row of the series left with its UID, as the sweep does, never a plain drop; its prompt comes down either way.
        assertTrue(hold, hold.contains("undecidedInfo[calId]?.let { reloadKeysForUnknownMembership(account, it) }"))
        assertTrue(hold, hold.contains("for (e in plan.dropEntries) NewEventHolds.passOnOrDrop(context, provider, prefs, account, e, row = null, mappedEventIds = mappedIds)"))
        assertFalse(hold, hold.contains("NewEventHoldStore.drop("))
        assertTrue(hold, hold.contains("plan.apart.mapTo(out) { it.id }"))
        val owner = squash(code(body(adapter, "private fun ownMemberIdForWrites(")))
        assertTrue(owner, owner.contains("reloadKeysForUnknownMembership(account, calInfo)"))
        // The debug verbs skip the sync request and nothing else.
        val debug = squash(code(activity))
        assertEquals(2, Regex(Regex.escape("requestSync = !nosync")).findAll(debug).count())
    }

    @Test
    fun `a calendar kept on the phone goes back only to its own Proton calendar`() {
        val removed = squash(code(body(adapter, "private suspend fun removeCalendarsGoneFromServer(")))
        val marks = Regex(Regex.escape("markKeptCalendarRow(provider, account, androidCalId, protonId)")).findAll(removed).map { it.range.first }.toList()
        val drops = Regex(Regex.escape("calendarSyncDao.deleteByProtonId(account.name, protonId)")).findAll(removed).map { it.range.first }.toList()
        // Both branches that keep the row mark it before its mapping goes; the branch whose row a live calendar uses does not.
        assertEquals(removed, 2, marks.size)
        assertEquals(removed, 3, drops.size)
        assertTrue(removed, marks[0] in drops[0]..drops[1] && marks[1] in drops[1]..drops[2])
        // Listed under that calendar's own key, never its name, so the reuse by name cannot find it for another one.
        val read = squash(code(body(adapter, "private fun readAndDedupeLocalCalendars(")))
        // After the duplicate clean-up by name, which still sees it (an empty one goes as before).
        val dedupe = read.indexOf("ids.removeAll(toDelete.toSet())")
        val moved = read.indexOf("existingLocalCalendars.getOrPut(CalendarRowClaims.listingKey(name, marker)) { mutableListOf() }.add(id)")
        assertTrue(read, dedupe >= 0 && moved > dedupe)
        assertTrue(read, read.contains("if (existingLocalCalendars[name]?.remove(id) != true) continue"))
        assertTrue(squash(code(adapter)).contains("provider.query(CalendarContract.Calendars.CONTENT_URI, columns + CalendarContract.Calendars.CAL_SYNC1, selection, args, null)"))
        val job = squash(code(body(adapter, "private suspend fun prepareCalendarJob(")))
        val byName = job.indexOf("val orphanedCalId = CalendarRowClaims.reusableRow(sameNameRows, calInfo.id, existingCalMappings.values, allServerCalIds)")
        val own = job.indexOf("val keptRow = existingLocalCalendars[keptKey]?.firstOrNull()")
        assertTrue(job, byName >= 0 && own > byName)
        assertTrue(job, job.contains("val reusedCalId = keptRow ?: orphanedCalId"))
        assertTrue(job, job.contains("clearKeptMarker = reusedCalId == keptRow"))
        assertTrue(job, job.contains("existingLocalCalendars[keptKey]?.remove(reusedCalId)"))
        assertTrue(job, job.contains("if (supersededMappings.isNotEmpty()) unpublishTakenOverRow(account, androidCalId)"))
    }

    @Test
    fun `a kept calendar goes once nothing keeps it, and what was published to it waits again`() {
        val removed = squash(code(body(adapter, "private suspend fun removeCalendarsGoneFromServer(")))
        // Both branches that keep a removed calendar send what the user published to it back to waiting, after the mark.
        val marks = Regex(Regex.escape("markKeptCalendarRow(provider, account, androidCalId, protonId)")).findAll(removed).map { it.range.first }.toList()
        val waits = Regex(Regex.escape("waitingAgain.addAll(unpublishUnmappedRow(account, androidCalId, ")).findAll(removed).map { it.range.first }.toList()
        assertEquals(removed, 2, waits.size)
        assertTrue(removed, marks.size == 2 && marks[0] < waits[0] && waits[0] < marks[1] && marks[1] < waits[1])
        // Then, after the loop, the kept rows are settled and those prompts posted, the first one alerting.
        assertTrue(removed, removed.trimEnd().endsWith("settleKeptCalendarRows(account, provider, serverCalIds, askAgain = waitingAgain.firstOrNull())"))
        val settle = squash(code(body(adapter, "private suspend fun settleKeptCalendarRows(")))
        assertTrue(settle, settle.contains("val kept = keptCalendarRows(provider, account)"))
        assertTrue(settle, settle.contains("val keptEvents = calendarEventSyncDao.getAllByCalendar(account.name, protonId).size"))
        assertTrue(settle, settle.contains("removeKeptCalendarRowIfEmpty(provider, account, androidCalId, protonId, serverCalIds, androidCalId in mappedRows, keptEvents)"))
        assertTrue(settle, settle.contains("if (askAgain != null) NewEventHolds.refreshPrompts(context, provider, account, mappedRows, alertId = askAgain)"))
        // Only rows that carry the kept-for marker are looked at.
        val listed = squash(code(body(adapter, "private fun keptCalendarRows(")))
        assertTrue(listed, listed.contains("CalendarRowClaims.keptFor(c.getString(2))?.let { out[c.getLong(0)] = it }"))
        // Both counts are read, then the rule decides, then the row goes, as the sync adapter.
        val remove = squash(code(body(adapter, "private fun removeKeptCalendarRowIfEmpty(")))
        val live = remove.indexOf("val live = calendarRowCount(provider, account, androidCalId)")
        val own = remove.indexOf("val phoneOnly = phoneOnlyRowCount(provider, account, androidCalId)")
        val rule = remove.indexOf("if (!PhoneOnlyRows.removeKeptCalendarRow(protonId, serverCalIds, rowMapped, keptEventMappings, live, phoneOnly)) return")
        val gone = remove.indexOf("provider.delete(")
        assertTrue(remove, live >= 0 && own > live && rule > own && gone > rule)
        assertTrue(remove, remove.contains("ContentUris.withAppendedId(SyncUtils.asCallerSyncAdapter(CalendarContract.Calendars.CONTENT_URI, account), androidCalId)"))
        // And a published series whose calendar has no mapping at an upload waits again, in that run's plan too.
        val hold = squash(code(body(adapter, "private fun applyNewEventHolds(")))
        val nowhere = hold.indexOf("val nowhere = NewEventHoldRules.publishedNowhere(holdSnapshot, mappedCalendarIds)")
        val planned = hold.indexOf("rows, entries, decisions, mappedIds, verdictOf, System.currentTimeMillis(),")
        assertTrue(hold, nowhere >= 0 && planned > nowhere)
        assertTrue(hold, hold.contains("val entries = holdSnapshot.map { if (it in nowhere) it.copy(state = NewEventHoldRules.State.WAITING) else it }"))
        assertFalse(hold, hold.contains("rows, holdSnapshot, decisions"))
    }

    @Test
    fun `only exceptions made on this phone wait for where their parent sits`() {
        // The name predates the owner's decision L of 2026-11-22: since then the read also asks for a date with a Proton
        // event of its own, whose parent never reached Proton. The read takes the account too (decision L): a parent row
        // in another account of this app counts as not read (the next test).
        val hold = squash(code(body(adapter, "private fun applyNewEventHolds(")))
        // Since the owner's answer of 2026-11-22 the same read also gives which of those calendars are this account's
        // (the next tests), so its result is taken apart before the plan; the plan's parentCalendars are still its own.
        assertTrue(hold, hold.contains("val (parentCalendars, accountCalendars) = parentCalendarsOf(account, provider, rows, decisions, mappedIds)"))
        assertTrue(hold, hold.contains("parentCalendars = parentCalendars,"))
        // The parent read asks only for the parents the plan's own rules need. Widened by the owner's decision L of
        // 2026-11-22: besides the moved-parent rule's exceptions, every live date of a parent that never reached Proton,
        // wherever it sits (parentCalendarCounts), which stays out while that parent sits in a shared calendar.
        val parents = squash(code(body(adapter, "private fun parentCalendarsOf(")))
        assertTrue(parents, parents.contains("r.originalId?.takeIf { it !in inRun && NewEventHoldRules.parentCalendarCounts(r, decisions, mappedIds) }"))
        assertFalse(parents, parents.contains("NewEventHoldRules.needsParentCalendar(r, decisions, mappedIds)"))
        // A parent whose read failed gets the calendar for which the plan keeps those dates out until the next run.
        assertTrue(parents, parents.contains("wanted.associateWith { NewEventHoldRules.UNREAD_CALENDAR }"))
        val rules = squash(code(source("java/me/proton/sync/calendar/NewEventHoldRules.kt")))
        assertTrue(rules, rules.contains("if (moved && needsParentCalendar(row, decisions, mappedIds)) apart.add(row) else upload.add(row)"))
        assertTrue(rules, rules.contains("needsParentCalendar(row, decisions, mappedIds) || ofPhoneOnlyParent(row, mappedIds)"))
    }

    @Test
    fun `a parent row in another account of this app counts as not read`() {
        // The owner's decision L of 2026-11-22 keeps the dates out wherever they are. Only another account's own sync reads
        // its calendars' members, so the parent read takes each parent row's account, and NewEventHoldRules.parentCalendar
        // (NewEventHoldRulesTest) gives a parent in another account of this app UNREAD_CALENDAR, for which the plan keeps
        // those dates out as for a parent not read.
        val parents = squash(code(body(adapter, "private fun parentCalendarsOf(")))
        assertTrue(
            parents,
            parents.contains(
                "arrayOf( CalendarContract.Events._ID, CalendarContract.Events.CALENDAR_ID, " +
                    "CalendarContract.Events.ACCOUNT_NAME, CalendarContract.Events.ACCOUNT_TYPE )"
            )
        )
        assertTrue(
            parents,
            parents.contains(
                "out[c.getLong(0)] = NewEventHoldRules.parentCalendar(c.getLong(1), c.getString(2), c.getString(3), account.name, account.type)"
            )
        )
        assertFalse(parents, parents.contains("out[c.getLong(0)] = c.getLong(1)"))
    }

    @Test
    fun `a parent that never reached Proton keeps its dates out while it sits in a calendar shared with the account`() {
        // The owner's decision L of 2026-11-22. The decision loop marks each listed calendar someone else shared with the
        // account, with or without the right to write, and the plan gets that set.
        val hold = squash(code(body(adapter, "private fun applyNewEventHolds(")))
        val listed = hold.indexOf("val info = listed[m.protonCalendarId] ?: continue")
        val shared = hold.indexOf("if (NewEventHoldRules.sharedWithAccount(info.type, info.members, userAddresses)) shared.add(androidCalId)")
        val planned = hold.indexOf("val plan = NewEventHoldRules.plan(")
        assertTrue(hold, listed >= 0 && shared > listed && planned > shared)
        assertTrue(hold, hold.contains("sharedCalendars = shared )"))
        // The plan's last pass, after the one for parents that live on Proton, keeps those dates apart, which takes them
        // out of the upload with the other out-lists (pinned above). It takes this account's calendars too since the
        // owner's answer of 2026-11-22 (the next test).
        val rules = squash(code(source("java/me/proton/sync/calendar/NewEventHoldRules.kt")))
        val lives = rules.indexOf("moveFollowers(upload.filter { livesApart(it, parentHomes, decisions, mappedIds) }, upload, apart)")
        val last = rules.indexOf(
            "moveFollowers( upload.filter { apartFromSharedParent(it, rowOf, parentCalendars, decisions, sharedCalendars, " +
                "mappedIds, accountCalendars) }, upload, apart )"
        )
        val returned = rules.indexOf("return Plan(upload, skipped, undecided, newHolds, drop.toList(), apart)")
        assertTrue(rules, lives >= 0 && last > lives && returned > last)
        assertTrue(rules, rules.contains("type == 0 && ownMember(members, userAddresses) != null && !isCalendarOwner(members, userAddresses)"))
    }

    @Test
    fun `a parent row in a calendar of this account without a decision keeps its dates out, read with the row's account`() {
        // The owner's answer of 2026-11-22: after a refused Publish, a shared calendar Proton no longer lists (or the row
        // the phone keeps for it) has no decision, and is no longer among the shared ones. The parent read that says where
        // a parent sits also says, from the same row's account, whether that calendar is this account's, in the same query.
        val parents = squash(code(body(adapter, "private fun parentCalendarsOf(")))
        assertTrue(parents, parents.contains("): Pair<Map<Long, Long>, Set<Long>> {"))
        val seat = parents.indexOf(
            "out[c.getLong(0)] = NewEventHoldRules.parentCalendar(c.getLong(1), c.getString(2), c.getString(3), account.name, account.type)"
        )
        val own = parents.indexOf(
            "if (NewEventHoldRules.inAccount(c.getString(2), c.getString(3), account.name, account.type)) ofAccount.add(c.getLong(1))"
        )
        assertTrue(parents, seat >= 0 && own > seat)
        assertTrue(parents, parents.contains("out to ofAccount"))
        // Nothing to read, or a read that failed: no calendar is known to be this account's, and a failed read still keeps
        // every such date out as not read.
        assertTrue(parents, parents.contains("if (wanted.isEmpty()) return emptyMap<Long, Long>() to emptySet<Long>()"))
        assertTrue(parents, parents.contains("wanted.associateWith { NewEventHoldRules.UNREAD_CALENDAR } to emptySet<Long>()"))
        // Both halves go to the plan, which hands this account's calendars to its last pass (the previous test).
        val hold = squash(code(body(adapter, "private fun applyNewEventHolds(")))
        val read = hold.indexOf("val (parentCalendars, accountCalendars) = parentCalendarsOf(account, provider, rows, decisions, mappedIds)")
        val planned = hold.indexOf("val plan = NewEventHoldRules.plan(")
        val passed = hold.indexOf("accountCalendars = accountCalendars,")
        assertTrue(hold, read >= 0 && planned > read && passed > planned && hold.indexOf("sharedCalendars = shared )") > passed)
        // The rule: a calendar without a decision this run counts when it is this account's, which a parent row among the
        // run's rows always sits in.
        val rules = squash(code(source("java/me/proton/sync/calendar/NewEventHoldRules.kt")))
        assertTrue(rules, rules.contains("val unjudged = decisions[calendar] == null && (parent != null || calendar in accountCalendars)"))
        assertTrue(
            rules,
            rules.contains(
                "(calendar == UNREAD_CALENDAR || unjudged || calendar in sharedCalendars || holdsOrUndecided(decisions[calendar]))"
            )
        )
        assertTrue(rules, rules.contains("accountType == ownType && accountName == ownName"))
    }

    @Test
    fun `a date the plan keeps out never reaches the upload, so its parent never goes up in the date's calendar`() {
        // Events reach Proton only through uploadModifiedEvent, from the rows readDirtyRows returns once the plan took its
        // own out. A cancelled date's upload reads its parent by id and sends the whole series in the date's calendar
        // (uploadCancelledOccurrenceParent), so a date kept out with an undecided, waiting or kept series, in an own
        // calendar too (owner, 2026-11-22), must not reach the upload another way.
        val hold = squash(code(body(adapter, "private fun applyNewEventHolds(")))
        val removed = hold.indexOf("if (out.isNotEmpty()) dirtyRows.removeAll { it.androidEventId in out }")
        for (list in listOf("skipped", "undecided", "apart")) {
            val taken = hold.indexOf("plan.$list.mapTo(out) { it.id }")
            assertTrue("$list: $hold", taken >= 0 && removed > taken)
        }
        val upload = squash(code(body(adapter, "private suspend fun uploadDirtyEvents(")))
        val read = upload.indexOf("val dirtyRows = readDirtyRows(")
        val each = upload.indexOf("coroutineScope { dirtyRows.map { row ->")
        assertTrue(upload, read >= 0 && each > read)
        // One caller each: uploadDirtyEvents calls uploadModifiedRow, which alone calls uploadCancelledOccurrenceParent,
        // and those two alone call uploadModifiedEvent. A new caller has to keep to the plan as well.
        val all = squash(code(adapter))
        fun count(name: String) = Regex(Regex.escape("$name(")).findAll(all).count()
        assertEquals("uploadModifiedRow", 2, count("uploadModifiedRow"))
        assertEquals("uploadCancelledOccurrenceParent", 2, count("uploadCancelledOccurrenceParent"))
        assertEquals("uploadModifiedEvent", 3, count("uploadModifiedEvent"))
        val row = squash(code(body(adapter, "private suspend fun uploadModifiedRow(")))
        assertTrue(
            row,
            row.contains("uploadCancelledOccurrenceParent( account, provider, parentId, row.androidCalId, syncResult, uploadContext, suppressAttendeeEmails )")
        )
        // The plan's pass for an undecided series' dates in other calendars, after its own followers.
        val rules = squash(code(source("java/me/proton/sync/calendar/NewEventHoldRules.kt")))
        val followers = rules.indexOf("moveFollowers(followersOf(undecided.toList(), upload, mappedIds), upload, undecided)")
        val dates = rules.indexOf("moveFollowers(upload.filter { r -> r.originalId?.let { it in waitIds } == true }, upload, apart)")
        assertTrue(rules, followers >= 0 && dates > followers)
    }

    @Test
    fun `a calendar without a decision is one the upload has no context for, so its new series' dates elsewhere wait`() {
        // The plan keeps the dates in other calendars of a new series out while the series' calendar has no decision. Only
        // a mapped calendar of this run's list gets one, and only a mapped calendar Proton lists gets an upload context.
        val hold = squash(code(body(adapter, "private fun applyNewEventHolds(")))
        val mapped = hold.indexOf("val androidCalId = m.androidCalendarId ?: continue")
        val listed = hold.indexOf("val info = listed[m.protonCalendarId] ?: continue")
        val decided = hold.indexOf("decisions.merge(androidCalId, decision) { a, b -> NewEventHoldRules.stricter(a, b) }")
        assertTrue(hold, mapped >= 0 && listed > mapped && decided > listed)
        val context = squash(code(body(adapter, "private suspend fun prepareCalendarUploadContext(")))
        assertTrue(context, context.contains(".firstOrNull { it.androidCalendarId == androidCalId } ?: return null"))
        assertTrue(context, context.contains("val calInfo = calResponse.body()?.calendars?.firstOrNull { it.id == protonCalendarId } ?: return null"))
        // In the plan: such a candidate goes to the upload and roots the pass, after the undecided series' one and before
        // the waiting and kept series' one.
        val rules = squash(code(source("java/me/proton/sync/calendar/NewEventHoldRules.kt")))
        assertTrue(rules, rules.contains("null -> { upload.add(row) nowhere.add(row) }"))
        val undecided = rules.indexOf("moveFollowers(upload.filter { r -> r.originalId?.let { it in waitIds } == true }, upload, apart)")
        val nowhere = rules.indexOf("upload.filter { r -> r.originalId?.let { it in nowhereIds && calendarOfRow[it] != r.calendarId } == true }")
        val kept = rules.indexOf("val outIds = skipped.mapTo(HashSet()) { it.id }")
        assertTrue(rules, undecided >= 0 && nowhere > undecided && kept > nowhere)
    }

    @Test
    fun `a changed date made on this phone stays apart while its parent lives on Proton in another calendar`() {
        // The published series' pass and the moved-parent rule see only the run that uploads the parent; after it, where
        // the parent's mapping says the series lives keeps such a date out, in every run.
        val hold = squash(code(body(adapter, "private suspend fun holdNewEventsFromOtherApps(")))
        val mapped = hold.indexOf("val mappedIds = calendarEventSyncDao.androidEventIdsByAccount(account.name).toHashSet()")
        val parents = hold.indexOf("val parentProtonCalendars = parentProtonCalendarsOf(account, dirtyRows, mappedIds)")
        val applied = hold.indexOf(
            "applyNewEventHolds(account, provider, dirtyRows, calendars, holdSnapshot, mappings, mappedIds, parentProtonCalendars)"
        )
        assertTrue(hold, mapped >= 0 && parents > mapped && applied > parents)
        // Only the mapped parents of live exceptions with no Proton event of their own, a few hundred per query.
        val read = squash(code(body(adapter, "private suspend fun parentProtonCalendarsOf(")))
        assertTrue(
            read,
            read.contains("r.originalId?.takeIf { it in mappedIds && r.deleted != 1 && r.syncId.isNullOrBlank() && r.androidEventId !in mappedIds }")
        )
        assertTrue(read, read.contains("for (chunk in parents.chunked(400)) {"))
        assertTrue(read, read.contains("calendarEventSyncDao.getByAndroidIds(account.name, chunk)"))
        val apply = squash(code(body(adapter, "private fun applyNewEventHolds(")))
        assertTrue(
            apply,
            apply.contains(
                "parentHomes = NewEventHoldRules.homesOf( parentProtonCalendars, " +
                    "mappings.mapNotNull { m -> m.androidCalendarId?.let { m.protonCalendarId to it } } )"
            )
        )
        // The plan's last pass, after the published series' one.
        val rules = squash(code(source("java/me/proton/sync/calendar/NewEventHoldRules.kt")))
        val published = rules.indexOf("it in published && calendarOfRow[it] != r.calendarId")
        val last = rules.indexOf("moveFollowers(upload.filter { livesApart(it, parentHomes, decisions, mappedIds) }, upload, apart)")
        val planned = rules.indexOf("return Plan(upload, skipped, undecided, newHolds, drop.toList(), apart)")
        assertTrue(rules, published >= 0 && last > published && planned > last)
    }

    @Test
    fun `a prompt offers Move only while Move would keep the series together`() {
        // Move refuses a series whose changed date an app left in a third calendar. The notification and the dialog take
        // Move's target from the Prompt, which names one only when that check passes, read as Move's assert counts.
        val holds = code(source("java/me/proton/sync/calendar/NewEventHolds.kt"))
        val prompts = squash(holds.substringAfter("fun prompts(").substringBefore("\n    }\n"))
        // Read for the entries Move is checked for, while there is a target: every entry the caller is given unless it
        // narrows them (a refresh, to the prompts it posts); an entry that is not checked names no target.
        assertTrue(prompts, prompts.contains("checkMoveOf: (List<Entry>) -> List<Entry> = { it },"))
        assertTrue(prompts, prompts.contains("val checked = if (target == null) emptyList() else checkMoveOf(drawn)"))
        assertTrue(prompts, prompts.contains("val exceptionsIn = if (checked.isEmpty()) emptyMap() else exceptionCalendars(provider, account, checked)"))
        assertTrue(prompts, prompts.contains("e.eventId in checkedIds && it.calendarId != e.calendarId &&"))
        assertTrue(prompts, prompts.contains("NewEventHoldRules.moveKeepsTogether(e.calendarId, it.calendarId, exceptionsIn[e.eventId].orEmpty())"))
        val read = squash(holds.substringAfter("private fun exceptionCalendars(").substringBefore("\n    }\n"))
        assertTrue(read, read.contains("uidRows(provider, account, e, emptySet())"))
        assertTrue(read, read.contains("CalendarContract.Events.CONTENT_URI, arrayOf(CalendarContract.Events.ORIGINAL_ID, CalendarContract.Events.CALENDAR_ID),"))
        assertFalse(read, read.contains("asCallerSyncAdapter"))
        val prompt = squash(code(body(source("java/me/proton/sync/SyncNotificationHelper.kt"), "internal fun showNewEventHoldPrompt(")))
        assertTrue(prompt, prompt.contains("val target = prompt.moveTarget"))
        assertTrue(squash(code(source("java/me/proton/sync/settings/NewEventHoldDialog.kt"))).contains("val target = prompt.moveTarget"))
    }

    @Test
    fun `Move leaves no row of the series behind, and a prompt is posted only while its question stands`() {
        val move = squash(actions.substringAfter("private fun move(").substringBefore("private fun gone("))
        assertTrue(move, move.contains("${"$"}{CalendarContract.Events._ID} NOT IN ("))
        assertTrue(move, move.contains(".withExpectedCount(0)"))
        assertTrue(move, move.indexOf(".withExpectedCount(0)") < move.indexOf("client.applyBatch(ops)"))
        val series = squash(actions.substringAfter("private fun seriesRows(").substringBefore("private class Series("))
        assertTrue(series, series.contains("${"$"}{CalendarContract.Events.ORIGINAL_ID} IN (${"$"}{clones.joinToString(\",\") { \"?\" }})"))
        val holds = code(source("java/me/proton/sync/calendar/NewEventHolds.kt"))
        val refresh = squash(holds.substringAfter("fun refreshPrompts(").substringBefore("suspend fun refreshOutsideSync("))
        val before = refresh.indexOf("if (!NewEventHoldStore.isStored(prefs, account.name, p.entry)) continue")
        val posted = refresh.indexOf("SyncNotificationHelper.showNewEventHoldPrompt(")
        val after = refresh.indexOf("if (!NewEventHoldStore.isStored(prefs, account.name, p.entry)) {", posted)
        assertTrue(refresh, before >= 0 && posted > before && after > posted)
    }

    @Test
    fun `the manifest makes the calendar editors visible and nothing else`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val queries = manifest.substringAfter("<queries>").substringBefore("</queries>")
        assertEquals(2, Regex("""<action android:name="android\.intent\.action\.INSERT" />""").findAll(queries).count())
        for (type in CalendarEditors.EVENT_TYPES) assertTrue(type, queries.contains("android:mimeType=\"$type\""))
        assertFalse(queries.contains("android.intent.action.MAIN"))
        assertFalse(queries.contains("EDIT"))
        assertFalse(queries.contains("android:path"))
        assertFalse(manifest.contains("QUERY_ALL_PACKAGES"))
        val receiver = squash(manifest.substringAfter("android:name=\".calendar.NewEventHoldReceiver\"").substringBefore("/>"))
        assertTrue(receiver, receiver.contains("android:exported=\"false\""))
    }
}

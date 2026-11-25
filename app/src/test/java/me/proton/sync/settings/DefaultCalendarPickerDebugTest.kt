package me.proton.sync.settings

import me.proton.sync.calendar.DefaultCalendarService.Fixture
import me.proton.sync.settings.DefaultCalendarPicker.Debug
import me.proton.sync.settings.DefaultCalendarPicker.Debug.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The default_calendar debug verb's extras, and that the verb and the card's row reach the Default calendar row
 * through the same entry point, and the verb's pick through the list's own item listener; which text each way the
 * load fails shows. Made-up names only.
 */
class DefaultCalendarPickerDebugTest {

    @Test
    fun `the verb's modes and fixtures`() {
        assertEquals(Debug(Mode.LIST, null, Fixture.NONE), Debug.parse("list", null, null))
        assertEquals(Debug(Mode.OPEN, null, Fixture.NONE), Debug.parse("open", null, "none"))
        assertEquals(Debug(Mode.OPEN, null, Fixture.NONE), Debug.parse("open", "  ", ""))
        assertEquals(Debug(Mode.CANCEL, null, Fixture.NONE), Debug.parse("cancel", null, null))
        assertEquals(Debug(Mode.CANCEL, null, Fixture.OFFLINE), Debug.parse("cancel", null, "offline"))
        assertEquals(Debug(Mode.PICK, "Home", Fixture.NONE), Debug.parse("pick", " Home ", null))
        val fixtures = mapOf(
            "offline" to Fixture.OFFLINE, "offline_save" to Fixture.OFFLINE_SAVE, "timeout_save" to Fixture.TIMEOUT_SAVE,
            "refuse" to Fixture.REFUSE, "auth" to Fixture.AUTH, "incomplete" to Fixture.INCOMPLETE,
            "none_own" to Fixture.NONE_OWN, "none" to Fixture.NONE,
        )
        for ((name, fixture) in fixtures) {
            assertEquals(name, Debug(Mode.PICK, "Work", fixture), Debug.parse("pick", "Work", name))
            assertEquals(name, fixture, Debug.parse("list", null, name)?.fixture)
        }
        assertEquals(Fixture.entries.toSet(), fixtures.values.toSet())
    }

    @Test
    fun `extras that make no verb are refused`() {
        // pick needs the part of a name.
        assertNull(Debug.parse("pick", null, null))
        assertNull(Debug.parse("pick", "", null))
        assertNull(Debug.parse("pick", "   ", "refuse"))
        assertNull(Debug.parse("show", "Home", null))
        assertNull(Debug.parse(null, null, null))
        assertNull(Debug.parse("LIST", null, null))
        assertNull(Debug.parse("Cancel", null, null))
        // The script turns dashes into underscores; anything else is not a fixture.
        assertNull(Debug.parse("list", null, "offline-save"))
        assertNull(Debug.parse("open", null, "fail"))
    }

    private fun source(path: String) = File("src/main/java/me/proton/sync/$path").readText().replace("\r\n", "\n")

    @Test
    fun `the row and the verb open the same picker, and the verb presses the list's own listener`() {
        val activity = source("settings/SyncSettingsActivity.kt")
        assertTrue(activity.contains("cardBinding.rowDefaultCalendar.setOnClickListener { defaultCalendarPicker.show(account) }"))
        val branch = activity.substringAfter("\"default_calendar\" -> {").substringBefore("\n            }\n")
        assertTrue(branch, branch.contains("DefaultCalendarPicker.Debug.parse(mode, namePart, fixture)"))
        assertTrue(branch, branch.contains("defaultCalendarPicker.show(account, debug)"))
        // Its info dialog, and the row redrawn whenever the record changes.
        assertTrue(activity.contains("bindInfo(c.infoDefaultCalendar, R.string.label_default_calendar, R.string.info_default_calendar_body)"))
        val listener = activity.substringAfter("private val cardRowListener =").substringBefore("\n    }\n")
        assertTrue(listener, listener.contains("if (key == SyncPreferences.primaryCalendarLastKey(name)) refreshDefaultCalendarValue(handle.parentAccount)"))

        val picker = source("settings/DefaultCalendarPicker.kt")
        assertTrue(picker.contains("dialog.listView.performItemClick(null, i, i.toLong())"))
    }

    private fun body(src: String, signature: String): String {
        assertTrue("$signature not found", src.contains(signature))
        return src.substringAfter(signature).substringBefore("\n    }\n")
    }

    @Test
    fun `the card row shows the default calendar's name read live, never one stored with the record`() {
        // The record in plain preferences holds ids only (the owner's rule, 2026-11-21): the name comes from the chosen
        // calendar's mapping in the app's database, off the main thread.
        val refresh = body(source("settings/SyncSettingsActivity.kt"), "private fun refreshDefaultCalendarValue(")
        assertTrue(refresh, refresh.contains("val chosenId = last?.protonCalendarId"))
        assertTrue(refresh, refresh.contains("withContext(Dispatchers.IO)"))
        assertTrue(refresh, refresh.contains("syncDatabase.calendarSyncDao().getByProtonId(account.name, chosenId)?.displayName"))
        assertFalse(refresh, Regex("""\blast\??\.name\b""").containsMatchIn(refresh))
    }

    @Test
    fun `the card row follows a rename the account's calendar sync brings while the screen is open`() {
        // The record holds ids only, so a rename on Proton leaves it unchanged and the listener never fires: the end of the
        // account's calendar sync redraws the row, as it redraws the "Remove older events" row.
        val indicators = body(source("settings/SyncSettingsActivity.kt"), "private fun refreshSyncIndicators() {")
        val ended = indicators.indexOf("val calendarSyncEnded = !calendarActive && handle.parentAccount.name in calendarSyncSeen")
        val seen = indicators.indexOf("if (calendarActive) calendarSyncSeen.add(handle.parentAccount.name)")
        val redrawn = indicators.indexOf("if (calendarSyncEnded) refreshDefaultCalendarValue(handle.parentAccount)")
        assertTrue(indicators, ended >= 0 && seen > ended && redrawn > seen)
    }

    @Test
    fun `the load starts once its waiting dialog shows, and Cancel or the screen's end frees the row at once`() {
        val show = body(source("settings/DefaultCalendarPicker.kt"), "fun show(account: Account, debug: Debug? = null) {")
        val launched = show.indexOf("activity.lifecycleScope.launch(start = CoroutineStart.LAZY) {")
        val shown = show.indexOf("progress.show()")
        val started = show.indexOf("job.start()")
        assertTrue(show, launched >= 0 && shown > launched && started > shown)
        // The load's key unlock cannot be cancelled: the row must not wait for it. Each load frees only its own token.
        val cancel = show.substringAfter("cancel?.setOnClickListener {").substringBefore("}")
        assertTrue(cancel, cancel.indexOf("busy.remove(name, token)") in 0 until cancel.indexOf("job.cancel()"))
        val destroy = show.substringAfter("if (event == Lifecycle.Event.ON_DESTROY) {").substringBefore("}")
        assertTrue(destroy, destroy.contains("busy.remove(name, token)"))
        assertTrue(show, show.contains("job.invokeOnCompletion { busy.remove(name, token) }"))
        // The verb's cancel presses that same Cancel.
        assertTrue(show, show.contains("if (job.isActive) cancel?.performClick()"))
    }

    @Test
    fun `no address loaded fails as unreachable, calendars not told apart as incomplete, which a fixture shows`() {
        val load = body(source("calendar/DefaultCalendarService.kt"), "private suspend fun loadChecked(")
        // The fixture stands in for the load before any request, as offline and auth do.
        val fixture = load.indexOf("Fixture.INCOMPLETE -> return failed(name, Kind.INCOMPLETE, \"fixture incomplete\")")
        val unlocked = load.indexOf("unlock(account)?.let { return it }")
        assertTrue(load, fixture >= 0 && unlocked > fixture)
        // After the one address re-read: no address at all is a failed read that another try may mend, not calendars
        // that cannot be told apart.
        val reread = load.indexOf("SyncUtils.invalidateKeyCache(name)")
        val unknown = load.substringAfter("is DefaultCalendarChoices.Shape.Unknown -> return if (shape.noAddressLoaded) {")
            .substringBefore("is DefaultCalendarChoices.Shape.NoOwnCalendar ->")
        assertTrue(load, reread >= 0 && load.indexOf("is DefaultCalendarChoices.Shape.Unknown ->") > reread)
        val noAddress = unknown.substringBefore("} else {")
        val someAddresses = unknown.substringAfter("} else {")
        assertTrue(noAddress, noAddress.contains("Kind.UNREACHABLE") && !noAddress.contains("Kind.INCOMPLETE"))
        assertTrue(someAddresses, someAddresses.contains("Kind.INCOMPLETE") && !someAddresses.contains("Kind.UNREACHABLE"))
        // Each kind reaches its own text on the row.
        val show = body(source("settings/DefaultCalendarPicker.kt"), "fun show(account: Account, debug: Debug? = null) {")
        assertTrue(show, show.contains("Kind.INCOMPLETE -> message(R.string.default_calendar_incomplete)"))
        assertTrue(show, show.contains("Kind.UNREACHABLE -> Toast.makeText(activity, R.string.default_calendar_unreachable, Toast.LENGTH_LONG).show()"))
    }

    @Test
    fun `the checked calendar applies nothing, and a change's toast reads its text while the screen is alive`() {
        val picker = source("settings/DefaultCalendarPicker.kt")
        // The load brought the phone in line already; the list may be older than a change made since.
        assertTrue(!picker.contains("bringInLine"))
        val picked = body(picker, "private fun onPicked(")
        val texts = picked.indexOf("val texts = TOASTS.associateWith { activity.getString(it) }")
        assertTrue(picked, texts >= 0 && texts < picked.indexOf("saveScope.launch {"))
        assertTrue(picked, picked.contains("Toast.makeText(app, texts.getValue(toastFor(outcome)), Toast.LENGTH_LONG)"))
    }

    @Test
    fun `a confirmed default without a row asks for the ordinary settings sync, and nothing else syncs`() {
        val service = source("calendar/DefaultCalendarService.kt")
        val notOnPhone = service.indexOf("if (done?.noRow == PrimaryCalendarPicks.NoRow.NOT_ON_PHONE) {")
        val asked = service.indexOf("SyncScheduling.requestSettingsSync(context, account, \"default calendar\")")
        assertTrue(notOnPhone >= 0 && asked > notOnPhone)
        assertEquals(1, Regex(Regex.escape("requestSettingsSync(")).findAll(service).count())
        for (path in listOf("calendar/DefaultCalendarService.kt", "settings/DefaultCalendarPicker.kt", "calendar/PrimaryCalendarPicks.kt", "calendar/DefaultCalendarChoices.kt")) {
            val src = source(path)
            assertTrue(path, !src.contains("requestSync(") && !src.contains("SYNC_EXTRAS_"))
        }
    }
}

package me.proton.sync.backup

import me.proton.sync.SyncPreferences
import me.proton.sync.contacts.ContactNameMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The start-up repair (BackupSettings.repairStoredTypes, run first in Application.onCreate). Versions before
 * this one stored a restored JSON boolean as a Boolean and any other value as a String, whatever the key, so
 * an edited backup with "true" in quotes for a per-account switch left a String where getBoolean reads.
 * Application.onCreate reads display_name_user_chosen_<account> that way, outside any try, and the app could
 * not start again.
 */
class BackupSettingsRepairTest {

    private val account = "me@example.com"
    private val userChosen = SyncPreferences.displayNameUserChosenKey(account)

    @Test
    fun `the state an older import left behind stops the reader, and the repair gives it back its Boolean`() {
        val prefs = StrictPrefs()
        // What the old ImportManager.writeSettings did with "true" in quotes: putString(value.toString()).
        prefs.fake.values[userChosen] = "true"
        try {
            prefs.getBoolean(userChosen, false)
            fail("the fake must throw like Android, or this case proves nothing")
        } catch (_: ClassCastException) {
        }

        val repair = BackupSettings.repairStoredTypes(prefs)

        assertTrue(prefs.getBoolean(userChosen, false))
        assertEquals(listOf(userChosen), repair.converted)
        assertTrue(repair.removed.isEmpty())
    }

    @Test
    fun `every per-account and app-wide switch is converted, whatever the case and spaces`() {
        val prefs = StrictPrefs()
        val switches = mapOf(
            SyncPreferences.fallbackContactsEnabledKey(account) to " TRUE ",
            SyncPreferences.fallbackCalendarEnabledKey(account) to "false",
            SyncPreferences.sendCalendarInvitesKey(account) to "True",
            SyncPreferences.notifySyncFailureKey("calendar", account) to "FALSE",
            SyncPreferences.contactFilterDirtyKey(account) to "true",
            SyncPreferences.KEY_VERBOSE_LOGGING to "false",
            SyncPreferences.KEY_FILE_LOGGING to "true",
            SyncPreferences.KEY_TIMEZONE_UPDATES to "false",
            SyncPreferences.KEY_BACKUP_ENABLED to "true",
        )
        prefs.fake.values.putAll(switches)

        val repair = BackupSettings.repairStoredTypes(prefs)

        for ((key, text) in switches) {
            assertEquals(key, text.trim().lowercase() == "true", prefs.getBoolean(key, text.trim().lowercase() != "true"))
        }
        assertEquals(switches.keys.sorted(), repair.converted)
    }

    @Test
    fun `a value that cannot be read as its type is removed, so the reader's default applies`() {
        val prefs = StrictPrefs()
        val invites = SyncPreferences.sendCalendarInvitesKey(account)
        val inProgress = SyncPreferences.notifySyncInProgressKey(account)
        val filterDirty = SyncPreferences.contactFilterDirtyKey(account)
        prefs.fake.values[invites] = "yes"
        // What an older restore stored for a JSON null: its text.
        prefs.fake.values[inProgress] = "null"
        prefs.fake.values[filterDirty] = "1"

        val repair = BackupSettings.repairStoredTypes(prefs)

        assertFalse(prefs.contains(invites))
        assertFalse("invitation emails default to off", prefs.getBoolean(invites, false))
        assertTrue("the in-progress notice defaults to on", prefs.getBoolean(inProgress, true))
        assertFalse(prefs.contains(filterDirty))
        assertEquals(listOf(invites, filterDirty, inProgress).sorted(), repair.removed)
        assertTrue(repair.converted.isEmpty())
        assertTrue(repair.assumed.isEmpty())
    }

    @Test
    fun `a switch stored where text belongs is removed, so the default applies`() {
        // An older restore stored a JSON boolean as a Boolean whatever the key, and getString throws on one. As
        // the text "true" the sync mode would be a custom window (anything but "all" is), not the default.
        val prefs = StrictPrefs()
        val mode = SyncPreferences.calendarSyncModeKey(account)
        val interval = SyncPreferences.eventLoopIntervalKey(account)
        prefs.fake.values[mode] = true
        prefs.fake.values[interval] = false
        prefs.fake.values[ContactNameMode.key(account)] = true

        val repair = BackupSettings.repairStoredTypes(prefs)

        assertEquals(SyncPreferences.DEFAULT_SYNC_MODE, prefs.getString(mode, SyncPreferences.DEFAULT_SYNC_MODE))
        assertEquals(3600L, SyncPreferences.getLongFromStringPref(prefs, interval, 3600))
        assertEquals(listOf(mode, interval, ContactNameMode.key(account)).sorted(), repair.removed)
    }

    @Test
    fun `an unreadable user-chosen name flag is set to true, so this start does not rename the account`() {
        // Removed, migrateParentAccountNames (a few lines after the repair in Application.onCreate) would read
        // false and rename the bare account name "Work" to its email form: the rename the flag exists to stop.
        for (stored in listOf("1", "null", "yes", "")) {
            val prefs = StrictPrefs()
            val work = SyncPreferences.displayNameUserChosenKey("Work")
            prefs.fake.values[work] = stored

            val repair = BackupSettings.repairStoredTypes(prefs)

            assertTrue(stored, prefs.getBoolean(work, false))
            assertEquals(listOf(work), repair.assumed)
            assertTrue(repair.converted.isEmpty() && repair.removed.isEmpty())
        }
    }

    @Test
    fun `an unreadable backup encryption switch is set to on, so no run writes cleartext`() {
        // Removed, the next scheduled data backup would write every contact and event unencrypted. On, a run
        // without a stored password fails and writes nothing, as it did while the value was unreadable.
        val prefs = StrictPrefs()
        prefs.fake.values[SyncPreferences.KEY_BACKUP_ENCRYPTION_ENABLED] = "1"
        prefs.fake.values[SyncPreferences.KEY_BACKUP_ENABLED] = "1"

        val repair = BackupSettings.repairStoredTypes(prefs)

        assertTrue(prefs.getBoolean(SyncPreferences.KEY_BACKUP_ENCRYPTION_ENABLED, false))
        assertEquals(listOf(SyncPreferences.KEY_BACKUP_ENCRYPTION_ENABLED), repair.assumed)
        // Turning scheduled exports on is not the safe side either: removed, and the reminder asks again.
        assertFalse(prefs.contains(SyncPreferences.KEY_BACKUP_ENABLED))
        assertEquals(listOf(SyncPreferences.KEY_BACKUP_ENABLED), repair.removed)
    }

    @Test
    fun `a type no restore ever wrote is left alone, even under an exportable key`() {
        // Every restore put a Boolean or a String. Anything else under an exportable key is the app's own,
        // which the repair must never rewrite at every start (a new key under an existing prefix is for the
        // guard in BackupSettingsTypeMapTest).
        val prefs = StrictPrefs()
        val untouched = mapOf<String, Any>(
            SyncPreferences.KEY_BACKUP_INTERVAL_DAYS to 7,
            SyncPreferences.eventLoopIntervalKey(account) to 900L,
            SyncPreferences.calendarPastDaysKey(account) to 90,
            SyncPreferences.notifySyncInProgressKey(account) to 1,
            "notify_sync_failure_snoozed_until_$account" to 1700000000000L,
            userChosen to 1.5f,
            SyncPreferences.calendarSyncModeKey(account) to setOf("all"),
        )
        prefs.fake.values.putAll(untouched)

        val repair = BackupSettings.repairStoredTypes(prefs)

        assertTrue(repair.isEmpty)
        assertEquals(untouched, prefs.fake.values)
        assertEquals(0, prefs.editsOpened)
    }

    @Test
    fun `correctly typed values, other keys and everything else are left exactly as they are`() {
        val prefs = StrictPrefs()
        val untouched = mapOf<String, Any>(
            userChosen to true,
            SyncPreferences.sendCalendarInvitesKey(account) to false,
            SyncPreferences.KEY_VERBOSE_LOGGING to true,
            // Text stays text exactly, even when it looks like something else: only switches are read as true/false.
            SyncPreferences.calendarSyncModeKey(account) to " true ",
            SyncPreferences.eventLoopIntervalKey(account) to "not a number",
            SyncPreferences.contactFilterRulesKey(account) to """{"rules":[]}""",
            ContactNameMode.key(account) to "NICKNAME",
            SyncPreferences.KEY_APP_LANGUAGE_TAG to "",
            // Not in the allowlist: never looked at, whatever they hold.
            SyncPreferences.KEY_SUBSCRIPTION_STATE to "Active",
            SyncPreferences.KEY_SUBSCRIPTION_EXPIRY to 5L,
            SyncPreferences.KEY_LICENSE_CONFIRMED to "true",
            SyncPreferences.KEY_BACKUP_LAST_TOTAL to 12,
            SyncPreferences.KEY_BACKUP_FOLDER_URI to true,
            SyncPreferences.timeZoneRetimeKey(account) to 3,
            SyncPreferences.lastSyncKey(account, "contacts") to "17",
            SyncPreferences.contactFilterKeptLocalKey(account) to setOf("1", "2"),
            SyncPreferences.eventAttendeesKey("evt1") to setOf("a@b.c"),
            "upload_reject_count_42" to 2,
            "some_other_flag" to "false",
        )
        prefs.fake.values.putAll(untouched)

        val repair = BackupSettings.repairStoredTypes(prefs)

        assertTrue(repair.isEmpty)
        assertEquals(untouched, prefs.fake.values)
        assertEquals("nothing wrong, nothing written: not even an editor", 0, prefs.editsOpened)
    }

    @Test
    fun `a second run finds nothing and writes nothing`() {
        val prefs = StrictPrefs()
        prefs.fake.values[userChosen] = "true"
        prefs.fake.values[SyncPreferences.sendCalendarUpdatesKey(account)] = "maybe"
        prefs.fake.values[SyncPreferences.calendarFutureDaysKey(account)] = 365
        val first = BackupSettings.repairStoredTypes(prefs)
        assertFalse(first.isEmpty)
        val afterFirst = HashMap(prefs.fake.values)
        val editsAfterFirst = prefs.editsOpened

        val second = BackupSettings.repairStoredTypes(prefs)

        assertTrue(second.isEmpty)
        assertEquals(afterFirst, prefs.fake.values)
        assertEquals(editsAfterFirst, prefs.editsOpened)
    }

    @Test
    fun `the name mode's re-apply flag key keeps a name mode and converts true or false`() {
        val prefs = StrictPrefs()
        val flag = ContactNameMode.reapplyKey(account)
        // An account literally called "reapply_<x>" stores its name mode under the flag key of account <x>.
        val modeOfOddlyNamedAccount = ContactNameMode.key("reapply_other@example.com")
        prefs.fake.values[flag] = "true"
        prefs.fake.values[modeOfOddlyNamedAccount] = "GIVEN"

        val repair = BackupSettings.repairStoredTypes(prefs)

        assertTrue(prefs.getBoolean(flag, false))
        assertEquals("GIVEN", prefs.getString(modeOfOddlyNamedAccount, null))
        assertEquals(listOf(flag), repair.converted)
    }

    @Test
    fun `other text under the re-apply flag's key is removed, so the contacts sync can read the flag`() {
        // Kept, ContactNameMode.needsReapply (getBoolean) threw at the start of every contacts sync of the account.
        val prefs = StrictPrefs()
        val flag = ContactNameMode.reapplyKey(account)
        prefs.fake.values[flag] = "yes"

        val repair = BackupSettings.repairStoredTypes(prefs)

        assertFalse(prefs.contains(flag))
        assertFalse(prefs.getBoolean(flag, false))
        assertEquals(listOf(flag), repair.removed)
    }

    @Test
    fun `Application onCreate repairs the settings before anything reads one`() {
        // A source-order guard: the repair only helps if it runs first. The working directory of a unit test
        // is the module's.
        val source = java.io.File("src/main/java/me/proton/sync/ProtonSyncApp.kt").readText().replace("\r\n", "\n")
        // The body of onCreate, without its line comments (they name some of these calls too).
        val onCreate = source.substringAfter("override fun onCreate() {").substringBefore("\n    }\n")
            .lines().joinToString("\n") { it.substringBefore("//") }
        val repair = onCreate.indexOf("repairSettingTypes()")
        assertTrue("onCreate calls the repair", repair >= 0)
        assertTrue(
            "the Bouncy Castle setup stays first",
            onCreate.indexOf("BouncyCastleSetup.ensureInstalled()") in 0 until repair
        )
        for (reader in listOf(
            "applyVerboseLoggingPref()",
            "FileLogController.initFromPrefs(",
            "logTimeZoneSource()",
            "requestRetimeIfIdentityChanged(",
            "BackupScheduleWorker.reschedule(",
            "migrateParentAccountNames()",
            "subscriptionManager.connect()",
            "observeSubscriptionForScheduling()",
        )) {
            assertTrue("$reader runs after the repair", onCreate.indexOf(reader) > repair)
        }
    }

    @Test
    fun `the repair report names keys only, never a value`() {
        val prefs = StrictPrefs()
        prefs.fake.values[userChosen] = "secret-looking value"
        prefs.fake.values[SyncPreferences.fallbackCalendarEnabledKey(account)] = "TRUE"

        val repair = BackupSettings.repairStoredTypes(prefs)

        val everything = repair.converted + repair.assumed + repair.removed
        assertEquals(listOf(SyncPreferences.fallbackCalendarEnabledKey(account), userChosen).sorted(), everything.sorted())
        assertFalse(everything.any { it.contains("secret") || it.contains("TRUE") })
    }
}

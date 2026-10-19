package me.proton.sync.backup

import me.proton.sync.SyncPreferences
import me.proton.sync.backup.BackupSettings.PrefType
import me.proton.sync.contacts.ContactNameMode
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.math.BigInteger

/**
 * A restore writes every setting as the type its readers ask for, or not at all, or as its safe value where
 * the readers' default would do harm (BackupSettings.importValue, writeImported, UNREADABLE_AS). A settings
 * backup without a password is plain JSON a user can edit; until now only eight app-wide switches were
 * checked, so "true" in quotes for a per-account switch was stored as a String, and
 * display_name_user_chosen_<account>, read in Application.onCreate with getBoolean, then threw on every start.
 */
class BackupSettingsTypedImportTest {

    private val account = "me@example.com"

    private val perAccountSwitches = listOf(
        SyncPreferences.displayNameUserChosenKey(account),
        SyncPreferences.displayNameRecoveryOfferedKey(account),
        SyncPreferences.includeExternalCalendarsKey(account),
        SyncPreferences.notifySyncFailureKey("contacts", account),
        SyncPreferences.notifySyncFailureKey("calendar", account),
        SyncPreferences.notifySyncInProgressKey(account),
        SyncPreferences.sendCalendarInvitesKey(account),
        SyncPreferences.sendCalendarUpdatesKey(account),
        SyncPreferences.sendCalendarCancellationsKey(account),
        SyncPreferences.inviteSubjectIncludeTitleKey(account),
        SyncPreferences.fallbackContactsEnabledKey(account),
        SyncPreferences.fallbackCalendarEnabledKey(account),
        SyncPreferences.pollAttendeeStatusKey(account),
        SyncPreferences.contactFilterDirtyKey(account),
    )

    /** Free text. The name mode is text too, but only a mode's name (its own tests below). */
    private val perAccountText = listOf(
        SyncPreferences.calendarSyncModeKey(account),
        SyncPreferences.calendarPastDaysKey(account),
        SyncPreferences.calendarFutureDaysKey(account),
        SyncPreferences.eventLoopIntervalKey(account),
        SyncPreferences.fallbackContactsIntervalKey(account),
        SyncPreferences.fallbackCalendarIntervalKey(account),
        SyncPreferences.contactFilterRulesKey(account),
        SyncPreferences.syncIntervalKey(account, "contacts"),
    )

    // ---- Booleans ----

    @Test
    fun `a per-account switch keeps a JSON boolean`() {
        for (key in perAccountSwitches) {
            assertEquals(key, true, BackupSettings.importValue(key, true))
            assertEquals(key, false, BackupSettings.importValue(key, false))
        }
    }

    @Test
    fun `a per-account switch given true or false in quotes is restored as that Boolean`() {
        for (key in perAccountSwitches) {
            assertEquals(key, true, BackupSettings.importValue(key, "true"))
            assertEquals(key, false, BackupSettings.importValue(key, " False "))
            assertEquals(key, true, BackupSettings.importValue(key, "TRUE"))
        }
    }

    @Test
    fun `a per-account switch given anything else is skipped, never stored as a String`() {
        val garbage = listOf<Any?>(
            "yes", "on", "1", "", "  ", 1, 0, 1L, 1.0, BigDecimal.ONE, JSONObject.NULL, null,
            JSONObject("""{"a":true}"""), JSONArray("[true]"),
        )
        // Except the user-chosen name flag, whose unreadable value is restored as true (its own test below).
        for (key in perAccountSwitches - SyncPreferences.displayNameUserChosenKey(account)) {
            for (bad in garbage) assertNull("$key <- $bad", BackupSettings.importValue(key, bad))
        }
        for (bad in garbage) {
            assertEquals("$bad", true, BackupSettings.importValue(SyncPreferences.displayNameUserChosenKey(account), bad))
        }
    }

    @Test
    fun `the name mode's re-apply flag is a Boolean first, and a mode's name the name mode of an account called reapply_x`() {
        // contact_name_mode_reapply_<x> is the flag of account <x>, and also the name mode of an account
        // literally called "reapply_<x>". "true" and "false" are never a name mode (ContactNameMode.of reads
        // anything it does not know as ANDROID), so they are the flag.
        val key = ContactNameMode.reapplyKey(account)
        assertEquals(true, BackupSettings.importValue(key, true))
        assertEquals(true, BackupSettings.importValue(key, "true"))
        assertEquals(false, BackupSettings.importValue(key, " FALSE "))
        for (mode in ContactNameMode.entries) assertEquals(mode.name, BackupSettings.importValue(key, mode.name))
        assertNull(BackupSettings.importValue(key, JSONObject()))
        assertNull(BackupSettings.importValue(key, JSONObject.NULL))
    }

    @Test
    fun `the name mode's re-apply flag never keeps other text, which its getBoolean reader would throw on`() {
        // Kept as text, "yes" failed every contacts sync of the account until the user picked another name mode
        // (ContactNameMode.needsReapply reads the flag with getBoolean, ContactsSyncAdapter at every sync).
        val key = ContactNameMode.reapplyKey(account)
        for (bad in listOf<Any?>("yes", "1", 1, 0L, 1.5, "", "nickname", " NICKNAME ", "ANDROID ")) {
            assertNull("$bad", BackupSettings.importValue(key, bad))
        }
        val prefs = StrictPrefs()
        val editor = prefs.edit()
        val result = BackupSettings.writeImported(editor, mapOf(key to "yes"))
        editor.apply()
        assertEquals(listOf(key), result.skipped)
        assertFalse(prefs.contains(key))
        assertFalse(prefs.getBoolean(key, false))
    }

    @Test
    fun `a name mode is restored only as a mode's name, which is all its reader knows`() {
        val key = ContactNameMode.key(account)
        for (mode in ContactNameMode.entries) assertEquals(mode.name, BackupSettings.importValue(key, mode.name))
        // ContactNameMode.of reads any other text as ANDROID, so skipping it changes nothing for the account.
        for (bad in listOf<Any?>("nickname", "", "x", 1, true, JSONObject.NULL)) {
            assertNull("$bad", BackupSettings.importValue(key, bad))
        }
        assertEquals("string (ANDROID, FAMILY, GIVEN, NICKNAME)", BackupSettings.describe(key))
        assertEquals(
            "boolean or string (ANDROID, FAMILY, GIVEN, NICKNAME)",
            BackupSettings.describe(ContactNameMode.reapplyKey(account))
        )
    }

    // ---- Text ----

    @Test
    fun `a text setting keeps its text exactly, untrimmed`() {
        for (key in perAccountText) {
            assertEquals(key, "900", BackupSettings.importValue(key, "900"))
            assertEquals(key, " custom ", BackupSettings.importValue(key, " custom "))
            assertEquals(key, "", BackupSettings.importValue(key, ""))
        }
        val rules = """{"rules":[{"field":"EMAIL","op":"CONTAINS","value":"x"}],"revision":3}"""
        assertEquals(rules, BackupSettings.importValue(SyncPreferences.contactFilterRulesKey(account), rules))
    }

    @Test
    fun `a JSON number for a text setting is stored as its text`() {
        // Numbers were always stored as their text, and the day counts and intervals are numbers.
        val days = SyncPreferences.calendarPastDaysKey(account)
        assertEquals("90", BackupSettings.importValue(days, 90))
        assertEquals("3000000000", BackupSettings.importValue(days, 3000000000L))
        assertEquals("7", BackupSettings.importValue(SyncPreferences.KEY_BACKUP_INTERVAL_DAYS, 7))
    }

    @Test
    fun `a JSON boolean for a text setting is skipped, so the default applies`() {
        // Stored as a Boolean (older versions), getString threw on it. Stored as the text "true", it is no
        // value any text setting takes, and the sync mode would read it as a custom window: 180 days back
        // and 365 ahead instead of the default, everything.
        val mode = SyncPreferences.calendarSyncModeKey(account)
        for (key in perAccountText + SyncPreferences.KEY_APP_LANGUAGE_TAG + SyncPreferences.LAST_SYNC_DATE_FORMAT_KEY) {
            assertNull(key, BackupSettings.importValue(key, true))
            assertNull(key, BackupSettings.importValue(key, false))
        }
        val prefs = StrictPrefs()
        val editor = prefs.edit()
        val result = BackupSettings.writeImported(editor, mapOf(mode to true))
        editor.apply()
        assertEquals(listOf(mode), result.skipped)
        assertEquals(
            SyncPreferences.DEFAULT_SYNC_MODE,
            prefs.getString(mode, SyncPreferences.DEFAULT_SYNC_MODE)
        )
    }

    // ---- Unreadable values where the default would do harm ----

    private val userChosen = SyncPreferences.displayNameUserChosenKey("Work")

    @Test
    fun `an unreadable user-chosen name flag is restored as true, so no start renames the account`() {
        // Absent, the next start renames a bare account name ("Work") to its email form
        // (ProtonSyncApp.migrateParentAccountNames), the forced rename this flag exists to prevent.
        for (bad in listOf<Any?>(1, "1", "yes", "null", JSONObject.NULL, null, 1.5, JSONObject(), JSONArray())) {
            assertEquals("$bad", true, BackupSettings.importValue(userChosen, bad))
        }
        // A value that can be read is the user's, false included.
        assertEquals(false, BackupSettings.importValue(userChosen, false))
        assertEquals(false, BackupSettings.importValue(userChosen, " false "))
    }

    @Test
    fun `an unreadable backup encryption switch is restored as on, never as cleartext backups`() {
        val key = SyncPreferences.KEY_BACKUP_ENCRYPTION_ENABLED
        for (bad in listOf<Any?>(1, "1", "on", JSONObject.NULL, null)) {
            assertEquals("$bad", true, BackupSettings.importValue(key, bad))
        }
        assertEquals(false, BackupSettings.importValue(key, false))
        assertEquals(false, BackupSettings.importValue(key, "FALSE"))
    }

    @Test
    fun `every other switch drops an unreadable value, so its readers' default applies`() {
        val others = perAccountSwitches.filter { it != SyncPreferences.displayNameUserChosenKey(account) } + listOf(
            SyncPreferences.KEY_BACKUP_ENABLED,
            SyncPreferences.KEY_VERBOSE_LOGGING,
            SyncPreferences.KEY_TIMEZONE_UPDATES,
            ContactNameMode.reapplyKey(account),
        )
        for (key in others) {
            assertNull(key, BackupSettings.importValue(key, "1"))
            assertNull(key, BackupSettings.importValue(key, JSONObject.NULL))
        }
    }

    @Test
    fun `a restore reports a safe value by key, apart from the values it skipped`() {
        val prefs = StrictPrefs()
        val editor = prefs.edit()
        val result = BackupSettings.writeImported(
            editor,
            mapOf(
                userChosen to "1",
                SyncPreferences.KEY_BACKUP_ENCRYPTION_ENABLED to JSONObject.NULL,
                SyncPreferences.displayNameRecoveryOfferedKey("Work") to "1",
                SyncPreferences.KEY_BACKUP_ENABLED to true,
            )
        )
        editor.apply()

        assertEquals(3, result.written)
        assertEquals(setOf(userChosen, SyncPreferences.KEY_BACKUP_ENCRYPTION_ENABLED), result.assumed.toSet())
        assertEquals(listOf(SyncPreferences.displayNameRecoveryOfferedKey("Work")), result.skipped)
        assertTrue(prefs.getBoolean(userChosen, false))
        assertTrue(prefs.getBoolean(SyncPreferences.KEY_BACKUP_ENCRYPTION_ENABLED, false))
        assertFalse(prefs.contains(SyncPreferences.displayNameRecoveryOfferedKey("Work")))
    }

    @Test
    fun `an object, an array or a null for a text setting is skipped`() {
        for (key in perAccountText) {
            for (bad in listOf<Any?>(JSONObject("""{"rules":[]}"""), JSONArray("[1]"), JSONObject.NULL, null)) {
                assertNull("$key <- $bad", BackupSettings.importValue(key, bad))
            }
        }
    }

    // ---- Whole numbers (no exportable key is one today; declared for the next one) ----

    @Test
    fun `an Int takes whole numbers in range, as JSON numbers or as text`() {
        val t = PrefType.INT
        assertEquals(7, BackupSettings.coerce(7, t))
        assertEquals(7, BackupSettings.coerce(7L, t))
        assertEquals(7, BackupSettings.coerce(7.0, t))
        assertEquals(7, BackupSettings.coerce(BigDecimal("7.000"), t))
        assertEquals(7, BackupSettings.coerce(BigInteger.valueOf(7), t))
        assertEquals(7, BackupSettings.coerce(" 7 ", t))
        assertEquals(5, BackupSettings.coerce("+5", t))
        assertEquals(-3, BackupSettings.coerce("-3", t))
        assertEquals(7, BackupSettings.coerce("7.0", t))
        assertEquals(1000, BackupSettings.coerce("1e3", t))
        assertEquals(Int.MAX_VALUE, BackupSettings.coerce(Int.MAX_VALUE.toLong(), t))
        assertEquals(Int.MIN_VALUE, BackupSettings.coerce(Int.MIN_VALUE.toString(), t))
    }

    @Test
    fun `an Int refuses numbers out of range, fractions and anything that is not a number`() {
        val t = PrefType.INT
        val bad = listOf<Any?>(
            Int.MAX_VALUE.toLong() + 1, Int.MIN_VALUE.toLong() - 1, "2147483648", 3e9,
            7.5, "7.5", BigDecimal("7.5"), Double.NaN, Double.POSITIVE_INFINITY,
            true, "true", "seven", "", "7 days", "0x10", null, JSONObject.NULL, JSONObject(), JSONArray(),
        )
        for (v in bad) assertNull("$v", BackupSettings.coerce(v, t))
    }

    @Test
    fun `a Long takes its whole range and nothing past it`() {
        val t = PrefType.LONG
        assertEquals(3000000000L, BackupSettings.coerce(3000000000L, t))
        assertEquals(3000000000L, BackupSettings.coerce(3000000000, t))
        assertEquals(7L, BackupSettings.coerce(7, t))
        assertEquals(Long.MAX_VALUE, BackupSettings.coerce("9223372036854775807", t))
        assertEquals(Long.MIN_VALUE, BackupSettings.coerce(-9.223372036854775808E18, t))
        assertEquals(1000000000000000000L, BackupSettings.coerce(1e18, t))
        for (v in listOf<Any?>(
            "9223372036854775808", BigInteger("9223372036854775808"), 9.223372036854775807E18,
            BigDecimal("9223372036854775808"), "1e19", 0.5, "0.5", false, "x", null,
        )) {
            assertNull("$v", BackupSettings.coerce(v, t))
        }
    }

    @Test(timeout = 2000)
    fun `an absurd exponent is refused without being expanded`() {
        for (t in listOf(PrefType.INT, PrefType.LONG)) {
            assertNull(BackupSettings.coerce("1e999999999", t))
            assertNull(BackupSettings.coerce("1e-999999999", t))
            assertNull(BackupSettings.coerce(BigDecimal("1e999999999"), t))
            assertNull(BackupSettings.coerce(BigDecimal("1e-999999999"), t))
            assertEquals(0L, (BackupSettings.coerce(BigDecimal("0e999999999"), t) as Number).toLong())
        }
    }

    // ---- The whole import ----

    /** The settings of a cleartext backup file, read the way ImportManager.decryptPayload reads them. */
    private fun settingsOf(fileText: String): Map<String, Any?> =
        BackupPayload.fromJson(JSONObject(fileText).getJSONObject("payload")).settings

    /**
     * "The finding" is the bug the class comment describes. This file is a backup edited by hand the same way:
     * a per-account switch as "true" in quotes, and the other kinds of value a hand edit can leave.
     */
    @Test
    fun `the edited backup from the finding restores and every reader can read it`() {
        val file = """
            {"format":"sync-for-proton-backup","version":1,"encrypted":false,"payload":{"settings":{
              "display_name_user_chosen_$account":"true",
              "fallback_contacts_enabled_$account":" false ",
              "send_calendar_invites_$account":"yes",
              "calendar_past_days_$account":90,
              "event_loop_interval_$account":"900",
              "verbose_logging_enabled":true,
              "contact_filter_rules_$account":{"rules":[]},
              "paddle_subscription_id":"sub_123",
              "notify_sync_in_progress_$account":null
            },"accounts":[]}}
        """.trimIndent()
        val prefs = StrictPrefs()
        val editor = prefs.edit()
        val result = BackupSettings.writeImported(editor, settingsOf(file))
        editor.apply()

        // What Application.onCreate and the account cards read: no ClassCastException, the meant values.
        assertTrue(prefs.getBoolean(SyncPreferences.displayNameUserChosenKey(account), false))
        assertFalse(prefs.getBoolean(SyncPreferences.fallbackContactsEnabledKey(account), true))
        assertTrue(prefs.getBoolean(SyncPreferences.KEY_VERBOSE_LOGGING, false))
        assertEquals("90", prefs.getString(SyncPreferences.calendarPastDaysKey(account), null))
        assertEquals("900", prefs.getString(SyncPreferences.eventLoopIntervalKey(account), null))
        // Skipped, so the readers' defaults apply.
        assertFalse(prefs.getBoolean(SyncPreferences.sendCalendarInvitesKey(account), false))
        assertFalse(prefs.contains(SyncPreferences.sendCalendarInvitesKey(account)))
        assertFalse(prefs.contains(SyncPreferences.contactFilterRulesKey(account)))
        assertFalse(prefs.contains(SyncPreferences.notifySyncInProgressKey(account)))
        assertFalse(prefs.contains(SyncPreferences.KEY_PADDLE_SUB_ID))

        assertEquals(5, result.written)
        assertEquals(
            setOf(SyncPreferences.sendCalendarInvitesKey(account), SyncPreferences.contactFilterRulesKey(account)),
            result.skipped.toSet()
        )
        assertTrue(result.assumed.isEmpty())
    }

    @Test
    fun `an unedited backup restores every setting with the type it was exported with`() {
        // Every declared key, holding a value of its type, the way the app stores it.
        val source = StrictPrefs()
        val e = source.edit()
        for ((key, type) in BackupSettings.EXACT_KEYS) putSample(e, key, type, key)
        for ((prefix, type) in BackupSettings.PREFIXES) putSample(e, prefix + account, type, prefix)
        e.putLong(SyncPreferences.KEY_SUBSCRIPTION_EXPIRY, 1L).putInt(SyncPreferences.KEY_BACKUP_LAST_TOTAL, 3)
        e.apply()

        // ExportManager.gatherSettings, the file, and ImportManager's reading of it.
        val exported = source.all.filterKeys { BackupSettings.isExportable(it) }
        val fileText = JSONObject()
            .put("payload", BackupPayload(exported, emptyList()).toJson())
            .toString(2)
        val target = StrictPrefs()
        val editor = target.edit()
        val result = BackupSettings.writeImported(editor, settingsOf(fileText))
        editor.apply()

        assertTrue(result.skipped.isEmpty())
        assertTrue(result.assumed.isEmpty())
        assertEquals(exported, target.fake.values)
        for ((key, value) in exported) assertEquals(key, value!!.javaClass, target.fake.values[key]!!.javaClass)
    }

    /** A value of [type] for [key], one of its declaration's choices where it has them (the name mode). */
    private fun putSample(e: android.content.SharedPreferences.Editor, key: String, type: PrefType, declared: String) {
        when (type) {
            PrefType.BOOLEAN -> e.putBoolean(key, true)
            PrefType.INT -> e.putInt(key, 7)
            PrefType.LONG -> e.putLong(key, 3000000000L)
            PrefType.STRING -> e.putString(key, BackupSettings.TEXT_CHOICES[declared]?.min() ?: "v $key")
        }
    }
}

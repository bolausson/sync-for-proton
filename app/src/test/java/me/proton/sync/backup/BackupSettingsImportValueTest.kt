package me.proton.sync.backup

import me.proton.sync.SyncPreferences
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What a settings import may write (BackupSettings.importValue). A backup without a password is plain
 * JSON a user can edit, and every reader of a Boolean key uses getBoolean, which on Android throws
 * ClassCastException for a String. Three such keys are read in Application.onCreate, so a String there
 * stopped every start of the app until its data was cleared.
 */
class BackupSettingsImportValueTest {

    private val booleanKeys = listOf(
        SyncPreferences.KEY_TIMEZONE_UPDATES,
        SyncPreferences.KEY_VERBOSE_LOGGING,
        SyncPreferences.KEY_FILE_LOGGING,
        SyncPreferences.KEY_BACKUP_ENABLED,
        SyncPreferences.KEY_BACKUP_INCLUDE_CALENDARS,
        SyncPreferences.KEY_BACKUP_INCLUDE_CONTACTS,
        SyncPreferences.KEY_BACKUP_ENCRYPTION_ENABLED,
        SyncPreferences.KEY_BACKUP_REMINDER_OPTED_OUT,
    )

    @Test
    fun `a Boolean key keeps a Boolean`() {
        for (key in booleanKeys) {
            assertEquals(key, true, BackupSettings.importValue(key, true))
            assertEquals(key, false, BackupSettings.importValue(key, false))
        }
    }

    @Test
    fun `a Boolean key given true or false in quotes is read as that Boolean`() {
        for (key in booleanKeys) {
            assertEquals(key, false, BackupSettings.importValue(key, "false"))
            assertEquals(key, true, BackupSettings.importValue(key, " TRUE "))
        }
    }

    @Test
    fun `a Boolean key given anything else is skipped, never stored as a String`() {
        // Except the backup encryption switch: unreadable, it is restored as on, because off writes every
        // contact and event in cleartext (BackupSettings.UNREADABLE_AS, BackupSettingsTypedImportTest).
        for (key in booleanKeys - SyncPreferences.KEY_BACKUP_ENCRYPTION_ENABLED) {
            for (bad in listOf<Any?>("yes", "", 1, 0L, 1.0, JSONObject.NULL, null)) {
                assertNull("$key <- $bad", BackupSettings.importValue(key, bad))
            }
        }
        for (bad in listOf<Any?>("yes", "", 1, 0L, 1.0, JSONObject.NULL, null)) {
            assertEquals("$bad", true, BackupSettings.importValue(SyncPreferences.KEY_BACKUP_ENCRYPTION_ENABLED, bad))
        }
    }

    /** "The finding" is the bug the class comment describes: a hand-edited backup with a switch in quotes. */
    @Test
    fun `the edited backup from the finding restores the switch as a Boolean`() {
        val payload = BackupPayload.fromJson(
            JSONObject("""{"settings":{"online_timezone_updates_enabled":"false","verbose_logging_enabled":"oops"}}""")
        )
        assertEquals(false, BackupSettings.importValue(SyncPreferences.KEY_TIMEZONE_UPDATES, payload.settings[SyncPreferences.KEY_TIMEZONE_UPDATES]))
        assertNull(BackupSettings.importValue(SyncPreferences.KEY_VERBOSE_LOGGING, payload.settings[SyncPreferences.KEY_VERBOSE_LOGGING]))
    }

    @Test
    fun `String keys pass through as before, and keys outside the allowlist or JSON nulls are skipped`() {
        assertEquals("7", BackupSettings.importValue(SyncPreferences.KEY_BACKUP_INTERVAL_DAYS, "7"))
        assertEquals("de", BackupSettings.importValue(SyncPreferences.KEY_APP_LANGUAGE_TAG, "de"))
        assertEquals("all", BackupSettings.importValue(SyncPreferences.calendarSyncModeKey("me@example.com"), "all"))
        assertNull(BackupSettings.importValue(SyncPreferences.KEY_PADDLE_SUB_ID, "sub_123"))
        // org.json's JSONObject.NULL is not a Kotlin null; it used to be stored as the string "null".
        assertNull(BackupSettings.importValue(SyncPreferences.KEY_BACKUP_INTERVAL_DAYS, JSONObject.NULL))
    }
}

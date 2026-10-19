package me.proton.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [SyncPreferences] key builders.
 *
 * These tests guard against accidental key format changes that would silently
 * reset existing user preferences (e.g., sync intervals, last sync timestamps).
 */
class SyncPreferencesTest {

    @Test
    fun lastSyncKey_formatsCorrectly() {
        assertEquals(
            "last_contacts_sync_user@example.com",
            SyncPreferences.lastSyncKey("user@example.com", "contacts")
        )
        assertEquals(
            "last_calendar_sync_other@example.com",
            SyncPreferences.lastSyncKey("other@example.com", "calendar")
        )
    }

    @Test
    fun lastSyncFailedKey_formatsCorrectly() {
        assertEquals(
            "last_contacts_sync_failed_user@example.com",
            SyncPreferences.lastSyncFailedKey("user@example.com", "contacts")
        )
        assertEquals(
            "last_calendar_sync_failed_user@example.com",
            SyncPreferences.lastSyncFailedKey("user@example.com", "calendar")
        )
    }

    @Test
    fun syncIntervalKey_formatsCorrectly() {
        assertEquals(
            "sync_interval_contacts_user@example.com",
            SyncPreferences.syncIntervalKey("user@example.com", "contacts")
        )
        assertEquals(
            "sync_interval_calendar_user@example.com",
            SyncPreferences.syncIntervalKey("user@example.com", "calendar")
        )
    }

    @Test
    fun legacySyncIntervalKey_formatsCorrectly() {
        assertEquals(
            "sync_interval_user@example.com",
            SyncPreferences.legacySyncIntervalKey("user@example.com")
        )
    }

    @Test
    fun includeExternalCalendarsKey_formatsCorrectly() {
        assertEquals(
            "include_external_calendars_user@example.com",
            SyncPreferences.includeExternalCalendarsKey("user@example.com")
        )
    }

    @Test
    fun notifySyncFailureKey_formatsCorrectly() {
        assertEquals(
            "notify_sync_failure_contacts_user@example.com",
            SyncPreferences.notifySyncFailureKey("contacts", "user@example.com")
        )
        assertEquals(
            "notify_sync_failure_calendar_user@example.com",
            SyncPreferences.notifySyncFailureKey("calendar", "user@example.com")
        )
    }

    @Test
    fun defaultIntervalSeconds_isOneHour() {
        assertEquals("3600", SyncPreferences.DEFAULT_INTERVAL_SECONDS)
    }

    // ---- Calendar sync range keys ----

    @Test
    fun calendarPastDaysKey_formatsCorrectly() {
        assertEquals(
            "calendar_past_days_user@example.com",
            SyncPreferences.calendarPastDaysKey("user@example.com")
        )
    }

    @Test
    fun calendarFutureDaysKey_formatsCorrectly() {
        assertEquals(
            "calendar_future_days_user@example.com",
            SyncPreferences.calendarFutureDaysKey("user@example.com")
        )
    }

    @Test
    fun lastSyncRangeKey_formatsCorrectly() {
        assertEquals(
            "last_sync_range_user@example.com_cal123",
            SyncPreferences.lastSyncRangeKey("user@example.com", "cal123")
        )
    }

    @Test
    fun eventLoopIdKey_formatsCorrectly() {
        assertEquals(
            "event_loop_id_user@example.com",
            SyncPreferences.eventLoopIdKey("user@example.com")
        )
    }

    @Test
    fun calendarSettingsDirtyKey_formatsCorrectly() {
        assertEquals(
            "calendar_settings_dirty_user@example.com",
            SyncPreferences.calendarSettingsDirtyKey("user@example.com")
        )
    }

    @Test
    fun defaultPastDays_is180() {
        assertEquals(180L, SyncPreferences.DEFAULT_PAST_DAYS)
    }

    @Test
    fun defaultFutureDays_is365() {
        assertEquals(365L, SyncPreferences.DEFAULT_FUTURE_DAYS)
    }

    // ---- Fallback / event loop interval keys (v1.3.x) ----

    @Test
    fun eventLoopIntervalKey_formatsCorrectly() {
        assertEquals(
            "event_loop_interval_user@example.com",
            SyncPreferences.eventLoopIntervalKey("user@example.com")
        )
    }

    @Test
    fun fallbackContactsIntervalKey_formatsCorrectly() {
        assertEquals(
            "fallback_contacts_interval_user@example.com",
            SyncPreferences.fallbackContactsIntervalKey("user@example.com")
        )
    }

    @Test
    fun fallbackContactsEnabledKey_formatsCorrectly() {
        assertEquals(
            "fallback_contacts_enabled_user@example.com",
            SyncPreferences.fallbackContactsEnabledKey("user@example.com")
        )
    }

    @Test
    fun fallbackCalendarIntervalKey_formatsCorrectly() {
        assertEquals(
            "fallback_calendar_interval_user@example.com",
            SyncPreferences.fallbackCalendarIntervalKey("user@example.com")
        )
    }

    @Test
    fun fallbackCalendarEnabledKey_formatsCorrectly() {
        assertEquals(
            "fallback_calendar_enabled_user@example.com",
            SyncPreferences.fallbackCalendarEnabledKey("user@example.com")
        )
    }

    @Test
    fun pollAttendeeStatusKey_formatsCorrectly() {
        assertEquals(
            "poll_attendee_status_user@example.com",
            SyncPreferences.pollAttendeeStatusKey("user@example.com")
        )
    }

    @Test
    fun eventAttendeesKey_formatsCorrectly() {
        assertEquals(
            "event_attendees_evt123abc",
            SyncPreferences.eventAttendeesKey("evt123abc")
        )
    }

    @Test
    fun lastSyncDateFormatKey_isCorrect() {
        assertEquals("last_sync_date_format", SyncPreferences.LAST_SYNC_DATE_FORMAT_KEY)
    }

    @Test
    fun defaultFallbackIntervalSeconds_is43200() {
        assertEquals("43200", SyncPreferences.DEFAULT_FALLBACK_INTERVAL_SECONDS)
    }

    @Test
    fun accountRename_neverMovesTheTimeZoneSwitch_whateverTheAccountIsCalled() {
        // renameAccountInPrefs matches an "_<name>" token followed by "_" or the end, and
        // "online_timezone_updates_enabled" is made of such tokens. Before DEVICE_WIDE_KEYS an account
        // called "updates" carried the device-wide choice off to "online_timezone_Work_enabled".
        for (name in listOf("timezone", "updates", "enabled", "timezone_updates", "updates_enabled", "timezone_updates_enabled")) {
            assertNull(name, SyncPreferences.renamedAccountKey(SyncPreferences.KEY_TIMEZONE_UPDATES, name, "Work"))
        }
    }

    @Test
    fun accountRename_leavesEveryDeviceWideKeyAlone_forEveryTokenItContains() {
        for (key in SyncPreferences.DEVICE_WIDE_KEYS) {
            val tokens = key.split('_')
            // Every run of tokens after the first is a name the pattern could match.
            for (from in 1 until tokens.size) for (to in from + 1..tokens.size) {
                val name = tokens.subList(from, to).joinToString("_")
                assertNull("$key / $name", SyncPreferences.renamedAccountKey(key, name, "Work"))
            }
        }
    }

    @Test
    fun accountRename_stillMovesTheAccountsOwnKeys() {
        assertEquals(
            "send_calendar_invites_Work",
            SyncPreferences.renamedAccountKey(SyncPreferences.sendCalendarInvitesKey("updates"), "updates", "Work")
        )
        assertEquals(
            "last_sync_range_Work_cal1",
            SyncPreferences.renamedAccountKey(SyncPreferences.lastSyncRangeKey("updates", "cal1"), "updates", "Work")
        )
        assertEquals(
            "last_contacts_sync_Work",
            SyncPreferences.renamedAccountKey(SyncPreferences.lastSyncKey("enabled", "contacts"), "enabled", "Work")
        )
        // Another account's key, and a name that is only a prefix of a token, are not touched.
        assertNull(SyncPreferences.renamedAccountKey(SyncPreferences.sendCalendarInvitesKey("other"), "updates", "Work"))
        assertNull(SyncPreferences.renamedAccountKey(SyncPreferences.sendCalendarInvitesKey("updates2"), "updates", "Work"))
    }

    @Test
    fun accountRename_insertsTheNewNameLiterally() {
        // Regex.replace reads "$1" in a replacement as a group reference and threw on such a name.
        assertEquals(
            "sync_paused_until_A\$1\\b",
            SyncPreferences.renamedAccountKey(SyncPreferences.syncPausedUntilKey("old"), "old", "A\$1\\b")
        )
    }

    @Test
    fun deviceWideKeys_includeEveryKeyConstant() {
        // Every `const val KEY_...` in SyncPreferences is device-wide (account keys come from functions).
        // A new one missing from DEVICE_WIDE_KEYS would be renamed along with an account of the right name.
        val constants = SyncPreferences::class.java.declaredFields
            .filter { java.lang.reflect.Modifier.isStatic(it.modifiers) && it.type == String::class.java }
            .filter { it.name.startsWith("KEY_") || it.name == "LAST_SYNC_DATE_FORMAT_KEY" }
            .map { it.isAccessible = true; it.get(null) as String }
        assertTrue(constants.size >= 30)
        assertEquals(emptyList<String>(), constants.filter { it !in SyncPreferences.DEVICE_WIDE_KEYS })
    }

    @Test
    fun timeZoneUpdatesKey_isCorrect_andDefaultsToOff() {
        // The literal is in users' settings backups (BackupSettings.EXACT_KEYS); renaming it would drop the
        // choice on import. The default is a privacy promise: nothing goes to tzurl.org unless switched on.
        assertEquals("online_timezone_updates_enabled", SyncPreferences.KEY_TIMEZONE_UPDATES)
        assertEquals(false, SyncPreferences.DEFAULT_TIMEZONE_UPDATES)
    }
}

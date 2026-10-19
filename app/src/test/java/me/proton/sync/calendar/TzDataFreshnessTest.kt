package me.proton.sync.calendar

import me.proton.sync.SyncPreferences
import me.proton.sync.backup.BackupSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the phone's own tz data counts as stuck: the notice and the hint under "Update time zones online". */
class TzDataFreshnessTest {

    @Test
    fun `two calendar years behind the clock, or behind the 2026 floor, is outdated`() {
        assertTrue(TzDataFreshness.isOutdated("2021a", 2026))     // the Note 10+'s files
        assertFalse(TzDataFreshness.isOutdated("2026c", 2026))    // the S23
        assertTrue(TzDataFreshness.isOutdated("2024b", 2026))
        assertFalse(TzDataFreshness.isOutdated("2025b", 2026))
        assertTrue(TzDataFreshness.isOutdated("2025b", 2027))     // a phone stuck on 2025 data from 1 January 2027
        assertFalse(TzDataFreshness.isOutdated("2026c", 2027))
        assertTrue(TzDataFreshness.isOutdated("2026c", 2028))
    }

    @Test
    fun `a clock set in the past does not hide old data`() {
        assertTrue(TzDataFreshness.isOutdated("2021a", 2020))
        assertTrue(TzDataFreshness.isOutdated("2024a", 1970))
    }

    @Test
    fun `an unknown or unreadable version is never outdated`() {
        for (v in listOf(null, "", "unknown", "2026", "26c", "2026C", "v2026c", "2026-c")) {
            assertFalse("$v", TzDataFreshness.isOutdated(v, 2030))
        }
        assertNull(TzDataFreshness.year("garbage"))
        assertEquals(2021, TzDataFreshness.year(" 2021a "))
    }

    @Test
    fun `the notice's state describes this phone - never exported, never moved by an account rename`() {
        for (key in listOf(
            SyncPreferences.KEY_TZ_OUTDATED_NOTICE_ANSWER, SyncPreferences.KEY_TZ_OUTDATED_NOTICE_LAST_SHOWN_MS,
            SyncPreferences.KEY_TZ_RETIME_REQUESTED_IDENTITY, SyncPreferences.KEY_TZURL_SEEN_VERSION,
            SyncPreferences.KEY_DEBUG_TZDATA_VERSION_OVERRIDE, SyncPreferences.timeZoneRetimeKey("alice")
        )) {
            assertFalse(key, BackupSettings.isExportable(key))
        }
        for (key in listOf(SyncPreferences.KEY_TZ_OUTDATED_NOTICE_ANSWER, SyncPreferences.KEY_TZ_OUTDATED_NOTICE_LAST_SHOWN_MS,
            SyncPreferences.KEY_TZ_RETIME_REQUESTED_IDENTITY, SyncPreferences.KEY_TZURL_SEEN_VERSION)) {
            assertTrue(key, key in SyncPreferences.DEVICE_WIDE_KEYS)
            assertNull(key, SyncPreferences.renamedAccountKey(key, "notice", "Work"))
        }
        // The account's own re-time state follows the account.
        assertEquals("tz_retime_Work", SyncPreferences.renamedAccountKey(SyncPreferences.timeZoneRetimeKey("old"), "old", "Work"))
    }
}

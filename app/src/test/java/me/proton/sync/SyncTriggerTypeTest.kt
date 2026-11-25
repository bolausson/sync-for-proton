package me.proton.sync

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What a sync run logs as its trigger (SyncTriggerType.from, which fromExtras reads a Bundle through). The calendar sync
 * asked for after a calendar setting changed has its own label since 2.2.0, so a log tells it from a scheduled sync; a
 * tap, a catch-up and the forced re-syncs still win over it. The keys are written out: ContentResolver's constants are
 * "force", "upload" and "initialize", and the app's own are pinned against the same text.
 */
class SyncTriggerTypeTest {

    private fun of(vararg flags: String) = SyncTriggerType.from { it in flags }

    @Test
    fun `the keys are the ones the requests set`() {
        assertEquals("settingsChanged", SyncConstants.EXTRA_SETTINGS_CHANGED)
        assertEquals("userSyncNow", SyncConstants.EXTRA_USER_SYNC_NOW)
        assertEquals("catchUp", SyncConstants.EXTRA_CATCH_UP)
        assertEquals("scheduledByWorker", SyncConstants.EXTRA_SCHEDULED_BY_WORKER)
    }

    @Test
    fun `a settings change is its own trigger`() {
        assertEquals(SyncTriggerType.SettingsChangeSync, of("settingsChanged"))
        assertEquals("Sync after a settings change", SyncTriggerType.SettingsChangeSync.label)
        // Its request id is read by nothing.
        assertEquals(SyncTriggerType.SettingsChangeSync, of("settingsChanged", "settingsChangeId"))
    }

    @Test
    fun `a tap, a catch-up and a forced re-sync still win over a settings change`() {
        assertEquals(SyncTriggerType.UserSyncNow, of("settingsChanged", "userSyncNow"))
        assertEquals(SyncTriggerType.CatchUpSync, of("settingsChanged", "catchUp"))
        assertEquals(SyncTriggerType.ManualForceLocalToRemote, of("settingsChanged", "forceLocalToRemote"))
        assertEquals(SyncTriggerType.ManualForceRemoteToLocal, of("settingsChanged", "forceRemoteToLocal"))
        assertEquals(SyncTriggerType.InitialSync, of("settingsChanged", "initialize"))
    }

    @Test
    fun `a settings change wins over the watchdog's flag and a manual request`() {
        assertEquals(SyncTriggerType.SettingsChangeSync, of("settingsChanged", "scheduledByWorker"))
        assertEquals(SyncTriggerType.SettingsChangeSync, of("settingsChanged", "force"))
    }

    @Test
    fun `the watchdog, a manual request and no flag at all`() {
        assertEquals(SyncTriggerType.ScheduledSync, of("scheduledByWorker"))
        assertEquals(SyncTriggerType.ManualSync, of("force"))
        assertEquals(SyncTriggerType.ScheduledSync, of())
        assertEquals("a manual upload is the Local to Remote re-sync", SyncTriggerType.ManualForceLocalToRemote, of("force", "upload"))
    }
}

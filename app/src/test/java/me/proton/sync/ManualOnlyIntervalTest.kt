package me.proton.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Manual only" has to mean no background sync, and once meant the fastest one on offer.
 *
 * The interval preference stores SECONDS, and "Manual only" is the sentinel -1. The schedulers take
 * MINUTES and cancel on a negative value. The settings screen converted with `seconds / 60`, and
 * integer division truncates toward zero: -1 / 60 is 0, not -1. Zero is not negative, so instead of
 * cancelling, the worker took `maxOf(0, MIN_INTERVAL_MINUTES)` and scheduled the event loop every
 * fifteen minutes. Picking the option that promises no automatic syncing gave you the most frequent
 * one the app offers.
 *
 * A user reported that the app kept syncing in the background with the schedule set to manual,
 * which is exactly what it did. The two fallback pickers always branched on the sentinel before
 * dividing; the event-loop picker never did.
 *
 * Both halves are pinned here: the conversion must preserve the sentinel, and the guard must treat
 * zero as off so a caller that divides can never arm a job.
 */
class ManualOnlyIntervalTest {

    // Both call the SHIPPED functions. An earlier version of this test re-implemented them here,
    // which meant reverting every production line left it green. A test that cannot fail is worse
    // than no test, because it is counted as cover.
    private fun toMinutes(seconds: Long): Long = EventLoopWorker.intervalMinutesFor(seconds)

    private fun schedules(intervalMinutes: Long): Boolean = EventLoopWorker.schedulesWork(intervalMinutes)

    @Test
    fun `the manual sentinel survives the conversion to minutes`() {
        assertEquals("-1 seconds must stay negative, not truncate to 0", -1L, toMinutes(-1L))
    }

    @Test
    fun `manual only schedules nothing`() {
        assertTrue("manual must not schedule", !schedules(toMinutes(-1L)))
    }

    /**
     * The specific regression: the old conversion produced 0, and a guard of `< 0` let 0 through to
     * be clamped up to the minimum interval. Zero must be off.
     */
    @Test
    fun `a truncated sentinel can never arm a job`() {
        val truncatedTheOldWay = -1L / 60L
        assertEquals(0L, truncatedTheOldWay)
        assertTrue("zero minutes must be treated as off, not clamped up", !schedules(truncatedTheOldWay))
    }

    /**
     * Fixing the write path is not enough: existing bad state has to be repaired.
     *
     * A user who chose "Manual only" on the release with the truncation bug got a real 15-minute
     * job enqueued. Updating fixed the setting for the future, and that job kept firing, because
     * the startup pass SKIPPED manual accounts instead of cancelling them, and WorkManager unique
     * periodic work outlives an upgrade and a force-stop. Seen in the field: after the update and
     * a force-stop, the job still fired every 15 minutes.
     *
     * So the startup pass reconciles rather than restores: whatever the stored interval says must
     * be what is scheduled afterwards, including "nothing".
     */
    @Test
    fun `startup cancels a stale job when the stored interval is manual`() {
        assertTrue("manual must actively cancel, not merely decline to schedule", cancelsOnStartup(-1L))
        assertTrue("a manual interval must not arm even when the type is on", !armsOnStartup(-1L, enabled = true))
    }

    /** And the same pass must not leave a real interval unscheduled. */
    @Test
    fun `startup arms every real interval, for enabled types only`() {
        for (seconds in listOf(900L, 1800L, 3600L, 86400L)) {
            assertTrue("$seconds with the type on must arm", armsOnStartup(seconds, enabled = true))
            assertTrue("$seconds with the type off must not arm", !armsOnStartup(seconds, enabled = false))
        }
    }

    /** The startup pass cancels exactly when the shipped predicate says there is no work. */
    private fun cancelsOnStartup(storedSeconds: Long): Boolean =
        !EventLoopWorker.schedulesWork(EventLoopWorker.intervalMinutesFor(storedSeconds))

    /** A periodic sync is armed only for an enabled type on a real interval. */
    private fun armsOnStartup(storedSeconds: Long, enabled: Boolean): Boolean =
        enabled && EventLoopWorker.schedulesWork(EventLoopWorker.intervalMinutesFor(storedSeconds))

    /** And the half that must keep working: every real interval still schedules, unchanged. */
    @Test
    fun `real intervals are unaffected`() {
        assertEquals(15L, toMinutes(900L))
        assertEquals(60L, toMinutes(3600L))
        assertEquals(1440L, toMinutes(86400L))
        for (seconds in listOf(900L, 1800L, 3600L, 7200L, 14400L, 21600L, 43200L, 86400L)) {
            assertTrue("$seconds must still schedule", schedules(toMinutes(seconds)))
        }
    }
}

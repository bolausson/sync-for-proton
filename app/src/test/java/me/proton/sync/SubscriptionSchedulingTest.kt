package me.proton.sync

import me.proton.sync.SyncScheduling.Action.ARM
import me.proton.sync.SyncScheduling.Action.ARM_AND_CATCH_UP
import me.proton.sync.SyncScheduling.Action.DISARM
import me.proton.sync.SyncScheduling.Action.NONE
import me.proton.sync.billing.SubscriptionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the subscription observer arms, cancels, and owes a catch-up sync.
 *
 * Until 2.0.2 the observer only re-armed schedules when the subscription turned active. Every sync the gate
 * had declined meanwhile was simply lost: the setup chooser's first sync for an account signed in before the
 * licence check answered, a restore's first syncs before its subscription id was accepted. The account then
 * sat empty until its first scheduled sync, an hour later, or never for "Manual only". The catch-up must not
 * fire on a plain start of an active install, though, or every app start would sync.
 *
 * These drive the SHIPPED SyncScheduling.Tracker through the start sequences each flavour produces.
 */
class SubscriptionSchedulingTest {

    private val p = "sync_for_proton_monthly"

    private fun actions(activeAtStart: Boolean, vararg states: SubscriptionState) =
        SyncScheduling.Tracker(activeAtStart).let { t -> states.map { t.next(it) } }

    @Test
    fun `a plain start of an active install arms and does not sync`() {
        assertEquals("a cached Active only re-arms: no sync on every app start", listOf(ARM), actions(true, SubscriptionState.Active(1L, p)))
        assertEquals("a cached Trial only re-arms", listOf(ARM), actions(true, SubscriptionState.Trial(1L, p)))
        assertEquals(
            "play's Active(0) after a cached pre-2.0.1 expiry, and a past-due Active, change nothing",
            listOf(ARM, NONE, NONE),
            actions(true, SubscriptionState.Active(1L, p), SubscriptionState.Active(0L, p), SubscriptionState.Active(0L, p, paymentFailed = true)),
        )
    }

    @Test
    fun `the first start of an install waits for an answer, then cancels`() {
        assertEquals(
            "Unknown must not thrash the schedules; the direct refresh without an id cancels",
            listOf(NONE, DISARM),
            actions(false, SubscriptionState.Unknown, SubscriptionState.NoSubscription),
        )
    }

    @Test
    fun `a direct activation catches up`() {
        assertEquals(
            "a pasted or restored id that the licence check accepts owes the syncs the gate dropped",
            listOf(NONE, DISARM, ARM_AND_CATCH_UP),
            actions(false, SubscriptionState.Unknown, SubscriptionState.NoSubscription, SubscriptionState.Trial(1L, p)),
        )
    }

    @Test
    fun `a purchase after a cached lapse catches up once`() {
        assertEquals(
            "onPurchasesUpdated catches up, and the refreshState after it emits the same verdict, so nothing",
            listOf(DISARM, ARM_AND_CATCH_UP, NONE),
            actions(false, SubscriptionState.Expired(p), SubscriptionState.Active(0L, p), SubscriptionState.Active(0L, p)),
        )
    }

    @Test
    fun `a play first start where billing finds a purchase catches up`() {
        assertEquals(
            "the gate was closed until billing answered, so a sign-in meanwhile had its sync dropped",
            listOf(NONE, ARM_AND_CATCH_UP),
            actions(false, SubscriptionState.Unknown, SubscriptionState.Active(0L, p)),
        )
    }

    @Test
    fun `a conflated first value still tells activation from a plain start`() {
        assertEquals(
            "the cached state was inactive, so an already active first value is an activation",
            listOf(ARM_AND_CATCH_UP),
            actions(false, SubscriptionState.Active(0L, p)),
        )
        assertEquals("an inactive first value cancels whatever was cached", listOf(DISARM), actions(true, SubscriptionState.Expired(p)))
    }

    @Test
    fun `a lapse and a renewal in one process cancel, then arm and catch up`() {
        assertEquals(
            listOf(ARM, DISARM, ARM_AND_CATCH_UP),
            actions(
                true,
                SubscriptionState.Active(1L, p),
                SubscriptionState.Expired(p, paymentFailed = true),
                SubscriptionState.Active(1L, p),
            ),
        )
    }

    @Test
    fun `grace and paused are inactive`() {
        assertEquals(
            "only Trial and Active let sync run; Grace then a pause is one inactive stretch",
            listOf(ARM, DISARM, NONE),
            actions(true, SubscriptionState.Active(1L, p), SubscriptionState.Grace(1L, p), SubscriptionState.Expired(p, paused = true)),
        )
    }

    @Test
    fun `an Unknown in the middle changes nothing`() {
        assertEquals(
            "Unknown is no verdict, so the Active after it is the same as before it",
            listOf(ARM, NONE, NONE),
            actions(true, SubscriptionState.Active(1L, p), SubscriptionState.Unknown, SubscriptionState.Active(1L, p)),
        )
    }

    @Test
    fun `Manual only gets only the first sync the gate dropped, and a switched-off type nothing`() {
        fun manualOnly(masterSyncOn: Boolean, everSynced: Boolean, paused: Boolean = false) =
            SyncScheduling.catchUpRequest(-1L, true, masterSyncOn = masterSyncOn, everSynced = everSynced, paused = paused)
        for (master in listOf(true, false)) {
            assertEquals(
                "a restore's or the setup chooser's MANUAL first sync would have run on Manual only (master=$master)",
                SyncScheduling.CatchUp.MANUAL,
                manualOnly(master, everSynced = false),
            )
            assertEquals("Manual only promises no sync the user did not start (master=$master)", SyncScheduling.CatchUp.NONE, manualOnly(master, everSynced = true))
            assertEquals("MANUAL would get through the pause after an auth failure (master=$master)", SyncScheduling.CatchUp.NONE, manualOnly(master, everSynced = false, paused = true))
            assertEquals(
                "a first sync that ran and failed was not dropped by the gate, so nothing is owed (master=$master)",
                SyncScheduling.CatchUp.NONE,
                SyncScheduling.catchUpRequest(-1L, true, masterSyncOn = master, everSynced = false, paused = false, everAttempted = true),
            )
            assertEquals(
                "a switched-off type never syncs (master=$master)",
                SyncScheduling.CatchUp.NONE,
                SyncScheduling.catchUpRequest(-1L, false, masterSyncOn = master, everSynced = false, paused = false),
            )
        }
        assertEquals("the shortest interval catches up too", SyncScheduling.CatchUp.EXPEDITED, SyncScheduling.catchUpRequest(900L, true, masterSyncOn = true, everSynced = true, paused = false))
    }

    @Test
    fun `with Android's automatic sync on, the catch-up is expedited and never manual`() {
        fun on(everSynced: Boolean, paused: Boolean) = SyncScheduling.catchUpRequest(3600L, true, masterSyncOn = true, everSynced = everSynced, paused = paused)
        assertEquals(SyncScheduling.CatchUp.EXPEDITED, on(everSynced = true, paused = false))
        assertEquals("a first sync needs no MANUAL while Android runs automatic syncs", SyncScheduling.CatchUp.EXPEDITED, on(everSynced = false, paused = false))
        assertEquals("a paused account is left to the adapter's own pause check", SyncScheduling.CatchUp.EXPEDITED, on(everSynced = false, paused = true))
        assertEquals(SyncScheduling.CatchUp.NONE, SyncScheduling.catchUpRequest(-1L, true, masterSyncOn = true, everSynced = true, paused = false))
        assertEquals(SyncScheduling.CatchUp.NONE, SyncScheduling.catchUpRequest(3600L, false, masterSyncOn = true, everSynced = false, paused = false))
    }

    @Test
    fun `with Android's automatic sync off, only a first sync is asked for, as manual`() {
        fun off(interval: Long = 3600L, switchedOn: Boolean = true, everSynced: Boolean, paused: Boolean = false) =
            SyncScheduling.catchUpRequest(interval, switchedOn, masterSyncOn = false, everSynced = everSynced, paused = paused)
        assertEquals(
            "the setup chooser's MANUAL first sync would have run with automatic sync off, so the catch-up runs it",
            SyncScheduling.CatchUp.MANUAL,
            off(everSynced = false),
        )
        assertEquals("a type that synced before waits for automatic sync, as before 2.0.2", SyncScheduling.CatchUp.NONE, off(everSynced = true))
        assertEquals("MANUAL would get through the pause after an auth failure", SyncScheduling.CatchUp.NONE, off(everSynced = false, paused = true))
        assertEquals("Manual only promises no sync the user did not start", SyncScheduling.CatchUp.NONE, off(interval = -1L, everSynced = true))
        assertEquals("but its dropped first sync is owed", SyncScheduling.CatchUp.MANUAL, off(interval = -1L, everSynced = false))
        assertEquals("a switched-off type never syncs", SyncScheduling.CatchUp.NONE, off(switchedOn = false, everSynced = false))
    }

    @Test
    fun `a periodic sync needs the subscription, the switch and a real interval`() {
        assertFalse(
            "an inactive subscription arms nothing: the gate would decline every wakeup",
            SyncUtils.periodicSyncWanted(enabled = true, seconds = 3600, syncAllowed = false),
        )
        assertTrue(SyncUtils.periodicSyncWanted(enabled = true, seconds = 3600, syncAllowed = true))
        assertFalse("Manual only arms nothing", SyncUtils.periodicSyncWanted(enabled = true, seconds = -1, syncAllowed = true))
        assertFalse("a switched-off type arms nothing", SyncUtils.periodicSyncWanted(enabled = false, seconds = 3600, syncAllowed = true))
    }
}

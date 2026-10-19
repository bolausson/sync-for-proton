package me.proton.sync.billing

import me.proton.sync.SyncPreferences
import me.proton.sync.contacts.FakePrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ScheduledChangeStoreTest {

    private val cancel = ScheduledChange(ScheduledChange.Action.CANCEL, 1_800_000_000_000L)
    private val pause = ScheduledChange(ScheduledChange.Action.PAUSE, 1_800_000_000_000L)

    private fun stored(state: SubscriptionState, prefs: FakePrefs = FakePrefs()): FakePrefs {
        val editor = prefs.edit()
        ScheduledChangeStore.write(editor, state)
        editor.apply()
        return prefs
    }

    @Test
    fun `a trial's and an active subscription's change survive a restart`() {
        assertEquals(cancel, ScheduledChangeStore.read(stored(SubscriptionState.Trial(1L, "p", scheduledChange = cancel))))
        assertEquals(pause, ScheduledChangeStore.read(stored(SubscriptionState.Active(1L, "p", scheduledChange = pause))))
        val prefs = stored(SubscriptionState.Active(1L, "p", paymentFailed = true, scheduledChange = cancel))
        assertEquals("cancel", prefs.values[SyncPreferences.KEY_SUBSCRIPTION_SCHEDULED_CHANGE])
        assertEquals(1_800_000_000_000L, prefs.values[SyncPreferences.KEY_SUBSCRIPTION_SCHEDULED_CHANGE_AT])
    }

    @Test
    fun `a state without a change removes the record, whatever the state`() {
        for (state in listOf(
            SubscriptionState.Active(1L, "p"),
            SubscriptionState.Trial(1L, "p"),
            SubscriptionState.Expired("p", paused = true),
            SubscriptionState.Grace(1L, "p"),
            SubscriptionState.NoSubscription,
            SubscriptionState.Unknown,
        )) {
            val prefs = stored(state, stored(SubscriptionState.Active(1L, "p", scheduledChange = cancel)))
            assertFalse("$state", prefs.contains(SyncPreferences.KEY_SUBSCRIPTION_SCHEDULED_CHANGE))
            assertFalse("$state", prefs.contains(SyncPreferences.KEY_SUBSCRIPTION_SCHEDULED_CHANGE_AT))
            assertNull("$state", ScheduledChangeStore.read(prefs))
        }
    }

    @Test
    fun `a record that cannot be read is no change`() {
        fun prefsWith(action: String?, at: Long?) = FakePrefs().apply {
            action?.let { values[SyncPreferences.KEY_SUBSCRIPTION_SCHEDULED_CHANGE] = it }
            at?.let { values[SyncPreferences.KEY_SUBSCRIPTION_SCHEDULED_CHANGE_AT] = it }
        }
        assertNull(ScheduledChangeStore.read(prefsWith(null, null)))
        assertNull(ScheduledChangeStore.read(prefsWith("CANCEL", 5L)))
        assertNull(ScheduledChangeStore.read(prefsWith("resume", 5L)))
        assertNull(ScheduledChangeStore.read(prefsWith("cancel", 0L)))
        assertNull(ScheduledChangeStore.read(prefsWith("cancel", null)))
        assertEquals(ScheduledChange(ScheduledChange.Action.PAUSE, 5L), ScheduledChangeStore.read(prefsWith("pause", 5L)))
    }

    @Test
    fun `a new subscription id starts without the old one's details`() {
        assertEquals(SubscriptionState.Trial(1L, "p"), SubscriptionState.Trial(1L, "p", scheduledChange = cancel).withoutPaddleDetails())
        assertEquals(
            SubscriptionState.Active(1L, "p"),
            SubscriptionState.Active(1L, "p", paymentFailed = true, scheduledChange = pause).withoutPaddleDetails()
        )
        assertEquals(SubscriptionState.Expired("p"), SubscriptionState.Expired("p", paused = true, paymentFailed = true).withoutPaddleDetails())
        val clean = SubscriptionState.Active(1L, "p")
        assertSame("nothing to drop: the same object", clean, clean.withoutPaddleDetails())
        assertSame(SubscriptionState.NoSubscription, SubscriptionState.NoSubscription.withoutPaddleDetails())
    }

    @Test
    fun `only a trial and an active state carry a change`() {
        assertEquals(cancel, SubscriptionState.Trial(1L, "p", scheduledChange = cancel).scheduledChangeOrNull())
        assertEquals(pause, SubscriptionState.Active(1L, "p", scheduledChange = pause).scheduledChangeOrNull())
        assertNull(SubscriptionState.Expired("p").scheduledChangeOrNull())
        assertNull(SubscriptionState.Grace(1L, "p").scheduledChangeOrNull())
    }
}

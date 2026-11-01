package me.proton.sync.billing

import me.proton.sync.SyncPreferences
import me.proton.sync.contacts.FakePrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The play version's stored subscription state, which the sync gate reads and the next start begins from. A held
 * or paused subscription must come back as such after a restart (or the card offers Renew until Billing answers),
 * the flag must never outlive it, and the gate must keep sync off for it.
 */
class PlayStateStoreTest {

    private val p = SubscriptionManager.PRODUCT_ID

    private fun store(prefs: FakePrefs, s: SubscriptionState) {
        val editor = prefs.edit()
        PlayStateStore.write(editor, s, 1L)
        editor.apply()
    }

    @Test
    fun `a suspended subscription reads back suspended, and the gate keeps sync off`() {
        val prefs = FakePrefs()
        store(prefs, SubscriptionState.Expired(p, suspended = true))
        assertEquals(SubscriptionState.Expired(p, suspended = true), PlayStateStore.read(prefs))
        assertEquals("Expired", prefs.values[SyncPreferences.KEY_SUBSCRIPTION_STATE])
        assertFalse(SubscriptionGate.isSyncAllowed(prefs))
    }

    @Test
    fun `the flag never outlives the state it belongs to`() {
        val prefs = FakePrefs()
        store(prefs, SubscriptionState.Expired(p, suspended = true))
        store(prefs, SubscriptionState.Active(0L, p))
        assertEquals(false, prefs.values[SyncPreferences.KEY_SUBSCRIPTION_SUSPENDED])
        assertEquals(SubscriptionState.Active(0L, p), PlayStateStore.read(prefs))
        store(prefs, SubscriptionState.Expired(p))
        assertEquals(SubscriptionState.Expired(p), PlayStateStore.read(prefs))
    }

    @Test
    fun `an expired state stored by an older version reads back not suspended`() {
        val prefs = FakePrefs()
        prefs.values[SyncPreferences.KEY_SUBSCRIPTION_STATE] = "Expired"
        prefs.values[SyncPreferences.KEY_SUBSCRIPTION_PRODUCT] = p
        assertEquals(SubscriptionState.Expired(p), PlayStateStore.read(prefs))
    }

    @Test
    fun `every state keeps its name`() {
        for (s in listOf(
            SubscriptionState.Trial(5L, p),
            SubscriptionState.Active(0L, p),
            SubscriptionState.Grace(5L, p),
            SubscriptionState.Expired(p),
            SubscriptionState.NoSubscription,
        )) {
            val prefs = FakePrefs()
            store(prefs, s)
            assertEquals(s, PlayStateStore.read(prefs))
        }
        assertEquals(SubscriptionState.Unknown, PlayStateStore.read(FakePrefs()))
    }
}

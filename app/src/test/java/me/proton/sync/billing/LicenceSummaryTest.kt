package me.proton.sync.billing

import me.proton.sync.SyncPreferences
import me.proton.sync.backup.StrictPrefs
import me.proton.sync.contacts.FakePrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * The debug log header's licence line: what this install last stored about its subscription, never the id,
 * and without changing a setting.
 */
class LicenceSummaryTest {

    private fun ms(iso: String): Long = Instant.parse(iso).toEpochMilli()

    /** A well-formed but made-up Paddle id. */
    private val id = "sub_01" + "b".repeat(24)

    @Test
    fun `an install that never checked and has no id says so`() {
        assertEquals("not checked yet; no subscription id stored", LicenceSummary.line(FakePrefs(), paddle = true))
    }

    @Test
    fun `the play version says the state and when it was saved, and nothing about an id`() {
        val prefs = FakePrefs()
        prefs.values[SyncPreferences.KEY_SUBSCRIPTION_STATE] = "Active"
        prefs.values[SyncPreferences.KEY_SUBSCRIPTION_EXPIRY] = 0L
        prefs.values[SyncPreferences.KEY_SUBSCRIPTION_LAST_CHECK] = ms("2026-10-24T08:15:00Z")
        assertEquals("Active; state saved 2026-10-24 08:15 UTC", LicenceSummary.line(prefs, paddle = false))
    }

    @Test
    fun `a direct trial gives its end and a confirmed id, without the id`() {
        val prefs = FakePrefs()
        prefs.values[SyncPreferences.KEY_SUBSCRIPTION_STATE] = "Trial"
        prefs.values[SyncPreferences.KEY_SUBSCRIPTION_EXPIRY] = ms("2026-10-31T00:00:00Z")
        prefs.values[SyncPreferences.KEY_PADDLE_SUB_ID] = id
        prefs.values[SyncPreferences.KEY_LICENSE_CONFIRMED] = true
        val line = LicenceSummary.line(prefs, paddle = true)
        assertTrue(line, line.contains("Trial until 2026-10-31"))
        assertTrue(line, line.contains("id stored, confirmed"))
        assertFalse(line, line.contains(id))
        assertFalse(line, line.contains("sub_01"))
    }

    @Test
    fun `paused and payment failed are named`() {
        val prefs = FakePrefs()
        prefs.values[SyncPreferences.KEY_SUBSCRIPTION_STATE] = "Expired"
        prefs.values[SyncPreferences.KEY_SUBSCRIPTION_PAUSED] = true
        assertTrue(LicenceSummary.line(prefs, paddle = true).startsWith("Expired, paused;"))
        prefs.values[SyncPreferences.KEY_SUBSCRIPTION_PAUSED] = false
        prefs.values[SyncPreferences.KEY_SUBSCRIPTION_PAYMENT_FAILED] = true
        assertTrue(LicenceSummary.line(prefs, paddle = true).startsWith("Expired, payment failed;"))
    }

    @Test
    fun `a scheduled change is named with its date`() {
        val prefs = FakePrefs()
        val editor = prefs.edit()
        ScheduledChangeStore.write(
            editor,
            SubscriptionState.Active(
                ms("2026-11-23T10:00:00Z"), "p",
                scheduledChange = ScheduledChange(ScheduledChange.Action.CANCEL, ms("2026-11-23T10:00:00Z")),
            ),
        )
        editor.apply()
        prefs.values[SyncPreferences.KEY_SUBSCRIPTION_STATE] = "Active"
        val line = LicenceSummary.line(prefs, paddle = true)
        assertTrue(line, line.contains("cancel scheduled for 2026-11-23"))
    }

    @Test
    fun `an id stored before the confirmation flag existed says the flag is not recorded`() {
        val prefs = FakePrefs()
        prefs.values[SyncPreferences.KEY_SUBSCRIPTION_STATE] = "Active"
        prefs.values[SyncPreferences.KEY_PADDLE_SUB_ID] = id
        assertEquals("Active; id stored, confirmation not recorded", LicenceSummary.line(prefs, paddle = true))
    }

    @Test
    fun `an entry that is not an id gives its length, never its text`() {
        val prefs = FakePrefs()
        prefs.values[SyncPreferences.KEY_PADDLE_SUB_ID] = "someone@example.com"
        prefs.values[SyncPreferences.KEY_LICENSE_CONFIRMED] = false
        val line = LicenceSummary.line(prefs, paddle = true)
        assertTrue(line, line.contains("id stored, not in sub_ form (19 characters), unconfirmed"))
        assertFalse(line, line.contains("@"))
        assertFalse(line, line.contains("someone"))
    }

    @Test
    fun `a run of failing checks gives when it started`() {
        val prefs = FakePrefs()
        prefs.values[SyncPreferences.KEY_SUBSCRIPTION_STATE] = "Active"
        prefs.values[SyncPreferences.KEY_PADDLE_SUB_ID] = id
        prefs.values[SyncPreferences.KEY_LICENSE_CONFIRMED] = true
        prefs.values[SyncPreferences.KEY_LICENSE_FIRST_FAILED] = ms("2026-10-20T06:05:00Z")
        val line = LicenceSummary.line(prefs, paddle = true)
        assertTrue(line, line.contains("; checks failing since 2026-10-20 06:05 UTC"))
    }

    @Test
    fun `the play version ignores a stored Paddle id`() {
        val prefs = FakePrefs()
        prefs.values[SyncPreferences.KEY_SUBSCRIPTION_STATE] = "Expired"
        prefs.values[SyncPreferences.KEY_PADDLE_SUB_ID] = id
        prefs.values[SyncPreferences.KEY_LICENSE_FIRST_FAILED] = ms("2026-10-20T06:05:00Z")
        assertEquals("Expired", LicenceSummary.line(prefs, paddle = false))
    }

    @Test
    fun `reading changes no setting`() {
        // Above all KEY_LICENSE_CONFIRMED, which the direct version's licence check writes when it is absent.
        val prefs = FakePrefs()
        prefs.values[SyncPreferences.KEY_SUBSCRIPTION_STATE] = "Active"
        prefs.values[SyncPreferences.KEY_PADDLE_SUB_ID] = id
        prefs.values[SyncPreferences.KEY_SUBSCRIPTION_LAST_CHECK] = ms("2026-10-24T08:15:00Z")
        val before = HashMap(prefs.values)
        LicenceSummary.line(prefs, paddle = true)
        assertEquals(before, prefs.values)
    }

    @Test
    fun `a setting of the wrong type gives an unreadable line instead of an exception`() {
        val prefs = StrictPrefs()
        prefs.fake.values[SyncPreferences.KEY_SUBSCRIPTION_STATE] = "Active"
        prefs.fake.values[SyncPreferences.KEY_SUBSCRIPTION_EXPIRY] = "1793000000000"
        assertEquals("unreadable (ClassCastException)", LicenceSummary.line(prefs, paddle = true))
        assertEquals(0, prefs.editsOpened)
    }

    @Test
    fun `a state name the app does not write is not printed as stored`() {
        val prefs = FakePrefs()
        prefs.values[SyncPreferences.KEY_SUBSCRIPTION_STATE] = "someone@example.com"
        assertEquals("unrecognised state", LicenceSummary.line(prefs, paddle = false))
    }
}

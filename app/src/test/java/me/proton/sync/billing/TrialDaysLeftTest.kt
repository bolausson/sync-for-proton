package me.proton.sync.billing

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The trial card's day count. It used to round down, so a trial that had just started read "6 days left" and the
 * whole last day read "0 days left". Now it rounds up, and only a trial whose end has passed reads 0.
 */
class TrialDaysLeftTest {

    private val now = 1_800_000_000_000L
    private val minute = 60_000L
    private val hour = 60 * minute
    private val day = 24 * hour

    @Test
    fun `a trial that started a minute ago has 7 days`() {
        assertEquals(7, trialDaysLeft(now + 7 * day - minute, now))
        assertEquals(7, trialDaysLeft(now + 7 * day, now))
    }

    @Test
    fun `a part of a day counts as a whole day`() {
        assertEquals(7, trialDaysLeft(now + 6 * day + 1, now))
        assertEquals(6, trialDaysLeft(now + 6 * day, now))
    }

    @Test
    fun `the last day reads 1 until the trial ends`() {
        assertEquals(1, trialDaysLeft(now + 5 * hour, now))
        assertEquals(1, trialDaysLeft(now + 1, now))
    }

    @Test
    fun `an ended trial reads 0, never less`() {
        assertEquals(0, trialDaysLeft(now, now))
        assertEquals(0, trialDaysLeft(now - 3 * day, now))
        // A cached Trial without an expiry.
        assertEquals(0, trialDaysLeft(0L, now))
    }
}

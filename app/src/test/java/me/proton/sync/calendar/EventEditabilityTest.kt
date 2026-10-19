package me.proton.sync.calendar

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether a downloaded event is answer-only.
 *
 * Every expectation here was measured against Proton on 2026-09-21 rather than reasoned about, after
 * a version shipped on the opposite assumption. On a calendar shared to this account with write
 * access: a guest-free event uploads fine, and an event WITH guests that this account did not
 * organise is refused with code 2011 even for a title-only change.
 */
class EventEditabilityTest {

    private companion object {
        val MINE = setOf("me@example.com", "alias@example.com")
        // The address this account holds on the calendar.
        const val OURS_ON_CALENDAR = "me@example.com"
    }

    @Test
    fun `our own event is editable`() {
        assertFalse(isAttendeeOnlyEvent("me@example.com", MINE, OURS_ON_CALENDAR))
    }

    @Test
    fun `an event organised by another of our addresses is editable`() {
        assertFalse(isAttendeeOnlyEvent("alias@example.com", MINE, OURS_ON_CALENDAR))
    }

    @Test
    fun `a multi-address account's calendar-owning address counts as ours`() {
        // Signed in as one address, the calendar created under another of the same account's.
        assertFalse(isAttendeeOnlyEvent("other@example.com", MINE, "other@example.com"))
    }

    @Test
    fun `the calendar owner's event with guests is answer-only`() {
        // Device-measured: Proton refuses this account's edits to it with code 2011, INCLUDING a
        // title-only change. A version that briefly treated it as editable was wrong, and the phone
        // showing it read-only is the truthful answer.
        assertTrue(isAttendeeOnlyEvent("organizer@example.com", MINE, OURS_ON_CALENDAR))
    }

    @Test
    fun `a guest-free event needs no special case`() {
        // Proton writes no ORGANIZER into an event with no attendees, so a co-owner's guest-free
        // event arrives with none and stays editable, which matches what Proton accepts.
        assertFalse(isAttendeeOnlyEvent(null, MINE, OURS_ON_CALENDAR))
        assertFalse(isAttendeeOnlyEvent("", MINE, OURS_ON_CALENDAR))
        assertFalse(isAttendeeOnlyEvent("   ", MINE, OURS_ON_CALENDAR))
    }

    @Test
    fun `a genuine external invitation is answer-only`() {
        assertTrue(isAttendeeOnlyEvent("someone@external.example", MINE, OURS_ON_CALENDAR))
    }

    @Test
    fun `comparison ignores case and surrounding space`() {
        assertFalse(isAttendeeOnlyEvent("  Me@Example.COM ", MINE, OURS_ON_CALENDAR))
        assertFalse(isAttendeeOnlyEvent(" Other@Example.COM ", MINE, "other@example.com"))
    }

    @Test
    fun `an empty address set does not make every event editable`() {
        // A race can leave the address map empty; a stranger's event must still be answer-only.
        assertTrue(isAttendeeOnlyEvent("someone@external.example", emptySet(), null))
    }
}

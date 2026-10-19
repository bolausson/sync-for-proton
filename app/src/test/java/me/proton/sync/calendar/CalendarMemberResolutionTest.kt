package me.proton.sync.calendar

import me.proton.sync.api.models.CalendarMember
import me.proton.sync.api.models.EventAttendee
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Picking our own entry out of a calendar's member list, and our own addresses and attendee.
 *
 * The shared-calendar cases are the reason this exists: everything here passed trivially while the
 * app only ever wrote to calendars it owned, and a write to a calendar shared from another account
 * succeeded on 2026-09-21. The lists Proton sends hold only our own entries, so the cases with
 * somebody else's entry first are the ones the code must survive, not the ones it meets every day.
 */
class CalendarMemberResolutionTest {

    private fun member(
        email: String,
        permissions: Int = WRITE_AND_READ,
        id: String = "member-$email",
        color: String? = "#111111",
    ) = CalendarMember(
        id = id,
        email = email,
        permissions = permissions,
        addressId = "addr-$email",
        calendarId = "cal1",
        color = color,
        display = 1,
    )

    private fun attendee(email: String, id: String) =
        EventAttendee(id = id, token = attendeeToken(UID, email), status = 0, permissions = null)

    private companion object {
        const val WRITE_AND_READ = 16 or 32
        const val READ_ONLY = 32
        /** What an owner's entry carries: every bit, SUPER_OWNER (1) and OWNER (2) included. */
        const val OWNS = 127
        /** A share with edit rights: WRITE, READ and AVAILABILITY, as TestCal measured (112). */
        const val EDIT = 16 or 32 or 64
        const val UID = "event-uid@proton.me"
        val MINE = setOf("me@example.com", "alias@example.com")

        /** A Proton address as written, and the canonical form Proton hashes it in. */
        const val DOTTED = "a.b_c-d+x@proton.me"
        const val CANONICAL = "abcd@proton.me"
    }

    @Test
    fun `a personal calendar with one member returns it`() {
        val only = member("me@example.com")
        assertEquals(only, memberForUser(listOf(only), MINE))
    }

    @Test
    fun `a shared calendar returns OUR entry, not the first one`() {
        // The case the old code got wrong. The owner is listed first and is not us.
        val owner = member("owner@example.com", permissions = WRITE_AND_READ, id = "owner")
        val me = member("me@example.com", permissions = READ_ONLY, id = "me")
        val chosen = memberForUser(listOf(owner, me), MINE)
        assertEquals("me", chosen?.id)
        assertEquals("me@example.com", chosen?.email)
    }

    @Test
    fun `the permissions come from OUR entry`() {
        // Not a detail: these become CALENDAR_ACCESS_LEVEL. Reading the owner's WRITE bit while we
        // are read-only makes Android offer an edit that Proton then refuses with code 2026.
        val owner = member("owner@example.com", permissions = WRITE_AND_READ)
        val me = member("me@example.com", permissions = READ_ONLY)
        assertEquals(READ_ONLY, memberForUser(listOf(owner, me), MINE)?.permissions)
    }

    @Test
    fun `the colour comes from OUR entry, because colour is per member`() {
        val owner = member("owner@example.com", color = "#FF0000")
        val me = member("me@example.com", color = "#00FF00")
        assertEquals("#00FF00", memberForUser(listOf(owner, me), MINE)?.color)
    }

    @Test
    fun `any of the account's addresses counts, not just the primary`() {
        val owner = member("someone@else.example")
        val mineByAlias = member("alias@example.com", id = "alias-entry")
        assertEquals("alias-entry", memberForUser(listOf(owner, mineByAlias), MINE)?.id)
    }

    @Test
    fun `matching ignores case, because the wire casing is not guaranteed`() {
        val owner = member("owner@example.com")
        val me = member("Me@Example.COM", id = "me-mixed-case")
        assertEquals("me-mixed-case", memberForUser(listOf(owner, me), MINE)?.id)
    }

    @Test
    fun `with no match it falls back to the first entry rather than nothing`() {
        // An address set that could not be read must not stop the calendar syncing: falling back is
        // exactly what the code did everywhere before, so this is never a regression.
        val owner = member("owner@example.com", id = "owner")
        val other = member("third@party.example", id = "third")
        assertEquals("owner", memberForUser(listOf(owner, other), MINE)?.id)
        assertEquals("owner", memberForUser(listOf(owner, other), emptySet())?.id)
    }

    @Test
    fun `no members at all is null, not an exception`() {
        assertNull(memberForUser(null, MINE))
        assertNull(memberForUser(emptyList(), MINE))
    }

    // ownMember: what every write and every decision about this account takes.

    @Test
    fun `ownMember returns OUR entry, not the first one`() {
        val owner = member("owner@example.com", permissions = OWNS, id = "owner")
        val me = member("me@example.com", permissions = EDIT, id = "me")
        assertEquals("me", ownMember(listOf(owner, me), MINE)?.id)
    }

    @Test
    fun `ownMember never falls back to somebody else's entry`() {
        // The difference from memberForUser, and the reason it exists: a write taking this fallback would
        // go up through another participant's membership. Web throws "Self as member not found" here.
        val owner = member("owner@example.com", permissions = OWNS, id = "owner")
        assertNull(ownMember(listOf(owner), MINE))
        assertEquals("owner", memberForUser(listOf(owner), MINE)?.id)
    }

    @Test
    fun `ownMember with no addresses known is null, so nothing is written`() {
        assertNull(ownMember(listOf(member("me@example.com")), emptySet()))
    }

    @Test
    fun `ownMember matching ignores case`() {
        val me = member("Me@Example.COM", id = "me-mixed-case")
        assertEquals("me-mixed-case", ownMember(listOf(member("owner@example.com"), me), MINE)?.id)
    }

    @Test
    fun `ownMember of no members at all is null`() {
        assertNull(ownMember(null, MINE))
        assertNull(ownMember(emptyList(), MINE))
    }

    @Test
    fun `with two entries of ours the write takes the first, the same one the signer gets`() {
        // A calendar shared with two of our addresses. The memberId comes from ownMember and the signing
        // address from memberForUser: they must name the same entry, or the signature is not the member's.
        val alias = member("alias@example.com", id = "alias-entry")
        val me = member("me@example.com", id = "me-entry")
        val members = listOf(member("owner@example.com", permissions = OWNS), alias, me)
        assertEquals("alias-entry", ownMember(members, MINE)?.id)
        assertEquals(ownMember(members, MINE), memberForUser(members, MINE))
    }

    // isCalendarOwner: the ownership test behind cancellations and the guest record.

    @Test
    fun `a calendar whose own entry has the owner bits is ours`() {
        assertTrue(isCalendarOwner(listOf(member("me@example.com", permissions = OWNS)), MINE))
    }

    @Test
    fun `a share of somebody else's calendar is not ours, even with the owner listed first`() {
        // The old test read Members[0]: the owner's bits made the share look like our own calendar, and a
        // deletion of the owner's event from this phone, once Proton took it, emailed the owner's guests.
        val owner = member("owner@example.com", permissions = OWNS)
        val me = member("me@example.com", permissions = EDIT)
        assertFalse(isCalendarOwner(listOf(owner, me), MINE))
    }

    @Test
    fun `the super owner bit alone counts as owning`() {
        assertTrue(isCalendarOwner(listOf(member("me@example.com", permissions = 1)), MINE))
    }

    @Test
    fun `with no entry of ours nothing is owned, whatever the first entry holds`() {
        assertFalse(isCalendarOwner(listOf(member("owner@example.com", permissions = OWNS)), MINE))
        assertFalse(isCalendarOwner(null, MINE))
    }

    // ownAddresses and ownAttendeeId: telling our own attendee rows from everybody else's.

    @Test
    fun `our addresses and the account name are us, and nothing else is`() {
        assertEquals(setOf("me@example.com", "alias@example.com", "alice"), ownAddresses(MINE, "Alice"))
    }

    @Test
    fun `our attendee is found by its token`() {
        val attendees = listOf(attendee("owner@example.com", "a-owner"), attendee("alias@example.com", "a-alias"))
        assertEquals("a-alias", ownAttendeeId(attendees, UID, ownAddresses(MINE, "me")))
    }

    @Test
    fun `an invitation addressed to the calendar's owner is not ours to answer`() {
        // On a calendar shared with this account, the invitation in it may be the owner's. The old RSVP
        // counted the first member's address as ours, so with the owner listed first it answered for them.
        val attendees = listOf(attendee("owner@example.com", "a-owner"))
        assertNull(ownAttendeeId(attendees, UID, ownAddresses(MINE, "me")))
    }

    @Test
    fun `the first of our addresses that is invited wins`() {
        val attendees = listOf(attendee("alias@example.com", "a-alias"), attendee("me@example.com", "a-me"))
        assertEquals("a-me", ownAttendeeId(attendees, UID, listOf("me@example.com", "alias@example.com")))
        assertEquals("a-alias", ownAttendeeId(attendees, UID, listOf("alias@example.com", "me@example.com")))
    }

    @Test
    fun `a token made under another UID is not our attendee`() {
        val attendees = listOf(EventAttendee("a-me", attendeeToken("other-uid@proton.me", "me@example.com"), 0, null))
        assertNull(ownAttendeeId(attendees, UID, ownAddresses(MINE, "me")))
    }

    @Test
    fun `Proton hashes the canonical address, so a dotted Proton address still finds its attendee`() {
        // Proton's clients make the token from the canonical form, which on Proton's own domains drops dots,
        // hyphens, underscores and a +tag (the pair GuestStatusesTest pins).
        val attendees = listOf(EventAttendee("a-me", attendeeToken(UID, CANONICAL), 0, null))
        assertEquals("a-me", ownAttendeeId(attendees, UID, listOf(DOTTED)))
        // A token in upper case is the same token.
        val upper = listOf(EventAttendee("a-me", attendeeToken(UID, DOTTED).uppercase(), 0, null))
        assertEquals("a-me", ownAttendeeId(upper, UID, listOf(DOTTED)))
    }

    @Test
    fun `an address's exact token is tried before its canonical form`() {
        // Both forms invited: the exact one is ours as written and wins.
        val attendees = listOf(
            EventAttendee("a-canonical", attendeeToken(UID, CANONICAL), 0, null),
            EventAttendee("a-exact", attendeeToken(UID, DOTTED), 0, null),
        )
        assertEquals("a-exact", ownAttendeeId(attendees, UID, listOf(DOTTED)))
    }
}

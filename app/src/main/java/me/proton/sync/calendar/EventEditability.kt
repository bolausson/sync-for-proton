package me.proton.sync.calendar

/**
 * Whether a downloaded event is one the user may only answer, not edit. Pure, so it can be tested.
 *
 * An answer-only event is marked guest-restricted so the phone's calendar app presents it as
 * read-only, which keeps the user from making an edit that would be dropped on upload.
 *
 * ## What Proton actually allows, measured rather than assumed (2026-09-21, on a shared calendar)
 *
 * A calendar shared to this account with WRITE:
 *  - an event with **no guests** can be created and edited freely (`opCode=1000`, twice);
 *  - an event **with guests** that this account did not organise is refused with code **2011**, and
 *    not only for guest-list changes: a title-only edit was refused too.
 *
 * So "the organiser is not one of our addresses" is the right test after all, and the fix that
 * briefly replaced it was built on a premise that turned out to be false. A co-owner's event is NOT
 * ours to edit merely because we can write to the calendar. The guest-free case needs no special
 * handling either, because Proton writes no ORGANIZER into an event without attendees, so it lands
 * on the null branch below and stays editable, which is correct.
 *
 * Note also that the member list Proton returns to a sharee contains only THEIR own entry, not the
 * owner's, so comparing an organiser against "every member of the calendar" could never have matched
 * the owner anyway.
 *
 * [calendarMemberEmail] is the address this account holds on the calendar. It matters for a
 * multi-address account whose calendar belongs to another of its own addresses: without it, an event
 * organised by that address looks like a stranger's.
 *
 * A missing ORGANIZER is not answer-only: an event with no organiser at all is nobody's invitation.
 */
internal fun isAttendeeOnlyEvent(
    organizerEmail: String?,
    userAddresses: Set<String>,
    calendarMemberEmail: String?,
): Boolean {
    val organizer = organizerEmail?.lowercase()?.trim() ?: return false
    if (organizer.isEmpty()) return false
    if (organizer in userAddresses) return false
    if (organizer == calendarMemberEmail?.lowercase()?.trim()) return false
    return true
}

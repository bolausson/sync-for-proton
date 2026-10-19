package me.proton.sync.calendar

import me.proton.sync.api.models.CalendarMember
import me.proton.sync.api.models.EventAttendee

/**
 * Who is US on a Proton calendar: which entry in its `Members` list, which addresses, which attendee
 * of an event. Pure, so it can be tested without a device.
 *
 * This resolution was once put off on the grounds that writes only ever target personal (Type: 0)
 * calendars, where Members[0] is correct. That premise died on 2026-09-21, when a write to a calendar
 * shared from another account succeeded: TestCal, owned by a different address, shared with edit
 * rights, uploaded with opCode=1000.
 *
 * **What the lists hold.** The two this app reads, the calendar list (`GET calendar/v1`, which fills
 * `CalendarInfo.members`) and the bootstrap, hold the account's OWN entries, normally exactly one.
 * Proton web types the calendar list `CalendarWithOwnMembers` and builds the same shape from
 * `calendar/v1/{id}/members`; its `getMemberAndAddress` (shared/lib/calendar/members.ts), which its
 * event saves and deletes go through, falls back to the bootstrap's first entry because "it is
 * assumed the list of members only contain yourself". Measured on 2026-09-21: the list a sharee
 * receives holds their own entry, not the owner's. Everybody's entries come only from
 * `calendar/v1/{id}/members/all`, which web reads for the sharing screens of a calendar the user owns
 * (and filters the owner out of), and which this app never calls. More than one entry of ours is
 * possible in principle, for a calendar shared with two of the user's addresses.
 *
 * Nothing here depends on that. An earlier version of this note said Proton returns one member per
 * participant, with the owner not always first; every reader below picks our entry by address, so it
 * stays right whichever is true.
 *
 * Getting it wrong is not cosmetic, because four different things depend on this one entry:
 *
 *  - **the signing identity**, `keyManager.sign(account, <this>.email, ics)`. A member whose address
 *    key this account does not hold cannot sign at all;
 *  - **the memberId** sent with every `events/sync` operation, which must be our own membership;
 *  - **the permissions**, which become `CALENDAR_ACCESS_LEVEL` and so decide whether Android offers
 *    editing. Taking the email from our entry and the permissions from another, which is what the
 *    code did, can offer an edit Proton will refuse, or refuse an edit Proton would allow;
 *  - **the colour**, which is per member, so another member's colour is simply not ours.
 *
 * Returning the whole member rather than a field at a time is the point: a caller that uses it reads
 * a consistent entry instead of mixing two. Who takes what, in CalendarSyncAdapter:
 *
 *  - **[ownMember], which never falls back**, for writes and for decisions about this account: the
 *    memberId of every upload (prepareCalendarUploadContext) and of the deletions reconcileDeletedEvents
 *    sends, both through ownMemberIdForWrites, and the ownership test (ownsCalendar, [isCalendarOwner]).
 *    No entry of ours means nothing is written, never that it is written as somebody else;
 *  - **[ownAddresses]**, which holds no member at all, to tell this account's own attendee rows from
 *    everybody else's: isPhantomOnlyAttendeeList, ownGuestEmails and uploadAttendeeRsvp, which then
 *    finds our attendee with [ownAttendeeId];
 *  - **[memberForUser]**, which may fall back to the first entry, for what the phone shows of the
 *    calendar: its colour, permissions and OWNER_ACCOUNT (prepareCalendarJob) and the download's
 *    answer-only test. Uploads read it as well (the signer in resolveOrganizer, the organiser of a
 *    cancellation), but only once [ownMember] has found an entry, so it is that same entry there and
 *    the memberId and the signature name one member.
 *
 * Until 2.0.0 the memberId, the ownership test and those address sets read `Members[0]` instead,
 * which is right only while the first entry is ours.
 */

/**
 * OUR entry and nobody else's: the first member whose email is one of [userAddresses], or null.
 *
 * For writes and for every decision about this account. Null is not a gap to paper over: this account
 * holds no entry here that it can act as, and the caller refuses rather than write as somebody else.
 * Both Proton clients do the same. Web's getMemberAndAddress throws "Self as member not found" when
 * the entry it would use is not one of the user's addresses, and Proton's Android calendar
 * (CalendarsRepositoryImpl.getUserMember) returns null.
 *
 * [userAddresses] are the addresses whose keys this account holds, so a match is also an entry it can
 * sign as. With two entries of ours, the first: web prefers, when it edits an event, the entry whose
 * address is the event's Author, but this app writes a calendar through one entry, and its signer
 * ([memberForUser]) resolves to the same first one, which keeps the memberId and the signature in
 * agreement.
 *
 * [userAddresses] must be lowercase, as `KeyManager.getAddressEmails` returns them.
 */
internal fun ownMember(members: List<CalendarMember>?, userAddresses: Set<String>): CalendarMember? =
    members?.firstOrNull { it.email.lowercase() in userAddresses }

/**
 * [ownMember], or the first entry when none is ours. For what the phone shows of a calendar, never for
 * a write.
 *
 * Falling back to the first entry keeps the old behaviour for a personal calendar, where there is
 * exactly one, and for the case where the address set could not be read: a wrong guess there is no
 * worse than the guess this code made everywhere before. A write cannot take that guess, which is what
 * [ownMember] is for.
 *
 * [userAddresses] must be lowercase, as `KeyManager.getAddressEmails` returns them.
 */
internal fun memberForUser(members: List<CalendarMember>?, userAddresses: Set<String>): CalendarMember? {
    if (members.isNullOrEmpty()) return null
    return ownMember(members, userAddresses) ?: members.first()
}

/** Proton's SUPER_OWNER (1) and OWNER (2) calendar permission bits (WebClients calendar/constants.ts). */
private const val OWNER_PERMISSION_BITS = 1 or 2

/**
 * Whether this account owns the calendar rather than holding a share of it: [ownMember] carries
 * Proton's SUPER_OWNER or OWNER bit. False when no entry is ours, which only ever stops an email from
 * going out.
 *
 * Proton's Android calendar reads the same bit off the user's own entry (Calendar.isOwner); web
 * compares the calendar's Owner.Email with the address of its first own entry (getIsOwnedCalendar in
 * shared/lib/calendar/calendar.ts), the same entry by another route. Reading the bits of `Members[0]`
 * made an owner listed first look like us.
 *
 * [userAddresses] must be lowercase, as `KeyManager.getAddressEmails` returns them.
 */
internal fun isCalendarOwner(members: List<CalendarMember>?, userAddresses: Set<String>): Boolean =
    ownMember(members, userAddresses)?.let { (it.permissions and OWNER_PERMISSION_BITS) != 0 } == true

/**
 * Every address that is this account on an event: [userAddresses] and the account name. What tells the
 * user's own attendee rows from everybody else's.
 *
 * No member's email in it, on purpose. Our own entry's address is one of [userAddresses] already, since
 * that is what [ownMember] matches on, and any other entry's address belongs to another participant.
 * The sets built from this used to add `Members[0].email`, which made that participant "us" whenever
 * the first entry was theirs. Proton web finds the user among an event's attendees from the user's own
 * addresses alone (getSelfAddressData in shared/lib/calendar/deserialize.ts), never from a member.
 *
 * [userAddresses] must be lowercase, as `KeyManager.getAddressEmails` returns them; the account name is
 * lowercased here.
 */
internal fun ownAddresses(userAddresses: Set<String>, accountName: String): Set<String> =
    userAddresses + accountName.lowercase()

/**
 * The ID of this account's attendee on an event, found by Proton's attendee token: the SHA-1 of the
 * event's UID and the address. Proton's clients hash the CANONICAL address (web through
 * core/v4/addresses/canonical in withPmAttendees, Proton's Android calendar through
 * canonicalizeProtonEmail), which on Proton's own domains drops dots, hyphens and underscores, so each
 * address is tried as written and then in its canonical form ([attendeeTokenCandidates]). Hashing only
 * the address as written missed every Proton address with a dot, hyphen or underscore before the @,
 * and the answer was dropped.
 *
 * The first of [ownAddresses], in the CALLER's order, that is invited wins, so the caller puts the
 * address it means first: uploadAttendeeRsvp puts the row the phone's answer is on, then our own
 * member's address. Null when none is invited, which is the right answer for an invitation addressed to
 * somebody else, such as the owner of a calendar shared with this account: that answer is theirs.
 *
 * Web finds the user's attendee by comparing canonical addresses (getSelfAddressData in
 * shared/lib/calendar/deserialize.ts) and then reads that attendee's own X-PM-TOKEN; when several of
 * the user's addresses are invited, it prefers an active address whose attendee has answered.
 */
internal fun ownAttendeeId(attendees: List<EventAttendee>, uid: String, ownAddresses: Iterable<String>): String? {
    for (address in ownAddresses) {
        for (token in attendeeTokenCandidates(address, listOf(uid))) {
            attendees.firstOrNull { it.token.equals(token, ignoreCase = true) }?.let { return it.id }
        }
    }
    return null
}

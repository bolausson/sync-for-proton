package me.proton.sync.calendar

import android.provider.CalendarContract.Attendees
import me.proton.sync.api.models.EventAttendee
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/**
 * Tests for the pure guest-answer rules in GuestStatuses.kt.
 */
class GuestStatusesTest {

    private val guest = "guestsample@gmail.com"
    private val other = "second@example.com"
    private val me = "me@example.com"

    private fun local(email: String, status: Int, own: Boolean = false, tokens: List<String> = listOf("t-$email")) =
        LocalGuest(email, status, own, tokens)

    // ---- status encodings ----

    @Test
    fun statusMappingsRoundTrip() {
        for (s in listOf(PROTON_STATUS_ACCEPTED, PROTON_STATUS_DECLINED, PROTON_STATUS_TENTATIVE)) {
            assertEquals(s, androidToProtonStatus(protonToAndroidStatus(s)))
        }
        assertEquals(PROTON_STATUS_NEEDS_ACTION, androidToProtonStatus(Attendees.ATTENDEE_STATUS_INVITED))
        assertEquals(PROTON_STATUS_NEEDS_ACTION, androidToProtonStatus(Attendees.ATTENDEE_STATUS_NONE))
        assertEquals(Attendees.ATTENDEE_STATUS_INVITED, protonToAndroidStatus(PROTON_STATUS_NEEDS_ACTION))
        assertEquals("ACCEPTED", protonStatusToPartstat(3))
        assertEquals("DECLINED", protonStatusToPartstat(2))
        assertEquals("TENTATIVE", protonStatusToPartstat(1))
        assertEquals("NEEDS-ACTION", protonStatusToPartstat(0))
        assertEquals("NEEDS-ACTION", protonStatusToPartstat(42))
    }

    // ---- canonical addresses and tokens ----

    @Test
    fun canonicalizeFollowsProtonWebRules() {
        assertEquals("firstlast@gmail.com", canonicalizeEmailByGuess("First.Last+tag@Gmail.com"))
        assertEquals("abcd@proton.me", canonicalizeEmailByGuess("a.b_c-d+x@proton.me"))
        assertEquals("a.b@outlook.com", canonicalizeEmailByGuess("A.B+x@outlook.com"))
        assertEquals("a.b+x@example.com", canonicalizeEmailByGuess(" A.B+x@Example.com "))
    }

    @Test
    fun tokenIsSha1OfUidAndNormalisedAddress() {
        val expected = MessageDigest.getInstance("SHA-1").digest("uid-1$guest".toByteArray())
            .joinToString("") { "%02x".format(it) }
        assertEquals(expected, attendeeToken("uid-1", " Guestsample@Gmail.com "))
    }

    @Test
    fun exactTokensComeBeforeCanonicalOnes() {
        val plus = "guestsample+sfp@gmail.com"
        val candidates = attendeeTokenCandidates(plus, listOf("u1", "u2", "u1"))
        assertEquals(
            listOf(attendeeToken("u1", plus), attendeeToken("u2", plus), attendeeToken("u1", guest), attendeeToken("u2", guest)),
            candidates
        )
        // A custom domain has no canonical variant: no duplicates.
        assertEquals(1, attendeeTokenCandidates(me, listOf("u1")).size)
    }

    /** Seen 2026-09-19: a plus-address guest looked listed by Proton through the other guest's token. */
    @Test
    fun aCanonicalTokenThatIsAnotherGuestsExactTokenIsNotUsed() {
        val plus = "guestsample+sfp@gmail.com"
        val guests = buildLocalGuests(listOf(me, guest, plus), listOf(1, 3, 3), setOf(me), listOf("u1"))
        val mainToken = attendeeToken("u1", guest)
        assertTrue(mainToken in guests[1].tokens)
        assertFalse(mainToken in guests[2].tokens)
        assertEquals(setOf(guest), guestsListedByProton(guests, setOf(mainToken)))
        // Alone on the event, the plus address may still be found under its canonical token.
        val alone = buildLocalGuests(listOf(plus), listOf(3), emptySet(), listOf("u1"))
        assertTrue(mainToken in alone[0].tokens)
    }

    // ---- who is emailed ----

    private fun recipients(
        previous: Set<String>?, current: Set<String>, sharedChanged: Boolean,
        prevSent: Set<String> = emptySet(), prevFailed: Set<String> = emptySet(),
        invites: Boolean = true, updates: Boolean = true, cancels: Boolean = true
    ) = planInviteRecipients(previous, current, setOf(me), prevSent, prevFailed, invites, updates, cancels, sharedChanged)

    /** The first phone edit of an event created in Proton used to email nobody. */
    @Test
    fun eventFromProtonUpdatesItsExistingGuests() {
        val r = recipients(previous = null, current = setOf(me, guest), sharedChanged = true)
        assertEquals(emptySet<String>(), r.invitees)
        assertEquals(setOf(guest), r.updatees)
        assertEquals(emptySet<String>(), r.removed)
    }

    /** A reminder or colour of the organiser's own is nobody else's business. */
    @Test
    fun personalOnlyChangeEmailsNobody() {
        assertTrue(recipients(previous = setOf(me, guest), current = setOf(me, guest), sharedChanged = false).isEmpty)
        assertTrue(recipients(previous = null, current = setOf(me, guest), sharedChanged = false).isEmpty)
    }

    @Test
    fun addedGuestIsInvitedAndExistingOnesAreUpdated() {
        val r = recipients(previous = setOf(me, guest), current = setOf(me, guest, other), sharedChanged = false)
        assertEquals(setOf(other), r.invitees)
        assertEquals(setOf(guest), r.updatees)
    }

    @Test
    fun removedGuestIsCancelledAndTheRestUpdated() {
        val r = recipients(previous = setOf(me, guest, other), current = setOf(me, guest), sharedChanged = false)
        assertEquals(setOf(other), r.removed)
        assertEquals(setOf(guest), r.updatees)
        assertEquals(emptySet<String>(), recipients(setOf(me, guest, other), setOf(me, guest), false, cancels = false).removed)
    }

    @Test
    fun failedSendsAreRetriedWithTheRightWording() {
        // Never received anything: invited again. Received the invitation, then an update failed: updated.
        val r = recipients(
            previous = setOf(me, guest, other), current = setOf(me, guest, other), sharedChanged = false,
            prevSent = setOf(guest), prevFailed = setOf(guest, other)
        )
        assertEquals(setOf(other), r.invitees)
        assertEquals(setOf(guest), r.updatees)
    }

    @Test
    fun switchesAreRespectedAndOwnAddressesNeverEmailed() {
        val r = recipients(previous = setOf(me), current = setOf(me, guest), sharedChanged = true, invites = false, updates = false)
        assertTrue(r.isEmpty)
        val all = recipients(previous = null, current = setOf(me, guest), sharedChanged = true)
        assertFalse(me in all.updatees || me in all.invitees)
    }

    /** A guest added in Proton web was invited by Proton: existing, not new, and no reason to email anyone. */
    @Test
    fun guestAddedInProtonWebIsNotInvitedAgain() {
        val r = planInviteRecipients(
            previous = setOf(me, guest), current = setOf(me, guest, other), own = setOf(me),
            prevSent = setOf(guest), prevFailed = emptySet(), sendInvites = true, sendUpdates = true,
            sendCancellations = true, sharedChanged = false, onServer = setOf(guest, other)
        )
        assertTrue(r.invitees.isEmpty() && r.updatees.isEmpty())
        assertEquals(setOf(other), r.alreadyReached)
    }

    /** On an event created in Proton, a guest Proton does not list yet was added on the phone: invite them. */
    @Test
    fun guestAddedOnThePhoneToAProtonEventIsInvited() {
        val r = planInviteRecipients(
            previous = null, current = setOf(me, guest, other), own = setOf(me),
            prevSent = emptySet(), prevFailed = emptySet(), sendInvites = true, sendUpdates = true,
            sendCancellations = true, sharedChanged = false, onServer = setOf(guest)
        )
        assertEquals(setOf(other), r.invitees)
        assertEquals(setOf(guest), r.updatees)       // the guest list changed, which the others can see
        assertEquals(setOf(guest), r.alreadyReached)
    }

    /** A failed update to a guest Proton invited is retried as an update, not as a fresh invitation. */
    @Test
    fun failedUpdateToAProtonInvitedGuestIsRetriedAsAnUpdate() {
        val r = planInviteRecipients(
            previous = null, current = setOf(me, guest), own = setOf(me),
            prevSent = emptySet(), prevFailed = setOf(guest), sendInvites = true, sendUpdates = true,
            sendCancellations = true, sharedChanged = false
        )
        assertEquals(emptySet<String>(), r.invitees)
        assertEquals(setOf(guest), r.updatees)
    }

    /** A guest the phone added in a run that died before emailing them is still new on the retry. */
    @Test
    fun aPendingInviteeIsNotMistakenForAGuestProtonInvited() {
        val r = planInviteRecipients(
            previous = setOf(me, guest), current = setOf(me, guest, other), own = setOf(me),
            prevSent = setOf(guest), prevFailed = emptySet(), sendInvites = true, sendUpdates = true,
            sendCancellations = true, sharedChanged = false, onServer = setOf(guest, other), pending = setOf(other)
        )
        assertEquals(setOf(other), r.invitees)
        assertEquals(emptySet<String>(), r.alreadyReached)
    }

    /** A guest removed in Proton web was told by Proton; only a removal made on the phone is cancelled. */
    @Test
    fun aGuestRemovedInProtonIsNotCancelledAgain() {
        val r = planInviteRecipients(
            previous = setOf(me, guest, other), current = setOf(me, guest), own = setOf(me),
            prevSent = setOf(guest, other), prevFailed = emptySet(), sendInvites = true, sendUpdates = true,
            sendCancellations = true, sharedChanged = false, onServer = setOf(guest), recordedOnServer = emptySet()
        )
        assertTrue(r.isEmpty)
        val onPhone = planInviteRecipients(
            previous = setOf(me, guest, other), current = setOf(me, guest), own = setOf(me),
            prevSent = setOf(guest, other), prevFailed = emptySet(), sendInvites = true, sendUpdates = true,
            sendCancellations = true, sharedChanged = false, onServer = setOf(guest), recordedOnServer = setOf(other)
        )
        assertEquals(setOf(other), onPhone.removed)
    }

    /** A failed email is retried under whichever switch sent it. */
    @Test
    fun aFailedEmailIsRetriedEvenWithUpdatesOff() {
        val r = planInviteRecipients(
            previous = setOf(me, guest), current = setOf(me, guest), own = setOf(me),
            prevSent = setOf(guest), prevFailed = setOf(guest), sendInvites = true, sendUpdates = false,
            sendCancellations = false, sharedChanged = false
        )
        assertEquals(setOf(guest), r.updatees)
    }

    @Test
    fun listedByProtonMatchesAnyTokenCandidate() {
        val guests = listOf(local(me, 1, own = true, tokens = listOf("A")), local(guest, 3, tokens = listOf("b1", "B2")), local(other, 3, tokens = listOf("c")))
        assertEquals(setOf(guest), guestsListedByProton(guests, setOf("a", "b2")))
    }

    // ---- what each guest's answer is ----

    @Test
    fun aGuestWhoseReAskEmailFailedIsAskedAgainOnTheNextEmail() {
        val guests = listOf(local(guest, Attendees.ATTENDEE_STATUS_ACCEPTED))
        val emailed = InviteRecipients(emptySet(), setOf(guest), emptySet())
        val plan = planGuestStatuses(guests, mapOf("t-$guest" to PROTON_STATUS_NEEDS_ACTION), 1, emailed,
            breaking = false, reaskPending = setOf(guest))
        assertEquals(setOf(guest), plan.askedAgain)
        assertEquals("NEEDS-ACTION", plan.invitePartstat(guest))
        // Not emailed this time: the pending re-ask waits, nothing is claimed.
        val quiet = planGuestStatuses(guests, mapOf("t-$guest" to PROTON_STATUS_ACCEPTED), 1, InviteRecipients.NONE,
            breaking = false, reaskPending = setOf(guest))
        assertTrue(quiet.askedAgain.isEmpty())
        // The guest has answered the changed event since: the re-ask is over, their answer stands.
        val answered = planGuestStatuses(guests, mapOf("t-$guest" to PROTON_STATUS_ACCEPTED), 1, emailed,
            breaking = false, reaskPending = setOf(guest))
        assertTrue(answered.askedAgain.isEmpty())
        assertEquals(listOf(PROTON_STATUS_ACCEPTED), answered.statuses)
    }

    /** The provider fabricates an owner row Proton never lists; it must not spoil the comparison. */
    @Test
    fun anUnlistedOwnRowDoesNotSpoilMatchesServer() {
        val guests = listOf(local(me, Attendees.ATTENDEE_STATUS_NONE, own = true), local(guest, 3))
        assertTrue(planGuestStatuses(guests, mapOf("t-$guest" to 3), 1, InviteRecipients.NONE, false).matchesServer)
    }

    @Test
    fun durationsParseStrictly() {
        assertEquals(7 * 86_400_000L, parseIcalDurationMillis("P1W"))
        assertEquals(0L, parseIcalDurationMillis("PT0S"))
        assertEquals(0L, parseIcalDurationMillis("P0S"))
        assertEquals(26 * 3_600_000L, parseIcalDurationMillis("P1DT2H"))
        assertEquals(3_600_000L, parseIcalDurationMillis("PT3600S"))
        assertEquals(3_600_000L, parseIcalDurationMillis("P3600S"))   // how Android stores it
        assertEquals(-15 * 60_000L, parseIcalDurationMillis("-PT15M"))
        assertEquals(null, parseIcalDurationMillis("P"))
        assertEquals(null, parseIcalDurationMillis("PT"))
        assertEquals(null, parseIcalDurationMillis("garbage"))
        assertEquals(null, parseIcalDurationMillis(null))
    }

    /** The phone's copy is never written into the invitation as the guest's answer. */
    @Test
    fun anAnswerProtonDidNotConfirmIsNotClaimed() {
        val guests = listOf(local(guest, Attendees.ATTENDEE_STATUS_ACCEPTED))
        val plan = planGuestStatuses(guests, null, 0, InviteRecipients(emptySet(), setOf(guest), emptySet()), false)
        assertEquals(listOf(PROTON_STATUS_ACCEPTED), plan.statuses)   // Proton keeps what it had from the phone
        assertEquals("NEEDS-ACTION", plan.invitePartstat(guest))
    }

    @Test
    fun protonsAnswerWinsOverThePhonesStaleCopy() {
        val guests = listOf(local(me, Attendees.ATTENDEE_STATUS_ACCEPTED, own = true), local(guest, Attendees.ATTENDEE_STATUS_INVITED))
        val plan = planGuestStatuses(guests, mapOf("t-$guest" to PROTON_STATUS_ACCEPTED), 2, InviteRecipients.NONE, breaking = false)
        assertEquals(listOf(PROTON_STATUS_ACCEPTED, PROTON_STATUS_ACCEPTED), plan.statuses)
        assertEquals(listOf(false, true), plan.fromServer)
    }

    @Test
    fun ownRowsKeepThePhonesAnswer() {
        val guests = listOf(local(me, Attendees.ATTENDEE_STATUS_DECLINED, own = true))
        val plan = planGuestStatuses(guests, mapOf("t-$me" to PROTON_STATUS_ACCEPTED), 1, InviteRecipients.NONE, false)
        assertEquals(listOf(PROTON_STATUS_DECLINED), plan.statuses)
        assertEquals(null, plan.statusFor(me))
    }

    @Test
    fun withoutProtonTheMostDecisiveLocalRowIsUsed() {
        val guests = listOf(local(guest, Attendees.ATTENDEE_STATUS_INVITED), local(guest, Attendees.ATTENDEE_STATUS_ACCEPTED))
        val plan = planGuestStatuses(guests, null, 0, InviteRecipients.NONE, false)
        assertEquals(listOf(PROTON_STATUS_ACCEPTED, PROTON_STATUS_ACCEPTED), plan.statuses)
        assertFalse(plan.matchesServer)
    }

    @Test
    fun aRealChangeEmailedToAGuestAsksThemAgain() {
        val guests = listOf(local(guest, Attendees.ATTENDEE_STATUS_ACCEPTED))
        val server = mapOf("t-$guest" to PROTON_STATUS_ACCEPTED)
        val emailed = InviteRecipients(emptySet(), setOf(guest), emptySet())
        val moved = planGuestStatuses(guests, server, 1, emailed, breaking = true)
        assertTrue(moved.reset)
        assertEquals(listOf(PROTON_STATUS_NEEDS_ACTION), moved.statuses)
        assertEquals("NEEDS-ACTION", moved.invitePartstat(guest))
        // Same change, but the guest is not emailed (updates switched off): their answer stays.
        val silent = planGuestStatuses(guests, server, 1, InviteRecipients.NONE, breaking = true)
        assertFalse(silent.reset)
        assertEquals(listOf(PROTON_STATUS_ACCEPTED), silent.statuses)
        // A change that moves nothing keeps the answer and lets an outside guest keep theirs.
        val renamed = planGuestStatuses(guests, server, 1, emailed, breaking = false)
        assertEquals(listOf(PROTON_STATUS_ACCEPTED), renamed.statuses)
        assertEquals("ACCEPTED", renamed.invitePartstat(guest))
    }

    @Test
    fun anInviteeStartsUnanswered() {
        val guests = listOf(local(other, Attendees.ATTENDEE_STATUS_ACCEPTED))
        val plan = planGuestStatuses(guests, mapOf("t-$other" to PROTON_STATUS_ACCEPTED), 1,
            InviteRecipients(setOf(other), emptySet(), emptySet()), breaking = false)
        assertEquals(listOf(PROTON_STATUS_NEEDS_ACTION), plan.statuses)
        assertEquals("NEEDS-ACTION", plan.invitePartstat(other))
    }

    @Test
    fun matchesServerOnlyWhenEveryRowIsThereAndNothingElse() {
        val guests = listOf(local(me, 1, own = true), local(guest, 3))
        val both = mapOf("t-$me" to 3, "t-$guest" to 0)
        assertTrue(planGuestStatuses(guests, both, 2, InviteRecipients.NONE, false).matchesServer)
        assertFalse(planGuestStatuses(guests, both, 3, InviteRecipients.NONE, false).matchesServer)
        assertFalse(planGuestStatuses(guests, mapOf("t-$me" to 3), 1, InviteRecipients.NONE, false).matchesServer)
    }

    // ---- did the time really change ----

    private val start = "DTSTART;TZID=Europe/Berlin:20260924T100000"
    private val end = "DTEND;TZID=Europe/Berlin:20260924T110000"

    @Test
    fun sameLinesAreNoChange() {
        assertFalse(isBreakingChange(start, end, "FREQ=WEEKLY;BYDAY=TH", start, end, "FREQ=WEEKLY;BYDAY=TH"))
    }

    @Test
    fun reorderedRruleIsNoChange() {
        assertFalse(isBreakingChange(start, end, "FREQ=WEEKLY;BYDAY=TH;UNTIL=20261231T225959Z",
            start, end, "FREQ=WEEKLY;UNTIL=20261231T225959Z;BYDAY=TH"))
        assertTrue(isSameOrShortenedRecurrence("FREQ=WEEKLY;BYDAY=MO,TH;WKST=MO", "FREQ=WEEKLY;BYDAY=TH,MO"))
    }

    @Test
    fun movedStartOrEndIsAChange() {
        assertTrue(isBreakingChange(start, end, null, "DTSTART;TZID=Europe/Berlin:20260924T143000", "DTEND;TZID=Europe/Berlin:20260924T153000", null))
        assertTrue(isBreakingChange(start, end, null, start, "DTEND;TZID=Europe/Berlin:20260924T120000", null))
    }

    @Test
    fun respelledTimeZoneWithTheSameInstantIsNoChange() {
        assertFalse(isBreakingChange(start, end, null,
            "DTSTART;TZID=Europe/London:20260924T090000", "DTEND;TZID=Europe/London:20260924T100000", null))
        assertFalse(isBreakingChange(start, end, null, "DTSTART:20260924T080000Z", "DTEND:20260924T090000Z", null))
    }

    @Test
    fun droppedRedundantDtendIsNoChange() {
        assertFalse(isBreakingChange(start, "DTEND;TZID=Europe/Berlin:20260924T100000", null, start, null, null))
        assertFalse(isBreakingChange("DTSTART;VALUE=DATE:20260924", "DTEND;VALUE=DATE:20260925", null,
            "DTSTART;VALUE=DATE:20260924", null, null))
    }

    @Test
    fun allDayFlipIsAChange() {
        assertTrue(isBreakingChange("DTSTART;VALUE=DATE:20260924", null, null, "DTSTART:20260924T000000Z", null, null))
    }

    @Test
    fun shortenedRecurrenceIsNoChangeButAnythingElseIs() {
        val w = "FREQ=WEEKLY;BYDAY=TH"
        assertTrue(isSameOrShortenedRecurrence("$w;UNTIL=20261231T225959Z", "$w;UNTIL=20261031T225959Z"))
        assertFalse(isSameOrShortenedRecurrence("$w;UNTIL=20261031T225959Z", "$w;UNTIL=20261231T225959Z"))
        assertTrue(isSameOrShortenedRecurrence("$w;COUNT=10", "$w;COUNT=4"))
        assertFalse(isSameOrShortenedRecurrence("$w;COUNT=4", "$w;COUNT=10"))
        assertTrue(isSameOrShortenedRecurrence(w, "$w;COUNT=4"))
        assertFalse(isSameOrShortenedRecurrence(w, "FREQ=WEEKLY;BYDAY=FR"))
        assertFalse(isSameOrShortenedRecurrence(null, w))
        assertFalse(isSameOrShortenedRecurrence(w, null))
        assertTrue(isSameOrShortenedRecurrence(null, null))
    }

    @Test
    fun defaultIntervalAndInsignificantWeekStartAreNoChange() {
        assertTrue(isSameOrShortenedRecurrence("FREQ=WEEKLY;BYDAY=TH", "FREQ=WEEKLY;INTERVAL=1;BYDAY=TH"))
        assertTrue(isSameOrShortenedRecurrence("FREQ=WEEKLY;BYDAY=TH", "FREQ=WEEKLY;UNTIL=20261031T225959Z;WKST=SU;BYDAY=TH"))
        // Every other week on two days: the week start decides which weeks, so it is a change.
        assertFalse(isSameOrShortenedRecurrence("FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,TH", "FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,TH;WKST=SU"))
        assertFalse(isSameOrShortenedRecurrence("FREQ=WEEKLY;BYDAY=TH", "FREQ=WEEKLY;INTERVAL=2;BYDAY=TH"))
    }

    @Test
    fun unparseableAndDifferentCountsAsAChange() {
        assertTrue(isBreakingChange("DTSTART:garbage", null, null, "DTSTART:other", null, null))
        assertFalse(isBreakingChange("DTSTART:garbage", null, null, "DTSTART:garbage", null, null))
    }

    // ---- occurrence edits ----

    @Test
    fun occurrenceMovedOnlyWhenStartOrLengthChanged() {
        val hour = 3_600_000L
        assertFalse(isOccurrenceMoved(1_000L, 1_000L + hour, 1_000L, hour))
        assertTrue(isOccurrenceMoved(2_000L, 2_000L + hour, 1_000L, hour))
        assertTrue(isOccurrenceMoved(1_000L, 1_000L + 2 * hour, 1_000L, hour))
        assertTrue(isOccurrenceMoved(1_000L, 1_000L + hour, null, hour))
        assertTrue(isOccurrenceMoved(1_000L, 1_000L + hour, 1_000L, null))
    }

    // ---- a guest list the account may not change ----

    @Test
    fun attendeeListRefusalIsRecognisedNarrowly() {
        assertTrue(isAttendeeListRefusal(2011, "Invalid event data (Not allowed to update attendee list)"))
        assertFalse(isAttendeeListRefusal(2011, "Invalid event data (Unsupported property)"))
        assertFalse(isAttendeeListRefusal(2011, "This calendar does not allow editing events"))
        assertFalse(isAttendeeListRefusal(20110, "Not allowed to update attendee list"))
        assertFalse(isAttendeeListRefusal(2026, "Not allowed to update attendee list"))
        assertFalse(isAttendeeListRefusal(2011, null))
        assertFalse(isAttendeeListRefusal(null, "Not allowed to update attendee list"))
    }

    // ---- a guest list the download recorded from Proton ----

    private fun afterDownload(
        previous: Set<String>?, current: Set<String>, onServer: Set<String>?,
        sharedChanged: Boolean = false, prevSent: Set<String> = emptySet(), prevFailed: Set<String> = emptySet(),
        pending: Set<String> = emptySet(), recordedOnServer: Set<String>? = null, fromProton: Boolean = true
    ) = planInviteRecipients(
        previous = previous, current = current, own = setOf(me), prevSent = prevSent, prevFailed = prevFailed,
        sendInvites = true, sendUpdates = true, sendCancellations = true, sharedChanged = sharedChanged,
        onServer = onServer, pending = pending, recordedOnServer = recordedOnServer, previousFromProton = fromProton
    )

    private fun expected(
        invitees: Set<String> = emptySet(), updatees: Set<String> = emptySet(),
        removed: Set<String> = emptySet(), reached: Set<String> = emptySet()
    ) = InviteRecipients(invitees, updatees, removed, reached)

    /** The phone swapped the guest Proton listed for another: the new one is invited, the old one cancelled. */
    @Test
    fun aSwappedGuestOnAnEventFromProtonIsInvitedAndTheOldOneCancelled() {
        val r = afterDownload(
            previous = setOf(me, guest), current = setOf(me, other), onServer = emptySet(), recordedOnServer = setOf(guest)
        )
        assertEquals(expected(invitees = setOf(other), removed = setOf(guest)), r)
        // Proton unreadable: the record alone still tells the two guests apart.
        val blind = afterDownload(previous = setOf(me, guest), current = setOf(me, other), onServer = null)
        assertEquals(expected(invitees = setOf(other), removed = setOf(guest)), blind)
    }

    @Test
    fun aGuestAddedToAnEventFromProtonIsInvitedAndTheRecordedOnesUpdated() {
        val r = afterDownload(previous = setOf(guest), current = setOf(guest, other), onServer = setOf(guest))
        assertEquals(expected(invitees = setOf(other), updatees = setOf(guest), reached = setOf(guest)), r)
    }

    @Test
    fun aTitleChangeOnAnEventFromProtonUpdatesTheRecordedGuests() {
        val r = afterDownload(previous = setOf(guest), current = setOf(guest), onServer = setOf(guest), sharedChanged = true)
        assertEquals(expected(updatees = setOf(guest), reached = setOf(guest)), r)
        // A reminder of the organiser's own still emails nobody.
        assertTrue(afterDownload(previous = setOf(guest), current = setOf(guest), onServer = setOf(guest)).isEmpty)
    }

    /** Proton's client invited the recorded guest, so a failed update to them stays an update. */
    @Test
    fun aFailedUpdateToARecordedGuestIsRetriedAsAnUpdate() {
        val r = afterDownload(
            previous = setOf(guest), current = setOf(guest), onServer = setOf(guest),
            prevSent = emptySet(), prevFailed = setOf(guest)
        )
        assertEquals(expected(updatees = setOf(guest), reached = setOf(guest)), r)
    }

    @Test
    fun aRecordedGuestIsNotInvitedWhenProtonCannotBeRead() {
        val r = afterDownload(previous = setOf(guest), current = setOf(guest), onServer = null, sharedChanged = true)
        assertEquals(expected(updatees = setOf(guest), reached = setOf(guest)), r)
    }

    /** A guest the phone added, uploaded by a run that died before emailing them, is still invited. */
    @Test
    fun aPendingGuestOnAnEventFromProtonIsStillInvited() {
        val r = afterDownload(
            previous = setOf(guest), current = setOf(guest, other), onServer = setOf(guest, other), pending = setOf(other)
        )
        assertEquals(expected(invitees = setOf(other), updatees = setOf(guest), reached = setOf(guest)), r)
    }

    /** Without the flag the record is this app's own, and the earlier rules stand. */
    @Test
    fun aRecordThisAppWroteKeepsTheEarlierResults() {
        // Swap: the same either way.
        assertEquals(
            expected(invitees = setOf(other), removed = setOf(guest)),
            afterDownload(
                previous = setOf(me, guest), current = setOf(me, other), onServer = emptySet(),
                recordedOnServer = setOf(guest), fromProton = false
            )
        )
        // Added guest: the recorded one was emailed by this app, so nobody counts as reached by Proton.
        assertEquals(
            expected(invitees = setOf(other), updatees = setOf(guest)),
            afterDownload(previous = setOf(guest), current = setOf(guest, other), onServer = setOf(guest), fromProton = false)
        )
        // A guest this app never reached is invited again, not updated.
        assertEquals(
            expected(invitees = setOf(guest)),
            afterDownload(
                previous = setOf(guest), current = setOf(guest), onServer = setOf(guest),
                prevFailed = setOf(guest), fromProton = false
            )
        )
        // Proton unreadable: an update, and nobody is claimed as reached.
        assertEquals(
            expected(updatees = setOf(guest)),
            afterDownload(previous = setOf(guest), current = setOf(guest), onServer = null, sharedChanged = true, fromProton = false)
        )
    }

    // ---- what the download records ----

    /** The record the download would write, and whether the guest rows were read at all. */
    private fun record(
        hasGuests: Boolean, rows: List<String>, current: Set<String>?,
        appOwnsRecord: Boolean = false, pending: Set<String> = emptySet()
    ): Pair<Set<String>?, Boolean> {
        var read = false
        val result = downloadedGuestRecord(hasGuests, { read = true; rows }, current, appOwnsRecord, pending)
        return result to read
    }

    @Test
    fun theDownloadLeavesARecordThisAppOwnsAlone() {
        assertEquals(null to false, record(true, listOf(guest), null, appOwnsRecord = true))
        assertEquals(null to false, record(true, listOf(guest), setOf(other), appOwnsRecord = true))
        assertEquals(null to false, record(true, listOf(guest), null, pending = setOf(other)))
    }

    @Test
    fun withoutGuestsOnlyAnExistingRecordIsClearedAndNoRowIsRead() {
        assertEquals(null to false, record(false, listOf(guest), null))
        assertEquals(null to false, record(false, listOf(guest), emptySet()))
        assertEquals(emptySet<String>() to false, record(false, listOf(guest), setOf(guest)))
    }

    @Test
    fun recordedAddressesAreNormalisedAndRowsWithoutAnAtDropped() {
        assertEquals(
            setOf("a@example.com", me) to true,
            record(true, listOf(" A@Example.COM ", "Organizer", "", me), null)
        )
    }

    @Test
    fun anUnchangedRecordIsNotWrittenAgain() {
        assertEquals(null to true, record(true, listOf(" Guestsample@Gmail.com", me), setOf(guest, me)))
        assertEquals(setOf(guest, other) to true, record(true, listOf(guest, other), setOf(guest)))
    }

    @Test
    fun rowsWithoutAnyAddressRecordNothing() {
        assertEquals(null to true, record(true, listOf("Organizer", " "), null))
        assertEquals(null to true, record(true, listOf("Organizer"), setOf(guest)))
    }

    // ---- who an update adds to Proton ----

    private val uids = listOf("u1")

    private fun tok(email: String) = attendeeToken("u1", email)

    private fun guestsOf(vararg emails: String) =
        buildLocalGuests(emails.toList(), emails.map { Attendees.ATTENDEE_STATUS_INVITED }, setOf(me), uids)

    @Test
    fun aGuestProtonDoesNotListIsNewToIt() {
        assertEquals(setOf(other), guestsNewToProton(guestsOf(me, guest, other), setOf(tok(guest)), complete = true))
        // Nobody listed yet, not even the organiser: every guest is new.
        assertEquals(setOf(guest, other), guestsNewToProton(guestsOf(me, guest, other), emptySet(), complete = true))
    }

    @Test
    fun aGuestListedUnderItsCanonicalTokenIsNotNew() {
        // Proton web hashes the canonical Gmail address (no dots, no plus alias).
        val dotted = "guest.sample@gmail.com"
        assertEquals(setOf(other), guestsNewToProton(guestsOf(me, dotted, other), setOf(tok(guest)), complete = true))
        val plus = "guestsample+x@gmail.com"
        assertEquals(emptySet<String>(), guestsNewToProton(guestsOf(me, plus), setOf(tok(guest)), complete = true))
    }

    @Test
    fun ownAddressesAreNeverNewToProton() {
        assertEquals(emptySet<String>(), guestsNewToProton(guestsOf(me, other), setOf(tok(other)), complete = true))
    }

    @Test
    fun anUnknownOrIncompleteListAddsNobody() {
        assertEquals(emptySet<String>(), guestsNewToProton(guestsOf(me, guest, other), setOf(tok(guest)), complete = false))
        assertEquals(emptySet<String>(), guestsNewToProton(guestsOf(me, guest, other), null, complete = true))
    }

    /** Tokens that match none of our guests mean the tokens, not the guests, are unknown here. */
    @Test
    fun tokensThatMatchNoneOfOurGuestsAddNobody() {
        val guests = guestsOf(me, guest, other)
        assertEquals(emptySet<String>(), guestsNewToProton(guests, setOf(tok("stranger@example.com")), complete = true))
        assertEquals(emptySet<String>(), guestsNewToProton(guests, setOf(tok(me), tok("stranger@example.com")), complete = true))
        // The same guests, but tokenised under a UID this app does not know.
        val otherUid = setOf(attendeeToken("u-other", guest), attendeeToken("u-other", other))
        assertEquals(emptySet<String>(), guestsNewToProton(guests, otherUid, complete = true))
    }

    @Test
    fun onlyTheOrganisersOwnRowListedStillAddsTheNewGuest() {
        assertEquals(setOf(other), guestsNewToProton(guestsOf(me, other), setOf(tok(me)), complete = true))
    }

    /** The main address's token is that guest's, not the plus address's: the plus address is new. */
    @Test
    fun aPlusAddressIsNewWhileTheMainAddressIsListed() {
        val plus = "guestsample+sfp@gmail.com"
        assertEquals(setOf(plus), guestsNewToProton(guestsOf(me, guest, plus), setOf(tok(guest)), complete = true))
    }

    // ---- reading every page of guests ----

    private fun att(token: String) =
        EventAttendee(id = "id-$token", token = token, status = PROTON_STATUS_NEEDS_ACTION, permissions = null)

    @Test
    fun aSinglePageIsNotFetchedAgain() {
        var fetched = false
        val first = listOf(att("a"), att("b"))
        val result = collectAttendeePages(first, more = false, fetchPage = { fetched = true; null })
        assertEquals(first to true, result)
        assertFalse(fetched)
    }

    @Test
    fun laterPagesAreReadFromPageOneAndAppendedInOrder() {
        val requested = ArrayList<Int>()
        val (list, complete) = collectAttendeePages(listOf(att("a")), true, { page ->
            requested.add(page)
            when (page) {
                1 -> listOf(att("b")) to true
                2 -> listOf(att("c")) to false
                else -> null
            }
        })
        assertEquals(listOf(1, 2), requested)
        assertEquals(listOf("a", "b", "c"), list.map { it.token })
        assertTrue(complete)
    }

    @Test
    fun anUnreadablePageKeepsWhatWasReadAndIsIncomplete() {
        val requested = ArrayList<Int>()
        val (list, complete) = collectAttendeePages(listOf(att("a")), true, { page ->
            requested.add(page)
            if (page == 1) listOf(att("b")) to true else null
        })
        assertEquals(listOf(1, 2), requested)
        assertEquals(listOf("a", "b"), list.map { it.token })
        assertFalse(complete)
    }

    @Test
    fun anEndlessMoreFlagStopsAtMaxPages() {
        val requested = ArrayList<Int>()
        val (list, complete) = collectAttendeePages(listOf(att("a")), true, { page ->
            requested.add(page)
            listOf(att("p$page")) to true
        }, maxPages = 3)
        assertEquals(listOf(1, 2, 3), requested)
        assertEquals(listOf("a", "p1", "p2", "p3"), list.map { it.token })
        assertFalse(complete)
    }

    @Test
    fun aTokenOnSeveralPagesIsKeptOnceWhateverItsCase() {
        val (list, complete) = collectAttendeePages(listOf(att("ABC"), att("abc")), true, { page ->
            if (page == 1) listOf(att("Abc"), att("def")) to false else null
        })
        assertEquals(listOf("ABC", "def"), list.map { it.token })
        assertTrue(complete)
    }

    // ---- what the update plan carries ----

    @Test
    fun theUpdatesExtrasArePassedThroughAndRemovalsSorted() {
        val guests = listOf(local(guest, Attendees.ATTENDEE_STATUS_ACCEPTED))
        val plan = planGuestStatuses(
            guests, null, 0, InviteRecipients.NONE, breaking = false,
            addedToProton = setOf(other), removedFromProton = setOf("zed@example.com", "anna@example.com"),
            sharedEventId = "shared-1"
        )
        assertEquals(setOf(other), plan.addedToProton)
        assertEquals(setOf("zed@example.com", "anna@example.com"), plan.removedFromProton)
        assertEquals("shared-1", plan.sharedEventId)
        assertEquals(listOf("anna@example.com", "zed@example.com"), plan.removedAttendeeAddresses)
        // Defaults: nothing added, nothing removed, no shared ID, and no RemovedAttendeeAddresses at all.
        val plain = planGuestStatuses(guests, null, 0, InviteRecipients.NONE, false)
        assertTrue(plain.addedToProton.isEmpty())
        assertTrue(plain.removedFromProton.isEmpty())
        assertEquals(null, plain.sharedEventId)
        assertEquals(null, plain.removedAttendeeAddresses)
    }

    @Test
    fun aRemovedGuestProtonListsProvesTheTokensAreUnderstood() {
        // A swap: Proton lists only the guest this update removes, so none of the current ones matches.
        val swapped = guestsOf(me, other)
        assertEquals(
            emptySet<String>(),
            guestsNewToProton(swapped, setOf(tok(guest)), complete = true)
        )
        assertEquals(
            setOf(other),
            guestsNewToProton(swapped, setOf(tok(guest)), complete = true, recordedMatched = true)
        )
        // An incomplete list still adds nobody, whatever was matched.
        assertEquals(
            emptySet<String>(),
            guestsNewToProton(swapped, setOf(tok(guest)), complete = false, recordedMatched = true)
        )
    }

}

package me.proton.sync.calendar

import me.proton.sync.api.models.EventAttendee

import android.provider.CalendarContract
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/*
 * Pure rules for a guest's answer (PARTSTAT / Proton's Attendees[].Status) when the organiser's phone
 * changes an event, and for who is emailed about the change.
 *
 * The reference is Proton's own clients. Web keeps every attendee's current answer in the REQUEST it
 * emails and in the Attendees[].Status it stores, and resets both to "needs action" only when it
 * actually emails a change whose SEQUENCE rose (WebClients mailIntegration/invite.ts
 * getInviteVeventWithUpdatedParstats, getSaveSingleEventActions.ts). Proton Calendar for Android does
 * the same, but only for the attendees it notifies (HandleSaveUseCase.resetParticipationStatus).
 *
 * Nothing in here logs: SyncLog reaches android.util.Log, which a JVM unit test cannot call.
 */

/** Proton's cleartext Attendees[].Status (WebClients calendar/constants.ts ATTENDEE_STATUS_API). */
internal const val PROTON_STATUS_NEEDS_ACTION = 0
internal const val PROTON_STATUS_TENTATIVE = 1
internal const val PROTON_STATUS_DECLINED = 2
internal const val PROTON_STATUS_ACCEPTED = 3

internal fun androidToProtonStatus(androidStatus: Int): Int = when (androidStatus) {
    CalendarContract.Attendees.ATTENDEE_STATUS_ACCEPTED -> PROTON_STATUS_ACCEPTED
    CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED -> PROTON_STATUS_DECLINED
    CalendarContract.Attendees.ATTENDEE_STATUS_TENTATIVE -> PROTON_STATUS_TENTATIVE
    else -> PROTON_STATUS_NEEDS_ACTION
}

internal fun protonToAndroidStatus(protonStatus: Int): Int = when (protonStatus) {
    PROTON_STATUS_ACCEPTED -> CalendarContract.Attendees.ATTENDEE_STATUS_ACCEPTED
    PROTON_STATUS_DECLINED -> CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED
    PROTON_STATUS_TENTATIVE -> CalendarContract.Attendees.ATTENDEE_STATUS_TENTATIVE
    else -> CalendarContract.Attendees.ATTENDEE_STATUS_INVITED
}

internal fun protonStatusToPartstat(protonStatus: Int): String = when (protonStatus) {
    PROTON_STATUS_ACCEPTED -> "ACCEPTED"
    PROTON_STATUS_DECLINED -> "DECLINED"
    PROTON_STATUS_TENTATIVE -> "TENTATIVE"
    else -> "NEEDS-ACTION"
}

/** Lower-case and trimmed, the form every set and map in here is keyed by. */
internal fun normalizeGuestEmail(email: String): String = email.trim().lowercase()

private val PROTON_DOMAINS = setOf("protonmail.com", "protonmail.ch", "pm.me", "proton.me")
private val GMAIL_DOMAINS = setOf("gmail.com", "googlemail.com", "google.com")
private val PLUS_DOMAINS = setOf("hotmail.com", "hotmail.co.uk", "hotmail.fr", "outlook.com", "yandex.ru", "mail.ru")
private val PROTON_LOCAL_PART_NOISE = Regex("[._-]")

/**
 * The address Proton web hashes into an attendee's token, as far as a client can guess it
 * (WebClients helpers/email.ts canonicalizeEmailByGuess). Proton addresses drop the plus alias and
 * '.', '_', '-'; Gmail drops the plus alias and dots; a few providers drop only the plus alias. Custom
 * domains are left alone, exactly as the web client leaves them.
 */
internal fun canonicalizeEmailByGuess(email: String): String {
    val trimmed = email.trim()
    val at = trimmed.lastIndexOf('@')
    if (at < 0) return trimmed.lowercase()
    val domain = trimmed.substring(at + 1).lowercase()
    val local = trimmed.substring(0, at).substringBefore('+')
    return when (domain) {
        in PROTON_DOMAINS -> local.replace(PROTON_LOCAL_PART_NOISE, "").lowercase() + "@" + domain
        in GMAIL_DOMAINS -> local.replace(".", "").lowercase() + "@" + domain
        in PLUS_DOMAINS -> local.lowercase() + "@" + domain
        else -> trimmed.lowercase()
    }
}

/** Proton's `x-pm-token`: SHA-1 of the event UID followed by the (lower-cased, trimmed) address. */
internal fun attendeeToken(uid: String, email: String): String =
    MessageDigest.getInstance("SHA-1")
        .digest("$uid${normalizeGuestEmail(email)}".toByteArray())
        .joinToString("") { "%02x".format(it) }

/**
 * Every token Proton may hold for this guest, most likely first: the address exactly as this app
 * tokenises it (under the local and the server UID, which can differ), then its canonical form, which
 * is what Proton web hashes. The exact form must come first: `name+tag@gmail.com` canonicalises to
 * `name@gmail.com`, which may be a different guest on the same event.
 */
internal fun attendeeTokenCandidates(email: String, uids: List<String>): List<String> {
    val exact = normalizeGuestEmail(email)
    val canonical = canonicalizeEmailByGuess(email)
    val distinctUids = uids.filter { it.isNotBlank() }.distinct()
    val out = LinkedHashSet<String>()
    for (uid in distinctUids) out.add(attendeeToken(uid, exact))
    if (canonical != exact) for (uid in distinctUids) out.add(attendeeToken(uid, canonical))
    return out.toList()
}

/** One local Attendees row as the planner sees it. [own] = the user or the organiser. */
internal data class LocalGuest(
    val email: String,
    val androidStatus: Int,
    val own: Boolean,
    val tokens: List<String>
)

/**
 * The planner's view of an event's attendee rows (index-aligned with [emails]).
 *
 * A guest's canonical token is dropped when it is ANOTHER guest's exact token: `name+tag@gmail.com`
 * canonicalises to `name@gmail.com`, and when both are guests the canonical token is that other
 * guest, not this one. Without this a newly added plus-address guest looked like a guest Proton
 * already listed (seen 2026-09-19), and borrowed the other guest's answer.
 */
internal fun buildLocalGuests(
    emails: List<String>,
    androidStatuses: List<Int>,
    own: Set<String>,
    uids: List<String>
): List<LocalGuest> {
    val exact = emails.map { e -> uids.filter { it.isNotBlank() }.distinct().map { attendeeToken(it, e) }.toSet() }
    return emails.mapIndexed { i, e ->
        val email = normalizeGuestEmail(e)
        val others = exact.filterIndexed { j, _ -> j != i && normalizeGuestEmail(emails[j]) != email }.flatten().toSet()
        LocalGuest(email, androidStatuses[i], email in own, attendeeTokenCandidates(e, uids).filter { it in exact[i] || it !in others })
    }
}

/**
 * Who is emailed about this change, as normalised addresses. Own addresses never appear.
 *
 * [alreadyReached] are guests who have the event although this app never emailed them: Proton's own
 * client invited them (an event created there, or a guest added there). They are recorded as reached,
 * so a failed update to them is retried as an update, never as a fresh invitation.
 */
internal data class InviteRecipients(
    val invitees: Set<String>,
    val updatees: Set<String>,
    val removed: Set<String>,
    val alreadyReached: Set<String> = emptySet()
) {
    val isEmpty: Boolean get() = invitees.isEmpty() && updatees.isEmpty() && removed.isEmpty()

    companion object {
        val NONE = InviteRecipients(emptySet(), emptySet(), emptySet())
    }
}

/**
 * Split the guests of an edited event into who gets an invitation, who gets an update and who gets a
 * cancellation.
 *
 * [previous] is the guest list recorded the last time this app emailed about the event, or null
 * when it never did (the event was created in Proton, whose own client invited the guests). Null
 * therefore means "everybody Proton already lists is an existing guest": they are sent an UPDATE, not
 * an invitation, and certainly not nothing. Skipping them, as this app used to on the first phone edit
 * of such an event, left the guests with the old time.
 *
 * [previousFromProton]: [previous] is Proton's own guest list, recorded by the download
 * ([downloadedGuestRecord]) because this app never emailed about the event. Those guests have the
 * event from Proton's client, so they count as reached, and a guest the phone swapped in is new: a
 * swapped-out guest gets a cancellation, the new one an invitation. Without the record the swap sent
 * nothing (both lists looked new to the planner), and the new guest was never invited.
 *
 * [onServer] are the current guests Proton listed BEFORE this upload, or null when Proton could not be
 * read. A guest Proton lists that this app never recorded was invited by Proton's own client (added in
 * Proton web): an existing guest, not a new one to invite again. A guest Proton does not list yet was
 * added on the phone just now: new, even on an event created in Proton.
 *
 * Existing guests are only emailed when something they can see changed: a shared field
 * ([sharedChanged], the same fingerprint the collision check uses) or the guest list itself. A
 * reminder or colour of the organiser's own is nobody else's business. A failed earlier email is
 * retried: to an invitee if they never received anything, to an updatee otherwise.
 */
internal fun planInviteRecipients(
    previous: Set<String>?,
    current: Set<String>,
    own: Set<String>,
    prevSent: Set<String>,
    prevFailed: Set<String>,
    sendInvites: Boolean,
    sendUpdates: Boolean,
    sendCancellations: Boolean,
    sharedChanged: Boolean,
    onServer: Set<String>? = null,
    pending: Set<String> = emptySet(),
    recordedOnServer: Set<String>? = null,
    previousFromProton: Boolean = false
): InviteRecipients {
    val cur = current - own
    val listed = onServer?.let { it - own }
    val recorded = previous?.let { it - own }
    // Guests who have the event from Proton's own client rather than from this app. [pending] guests
    // were added on the phone and uploaded by an earlier run that never got as far as emailing them.
    val reachedByProton = (when {
        recorded == null -> listed?.intersect(cur) ?: cur
        listed == null -> emptySet()
        else -> (listed intersect cur) - recorded
    } + (if (previousFromProton) (recorded ?: emptySet()) intersect cur else emptySet())) - pending
    val prev = (recorded ?: emptySet()) + reachedByProton
    val newGuests = cur - prev
    val existing = cur intersect prev
    // A recorded guest Proton no longer lists was removed in Proton, whose client already told them.
    val removed = ((recorded ?: emptySet()) - cur).filter { recordedOnServer == null || it in recordedOnServer }.toSet()
    val neverReached = (prevFailed - prevSent - reachedByProton) intersect cur
    val invitees = if (sendInvites) newGuests + neverReached else emptySet()
    val visibleChange = sharedChanged || newGuests.isNotEmpty() || removed.isNotEmpty()
    // A failed email is retried under whichever switch sent it, so under either.
    val retry = if (sendUpdates || sendInvites) prevFailed intersect existing else emptySet()
    val updatees = ((if (sendUpdates && visibleChange) existing else emptySet()) + retry) - invitees
    return InviteRecipients(
        invitees = invitees,
        updatees = updatees,
        removed = if (sendCancellations) removed else emptySet(),
        alreadyReached = reachedByProton
    )
}

/**
 * The guest list the download records for an event this account organises but never emailed about
 * (created or last changed in Proton): the guests as the phone now has them from Proton, or null to
 * leave the record as it is.
 *
 * The record is what a later phone edit is compared with, as Proton's own clients compare with their
 * stored copy of the event. It is left alone once this app owns it ([appOwnsRecord]: it emailed about
 * the event and keeps the list itself) and while an invitation is [pending]. Without guests on Proton
 * ([hasGuests] false) only an existing record is cleared, so events without guests store nothing;
 * [readGuests] is then not called. Addresses are normalised; rows without '@' are dropped, as the
 * upload drops them.
 */
internal fun downloadedGuestRecord(
    hasGuests: Boolean,
    readGuests: () -> List<String>,
    current: Set<String>?,
    appOwnsRecord: Boolean,
    pending: Set<String>
): Set<String>? {
    if (appOwnsRecord || pending.isNotEmpty()) return null
    if (!hasGuests) return if (!current.isNullOrEmpty()) emptySet() else null
    val guests = readGuests().map { normalizeGuestEmail(it) }.filter { '@' in it }.toSet()
    return if (guests.isEmpty() || guests == current) null else guests
}

/**
 * What the upload stores as each guest's answer and what the invitation says about it.
 *
 * [statuses] is index-aligned with the guests the plan was made from, which in turn are
 * index-aligned with the upload's Attendees array (buildAttendeeIcs appends one entry per row).
 */
internal class GuestStatusPlan(
    val guests: List<LocalGuest>,
    val statuses: List<Int>,
    val recipients: InviteRecipients,
    /** A real change of time or recurrence is being emailed: the emailed guests are asked again. */
    val reset: Boolean,
    /** Proton's statuses were read, every local row matched one, and none is missing locally. */
    val matchesServer: Boolean,
    /** Index-aligned: this row's answer is the one Proton holds (false: the phone's copy, or a reset). */
    val fromServer: List<Boolean> = List(guests.size) { false },
    /** Emailed guests who are asked again: this change moved the event, or an earlier one did and its email failed. */
    val askedAgain: Set<String> = emptySet(),
    /** Guests this update adds to Proton ([guestsNewToProton]): a Proton address among them is given the event's key. */
    val addedToProton: Set<String> = emptySet(),
    /** Recorded guests Proton lists that the phone removed: sent as RemovedAttendeeAddresses, as Proton web does. */
    val removedFromProton: Set<String> = emptySet(),
    /** The event's SharedEventID, for the invitations' X-PM-SHARED-EVENT-ID; null when Proton was not read. */
    val sharedEventId: String? = null
) {
    val removedAttendeeAddresses: List<String>? get() = removedFromProton.takeIf { it.isNotEmpty() }?.sorted()

    private val statusByEmail: Map<String, Int> = HashMap<String, Int>().also { m ->
        guests.forEachIndexed { i, g -> if (!g.own) m.putIfAbsent(g.email, statuses[i]) }
    }
    private val serverKnown: Set<String> = guests.indices.filter { fromServer[it] }.map { guests[it].email }.toSet()

    fun statusFor(email: String): Int? = statusByEmail[normalizeGuestEmail(email)]

    /**
     * The PARTSTAT for this guest's ATTENDEE line. Receivers apply it (Google Calendar resets a guest
     * whose line says, or implies, NEEDS-ACTION), so it must be the answer the guest actually gave.
     *
     * A new or re-asked guest is told "needs action". Anyone else carries the answer Proton holds for
     * them, never the phone's copy, which is stale by design. An outside guest's answer reaches Proton
     * only through their reply email, so until that is processed they are asked again, which is also
     * what Proton web does.
     */
    fun invitePartstat(email: String): String {
        val e = normalizeGuestEmail(email)
        if (e in recipients.invitees || e in askedAgain || e !in serverKnown) return "NEEDS-ACTION"
        return protonStatusToPartstat(statusByEmail[e] ?: PROTON_STATUS_NEEDS_ACTION)
    }
}

/**
 * Decide every row's answer. Own rows keep the phone's answer: the user makes it here. A guest who is
 * invited now, or asked again because the time really changed, starts from "needs action". Anyone
 * else keeps what Proton has, and only when Proton could not be read (or does not list them) the
 * phone's copy, which is stale by design because an answer never marks an event as changed.
 *
 * [reaskPending] are guests an earlier change should have asked again, whose email failed: they are
 * asked again when they are emailed now, even if this change moves nothing.
 */
internal fun planGuestStatuses(
    guests: List<LocalGuest>,
    serverStatusByToken: Map<String, Int>?,
    serverTokenCount: Int,
    recipients: InviteRecipients,
    breaking: Boolean,
    reaskPending: Set<String> = emptySet(),
    addedToProton: Set<String> = emptySet(),
    removedFromProton: Set<String> = emptySet(),
    sharedEventId: String? = null
): GuestStatusPlan {
    val reset = breaking && recipients.updatees.isNotEmpty()
    val serverStatus = guests.map { g -> serverStatusByToken?.let { m -> g.tokens.firstNotNullOfOrNull { m[it.lowercase()] } } }
    val serverByEmail = HashMap<String, Int>().also { m ->
        guests.forEachIndexed { i, g -> serverStatus[i]?.let { m.putIfAbsent(g.email, it) } }
    }
    // A pending re-ask stands only while Proton still has no answer: the failed run stored "needs
    // action", so any answer Proton holds now was given to the changed event.
    val askedAgain = recipients.updatees.filter { e ->
        reset || (e in reaskPending && (serverByEmail[e] ?: PROTON_STATUS_NEEDS_ACTION) == PROTON_STATUS_NEEDS_ACTION)
    }.toSet()
    val bestLocal = HashMap<String, Int>()
    for (g in guests) {
        val prior = bestLocal[g.email]
        if (prior == null || decisiveness(g.androidStatus) < decisiveness(prior)) bestLocal[g.email] = g.androidStatus
    }
    val fromServer = ArrayList<Boolean>(guests.size)
    val statuses = guests.mapIndexed { i, g ->
        val server = serverStatus[i]
        val useServer = !g.own && g.email !in recipients.invitees && g.email !in askedAgain && server != null
        fromServer.add(useServer)
        when {
            g.own -> androidToProtonStatus(bestLocal[g.email] ?: g.androidStatus)
            g.email in recipients.invitees -> PROTON_STATUS_NEEDS_ACTION
            g.email in askedAgain -> PROTON_STATUS_NEEDS_ACTION
            server != null -> server
            else -> androidToProtonStatus(bestLocal[g.email] ?: g.androidStatus)
        }
    }
    // Own rows do not count: the provider fabricates one for the calendar's owner address on events
    // downloaded with no answer of ours, and Proton never lists it. Every guest must be listed, and
    // Proton must list nobody else.
    val nonOwn = guests.indices.filter { !guests[it].own }
    val ownListed = guests.indices.count { guests[it].own && serverStatus[it] != null }
    val matchesServer = serverStatusByToken != null &&
        nonOwn.all { serverStatus[it] != null } &&
        serverTokenCount == nonOwn.size + ownListed
    return GuestStatusPlan(
        guests, statuses, recipients, reset, matchesServer, fromServer, askedAgain,
        addedToProton, removedFromProton, sharedEventId
    )
}

/**
 * An event DURATION in milliseconds ("P1W", "P1DT2H", "PT0S", "-PT15M"), or null when it is not one.
 * The "T" is optional, as in Android's own parser, because Android stores "P3600S". Otherwise strict on
 * purpose: a wrong guess here makes an occurrence look moved, and asks its guests again.
 */
internal fun parseIcalDurationMillis(duration: String?): Long? {
    val m = Regex("""^([+-])?P(?:(\d+)W)?(?:(\d+)D)?T?(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?$""")
        .matchEntire(duration?.trim()?.uppercase() ?: return null) ?: return null
    val g = m.groupValues
    if (g.drop(2).all { it.isEmpty() }) return null
    fun n(i: Int) = g[i].toLongOrNull() ?: 0L
    val ms = ((n(2) * 7 + n(3)) * 86_400 + n(4) * 3_600 + n(5) * 60 + n(6)) * 1_000
    return if (g[1] == "-") -ms else ms
}

/** The normalised addresses of [guests] (not own) that Proton lists, by any of their token candidates. */
internal fun guestsListedByProton(guests: List<LocalGuest>, serverTokens: Set<String>): Set<String> =
    guests.filter { g -> !g.own && g.tokens.any { it.lowercase() in serverTokens } }.map { it.email }.toSet()

/**
 * The guests an update adds to Proton: not own, and listed by none of their tokens. Empty whenever
 * Proton's list is unknown ([serverTokens] null), incomplete, or not understood (it names guests, and
 * none of ours by token): telling Proton to add someone it already has is untested, and could get the
 * whole update refused.
 *
 * [recordedMatched] says a guest this update REMOVES was matched to one of Proton's tokens. That proves
 * the tokens are understood, which matters for a swap: the phone replaced the only guest, so none of the
 * current ones matches, and without this the new guest would get no key of the event.
 */
internal fun guestsNewToProton(
    guests: List<LocalGuest>,
    serverTokens: Set<String>?,
    complete: Boolean,
    recordedMatched: Boolean = false
): Set<String> {
    if (serverTokens == null || !complete) return emptySet()
    val listed = guestsListedByProton(guests, serverTokens)
    val ownTokens = guests.filter { it.own }.flatMap { it.tokens }.map { it.lowercase() }.toSet()
    if (listed.isEmpty() && !recordedMatched && (serverTokens - ownTokens).isNotEmpty()) return emptySet()
    return guests.filter { !it.own }.map { it.email }.toSet() - listed
}

/**
 * Every guest of an event: [first] came with the event, and while there are [more] the rest is read
 * page by page from page 1 ([fetchPage] gives a page's guests and whether more follow, or null when
 * it cannot be read), as Proton's clients do. Returns the guests, each token once, and whether the
 * list is complete: false when a page could not be read or [maxPages] ran out.
 */
internal fun collectAttendeePages(
    first: List<EventAttendee>,
    more: Boolean,
    fetchPage: (Int) -> Pair<List<EventAttendee>, Boolean>?,
    maxPages: Int = 20
): Pair<List<EventAttendee>, Boolean> {
    val out = ArrayList<EventAttendee>()
    val seen = HashSet<String>()
    for (a in first) if (seen.add(a.token.lowercase())) out.add(a)
    var hasMore = more
    var page = 1
    while (hasMore) {
        if (page > maxPages) return out to false
        val (list, next) = fetchPage(page) ?: return out to false
        for (a in list) if (seen.add(a.token.lowercase())) out.add(a)
        hasMore = next
        page++
    }
    return out to true
}

/**
 * Which of [emails] Proton lists, by the same token candidates as the local guests, never by a token
 * that belongs to one of the current guests ([taken]).
 */
internal fun emailsListedByProton(
    emails: Set<String>,
    uids: List<String>,
    serverTokens: Set<String>,
    taken: Set<String> = emptySet()
): Set<String> =
    emails.filter { e ->
        attendeeTokenCandidates(e, uids).any { it !in taken && it.lowercase() in serverTokens }
    }.toSet()

/** ACCEPTED first, NONE last: which of several rows for one address is the real answer. */
private fun decisiveness(androidStatus: Int): Int = when (androidStatus) {
    CalendarContract.Attendees.ATTENDEE_STATUS_ACCEPTED -> 0
    CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED -> 1
    CalendarContract.Attendees.ATTENDEE_STATUS_TENTATIVE -> 2
    CalendarContract.Attendees.ATTENDEE_STATUS_INVITED -> 3
    else -> 4
}

/**
 * Whether guests must be asked again: the event's start, end, all-day flag or recurrence REALLY
 * changed, not merely its spelling.
 *
 * This deliberately does not reuse the SEQUENCE rule, which compares the property lines as text: a
 * calendar app or ical4j reorders RRULE parts (UNTIL before BYDAY), a TZID can be respelled, and a
 * redundant DTEND is dropped, all of which bump SEQUENCE without moving a single occurrence. Proton
 * web asks again only on a changed instant, a flipped all-day flag or a recurrence that is not just
 * shortened (sequence.ts withVeventSequence, rruleSubset.ts). Anything this cannot parse counts as a
 * change, which is what happened before.
 */
internal fun isBreakingChange(
    serverDtStart: String?,
    serverDtEnd: String?,
    serverRrule: String?,
    currentDtStart: String?,
    currentDtEnd: String?,
    currentRrule: String?
): Boolean {
    val oldStart = parseIcalTime(serverDtStart)
    val newStart = parseIcalTime(currentDtStart)
    if (oldStart == null || newStart == null) {
        if (serverDtStart?.trim() != currentDtStart?.trim() || serverDtEnd?.trim() != currentDtEnd?.trim()) return true
    } else {
        if (oldStart != newStart) return true
        if (effectiveEnd(oldStart, serverDtEnd) != effectiveEnd(newStart, currentDtEnd)) return true
    }
    return !isSameOrShortenedRecurrence(serverRrule, currentRrule)
}

/** A parsed DTSTART/DTEND: whether it is a date, and its instant (dates as their UTC midnight). */
internal data class IcalTime(val allDay: Boolean, val epochSecond: Long)

private val ICAL_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
private val ICAL_DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")

/** Parse "DTSTART;TZID=Europe/Berlin:20260924T100000", "DTEND;VALUE=DATE:20260925" or "...:20260924T080000Z". */
internal fun parseIcalTime(line: String?): IcalTime? {
    if (line.isNullOrBlank()) return null
    val colon = line.lastIndexOf(':')
    if (colon < 0) return null
    val params = line.substring(0, colon).split(';').drop(1)
    val value = line.substring(colon + 1).trim()
    val tzid = params.firstOrNull { it.startsWith("TZID=", ignoreCase = true) }?.substringAfter('=')?.trim('"')
    val isDate = params.any { it.equals("VALUE=DATE", ignoreCase = true) } || value.length == 8
    return try {
        when {
            isDate -> IcalTime(true, LocalDate.parse(value, ICAL_DATE).atStartOfDay(ZoneOffset.UTC).toEpochSecond())
            value.endsWith("Z") -> IcalTime(false, LocalDateTime.parse(value.dropLast(1), ICAL_DATE_TIME).toEpochSecond(ZoneOffset.UTC))
            tzid != null -> IcalTime(false, LocalDateTime.parse(value, ICAL_DATE_TIME).atZone(ZoneId.of(tzid)).toEpochSecond())
            else -> null   // floating time: no instant to compare
        }
    } catch (_: Exception) {
        null
    }
}

/** The end an absent DTEND stands for: the start for a timed event, one day later for a date. */
private fun effectiveEnd(start: IcalTime, endLine: String?): IcalTime? {
    if (endLine.isNullOrBlank()) {
        return if (start.allDay) IcalTime(true, start.epochSecond + 86_400) else start
    }
    return parseIcalTime(endLine)
}

/**
 * True when [current] recurs exactly like [old], or is the same rule ending sooner (a smaller COUNT,
 * an earlier UNTIL, or an end where there was none): deleting "this and all following" occurrences
 * moves nobody's remaining meetings. Parts are compared as a set, so their order does not matter.
 * INTERVAL=1 is the default, and WKST changes nothing unless a weekly rule skips weeks and names its
 * days or a rule counts week numbers (as Proton web's rruleWkst.ts), so both are ignored otherwise.
 */
internal fun isSameOrShortenedRecurrence(old: String?, current: String?): Boolean {
    val a = recurParts(old)
    val b = recurParts(current)
    if (a == b) return true
    if (a.isEmpty() || b.isEmpty()) return false
    val aCore = a - "COUNT" - "UNTIL"
    val bCore = b - "COUNT" - "UNTIL"
    if (aCore != bCore) return false
    val oldCount = a["COUNT"]?.toIntOrNull()
    val newCount = b["COUNT"]?.toIntOrNull()
    val oldUntil = a["UNTIL"]
    val newUntil = b["UNTIL"]
    return when {
        oldCount == null && oldUntil == null -> newCount != null || newUntil != null
        oldCount != null && newCount != null && oldUntil == null && newUntil == null -> newCount <= oldCount
        oldUntil != null && newUntil != null && oldCount == null && newCount == null -> untilKey(newUntil) <= untilKey(oldUntil)
        else -> false
    }
}

private fun recurParts(rrule: String?): Map<String, String> {
    if (rrule.isNullOrBlank()) return emptyMap()
    val out = HashMap<String, String>()
    for (part in rrule.trim().removePrefix("RRULE:").split(';')) {
        val eq = part.indexOf('=')
        if (eq <= 0) continue
        val key = part.substring(0, eq).trim().uppercase()
        val value = part.substring(eq + 1).trim().uppercase()
        out[key] = if (key.startsWith("BY")) value.split(',').sorted().joinToString(",") else value
    }
    if (out["INTERVAL"] == "1") out.remove("INTERVAL")
    val interval = out["INTERVAL"]?.toIntOrNull() ?: 1
    val wkstMatters = (out["FREQ"] == "WEEKLY" && interval > 1 && out.containsKey("BYDAY")) || out.containsKey("BYWEEKNO")
    if (out["WKST"] == "MO" || !wkstMatters) out.remove("WKST")
    return out
}

/** UNTIL as a sortable string: a date sorts as the end of that day, so a DATE and a DATE-TIME compare. */
private fun untilKey(until: String): String = if (until.length == 8) "${until}T235959Z" else until

/**
 * Proton refused the upload because the account may not change the guest list: guests can only be
 * managed on calendars the user owns. Retrying the same payload can never succeed, so it is final at
 * once. Matched on the message, not on Code 2011 alone, which also covers other validation failures
 * that an app update may fix. The app sends no locale header, so Proton's message is English.
 */
internal fun isAttendeeListRefusal(code: Int?, error: String?): Boolean =
    code == 2011 && error != null && error.contains("attendee list", ignoreCase = true)

/**
 * Whether a single-occurrence edit moved its occurrence. Guests keep their answer to the series for an
 * occurrence that only changed its title or place, and are asked again when it moved. Unknown counts
 * as moved, which is what always happened before.
 */
internal fun isOccurrenceMoved(
    dtStartMillis: Long,
    dtEndMillis: Long,
    originalInstanceTime: Long?,
    originalDurationMillis: Long?
): Boolean {
    if (originalInstanceTime == null || originalDurationMillis == null) return true
    return dtStartMillis != originalInstanceTime || (dtEndMillis - dtStartMillis) != originalDurationMillis
}

package me.proton.sync.calendar

import me.proton.sync.api.models.CalendarInfo
import me.proton.sync.api.models.CalendarMember

/**
 * Writes to Proton a sync could not make and used to keep quiet about (2.1.0 has all of them). Since 2.2.0 a sync runs
 * by itself only when Android's automatic sync is on, so a run that reports success while changes wait on the phone
 * hides them until the user happens to tap Sync. Each of these now fails the run with its reason, and nothing else about
 * it changes (owner, 2026-11-23). Pure, so it is tested without a device.
 *
 * The upload context of a calendar (CalendarSyncAdapter.prepareCalendarUploadContext) that could not be prepared: its
 * own read of the calendars got an HTTP failure ([calendarsNotReadForUpload]), no member of the calendar is on an
 * address whose keys loaded ([countsMissingOwnMember]), or none of the calendar's keys unlocks. The calendar's changed
 * and deleted rows stay pending, as before.
 *
 * The deletion on Proton of mapped events whose rows vanished without a tombstone (CalendarSyncAdapter
 * .reconcileDeletedEvents, mainly an approved bulk deletion): an HTTP failure other than the read-only calendar's 400
 * ([EventDeletionFailures.reason]), a request that threw ([DELETION_NOT_SENT]), and a read of the calendars that failed
 * ([calendarsNotReadForDeletions]) or found no member of the account's own. The mappings stay, so each deletion is sent
 * again at the next sync, as before; except that a refusal of the deletion itself fails its first two syncs only, and the
 * third gives the deletion up and lets the event come back (DeletionPassRefusals, owner 2026-11-23).
 *
 * Each reason names no event and no calendar; Proton's status in it picks the text shown (SyncNotificationHelper
 * .friendlyMessageRes), and a request's network cause is added where it is recorded (NetworkCause.describe).
 */
internal object CalendarWriteFailures {

    /** The upload context's own read of the calendars got Proton's failure [httpCode]: nothing went up to that calendar. */
    fun calendarsNotReadForUpload(httpCode: Int): String =
        "The calendars could not be read for an upload, so nothing was uploaded to one calendar: HTTP $httpCode"

    /** The deletion pass's read of the calendars got Proton's failure [httpCode]: none of one calendar's deletions went. */
    fun calendarsNotReadForDeletions(httpCode: Int): String =
        "The calendars could not be read, so events deleted on this phone were not deleted on Proton: HTTP $httpCode"

    /** A deletion of the deletion pass that threw: the same words as an event deleted on the phone whose request threw. */
    const val DELETION_NOT_SENT = "Could not send a deleted event to Proton"

    /**
     * Whether a calendar none of whose members is the account's own fails the run: when it has members, none of them on
     * an address whose keys loaded (the reason is recorded by CalendarSyncAdapter.reloadKeysForUnknownMembership, and the
     * next sync reads the addresses again). A calendar listed without members, which nothing reads again, is left as it
     * was.
     */
    fun countsMissingOwnMember(members: List<CalendarMember>?): Boolean = !members.isNullOrEmpty()

    /**
     * Whether the deletion pass, having found no member of a calendar of the account's own, fails the run: when its read
     * of the calendars failed ([listRead] false), or the calendar is listed ([calendar] not null) with members, none of
     * them on a loaded address. A calendar a list read whole does not have is gone from Proton with its events, and one
     * listed without members is left as it was: neither fails anything, as before.
     */
    fun missingMemberFails(listRead: Boolean, calendar: CalendarInfo?): Boolean =
        !listRead || (calendar != null && countsMissingOwnMember(calendar.members))
}

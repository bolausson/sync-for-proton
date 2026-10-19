/**
 * Proton Calendar API data models.
 *
 * ## Event Content Arrays (Proton's 4-part event model, as measured against its API)
 * - **SharedEventContent**: type-2 (signed: DTSTART, DTEND, RRULE, ORGANIZER, SEQUENCE)
 *                          + type-3 (encrypted: SUMMARY, DESCRIPTION, LOCATION)
 * - **CalendarEventContent**: type-2 (signed: STATUS, TRANSP, EXDATE)
 * - **AttendeesEventContent**: type-3 (encrypted: ATTENDEE with x-pm-token)
 * - **PersonalEvents**: Notifications API field (not iCal VALARM)
 *
 * ## Encryption Model
 * - Creates: generate fresh SharedKeyPacket (PKESK), split type-3 into DataPacket (SEIPD)
 * - Updates: recover session key from existing SharedKeyPacket, do NOT send new KeyPacket
 * - AttendeesEventContent reuses the shared session key (same PKESK)
 */
package me.proton.sync.api.models

import com.google.gson.annotations.SerializedName

data class CalendarListResponse(
    @SerializedName("Code") val code: Int,
    @SerializedName("Calendars") val calendars: List<CalendarInfo>
)

data class CalendarInfo(
    @SerializedName("ID") val id: String,
    @SerializedName("Name") val name: String?,
    @SerializedName("Description") val description: String?,
    @SerializedName("Color") val color: String?,
    @SerializedName("Display") val display: Int,
    @SerializedName("Type") val type: Int,
    @SerializedName("Flags") val flags: Int,
    @SerializedName("Members") val members: List<CalendarMember>?
)

data class CalendarMember(
    @SerializedName("ID") val id: String,
    @SerializedName("Email") val email: String,
    @SerializedName("Permissions") val permissions: Int,
    @SerializedName("AddressID") val addressId: String,
    @SerializedName("CalendarID") val calendarId: String,
    @SerializedName("Color") val color: String?,
    @SerializedName("Display") val display: Int?
)

data class CalendarEventListResponse(
    @SerializedName("Code") val code: Int,
    @SerializedName("Events") val events: List<CalendarEventDetail>
)

data class CalendarEventDetailResponse(
    @SerializedName("Code") val code: Int,
    @SerializedName("Event") val event: CalendarEventDetail?
)

data class CalendarEventDetail(
    @SerializedName("ID") val id: String,
    @SerializedName("CalendarID") val calendarId: String,
    @SerializedName("CalendarKeyPacket") val calendarKeyPacket: String?,
    @SerializedName("CreateTime") val createTime: Long,
    @SerializedName("LastEditTime") val lastEditTime: Long,
    @SerializedName("StartTime") val startTime: Long,
    @SerializedName("EndTime") val endTime: Long,
    @SerializedName("FullDay") val fullDay: Int,
    @SerializedName("UID") val uid: String,
    @SerializedName("IsOrganizer") val isOrganizer: Int,
    @SerializedName("IsProtonProtonInvite") val isProtonProtonInvite: Int?,
    @SerializedName("AddressKeyPacket") val addressKeyPacket: String?,
    @SerializedName("AddressID") val addressId: String?,
    @SerializedName("SharedKeyPacket") val sharedKeyPacket: String?,
    @SerializedName("SharedEvents") val sharedEvents: List<EventPart>,
    @SerializedName("CalendarEvents") val calendarEvents: List<EventPart>,
    @SerializedName("PersonalEvents") val personalEvents: List<EventPart>?,
    @SerializedName("AttendeesEvents") val attendeesEvents: List<EventPart>?,
    /**
     * DEPRECATED by Proton in favour of [attendeesInfo]. Their own Android client annotates this
     * field `// deprecated in favor of attendeesInfo` (CalendarsApiImpl.EventResponse, v2.29.0).
     * Still populated today, but read [effectiveAttendees] instead of this so we keep working when
     * it stops being sent.
     */
    @SerializedName("Attendees") val attendees: List<EventAttendee>?,
    /** The current location of the cleartext attendee list. May be truncated, see [AttendeesInfo]. */
    @SerializedName("AttendeesInfo") val attendeesInfo: AttendeesInfo? = null,
    // Per-event colour, one of Proton's 20 accent hexes ("#RRGGBB") or null for "no
    // per-event colour" (the normal case — the event then inherits the calendar colour).
    // This is an API field: it is NOT carried inside the encrypted iCal, so it can only be
    // read from here. Matches WebClients CalendarEventSharedData.Color.
    @SerializedName("Color") val color: String? = null,
    /**
     * This member's reminders, CLEARTEXT and API-only.
     *
     * These are NOT rendered into VALARMs. Proton's own client labels this field
     * `// notifications that used to be in the PersonalEventContent`, i.e. they were moved OUT of
     * the encrypted part at some point, which is why `PersonalEvents` comes back empty even for
     * events that clearly have reminders (measured: 40 such events, `personalParts=0` on all).
     * Shape is identical to our upload-side [EventNotification]: Type + Trigger.
     */
    @SerializedName("Notifications") val notifications: List<EventNotification>? = null,
    /**
     * The ID the organiser's and every guest's copy of this event share. Invitations carry it
     * (X-PM-SHARED-EVENT-ID), so Proton Mail can link a guest's answer to this event.
     */
    @SerializedName("SharedEventID") val sharedEventId: String? = null
) {
    /**
     * The cleartext attendee list, preferring the current field and falling back to the deprecated
     * one. Cleartext means it survives a failed decrypt of the AttendeesEvents part, which is what
     * makes it usable as the "does this event really have guests" oracle in CalendarMapper.
     */
    val effectiveAttendees: List<EventAttendee>?
        get() = attendeesInfo?.attendees ?: attendees
}

/**
 * Proton's current wrapper for the cleartext attendee list.
 *
 * [moreAttendees] is 1 when the server truncated the list (WebClients
 * `ATTENDEE_MORE_ATTENDEES`: NO = 0, YES = 1), so a non-empty list is proof that guests EXIST but
 * an absent name is NOT proof that person is not invited. Fine for the existence check; treat the
 * per-attendee RSVP map as best-effort on large events.
 */
data class AttendeesInfo(
    @SerializedName("Attendees") val attendees: List<EventAttendee>? = null,
    @SerializedName("MoreAttendees") val moreAttendees: Int? = null
)

data class EventPart(
    @SerializedName("Type") val type: Int,
    @SerializedName("Data") val data: String,
    @SerializedName("Signature") val signature: String?,
    @SerializedName("MemberID") val memberId: String?,
    @SerializedName("Author") val author: String?
)

data class EventAttendee(
    @SerializedName("ID") val id: String,
    @SerializedName("Token") val token: String,
    @SerializedName("Status") val status: Int,
    @SerializedName("Permissions") val permissions: Int?,
    /** The note the guest gave with their answer, if any. */
    @SerializedName("Comment") val comment: AttendeeComment? = null
)

/** A guest's note with their answer (WebClients AttendeeComment): [type] 0 is cleartext, 1 encrypted and signed. */
data class AttendeeComment(
    @SerializedName("Message") val message: String,
    @SerializedName("Type") val type: Int
)

/**
 * Response of `GET calendar/v1/{calendarId}/events/{eventId}/attendees?Page=N`: the guests past the
 * first page, which comes with the event when its [AttendeesInfo.moreAttendees] is 1.
 */
data class CalendarEventAttendeesResponse(
    @SerializedName("Code") val code: Int? = null,
    @SerializedName("Attendees") val attendees: List<EventAttendee>? = null,
    @SerializedName("MoreAttendees") val moreAttendees: Int? = null
)

data class CalendarKeyResponse(
    @SerializedName("Code") val code: Int,
    @SerializedName("Keys") val keys: List<CalendarKey>
)

data class CalendarKey(
    @SerializedName("ID") val id: String,
    @SerializedName("CalendarID") val calendarId: String,
    @SerializedName("PrivateKey") val privateKey: String,
    @SerializedName("PassphraseID") val passphraseId: String,
    @SerializedName("Flags") val flags: Int
)

data class CalendarPassphraseResponse(
    @SerializedName("Code") val code: Int,
    @SerializedName("Passphrases") val passphrases: List<CalendarPassphrase>?,
    @SerializedName("Passphrase") val passphrase: CalendarPassphrase?
) {
    fun getAllPassphrases(): List<CalendarPassphrase> {
        // API may return "Passphrase" (singular) or "Passphrases" (plural)
        val result = mutableListOf<CalendarPassphrase>()
        passphrases?.let { result.addAll(it) }
        passphrase?.let { result.add(it) }
        return result
    }
}

data class CalendarPassphrase(
    @SerializedName("ID") val id: String,
    @SerializedName("Flags") val flags: Int,
    @SerializedName("MemberPassphrases") val memberPassphrases: List<MemberPassphrase>
)

data class MemberPassphrase(
    @SerializedName("MemberID") val memberId: String,
    @SerializedName("Passphrase") val passphrase: String,
    @SerializedName("Signature") val signature: String
)

data class CalendarEventSyncRequest(
    @SerializedName("MemberID") val memberId: String,
    @SerializedName("Events") val events: List<CalendarEventSyncOperation>
)

data class CalendarEventSyncOperation(
    @SerializedName("ID") val id: String? = null,
    // A delete operation has ONLY an ID field, a DeletionReason, and NO "Event" field.
    // A create/update includes an "Event" field with the encrypted event data.
    @SerializedName("Event") val event: CalendarEventSyncData? = null,
    // For creates: wrap in Overwrite flag (0=error on UID conflict, 1=overwrite)
    @SerializedName("Overwrite") val overwrite: Int? = null,
    // For deletes: 0=NORMAL, 1=CHANGE_CALENDAR
    @SerializedName("DeletionReason") val deletionReason: Int? = null
)

/**
 * The "Event" payload for create/update sync operations.
 * Contains the encrypted event parts split into KeyPacket + SharedEvents.
 */
data class CalendarEventSyncData(
    @SerializedName("SharedKeyPacket") val sharedKeyPacket: String? = null,
    @SerializedName("SharedEventContent") val sharedEventContent: List<EventPart>? = null,
    @SerializedName("CalendarKeyPacket") val calendarKeyPacket: String? = null,
    @SerializedName("CalendarEventContent") val calendarEventContent: List<EventPart>? = null,
    @SerializedName("AttendeesEventContent") val attendeesEventContent: List<EventPart>? = null,
    @SerializedName("Attendees") val attendees: List<SyncAttendee>? = null,
    @SerializedName("Permissions") val permissions: Int = 1,
    @SerializedName("IsOrganizer") val isOrganizer: Int = 1,
    @SerializedName("IsPersonalSingleEdit") val isPersonalSingleEdit: Boolean = false,
    @SerializedName("Notifications") val notifications: List<EventNotification>? = null,
    @SerializedName("Color") val color: String? = null,
    @SerializedName("AddedProtonAttendees") val addedProtonAttendees: List<ProtonAttendeeKeyPacket>? = null,
    @SerializedName("RemovedAttendeeAddresses") val removedAttendeeAddresses: List<String>? = null
)

data class ProtonAttendeeKeyPacket(
    @SerializedName("Email") val email: String,
    @SerializedName("AddressKeyPacket") val addressKeyPacket: String
)

data class SyncAttendee(
    @SerializedName("Token") val token: String,
    @SerializedName("Status") val status: Int,  // 0=NEEDS_ACTION, 1=TENTATIVE, 2=DECLINED, 3=ACCEPTED
    /** An object, not text. Left out (null), as Proton's Android client always does. */
    @SerializedName("Comment") val comment: AttendeeComment? = null
)

/** Response of `GET calendar/v1/{calendarId}/settings`. */
data class CalendarSettingsResponse(
    @SerializedName("Code") val code: Int,
    @SerializedName("CalendarSettings") val calendarSettings: CalendarSettingsData?
)

/**
 * A calendar's per-member defaults.
 *
 * [defaultPartDayNotifications] and [defaultFullDayNotifications] are the reminders an event gets
 * when it has NO per-event `Notifications` of its own, which is the overwhelmingly common case:
 * on a real account, only a few percent of events carried their own. Proton's editor shows these
 * defaults on such events, so a client that ignores them shows no reminders where Proton shows two.
 *
 * Full-day and part-day have separate lists because their triggers mean different things: a
 * part-day default is typically "-PT15M" (before the start), a full-day one "PT12H" (noon on the
 * day, since the event starts at midnight).
 */
data class CalendarSettingsData(
    @SerializedName("DefaultEventDuration") val defaultEventDuration: Int? = null,
    @SerializedName("DefaultPartDayNotifications") val defaultPartDayNotifications: List<EventNotification>? = null,
    @SerializedName("DefaultFullDayNotifications") val defaultFullDayNotifications: List<EventNotification>? = null
)

/**
 * Body for `PUT calendar/v1/{calendarId}/events/{eventId}/personal`.
 *
 * Both fields are this member's own data. Sending null for either means "no per-event value", i.e.
 * fall back to the calendar's defaults.
 */
data class UpdateEventPersonalPartRequest(
    @SerializedName("Notifications") val notifications: List<EventNotification>? = null,
    @SerializedName("Color") val color: String? = null
)

data class EventNotification(
    @SerializedName("Type") val type: Int,  // 0=EMAIL, 1=DEVICE
    @SerializedName("Trigger") val trigger: String  // e.g. "-PT15M"
)

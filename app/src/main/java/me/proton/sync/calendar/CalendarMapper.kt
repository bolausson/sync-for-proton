package me.proton.sync.calendar

import android.accounts.Account
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.net.Uri
import android.provider.CalendarContract
import net.fortuna.ical4j.model.component.VEvent
import net.fortuna.ical4j.model.property.*
import me.proton.sync.SyncConstants
import me.proton.sync.SyncUtils
import java.util.TimeZone

/**
 * Maps between ical4j VEvent objects and Android CalendarContract operations.
 *
 * Handles the full iCal property set: DTSTART, DTEND, RRULE, RDATE, EXDATE, EXRULE,
 * STATUS, TRANSP, ORGANIZER, VALARM (reminders), ATTENDEE, COLOR, RECURRENCE-ID,
 * UID, DURATION, and all-day event normalization.
 *
 * ## Samsung EXDATE Workaround
 * Samsung's CalendarProvider ignores the EXDATE column entirely — it only respects
 * cancelled exception events (STATUS_CANCELED + ORIGINAL_SYNC_ID). The
 * [buildExdateExceptionOps] method creates these exception events for each EXDATE
 * entry so deleted occurrences are hidden in all calendar apps.
 *
 * ## Key CalendarContract Requirements
 * - [DIRTY]=0 must be set explicitly for instance generation
 * - Recurring events use DURATION (not DTEND) per CalendarContract spec
 * - All-day dates normalized to midnight UTC
 * - [_SYNC_ID] set to Proton event ID for exception event linking
 *
 * ## Where the times come from (1.11.5)
 * Every time column is computed with java.time from the iCal TEXT by [DownloadTimes] and handed in as
 * [EventTimeColumns]; this mapper only copies it. ical4j's own values (its VTIMEZONE arithmetic, bundled
 * tzdb 2022g, and misreads of current definitions) are used only when the text could not be read with
 * certainty and the caller passes no columns, which keeps that event exactly as before 1.11.5.
 */
object CalendarMapper {

    internal fun mapToAndroidOps(
        vEvent: VEvent,
        account: Account,
        androidCalendarId: Long,
        existingEventId: Long?,
        protonEventId: String? = null,
        parentSyncId: String? = null,
        /**
         * Lowercase set of every Proton address this user owns on this account
         * (primary, alias, custom-domain). Used to decide whether an event's ORGANIZER
         * is "us" or a third-party — third-party events are written with
         * `GUESTS_CAN_MODIFY=0` so Android Calendar's UI presents them as read-only,
         * preventing local edits we'd silently drop on upload (RFC 5546 §3.2.4 — only
         * `REPLY` carrying `PARTSTAT` is a valid attendee-originated message).
         * Default is empty: callers without the address list get the prior behaviour.
         */
        userAddressEmails: Set<String> = emptySet(),
        /**
         * The address THIS account holds on the Proton calendar, resolved by `memberForUser`.
         *
         * Used to substitute for [accountName] when the iCal omits an ORGANIZER entirely and
         * account.name is a username rather than an email, and as the multi-address arm of the
         * answer-only test in [isAttendeeOnlyEvent].
         */
        calendarMemberEmail: String? = null,

        /**
         * Proton's authoritative RSVP map: x-pm-token (lowercase hex) -> attendee Status
         * (0 needs-action, 1 tentative, 2 declined, 3 accepted) from the event's cleartext
         * Attendees[] array. Participation status is taken from here, NOT the decrypted iCal
         * PARTSTAT: Proton never rewrites the encrypted iCal when an attendee RSVPs, so the
         * PARTSTAT stays frozen at the value the event was created with. Empty map -> fall
         * back to the iCal PARTSTAT (prior behaviour).
         */
        protonAttendeeStatusByToken: Map<String, Int> = emptyMap(),
        /**
         * Proton's per-event colour ("#RRGGBB") from the API field, or null when the event has
         * none. Proton does NOT put a COLOR property in the encrypted iCal, so this is the only
         * way the colour can reach the phone. Null is "no per-event colour", which is the normal
         * case, NOT "the user cleared it" — see [buildEventValues] for why that distinction
         * matters.
         */
        protonEventColor: String? = null,
        /**
         * Whether Proton's CLEARTEXT `Attendees[]` array says this event has guests. That array
         * lives outside the encrypted parts, so it is readable even when decryption fails, which
         * makes it the one trustworthy answer to "does this event have attendees at all".
         * See [attendeeDataTrustworthy] below for why that matters.
         */
        protonHasAttendees: Boolean = false,
        /**
         * This member's reminders, from Proton's CLEARTEXT `Notifications` API field. Proton does
         * not put VALARMs in the encrypted parts, so this is the only place reminders come from.
         */
        protonNotifications: List<me.proton.sync.api.models.EventNotification>? = null,
        /**
         * When true, do NOT clear `Events.DIRTY`. Set only when the local row has an unsent
         * personal-part change (reminder or colour) that must survive applying the server's shared
         * fields. Clearing the flag there would discard the user's edit before it was ever sent.
         */
        preserveDirty: Boolean = false,
        /**
         * The event's time columns computed with java.time from the raw text ([DownloadTimes]). Null keeps
         * ical4j's values, as before 1.11.5, for an event whose text could not be read with certainty.
         */
        times: EventTimeColumns? = null
    ): ArrayList<ContentProviderOperation> {
        val ops = ArrayList<ContentProviderOperation>()

        val eventsUri = SyncUtils.asCallerSyncAdapter(CalendarContract.Events.CONTENT_URI, account)

        // Resolve attendees and their real RSVP status once. Reused for the event row
        // (HAS_ATTENDEE_DATA + SELF_ATTENDEE_STATUS) and the per-attendee rows below.
        val mappedAttendees = mapAttendees(vEvent, protonAttendeeStatusByToken, userAddressEmails, calendarMemberEmail)
        val hasAttendees = mappedAttendees.isNotEmpty()
        val selfStatus = mappedAttendees.firstOrNull { it.isSelf }?.androidStatus

        // ATTENDEE rows are rewritten by delete-then-reinsert on update (below). That is only safe
        // when the guest list we parsed is actually the truth. ATTENDEE properties live in the
        // ENCRYPTED AttendeesEvents part, and CalendarDecryptor silently DROPS any part it cannot
        // decrypt (decryptEventPartWithMultipleKeys returns null and the part is skipped), so a
        // decryption failure yields a well-formed iCal that simply has no attendees in it —
        // indistinguishable, on its own, from an event that genuinely has none.
        //
        // Proton's cleartext Attendees[] array settles it, because it is not encrypted:
        //   cleartext says none                  -> parsed none is correct, rewrite (a real clear)
        //   cleartext says some, parsed some     -> rewrite
        //   cleartext says some, parsed NONE     -> decrypt or parse failed, DO NOT rewrite
        //
        // Getting the last case wrong is expensive, not cosmetic: the delete would wipe the guest
        // list locally, and uploadModifiedEvent reads those very rows back, so the user's next edit
        // to this event would push an empty attendee list to Proton and can email a cancellation to
        // every guest. Keeping a stale list is strictly better than destroying a real one.
        val attendeeDataTrustworthy = !protonHasAttendees || hasAttendees
        if (!attendeeDataTrustworthy) {
            me.proton.sync.SyncLog.w(
                "CalendarMapper",
                "Event ${protonEventId ?: "?"}: Proton reports attendees but none survived decryption/parsing - " +
                    "keeping the existing guest list and HAS_ATTENDEE_DATA instead of wiping them"
            )
        }

        if (existingEventId != null) {
            // Update existing event
            val values = buildEventValues(vEvent, androidCalendarId, protonEventId, account.name, parentSyncId, userAddressEmails, calendarMemberEmail, hasAttendees, selfStatus, protonEventColor, attendeeDataTrustworthy, preserveDirty, times)
            // Android's CalendarProvider REJECTS Events.SELF_ATTENDEE_STATUS on UPDATE with
            // IllegalArgumentException("Updating selfAttendeeStatus in Events table is not
            // allowed") — even for a sync adapter, even when the value is NONE, even on events
            // with no guests at all (verified directly against the provider, 2026-07-26).
            //
            // The event row, its reminders and its attendees are written as ONE applyBatch, so
            // leaving this column in did not just drop the column: the provider threw and the
            // ENTIRE update was discarded. Every remote edit to an already-synced event was
            // fetched, decrypted and then thrown away, on every calendar, since v1.5.14 when we
            // started writing this column. Only a mapping wipe (Force Remote→Local, which
            // re-INSERTs rows) ever brought such changes in, which is exactly what field reports
            // described. Reproduced end-to-end on a two-account shared calendar.
            //
            // The column is INSERT-only by design: on update the provider recomputes it from the
            // Attendees rows, which we delete and re-insert immediately below.
            values.remove(CalendarContract.Events.SELF_ATTENDEE_STATUS)
            ops.add(
                ContentProviderOperation.newUpdate(
                    ContentUris.withAppendedId(eventsUri, existingEventId)
                )
                    .withValues(values)
                    .build()
            )

            // Delete existing reminders and attendees, then re-insert
            // Use sync adapter URI to avoid marking parent event dirty
            val remindersUri = SyncUtils.asCallerSyncAdapter(CalendarContract.Reminders.CONTENT_URI, account)
            val attendeesUri = SyncUtils.asCallerSyncAdapter(CalendarContract.Attendees.CONTENT_URI, account)
            // Build the replacement reminder rows FIRST, so we can refuse to delete what we cannot
            // rebuild. If Proton says this event has reminders but every trigger failed to parse,
            // wiping the rows would silently remove the user's alarms with nothing to put back.
            val reminderOps = ArrayList<ContentProviderOperation>()
            addReminderOps(reminderOps, vEvent, account, eventId = existingEventId, protonNotifications = protonNotifications)
            val reminderDataTrustworthy = protonNotifications.isNullOrEmpty() || reminderOps.isNotEmpty()
            if (!reminderDataTrustworthy) {
                me.proton.sync.SyncLog.w(
                    "CalendarMapper",
                    "Event ${protonEventId ?: "?"}: Proton reports ${protonNotifications?.size} reminder(s) but none could be " +
                        "converted - keeping the existing reminder rows instead of wiping them"
                )
            }
            if (reminderDataTrustworthy) {
                ops.add(
                    ContentProviderOperation.newDelete(remindersUri)
                        .withSelection(
                            "${CalendarContract.Reminders.EVENT_ID} = ?",
                            arrayOf(existingEventId.toString())
                        )
                        .build()
                )
            }
            // Only touch the attendee rows when the parsed guest list is trustworthy. When it is
            // not, both the delete and the re-insert are skipped, leaving the existing rows exactly
            // as they are.
            if (attendeeDataTrustworthy) {
                ops.add(
                    ContentProviderOperation.newDelete(attendeesUri)
                        .withSelection(
                            "${CalendarContract.Attendees.EVENT_ID} = ?",
                            arrayOf(existingEventId.toString())
                        )
                        .build()
                )
            }

            if (reminderDataTrustworthy) {
                ops.addAll(reminderOps)
            }
            if (attendeeDataTrustworthy) {
                addAttendeeOps(ops, mappedAttendees, account, eventId = existingEventId)
            }
        } else {
            // Insert new event
            val values = buildEventValues(vEvent, androidCalendarId, protonEventId, account.name, parentSyncId, userAddressEmails, calendarMemberEmail, hasAttendees, selfStatus, protonEventColor, times = times)
            ops.add(
                ContentProviderOperation.newInsert(eventsUri)
                    .withValues(values)
                    .build()
            )

            addReminderOps(ops, vEvent, account, backReference = 0, protonNotifications = protonNotifications)
            addAttendeeOps(ops, mappedAttendees, account, backReference = 0)
        }

        return ops
    }

    /**
     * Create cancelled exception events for each EXDATE in a recurring event.
     * Samsung's CalendarProvider ignores the EXDATE column entirely — it only
     * respects exception events with STATUS_CANCELED + ORIGINAL_SYNC_ID.
     * Must be called AFTER the parent event is inserted (needs parent event ID).
     */
    internal fun buildExdateExceptionOps(
        vEvent: VEvent,
        account: Account,
        androidCalendarId: Long,
        parentEventId: Long,
        parentSyncId: String,
        /**
         * The series' times from the raw text ([DownloadTimes]): the cancelled rows then sit at exactly the
         * instants the provider expands the series to with the phone's rules, which is the only way they
         * hide an occurrence. Null keeps ical4j's instants, as before 1.11.5.
         */
        times: EventTimeColumns? = null
    ): ArrayList<ContentProviderOperation> {
        val ops = ArrayList<ContentProviderOperation>()
        val exdates = vEvent.getProperties<ExDate>("EXDATE") ?: return ops
        val rrule = vEvent.getProperty<RRule>("RRULE") ?: return ops
        val eventsUri = SyncUtils.asCallerSyncAdapter(CalendarContract.Events.CONTENT_URI, account)

        // Determine if parent is all-day
        val isAllDay = times?.allDay
            ?: vEvent.startDate?.let { it.date !is net.fortuna.ical4j.model.DateTime } ?: false
        val timezone = times?.exdateRowTimezone ?: vEvent.startDate?.let {
            if (it.date is net.fortuna.ical4j.model.DateTime) {
                (it.date as net.fortuna.ical4j.model.DateTime).timeZone?.id ?: "UTC"
            } else "UTC"
        } ?: "UTC"

        // Get duration from parent for the exception events
        val duration = vEvent.duration?.duration?.let { "P${it.toString().removePrefix("PT").removePrefix("P")}" }
            ?: vEvent.getProperty<net.fortuna.ical4j.model.property.Duration>("DURATION")?.value

        // Every excluded occurrence's start, in order and de-duplicated: from the raw text when given,
        // otherwise from ical4j as before.
        val instants: List<Long> = times?.exdateInstants ?: run {
            val seen = LinkedHashSet<Long>()
            for (exdate in exdates) {
                val dates = exdate.dates ?: continue
                for (date in dates) seen.add(if (isAllDay) normalizeToUtcMidnight(date) else date.time)
            }
            seen.toList()
        }

        for (millis in instants) {
            val values = android.content.ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, androidCalendarId)
                put(CalendarContract.Events.ORIGINAL_SYNC_ID, parentSyncId)
                put(CalendarContract.Events.ORIGINAL_INSTANCE_TIME, millis)
                put(CalendarContract.Events.ORIGINAL_ALL_DAY, if (isAllDay) 1 else 0)
                put(CalendarContract.Events.STATUS, CalendarContract.Events.STATUS_CANCELED)
                put(CalendarContract.Events.DTSTART, millis)
                if (isAllDay) {
                    put(CalendarContract.Events.ALL_DAY, 1)
                    put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
                    put(CalendarContract.Events.DTEND, millis + SyncConstants.ONE_DAY_MS)
                } else {
                    put(CalendarContract.Events.ALL_DAY, 0)
                    put(CalendarContract.Events.EVENT_TIMEZONE, timezone)
                    if (duration != null) {
                        put(CalendarContract.Events.DURATION, duration)
                    } else {
                        put(CalendarContract.Events.DTEND, millis + SyncConstants.ONE_HOUR_MS) // 1h default
                    }
                }
                put(CalendarContract.Events.DIRTY, 0)
            }
            ops.add(ContentProviderOperation.newInsert(eventsUri).withValues(values).build())
        }
        return ops
    }

    private fun buildEventValues(
        vEvent: VEvent,
        androidCalendarId: Long,
        protonEventId: String? = null,
        accountName: String? = null,
        parentSyncId: String? = null,
        userAddressEmails: Set<String> = emptySet(),
        calendarMemberEmail: String? = null,
        hasAttendees: Boolean = false,
        selfAttendeeStatus: Int? = null,
        protonEventColor: String? = null,
        /**
         * False when the parsed guest list could not be trusted (see `attendeeDataTrustworthy` in
         * [mapToAndroidOps]). The attendee summary columns are then left untouched, so we do not
         * announce "no guests" on an event whose real guest list we are deliberately preserving.
         */
        writeAttendeeSummary: Boolean = true,
        preserveDirty: Boolean = false,
        /** The time columns from the raw text ([DownloadTimes]); null keeps ical4j's, as before 1.11.5. */
        times: EventTimeColumns? = null
    ): android.content.ContentValues {
        return android.content.ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, androidCalendarId)

            // Sync ID — required for CalendarProvider to create exception events
            // (single-occurrence delete/edit of recurring series)
            if (protonEventId != null) {
                put(CalendarContract.Events._SYNC_ID, protonEventId)
            }

            // RECURRENCE-ID: this event is an exception to a recurring series
            val recurrenceId = vEvent.getProperty<net.fortuna.ical4j.model.property.RecurrenceId>("RECURRENCE-ID")
            if (recurrenceId != null && parentSyncId != null) {
                // Android uses ORIGINAL_SYNC_ID to link exception to parent recurring event
                put(CalendarContract.Events.ORIGINAL_SYNC_ID, parentSyncId)
                if (times?.originalInstanceTime != null) {
                    // From the RECURRENCE-ID's own text with the PHONE's rules: the provider links this
                    // override to the occurrence it expands with the phone's data, by the exact millisecond.
                    put(CalendarContract.Events.ORIGINAL_ALL_DAY, if (times.originalAllDay) 1 else 0)
                    put(CalendarContract.Events.ORIGINAL_INSTANCE_TIME, times.originalInstanceTime)
                } else {
                    val isOrigAllDay = recurrenceId.date != null && recurrenceId.date !is net.fortuna.ical4j.model.DateTime
                    put(CalendarContract.Events.ORIGINAL_ALL_DAY, if (isOrigAllDay) 1 else 0)
                    put(CalendarContract.Events.ORIGINAL_INSTANCE_TIME,
                        if (isOrigAllDay) normalizeToUtcMidnight(recurrenceId.date) else recurrenceId.date.time)
                }
            } else if (recurrenceId != null) {
                // parentSyncId unresolved: the override can't be linked to its series and will
                // insert as a stray standalone the provider won't attach. Surface it for diagnosis.
                me.proton.sync.SyncLog.d("CalendarMapper", "Event $protonEventId (uid=${vEvent.uid?.value}) has RECURRENCE-ID but no parentSyncId - inserting WITHOUT ORIGINAL_SYNC_ID link; override will not attach to its series")
            }

            // Title
            vEvent.summary?.let { put(CalendarContract.Events.TITLE, it.value) }

            // Description
            vEvent.description?.let { put(CalendarContract.Events.DESCRIPTION, it.value) }

            // Location
            vEvent.location?.let { put(CalendarContract.Events.EVENT_LOCATION, it.value) }

            // Start, end, all-day, recurrence and exclusions.
            if (times != null) putTimeColumns(this, times, vEvent) else putIcal4jTimeColumns(this, vEvent)

            // Status
            val status = vEvent.status
            if (status != null) {
                val androidStatus = when (status) {
                    Status.VEVENT_TENTATIVE -> CalendarContract.Events.STATUS_TENTATIVE
                    Status.VEVENT_CONFIRMED -> CalendarContract.Events.STATUS_CONFIRMED
                    Status.VEVENT_CANCELLED -> CalendarContract.Events.STATUS_CANCELED
                    else -> CalendarContract.Events.STATUS_TENTATIVE
                }
                put(CalendarContract.Events.STATUS, androidStatus)
            }

            // Organizer — when the iCal has no ORGANIZER (Proton omits it on
            // events with no attendees), default to the calendar's owner email so
            // Google Calendar's editability check `organizer == ownerAccount`
            // returns true and the event shows the edit button. Defaulting to
            // accountName instead breaks on accounts where the sync framework's
            // account.name is a username rather than an email, because Google
            // Calendar then either falls through to GUESTS_CAN_MODIFY (which it
            // ignores on calendars whose ACCOUNT_NAME ≠ OWNER_ACCOUNT) or treats
            // the non-email organizer as a third party and hides the edit UI.
            val organizerEmail = vEvent.organizer?.calAddress?.toString()?.removePrefix("mailto:")
            put(CalendarContract.Events.ORGANIZER, organizerEmail ?: calendarMemberEmail ?: accountName ?: "")

            // Decide whether the user is the organizer of this event. The user is
            // an "attendee only" iff the iCal organizer is a real email AND it
            // doesn't match any of the user's own Proton addresses AND it doesn't
            // match the calendar owner. The calendar-owner branch matters for
            // multi-address accounts: the user controls their own calendar
            // regardless of which of their addresses ended up signing an event.
            //
            // For attendee-only events we mark the event guest-restricted so
            // Android Calendar's UI presents it as read-only — preventing local
            // edits we'd silently drop on upload (RFC 5546 §3.2.4: only REPLY
            // carrying PARTSTAT is a valid attendee-originated message; other
            // field changes have no RFC-defined attendee path and Proton doesn't
            // implement COUNTER).
            //
            // The flags MUST be written both ways (1,1 when user is organizer;
            // 0,0 when attendee). Writing only the 0 case meant any event flagged
            // read-only by an earlier sync stayed read-only forever, since the
            // CalendarProvider UPDATE only touches columns we explicitly put().
            // Measured on a device, not assumed: Proton refuses ANY edit by a non-organiser to an
            // event that has guests on a shared calendar, so a co-owner's event really is answer-only.
            // The rule and the evidence live in EventEditability.kt.
            val isAttendeeOnly = isAttendeeOnlyEvent(
                organizerEmail = organizerEmail,
                userAddresses = userAddressEmails,
                calendarMemberEmail = calendarMemberEmail,
            )
            if (isAttendeeOnly) {
                put(CalendarContract.Events.GUESTS_CAN_MODIFY, 0)
                put(CalendarContract.Events.GUESTS_CAN_INVITE_OTHERS, 0)
            } else {
                put(CalendarContract.Events.GUESTS_CAN_MODIFY, 1)
                put(CalendarContract.Events.GUESTS_CAN_INVITE_OTHERS, 1)
            }

            // Transparency/Availability — maps FREE/BUSY for calendar apps
            val transp = vEvent.transparency
            if (transp != null) {
                val avail = if (transp.value?.uppercase() == "TRANSPARENT") {
                    CalendarContract.Events.AVAILABILITY_FREE
                } else {
                    CalendarContract.Events.AVAILABILITY_BUSY
                }
                put(CalendarContract.Events.AVAILABILITY, avail)
            }

            // Event colour. Proton carries this in the API field CalendarEventDetail.Color and
            // NOT inside the encrypted iCal, so the RFC 7986 COLOR property is effectively never
            // present on a Proton-authored event (it only appears on events imported from other
            // tools). Prefer Proton's value, fall back to the iCal property.
            //
            // Written only when a colour is actually present, never cleared. An absent colour
            // means "this event has no per-event colour, so it inherits the calendar's", which is
            // the normal case for the overwhelming majority of events. It does NOT mean the user
            // cleared one. Clearing here would blank EVENT_COLOR on every update, and
            // uploadModifiedEvent reads that same column straight back and would push the blank
            // to Proton, where nothing can restore it.
            val colorSource = protonEventColor?.takeIf { it.isNotBlank() }
                ?: vEvent.getProperty<net.fortuna.ical4j.model.Property>("COLOR")?.value
            if (!colorSource.isNullOrBlank()) {
                try {
                    put(CalendarContract.Events.EVENT_COLOR, android.graphics.Color.parseColor(colorSource.trim()))
                } catch (_: Exception) { /* ignore invalid colour values */ }
            }

            // UID for dedup
            vEvent.uid?.let { put(CalendarContract.Events.UID_2445, it.value) }

            // Attendee summary on the event row. HAS_ATTENDEE_DATA tells calendar apps the
            // guest list is complete; SELF_ATTENDEE_STATUS is the field Google/Samsung/Business
            // Calendar read to show whether *you* responded (and whether to offer the RSVP
            // buttons). Both are written explicitly rather than relying on the CalendarProvider
            // deriving them from the attendee rows, which is OEM-dependent. The status itself
            // comes from Proton's authoritative Attendees[] array, not the frozen iCal PARTSTAT.
            if (writeAttendeeSummary) {
                put(CalendarContract.Events.HAS_ATTENDEE_DATA, if (hasAttendees) 1 else 0)
                // SELF_ATTENDEE_STATUS is written ONLY for events that actually have guests.
                //
                // Writing it on an event with no attendees is what CREATES the phantom: AOSP's
                // CalendarProvider2 fabricates an Attendees row for the calendar's OWNER_ACCOUNT
                // whenever this column is present on an INSERT (createAttendeeEntry). Every solo
                // event therefore ended up carrying a guest nobody invited, and the upload path
                // read it straight back, so a local edit either added a real participant to a
                // Proton event that had none, or was rejected with code 2011 "Not allowed to
                // update attendee list" and eventually discarded. Confirmed from the server side:
                // editing such an event in Proton web now offers to email that phantom participant.
                //
                // Skipping the column for guest-less events costs nothing. There is no self-status
                // to show when there are no attendees, and on UPDATE the provider recomputes the
                // value from the Attendees rows anyway (the column is rejected on update outright,
                // see the note in mapToAndroidOps).
                if (hasAttendees) {
                    put(
                        CalendarContract.Events.SELF_ATTENDEE_STATUS,
                        selfAttendeeStatus ?: CalendarContract.Attendees.ATTENDEE_STATUS_NONE
                    )
                }
            }

            // Mark as clean — CalendarProvider requires DIRTY=0 for sync adapter events
            // to generate instances. Without this, events exist in the events table but
            // CalendarProvider's instance expander skips them, making them invisible in
            // Google Calendar and Samsung Calendar (Business Calendar does its own expansion).
            //
            // The one exception is a local personal-part edit (reminder or colour) that has not
            // been uploaded yet: clearing the flag there would discard the user's change before it
            // was ever sent. Leaving the column untouched keeps the row exactly as the user's own
            // edit left it, which is already an instance-generating state, so visibility is
            // unaffected. See EventConflictGuard.
            if (!preserveDirty) {
                put(CalendarContract.Events.DIRTY, 0)
            }
        }
    }

    /**
     * The time columns from [times]: java.time instants computed from the raw text ([DownloadTimes]). The
     * RRULE and EXRULE text still comes from ical4j: it holds no zone arithmetic (ical4j only reorders the
     * parts, and UNTIL is not bound to a zone).
     */
    private fun putTimeColumns(values: android.content.ContentValues, times: EventTimeColumns, vEvent: VEvent) {
        with(values) {
            if (times.dtStart != null) {
                put(CalendarContract.Events.DTSTART, times.dtStart)
                put(CalendarContract.Events.EVENT_TIMEZONE, times.eventTimezone ?: "UTC")
            }
            // Absent for a series that carries DURATION instead, exactly as the ical4j path removes it.
            if (times.dtEnd != null) {
                put(CalendarContract.Events.DTEND, times.dtEnd)
                put(CalendarContract.Events.EVENT_END_TIMEZONE, times.endTimezone ?: "UTC")
            }
            if (times.duration != null) put(CalendarContract.Events.DURATION, times.duration)
            put(CalendarContract.Events.ALL_DAY, if (times.allDay) 1 else 0)
            vEvent.getProperty<RRule>("RRULE")?.let { put(CalendarContract.Events.RRULE, it.value) }
            times.rdateColumn?.let { put(CalendarContract.Events.RDATE, it) }
            // Proton carries EXDATE in BOTH the shared signed and the calendar signed part, so each excluded
            // date arrives at least twice: DownloadTimes keeps the first of each, in the order the parts list them.
            times.exdateColumn?.let { put(CalendarContract.Events.EXDATE, it) }
            vEvent.getProperty<ExRule>("EXRULE")?.let { put(CalendarContract.Events.EXRULE, it.value) }
        }
    }

    /**
     * The time columns as every version before 1.11.5 wrote them, from ical4j's computed values. Kept only for
     * an event whose raw text could not be read with certainty (DownloadTimes.Unevaluable), so such an event
     * behaves exactly as it always did.
     */
    private fun putIcal4jTimeColumns(values: android.content.ContentValues, vEvent: VEvent) {
        with(values) {
            // Start time
            val dtStart = vEvent.startDate

            // All-day event detection
            val isAllDay = dtStart?.date != null && dtStart.date !is net.fortuna.ical4j.model.DateTime
            if (dtStart != null) {
                if (isAllDay) {
                    // All-day: normalize to midnight UTC for the correct date.
                    // ical4j Date stores epoch millis in JVM default timezone, but Android
                    // needs midnight UTC. Without this, events shift ±1 day in non-UTC zones.
                    put(CalendarContract.Events.DTSTART, normalizeToUtcMidnight(dtStart.date))
                } else {
                    put(CalendarContract.Events.DTSTART, dtStart.date.time)
                }
                if (isAllDay || dtStart.isUtc || dtStart.timeZone == null) {
                    put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
                } else {
                    put(CalendarContract.Events.EVENT_TIMEZONE, dtStart.timeZone.id)
                }
            }

            // End time or duration
            val dtEnd = vEvent.endDate
            val duration = vEvent.duration
            if (dtEnd != null) {
                if (isAllDay) {
                    put(CalendarContract.Events.DTEND, normalizeToUtcMidnight(dtEnd.date))
                } else {
                    put(CalendarContract.Events.DTEND, dtEnd.date.time)
                }
                if (isAllDay || dtEnd.isUtc || dtEnd.timeZone == null) {
                    put(CalendarContract.Events.EVENT_END_TIMEZONE, "UTC")
                } else {
                    put(CalendarContract.Events.EVENT_END_TIMEZONE, dtEnd.timeZone.id)
                }
            } else if (duration != null) {
                put(CalendarContract.Events.DURATION, duration.value)
            }

            if (isAllDay) {
                put(CalendarContract.Events.ALL_DAY, 1)
            } else {
                put(CalendarContract.Events.ALL_DAY, 0)
            }

            // Recurrence rule
            val rrule = vEvent.getProperty<RRule>("RRULE")
            if (rrule != null) {
                put(CalendarContract.Events.RRULE, rrule.value)
                // When there's an RRULE, use DURATION instead of DTEND
                if (dtEnd != null && duration == null && dtStart != null) {
                    val durationMs = dtEnd.date.time - dtStart.date.time
                    val durationSec = durationMs / 1000
                    put(CalendarContract.Events.DURATION, "P${durationSec}S")
                    remove(CalendarContract.Events.DTEND)
                    remove(CalendarContract.Events.EVENT_END_TIMEZONE)
                }
            }

            // Recurrence dates (RDATE) — additional occurrences
            val rdate = vEvent.getProperty<RDate>("RDATE")
            if (rdate != null) {
                put(CalendarContract.Events.RDATE, rdate.value)
            }

            // Exception dates (EXDATE) — removed occurrences.
            //
            // Proton carries EXDATE in BOTH the shared signed and the calendar signed part, and the
            // caller hands us one VEvent parsed from every part together, so each excluded date
            // arrives at least twice. Joining the properties as they came wrote "a,b,a,b" into the
            // column; a phone that later uploaded that column sent the duplicates on, and the next
            // download doubled them again. Keep the first occurrence of each date, in the order the
            // parts list them.
            val exdates = vEvent.getProperties<ExDate>("EXDATE")
            if (exdates != null && exdates.isNotEmpty()) {
                val dates = exdates
                    .flatMap { it.value.split(",") }
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .distinct()
                if (dates.isNotEmpty()) put(CalendarContract.Events.EXDATE, dates.joinToString(","))
            }

            // Exception rule (EXRULE) — rare but supported
            val exrule = vEvent.getProperty<ExRule>("EXRULE")
            if (exrule != null) {
                put(CalendarContract.Events.EXRULE, exrule.value)
            }
        }
    }

    /**
     * Convert a Proton notification trigger to Android's `Reminders.MINUTES`, which counts minutes
     * BEFORE the event. Proton uses ISO-8601 durations: a NEGATIVE one is "before the start"
     * ("-PT15M" = 15 minutes before) and becomes a positive MINUTES. A POSITIVE one is "after the
     * start", used on all-day events ("PT12H" = 12:00 on the day, since the event starts at
     * midnight), and becomes a negative MINUTES, which is the same convention Google Calendar uses
     * for all-day reminders.
     *
     * Returns null for anything unparseable, so a malformed trigger drops that one reminder rather
     * than the whole event.
     */
    private fun triggerToMinutesBefore(trigger: String): Int? {
        return try {
            val minutes = -java.time.Duration.parse(trigger.trim()).toMinutes()
            val value = minutes.toInt()
            // MINUTES_DEFAULT (-1) is a sentinel meaning "use the calendar's default reminder", so
            // never emit it literally; a trigger of exactly PT1M becomes "at the start" instead.
            if (value == CalendarContract.Reminders.MINUTES_DEFAULT) 0 else value
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Reminder inserts for an event that already exists locally and is NOT being rewritten.
     *
     * Used by the one-time backfill for events that inherit the calendar's default reminders: the
     * per-event skip gate means those events are never rewritten, so they would otherwise never
     * receive the defaults. Adds rows only; the event row is left untouched.
     */
    fun addReminderOpsForBackfill(
        ops: ArrayList<ContentProviderOperation>,
        account: Account,
        eventId: Long,
        notifications: List<me.proton.sync.api.models.EventNotification>
    ) {
        addReminderOps(
            ops = ops,
            vEvent = VEvent(),
            account = account,
            eventId = eventId,
            protonNotifications = notifications
        )
    }

    private fun addReminderOps(
        ops: ArrayList<ContentProviderOperation>,
        vEvent: VEvent,
        account: Account,
        eventId: Long? = null,
        backReference: Int? = null,
        protonNotifications: List<me.proton.sync.api.models.EventNotification>? = null
    ) {
        val uri = SyncUtils.asCallerSyncAdapter(CalendarContract.Reminders.CONTENT_URI, account)

        // Proton keeps per-event reminders in the CLEARTEXT `Notifications` API field and does NOT
        // render them into VALARMs. Measured on a real account: 40 events had Notifications and
        // EVERY one of them came back with PersonalEvents empty (personalParts=0), so there was no
        // VALARM to parse and reminders set in Proton never reached the phone at all. The API field
        // is the only source; the VALARM path below is kept as a fallback for events imported from
        // other tools, which can legitimately carry alarms in the iCal.
        if (!protonNotifications.isNullOrEmpty()) {
            for (notification in protonNotifications) {
                val minutes = triggerToMinutesBefore(notification.trigger) ?: continue
                // Proton Type: 0 = EMAIL, 1 = DEVICE.
                val method = if (notification.type == 0) {
                    CalendarContract.Reminders.METHOD_EMAIL
                } else {
                    CalendarContract.Reminders.METHOD_ALERT
                }
                val op = ContentProviderOperation.newInsert(uri)
                if (eventId != null) {
                    op.withValue(CalendarContract.Reminders.EVENT_ID, eventId)
                } else {
                    op.withValueBackReference(CalendarContract.Reminders.EVENT_ID, backReference!!)
                }
                op.withValue(CalendarContract.Reminders.MINUTES, minutes)
                op.withValue(CalendarContract.Reminders.METHOD, method)
                ops.add(op.build())
            }
            return
        }

        val alarms = vEvent.alarms ?: return
        for (alarm in alarms) {
            val trigger = alarm.trigger ?: continue
            val dur = trigger.duration ?: continue

            // Convert duration to minutes before event
            // ical4j TemporalAmountAdapter — use java.time.Duration
            val minutes = try {
                val duration = java.time.Duration.from(dur)
                Math.abs(duration.toMinutes()).toInt()
            } catch (e: Exception) {
                15 // Default fallback
            }

            // Map VALARM ACTION to CalendarContract.Reminders.METHOD, for iCal from other tools
            // (Proton's own reminders come from the Notifications field above): ACTION=EMAIL maps
            // to METHOD_EMAIL, AUDIO to METHOD_ALARM, and anything else (DISPLAY, PROCEDURE) to
            // METHOD_ALERT.
            val method = when (alarm.action?.value?.uppercase()) {
                "EMAIL" -> CalendarContract.Reminders.METHOD_EMAIL
                "AUDIO" -> CalendarContract.Reminders.METHOD_ALARM
                else -> CalendarContract.Reminders.METHOD_ALERT
            }

            val op = ContentProviderOperation.newInsert(uri)
            if (eventId != null) {
                op.withValue(CalendarContract.Reminders.EVENT_ID, eventId)
            } else {
                op.withValueBackReference(CalendarContract.Reminders.EVENT_ID, backReference!!)
            }
            op.withValue(CalendarContract.Reminders.MINUTES, minutes)
            op.withValue(CalendarContract.Reminders.METHOD, method)
            ops.add(op.build())
        }
    }

    /** Per-attendee write model, resolved once from the iCal + Proton's RSVP array. */
    private data class MappedAttendee(
        val email: String,
        val name: String?,
        val androidStatus: Int,
        val relationship: Int,
        val type: Int,
        val isSelf: Boolean
    )

    /**
     * Resolve the event's attendees into write-ready rows.
     *
     * Participation status comes from Proton's cleartext `Attendees[].Status`
     * ([protonStatusByToken]) — matched per attendee by x-pm-token — NOT from the iCal
     * PARTSTAT. Proton freezes the encrypted iCal's PARTSTAT at creation time and records
     * RSVP responses only in the cleartext array, so reading PARTSTAT would always report the
     * original NEEDS-ACTION and a received-and-accepted invite would look unanswered in every
     * calendar app. When the token can't be matched (e.g. the array wasn't fetched) we fall
     * back to the iCal PARTSTAT.
     */
    private fun mapAttendees(
        vEvent: VEvent,
        protonStatusByToken: Map<String, Int>,
        userAddressEmails: Set<String>,
        calendarMemberEmail: String?
    ): List<MappedAttendee> {
        val attendees = vEvent.getProperties<Attendee>("ATTENDEE") ?: return emptyList()
        val uid = vEvent.uid?.value
        val ownerLower = calendarMemberEmail?.lowercase()?.trim()
        val result = ArrayList<MappedAttendee>(attendees.size)
        for (attendee in attendees) {
            val email = attendee.calAddress?.toString()?.removePrefix("mailto:") ?: continue
            val emailLower = email.lowercase().trim()
            val name = attendee.getParameter<net.fortuna.ical4j.model.parameter.Cn>("CN")?.value

            val protonStatus = resolveProtonStatus(attendee, emailLower, uid, protonStatusByToken)
            val androidStatus = if (protonStatus != null) {
                protonStatusToAndroid(protonStatus)
            } else {
                partStatToAndroid(attendee.getParameter<net.fortuna.ical4j.model.parameter.PartStat>("PARTSTAT"))
            }

            val role = attendee.getParameter<net.fortuna.ical4j.model.parameter.Role>("ROLE")?.value?.uppercase()
            val relationship = when (role) {
                "CHAIR" -> CalendarContract.Attendees.RELATIONSHIP_ORGANIZER
                else -> CalendarContract.Attendees.RELATIONSHIP_ATTENDEE
            }
            val type = when (role) {
                "OPT-PARTICIPANT" -> CalendarContract.Attendees.TYPE_OPTIONAL
                "NON-PARTICIPANT" -> CalendarContract.Attendees.TYPE_NONE
                else -> CalendarContract.Attendees.TYPE_REQUIRED
            }
            val isSelf = emailLower in userAddressEmails || (ownerLower != null && emailLower == ownerLower)
            result.add(MappedAttendee(email, name, androidStatus, relationship, type, isSelf))
        }
        return result
    }

    /**
     * Find this attendee's Proton RSVP status by x-pm-token. Prefers the token Proton itself
     * wrote into the encrypted ATTENDEE line (an exact match to the cleartext
     * Attendees[].Token); falls back to reconstructing SHA-1(uid + lowercase email), the same
     * derivation the upload path uses (and Proton accepts). Returns null when the map is empty
     * or no token matches.
     */
    private fun resolveProtonStatus(
        attendee: Attendee,
        emailLower: String,
        uid: String?,
        protonStatusByToken: Map<String, Int>
    ): Int? {
        if (protonStatusByToken.isEmpty()) return null
        val icsToken = try {
            attendee.getParameter<net.fortuna.ical4j.model.parameter.XParameter>("X-PM-TOKEN")?.value?.lowercase()
        } catch (_: Exception) { null }
        if (icsToken != null) protonStatusByToken[icsToken]?.let { return it }
        if (uid != null) {
            protonStatusByToken[sha1Hex("$uid$emailLower")]?.let { return it }
        }
        return null
    }

    private fun sha1Hex(input: String): String =
        java.security.MessageDigest.getInstance("SHA-1")
            .digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }

    /** Proton status (0 needs-action, 1 tentative, 2 declined, 3 accepted) -> Android. */
    private fun protonStatusToAndroid(protonStatus: Int): Int = when (protonStatus) {
        3 -> CalendarContract.Attendees.ATTENDEE_STATUS_ACCEPTED
        2 -> CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED
        1 -> CalendarContract.Attendees.ATTENDEE_STATUS_TENTATIVE
        else -> CalendarContract.Attendees.ATTENDEE_STATUS_INVITED
    }

    private fun partStatToAndroid(partStat: net.fortuna.ical4j.model.parameter.PartStat?): Int =
        when (partStat?.value?.uppercase()) {
            "ACCEPTED" -> CalendarContract.Attendees.ATTENDEE_STATUS_ACCEPTED
            "DECLINED" -> CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED
            "TENTATIVE" -> CalendarContract.Attendees.ATTENDEE_STATUS_TENTATIVE
            else -> CalendarContract.Attendees.ATTENDEE_STATUS_INVITED
        }

    private fun addAttendeeOps(
        ops: ArrayList<ContentProviderOperation>,
        mappedAttendees: List<MappedAttendee>,
        account: Account,
        eventId: Long? = null,
        backReference: Int? = null
    ) {
        if (mappedAttendees.isEmpty()) return
        val uri = SyncUtils.asCallerSyncAdapter(CalendarContract.Attendees.CONTENT_URI, account)
        for (att in mappedAttendees) {
            val op = ContentProviderOperation.newInsert(uri)
            if (eventId != null) {
                op.withValue(CalendarContract.Attendees.EVENT_ID, eventId)
            } else {
                op.withValueBackReference(CalendarContract.Attendees.EVENT_ID, backReference!!)
            }
            op.withValue(CalendarContract.Attendees.ATTENDEE_EMAIL, att.email)
            if (att.name != null) {
                op.withValue(CalendarContract.Attendees.ATTENDEE_NAME, att.name)
            }
            op.withValue(CalendarContract.Attendees.ATTENDEE_STATUS, att.androidStatus)
            op.withValue(CalendarContract.Attendees.ATTENDEE_RELATIONSHIP, att.relationship)
            op.withValue(CalendarContract.Attendees.ATTENDEE_TYPE, att.type)
            ops.add(op.build())
        }
    }

    /**
     * Normalize an iCal DATE (all-day) value to midnight UTC of the same calendar date,
     * which is what Android `ALL_DAY` events require.
     *
     * ical4j parses VALUE=DATE as midnight **UTC** by default (`TimeZones.getDateTimeZone()`;
     * the JVM-default-timezone behaviour applies only with the opt-in
     * `net.fortuna.ical4j.timezone.date.floating=true`, which this app never sets). The
     * previous implementation assumed JVM-default parsing and derived the calendar date by
     * formatting the epoch in the DEVICE timezone — a no-op east of UTC (where it was
     * written and tested) but a one-day-earlier shift for every remotely-authored all-day
     * event on devices west of UTC (v1.6.3 fix).
     *
     * Reading the ical4j Date's own `toString()` sidesteps the assumption entirely: it
     * formats with the same timezone it parsed with, so it always reproduces the literal
     * `yyyyMMdd` from the ICS regardless of device timezone or the floating setting.
     */
    internal fun normalizeToUtcMidnight(date: java.util.Date): Long {
        val dateStr = if (date is net.fortuna.ical4j.model.Date &&
            date !is net.fortuna.ical4j.model.DateTime) {
            date.toString()
        } else {
            // Defensive fallback — all-day call sites always pass an ical4j Date. Read the
            // date in UTC, matching ical4j's default DATE parsing.
            java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }.format(date)
        }
        val utcSdf = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
        utcSdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return utcSdf.parse(dateStr)!!.time
    }
}

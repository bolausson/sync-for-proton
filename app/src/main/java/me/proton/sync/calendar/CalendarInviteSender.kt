package me.proton.sync.calendar

import com.google.gson.Gson
import me.proton.sync.SyncConstants
import me.proton.sync.SyncLog
import me.proton.sync.api.ProtonApiClient
import me.proton.sync.api.protonRecipientKey
import me.proton.sync.api.unexpectedKeyLookupFailure
import me.proton.sync.crypto.KeyManager
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Sends calendar invitation emails to attendees via Proton's mail API.
 *
 * Matches the official Proton Calendar WebUI behavior:
 * - Sends ONE email per group (new guests, existing guests, removed guests), each to all its
 *   recipients at once (not separate emails per recipient)
 * - Mixed encryption: Type=1 (E2E) for Proton users, Type=4 (ClearScheme) for external
 * - ICS attachment with METHOD:REQUEST or METHOD:CANCEL
 * - RFC 5545 compliant line folding and VTIMEZONE
 * - X-PM-SHARED-EVENT-ID and X-PM-SESSION-KEY for P2P calendar sharing
 */
class CalendarInviteSender(
    private val apiClient: ProtonApiClient,
    private val accountName: String,
    private val keyManager: KeyManager,
    /**
     * When true, the event title (SUMMARY) is prepended to invite/update/cancellation
     * email subjects so recipients can identify the event at a glance instead of seeing
     * only a timestamp. Off by default — opt-in because email subject lines aren't
     * encrypted in standard SMTP transport, so enabling it leaks the title to
     * mail-server-level metadata. Sourced per-account from
     * [me.proton.sync.SyncPreferences.inviteSubjectIncludeTitleKey].
     */
    private val includeTitleInSubject: Boolean = false
) {
    companion object {
        private const val TAG = "CalendarInvite"
        private const val CR = "\r\n"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        /**
         * How long to let a freshly stored draft settle before asking Proton to send it, and how often to ask
         * again when it answers that it cannot read it yet ([isDraftNotReadable]). Measured on hardware: without
         * the pause two thirds of the emails were refused; with it, none were, and a refusal that still happens
         * is accepted on the next attempt.
         */
        private const val SEND_SETTLE_MS = 1200L
        private const val SEND_ATTEMPTS = 3
        private const val SEND_RETRY_MS = 1500L
        private val TEXT_MEDIA = "text/plain".toMediaType()
        private val OCTET_MEDIA = "application/octet-stream".toMediaType()

        /**
         * The .ics attachment for [method] REQUEST or CANCEL.
         *
         * Every content line is folded at 75 octets, and every TEXT value is escaped (RFC 5545 3.1,
         * 3.3.11). DTSTAMP is the time of the call, so compare the result line by line, never whole.
         *
         * Each guest line states that guest's answer ([AttendeeInfo.partstat]), as Proton web does.
         * Receivers apply the organiser's PARTSTAT: Google Calendar, tested 2026-09-19, reset an
         * accepted guest to "needs action" for a change of room when the line said NEEDS-ACTION, and
         * equally when PARTSTAT was left out, with or without RSVP=TRUE, since a missing PARTSTAT MEANS
         * NEEDS-ACTION (RFC 5545 3.2.12). It kept the answer only when the line said ACCEPTED.
         */
        internal fun buildIcs(event: EventInfo, method: String): String {
            val sb = StringBuilder()
            sb.append("BEGIN:VCALENDAR${CR}")
            sb.append("PRODID:${ICalUtils.PRODID}${CR}")
            sb.append("VERSION:2.0${CR}")
            sb.append("METHOD:$method${CR}")
            sb.append("CALSCALE:GREGORIAN${CR}")

            // One definition per zone the lines below name (RFC 5545 3.2.19), built from the zone's
            // real rules (VTimezoneBuilder). A cancellation carries no EXDATE, so it needs none for them.
            val cancel = method == "CANCEL"
            for (tzid in VTimezoneBuilder.tzidsOf(event.timezone, event.allDay, if (cancel) null else event.exdateLines, event.recurrenceIdLine)) {
                val rules = if (tzid == event.timezone) event.zoneRules else null
                VTimezoneBuilder.build(tzid, event.dtStart, rules)?.let { sb.append(foldLines(it)) }
            }

            sb.append("BEGIN:VEVENT${CR}")

            val sdf = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("UTC")

            // Common: DTSTART/DTEND
            if (event.allDay) {
                sb.append("DTSTART;VALUE=DATE:${ICalUtils.formatDate(event.dtStart)}${CR}")
                sb.append("DTEND;VALUE=DATE:${ICalUtils.formatDate(event.dtEnd)}${CR}")
            } else if (event.timezone != "UTC") {
                // The very text Proton got when the upload says so (EventInfo.dtStartText), else from the instant.
                val start = event.dtStartText ?: UploadTimes.formatLocal(event.dtStart, event.timezone, event.zoneRules)
                val end = event.dtEndText ?: UploadTimes.formatLocal(event.dtEnd, event.timezone, event.zoneRules)
                sb.append("DTSTART;TZID=${event.timezone}:$start${CR}")
                sb.append("DTEND;TZID=${event.timezone}:$end${CR}")
            } else {
                sb.append("DTSTART:${ICalUtils.formatTimestamp(event.dtStart)}${CR}")
                sb.append("DTEND:${ICalUtils.formatTimestamp(event.dtEnd)}${CR}")
            }

            sb.append("DTSTAMP:${sdf.format(Date())}${CR}")

            // Recurrence properties — emit verbatim from the upload-path's pre-formatted
            // strings so external attendees see the same series the organizer scheduled.
            // Without these the .ics has only DTSTART/DTEND and the recipient (Gmail,
            // Outlook) sees a single non-recurring occurrence — even when Proton's web UI
            // correctly renders all 8 because the API received the RRULE separately.
            // They arrive as whole lines with their CRLF and are shared with the Proton upload, so
            // they are only folded here, one line at a time.
            // A cancellation names what it cancels and nothing else: no EXDATE, as Proton sends it.
            event.rruleLine?.takeIf { it.isNotEmpty() }?.let { sb.append(foldLines(it)) }
            if (!cancel) event.exdateLines?.takeIf { it.isNotEmpty() }?.let { sb.append(foldLines(it)) }
            event.recurrenceIdLine?.takeIf { it.isNotEmpty() }?.let { sb.append(foldLines(it)) }

            // ORGANIZER: prefer a real display name in CN if we have one (matches Proton
            // WebCalendar's `CN=J. Doe:mailto:…` style). Otherwise emit just `mailto:`
            // without a CN — duplicating the email as `CN=jdoe@example.com:mailto:jdoe@…`
            // looks like a parser bug to recipients.
            val organizerCn = event.organizerName?.takeIf { it.isNotEmpty() && !it.contains('@') }
            if (organizerCn != null) {
                sb.append(foldLine("ORGANIZER;CN=${icsParamValue(organizerCn)}:mailto:${event.organizer}"))
            } else {
                sb.append(foldLine("ORGANIZER:mailto:${event.organizer}"))
            }
            sb.append(CR)
            sb.append(foldLine("UID:${event.uid}")).append(CR)

            // Attendees — exclude organizer and all user addresses
            val excludeEmails = event.userAddresses.map { it.lowercase() }.toSet() +
                setOf(event.organizer.lowercase())
            for (att in event.attendees) {
                if (att.email.lowercase() in excludeEmails) continue
                // Drop the CN parameter when the stored attendee name is just the email
                // (sometimes mangled — Samsung Contacts replaces the period with a space,
                // producing `jdoe@example com` which RFC 5545 won't parse cleanly). Real
                // human names round-trip; email-shaped "names" are silently dropped.
                val attendeeCn = att.name?.takeIf { it.isNotEmpty() && !it.contains('@') }
                val attendeeCnPart = if (attendeeCn != null) ";CN=${icsParamValue(attendeeCn)}" else ""
                if (method == "CANCEL") {
                    // CANCEL: full details per RFC 5546 + Google style
                    val line = "ATTENDEE;CUTYPE=INDIVIDUAL;ROLE=REQ-PARTICIPANT;PARTSTAT=NEEDS-ACTION" +
                        "$attendeeCnPart;X-PM-TOKEN=${att.token}:mailto:${att.email}"
                    sb.append(foldLine(line))
                } else {
                    val line = "ATTENDEE${attendeeCnPart};ROLE=REQ-PARTICIPANT;RSVP=TRUE;PARTSTAT=${att.partstat}" +
                        ";X-PM-TOKEN=${att.token}:mailto:${att.email}"
                    sb.append(foldLine(line))
                }
                sb.append(CR)
            }

            // Optional: CREATED, LAST-MODIFIED
            if (event.created > 0) {
                sb.append("CREATED:${sdf.format(Date(event.created))}${CR}")
            }
            if (event.lastModified > 0) {
                sb.append("LAST-MODIFIED:${sdf.format(Date(event.lastModified))}${CR}")
            }

            if (!event.location.isNullOrEmpty()) {
                // Escape just like DESCRIPTION below — literal newlines and special chars in
                // multi-line addresses (street + zip on separate lines) break Proton's iCal
                // parser ("We could not update the event in your calendar") and Gmail's iTIP
                // processor; per RFC 5545 §3.3.11 text values must escape \n, \\, , and ;.
                sb.append(foldLine("LOCATION:${escapeIcsText(event.location)}")).append(CR)
            }

            sb.append("SEQUENCE:${event.sequence}${CR}")
            // STATUS:CANCELLED cancels the whole event for everybody (RFC 5546 3.2.5), so it is only
            // sent when the event is gone; a guest who was merely removed gets a CANCEL without it.
            when {
                !cancel -> sb.append("STATUS:CONFIRMED${CR}")
                event.cancelWholeEvent -> sb.append("STATUS:CANCELLED${CR}")
            }
            sb.append(foldLine("SUMMARY:${escapeIcsText(event.summary)}")).append(CR)
            sb.append("TRANSP:${if (event.availability == 1) "TRANSPARENT" else "OPAQUE"}${CR}")

            if (!cancel && !event.description.isNullOrEmpty()) {
                sb.append(foldLine("DESCRIPTION:${escapeIcsText(event.description)}")).append(CR)
            }

            if (event.sharedEventId != null) {
                sb.append(foldLine("X-PM-SHARED-EVENT-ID:${event.sharedEventId}"))
                sb.append(CR)
            }
            // The event's key never goes out with a cancellation, as Proton's own clients do it.
            if (!cancel && event.sessionKey != null) {
                sb.append(foldLine("X-PM-SESSION-KEY:${event.sessionKey}"))
                sb.append(CR)
            }

            sb.append("END:VEVENT${CR}")
            sb.append("END:VCALENDAR${CR}")
            return sb.toString()
        }

        /**
         * The plain-text email body. English, like the subject: the recipient's language is unknown.
         * An update says so ("has been updated"): an unchanged "You are invited to" on every change
         * read as a fresh invitation.
         */
        internal fun buildPlainTextBody(event: EventInfo, method: String, isUpdate: Boolean = false): String {
            val sb = StringBuilder()
            val title = event.summary.trim()
            val occurrence = !event.recurrenceIdLine.isNullOrEmpty()
            val name = when {
                occurrence && title.isEmpty() -> "One occurrence of an event"
                occurrence -> "One occurrence of $title"
                title.isEmpty() -> "The event"
                else -> title
            }
            when {
                method == "CANCEL" -> sb.appendLine("$name was canceled.")
                isUpdate -> sb.appendLine("$name has been updated.")
                else -> sb.appendLine(if (title.isEmpty()) "You are invited to an event." else "You are invited to $title.")
            }
            sb.appendLine()

            sb.appendLine("TIME:")
            if (event.allDay) {
                // All-day times are UTC midnights (CalendarContract); in any other zone the date shifts.
                sb.appendLine(
                    if (isSingleAllDay(event)) "${allDayDate(event.dtStart)} (all day)"
                    else "${allDayDate(event.dtStart)} - ${allDayDate(event.dtEnd - SyncConstants.ONE_DAY_MS)}"
                )
            } else {
                val sdf = SimpleDateFormat("EEEE d MMMM yyyy 'at' HH:mm", Locale.ENGLISH)
                sdf.timeZone = TimeZone.getTimeZone(event.timezone)
                sb.appendLine(
                    "${sdf.format(Date(event.dtStart))} (${getTimezoneAbbr(event.timezone, event.dtStart)}) - " +
                        "${sdf.format(Date(event.dtEnd))} (${getTimezoneAbbr(event.timezone, event.dtEnd)})"
                )
            }

            if (!event.location.isNullOrEmpty()) {
                sb.appendLine()
                sb.appendLine("LOCATION:")
                sb.appendLine(event.location)
            }

            if (method == "REQUEST" && !event.description.isNullOrEmpty()) {
                sb.appendLine()
                sb.appendLine("DESCRIPTION:")
                sb.appendLine(event.description)
            }

            return sb.toString()
        }

        internal fun buildSubject(event: EventInfo, method: String, isUpdate: Boolean, includeTitleInSubject: Boolean): String {
            // "starting on Thursday 24 September 2026 at 10:00 (GMT+2)", or for an all-day event, which
            // has no time and no zone, "on Thursday 24 September 2026" (several days: "starting on").
            val `when` = if (event.allDay) {
                (if (isSingleAllDay(event)) "on " else "starting on ") + allDayDate(event.dtStart)
            } else {
                val sdf = SimpleDateFormat("EEEE d MMMM yyyy 'at' HH:mm", Locale.ENGLISH)
                sdf.timeZone = TimeZone.getTimeZone(event.timezone)
                "starting on ${sdf.format(Date(event.dtStart))} (${getTimezoneAbbr(event.timezone, event.dtStart)})"
            }
            // METHOD:REQUEST covers both new invitations and updates per RFC 5546 — the
            // subject line is the only signal recipients see distinguishing them.
            val base = when {
                method == "CANCEL" -> "Cancellation of an event $`when`"
                isUpdate -> "Updated invitation for an event $`when`"
                else -> "Invitation for an event $`when`"
            }
            // Prepend the event title when the per-account toggle is on, separated by a
            // hyphen. Empty/blank summary falls back to the date-only subject (no double
            // separator).
            val title = event.summary.trim()
            return if (includeTitleInSubject && title.isNotEmpty()) {
                "$title - $base"
            } else {
                base
            }
        }

        /** "GMT+2", "GMT-4", "GMT+5:30": the zone's offset at that moment, minutes included when there are any. */
        internal fun getTimezoneAbbr(tzId: String, epochMillis: Long): String {
            val minutes = TimeZone.getTimeZone(tzId).getOffset(epochMillis) / 60_000
            val sign = if (minutes < 0) "-" else "+"
            val abs = Math.abs(minutes)
            return if (abs % 60 == 0) "GMT$sign${abs / 60}" else String.format(Locale.ROOT, "GMT%s%d:%02d", sign, abs / 60, abs % 60)
        }

        /** An all-day date. The times are UTC midnights, so it is read in UTC, never in the phone's zone. */
        private fun allDayDate(epochMillis: Long): String =
            SimpleDateFormat("EEEE d MMMM yyyy", Locale.ENGLISH).apply { timeZone = TimeZone.getTimeZone("UTC") }
                .format(Date(epochMillis))

        private fun isSingleAllDay(event: EventInfo): Boolean = event.dtEnd - event.dtStart <= SyncConstants.ONE_DAY_MS

        /**
         * What a cancellation sends to [recipients]: the event naming only them as guests (as Proton
         * does), without its description or EXDATEs, and cancelling it for everybody only when
         * [wholeEvent] (a guest who was merely removed gets no STATUS:CANCELLED).
         */
        internal fun cancellationOf(event: EventInfo, recipients: List<AttendeeInfo>, wholeEvent: Boolean): EventInfo =
            event.copy(attendees = recipients, description = null, exdateLines = null, cancelWholeEvent = wholeEvent)

        /** Fold long ICS lines per RFC 5545 section 3.1, by octets and between characters. */
        private fun foldLine(line: String): String = ICalUtils.foldLine(line)

        /** Fold every line of already formatted content lines, each ending in CRLF. */
        private fun foldLines(lines: String): String =
            lines.split(CR).filter { it.isNotEmpty() }.joinToString("") { foldLine(it) + CR }

        /** A TEXT value (RFC 5545 3.3.11): [ICalUtils.escapeText]. */
        internal fun escapeIcsText(text: String): String = ICalUtils.escapeText(text)

        /**
         * A parameter value such as CN (RFC 5545 3.2): quoted when it contains ',' ';' or ':', which
         * would otherwise end the value, the parameter or the whole parameter list. A DQUOTE cannot
         * appear even inside quotes, so it is dropped, as are control characters.
         */
        internal fun icsParamValue(value: String): String {
            val clean = value.filter { it != '"' && (it == '\t' || (it >= ' ' && it != '\u007F')) }
            return if (clean.any { it == ',' || it == ';' || it == ':' }) "\"$clean\"" else clean
        }

    }

    /**
     * One guest line of the invitation. [partstat] is written as this guest's PARTSTAT; the default is
     * what a fresh invitation says: nobody has answered yet.
     */
    data class AttendeeInfo(
        val email: String,
        val name: String?,
        val token: String,
        val partstat: String = "NEEDS-ACTION"
    )

    data class EventInfo(
        val uid: String,
        val summary: String,
        val description: String?,
        val location: String?,
        val dtStart: Long,
        val dtEnd: Long,
        val timezone: String,
        val allDay: Boolean,
        val organizer: String,
        val attendees: List<AttendeeInfo>,
        val sharedEventId: String?,
        val sessionKey: String?,
        val sequence: Int = 0,
        val userAddresses: Set<String> = emptySet(),
        val created: Long = 0,
        val lastModified: Long = 0,
        val availability: Int = 0,  // CalendarContract.Events.AVAILABILITY_BUSY
        // Recurrence properties carried verbatim into the .ics attachment so external
        // attendees (Gmail, Outlook) see all occurrences, not just DTSTART. Each is the
        // *fully formatted iCal line* including property name, params, and trailing
        // CRLF — or null/empty when not applicable. Examples:
        //   rruleLine        = "RRULE:FREQ=DAILY;COUNT=8;WKST=MO\r\n"
        //   exdateLines      = "EXDATE;TZID=Europe/Berlin:20260501T150000,20260502T150000\r\n"
        //   recurrenceIdLine = "RECURRENCE-ID;TZID=Europe/Berlin:20260502T150000\r\n"
        // Reusing the upload-path's already-formatted strings avoids duplicating the
        // all-day/UTC/TZID branching logic inside buildIcs().
        val rruleLine: String? = null,
        val exdateLines: String? = null,
        val recurrenceIdLine: String? = null,
        // Optional human display name for ORGANIZER's CN parameter — if absent or
        // looks like an email, the .ics omits CN entirely. Matches Proton WebCalendar's
        // `CN=J. Doe:mailto:jdoe@…` style. Pass the user's chosen alias here
        // (e.g. account.name) for a friendly subject in attendees' inbox.
        val organizerName: String? = null,
        /** For a CANCEL: the whole event is cancelled (true), or only its recipients were removed. */
        val cancelWholeEvent: Boolean = true,
        /**
         * The rules the invitation's VTIMEZONE for [timezone] describes when they are not the phone's own: the
         * online rules ("Update time zones online", UploadTimes.Plan.inviteRules), or null. A reader that applies
         * the definition (Outlook, Apple Calendar) then gets the same instants as one that only reads the TZID
         * name. Without [dtStartText] and [dtEndText], [dtStart] and [dtEnd] are also written with them.
         */
        val zoneRules: java.time.zone.ZoneRules? = null,
        /**
         * The local values ("yyyyMMdd'T'HHmmss") the upload sent Proton for a timed event in [timezone]
         * (UploadTimes.plan: Proton's own text for a time the user did not touch), so the invitation states
         * exactly the same; null to format [dtStart] and [dtEnd].
         */
        val dtStartText: String? = null,
        val dtEndText: String? = null
    )

    data class SendResult(val sent: Set<String>, val failed: Set<String>)

    /** Recipient info with resolved public key (null = external). */
    private data class ResolvedRecipient(
        val attendee: AttendeeInfo,
        val pubKeyArmored: String?
    )

    /**
     * Send METHOD:REQUEST invitation emails to specified attendees (excluding the organizer).
     * Sends ONE email to ALL recipients, matching WebCalendar behavior.
     * If [onlyTo] is provided, only those emails receive invitations.
     *
     * @param isUpdate true when this REQUEST is propagating an edit to existing
     *   attendees (subject becomes "Updated invitation for…"). false when this
     *   is the first REQUEST for a freshly-created event (subject "Invitation
     *   for…"). Both ship as iTIP METHOD:REQUEST per RFC 5546 — only the human-
     *   facing subject and body copy differ. When a single update mixes brand-
     *   new and existing recipients into one email, the caller picks `isUpdate`
     *   based on whether the *event* is new vs being edited; a freshly-added
     *   attendee on an existing event therefore gets "Updated invitation",
     *   which is slightly odd but better than existing recipients seeing
     *   "Invitation" repeatedly on every edit.
     */
    suspend fun sendInvitations(event: EventInfo, onlyTo: Set<String>? = null, isUpdate: Boolean = false): SendResult {
        val excludeEmails = event.userAddresses.map { it.lowercase() }.toSet() +
            setOf(event.organizer.lowercase())
        val recipients = event.attendees.filter {
            val email = it.email.lowercase()
            email !in excludeEmails && (onlyTo == null || email in onlyTo)
        }
        if (recipients.isEmpty()) {
            SyncLog.d(TAG, "No attendees to invite")
            return SendResult(emptySet(), emptySet())
        }

        return try {
            val ics = buildIcs(event, "REQUEST")
            val plainBody = buildPlainTextBody(event, "REQUEST", isUpdate)
            val subject = buildSubject(event, "REQUEST", isUpdate, includeTitleInSubject)
            sendToAllRecipients(event, recipients, subject, plainBody, ics, "REQUEST", if (isUpdate) "update" else "invitation")
        } catch (e: Exception) {
            SyncLog.w(TAG, "Failed to send invitations for event ${event.uid.take(12)}…: ${e.message}")
            SendResult(emptySet(), recipients.map { it.email.lowercase() }.toSet())
        }
    }

    /**
     * An edit's REQUESTs: [invitees] (guests who never received this event) get an invitation, and
     * [updatees] (guests who already have it) get an update. Two emails, because each group needs its
     * own wording: a newly added guest told "has been updated" has never seen the original, and an
     * existing one told "You are invited" reads it as a second, separate event. Both carry the same
     * event; what each attendee line says about answers is already in [EventInfo.attendees].
     */
    suspend fun sendInvitationsAndUpdates(event: EventInfo, invitees: Set<String>, updatees: Set<String>): SendResult {
        val sent = mutableSetOf<String>()
        val failed = mutableSetOf<String>()
        if (invitees.isNotEmpty()) {
            val r = sendInvitations(event, onlyTo = invitees, isUpdate = false)
            sent += r.sent; failed += r.failed
        }
        val updateOnly = updatees - invitees
        if (updateOnly.isNotEmpty()) {
            val r = sendInvitations(event, onlyTo = updateOnly, isUpdate = true)
            sent += r.sent; failed += r.failed
        }
        return SendResult(sent, failed)
    }

    /**
     * Send METHOD:CANCEL to [removedAttendees]: the event is gone ([wholeEvent]), or they were taken off
     * its guest list. Returns who was reached, so a failure can be retried (PendingCancellations).
     */
    suspend fun sendCancellations(event: EventInfo, removedAttendees: List<AttendeeInfo>, wholeEvent: Boolean = true): SendResult {
        if (removedAttendees.isEmpty()) return SendResult(emptySet(), emptySet())
        val cancelEvent = cancellationOf(event, removedAttendees, wholeEvent)
        return try {
            val ics = buildIcs(cancelEvent, "CANCEL")
            val plainBody = buildPlainTextBody(cancelEvent, "CANCEL")
            val subject = buildSubject(cancelEvent, "CANCEL", isUpdate = false, includeTitleInSubject = includeTitleInSubject)
            sendToAllRecipients(cancelEvent, removedAttendees, subject, plainBody, ics, "CANCEL", "cancellation")
        } catch (e: Exception) {
            SyncLog.w(TAG, "Failed to send cancellations for event ${event.uid.take(12)}…: ${e.message}")
            SendResult(emptySet(), removedAttendees.map { it.email.lowercase() }.toSet())
        }
    }

    /**
     * Send ONE email to ALL recipients with mixed encryption packages.
     * This matches the Proton WebCalendar behavior which sends a single email
     * to all attendees, enabling server-side calendar event auto-add.
     *
     * [kind] names the email in the log: "invitation", "update" or "cancellation".
     */
    @Suppress("UNCHECKED_CAST")
    private suspend fun sendToAllRecipients(
        event: EventInfo,
        recipients: List<AttendeeInfo>,
        subject: String,
        plainBody: String,
        ics: String,
        method: String,
        kind: String
    ): SendResult {
        // Resolve public keys for all recipients
        val resolved = recipients.map { att ->
            ResolvedRecipient(att, getRecipientPublicKey(att.email))
        }
        val internalRecipients = resolved.filter { it.pubKeyArmored != null }
        val externalRecipients = resolved.filter { it.pubKeyArmored == null }

        // Step 1: Create draft with ALL recipients in ToList
        val toList = resolved.map { mapOf(
            "Address" to it.attendee.email,
            "Name" to (it.attendee.name ?: it.attendee.email)
        ) }

        val draftBodyEncrypted = keyManager.encryptAndSignForAddress(accountName, event.organizer, plainBody)
        val draftPayload = mapOf(
            "Message" to mapOf(
                "ToList" to toList,
                "CCList" to emptyList<Any>(),
                "BCCList" to emptyList<Any>(),
                "Subject" to subject,
                "Sender" to mapOf(
                    "Address" to event.organizer,
                    "Name" to event.organizer
                ),
                "Body" to draftBodyEncrypted,
                "MIMEType" to "text/plain"
            )
        )
        val draftResponse = apiClient.createDraft(
            accountName, Gson().toJson(draftPayload).toRequestBody(JSON_MEDIA)
        )
        if (!draftResponse.isSuccessful) {
            val err = draftResponse.errorBody()?.string() ?: "no body"
            throw RuntimeException("Draft creation failed: HTTP ${draftResponse.code()}: $err")
        }
        val draftMessage = draftResponse.body()?.get("Message") as? Map<String, Any>
        val draftId = draftMessage?.get("ID") as? String
            ?: throw RuntimeException("Draft creation returned no ID")
        var sendPackages: List<Map<String, Any>> = emptyList()

        // Whatever fails while the email is being BUILT must not leave the draft behind in the sender's
        // mailbox: a retried send would otherwise leave one more each time. The send itself is deliberately
        // outside this: Proton can deliver the message and the answer still be lost, and deleting that id
        // would delete the sent mail from the user's mailbox. A draft left behind is the lesser harm.
        try {
            // Step 2: Upload ICS attachment (ONE upload for all recipients)
            var attachmentId: String? = null
            var recipientPkesks: Map<String, String>? = null  // email → base64 PKESK for internal
            var attachmentSessionKeyBytes: ByteArray? = null   // raw session key for external

            try {
                val icsBytes = ics.toByteArray(Charsets.UTF_8)
                val keyPackets: ByteArray
                val encryptedData: ByteArray

                if (internalRecipients.isNotEmpty()) {
                    // Encrypt to sender + ALL internal recipients → shared session key
                    val internalPubKeys = internalRecipients.map { it.pubKeyArmored!! }
                    val result = keyManager.encryptForAddressAndRecipients(
                        accountName, event.organizer, ics, internalPubKeys
                    )
                    keyPackets = result.first        // sender PKESK for upload
                    encryptedData = result.third     // SEIPD (shared)

                    // Map each recipient PKESK by email
                    val pkeskMap = mutableMapOf<String, String>()
                    for ((i, r) in internalRecipients.withIndex()) {
                        pkeskMap[r.attendee.email.lowercase()] =
                            android.util.Base64.encodeToString(result.second[i], android.util.Base64.NO_WRAP)
                    }
                    recipientPkesks = pkeskMap

                    // If there are also external recipients, recover the session key
                    if (externalRecipients.isNotEmpty()) {
                        attachmentSessionKeyBytes = keyManager.decryptSessionKeyWithAddressKey(
                            keyPackets, encryptedData, accountName, event.organizer
                        )?.second
                    }
                } else {
                    // All external: encrypt to sender only
                    val icsEncrypted = keyManager.encryptForAddress(accountName, event.organizer, ics)
                    val icsSplit = keyManager.splitPgpMessage(icsEncrypted)
                    keyPackets = icsSplit.first
                    encryptedData = icsSplit.second
                    attachmentSessionKeyBytes = keyManager.decryptSessionKeyWithAddressKey(
                        keyPackets, encryptedData, accountName, event.organizer
                    )?.second
                }

                val sigBytes = keyManager.createBinaryDetachedSignature(icsBytes, accountName, event.organizer)

                val attResponse = apiClient.uploadAttachment(
                    accountName,
                    draftId.toRequestBody(TEXT_MEDIA),
                    "invite.ics".toRequestBody(TEXT_MEDIA),
                    "text/calendar; method=${method.lowercase()}; name=invite.ics".toRequestBody(TEXT_MEDIA),
                    "".toRequestBody(TEXT_MEDIA),
                    okhttp3.MultipartBody.Part.createFormData("KeyPackets", "KeyPackets", keyPackets.toRequestBody(OCTET_MEDIA)),
                    okhttp3.MultipartBody.Part.createFormData("DataPacket", "DataPacket", encryptedData.toRequestBody(OCTET_MEDIA)),
                    okhttp3.MultipartBody.Part.createFormData("Signature", "Signature", sigBytes.toRequestBody(OCTET_MEDIA))
                )
                if (attResponse.isSuccessful) {
                    val attData = attResponse.body()?.get("Attachment") as? Map<String, Any>
                    attachmentId = attData?.get("ID") as? String
                    SyncLog.i(TAG, "Uploaded ICS attachment (id=$attachmentId)")
                } else {
                    val err = attResponse.errorBody()?.string() ?: "no body"
                    SyncLog.w(TAG, "ICS attachment upload failed: ${attResponse.code()} $err")
                    recipientPkesks = null
                    attachmentSessionKeyBytes = null
                }
            } catch (e: Exception) {
                SyncLog.w(TAG, "ICS attachment error: ${e.message}")
                recipientPkesks = null
                attachmentSessionKeyBytes = null
            }

            // The calendar file IS the message. Without it a cancellation cancels nothing in anyone's
            // calendar, an invitation puts no event in anyone's calendar and offers no way to answer,
            // and a reply tells the organiser nothing. Fail the send, so it goes again whole later
            // (PendingCancellations, or the invite retry strikes), rather than report a success that is
            // not: reporting success sent one text-only email and then never tried again, so the guest
            // was told about an event they could not add and the organiser believed they had been asked.
            // Past this point attachmentId is never null, and the `attachmentId != null` tests in the
            // packaging below are NOT dead: they are what lets Kotlin smart-cast it, so `mapOf(attachmentId
            // to ...)` is keyed by String rather than String?. Removing them would put a null key in the
            // AttachmentKeyPackets Proton is sent.
            if (attachmentId == null) {
                throw RuntimeException("the ${method.lowercase()}'s calendar file could not be attached")
            }

            // Step 3: Build send packages — mixed Type=1 (internal) and Type=4 (external)
            val packages = mutableListOf<Map<String, Any>>()

            // For external recipients: encrypt body with random session key
            val bodySessionKeyBytes = ByteArray(32)
            java.security.SecureRandom().nextBytes(bodySessionKeyBytes)
            val bodySessionKeyB64 = android.util.Base64.encodeToString(bodySessionKeyBytes, android.util.Base64.NO_WRAP)
            val encryptedBodyBytes = keyManager.encryptAndSignWithSessionKey(
                plainBody, 9, bodySessionKeyBytes, accountName, event.organizer
            )
            val encryptedBodyB64 = android.util.Base64.encodeToString(encryptedBodyBytes, android.util.Base64.NO_WRAP)

            // Internal package (Type=1): encrypt body to sender + all internal recipients
            // in a single PGP message so all PKESKs share the same session key.
            if (internalRecipients.isNotEmpty()) {
                try {
                    val internalPubKeys = internalRecipients.map { it.pubKeyArmored!! }
                    val bodyResult = keyManager.encryptForAddressAndRecipients(
                        accountName, event.organizer, plainBody, internalPubKeys
                    )
                    // bodyResult: (senderPKESK, [recipientPKESKs], SEIPD)
                    val internalBodyB64 = android.util.Base64.encodeToString(bodyResult.third, android.util.Base64.NO_WRAP)

                    val internalAddresses = mutableMapOf<String, Any>()
                    for ((i, r) in internalRecipients.withIndex()) {
                        val addr = mutableMapOf<String, Any>(
                            "Type" to 1,
                            "BodyKeyPacket" to android.util.Base64.encodeToString(bodyResult.second[i], android.util.Base64.NO_WRAP),
                            "Signature" to 1
                        )
                        val pkesk = recipientPkesks?.get(r.attendee.email.lowercase())
                        if (attachmentId != null && pkesk != null) {
                            addr["AttachmentKeyPackets"] = mapOf(attachmentId to pkesk)
                        }
                        internalAddresses[r.attendee.email] = addr
                    }
                    packages.add(mapOf(
                        "Addresses" to internalAddresses,
                        "Type" to 1,
                        "Body" to internalBodyB64,
                        "MIMEType" to "text/plain"
                    ))
                } catch (e: Exception) {
                    SyncLog.w(TAG, "Internal body encrypt failed, falling back to ClearScheme: ${e.message}")
                    // Fall back: add all internal as external
                    val fallbackAddresses = mutableMapOf<String, Any>()
                    for (r in internalRecipients) {
                        fallbackAddresses[r.attendee.email] = mapOf("Type" to 4, "Signature" to 0)
                    }
                    val pkg = mutableMapOf<String, Any>(
                        "Addresses" to fallbackAddresses,
                        "Type" to 4,
                        "Body" to encryptedBodyB64,
                        "MIMEType" to "text/plain",
                        "BodyKey" to mapOf("Key" to bodySessionKeyB64, "Algorithm" to "aes256")
                    )
                    if (attachmentId != null && attachmentSessionKeyBytes != null) {
                        val attKeyB64 = android.util.Base64.encodeToString(attachmentSessionKeyBytes, android.util.Base64.NO_WRAP)
                        pkg["AttachmentKeys"] = mapOf(attachmentId to mapOf("Key" to attKeyB64, "Algorithm" to "aes256"))
                    }
                    packages.add(pkg)
                }
            }

            // External package (Type=4) with all external recipients
            if (externalRecipients.isNotEmpty()) {
                val externalAddresses = mutableMapOf<String, Any>()
                for (r in externalRecipients) {
                    externalAddresses[r.attendee.email] = mapOf("Type" to 4, "Signature" to 0)
                }
                val pkg = mutableMapOf<String, Any>(
                    "Addresses" to externalAddresses,
                    "Type" to 4,
                    "Body" to encryptedBodyB64,
                    "MIMEType" to "text/plain",
                    "BodyKey" to mapOf("Key" to bodySessionKeyB64, "Algorithm" to "aes256")
                )
                if (attachmentId != null && attachmentSessionKeyBytes != null) {
                    val attKeyB64 = android.util.Base64.encodeToString(attachmentSessionKeyBytes, android.util.Base64.NO_WRAP)
                    pkg["AttachmentKeys"] = mapOf(attachmentId to mapOf("Key" to attKeyB64, "Algorithm" to "aes256"))
                }
                packages.add(pkg)
            }

            sendPackages = packages
        } catch (e: Exception) {
            deleteDraft(draftId)
            throw e
        }

        // Step 4: Send, once Proton has had a moment to store what we just wrote (see SEND_SETTLE_MS).
        kotlinx.coroutines.delay(SEND_SETTLE_MS)
        var sendResponse = apiClient.sendDraft(
            accountName, draftId, Gson().toJson(mapOf("AutoSaveContacts" to 0, "Packages" to sendPackages)).toRequestBody(JSON_MEDIA)
        )
        var sendError = if (sendResponse.isSuccessful) null else (sendResponse.errorBody()?.string() ?: "no body")
        var attempt = 1
        while (sendError != null && attempt < SEND_ATTEMPTS && isDraftNotReadable(sendResponse.code(), sendError)) {
            val wait = SEND_RETRY_MS * attempt
            SyncLog.w(TAG, "Proton could not read the draft of this $kind yet; asking again in ${wait}ms " +
                "(attempt ${attempt + 1}/$SEND_ATTEMPTS)")
            kotlinx.coroutines.delay(wait)
            sendResponse = apiClient.sendDraft(
                accountName, draftId, Gson().toJson(mapOf("AutoSaveContacts" to 0, "Packages" to sendPackages)).toRequestBody(JSON_MEDIA)
            )
            sendError = if (sendResponse.isSuccessful) null else (sendResponse.errorBody()?.string() ?: "no body")
            attempt++
        }
        if (sendError != null) {
            // A refused send delivered nothing, so the draft goes.
            deleteDraft(draftId)
            throw RuntimeException("Send failed: HTTP ${sendResponse.code()}: $sendError")
        }

        val allEmails = resolved.map { it.attendee.email.lowercase() }.toSet()
        val encLabel = buildString {
            if (internalRecipients.isNotEmpty()) append("${internalRecipients.size} E2E")
            if (externalRecipients.isNotEmpty()) {
                if (isNotEmpty()) append(", ")
                append("${externalRecipients.size} cleartext")
            }
        }
        SyncLog.i(TAG, "Sent $kind ($encLabel) to ${allEmails.joinToString(", ")} for event ${event.uid.take(12)}…")
        return SendResult(allEmails, emptySet())
    }

    /** Remove a draft that was never sent, so a retry does not leave one behind each time. */
    private suspend fun deleteDraft(draftId: String) {
        try {
            apiClient.deleteMessages(accountName,
                Gson().toJson(mapOf("IDs" to listOf(draftId))).toRequestBody(JSON_MEDIA))
            SyncLog.d(TAG, "Cleaned up failed draft $draftId")
        } catch (e: Exception) {
            SyncLog.d(TAG, "Could not clean up draft $draftId: ${e.message}")
        }
    }

    /**
     * Look up a recipient's public key. Returns the armored key if they are a Proton user, null for
     * anyone else. "Proton user" means RecipientType 1, see [protonRecipientKey].
     */
    private suspend fun getRecipientPublicKey(email: String): String? {
        return try {
            val response = apiClient.getPublicKeys(accountName, email)
            if (!response.isSuccessful) {
                val body = try { response.errorBody()?.string() } catch (_: Exception) { null }
                // An external address normally comes back as a 200 with RecipientType 2, and a
                // missing or non-Proton address can also fail with 33102 or 33103. Anything else is a
                // real failure: it silently downgrades a Proton recipient to external.
                unexpectedKeyLookupFailure(response.code(), body)?.let { reason ->
                    SyncLog.w(TAG, "Key lookup for $email failed ($reason); treating it as a non-Proton address, " +
                        "so this invitation is not end-to-end encrypted to it")
                }
                return null
            }
            protonRecipientKey(response.body())
        } catch (e: Exception) {
            SyncLog.w(TAG, "Key lookup for $email failed (${e.message}); treating it as a non-Proton address, " +
                "so this invitation is not end-to-end encrypted to it")
            null
        }
    }
}

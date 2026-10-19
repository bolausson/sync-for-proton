package me.proton.sync.calendar

/**
 * Reading Proton's verdicts on events/sync operations, and what the upload does with them. Pure.
 *
 * events/sync answers HTTP 200 for the request and gives each operation its own Code inside
 * `Responses[].Response` (1000 or 1001 success, 2501 not found, 2xxx refused), so a 200 alone says
 * nothing about whether the event was changed.
 */
internal data class SyncOpResult(val code: Int?, val error: String?)

/**
 * The verdict on the request's first (and, for a delete, only) operation: the entry with Index 0,
 * else the first one. Gson reads numbers as Doubles, hence the Number conversion.
 */
internal fun firstSyncOpResult(body: Map<*, *>?): SyncOpResult {
    val responses = body?.get("Responses") as? List<*> ?: return SyncOpResult(null, null)
    val entry = responses.firstOrNull { ((it as? Map<*, *>)?.get("Index") as? Number)?.toInt() == 0 }
        ?: responses.firstOrNull()
    val response = (entry as? Map<*, *>)?.get("Response") as? Map<*, *> ?: return SyncOpResult(null, null)
    return SyncOpResult((response["Code"] as? Number)?.toInt(), (response["Error"] as? String)?.takeIf { it.isNotBlank() })
}

internal enum class DeleteOpVerdict {
    /** Proton deleted it. */
    DELETED,
    /** It was already gone (2501): deleted elsewhere, whose client already told the guests. */
    ALREADY_GONE,
    /** Proton does not allow this account to delete it (2011, 2026); asking again changes nothing. */
    REFUSED,
    /** Any other error: may pass on a later attempt. */
    REJECTED
}

/**
 * A delete's verdict from its operation [code]; no code at all (no Responses) counts as done, as before,
 * unless the operation did carry an [error], which is a failure whatever the code says.
 */
internal fun deleteOpVerdict(code: Int?, error: String? = null): DeleteOpVerdict = when (code) {
    null -> if (error.isNullOrBlank()) DeleteOpVerdict.DELETED else DeleteOpVerdict.REJECTED
    1000, 1001 -> DeleteOpVerdict.DELETED
    2501 -> DeleteOpVerdict.ALREADY_GONE
    2011, 2026 -> DeleteOpVerdict.REFUSED
    else -> DeleteOpVerdict.REJECTED
}

/**
 * Proton refusing a send because it cannot read the draft yet: HTTP 400 with code 2001 and a complaint about
 * the message signature ("Invalid or missing message signature, please sign out and back in again").
 *
 * It is not about our signature. Measured on hardware (2026-09-20, 16 sends of the same email): sending straight
 * after the draft and its attachment were stored was refused 11 times, and the byte-identical payload was accepted
 * 1.5 s later in every single retry; a one second pause before the first attempt made 12 of 12 go through. So the
 * draft has not reached the node that verifies it, and the answer is to wait and ask again, not to rebuild
 * anything. Any other 2001 (genuinely invalid data) must NOT be retried, hence the text match.
 */
internal fun isDraftNotReadable(httpCode: Int, errorBody: String?): Boolean =
    httpCode == 400 && errorBody != null &&
        Regex("\"Code\"\\s*:\\s*2001(?![0-9])").containsMatchIn(errorBody) &&
        errorBody.contains("message signature", ignoreCase = true)

/**
 * How an upload attempt ended, for a caller whose own pending row depends on it.
 *
 * [DONE]: nothing is left to send. Proton accepted the event, or the attempt was given up for good on
 * an event Proton does not hold (a refused create, an event out of range), so the phone's version is
 * the only one. [RETRY]: the event stays pending for the next sync (an HTTP error, a rejection still
 * inside its three strikes); a thrown exception means the same. [GAVE_UP]: an UPDATE was given up for
 * good (refused, the third rejection, unrecoverable crypto) and Proton keeps its own version, so a
 * change that rode on it, such as a deleted occurrence, did not happen there.
 */
internal enum class UploadOutcome { DONE, RETRY, GAVE_UP }

internal enum class CancelledRowAction {
    /** The occurrence's deletion reached Proton, or is final anyway: clear the row's dirty flag. */
    CLEAR_DIRTY,
    /** Not settled yet: the row stays dirty for the next sync. */
    KEEP_PENDING,
    /** Proton refused it for good: remove the cancelled row, so the occurrence shows again as on Proton. */
    RESTORE
}

/**
 * What to do with a cancelled occurrence row after its parent's EXDATE upload ended with [outcome].
 *
 * On [UploadOutcome.GAVE_UP] the occurrence is shown again, unless the parent's own EXDATE column
 * ([parentExdateColumn]) already excludes it: then Proton excluded it too (the download writes that
 * column from Proton's EXDATEs), and restoring would bring back an occurrence Proton deleted. An
 * unreadable row ([rowExdate] null) is left as it was.
 */
internal fun cancelledRowAction(outcome: UploadOutcome?, rowExdate: String?, parentExdateColumn: String?): CancelledRowAction =
    when (outcome) {
        UploadOutcome.DONE -> CancelledRowAction.CLEAR_DIRTY
        UploadOutcome.RETRY, null -> CancelledRowAction.KEEP_PENDING
        UploadOutcome.GAVE_UP -> when {
            rowExdate == null -> CancelledRowAction.CLEAR_DIRTY
            parentExdateColumn.orEmpty().split(",").any { it.trim() == rowExdate } -> CancelledRowAction.CLEAR_DIRTY
            else -> CancelledRowAction.RESTORE
        }
    }

/**
 * The EXDATE value of an occurrence that started at [instanceTime]: a date for an all-day series, the
 * local time in the series' zone [parentTz], or UTC. The same form the download writes to the column.
 */
internal fun exdateValueFor(instanceTime: Long, originalAllDay: Boolean, parentTz: String): String = when {
    originalAllDay -> ICalUtils.formatDate(instanceTime)
    parentTz != "UTC" -> ICalUtils.formatLocalTimestamp(instanceTime, parentTz)
    else -> ICalUtils.formatTimestamp(instanceTime)
}

/**
 * Whether a Proton error body carries exactly this Code.
 *
 * A plain `contains("\"Code\":2026")` also matches Code 20261, and treating an unrelated
 * error as a permanent refusal would silently stop retrying an upload that deserved another
 * go. The delimiter is what makes the match exact: Proton's body is either
 * `{"Code":2026,"Error":...}` or `{"Code":2026}`.
 */
internal fun hasErrorCode(errorBody: String, code: Int): Boolean {
    val needle = "\"Code\":" + code
    return errorBody.contains(needle + ",") || errorBody.contains(needle + "}")
}

/**
 * What the app records, and later shows the user, when an upload came back non-2xx and nothing else took
 * ownership of the failure.
 *
 * [verb] is the path's own wording: "save an event to Proton" for a create, "update an event on Proton"
 * for an update. They must stay different, because the two paths fail for different reasons and the list
 * the user reads is the only place that distinguishes them. The body is cut to 160 characters, which is
 * about what a notification line can carry.
 */
internal fun uploadSwallowedFailure(verb: String, httpCode: Int, errorBody: String): String =
    "Could not $verb: HTTP $httpCode ${errorBody.take(160)}"

/**
 * The reason shown for an upload Proton has permanently refused (code 2026 or 2011).
 *
 * Proton's own message is preferred, because it is the only text that can say WHY, and the calendar's name
 * is appended when we know it: a user with several calendars cannot act on "insufficient permissions"
 * alone. A body we cannot parse falls back to a plain sentence rather than showing the user raw JSON.
 *
 * [attendeeRefusalText] is used only for the guest-list refusal, which Proton delivers as a 2011 with a
 * message about the attendee list and which needs its own explanation rather than the calendar wording.
 */
internal fun refusedUploadReason(errorBody: String, calendarName: String?, attendeeRefusalText: String): String {
    val serverMessage = errorBody.substringAfter("\"Error\":\"", "").substringBefore("\"")
        .takeIf { it.isNotBlank() }
    if (hasErrorCode(errorBody, 2011) && isAttendeeListRefusal(2011, serverMessage)) return attendeeRefusalText
    val message = serverMessage ?: "Proton does not allow writing to this calendar"
    return if (calendarName.isNullOrBlank()) message else "$message (calendar: $calendarName)"
}

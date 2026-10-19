package me.proton.sync.calendar

import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken

/**
 * Cancellation emails that could not be sent, kept so a later sync sends them.
 *
 * A cancellation is sent once, right after Proton accepted the delete or the guest removal, and by
 * then the event's own records are gone: the mapping and the tombstone after a delete, the removed
 * guest from the stored guest list after an update. A failed send used to be logged and forgotten,
 * so the guest kept the event in their calendar for good. Everything the email needs is stored here
 * instead, because nothing else is left to rebuild it from.
 *
 * Retries back off (15 min, doubling, at most 12 h) and stop after [MAX_ATTEMPTS] attempts or
 * [MAX_AGE_MS]; a guest who is invited again is taken off (a late CANCEL would remove the event
 * they were just invited back to). Pure, apart from [PendingCancellationStore].
 */
internal data class PendingCancellation(
    @SerializedName("uid") val uid: String,
    @SerializedName("recurrenceIdLine") val recurrenceIdLine: String?,
    @SerializedName("rruleLine") val rruleLine: String?,
    @SerializedName("summary") val summary: String,
    @SerializedName("location") val location: String?,
    @SerializedName("dtStart") val dtStart: Long,
    @SerializedName("dtEnd") val dtEnd: Long,
    @SerializedName("timezone") val timezone: String,
    @SerializedName("allDay") val allDay: Boolean,
    @SerializedName("sequence") val sequence: Int,
    /** The address the cancellation is sent from: the event's organiser. */
    @SerializedName("organizer") val organizer: String,
    @SerializedName("organizerName") val organizerName: String?,
    @SerializedName("recipients") val recipients: List<PendingRecipient>,
    /** The event is gone (STATUS:CANCELLED), or only these guests were taken off it. */
    @SerializedName("wholeEvent") val wholeEvent: Boolean,
    @SerializedName("createdAt") val createdAt: Long,
    @SerializedName("attempts") val attempts: Int,
    @SerializedName("nextAttemptAt") val nextAttemptAt: Long,
    @SerializedName("sharedEventId") val sharedEventId: String? = null
) {
    /** One cancellation per event or occurrence: the same key merges. */
    val key: String get() = PendingCancellations.keyOf(uid, recurrenceIdLine)
}

internal data class PendingRecipient(
    @SerializedName("email") val email: String,
    @SerializedName("name") val name: String?
)

internal object PendingCancellations {
    const val MAX_ATTEMPTS = 5
    const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
    const val MAX_ENTRIES = 100
    const val MAX_PER_RUN = 10
    private const val FIRST_BACKOFF_MS = 15L * 60 * 1000
    private const val MAX_BACKOFF_MS = 12L * 60 * 60 * 1000

    private val gson = Gson()
    private val listType = object : TypeToken<List<PendingCancellation>>() {}.type

    /** How long to wait after the [attempts]th failed attempt: 15 min, 30 min, 1 h ... at most 12 h. */
    fun backoffMillis(attempts: Int): Long {
        var ms = FIRST_BACKOFF_MS
        repeat((attempts - 1).coerceIn(0, 16)) { ms = (ms * 2).coerceAtMost(MAX_BACKOFF_MS) }
        return ms
    }

    /** A cancellation that just failed its first attempt, for [enqueue]. */
    fun firstFailure(entry: PendingCancellation, now: Long): PendingCancellation =
        entry.copy(createdAt = now, attempts = 1, nextAttemptAt = now + backoffMillis(1))

    /**
     * Add [entry] to [queue]. An entry for the same event or occurrence absorbs it: the guests of both,
     * the higher SEQUENCE, the newer event details, and a whole-event cancellation wins over a removal.
     * Newly added guests restart the attempt count. The oldest entries go beyond [MAX_ENTRIES].
     */
    fun enqueue(queue: List<PendingCancellation>, entry: PendingCancellation): List<PendingCancellation> {
        if (entry.recipients.isEmpty()) return queue
        val existing = queue.firstOrNull { it.key == entry.key }
        val merged = if (existing == null) entry else {
            val known = existing.recipients.map { it.email.lowercase() }.toSet()
            val added = entry.recipients.filter { it.email.lowercase() !in known }
            entry.copy(
                recipients = existing.recipients + added,
                sequence = maxOf(existing.sequence, entry.sequence),
                wholeEvent = existing.wholeEvent || entry.wholeEvent,
                createdAt = if (added.isEmpty()) existing.createdAt else entry.createdAt,
                attempts = if (added.isEmpty()) existing.attempts else entry.attempts,
                nextAttemptAt = minOf(existing.nextAttemptAt, entry.nextAttemptAt)
            )
        }
        val out = queue.filter { it.key != entry.key } + merged
        return if (out.size <= MAX_ENTRIES) out else out.sortedBy { it.createdAt }.takeLast(MAX_ENTRIES)
    }

    /** The entries whose turn has come, soonest first, at most [MAX_PER_RUN]. */
    fun due(queue: List<PendingCancellation>, now: Long): List<PendingCancellation> =
        queue.filter { it.nextAttemptAt <= now }.sortedBy { it.nextAttemptAt }.take(MAX_PER_RUN)

    /** True when [entry] has had its last chance. */
    fun isExhausted(entry: PendingCancellation, now: Long): Boolean =
        entry.attempts >= MAX_ATTEMPTS || now - entry.createdAt >= MAX_AGE_MS

    /**
     * [entry] after an attempt that left [failed] unreached: null when everybody was reached, else the
     * entry for those guests with one more attempt counted and the next one scheduled.
     */
    fun afterAttempt(entry: PendingCancellation, failed: Set<String>, now: Long): PendingCancellation? {
        val left = entry.recipients.filter { it.email.lowercase() in failed }
        if (left.isEmpty()) return null
        val attempts = entry.attempts + 1
        return entry.copy(recipients = left, attempts = attempts, nextAttemptAt = now + backoffMillis(attempts))
    }

    /**
     * The entry to keep after an attempt, against the queue as it stands NOW ([current]): an entry the queue
     * no longer has was purged (a guest invited again) and does not come back, a recipient purged meanwhile is
     * dropped, and a recipient another upload queued while this one was sending stays pending. [attempted] is
     * what was sent, [next] what [afterAttempt] made of the failures.
     */
    fun mergeAfterAttempt(
        current: PendingCancellation?,
        attempted: PendingCancellation,
        next: PendingCancellation?
    ): PendingCancellation? {
        if (current == null) return null
        val stillThere = current.recipients.map { normalizeGuestEmail(it.email) }.toSet()
        val sentTo = attempted.recipients.map { normalizeGuestEmail(it.email) }.toSet()
        val failed = (next?.recipients ?: emptyList()).filter { normalizeGuestEmail(it.email) in stillThere }
        val queuedMeanwhile = current.recipients.filter { normalizeGuestEmail(it.email) !in sentTo }
        val keep = failed + queuedMeanwhile.filter { q -> failed.none { normalizeGuestEmail(it.email) == normalizeGuestEmail(q.email) } }
        if (keep.isEmpty()) return null
        val base = next ?: current
        return base.copy(recipients = keep)
    }

    /**
     * [queue] without [emails] as recipients of the event or occurrence [uid] / [recurrenceIdLine]:
     * they are its guests again. Empty entries go. Other occurrences of the series keep theirs.
     */
    fun purgeReinvited(
        queue: List<PendingCancellation>,
        uid: String,
        recurrenceIdLine: String?,
        emails: Set<String>
    ): List<PendingCancellation> {
        if (emails.isEmpty()) return queue
        val key = keyOf(uid, recurrenceIdLine)
        val lower = emails.map { normalizeGuestEmail(it) }.toSet()
        return queue.mapNotNull { e ->
            if (e.key != key) e
            else e.copy(recipients = e.recipients.filter { normalizeGuestEmail(it.email) !in lower }).takeIf { it.recipients.isNotEmpty() }
        }
    }

    fun keyOf(uid: String, recurrenceIdLine: String?): String = uid + "|" + (recurrenceIdLine?.trim() ?: "")

    /** The queue entry for [event]'s cancellation to [recipients], before its first attempt is counted. */
    fun of(
        event: CalendarInviteSender.EventInfo,
        recipients: Collection<CalendarInviteSender.AttendeeInfo>,
        wholeEvent: Boolean
    ): PendingCancellation = PendingCancellation(
        uid = event.uid, recurrenceIdLine = event.recurrenceIdLine?.ifEmpty { null },
        rruleLine = event.rruleLine?.ifEmpty { null }, summary = event.summary, location = event.location,
        dtStart = event.dtStart, dtEnd = event.dtEnd, timezone = event.timezone, allDay = event.allDay,
        sequence = event.sequence, organizer = event.organizer, organizerName = event.organizerName,
        recipients = recipients.map { PendingRecipient(normalizeGuestEmail(it.email), it.name) },
        wholeEvent = wholeEvent, createdAt = 0, attempts = 0, nextAttemptAt = 0, sharedEventId = event.sharedEventId
    )

    /** The event a retry of [entry] cancels, addressed to its recipients. */
    fun eventOf(entry: PendingCancellation, userAddresses: Set<String>): CalendarInviteSender.EventInfo =
        CalendarInviteSender.EventInfo(
            uid = entry.uid, summary = entry.summary, description = null, location = entry.location,
            dtStart = entry.dtStart, dtEnd = entry.dtEnd, timezone = entry.timezone, allDay = entry.allDay,
            organizer = entry.organizer, attendees = attendeesOf(entry), sharedEventId = entry.sharedEventId, sessionKey = null,
            sequence = entry.sequence, userAddresses = userAddresses, rruleLine = entry.rruleLine,
            recurrenceIdLine = entry.recurrenceIdLine, organizerName = entry.organizerName,
            cancelWholeEvent = entry.wholeEvent
        )

    fun attendeesOf(entry: PendingCancellation): List<CalendarInviteSender.AttendeeInfo> =
        entry.recipients.map { CalendarInviteSender.AttendeeInfo(it.email, it.name, attendeeToken(entry.uid, it.email)) }

    fun encode(queue: List<PendingCancellation>): String = gson.toJson(queue, listType)

    /**
     * The stored queue; never throws. Gson fills a missing field with null even where Kotlin says it
     * cannot be, so each entry is checked and a damaged one dropped rather than crashing the sync.
     */
    @Suppress("SENSELESS_COMPARISON", "USELESS_ELVIS")
    fun decode(json: String?): List<PendingCancellation> {
        if (json.isNullOrBlank()) return emptyList()
        val raw: List<PendingCancellation?> = try {
            gson.fromJson<List<PendingCancellation?>>(json, listType) ?: emptyList()
        } catch (_: Exception) {
            return emptyList()
        }
        return raw.mapNotNull { e ->
            if (e == null || e.uid == null || e.summary == null || e.timezone == null || e.organizer == null ||
                e.recipients == null) return@mapNotNull null
            val recipients = e.recipients.filter { it != null && it.email != null && it.email.isNotBlank() }
            if (recipients.isEmpty()) null else e.copy(recipients = recipients)
        }
    }
}

/**
 * The cancellation of a deleted event or occurrence, as its guests received it, built from what the
 * phone kept of it (Android keeps a deleted row's columns until the sync adapter removes it).
 *
 * It used to name the Proton event ID when the row had no UID (so it cancelled nothing), ignore the
 * all-day flag, and take the end from the time of the last sync: a recurring row stores a DURATION and
 * no DTEND, so the cancellation of a series ended before it began. [effectiveDtEnd] is the upload's
 * DTSTART + DURATION; an end still before the start (neither column set) becomes the start, or the next
 * day for an all-day event. [recurrenceIdLine] makes a deleted edited occurrence cancel only itself:
 * without it, the email cancelled the whole series. SEQUENCE is one above the stored one (RFC 5546
 * 3.2.5, as Proton does for a delete).
 */
internal fun deletedEventCancellation(
    uid: String,
    summary: String,
    location: String?,
    dtStart: Long,
    effectiveDtEnd: Long,
    allDay: Boolean,
    timezone: String,
    rruleLine: String?,
    recurrenceIdLine: String?,
    organizer: String,
    organizerName: String?,
    storedSequence: Int,
    userAddresses: Set<String>,
    recipients: Collection<String>
): CalendarInviteSender.EventInfo = CalendarInviteSender.EventInfo(
    uid = uid, summary = summary, description = null, location = location?.ifEmpty { null },
    dtStart = dtStart,
    dtEnd = when {
        effectiveDtEnd > dtStart -> effectiveDtEnd
        allDay -> dtStart + me.proton.sync.SyncConstants.ONE_DAY_MS
        else -> dtStart
    },
    timezone = timezone, allDay = allDay, organizer = organizer,
    attendees = recipients.map { CalendarInviteSender.AttendeeInfo(normalizeGuestEmail(it), null, attendeeToken(uid, it)) },
    sharedEventId = null, sessionKey = null, sequence = storedSequence + 1, userAddresses = userAddresses,
    rruleLine = if (recurrenceIdLine.isNullOrEmpty()) rruleLine?.ifEmpty { null } else null,
    recurrenceIdLine = recurrenceIdLine?.ifEmpty { null }, organizerName = organizerName,
    cancelWholeEvent = true
)

/**
 * The per-account queue in the app's preferences. Upload workers run in parallel, so every change is a
 * read-modify-write under one lock, written with commit() so the next reader sees it.
 */
internal object PendingCancellationStore {
    private val lock = Any()

    fun key(accountName: String) = "calendar_pending_cancels_$accountName"

    fun read(prefs: SharedPreferences, accountName: String): List<PendingCancellation> = synchronized(lock) {
        PendingCancellations.decode(prefs.getString(key(accountName), null))
    }

    fun update(
        prefs: SharedPreferences,
        accountName: String,
        change: (List<PendingCancellation>) -> List<PendingCancellation>
    ): List<PendingCancellation> = synchronized(lock) {
        val before = PendingCancellations.decode(prefs.getString(key(accountName), null))
        val after = change(before)
        if (after != before) {
            val edit = prefs.edit()
            if (after.isEmpty()) edit.remove(key(accountName)) else edit.putString(key(accountName), PendingCancellations.encode(after))
            edit.commit()
        }
        after
    }
}

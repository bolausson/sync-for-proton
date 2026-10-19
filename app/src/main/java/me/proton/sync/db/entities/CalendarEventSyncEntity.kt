package me.proton.sync.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "calendar_event_sync",
    primaryKeys = ["account_name", "proton_event_id"],
    indices = [
        Index(value = ["account_name"]),
        Index(value = ["account_name", "proton_calendar_id"])
    ]
)
data class CalendarEventSyncEntity(
    @ColumnInfo(name = "account_name") val accountName: String,
    @ColumnInfo(name = "proton_calendar_id") val protonCalendarId: String,
    @ColumnInfo(name = "proton_event_id") val protonEventId: String,
    @ColumnInfo(name = "android_event_id") val androidEventId: Long? = null,
    @ColumnInfo(name = "last_modified") val lastModified: Long = 0,
    /**
     * "retime" (EventRetimePlanner.RETIME_TAG) while the stored times may have been computed with other zone
     * rules than the current ones: the next download re-applies this event from Proton although Proton did
     * not change it, once the local row is clean. Set only by the re-time pass, cleared by every download
     * write (a fresh entity); the upload paths copy() the mapping and keep it. Unused otherwise.
     */
    @ColumnInfo(name = "sync_tag") val syncTag: String? = null,
    @ColumnInfo(name = "sequence", defaultValue = "0") val sequence: Int = 0,
    // Cache of server fields needed by uploadModifiedEvent so we don't have to
    // re-fetch /calendar/events/{id} on every upload. Populated whenever we
    // pull or write the event; null means "unknown, do the GET to fill them".
    // When `cached_is_organizer = 1` and the other cached_* fields are present,
    // the upload path skips the GET entirely.
    @ColumnInfo(name = "cached_shared_key_packet") val cachedSharedKeyPacket: String? = null,
    @ColumnInfo(name = "cached_dtstart_line") val cachedDtStartLine: String? = null,
    @ColumnInfo(name = "cached_dtend_line") val cachedDtEndLine: String? = null,
    @ColumnInfo(name = "cached_rrule") val cachedRrule: String? = null,
    @ColumnInfo(name = "cached_uid") val cachedUid: String? = null,
    @ColumnInfo(name = "cached_is_organizer") val cachedIsOrganizer: Int? = null,
    // Override marker: the RECURRENCE-ID of a single-occurrence override; null for
    // recurring masters and standalone events. Lets the master-selection DB fallback in
    // syncCalendarEvents exclude a RANGE=THISANDFUTURE override (which carries BOTH an
    // RRULE and a RECURRENCE-ID) so it is never mistaken for the recurring master.
    @ColumnInfo(name = "cached_recurrence_id") val cachedRecurrenceId: String? = null,
    /**
     * Fingerprint of the event's SHARED fields (title, description, location, start, end, all-day,
     * recurrence) as they stood at the last successful sync, i.e. the last moment both sides
     * agreed. See `ConflictDetector`.
     *
     * Exists to answer one question the dirty flag cannot: when an event is locally modified AND
     * newer on the server, did the user change a SHARED field (a real collision, worth asking
     * about) or only their personal part such as a reminder or colour (no collision, both changes
     * can be kept)? Android marks the parent row dirty for both cases.
     *
     * Null means "no baseline recorded yet", which the conflict check treats as a possible
     * collision rather than assuming it is safe.
     */
    @ColumnInfo(name = "shared_fingerprint") val sharedFingerprint: String? = null,
    /**
     * The row's time columns (DTSTART, DTEND, DURATION, EVENT_TIMEZONE) as the provider held them right after
     * the last sync wrote or uploaded the event, taken in the same read as [sharedFingerprint]
     * (calendar.SyncedTimes). The cached DTSTART/DTEND lines are Proton's text for exactly these values, so
     * while the columns still equal this record the user has not touched the times, and the upload sends
     * Proton's own text back (calendar.UploadTimes) instead of reformatting the stored instants with rules that
     * may not be the ones they were computed with. Null on rows synced before it existed: the upload then falls
     * back to comparing the instants.
     */
    @ColumnInfo(name = "cached_times") val cachedTimes: String? = null
)

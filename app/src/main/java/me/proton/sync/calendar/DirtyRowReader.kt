package me.proton.sync.calendar

import android.database.Cursor
import android.provider.CalendarContract

/**
 * One row of the dirty or deleted set the upload walks (CalendarSyncAdapter.readDirtyRows).
 *
 * [mutators] is the provider's MUTATORS column, the app(s) that wrote the row since it was last marked clean (see
 * [LocalEventOrigin]): logged by the upload, and read by the hold of a new event written into a calendar someone else
 * shared ([NewEventHoldRules.verdict]). [mutatorsReadable] is false when the provider refused the column. [syncId],
 * [originalId], [originalSyncId] and [uid] are _SYNC_ID, ORIGINAL_ID, ORIGINAL_SYNC_ID and UID_2445, null when absent or
 * not of their type: they tell a new event from one of Proton's ([NewEventHoldRules.isCandidate]).
 */
internal data class DirtyRow(
    val androidEventId: Long,
    val androidCalId: Long,
    val deleted: Int,
    val mutators: String? = null,
    val mutatorsReadable: Boolean = true,
    val syncId: String? = null,
    val originalId: Long? = null,
    val originalSyncId: String? = null,
    val uid: String? = null,
)

/**
 * Reads a [DirtyRow] from the dirty-row query's cursor by column NAME, never by position, and every optional column by
 * its type, so a projection without MUTATORS (the retry when a provider refuses it) shifts nothing and no value is read
 * as another type. Pure: CalendarSyncAdapter passes the cursor's column names and its getType, getString and getLong;
 * tests pass arrays.
 */
internal object DirtyRowReader {

    /** Where each column is in the projection, -1 when it is not there. */
    class Columns(
        val id: Int,
        val calendarId: Int,
        val deleted: Int,
        val mutators: Int,
        val syncId: Int,
        val originalId: Int,
        val originalSyncId: Int,
        val uid: Int,
    )

    /** The columns of [names], a cursor's columnNames. _ID, CALENDAR_ID and DELETED are always selected. */
    fun columns(names: Array<String>): Columns {
        fun at(name: String) = names.indexOf(name)
        val c = Columns(
            id = at(CalendarContract.Events._ID),
            calendarId = at(CalendarContract.Events.CALENDAR_ID),
            deleted = at(CalendarContract.Events.DELETED),
            mutators = at(CalendarContract.Events.MUTATORS),
            syncId = at(CalendarContract.Events._SYNC_ID),
            originalId = at(CalendarContract.Events.ORIGINAL_ID),
            originalSyncId = at(CalendarContract.Events.ORIGINAL_SYNC_ID),
            uid = at(CalendarContract.Events.UID_2445),
        )
        require(c.id >= 0 && c.calendarId >= 0 && c.deleted >= 0) { "the dirty-row query lost a required column" }
        return c
    }

    /**
     * The row at the cursor's position. Text columns (MUTATORS, _SYNC_ID, ORIGINAL_SYNC_ID, UID_2445) are read only when
     * [type] says Cursor.FIELD_TYPE_STRING, as LocalEventOrigin.mutatorsText does: getString on a BLOB throws, and a
     * caller claiming to be a sync adapter may store one. ORIGINAL_ID only when it is FIELD_TYPE_INTEGER, so a NULL never
     * reads as 0. _ID, CALENDAR_ID and DELETED through [number].
     */
    fun read(c: Columns, type: (Int) -> Int, text: (Int) -> String?, number: (Int) -> Long): DirtyRow {
        fun textAt(i: Int): String? = if (i >= 0 && type(i) == Cursor.FIELD_TYPE_STRING) text(i) else null
        return DirtyRow(
            androidEventId = number(c.id),
            androidCalId = number(c.calendarId),
            deleted = number(c.deleted).toInt(),
            mutators = textAt(c.mutators),
            mutatorsReadable = c.mutators >= 0,
            syncId = textAt(c.syncId),
            originalId = if (c.originalId >= 0 && type(c.originalId) == Cursor.FIELD_TYPE_INTEGER) number(c.originalId) else null,
            originalSyncId = textAt(c.originalSyncId),
            uid = textAt(c.uid),
        )
    }
}

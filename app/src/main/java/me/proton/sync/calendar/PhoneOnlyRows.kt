package me.proton.sync.calendar

import android.provider.CalendarContract

/**
 * Which event rows came from Proton and which exist only on this phone. Pure, so the rule is tested without a phone.
 *
 * A row came from Proton when it has a mapping, a _SYNC_ID (the event's Proton id) or an ORIGINAL_SYNC_ID (an exception
 * of a Proton series): the forced Remote to Local wipe and "Remove synced data" spare everything else, because nothing
 * can bring it back. Everything else is the only copy: an event made on the phone that has not reached Proton yet, one
 * held for the user's choice in a calendar someone else shared, one kept on this phone for good. The passes that
 * delete a calendar's rows follow the same rule: the bulk-deletion restore deletes only rows from Proton, and a
 * calendar removed on Proton stays on the phone while it holds rows of its own ([keepRemovedCalendarRow]), and goes
 * once nothing in it is left to keep ([removeKeptCalendarRow]).
 *
 * A blank id counts as no id, in Kotlin and in the SQL alike.
 */
internal object PhoneOnlyRows {

    /** Whether row [id] came from Proton: mapped ([mappedIds]), or carrying a Proton id. */
    fun isFromProton(id: Long, syncId: String?, originalSyncId: String?, mappedIds: Set<Long>): Boolean =
        id in mappedIds || !syncId.isNullOrBlank() || !originalSyncId.isNullOrBlank()

    /** The rows that carry a Proton id, as an Events selection. Mapped rows without one are matched by id instead. */
    const val FROM_PROTON_SQL =
        "(TRIM(COALESCE(${CalendarContract.Events._SYNC_ID},'')) != '' OR " +
            "TRIM(COALESCE(${CalendarContract.Events.ORIGINAL_SYNC_ID},'')) != '')"

    /** The rows that carry no Proton id, as an Events selection: the complement of [FROM_PROTON_SQL]. */
    const val PHONE_ONLY_SQL =
        "TRIM(COALESCE(${CalendarContract.Events._SYNC_ID},'')) = '' AND " +
            "TRIM(COALESCE(${CalendarContract.Events.ORIGINAL_SYNC_ID},'')) = ''"

    /**
     * Whether a calendar removed on Proton stays on this phone: it still holds [phoneOnlyRows] live rows of its own, or
     * that count could not be read (null). Deleting the Android calendar deletes every row in it.
     */
    fun keepRemovedCalendarRow(phoneOnlyRows: Int?): Boolean = phoneOnlyRows == null || phoneOnlyRows > 0

    /**
     * Whether a calendar row kept on this phone for removed Proton calendar [protonId] (the kept-for marker in its
     * CAL_SYNC1) goes now, so it does not stay for good, empty and unmapped, and writable in every calendar app. Only
     * once nothing keeps it: Proton does not list that calendar again ([listedIds]; a re-share takes the row back), no
     * calendar mapping points at the row ([rowMapped]), no event kept there with "Keep on phone" still has its mapping
     * ([keptEventMappings]), and it holds nothing: [liveRows] (live rows but the download's cancelled-date placeholders)
     * and [phoneOnlyRows] (live rows that carry no Proton id) are both 0. A count that could not be read (null) keeps it.
     */
    fun removeKeptCalendarRow(
        protonId: String,
        listedIds: Set<String>,
        rowMapped: Boolean,
        keptEventMappings: Int,
        liveRows: Int?,
        phoneOnlyRows: Int?,
    ): Boolean = protonId !in listedIds && !rowMapped && keptEventMappings == 0 && liveRows == 0 && phoneOnlyRows == 0
}

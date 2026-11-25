package me.proton.sync.calendar

import me.proton.sync.db.entities.CalendarSyncEntity

/**
 * Which Android calendar row a Proton calendar may take, judged by this account's calendar mappings as read before
 * the calendar loop. Pure, so it is tested without a device.
 *
 * A calendar without a mapping looks for a row of the same display name to take over (prepareCalendarJob), so that a
 * reinstall, a resync that wiped the mappings, or a calendar deleted and re-created on Proton under the same name
 * keeps its row instead of getting a duplicate. That lookup used to take the first row of that name even when
 * another calendar Proton still lists mapped to it: a calendar shared with the user under the name of one of the
 * user's own calendars then took over the own calendar's row, both calendars downloaded into it, a new event in it
 * went up to whichever mapping the upload found first, and once the own calendar was the account's primary one,
 * apps that add events to the primary calendar wrote into that merged row.
 */
internal object CalendarRowClaims {

    /**
     * The row of [sameNameRows] (this account's rows with the calendar's display name, in provider order) that
     * calendar [protonCalendarId], which has no mapping, may take over: the first one that no OTHER calendar of
     * [liveProtonIds] (the calendars Proton listed this run) maps to. A row whose calendar is gone from Proton is
     * taken over as before, which is how a calendar deleted and re-created under the same name keeps its events; so is
     * an unmapped row. A row a listed calendar maps to is never taken, whether that calendar syncs this run or is
     * skipped (an external calendar while its switch is off): the caller creates a new row instead.
     *
     * A row kept on this phone for a calendar removed on Proton is never among [sameNameRows]: the account's rows are
     * listed under [listingKey], which is not its name, so only that calendar takes it back ([keptRowKey]). One kept by a
     * version up to 2.1.0 carries no marker; the sync marks it first ([unmarkedKeptRows]). One a listed calendar maps
     * stays under its name while no event mapping of the removed calendar is left ([listedAsKept]), and that mapping
     * holds it.
     */
    fun reusableRow(
        sameNameRows: List<Long>?,
        protonCalendarId: String,
        mappings: Collection<CalendarSyncEntity>,
        liveProtonIds: Set<String>,
    ): Long? {
        if (sameNameRows.isNullOrEmpty()) return null
        val held = mappings.mapNotNullTo(HashSet()) { mapping ->
            mapping.androidCalendarId?.takeIf {
                mapping.protonCalendarId != protonCalendarId && mapping.protonCalendarId in liveProtonIds
            }
        }
        return sameNameRows.firstOrNull { it !in held }
    }

    /**
     * The marker a calendar removed on Proton but kept on the phone carries in its row's CAL_SYNC1, a column the
     * account's sync adapter alone writes: which Proton calendar it was. A calendar stays on the phone, unmapped,
     * while it holds events that exist only there (or events the user kept), and only THAT calendar maps it again (a
     * re-share keeps the Proton id), whose sync then clears the marker. Another calendar of the same name, someone
     * else's above all, never does: the events in it, never sent anywhere, would go up there.
     */
    fun keptForMarker(protonCalendarId: String): String = KEPT_FOR + protonCalendarId

    /** The Proton calendar id in a CAL_SYNC1 value written by [keptForMarker], or null for any other value. */
    fun keptFor(calSync1: String?): String? =
        calSync1?.takeIf { it.startsWith(KEPT_FOR) }?.removePrefix(KEPT_FOR)?.takeIf { it.isNotBlank() }

    /**
     * The key an account's calendar row is listed under for the reuse by name (CalendarSyncAdapter's
     * readAndDedupeLocalCalendars): its [displayName], or, for a row kept for a removed calendar ([calSync1] holds
     * [keptForMarker]), [keptRowKey] of that calendar, which no display name can be, so the name never finds it.
     */
    fun listingKey(displayName: String, calSync1: String?): String = keptFor(calSync1)?.let { keptRowKey(it) } ?: displayName

    /** The key the row kept for removed Proton calendar [protonCalendarId] is listed under ([listingKey]). */
    fun keptRowKey(protonCalendarId: String): String = KEPT_KEY + protonCalendarId

    /**
     * Whether marked row [row] (its CAL_SYNC1 holds [marker], from [keptForMarker]) is listed under its calendar's own
     * key ([listingKey]): not while a calendar of [liveProtonIds] (the calendars Proton listed this run) maps it and no
     * event mapping of the marker's calendar is left ([eventCalendarIds]: the calendars this account's event mappings
     * with a local row name), which keeps it under its name. This version drops a kept row's mapping when it marks the
     * row, so only a version up to 2.1.0, installed over this one with the data kept, maps a marked row: it reused the
     * row by name and never read the marker. Listed under the marker's key as well, the row would go to the removed
     * calendar too once it is shared again, and two calendars would sync into one row. It is listed there all the same
     * while events kept for that calendar ("Keep on phone") have their mappings: they sit in this row, and with a row of
     * its own the calendar's sync would count them as deleted on the phone and delete them on Proton. A mapping of a
     * calendar Proton no longer lists (a removal cut short before the mapping went) changes nothing. False for a
     * [marker] that names no calendar.
     */
    fun listedAsKept(
        row: Long,
        marker: String,
        mappings: Collection<CalendarSyncEntity>,
        liveProtonIds: Set<String>,
        eventCalendarIds: Set<String>,
    ): Boolean {
        val removed = keptFor(marker) ?: return false
        return removed in eventCalendarIds || mappings.none { it.androidCalendarId == row && it.protonCalendarId in liveProtonIds }
    }

    /**
     * The Proton calendars of [eventCalendarIds] (the calendars this account's event mappings name) that have no
     * calendar mapping ([mappedCalendarIds]) and that Proton does not list ([listedIds]): removed on Proton while events
     * of theirs stayed on this phone, which is what "Keep on phone" leaves. Each once, in the order given.
     */
    fun removedCalendarsWithEvents(
        eventCalendarIds: Collection<String>,
        mappedCalendarIds: Set<String>,
        listedIds: Set<String>,
    ): Set<String> = eventCalendarIds.filterTo(LinkedHashSet()) { it !in mappedCalendarIds && it !in listedIds }

    /**
     * The calendars of [eventCalendarIds] (the calendars this account's event mappings name) that Proton lists
     * ([listedIds]) and that have no calendar mapping ([mappedCalendarIds]). A Local to Remote re-sync deletes every
     * calendar mapping of the account and keeps the events', and its run maps the calendars again by name only after
     * the marking ([unmarkedKeptRows]): until then the rows such a calendar uses are known only by where its mapped
     * events sit ([rowsInUse]). Each once, in the order given.
     */
    fun listedWithoutMapping(
        eventCalendarIds: Collection<String>,
        mappedCalendarIds: Set<String>,
        listedIds: Set<String>,
    ): Set<String> = eventCalendarIds.filterTo(LinkedHashSet()) { it !in mappedCalendarIds && it in listedIds }

    /**
     * The rows a calendar uses, which [unmarkedKeptRows] never marks: the rows this account's calendar [mappings] point
     * at, and [rowsOfListedEvents], the rows that hold a mapped event of a calendar [listedWithoutMapping] names. So a
     * row 2.1.0 handed to another calendar of its name is left alone also in the run of a Local to Remote re-sync.
     */
    fun rowsInUse(mappings: Collection<CalendarSyncEntity>, rowsOfListedEvents: Set<Long>): Set<Long> =
        mappings.mapNotNullTo(HashSet(rowsOfListedEvents)) { it.androidCalendarId }

    /**
     * Which calendar rows a version up to 2.1.0 kept for a calendar removed on Proton ("Keep on phone"), and so never
     * marked: row id to the calendar to mark it for ([keptForMarker]). [rowsOfRemoved] is, per removed calendar
     * ([removedCalendarsWithEvents]), the rows its mapped events sit in. A row a calendar uses ([mappedRows], from
     * [rowsInUse]: a calendar mapping points at it, so a calendar uses it or the removal pass settles it, or a mapped
     * event of a listed calendar without a mapping sits in it) or that carries a marker already ([markedRows]) is left
     * alone. A row that
     * holds the events of several removed calendars is marked for the first of them by id: only that one takes it back
     * on a re-share, and no other calendar of its name ever does.
     */
    fun unmarkedKeptRows(rowsOfRemoved: Map<String, Set<Long>>, mappedRows: Set<Long>, markedRows: Set<Long>): Map<Long, String> {
        val out = LinkedHashMap<Long, String>()
        for (protonId in rowsOfRemoved.keys.sorted()) {
            for (row in rowsOfRemoved.getValue(protonId).sorted()) {
                if (row !in mappedRows && row !in markedRows) out.putIfAbsent(row, protonId)
            }
        }
        return out
    }

    private const val KEPT_FOR = "kept-for:"

    /** Starts with a NUL character, which no calendar name has. */
    private val KEPT_KEY = Char(0) + KEPT_FOR

    /**
     * The rows that more than one calendar of [liveProtonIds] maps to: merged by the old reuse by name, which
     * [reusableRow] no longer does. Such a row is never marked primary (see PrimaryCalendarRun).
     */
    fun sharedRows(mappings: Collection<CalendarSyncEntity>, liveProtonIds: Set<String>): Set<Long> =
        mappings.asSequence()
            .filter { it.protonCalendarId in liveProtonIds }
            .mapNotNull { it.androidCalendarId }
            .groupingBy { it }
            .eachCount()
            .filterValues { it > 1 }
            .keys
}

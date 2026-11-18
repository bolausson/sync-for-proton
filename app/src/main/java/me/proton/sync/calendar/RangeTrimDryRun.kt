package me.proton.sync.calendar

import android.accounts.Account
import android.content.ContentProviderClient
import android.content.Context
import me.proton.sync.db.daos.CalendarEventSyncDao
import me.proton.sync.db.daos.CalendarSyncDao
import me.proton.sync.db.entities.CalendarEventSyncEntity
import java.time.ZoneId

/**
 * What "Remove older events from this phone" would remove from each of an account's calendars, from the phone alone:
 * the planner without the server checks (every mapped event counts as still on Proton, nothing as in Proton's
 * window), which can only keep more at the real removal. So the counts are upper bounds: the settings dialog says
 * "Up to". Also what `trim <account> check` logs. Reads only; writes nothing anywhere.
 */
internal object RangeTrimDryRun {

    class Estimate(
        val androidCalId: Long,
        val protonCalendarId: String,
        val displayName: String,
        /** The calendar's mapped events, and its event count on Proton at its last sync (-1 when unknown). */
        val mapped: Int,
        val serverTotal: Int,
        /** Above it the calendar takes the windowed fetch and can be trimmed. */
        val threshold: Int,
        /** Visible events (a series counts once) and rows the removal would take, at most. */
        val events: Int,
        val rows: Int,
        val plan: RangeTrimPlanner.Plan,
        /** Rows whose LAST_DATE the provider left empty, by kind (one-off, series, edit, cancelled). */
        val lastDateNull: Map<String, Int>,
        /** Series whose LAST_DATE and own end fall on different sides of the cutoff, or one of them missing. */
        val endDisagreements: Int,
        /** False while the sync skips the calendar ([RangeTrimRunner.skippedBySync]): nothing is removed from it then. */
        val synced: Boolean = true,
    ) {
        /** Too large to sync whole: by Proton's count, as the sync decides, or the phone's while that is not known yet. */
        val large: Boolean get() = (if (serverTotal >= 0) serverTotal else mapped) > threshold

        /** How the setting names this calendar. */
        val choice: RangeTrimRunner.Choice get() = RangeTrimRunner.Choice(androidCalId, protonCalendarId)
    }

    /** One estimate per synced calendar of [account], or null when the provider cannot be read. */
    suspend fun run(
        context: Context,
        provider: ContentProviderClient,
        account: Account,
        calendarDao: CalendarSyncDao,
        eventDao: CalendarEventSyncDao,
        pastDays: Long,
        futureDays: Long,
        nowMillis: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<Estimate>? {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
        val cutoff = RangeTrimPlanner.cutoffMillis(nowMillis, pastDays, zone)
        val threshold = RangeTrimPlanner.wholeSyncThreshold(pastDays, futureDays)
        val rowsSource = RangeTrimPass.ProviderRows(provider, account)
        val out = ArrayList<Estimate>()
        for (cal in calendarDao.getAllByAccount(account.name)) {
            val androidCalId = cal.androidCalendarId ?: continue
            val entities = eventDao.getAllByCalendar(account.name, cal.protonCalendarId)
            val rows = rowsSource.read(androidCalId) ?: return null
            val mappings = plannerMappings(entities)
            val ids = mappings.mapTo(HashSet()) { it.protonEventId }
            val guards = RangeTrimPass.guards(context, prefs, account.name, ids)
            val plan = RangeTrimPlanner.plan(
                RangeTrimPlanner.Input(
                    rows, mappings, cutoff, nowMillis, allTimeIds = ids, windowIds = emptySet(),
                    heldIds = guards.heldIds, cancellationUids = guards.cancellationUids, inviteWorkIds = guards.inviteWorkIds
                )
            )
            val byId = rows.associateBy { it.id }
            val events = plan.remove.count { g -> g.rowIds.any { id -> byId[id]?.cancelled == false } }
            out.add(
                Estimate(
                    androidCalId, cal.protonCalendarId, cal.displayName, entities.size,
                    prefs.getInt(me.proton.sync.SyncPreferences.serverTotalKey(account.name, cal.protonCalendarId), -1),
                    threshold, events, plan.rowCount, plan, lastDateNull(rows, mappings), endDisagreements(rows, cutoff),
                    synced = !RangeTrimRunner.skippedBySync(prefs, account.name, cal.protonCalendarId)
                )
            )
        }
        return out
    }

    /**
     * The calendars the dialog lists: the large ones, and any removal is on for, so it can always be switched off again;
     * never one the sync skips, which loses nothing (its choice waits, unlisted, until it syncs again).
     */
    fun listed(estimates: List<Estimate>, choices: Set<RangeTrimRunner.Choice>): List<Estimate> =
        estimates.filter { it.synced && (it.large || it.choice in choices) }

    /** Whether narrowing the range offers the removal: a listed large calendar would lose an event. */
    fun offerWanted(listed: List<Estimate>): Boolean = listed.any { it.large && it.events > 0 }

    /** Why calendars are not listed, for the dialog's note: small ones sync whole, and one the sync skips is not synced. */
    enum class LeftOut { SMALL, NOT_SYNCED }

    fun leftOut(estimates: List<Estimate>, choices: Set<RangeTrimRunner.Choice>): List<LeftOut> = listOfNotNull(
        LeftOut.SMALL.takeIf { estimates.any { it.synced && !it.large && it.choice !in choices } },
        LeftOut.NOT_SYNCED.takeIf { estimates.any { !it.synced && (it.large || it.choice in choices) } },
    )

    /** The planner's mappings: the entities that name a row. */
    fun plannerMappings(entities: Collection<CalendarEventSyncEntity>): List<RangeTrimPlanner.Mapping> = entities.mapNotNull { e ->
        val row = e.androidEventId ?: return@mapNotNull null
        RangeTrimPlanner.Mapping(e.protonEventId, row, e.lastModified, e.sharedFingerprint, e.cachedRecurrenceId)
    }

    internal fun lastDateNull(rows: Collection<RangeTrimPlanner.Row>, mappings: Collection<RangeTrimPlanner.Mapping>): Map<String, Int> {
        val edits = mappings.filter { it.cachedRecurrenceId != null }.mapTo(HashSet()) { it.androidEventId }
        return rows.filter { it.lastDate == null }.groupingBy { r ->
            when {
                r.cancelled -> "cancelled"
                !r.rrule.isNullOrBlank() || !r.rdate.isNullOrBlank() -> "series"
                r.originalSyncId != null || r.id in edits -> "edit"
                else -> "one-off"
            }
        }.eachCount()
    }

    internal fun endDisagreements(rows: Collection<RangeTrimPlanner.Row>, cutoff: Long): Int = rows.count { r ->
        if (r.rrule.isNullOrBlank() && r.rdate.isNullOrBlank()) return@count false
        val own = (RangeTrimPlanner.seriesEnd(r) as? RangeTrimPlanner.SeriesEnd.At)?.millis
        val last = r.lastDate
        when {
            own == null && last == null -> false
            own == null || last == null -> true
            else -> (own < cutoff) != (last < cutoff)
        }
    }
}

package me.proton.sync.calendar

import android.accounts.Account
import android.content.ContentProviderClient
import android.content.Context
import android.content.SharedPreferences
import me.proton.sync.SyncLog
import me.proton.sync.SyncPreferences
import me.proton.sync.db.daos.CalendarEventSyncDao
import java.time.Instant
import java.time.ZoneId

/**
 * What the calendar sync calls for "Remove older events from this phone" (on per calendar, a [Choice]): [recover] as a
 * sync's first step, a [Seed] recorded by every complete windowed run of a calendar it is on for, and [trim] for each
 * seed once every calendar has synced and the confirmed server deletions are applied, before reconcileDeletedEvents
 * and the upload. While an interrupted removal is unfinished, reconcileDeletedEvents sends nothing to Proton
 * ([removalUnfinished]). Kept out of CalendarSyncAdapter, whose suspend functions sit at the coroutine transform
 * ceiling. Never fails a sync: each step catches and logs its own failure.
 */
internal object RangeTrimRunner {
    private const val TAG = "CalendarSync"
    private const val DAY_MILLIS = 24L * 3600 * 1000

    /** One calendar's input, from a windowed run that fetched everything it asked for and Proton's all-time id list. */
    class Seed(
        val androidCalId: Long,
        val protonCalendarId: String,
        val displayName: String,
        val cutoffMillis: Long,
        val windowIds: Set<String>,
        val allTimeIds: Set<String>,
    )

    /**
     * One calendar removal is on for: the Android calendar it was chosen on and the Proton calendar that calendar showed.
     * The Android id alone is not enough: the calendar provider gives a deleted calendar's id to the next one created,
     * so a choice of a calendar that is gone could pass to one nobody chose. A choice goes when its calendar's mapping
     * goes ([forgetCalendar]), not before: a Proton calendar can come back under its id (a holiday calendar joined
     * again, a shared one shared again), and while its events are only held it is still this phone's calendar.
     */
    data class Choice(val androidCalId: Long, val protonCalendarId: String) {
        fun encode() = "$androidCalId|$protonCalendarId"

        companion object {
            fun decode(text: String): Choice? {
                val bar = text.indexOf('|')
                if (bar <= 0 || bar == text.length - 1) return null
                return Choice(text.substring(0, bar).toLongOrNull() ?: return null, text.substring(bar + 1))
            }
        }
    }

    /** The calendars of [accountName] that removal is on for. */
    fun choices(prefs: SharedPreferences, accountName: String): Set<Choice> =
        prefs.getStringSet(SyncPreferences.calendarTrimPastKey(accountName), null).orEmpty().mapNotNullTo(HashSet()) { Choice.decode(it) }

    fun setChoices(prefs: SharedPreferences, accountName: String, choices: Set<Choice>) {
        val key = SyncPreferences.calendarTrimPastKey(accountName)
        val e = prefs.edit()
        if (choices.isEmpty()) e.remove(key) else e.putStringSet(key, choices.mapTo(HashSet()) { it.encode() })
        e.apply()
    }

    /**
     * Forget the choices of a Proton calendar whose mapping this phone drops (gone from Proton, or its Android calendar
     * taken over by one re-created under the same name): a calendar this phone maps again later is a new one, and
     * never inherits removal.
     */
    fun forgetCalendar(prefs: SharedPreferences, accountName: String, protonCalendarId: String) {
        val all = choices(prefs, accountName)
        val left = all.filterTo(HashSet()) { it.protonCalendarId != protonCalendarId }
        if (left.size != all.size) setChoices(prefs, accountName, left)
    }

    /** Note whether a calendar of [accountName] is a holiday or subscribed one, for [skippedBySync]; written on a change only. */
    fun noteCalendarType(prefs: SharedPreferences, accountName: String, protonCalendarId: String, external: Boolean) {
        val key = SyncPreferences.calendarExternalKey(accountName, protonCalendarId)
        if (!prefs.contains(key) || prefs.getBoolean(key, false) != external) prefs.edit().putBoolean(key, external).apply()
    }

    /**
     * Whether the sync skips this calendar of [accountName] (a holiday or subscribed one while "Include external
     * calendars" is off), so that no removal of older events ever runs for it: the dialog does not list it, and a choice
     * of it waits until the calendar syncs again. A calendar not noted yet (no sync since 2.1.0) counts as synced.
     */
    fun skippedBySync(prefs: SharedPreferences, accountName: String, protonCalendarId: String): Boolean =
        !prefs.getBoolean(SyncPreferences.includeExternalCalendarsKey(accountName), false) &&
            prefs.getBoolean(SyncPreferences.calendarExternalKey(accountName, protonCalendarId), false)

    /**
     * Whether a removal of [accountName]'s older events was interrupted and is still to be finished: its journal holds
     * rows whose mappings may still name them although they are gone. reconcileDeletedEvents waits while it does,
     * because it would send each such mapping to Proton as a deletion. Read from the raw journal, so an entry that
     * cannot be decoded still holds it back.
     */
    fun removalUnfinished(context: Context, accountName: String): Boolean =
        !androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
            .getStringSet(SyncPreferences.calendarTrimJournalKey(accountName), null).isNullOrEmpty()

    /**
     * Whether this phone's clock can be trusted for a cutoff: Proton's clock ([serverDateMillis], the Date header of
     * this run's event count) is known and the phone is not more than a day ahead of it. A phone running days ahead
     * would put the cutoff after events still to come, and the window that should veto them moves with the clock.
     */
    fun clockTrusted(serverDateMillis: Long?, nowMillis: Long): Boolean =
        serverDateMillis != null && nowMillis - serverDateMillis <= DAY_MILLIS

    private fun pastDays(prefs: SharedPreferences, accountName: String): Long =
        prefs.getString(SyncPreferences.calendarPastDaysKey(accountName), "${SyncPreferences.DEFAULT_PAST_DAYS}")
            ?.toLongOrNull() ?: SyncPreferences.DEFAULT_PAST_DAYS

    /**
     * The seed of a calendar whose run was windowed and complete, or null when the trim must not run for it: off for
     * this calendar, no all-time list, or a clock that cannot be trusted. Its cutoff is that of [pastDays], the Past
     * events the run's window was computed from, not the setting as it is now: one shortened while the run fetched
     * must not reach past the window that run's ids veto.
     */
    fun seedOf(
        prefs: SharedPreferences,
        accountName: String,
        androidCalId: Long,
        protonCalendarId: String,
        displayName: String,
        windowIds: Set<String>?,
        allTimeIds: Set<String>?,
        serverDateMillis: Long?,
        pastDays: Long,
        nowMillis: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): Seed? {
        if (Choice(androidCalId, protonCalendarId) !in choices(prefs, accountName)) return null
        if (windowIds == null || allTimeIds == null) return null
        if (!clockTrusted(serverDateMillis, nowMillis)) {
            SyncLog.w(TAG, "Range trim '$displayName': skipped, this phone's clock is ahead of Proton's or Proton's is unknown")
            return null
        }
        val cutoff = RangeTrimPlanner.cutoffMillis(nowMillis, pastDays, zone)
        return Seed(androidCalId, protonCalendarId, displayName, cutoff, windowIds, allTimeIds)
    }

    /**
     * The cutoff [seed]'s removal may use now, or null when it must not run: the setting is read again, because it may
     * have changed while the other calendars synced (switched off for this calendar, the range left Custom). A Past
     * events made longer meanwhile moves the cutoff earlier; one made shorter does not move it, as the run fetched the
     * window of the seed's range ([seedOf]).
     */
    internal fun cutoffNow(prefs: SharedPreferences, accountName: String, seed: Seed, nowMillis: Long, zone: ZoneId): Long? {
        if (Choice(seed.androidCalId, seed.protonCalendarId) !in choices(prefs, accountName)) return null
        if (prefs.getString(SyncPreferences.calendarSyncModeKey(accountName), SyncPreferences.DEFAULT_SYNC_MODE) != SyncPreferences.SYNC_MODE_CUSTOM) return null
        return minOf(seed.cutoffMillis, RangeTrimPlanner.cutoffMillis(nowMillis, pastDays(prefs, accountName), zone))
    }

    /** Finish an interrupted removal of [account], whatever the setting is now ([RangeTrimPass.recover]). */
    suspend fun recover(context: Context, provider: ContentProviderClient, account: Account, eventDao: CalendarEventSyncDao) {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
        val journal = RangeTrimPass.PrefsJournal(prefs, SyncPreferences.calendarTrimJournalKey(account.name))
        if (!removalUnfinished(context, account.name)) return
        try {
            val r = RangeTrimPass.recover(RangeTrimPass.ProviderRows(provider, account), RoomMappings(eventDao, account.name), journal)
            if (r == null) {
                SyncLog.w(TAG, "Range trim: an interrupted removal for ${account.name} could not be finished yet; the next sync tries " +
                    "again, and until then no local deletion goes to Proton")
            } else {
                SyncLog.i(TAG, "Range trim: finished an interrupted removal for ${account.name}: ${r.deletedMappings} mapping(s) of " +
                    "removed rows deleted, ${r.keptRows} row(s) still there keep theirs")
            }
        } catch (e: Exception) {
            SyncLog.w(TAG, "Range trim: recovery failed for ${account.name}: ${e.message}")
        }
    }

    /** Remove from the phone what [seed]'s calendar holds that ended before its cutoff. Logs counts, never titles. */
    suspend fun trim(context: Context, provider: ContentProviderClient, account: Account, eventDao: CalendarEventSyncDao, seed: Seed) {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
        try {
            val now = System.currentTimeMillis()
            val zone = ZoneId.systemDefault()
            val cutoff = cutoffNow(prefs, account.name, seed, now, zone) ?: run {
                SyncLog.i(TAG, "Range trim '${seed.displayName}': skipped, switched off or the range is no longer Custom since this sync began")
                return
            }
            val cutoffDay = Instant.ofEpochMilli(cutoff).atZone(zone).toLocalDate()
            if (removalUnfinished(context, account.name)) {
                SyncLog.w(TAG, "Range trim '${seed.displayName}': skipped, an interrupted removal is still to be finished")
                return
            }
            val journal = RangeTrimPass.PrefsJournal(prefs, SyncPreferences.calendarTrimJournalKey(account.name))
            val rowsSource = RangeTrimPass.ProviderRows(provider, account)
            val rows = rowsSource.read(seed.androidCalId) ?: return
            val entities = eventDao.getAllByCalendar(account.name, seed.protonCalendarId)
            val mappings = RangeTrimDryRun.plannerMappings(entities)
            val guards = RangeTrimPass.guards(context, prefs, account.name, mappings.map { it.protonEventId })
            val plan = RangeTrimPlanner.plan(
                RangeTrimPlanner.Input(
                    rows, mappings, cutoff, now, seed.allTimeIds, seed.windowIds,
                    guards.heldIds, guards.cancellationUids, guards.inviteWorkIds
                )
            )
            if (plan.remove.isEmpty()) {
                SyncLog.i(TAG, "Range trim '${seed.displayName}': nothing ended before $cutoffDay to remove; ${plan.summary()}")
                return
            }
            val out = RangeTrimPass.remove(
                seed.protonCalendarId, seed.androidCalId, plan, entities, rowsSource, RoomMappings(eventDao, account.name), journal
            )
            SyncLog.i(
                TAG,
                "Range trim '${seed.displayName}': removed ${out.removedRows} row(s) in ${out.removedGroups} group(s) that ended " +
                    "before $cutoffDay (still on Proton); planned ${plan.summary()}" +
                    (if (out.refusedGroups > 0) "; ${out.refusedGroups} group(s) changed meanwhile and stay" else "") +
                    (if (out.failedGroups > 0) "; ${out.failedGroups} group(s) could not be removed this time" else "") +
                    (if (out.stopped) "; stopped, the next sync finishes it" else "")
            )
            for (note in out.notes) SyncLog.w(TAG, "Range trim '${seed.displayName}': $note")
        } catch (e: Exception) {
            SyncLog.w(TAG, "Range trim '${seed.displayName}' failed: ${e.message}")
        }
    }

    /** [RangeTrimPass.Mappings] over Room. Batches hold at most [RangeTrimPass.BATCH_ROWS] ids: one statement each. */
    class RoomMappings(private val dao: CalendarEventSyncDao, private val accountName: String) : RangeTrimPass.Mappings {
        override suspend fun delete(protonIds: List<String>) {
            for (chunk in protonIds.distinct().chunked(RangeTrimPass.BATCH_ROWS)) dao.deleteByProtonIds(accountName, chunk)
        }

        override suspend fun rowsOf(protonIds: List<String>): Map<String, Long> {
            val out = HashMap<String, Long>()
            for (chunk in protonIds.distinct().chunked(RangeTrimPass.BATCH_ROWS)) {
                for (m in dao.mappedRows(accountName, chunk)) out[m.protonEventId] = m.androidEventId
            }
            return out
        }
    }
}

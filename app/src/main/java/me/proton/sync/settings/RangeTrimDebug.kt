package me.proton.sync.settings

import android.accounts.Account
import android.content.Context
import android.provider.CalendarContract
import androidx.preference.PreferenceManager
import kotlinx.coroutines.runBlocking
import me.proton.sync.SyncLog
import me.proton.sync.SyncPreferences
import me.proton.sync.SyncUtils
import me.proton.sync.calendar.RangeTrimDryRun
import me.proton.sync.calendar.RangeTrimPass
import me.proton.sync.calendar.RangeTrimPlanner
import me.proton.sync.calendar.RangeTrimRunner
import me.proton.sync.db.SyncDatabase
import java.time.Instant
import java.time.ZoneId

/**
 * The debug verb `trim <account> check|on|off|interrupt [calendar name part|all]` (SyncSettingsActivity.handleDebugCommand):
 * check logs what "Remove older events from this phone" would take from each calendar, from the phone alone, with the
 * provider's LAST_DATE gaps and the series whose LAST_DATE and own end disagree; on and off set it for the large
 * calendars whose name contains the given part (or all of them), as the dialog's Save does, with its calendar sync
 * request for calendars it switched on ([afterSave] gets them); interrupt is a test fixture (see [interrupt]). Debug
 * builds only.
 */
internal object RangeTrimDebug {

    suspend fun run(
        context: Context,
        account: Account,
        database: SyncDatabase,
        mode: String?,
        namePart: String,
        tag: String,
        afterSave: (Set<RangeTrimRunner.Choice>) -> Unit,
    ) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val past = SyncPreferences.getStringOrDefault(prefs, SyncPreferences.calendarPastDaysKey(account.name), "${SyncPreferences.DEFAULT_PAST_DAYS}")
            .toLongOrNull() ?: SyncPreferences.DEFAULT_PAST_DAYS
        val future = SyncPreferences.getStringOrDefault(prefs, SyncPreferences.calendarFutureDaysKey(account.name), "${SyncPreferences.DEFAULT_FUTURE_DAYS}")
            .toLongOrNull() ?: SyncPreferences.DEFAULT_FUTURE_DAYS
        val estimates = context.contentResolver.acquireContentProviderClient(CalendarContract.AUTHORITY)?.use { provider ->
            RangeTrimDryRun.run(context, provider, account, database.calendarSyncDao(), database.calendarEventSyncDao(), past, future)
        }
        if (estimates == null) {
            SyncLog.w(tag, "trim ignored: the calendars of '${account.name}' could not be read")
            return
        }
        val enabled = RangeTrimRunner.choices(prefs, account.name)
        when (mode) {
            "check" -> {
                val mode0 = prefs.getString(SyncPreferences.calendarSyncModeKey(account.name), SyncPreferences.DEFAULT_SYNC_MODE)
                val cutoff = Instant.ofEpochMilli(RangeTrimPlanner.cutoffMillis(System.currentTimeMillis(), past, ZoneId.systemDefault()))
                    .atZone(ZoneId.systemDefault()).toLocalDate()
                val journal = RangeTrimPass.PrefsJournal(prefs, SyncPreferences.calendarTrimJournalKey(account.name)).read()
                SyncLog.i(
                    tag,
                    "trim check '${account.name}': range $mode0 $past/$future, cutoff $cutoff, whole-sync threshold " +
                        "${RangeTrimPlanner.wholeSyncThreshold(past, future)} events, journal ${journal.size} row(s)" +
                        if (mode0 != SyncPreferences.SYNC_MODE_CUSTOM) "; not Custom range, so nothing is removed" else ""
                )
                for (e in estimates) {
                    SyncLog.i(
                        tag,
                        "trim check '${e.displayName}': ${if (e.choice in enabled) "on" else "off"}, ${e.mapped} mapped, " +
                            "${e.serverTotal} on Proton at the last sync " +
                            "(" + (if (!e.synced) "skipped by the sync while external calendars are off" else if (e.large) "large" else "small, synced whole") +
                            (if (e.large && e.synced) "" else ", never trimmed") +
                            "); up to ${e.events} event(s), " +
                            "${e.plan.summary()}; LAST_DATE empty ${e.lastDateNull}; series end disagreements ${e.endDisagreements}"
                    )
                }
            }
            "on", "off" -> {
                // As the dialog lists them: the large calendars, and to switch off also any it is on for; never one the
                // sync skips.
                val targets = estimates.filter {
                    it.synced && (it.large || (mode == "off" && it.choice in enabled)) &&
                        (namePart == "all" || it.displayName.contains(namePart, ignoreCase = true))
                }
                if (targets.isEmpty()) {
                    val skipped = estimates.filter { !it.synced && (namePart == "all" || it.displayName.contains(namePart, ignoreCase = true)) }
                    SyncLog.w(
                        tag,
                        "trim ignored: no large calendar of '${account.name}' that syncs matches '$namePart'" +
                            if (skipped.isEmpty()) "" else "; skipped by the sync while external calendars are off: " +
                                skipped.joinToString { "'${it.displayName}'" }
                    )
                    return
                }
                val picked = targets.mapTo(HashSet()) { it.choice }
                val newlyOn = RangeTrimRunner.saveChoices(prefs, account.name, if (mode == "on") enabled + picked else enabled - picked)
                SyncLog.i(tag, "trim: removal of older events $mode for ${targets.joinToString { "'${it.displayName}'" }}")
                afterSave(newlyOn)
            }
            "interrupt" -> interrupt(context, account, database, estimates, enabled, namePart, tag)
            else -> SyncLog.w(tag, "trim ignored: give check, on, off or interrupt")
        }
    }

    /**
     * Test fixture `trim <account> interrupt <calendar part>`: one removal for a large calendar it is on for, planned
     * from the phone alone as the dialog counts it (every mapped event as on Proton, none in Proton's window), stopped
     * where a crash would stop it: the provider batch took the rows, and their mappings are still there. The journal is
     * left, and the next sync's first step finishes the removal, which is what this checks on a real provider. Custom
     * range only; holds the calendar sync lock, so it does not run while a calendar sync does.
     */
    private fun interrupt(
        context: Context,
        account: Account,
        database: SyncDatabase,
        estimates: List<RangeTrimDryRun.Estimate>,
        enabled: Set<RangeTrimRunner.Choice>,
        namePart: String,
        tag: String,
    ) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        if (prefs.getString(SyncPreferences.calendarSyncModeKey(account.name), SyncPreferences.DEFAULT_SYNC_MODE) != SyncPreferences.SYNC_MODE_CUSTOM) {
            SyncLog.w(tag, "trim interrupt ignored: '${account.name}' is not in Custom range")
            return
        }
        val target = estimates.firstOrNull { it.large && it.choice in enabled && it.displayName.contains(namePart, ignoreCase = true) }
        if (target == null || target.plan.remove.isEmpty()) {
            SyncLog.w(tag, "trim interrupt ignored: no large calendar of '${account.name}' that removal is on for, matching '$namePart', has anything to remove")
            return
        }
        if (RangeTrimRunner.removalUnfinished(context, account.name)) {
            SyncLog.w(tag, "trim interrupt ignored: an interrupted removal of '${account.name}' is already waiting for the next sync")
            return
        }
        var started = false
        SyncUtils.withSyncLock("calendar", account.name, tag) {
            started = true
            runBlocking {
                val provider = context.contentResolver.acquireContentProviderClient(CalendarContract.AUTHORITY)
                if (provider == null) {
                    SyncLog.w(tag, "trim interrupt: the calendar provider could not be reached")
                    return@runBlocking
                }
                provider.use {
                    val dao = database.calendarEventSyncDao()
                    val real = RangeTrimRunner.RoomMappings(dao, account.name)
                    val crashing = object : RangeTrimPass.Mappings {
                        override suspend fun delete(protonIds: List<String>) {
                            throw IllegalStateException("simulated crash after the rows went")
                        }

                        override suspend fun rowsOf(protonIds: List<String>) = real.rowsOf(protonIds)
                    }
                    val journal = RangeTrimPass.PrefsJournal(prefs, SyncPreferences.calendarTrimJournalKey(account.name))
                    val out = RangeTrimPass.remove(
                        target.protonCalendarId, target.androidCalId, target.plan,
                        dao.getAllByCalendar(account.name, target.protonCalendarId), RangeTrimPass.ProviderRows(it, account), crashing, journal
                    )
                    SyncLog.i(
                        tag,
                        "trim interrupt '${target.displayName}': ${out.notes.joinToString("; ")}; the journal holds " +
                            "${journal.read().size} entr(ies) and the mappings are still there; the next calendar sync's " +
                            "first step finishes the removal"
                    )
                }
            }
        }
        if (!started) SyncLog.w(tag, "trim interrupt ignored: a calendar sync of '${account.name}' is running; try again after it")
    }
}

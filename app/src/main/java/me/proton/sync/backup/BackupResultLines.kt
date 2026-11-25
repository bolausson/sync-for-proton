package me.proton.sync.backup

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.StringRes
import me.proton.sync.R
import me.proton.sync.SyncPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The lines under "Back up now" on the Backups screen (BackupSettingsActivity.refreshUi), which the backup-scheduled
 * debug verb logs too, and the only writer of the three preferences behind them.
 *
 * "Last backup: <date>, <result>" describes the last run that recorded a result: a backup made by the schedule or by
 * "Back up now", or a run that failed, found no folder or kept the last good backup ([recordRun], through
 * BackupDataExporter.record). A scheduled backup skipped because Android's automatic sync is off gets a second line,
 * "Scheduled backup skipped on <date>: Android's automatic sync is off" ([recordSkip], through
 * BackupDataExporter.recordSkippedAutoSyncOff), and leaves the first line as it was. Recorded in the first line, the
 * skip replaced the date of the last real backup: after weeks of skips nothing on the screen said how old that backup
 * was, and a skip minutes after a successful "Back up now" replaced its line (owner, 2026-11-22). The next run that
 * records a result removes the skip line, since that run is newer. The write order decides, not the clock, so a clock
 * set back changes nothing.
 *
 * The skip line says Android's automatic sync "is off", so it shows only while that is still why a scheduled backup is
 * skipped ([skipsNow]). With Android's switch turned back on, "Back up even when automatic sync is off" turned on or
 * scheduled backups turned off, the line goes at once; the skip stays recorded, and the line comes back if a scheduled
 * backup would be skipped again before the next run removes it.
 */
internal object BackupResultLines {

    /** What the stored keys say ([read]): [lastRunMs] null, no run recorded yet; [skippedMs] null, no skip since it. */
    data class Stored(val lastRunMs: Long?, val lastResult: String, val skippedMs: Long?)

    /** One line on the screen: the string [res] with its [args], read in the app's language by [text]. */
    data class Line(@StringRes val res: Int, val args: List<String>) {
        fun text(context: Context): String =
            if (args.isEmpty()) context.getString(res) else context.getString(res, *args.toTypedArray())
    }

    /** A run's [summary], recorded at [nowMs]: the first line, and the skip line goes. */
    fun recordRun(prefs: SharedPreferences, summary: String, nowMs: Long) {
        prefs.edit()
            .putLong(SyncPreferences.KEY_BACKUP_LAST_RUN_MS, nowMs)
            .putString(SyncPreferences.KEY_BACKUP_LAST_RESULT, summary)
            .remove(SyncPreferences.KEY_BACKUP_LAST_SKIP_MS)
            .apply()
    }

    /** A scheduled backup skipped at [nowMs] because Android's automatic sync is off: the second line only ([skipsNow]). */
    fun recordSkip(prefs: SharedPreferences, nowMs: Long) {
        prefs.edit().putLong(SyncPreferences.KEY_BACKUP_LAST_SKIP_MS, nowMs).apply()
    }

    fun read(prefs: SharedPreferences): Stored = Stored(
        lastRunMs = prefs.getLong(SyncPreferences.KEY_BACKUP_LAST_RUN_MS, 0L).takeIf { it > 0L },
        lastResult = prefs.getString(SyncPreferences.KEY_BACKUP_LAST_RESULT, null).orEmpty(),
        skippedMs = prefs.getLong(SyncPreferences.KEY_BACKUP_LAST_SKIP_MS, 0L).takeIf { it > 0L },
    )

    /**
     * Whether a scheduled backup would be skipped now because Android's automatic sync is off, by the worker's own rule
     * (BackupScheduleWorker.runScheduled): scheduled backups on, Android's switch off ([masterSyncOn] false) and "Back
     * up even when automatic sync is off" off. Only then is the skip line's sentence still true.
     */
    fun skipsNow(prefs: SharedPreferences, masterSyncOn: Boolean): Boolean =
        prefs.getBoolean(SyncPreferences.KEY_BACKUP_ENABLED, false) && !BackupScheduleWorker.runsScheduled(
            masterSyncOn,
            prefs.getBoolean(SyncPreferences.KEY_BACKUP_WHEN_AUTO_SYNC_OFF, SyncPreferences.DEFAULT_BACKUP_WHEN_AUTO_SYNC_OFF),
        )

    /**
     * The two lines for [stored]: "Last backup: <date>, <result>", or "No backup yet." before any run, and the skip
     * line, or null while no scheduled backup was skipped since that run, or while one would not be skipped now
     * ([skipping] false, from [skipsNow]). Both dates are written by [formatDate].
     */
    fun lines(stored: Stored, skipping: Boolean, formatDate: (Long) -> String = { date(it) }): Pair<Line, Line?> = Pair(
        stored.lastRunMs?.let { Line(R.string.backup_last_run, listOf(formatDate(it), stored.lastResult)) }
            ?: Line(R.string.backup_last_never, emptyList()),
        stored.skippedMs?.takeIf { skipping }
            ?.let { Line(R.string.backup_last_skipped_auto_sync_off, listOf(formatDate(it))) },
    )

    /** The lines the stored keys give now, with Android's automatic sync switch reading [masterSyncOn]. */
    fun of(prefs: SharedPreferences, masterSyncOn: Boolean): Pair<Line, Line?> =
        lines(read(prefs), skipsNow(prefs, masterSyncOn))

    /** A line's date, "2026-11-22 03:00", as "Last backup" has always written it. */
    fun date(ms: Long, locale: Locale = Locale.getDefault(), zone: TimeZone = TimeZone.getDefault()): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", locale).apply { timeZone = zone }.format(Date(ms))
}

package me.proton.sync.backup

import android.content.ContentResolver
import android.content.Context
import androidx.preference.PreferenceManager
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import me.proton.sync.R
import me.proton.sync.SyncLog
import me.proton.sync.SyncNotificationHelper
import me.proton.sync.SyncPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * WorkManager periodic worker that runs an automatic data backup every N days.
 *
 * Mirrors the scheduling shape of [me.proton.sync.SyncWorker]. Requires network
 * (the backup pulls from Proton) but is deliberately NOT gated on the subscription:
 * a user's data backup is never withheld. It follows Android's own automatic sync switch, as every background
 * sync does (the owner's rule since 2.2.0): while that is off, a scheduled run is skipped and says so on the backup
 * screen, unless the user turned on "Back up even when automatic sync is off" ([runsScheduled]). Manual "Back up now"
 * does NOT go through this worker; the settings screen calls [BackupDataExporter] directly so it can show
 * progress and run even when scheduled backups are off, and whatever Android's switch says.
 */
class BackupScheduleWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    // Every outcome but a timeout or a network failure finishes as a success, a skipped run too: WorkManager then
    // keeps the schedule and tries nothing again before the next period.
    override fun doWork(): Result = if (runScheduled(applicationContext) == Run.RETRY) Result.retry() else Result.success()

    companion object {
        private const val TAG = "BackupWorker"
        const val WORK_NAME = "proton_data_backup"
        private const val WORKER_TIMEOUT_MS = 9L * 60L * 1000L // under WorkManager's ~10-min limit

        /** What one scheduled run did ([runScheduled]); [RETRY]: WorkManager tries again on its backoff. */
        internal enum class Run { OFF, SKIPPED_AUTO_SYNC_OFF, NO_FOLDER, DONE, RETRY }

        /**
         * Whether a scheduled backup runs: while Android's automatic sync is on ([masterSyncOn]) always; while it is off
         * only when the user turned on "Back up even when automatic sync is off" ([whenAutoSyncOff], off by default).
         * A backup downloads every calendar and contact from Proton, so it is a background sync like any other.
         */
        internal fun runsScheduled(masterSyncOn: Boolean, whenAutoSyncOff: Boolean): Boolean = masterSyncOn || whenAutoSyncOff

        /**
         * One scheduled run, as doWork runs it and the backup-scheduled debug verb does (SyncSettingsActivity): nothing
         * while automatic backups are off; skipped while Android's automatic sync is off ([runsScheduled]), which
         * records only the skip line the screen shows under the last backup's, leaving that one as it was (no
         * notification, no folder check, nothing sent to Proton); then the folder check and the backup itself. Blocking;
         * never on the main thread.
         */
        internal fun runScheduled(ctx: Context): Run {
            val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
            if (!prefs.getBoolean(SyncPreferences.KEY_BACKUP_ENABLED, false)) {
                SyncLog.i(TAG, "Auto-backup disabled; nothing to do")
                return Run.OFF
            }
            val whenAutoSyncOff = prefs.getBoolean(
                SyncPreferences.KEY_BACKUP_WHEN_AUTO_SYNC_OFF, SyncPreferences.DEFAULT_BACKUP_WHEN_AUTO_SYNC_OFF
            )
            if (!runsScheduled(ContentResolver.getMasterSyncAutomatically(), whenAutoSyncOff)) {
                SyncLog.i(TAG, "Scheduled backup skipped: Android's automatic sync is off, and \"Back up even when automatic sync is off\" is off")
                BackupDataExporter.recordSkippedAutoSyncOff(ctx)
                return Run.SKIPPED_AUTO_SYNC_OFF
            }
            if (!SafBackupStorage.hasValidPermission(ctx)) {
                SyncLog.w(TAG, "Backup folder permission missing/revoked - skipping run")
                BackupDataExporter.record(ctx, ctx.getString(R.string.backup_result_folder_lost))
                SyncNotificationHelper.showBackupResult(
                    ctx,
                    ctx.getString(R.string.backup_notif_folder_lost_title),
                    ctx.getString(R.string.backup_notif_folder_lost_body),
                    isAlert = true
                )
                return Run.NO_FOLDER
            }

            val label = SimpleDateFormat("yyyy-MM-dd'T'HHmmss", Locale.US).format(Date())
            return try {
                // Cap below WorkManager's ~10-min execution limit so a hung API call can't get the
                // worker killed mid-write; on timeout let WorkManager retry on its backoff.
                val result = runBlocking {
                    withTimeoutOrNull(WORKER_TIMEOUT_MS) { BackupDataExporter.from(ctx).runBackup(label) }
                }
                if (result == null) {
                    SyncLog.w(TAG, "Backup timed out after ${WORKER_TIMEOUT_MS}ms - will retry")
                    BackupDataExporter.record(ctx, ctx.getString(R.string.backup_result_failed, "timeout"))
                    return Run.RETRY
                }
                handleResult(ctx, result)
                Run.DONE
            } catch (e: Throwable) {
                // Throwable, not Exception: the encrypted path holds several full copies of the archive
                // at once, so a large account on a small phone can OutOfMemoryError. Caught nowhere,
                // that recorded nothing at all and left the screen showing a stale result forever.
                SyncLog.e(TAG, "Backup run failed", e)
                BackupDataExporter.record(ctx, ctx.getString(R.string.backup_result_failed, BackupDataExporter.describe(e)))
                // friendlyMessage turns known network failures into readable sentences, but EVERY arm
                // of it returns a canned string, so anything it does not recognise was replaced by
                // "something went wrong" and the real message was lost on this surface too. When it
                // falls through, say what actually happened instead. The texts that say it tries again stay: the backup
                // follows no account's interval, WorkManager retries a network failure (RETRY below) and the schedule
                // runs the next backup, and "Tap Sync" would point at the wrong button here.
                val generic = ctx.getString(R.string.error_unexpected)
                val friendly = SyncNotificationHelper.friendlyMessage(ctx, e.message ?: "", retries = true)
                SyncNotificationHelper.showBackupResult(
                    ctx,
                    ctx.getString(R.string.backup_notif_failed_title),
                    if (friendly == generic) BackupDataExporter.describe(e) else friendly,
                    isAlert = true
                )
                // Retry transient network errors on WorkManager's backoff; don't loop on terminal ones.
                if (e is java.io.IOException) Run.RETRY else Run.DONE
            }
        }

        private fun handleResult(ctx: Context, result: BackupDataExporter.Result) {
            // The recorded line is the shared one, so the manual button and this worker can never
            // describe the same outcome differently. Only the notifications differ, because a
            // notification has a title and can afford a fuller explanation than a one-line label.
            BackupDataExporter.record(ctx, BackupDataExporter.summaryFor(ctx, result))
            when (result) {
                is BackupDataExporter.Result.Success -> {
                    val summary = BackupDataExporter.summaryFor(ctx, result)
                    if (result.incomplete) {
                        SyncNotificationHelper.showBackupResult(
                            ctx, ctx.getString(R.string.backup_notif_incomplete_title), summary, isAlert = true
                        )
                    } else {
                        SyncNotificationHelper.showBackupResult(
                            ctx, ctx.getString(R.string.backup_notif_success_title), summary
                        )
                    }
                }
                BackupDataExporter.Result.SkippedEmptyGuard ->
                    SyncNotificationHelper.showBackupResult(
                        ctx,
                        ctx.getString(R.string.backup_notif_skipped_title),
                        ctx.getString(R.string.backup_notif_skipped_body),
                        isAlert = true
                    )
                // No notification: a lost folder is not something a background alert can fix, and the
                // screen says so the next time it is opened.
                BackupDataExporter.Result.NoFolder -> Unit
                is BackupDataExporter.Result.Failure ->
                    SyncNotificationHelper.showBackupResult(
                        ctx, ctx.getString(R.string.backup_notif_failed_title), result.reason, isAlert = true
                    )
            }
        }

        /** Read prefs and (re)schedule or cancel accordingly. Call on settings change + app start. */
        fun reschedule(context: Context) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            if (!prefs.getBoolean(SyncPreferences.KEY_BACKUP_ENABLED, false)) {
                cancel(context); return
            }
            val days = (prefs.getString(
                SyncPreferences.KEY_BACKUP_INTERVAL_DAYS, SyncPreferences.DEFAULT_BACKUP_INTERVAL_DAYS
            )?.toLongOrNull() ?: 7L).coerceIn(1L, 365L)
            schedule(context, days)
        }

        private fun schedule(context: Context, intervalDays: Long) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<BackupScheduleWorker>(intervalDays, TimeUnit.DAYS)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request
            )
            SyncLog.i(TAG, "Scheduled data backup every ${intervalDays}d")
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            SyncLog.i(TAG, "Cancelled scheduled data backup")
        }
    }
}

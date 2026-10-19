package me.proton.sync.backup

import android.content.Context
import android.content.SharedPreferences
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
 * (the backup pulls from Proton) but is deliberately NOT gated on the subscription —
 * a user's data backup is never withheld. Manual "Back up now" does NOT go through
 * this worker; the settings screen calls [BackupDataExporter] directly so it can show
 * progress and run even when scheduled backups are off.
 */
class BackupScheduleWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val ctx = applicationContext
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        if (!prefs.getBoolean(SyncPreferences.KEY_BACKUP_ENABLED, false)) {
            SyncLog.i(TAG, "Auto-backup disabled; nothing to do")
            return Result.success()
        }
        if (!SafBackupStorage.hasValidPermission(ctx)) {
            SyncLog.w(TAG, "Backup folder permission missing/revoked - skipping run")
            recordResult(prefs, ctx.getString(R.string.backup_result_folder_lost))
            SyncNotificationHelper.showBackupResult(
                ctx,
                ctx.getString(R.string.backup_notif_folder_lost_title),
                ctx.getString(R.string.backup_notif_folder_lost_body),
                isAlert = true
            )
            return Result.success()
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
                recordResult(prefs, ctx.getString(R.string.backup_result_failed, "timeout"))
                return Result.retry()
            }
            handleResult(ctx, prefs, result)
            Result.success()
        } catch (e: Throwable) {
            // Throwable, not Exception: the encrypted path holds several full copies of the archive
            // at once, so a large account on a small phone can OutOfMemoryError. Caught nowhere,
            // that recorded nothing at all and left the screen showing a stale result forever.
            SyncLog.e(TAG, "Backup run failed", e)
            recordResult(prefs, ctx.getString(R.string.backup_result_failed, BackupDataExporter.describe(e)))
            // friendlyMessage turns known network failures into readable sentences, but EVERY arm
            // of it returns a canned string, so anything it does not recognise was replaced by
            // "something went wrong" and the real message was lost on this surface too. When it
            // falls through, say what actually happened instead.
            val generic = ctx.getString(R.string.error_unexpected)
            val friendly = SyncNotificationHelper.friendlyMessage(ctx, e.message ?: "")
            SyncNotificationHelper.showBackupResult(
                ctx,
                ctx.getString(R.string.backup_notif_failed_title),
                if (friendly == generic) BackupDataExporter.describe(e) else friendly,
                isAlert = true
            )
            // Retry transient network errors on WorkManager's backoff; don't loop on terminal ones.
            if (e is java.io.IOException) Result.retry() else Result.success()
        }
    }

    private fun handleResult(ctx: Context, prefs: SharedPreferences, result: BackupDataExporter.Result) {
        // The recorded line is the shared one, so the manual button and this worker can never
        // describe the same outcome differently. Only the notifications differ, because a
        // notification has a title and can afford a fuller explanation than a one-line label.
        recordResult(prefs, BackupDataExporter.summaryFor(ctx, result))
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

    private fun recordResult(prefs: SharedPreferences, summary: String) =
        BackupDataExporter.record(applicationContext, summary)

    companion object {
        private const val TAG = "BackupWorker"
        const val WORK_NAME = "proton_data_backup"
        private const val WORKER_TIMEOUT_MS = 9L * 60L * 1000L // under WorkManager's ~10-min limit

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

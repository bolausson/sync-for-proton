package me.proton.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.preference.PreferenceManager
import me.proton.sync.calendar.BulkDeletionReceiver
import me.proton.sync.calendar.EventConflictReceiver
import me.proton.sync.settings.LogViewerActivity
import me.proton.sync.settings.SyncSettingsActivity
import me.proton.sync.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object SyncNotificationHelper {

    /**
     * Tag for lines recording what the user was actually TOLD.
     *
     * Registered in [SyncLogTags] so the informational line survives the default sync view; the
     * two "nothing was shown" cases are warnings, which pass from any tag regardless.
     */
    private const val TAG = "SyncNotify"

    private const val CHANNEL_ID = "sync_failures"
    private const val CHANNEL_ID_PROGRESS = "sync_progress"
    private const val CHANNEL_ID_ALERTS = "sync_alerts"
    private const val CHANNEL_ID_BACKUP = "data_backup"

    /**
     * Tracks which sync types are currently active per account so the ongoing
     * notification body string ("Contacts" / "Calendar" / "Contacts and calendar")
     * stays accurate as the two adapters come and go independently. Sync adapters
     * can run on different threads concurrently, so the map is synchronized.
     */
    private val activeSyncTypes = HashMap<String, MutableSet<String>>()
    private val activeSyncLock = Any()

    /**
     * Latest "x/y" progress per (account, syncType). Adapters call [updateProgress]
     * every 50 processed items; the notification body and progress bar reflect the
     * most recently updated entry for the account. Cleared when [clearOngoing]
     * finishes that syncType.
     */
    private data class ProgressEntry(
        val syncType: String,
        val phase: String,    // "uploading" or "downloading" — picks the localized verb
        val current: Int,
        val total: Int
    )
    private val progressByAccount = HashMap<String, ProgressEntry>()

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm?.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.notification_channel_sync_failures),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = context.getString(R.string.notification_channel_sync_description)
                    setShowBadge(false)
                }
                nm?.createNotificationChannel(channel)
            }
        }
    }

    private fun ensureProgressChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm?.getNotificationChannel(CHANNEL_ID_PROGRESS) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID_PROGRESS,
                    context.getString(R.string.notification_channel_sync_progress),
                    NotificationManager.IMPORTANCE_MIN
                ).apply {
                    description = context.getString(R.string.notification_channel_sync_progress_description)
                    setShowBadge(false)
                    setSound(null, null)
                    enableVibration(false)
                    enableLights(false)
                }
                nm?.createNotificationChannel(channel)
            }
        }
    }

    private fun notificationId(accountName: String, syncType: String): Int {
        return ("sync_fail_${accountName}_$syncType").hashCode()
    }

    /** Per-account ongoing-progress notification id (one per logical account, not per type). */
    private fun progressNotificationId(accountName: String): Int {
        return ("sync_progress_$accountName").hashCode()
    }

    fun notifyFailure(context: Context, accountName: String, syncType: String, errorMsg: String) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val prefKey = SyncPreferences.notifySyncFailureKey(syncType, accountName)
        if (!prefs.getBoolean(prefKey, true)) return

        ensureChannel(context)

        val nId = notificationId(accountName, syncType)
        val typeLabel = syncType.replaceFirstChar { it.uppercase() }
        val friendly = friendlyMessage(context, errorMsg)
        val time = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())

        // Tap notification → SyncSettingsActivity
        val settingsIntent = Intent(context, SyncSettingsActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val settingsPending = PendingIntent.getActivity(
            context, nId, settingsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // "View Details" action → LogViewerActivity
        val logIntent = Intent(context, LogViewerActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val logPending = PendingIntent.getActivity(
            context, nId + 1, logIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle(context.getString(R.string.notification_sync_failed_title, typeLabel))
            .setContentText(friendly)
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("$friendly\n$time \u2014 $accountName"))
            .setAutoCancel(true)
            .setSilent(true)
            .setContentIntent(settingsPending)
            .addAction(
                android.R.drawable.ic_menu_info_details,
                context.getString(R.string.notification_action_view_details),
                logPending
            )
            .build()

        val nm = context.getSystemService(NotificationManager::class.java)
        try {
            nm?.notify(nId, notification)
        } catch (e: SecurityException) {
            // Notification permission not granted — ignore silently
        }
    }

    fun clearFailure(context: Context, accountName: String, syncType: String) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm?.cancel(notificationId(accountName, syncType))
    }

    /**
     * Aggregated "couldn't sync" notification: lists every calendar event we permanently
     * gave up uploading this run (after the 3-strike cap), each with the server's raw reason.
     * Surfaces permanently-rejected events instead of letting them vanish silently. Gated on
     * the same per-account calendar sync-failure preference as [notifyFailure].
     *
     * @param events (title, reason) per skipped event.
     */
    fun notifyEventsRejected(context: Context, accountName: String, events: List<Pair<String, String>>) {
        if (events.isEmpty()) return
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        if (!prefs.getBoolean(SyncPreferences.notifySyncFailureKey("calendar", accountName), true)) {
            // Not a fault, but it is the difference between "we told them" and "they never knew".
            // A log that cannot tell those apart cannot answer the only question that matters when
            // somebody reports events quietly not syncing.
            SyncLog.w(
                TAG,
                "Gave up on " + events.size + " event(s) for " + accountName + ", but failure " +
                    "notifications are switched off for this account, so nothing was shown."
            )
            return
        }
        ensureChannel(context)

        val nId = ("events_rejected_$accountName").hashCode()
        val summary = context.getString(R.string.notification_events_rejected_summary, accountName)
        val lines = events.joinToString("\n") {
            context.getString(R.string.notification_events_rejected_line, it.first, it.second)
        }

        val settingsIntent = Intent(context, SyncSettingsActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val settingsPending = PendingIntent.getActivity(
            context, nId, settingsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val logIntent = Intent(context, LogViewerActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val logPending = PendingIntent.getActivity(
            context, nId + 1, logIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(context.getString(R.string.notification_events_rejected_title))
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$summary\n$lines"))
            .setAutoCancel(true)
            .setSilent(true)
            .setContentIntent(settingsPending)
            .addAction(
                android.R.drawable.ic_menu_info_details,
                context.getString(R.string.notification_action_view_details),
                logPending
            )
            .build()

        // Counts only, deliberately. Event titles are event content, and the app promises in
        // public that a log may hold calendar NAMES but never the contents of events. The matching
        // "Proton refuses writes to calendar ..." line already carries the calendar and the local
        // event id, which is enough to correlate without quoting the user's data.
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm == null) {
            SyncLog.w(
                TAG,
                "Gave up on " + events.size + " event(s) for " + accountName +
                    ", but this device returned no NotificationManager, so nothing was shown."
            )
            return
        }
        try {
            nm.notify(nId, notification)
            SyncLog.i(
                TAG,
                "Told " + accountName + " about " + events.size + " event(s) Proton refused."
            )
        } catch (e: SecurityException) {
            SyncLog.w(
                TAG,
                "Could not show the rejected-events notification for " + accountName +
                    "; notification permission is not granted, so nothing was shown.",
                e
            )
        }
    }

    /**
     * Contacts created on the phone that could not be saved to Proton: refused by Proton, failed to
     * prepare on the phone, or answered with nothing usable. Some are still being retried, some the app
     * gave up on after three attempts; none is deleted, and an edit retries a given-up one. Posted after
     * a sync in which something failed, listing every contact still stuck, and withdrawn once none is.
     * Silent, gated like [notifyFailure] on the per-account contacts failure preference. The names
     * appear in the notification only, never in the log.
     *
     * @param contacts (display name or null, reason) per contact still stuck.
     */
    fun notifyContactsRejected(context: Context, accountName: String, contacts: List<Pair<String?, String>>) =
        postContactList(
            context, accountName, contacts,
            notificationKey = "contacts_rejected_$accountName",
            title = context.getString(R.string.notification_contacts_rejected_title),
            summary = context.getString(R.string.notification_contacts_rejected_summary, accountName),
            what = "contact(s) that could not be saved to Proton",
        )

    /**
     * Contacts on Proton too large for this phone's contacts storage: not written, or kept at an older
     * version, and skipped until they change on Proton. Posted when a sync finds a new one, listing every
     * contact still held; withdrawn once none is. Same gating and privacy as [notifyContactsRejected].
     *
     * @param contacts (display name or null, what the phone has instead) per held contact.
     */
    fun notifyContactsTooLarge(context: Context, accountName: String, contacts: List<Pair<String?, String>>) =
        postContactList(
            context, accountName, contacts,
            notificationKey = "contacts_too_large_$accountName",
            title = context.getString(R.string.notification_contacts_too_large_title),
            summary = context.getString(R.string.notification_contacts_too_large_summary, accountName),
            what = "contact(s) too large for this phone",
        )

    /** Whether the too-large notification is still on screen (not dismissed by the user). */
    fun isContactsTooLargeShown(context: Context, accountName: String): Boolean = try {
        val id = ("contacts_too_large_$accountName").hashCode()
        context.getSystemService(NotificationManager::class.java)?.activeNotifications?.any { it.id == id } == true
    } catch (e: Exception) {
        false
    }

    /** Withdraw the too-large notification once no contact is held any more. */
    fun cancelContactsTooLarge(context: Context, accountName: String) {
        context.getSystemService(NotificationManager::class.java)?.cancel(("contacts_too_large_$accountName").hashCode())
    }

    /**
     * One silent notification listing contacts by name with a reason each, opening the settings and
     * offering the log. Gated on the per-account contacts failure preference. Logs counts only.
     */
    private fun postContactList(
        context: Context,
        accountName: String,
        contacts: List<Pair<String?, String>>,
        notificationKey: String,
        title: String,
        summary: String,
        what: String,
    ) {
        if (contacts.isEmpty()) return
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        if (!prefs.getBoolean(SyncPreferences.notifySyncFailureKey("contacts", accountName), true)) {
            SyncLog.w(
                TAG,
                "Found " + contacts.size + " " + what + " for " + accountName +
                    ", but failure notifications are switched off for this account, so nothing was shown."
            )
            return
        }
        ensureChannel(context)

        val nId = notificationKey.hashCode()
        val unnamed = context.getString(R.string.notification_contacts_rejected_unnamed)
        val lines = contacts.joinToString("\n") { (name, reason) ->
            context.getString(R.string.notification_events_rejected_line, name?.takeIf { it.isNotBlank() } ?: unnamed, reason)
        }
        val settingsIntent = Intent(context, SyncSettingsActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val settingsPending = PendingIntent.getActivity(
            context, nId, settingsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val logIntent = Intent(context, LogViewerActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val logPending = PendingIntent.getActivity(
            context, nId + 1, logIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(title)
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$summary\n$lines"))
            .setAutoCancel(true)
            .setSilent(true)
            .setContentIntent(settingsPending)
            .addAction(
                android.R.drawable.ic_menu_info_details,
                context.getString(R.string.notification_action_view_details),
                logPending
            )
            .build()
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm == null) {
            SyncLog.w(TAG, "Found " + contacts.size + " " + what + " for " + accountName +
                ", but this device returned no NotificationManager, so nothing was shown.")
            return
        }
        try {
            nm.notify(nId, notification)
            SyncLog.i(TAG, "Told " + accountName + " about " + contacts.size + " " + what + ".")
        } catch (e: SecurityException) {
            SyncLog.w(TAG, "Could not show the notification about " + what + " for " + accountName +
                "; notification permission is not granted, so nothing was shown.", e)
        }
    }

    /** Withdraw the rejected-contacts notification once no contact is stuck any more. */
    fun cancelContactsRejected(context: Context, accountName: String) {
        context.getSystemService(NotificationManager::class.java)?.cancel(("contacts_rejected_$accountName").hashCode())
    }

    /**
     * Take an event-collision prompt down. Keyed exactly as [notifyEventConflict] keys it, because a
     * prompt that outlives the question it asks is worse than no prompt: the user answers about an
     * event that is already gone.
     */
    fun cancelEventConflict(context: Context, accountName: String, protonEventId: String) {
        val nId = ("event_conflict_${accountName}_$protonEventId").hashCode()
        context.getSystemService(NotificationManager::class.java)?.cancel(nId)
    }

    /**
     * Whether that prompt is on screen right now.
     *
     * [notifyEventConflict] promises the prompt comes back on a later sync, and the download
     * deliberately does not re-notify an event it is already holding, so nothing kept that promise:
     * a prompt swiped away, cleared at a reboot, or stranded under the account's previous name left
     * the event held for ever, syncing in neither direction, with no way left to answer. Asked of
     * the system rather than remembered, because every one of those disappearances happens where
     * this app cannot see it.
     *
     * A look that fails answers true, so a failure re-posts nothing rather than nagging.
     */
    fun isEventConflictShowing(context: Context, accountName: String, protonEventId: String): Boolean {
        val nId = ("event_conflict_${accountName}_$protonEventId").hashCode()
        return try {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return true
            nm.activeNotifications.any { it.id == nId }
        } catch (e: Exception) {
            true
        }
    }

    /**
     * Ask which version of an event to keep after it was changed on the phone AND on the server
     * since the last sync.
     *
     * One notification per event on purpose: the choice discards the other side irreversibly, and a
     * grouped "apply to all" would make that decision blind. Not silent and not auto-cancelling,
     * because until it is answered the event syncs in neither direction, so it must not be easy to
     * miss. Dismissing it only hides it; the hold survives and the prompt returns on a later sync.
     *
     * [otherEditor] is set only when the server-side edit came from somebody else on a shared
     * calendar, in which case keeping the local version replaces their work and the text says so.
     */
    fun notifyEventConflict(
        context: Context,
        accountName: String,
        protonCalendarId: String,
        protonEventId: String,
        androidEventId: Long,
        eventTitle: String,
        otherEditor: String?
    ) {
        ensureChannel(context)

        // Keyed on the event, so re-notifying the same conflict replaces its notification instead
        // of stacking a new one on every sync.
        val nId = ("event_conflict_${accountName}_$protonEventId").hashCode()
        val displayTitle = eventTitle.ifBlank { context.getString(R.string.notification_conflict_untitled_event) }
        val body = if (otherEditor != null) {
            context.getString(R.string.notification_conflict_body_shared, displayTitle, otherEditor)
        } else {
            context.getString(R.string.notification_conflict_body, displayTitle)
        }

        fun conflictAction(actionName: String, requestOffset: Int): PendingIntent {
            val intent = Intent(context, EventConflictReceiver::class.java).apply {
                setAction(actionName)
                putExtra(EventConflictReceiver.EXTRA_ACCOUNT_NAME, accountName)
                putExtra(EventConflictReceiver.EXTRA_CALENDAR_ID, protonCalendarId)
                putExtra(EventConflictReceiver.EXTRA_PROTON_EVENT_ID, protonEventId)
                putExtra(EventConflictReceiver.EXTRA_ANDROID_EVENT_ID, androidEventId)
                putExtra(EventConflictReceiver.EXTRA_NOTIFICATION_ID, nId)
            }
            return PendingIntent.getBroadcast(
                context, nId + requestOffset, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(context.getString(R.string.notification_conflict_title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(false)
            .setOngoing(false)
            .addAction(
                android.R.drawable.ic_menu_upload,
                context.getString(R.string.notification_conflict_keep_remote),
                conflictAction(EventConflictReceiver.ACTION_KEEP_REMOTE, 1)
            )
            .addAction(
                android.R.drawable.ic_menu_save,
                context.getString(R.string.notification_conflict_keep_local),
                conflictAction(EventConflictReceiver.ACTION_KEEP_LOCAL, 2)
            )
            .build()

        val nm = context.getSystemService(NotificationManager::class.java)
        try {
            nm?.notify(nId, notification)
        } catch (e: SecurityException) {
            // Notification permission not granted — the hold still stands, so nothing is lost;
            // the event simply stays unsynced until the user grants permission and we re-prompt.
        }
    }

    /**
     * Post (or update) an ongoing low-priority notification while a sync is running.
     * [syncType] is `"contacts"` or `"calendar"`. If both types are active for the
     * same account the body text reads "Contacts and calendar"; the two adapters can
     * therefore call this independently without coordinating.
     *
     * The channel is `IMPORTANCE_MIN` — silent, no badge, sits at the bottom of the
     * shade so it doesn't compete with real notifications.
     */
    fun showOngoing(context: Context, accountName: String, syncType: String) {
        // Always track active types so the body composes correctly when the user
        // re-enables the toggle mid-sync — but only post if the per-account preference
        // is on (default true).
        val activeTypes: Set<String> = synchronized(activeSyncLock) {
            activeSyncTypes.getOrPut(accountName) { mutableSetOf() }.also { it.add(syncType) }.toSet()
        }
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        if (!prefs.getBoolean(SyncPreferences.notifySyncInProgressKey(accountName), true)) return
        postProgressNotification(context, accountName, activeTypes)
    }

    /**
     * Drop any currently-shown progress notification for [accountName] and forget its
     * active-type tracking. Used when the user toggles the per-account "show notification
     * while syncing" preference off mid-sync — the in-flight syncs continue but stop
     * being announced.
     */
    fun cancelOngoingForAccount(context: Context, accountName: String) {
        synchronized(activeSyncLock) {
            activeSyncTypes.remove(accountName)
            progressByAccount.remove(accountName)
        }
        val nm = context.getSystemService(NotificationManager::class.java)
        nm?.cancel(progressNotificationId(accountName))
    }

    /**
     * Publish "current/total" progress for an active sync. Adapters call this from
     * their upload/download loops at milestone boundaries (every 50 items). The
     * notification's body and progress bar update; system flicker is avoided by
     * `setOnlyAlertOnce(true)` on the underlying builder.
     *
     * [phase] is `"uploading"` or `"downloading"` — picks the localized verb. The
     * caller does not need to coordinate with [showOngoing]; this method assumes the
     * type is already marked active (the adapter brackets its own run).
     */
    fun updateProgress(
        context: Context,
        accountName: String,
        syncType: String,
        phase: String,
        current: Int,
        total: Int
    ) {
        val activeTypes: Set<String> = synchronized(activeSyncLock) {
            progressByAccount[accountName] = ProgressEntry(syncType, phase, current, total)
            (activeSyncTypes[accountName] ?: emptySet()).toSet()
        }
        if (activeTypes.isEmpty()) return // adapter cleared before progress arrived
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        if (!prefs.getBoolean(SyncPreferences.notifySyncInProgressKey(accountName), true)) return
        postProgressNotification(context, accountName, activeTypes)
    }

    /**
     * Mark a sync type finished for an account. Updates the body if the other type
     * is still active; clears the notification entirely once both types are done.
     */
    fun clearOngoing(context: Context, accountName: String, syncType: String) {
        val remaining: Set<String> = synchronized(activeSyncLock) {
            // Drop progress entry only if it belonged to the type that's finishing —
            // otherwise the other type's progress should stay on screen.
            progressByAccount[accountName]?.let { if (it.syncType == syncType) progressByAccount.remove(accountName) }
            val s = activeSyncTypes[accountName] ?: return@synchronized emptySet()
            s.remove(syncType)
            if (s.isEmpty()) {
                activeSyncTypes.remove(accountName)
                progressByAccount.remove(accountName)
            }
            s.toSet()
        }

        val nm = context.getSystemService(NotificationManager::class.java)
        if (remaining.isEmpty()) {
            nm?.cancel(progressNotificationId(accountName))
        } else {
            // Other type still syncing — re-post with a refined body so the user
            // sees "Contacts" instead of "Contacts and calendar" once one finishes.
            postProgressNotification(context, accountName, remaining)
        }
    }

    private fun postProgressNotification(context: Context, accountName: String, activeTypes: Set<String>) {
        if (activeTypes.isEmpty()) return
        ensureProgressChannel(context)

        val nId = progressNotificationId(accountName)

        // If we have a determinate progress entry for one of the active types, the
        // body becomes "Calendar - uploading 124/600" with a real progress bar.
        // Otherwise fall back to the simple "Contacts" / "Calendar" / "both" body
        // and an indeterminate spinner.
        val progress = synchronized(activeSyncLock) {
            progressByAccount[accountName]?.takeIf { it.syncType in activeTypes }
        }

        val body: String
        val builder = NotificationCompat.Builder(context, CHANNEL_ID_PROGRESS)
        if (progress != null) {
            val typeLabel = context.getString(
                if (progress.syncType == "contacts") R.string.notification_sync_in_progress_contacts
                else R.string.notification_sync_in_progress_calendar
            )
            val phaseFmt = when (progress.phase) {
                "uploading" -> R.string.notification_sync_phase_uploading
                "downloading" -> R.string.notification_sync_phase_downloading
                "scanning" -> R.string.notification_sync_phase_scanning
                "decrypting" -> R.string.notification_sync_phase_decrypting
                else -> R.string.notification_sync_phase_uploading
            }
            body = context.getString(
                R.string.notification_sync_progress_body,
                typeLabel,
                context.getString(phaseFmt, progress.current, progress.total)
            )
            builder.setProgress(progress.total.coerceAtLeast(1), progress.current.coerceAtMost(progress.total), false)
        } else {
            val bodyResId = when {
                "contacts" in activeTypes && "calendar" in activeTypes ->
                    R.string.notification_sync_in_progress_both
                "contacts" in activeTypes -> R.string.notification_sync_in_progress_contacts
                else -> R.string.notification_sync_in_progress_calendar
            }
            body = context.getString(bodyResId)
            builder.setProgress(0, 0, true)
        }

        val tapIntent = Intent(context, SyncSettingsActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val tapPending = PendingIntent.getActivity(
            context, nId, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = builder
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(context.getString(R.string.notification_sync_in_progress_title, accountName))
            .setContentText(body)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(tapPending)
            .build()

        val nm = context.getSystemService(NotificationManager::class.java)
        try {
            nm?.notify(nId, notification)
        } catch (e: SecurityException) {
            // Notification permission not granted — ignore silently
        }
    }

    // ===================== Bulk-deletion safety prompt =====================

    private fun ensureAlertsChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm?.getNotificationChannel(CHANNEL_ID_ALERTS) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID_ALERTS,
                    context.getString(R.string.notification_channel_alerts),
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = context.getString(R.string.notification_channel_alerts_description)
                    setShowBadge(true)
                }
                nm?.createNotificationChannel(channel)
            }
        }
    }

    private fun bulkDeletionNotificationId(accountName: String): Int =
        ("bulk_del_$accountName").hashCode()

    /**
     * Post (or refresh) an ONGOING high-importance notification asking the user to confirm a
     * suspiciously large calendar-event deletion that the sync adapter has held back (see
     * [me.proton.sync.calendar.BulkDeletionGuard]). The two actions broadcast to
     * [BulkDeletionReceiver]: "Keep on Proton" (restore locally) or "Delete on Proton" (confirm).
     *
     * Deliberately NOT gated on any sync-notification preference — this needs a decision.
     */
    fun showBulkDeletionPrompt(context: Context, accountName: String, count: Int) {
        ensureAlertsChannel(context)
        val nId = bulkDeletionNotificationId(accountName)

        fun action(act: String, requestCode: Int): PendingIntent {
            val i = Intent(context, BulkDeletionReceiver::class.java).apply {
                action = act
                putExtra(BulkDeletionReceiver.EXTRA_ACCOUNT_NAME, accountName)
                putExtra(BulkDeletionReceiver.EXTRA_NOTIFICATION_ID, nId)
            }
            return PendingIntent.getBroadcast(
                context, requestCode, i,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        val keepPending = action(BulkDeletionReceiver.ACTION_KEEP, nId + 1)
        val deletePending = action(BulkDeletionReceiver.ACTION_DELETE, nId + 2)

        val tapIntent = Intent(context, SyncSettingsActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val tapPending = PendingIntent.getActivity(
            context, nId, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val body = context.getString(R.string.notification_bulk_delete_body, count, accountName)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID_ALERTS)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(context.getString(R.string.notification_bulk_delete_title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setOngoing(true)
            .setAutoCancel(false)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(tapPending)
            .addAction(android.R.drawable.ic_menu_revert, context.getString(R.string.notification_bulk_delete_keep), keepPending)
            .addAction(android.R.drawable.ic_menu_delete, context.getString(R.string.notification_bulk_delete_delete), deletePending)
            .build()

        val nm = context.getSystemService(NotificationManager::class.java)
        try {
            nm?.notify(nId, notification)
        } catch (e: SecurityException) {
            // Notification permission not granted — ignore silently
        }
    }

    fun cancelBulkDeletionPrompt(context: Context, accountName: String) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm?.cancel(bulkDeletionNotificationId(accountName))
    }

    // ===================== Server→local deletion confirmation =====================

    private fun remoteDeletionNotificationId(type: String, accountName: String): Int =
        ("remote_del_${type}_$accountName").hashCode()

    /**
     * Post (or refresh) an ongoing prompt asking whether items deleted on Proton (contacts or
     * calendar events) should also be removed from this phone. Actions broadcast to
     * [RemoteDeletionReceiver]: Delete from phone / Keep on phone. See [RemoteDeletionGuard].
     */
    fun showRemoteDeletionPrompt(context: Context, accountName: String, type: String, count: Int) {
        ensureAlertsChannel(context)
        val nId = remoteDeletionNotificationId(type, accountName)

        fun action(act: String, requestCode: Int): PendingIntent {
            val i = Intent(context, RemoteDeletionReceiver::class.java).apply {
                action = act
                putExtra(RemoteDeletionReceiver.EXTRA_ACCOUNT_NAME, accountName)
                putExtra(RemoteDeletionReceiver.EXTRA_TYPE, type)
                putExtra(RemoteDeletionReceiver.EXTRA_NOTIFICATION_ID, nId)
            }
            return PendingIntent.getBroadcast(
                context, requestCode, i,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        val deletePending = action(RemoteDeletionReceiver.ACTION_DELETE, nId + 1)
        val keepPending = action(RemoteDeletionReceiver.ACTION_KEEP, nId + 2)

        val tapIntent = Intent(context, SyncSettingsActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val tapPending = PendingIntent.getActivity(
            context, nId, tapIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // A filter removal is not a remote deletion and must not claim to be one: those contacts
        // are untouched on Proton and only stop being copied here.
        val isFilter = type == RemoteDeletionGuard.TYPE_CONTACTS_FILTER
        val body = context.getString(
            when {
                isFilter -> R.string.contact_filter_removal_body
                type == RemoteDeletionGuard.TYPE_CALENDAR -> R.string.remote_deletion_body_calendar
                else -> R.string.remote_deletion_body_contacts
            },
            count, accountName
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID_ALERTS)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(
                context.getString(
                    if (isFilter) R.string.contact_filter_removal_title
                    else R.string.remote_deletion_title
                )
            )
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setOngoing(true)
            .setAutoCancel(false)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(tapPending)
            .addAction(android.R.drawable.ic_menu_delete, context.getString(R.string.remote_deletion_delete), deletePending)
            .addAction(android.R.drawable.ic_menu_revert, context.getString(R.string.remote_deletion_keep), keepPending)
            .build()

        val nm = context.getSystemService(NotificationManager::class.java)
        try { nm?.notify(nId, notification) } catch (e: SecurityException) { /* no notif permission */ }
    }

    fun cancelRemoteDeletionPrompt(context: Context, accountName: String, type: String) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm?.cancel(remoteDeletionNotificationId(type, accountName))
    }

    // ===================== Data backup result =====================

    private fun ensureBackupChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm?.getNotificationChannel(CHANNEL_ID_BACKUP) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID_BACKUP,
                    context.getString(R.string.notification_channel_backup),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = context.getString(R.string.notification_channel_backup_description)
                    setShowBadge(false)
                }
                nm?.createNotificationChannel(channel)
            }
        }
    }

    private const val PAYMENT_FAILED_ID = 0x5A7E_FA11

    /**
     * Paddle could not take the subscription payment (direct flavour). [syncOn]: a renewal failed and
     * sync keeps running while Paddle retries; otherwise sync is off (the first payment never went
     * through, or the subscription stayed past due too long). The caller decides when: once per
     * episode, and again when sync turns off or on during it, which replaces the text. Tapping opens settings, where the card's button
     * fetches Paddle's payment page: its link expires after about a day, so it cannot live in here.
     */
    fun notifyPaymentFailed(context: Context, syncOn: Boolean) {
        // Without the permission Android drops the notification without an error, so check first:
        // the log line below records what the user was actually told.
        if (!androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            SyncLog.w(TAG, "Could not show the payment-failed notification: notifications are off for the app")
            return
        }
        ensureAlertsChannel(context)
        // The app can be allowed to notify while this one channel is switched off.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            context.getSystemService(NotificationManager::class.java)
                ?.getNotificationChannel(CHANNEL_ID_ALERTS)?.importance == NotificationManager.IMPORTANCE_NONE
        ) {
            SyncLog.w(TAG, "Could not show the payment-failed notification: the alerts channel is switched off")
            return
        }
        val body = context.getString(if (syncOn) R.string.label_subscription_grace else R.string.label_subscription_unpaid)
        val tapIntent = Intent(context, SyncSettingsActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val tapPending = PendingIntent.getActivity(
            context, PAYMENT_FAILED_ID, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID_ALERTS)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(context.getString(R.string.notification_payment_failed_title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(tapPending)
            .build()
        try {
            context.getSystemService(NotificationManager::class.java)?.notify(PAYMENT_FAILED_ID, notification)
            SyncLog.i(TAG, "Told the user the subscription payment failed (sync ${if (syncOn) "still on" else "off"}).")
        } catch (e: SecurityException) {
            SyncLog.w(TAG, "Could not show the payment-failed notification: notification permission missing")
        }
    }

    /** The payment went through, or the subscription ended: the prompt no longer applies. */
    fun cancelPaymentFailed(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(PAYMENT_FAILED_ID)
    }

    /**
     * Post the outcome of a data-backup run (success counts, emptiness-guard skip, or a
     * failure). Low-importance + silent so a routine success is unobtrusive; the user can
     * tap through to settings. [isAlert] raises it onto the high-importance alerts channel
     * for cases that need attention (e.g. a lost backup folder).
     */
    fun showBackupResult(context: Context, title: String, body: String, isAlert: Boolean = false) {
        val channelId = if (isAlert) { ensureAlertsChannel(context); CHANNEL_ID_ALERTS } else { ensureBackupChannel(context); CHANNEL_ID_BACKUP }
        val nId = "data_backup_result".hashCode()

        val tapIntent = Intent(context, SyncSettingsActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val tapPending = PendingIntent.getActivity(
            context, nId, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setSilent(!isAlert)
            .setPriority(if (isAlert) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_LOW)
            .setContentIntent(tapPending)
            .build()

        val nm = context.getSystemService(NotificationManager::class.java)
        try {
            nm?.notify(nId, notification)
        } catch (e: SecurityException) {
            // Notification permission not granted — ignore silently
        }
    }

    fun friendlyMessage(context: Context, rawError: String): String {
        val lower = rawError.lowercase()
        return when {
            lower.contains("unable to resolve host") || lower.contains("unknownhostexception") ->
                context.getString(R.string.error_no_internet)
            lower.contains("timeout") ->
                context.getString(R.string.error_timeout)
            // Be specific about what counts as an auth error — bare "auth" used to match
            // synthesized messages like "Sync incomplete (io=1, auth=0)" and surface the
            // misleading "session expired" string. We only want true auth signals here.
            lower.contains("http 401") || lower.contains("http 403") ||
                lower.contains("unauthorized") || lower.contains("authentic") ||
                lower.contains("authexception") || lower.contains("session expired") ||
                lower.contains("auth failed") || lower.contains("auth required") ->
                context.getString(R.string.error_session_expired)
            lower.contains("http 429") ->
                context.getString(R.string.error_too_many_requests)
            lower.contains("http 5") ->
                context.getString(R.string.error_server_error)
            lower.contains("http 4") ->
                context.getString(R.string.error_request_rejected)
            lower.contains("ssl") || lower.contains("certificate") ->
                context.getString(R.string.error_ssl)
            lower.contains("socketexception") || lower.contains("connectionreset") || lower.contains("broken pipe") ->
                context.getString(R.string.error_connection_interrupted)
            else ->
                context.getString(R.string.error_unexpected)
        }
    }
}

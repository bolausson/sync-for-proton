package me.proton.sync.calendar

import android.accounts.Account
import android.content.Context
import android.provider.CalendarContract
import androidx.preference.PreferenceManager
import me.proton.sync.SyncLog
import me.proton.sync.SyncNotificationHelper
import me.proton.sync.calendar.NewEventHoldRules.State
import me.proton.sync.db.SyncDatabase

/**
 * Debug builds only: the event-holds verb (SyncSettingsActivity.handleDebugCommand, debug_command event_holds). Reads
 * and logs, and never sweeps, writes or sends anything: the calendar editors this phone has, then one line per held
 * series with what the next upload does with it ([NewEventHoldRules.nextUpload]), then the counts. Never a title.
 */
internal object NewEventHoldDebug {

    suspend fun log(context: Context, account: Account, database: SyncDatabase, tag: String) {
        val editors = CalendarEditors.packages(context)
        SyncLog.i(tag, "event_holds: calendar editors on this phone: " + (editors?.sorted()?.joinToString().takeUnless { it.isNullOrEmpty() } ?: if (editors == null) "query failed" else "none"))
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val entries = NewEventHoldStore.snapshot(prefs, account.name)
        if (entries.isEmpty()) {
            SyncLog.i(tag, "event_holds: nothing waiting, kept or published for '${account.name}'")
            return
        }
        if (!NewEventHolds.hasCalendarPermission(context)) {
            SyncLog.w(tag, "event_holds: no calendar permission, so the rows of ${entries.size} entr(ies) cannot be read")
            return
        }
        val mappedCalendarIds = database.calendarSyncDao().getAllByAccount(account.name).mapNotNullTo(HashSet()) { it.androidCalendarId }
        val mappedEventIds = database.calendarEventSyncDao().androidEventIdsByAccount(account.name).toHashSet()
        val client = context.contentResolver.acquireContentProviderClient(CalendarContract.AUTHORITY)
        if (client == null) {
            SyncLog.w(tag, "event_holds: the calendar provider is not available")
            return
        }
        var promptsUp = 0
        try {
            val present = NewEventHolds.presentRows(client, account, entries.map { it.eventId })
            val prompts = NewEventHolds.prompts(context, client, account, mappedCalendarIds, entries)
                ?.associateBy { it.entry.eventId }.orEmpty()
            for (e in entries) {
                val row = present?.get(e.eventId)
                val rowState = when {
                    present == null -> "unreadable"
                    row == null -> "gone"
                    row.calendarId != e.calendarId -> "moved to calendar ${row.calendarId}"
                    !row.syncId.isNullOrBlank() -> "synced"
                    e.eventId in mappedEventIds -> "mapped"
                    row.deleted -> "tombstone"
                    else -> "present"
                }
                val calendar = NewEventHolds.calendarRow(client, account, e.calendarId)?.name ?: "?"
                val prompt = prompts[e.eventId]
                val up = SyncNotificationHelper.isNewEventHoldPromptShowing(context, account.name, e.eventId)
                if (up) promptsUp++
                SyncLog.i(
                    tag,
                    "event_holds: ${e.state.name.lowercase()} event ${e.eventId} in calendar ${e.calendarId} ('$calendar'), " +
                        "writer ${e.writer ?: "none recorded"}; row $rowState, DIRTY=${if (row?.dirty == true) 1 else 0}; " +
                        "publish ${if (prompt?.canPublish == true) "yes" else "no"}, move ${prompt?.moveTarget?.let { "'${it.name}'" } ?: "none"}, " +
                        "prompt ${if (up) "showing" else "not showing"}; " +
                        "next upload: ${NewEventHoldRules.nextUpload(e, present, mappedEventIds)}"
                )
            }
        } finally {
            client.close()
        }
        SyncLog.i(
            tag,
            "event_holds: ${entries.count { it.state == State.WAITING }} waiting, ${entries.count { it.state == State.KEPT }} kept, " +
                "${entries.count { it.state == State.PUBLISHED }} published (until uploaded) for '${account.name}'; " +
                "$promptsUp prompt(s) on screen (at most ${NewEventHoldRules.MAX_PROMPTS}), the summary of the rest " +
                if (SyncNotificationHelper.isNewEventHoldSummaryShowing(context, account.name)) "showing" else "not showing"
        )
    }
}

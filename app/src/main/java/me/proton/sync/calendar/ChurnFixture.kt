package me.proton.sync.calendar

import android.accounts.Account
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import kotlinx.coroutines.runBlocking
import me.proton.sync.RemoteDeletionGuard
import me.proton.sync.SyncLog
import me.proton.sync.SyncUtils
import me.proton.sync.db.daos.CalendarEventSyncDao
import me.proton.sync.db.daos.CalendarSyncDao
import java.util.UUID

/**
 * Debug fixture churn_fixture (extra calendar = part of a calendar's display name), reached only from
 * SyncSettingsActivity.handleDebugCommand, which refuses everything in a release build.
 *
 * It makes one of the account's read-only calendars look the way Proton leaves a subscribed feed that it re-imported
 * under new event ids. Every mapped event of the calendar gets a made-up id: in its mapping, renamed in one statement,
 * then in the row's _SYNC_ID and in the ORIGINAL_SYNC_ID of overrides that point at it, both written through the
 * sync-adapter URI so nothing is marked for upload. The calendar's modelevents cursor is cleared first, so the next sync
 * does not skip it. That sync finds every event gone from Proton and every server id new: it downloads the server's
 * events and, for a subscribed or holiday calendar, removes the old copies without asking, each having a new copy of
 * the same content ([planServerDeletions]). In a custom range, on a calendar large enough for the windowed fetch, the
 * old copies outside the range go as well, once one inside it was replaced. Except in the calendar's first complete
 * pass in 2.1.0 or later, which asks about whatever is not on the phone again.
 *
 * Events the deletion guard tracks by id keep their ids: kept with Keep on phone, held for the question, or re-keyed
 * from a deleted calendar (RemoteDeletionGuard). Renamed, they would lose that protection and go without the question.
 *
 * Meant for subscribed and holiday calendars. A view-only calendar shared from another Proton account is read-only
 * too but not external: there the next sync treats the made-up ids as deleted events, so from ten events it asks, and
 * the answer must be Delete from phone (Keep on phone leaves the made-up copies beside the real ones until a Remote to
 * Local resync); below ten it removes them at once.
 *
 * It holds the account's calendar sync lock (SyncUtils.withSyncLock) from its first read to its last write: it does not
 * start while a calendar sync of the account runs, and a calendar sync that starts meanwhile is skipped. Request the
 * sync after its "churn_fixture:" line.
 */
internal object ChurnFixture {
    private const val TAG = "DebugCommand"

    fun run(
        context: Context,
        account: Account,
        calendarDao: CalendarSyncDao,
        eventDao: CalendarEventSyncDao,
        namePart: String
    ) {
        var started = false
        SyncUtils.withSyncLock("calendar", account.name, TAG) {
            started = true
            runBlocking { renameAll(context, account, calendarDao, eventDao, namePart) }
        }
        if (!started) {
            SyncLog.w(TAG, "churn_fixture ignored: a calendar sync of '${account.name}' is running; try again after it")
        }
    }

    private suspend fun renameAll(
        context: Context,
        account: Account,
        calendarDao: CalendarSyncDao,
        eventDao: CalendarEventSyncDao,
        namePart: String
    ) {
        val matches = calendarDao.getAllByAccount(account.name)
            .filter { it.androidCalendarId != null && it.displayName.contains(namePart, ignoreCase = true) }
        if (matches.size != 1) {
            SyncLog.w(TAG, "churn_fixture ignored: ${matches.size} calendars of '${account.name}' match '$namePart'")
            return
        }
        val cal = matches.single()
        val androidCalId = cal.androidCalendarId ?: return
        val client = context.contentResolver.acquireContentProviderClient(CalendarContract.AUTHORITY) ?: return
        client.use { provider ->
            // This account's calendar only, as every write below.
            val access = provider.query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL),
                "${CalendarContract.Calendars._ID} = ? AND ${CalendarContract.Calendars.ACCOUNT_NAME} = ? AND " +
                    "${CalendarContract.Calendars.ACCOUNT_TYPE} = ?",
                arrayOf(androidCalId.toString(), account.name, account.type), null
            )?.use { c -> if (c.moveToFirst()) c.getInt(0) else null }
            if (access == null || access > CalendarContract.Calendars.CAL_ACCESS_READ) {
                SyncLog.w(TAG, "churn_fixture ignored: '${cal.displayName}' is not a read-only calendar (access $access)")
                return@use
            }
            val gType = RemoteDeletionGuard.TYPE_CALENDAR
            val tracked = RemoteDeletionGuard.keptIds(context, gType, account.name) +
                RemoteDeletionGuard.pendingIds(context, gType, account.name) +
                RemoteDeletionGuard.rekeyedIds(context, account.name)
            calendarDao.upsert(cal.copy(lastModelEventId = null))
            val (untouched, events) = eventDao.getAllByCalendar(account.name, cal.protonCalendarId)
                .partition { it.protonEventId in tracked }
            val eventsUri = SyncUtils.asCallerSyncAdapter(CalendarContract.Events.CONTENT_URI, account)
            for (event in events) {
                val madeUp = "churnfixture" + UUID.randomUUID().toString().replace("-", "").take(20)
                // The mapping first. A crash right after it leaves the real _SYNC_ID on a row only the made-up mapping
                // names: the next sync downloads the real event again and the made-up one goes, as for the others
                // (deleteVanishedEvents keeps a row that another mapping names).
                eventDao.renameEvent(account.name, event.protonEventId, madeUp)
                event.androidEventId?.let { rowId ->
                    provider.update(
                        eventsUri,
                        ContentValues().apply { put(CalendarContract.Events._SYNC_ID, madeUp) },
                        "${CalendarContract.Events._ID} = ? AND ${CalendarContract.Events.CALENDAR_ID} = ?",
                        arrayOf(rowId.toString(), androidCalId.toString())
                    )
                }
                provider.update(
                    eventsUri,
                    ContentValues().apply { put(CalendarContract.Events.ORIGINAL_SYNC_ID, madeUp) },
                    "${CalendarContract.Events.CALENDAR_ID} = ? AND ${CalendarContract.Events.ORIGINAL_SYNC_ID} = ?",
                    arrayOf(androidCalId.toString(), event.protonEventId)
                )
            }
            SyncLog.i(
                TAG,
                "churn_fixture: ${events.size} event(s) of read-only '${cal.displayName}' now carry made-up ids" +
                    (if (untouched.isEmpty()) "" else "; ${untouched.size} kept, held or re-keyed event(s) keep theirs") +
                    "; the next calendar sync of '${account.name}' downloads the ones in its range again under their " +
                    "real ids and removes the made-up copies, or asks about them in the calendar's first complete pass " +
                    "since 2.1.0"
            )
        }
    }
}

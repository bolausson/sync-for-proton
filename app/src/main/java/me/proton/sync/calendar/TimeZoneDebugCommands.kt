package me.proton.sync.calendar

import android.accounts.Account
import android.accounts.AccountManager
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Bundle
import android.provider.CalendarContract
import androidx.preference.PreferenceManager
import me.proton.sync.ProtonSyncApp
import me.proton.sync.SyncConstants
import me.proton.sync.SyncLog
import me.proton.sync.SyncPreferences
import me.proton.sync.SyncUtils
import me.proton.sync.db.daos.CalendarEventSyncDao
import java.time.LocalDateTime
import java.time.Year

/**
 * The time-zone debug commands (tz_* and retime_*), reached only from SyncSettingsActivity.handleDebugCommand,
 * which refuses everything in a release build. Each logs under "DebugCommand". Call the suspend ones and
 * [probe] off the main thread.
 */
internal object TimeZoneDebugCommands {
    private const val TAG = "DebugCommand"

    /** tz_info: what the phone's tz data is, what the switch and the notice say, and each account's pass state. */
    suspend fun info(context: Context, dao: CalendarEventSyncDao) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        // ICU's version is java.time's on Android: libcore registers one provider, IcuZoneRulesProvider, which
        // reports exactly this (ZoneRulesProvider itself is not in the public SDK).
        val icu = TimeZoneRuleSource.phoneTzdataVersion()
        val displayed = TimeZoneRuleSource.displayedTzdataVersion(prefs)
        val year = Year.now().value
        val outdated = TzDataFreshness.isOutdated(displayed, year)
        SyncLog.i(
            TAG,
            "tz_info: tz data $icu (ICU, which java.time reads), judged ${displayed ?: "unknown"}" +
                (if (displayed != icu) " (debug override)" else "") +
                ": outdated=$outdated (data year ${TzDataFreshness.year(displayed)}, clock year $year, floor ${TzDataFreshness.FLOOR_YEAR})"
        )
        val snapshot = TimeZoneRuleSource.snapshot(context)
        SyncLog.i(
            TAG,
            "tz_info: online time zone updates ${if (snapshot.online) "on" else "off"}; notice answer " +
                "${prefs.getString(SyncPreferences.KEY_TZ_OUTDATED_NOTICE_ANSWER, null) ?: "none"}, last shown " +
                "${prefs.getLong(SyncPreferences.KEY_TZ_OUTDATED_NOTICE_LAST_SHOWN_MS, 0L)}; ical4j updater " +
                "${IcalTimeZoneUpdates.PROPERTY}=${System.getProperty(IcalTimeZoneUpdates.PROPERTY)}"
        )
        SyncLog.i(TAG, "tz_info: re-time identity now ${TimeZoneRuleSource.retimeIdentity(context, snapshot)}")
        for (account in AccountManager.get(context).getAccountsByType(ProtonSyncApp.ACCOUNT_TYPE)) {
            val stored = prefs.getString(SyncPreferences.timeZoneRetimeKey(account.name), null)
            val tagged = dao.countWithSyncTag(account.name, EventRetimePlanner.RETIME_TAG)
            SyncLog.i(TAG, "tz_info: '${account.name}': checked for ${stored ?: "never"}, $tagged event(s) waiting to be re-timed")
        }
        val online = TimeZoneRuleSource.online
        SyncLog.i(
            TAG,
            "tz_info: tzurl.org release last seen ${prefs.getString(SyncPreferences.KEY_TZURL_SEEN_VERSION, null) ?: "none"}; " +
                "this run: ${online.snapshot().ifEmpty { mapOf("-" to "nothing fetched") }}" +
                (online.stopReason?.let { "; stopped: $it" } ?: "")
        )
    }

    /**
     * tz_probe (extras tzid, and local as yyyyMMddTHHmmss): the instant one local time gets under the phone's
     * rules and, in ON mode, the online rules, and the columns the download would write for it as a one-off
     * event and as a series. May fetch from tzurl.org in ON mode, like a sync would: never on the main thread.
     */
    fun probe(context: Context, tzid: String, localText: String) {
        val local = try {
            LocalDateTime.parse(localText, IcalTimeValues.LOCAL_FORMAT)
        } catch (_: Exception) {
            SyncLog.w(TAG, "tz_probe ignored: '$localText' is not yyyyMMddTHHmmss")
            return
        }
        val zone = EventZones.resolve(tzid)
        if (zone == null) {
            SyncLog.w(TAG, "tz_probe: '$tzid' is not a zone this phone knows: the download keeps ical4j's times for it")
            return
        }
        val snapshot = TimeZoneRuleSource.snapshot(context)
        val phone = LocalTimes.toInstant(local, zone.zone.rules)
        // A series reads a wall time that occurs twice as the provider expands it: the later instant.
        val series = LocalTimes.toInstant(local, zone.zone.rules, providerExpands = true)
        val onlineRules = snapshot.onlineFor(zone.zone, local.year)
        val online = onlineRules?.let { LocalTimes.toInstant(local, it) }
        val standalone = online ?: phone
        SyncLog.i(
            TAG,
            "tz_probe $tzid $localText (column ${zone.columnId}): phone rules ${phone} (${zone.zone.rules.getOffset(phone)}); " +
                "online ${online?.let { "$it (${onlineRules.getOffset(it)})" } ?: if (snapshot.online) "not used" else "off"}; " +
                "one-off event DTSTART=${standalone.toEpochMilli()}, series DTSTART=${series.toEpochMilli()}"
        )
    }

    /** retime_check: the pass's decisions now, without writing anything. */
    suspend fun retimeCheck(context: Context, account: Account, dao: CalendarEventSyncDao) {
        val client = context.contentResolver.acquireContentProviderClient(CalendarContract.AUTHORITY)
        if (client == null) {
            SyncLog.w(TAG, "retime check: the calendar provider is not available")
            return
        }
        client.use { provider ->
            val outcome = EventRetimePass.evaluate(
                context, provider, account, dao.getAllMappedByAccount(account.name), TimeZoneRuleSource.snapshot(context)
            )
            SyncLog.i(TAG, "retime check '${account.name}' (nothing written): ${outcome.summary}")
            outcome.decisions.groupBy { it.verdict }.forEach { (verdict, ds) ->
                if (verdict == EventRetimePlanner.Verdict.CONSISTENT || verdict == EventRetimePlanner.Verdict.GONE) return@forEach
                SyncLog.i(TAG, "retime check: ${verdict.name.lowercase()}: " + ds.take(8).joinToString(", ") {
                    "${it.mapping.androidEventId}${if (it.reason.isNotEmpty()) " (${it.reason}${it.deltaMinutes?.let { d -> " ${d}min" } ?: ""})" else ""}"
                } + if (ds.size > 8) " and ${ds.size - 8} more" else "")
            }
        }
    }

    /** retime_run: forget that the account was checked and ask for a calendar sync, which runs the pass. */
    fun retimeRun(context: Context, account: Account) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .remove(SyncPreferences.timeZoneRetimeKey(account.name)).apply()
        ContentResolver.requestSync(account, SyncConstants.AUTHORITY_CALENDAR, Bundle().apply {
            putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
            putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
        })
        SyncLog.i(TAG, "retime run '${account.name}': calendar sync requested; the pass runs at its start")
    }

    /**
     * retime_fixture (extras event_id, minutes, mode = clean | dirty | edited): make one of this account's synced rows
     * look stored with other rules. Its start (and end) move by [minutes] through the sync-adapter URI and its
     * mapping's baseline (fingerprint and time record) is re-taken, as if the download had written it so.
     * "dirty" then adds a reminder and "edited" changes the title, both through the normal URI as a user would.
     * Then retime_run: clean is downloaded again; dirty is only marked, its upload sends Proton's own
     * time text with the reminder, and it is downloaded again at the following sync; edited is marked the same
     * way, its upload sends the new title with Proton's own time text, and it is downloaded again afterwards.
     */
    suspend fun retimeFixture(
        context: Context,
        account: Account,
        dao: CalendarEventSyncDao,
        eventId: Long,
        minutes: Int,
        mode: String
    ) {
        val mapping = dao.getByAndroidId(account.name, eventId)
        if (mapping == null) {
            SyncLog.w(TAG, "retime_fixture ignored: row $eventId has no mapping on '${account.name}'")
            return
        }
        val resolver = context.contentResolver
        val row = resolver.query(
            ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId),
            arrayOf(
                CalendarContract.Events.ACCOUNT_NAME, CalendarContract.Events.ACCOUNT_TYPE,
                CalendarContract.Events.DTSTART, CalendarContract.Events.DTEND, CalendarContract.Events.TITLE
            ),
            null, null, null
        )?.use { c ->
            if (!c.moveToFirst()) null
            else Triple(c.getString(0) to c.getString(1), c.getLong(2) to (if (c.isNull(3)) null else c.getLong(3)), c.getString(4))
        }
        if (row == null || row.first.first != account.name || row.first.second != account.type) {
            SyncLog.w(TAG, "retime_fixture ignored: row $eventId is not one of '${account.name}'s events")
            return
        }
        val shift = minutes * 60_000L
        val values = ContentValues().apply {
            put(CalendarContract.Events.DTSTART, row.second.first + shift)
            row.second.second?.let { put(CalendarContract.Events.DTEND, it + shift) }
        }
        val uri = ContentUris.withAppendedId(SyncUtils.asCallerSyncAdapter(CalendarContract.Events.CONTENT_URI, account), eventId)
        val client = resolver.acquireContentProviderClient(CalendarContract.AUTHORITY) ?: return
        client.use { provider ->
            provider.update(uri, values, null, null)
            val shared = EventConflictGuard.readSharedValues(provider, eventId)
            dao.setBaseline(
                account.name, mapping.protonEventId,
                shared?.let { EventConflictGuard.fingerprintOf(it) }, shared?.let { SyncedTimes.encode(it) }
            )
        }
        when (mode) {
            "dirty" -> resolver.insert(CalendarContract.Reminders.CONTENT_URI, ContentValues().apply {
                put(CalendarContract.Reminders.EVENT_ID, eventId)
                put(CalendarContract.Reminders.MINUTES, 17)
                put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
            })
            "edited" -> resolver.update(
                ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId),
                ContentValues().apply { put(CalendarContract.Events.TITLE, (row.third ?: "") + " (edited)") },
                null, null
            )
        }
        SyncLog.i(TAG, "retime_fixture: row $eventId moved by $minutes min and re-baselined ($mode); next: the retime_run debug command for '${account.name}'")
    }
}

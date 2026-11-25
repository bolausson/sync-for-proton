package me.proton.sync.calendar

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import android.provider.CalendarContract
import androidx.preference.PreferenceManager
import me.proton.sync.ProtonSyncApp
import me.proton.sync.SyncLog
import me.proton.sync.SyncPreferences
import me.proton.sync.db.daos.CalendarSyncDao

/**
 * Debug builds only: the calendar-picks probe and the default-calendar-fixture, behind the debug commands of the
 * same names (SyncSettingsActivity.handleDebugCommand, and the debug script's verbs). Neither sends anything to
 * Proton, and neither requests a sync. The Default calendar row's own verb is DefaultCalendarPicker.Debug.
 */
internal object PrimaryCalendarDebug {

    private const val TAG = "DebugCommand"

    private const val FIXTURE_FAIL = "fail"
    private const val FIXTURE_NULL = "null"
    private const val FIXTURE_ID_PREFIX = "id:"

    /**
     * What a calendar sync takes as Proton's calendar settings while the fixture is set ([raw]: the stored value of
     * [SyncPreferences.debugDefaultCalendarFixtureKey]), or null: ask Proton. Only debug builds read it.
     */
    fun fixtureSettings(raw: String?): PrimaryCalendarChooser.Settings? = when {
        raw == FIXTURE_FAIL -> PrimaryCalendarChooser.Settings.Failed("debug fixture: fail")
        raw == FIXTURE_NULL -> PrimaryCalendarChooser.Settings.Loaded(null)
        raw != null && raw.startsWith(FIXTURE_ID_PREFIX) && raw.length > FIXTURE_ID_PREFIX.length ->
            PrimaryCalendarChooser.Settings.Loaded(raw.substring(FIXTURE_ID_PREFIX.length))
        else -> null
    }

    /**
     * default-calendar-fixture: [mode] "fail" makes the next calendar syncs of [account] take settings/calendar as
     * failed, "null" as DefaultCalendarID null, "name" as DefaultCalendarID = the Proton id of the one calendar on
     * this phone whose name contains [namePart], and "real" removes the fixture. Nothing is sent to Proton and no
     * sync is requested: the next calendar sync reads it.
     */
    suspend fun setFixture(
        context: Context,
        account: Account,
        calendarSyncDao: CalendarSyncDao,
        mode: String?,
        namePart: String?
    ) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val key = SyncPreferences.debugDefaultCalendarFixtureKey(account.name)
        when (mode) {
            FIXTURE_FAIL, FIXTURE_NULL -> {
                prefs.edit().putString(key, mode).apply()
                SyncLog.i(
                    TAG,
                    "default_calendar_fixture: the next calendar syncs of '${account.name}' take settings/calendar as " +
                        (if (mode == FIXTURE_FAIL) "failed" else "DefaultCalendarID null") + "; nothing is sent to Proton"
                )
            }
            "real" -> {
                prefs.edit().remove(key).apply()
                SyncLog.i(TAG, "default_calendar_fixture: '${account.name}' asks Proton for settings/calendar again")
            }
            "name" -> {
                val part = namePart?.trim().orEmpty()
                if (part.isEmpty()) {
                    SyncLog.w(TAG, "default_calendar_fixture ignored: no calendar name part given")
                    return
                }
                val matches = calendarSyncDao.getAllByAccount(account.name)
                    .filter { it.displayName.contains(part, ignoreCase = true) }
                when (matches.size) {
                    0 -> SyncLog.w(TAG, "default_calendar_fixture ignored: no calendar of '${account.name}' on this phone has '$part' in its name")
                    1 -> {
                        val only = matches.single()
                        prefs.edit().putString(key, FIXTURE_ID_PREFIX + only.protonCalendarId).apply()
                        SyncLog.i(
                            TAG,
                            "default_calendar_fixture: the next calendar syncs of '${account.name}' take DefaultCalendarID as " +
                                "'${only.displayName}' (${only.protonCalendarId.take(8)}...); nothing is sent to Proton"
                        )
                    }
                    else -> SyncLog.w(
                        TAG,
                        "default_calendar_fixture ignored: '$part' is in the names of ${matches.size} calendars " +
                            "(${matches.joinToString { "'${it.displayName}'" }}); give a longer part"
                    )
                }
            }
            else -> SyncLog.w(TAG, "default_calendar_fixture ignored: give fail, null, real or a part of a calendar name")
        }
    }

    /**
     * calendar-picks, read-only and without network: every calendar row on the phone, of every account, because
     * a third-party picker sees them all; per account of this app how many rows store IS_PRIMARY = 1; and the row
     * each common pick rule would choose ([CalendarPickRules]).
     *
     * Display names are shown for this app's rows only, and of those only the rows of [only] when it is given:
     * another app's calendar names and account names never reach the log, only its account type.
     */
    fun logPicks(context: Context, only: String?) {
        val resolver = context.contentResolver
        val uri = CalendarContract.Calendars.CONTENT_URI
        // Selections on isPrimary see the stored value; the projection's isPrimary is the provider's COALESCE.
        fun ids(selection: String): Set<Long>? =
            resolver.query(uri, arrayOf(CalendarContract.Calendars._ID), selection, null, null)?.use { c ->
                HashSet<Long>().apply { while (c.moveToNext()) add(c.getLong(0)) }
            }
        val ones = ids("${CalendarContract.Calendars.IS_PRIMARY} = 1")
        val zeros = ids("${CalendarContract.Calendars.IS_PRIMARY} = 0")
        val nulls = ids("${CalendarContract.Calendars.IS_PRIMARY} IS NULL")
        val rows = resolver.query(
            uri,
            arrayOf(
                CalendarContract.Calendars._ID, CalendarContract.Calendars.ACCOUNT_NAME,
                CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.VISIBLE,
                CalendarContract.Calendars.IS_PRIMARY, CalendarContract.Calendars.OWNER_ACCOUNT
            ),
            null, null, CalendarContract.Calendars._ID
        )?.use { c ->
            val out = ArrayList<CalendarPickRules.Row>(c.count)
            while (c.moveToNext()) {
                val id = c.getLong(0)
                out.add(
                    CalendarPickRules.Row(
                        id = id,
                        accountName = c.getString(1).orEmpty(),
                        accountType = c.getString(2).orEmpty(),
                        displayName = c.getString(3),
                        accessLevel = c.getInt(4),
                        visible = c.getInt(5) == 1,
                        storedPrimary = when {
                            ones?.contains(id) == true -> CalendarPickRules.Stored.ONE
                            zeros?.contains(id) == true -> CalendarPickRules.Stored.ZERO
                            nulls?.contains(id) == true -> CalendarPickRules.Stored.NULL
                            else -> CalendarPickRules.Stored.OTHER
                        },
                        readPrimary = if (c.isNull(6)) null else c.getInt(6),
                        ownerAccount = c.getString(7)
                    )
                )
            }
            out
        }
        if (rows == null || ones == null || zeros == null || nulls == null) {
            SyncLog.w(TAG, "calendar_picks: the calendar provider gave no cursor")
            return
        }

        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val ourAccounts = AccountManager.get(context).getAccountsByType(ProtonSyncApp.ACCOUNT_TYPE).map { it.name }
        if (only != null && only !in ourAccounts) {
            SyncLog.w(TAG, "calendar_picks: no account of this app is named '$only', so no calendar name is shown")
        }
        val lastByAccount = ourAccounts.associateWith {
            PrimaryCalendarMemory.decode(prefs.getString(SyncPreferences.primaryCalendarLastKey(it), null))
        }
        fun ours(row: CalendarPickRules.Row) = row.accountType == ProtonSyncApp.ACCOUNT_TYPE
        // The one test for every calendar name this probe prints: only this app's, and of those only [only]'s when given.
        fun namesShown(accountName: String) = only == null || accountName == only
        fun label(row: CalendarPickRules.Row): String = when {
            !ours(row) -> "row ${row.id} other:${row.accountType}"
            namesShown(row.accountName) -> "row ${row.id} ${row.accountName} '${row.displayName.orEmpty()}'"
            else -> "row ${row.id} ${row.accountName}"
        }

        SyncLog.i(
            TAG,
            "calendar_picks: ${rows.size} calendar row(s), ${ourAccounts.size} account(s) of this app. Stored is what a " +
                "selection on isPrimary sees, read what the provider returns (COALESCE(isPrimary, ownerAccount = account_name))"
        )
        for (row in rows) {
            val decision = if (!ours(row)) "" else {
                val last = lastByAccount[row.accountName]
                when {
                    last == null -> " (no decision yet)"
                    last.androidCalId == row.id -> " (chosen: ${reasonText(last.reason)})"
                    else -> " (not chosen)"
                }
            }
            SyncLog.i(
                TAG,
                "calendar_picks: ${label(row)} access=${row.accessLevel} visible=${if (row.visible) 1 else 0} " +
                    "primary stored=${row.storedPrimary.label} read=${row.readPrimary ?: "null"} " +
                    "owner=account:${row.ownerIsAccount}$decision"
            )
        }
        for (name in ourAccounts) {
            val storingOne = rows.count { ours(it) && it.accountName == name && it.storedPrimary == CalendarPickRules.Stored.ONE }
            // The record keeps no name: the chosen row's, as listed above.
            val last = lastByAccount[name]?.let { l ->
                l.copy(name = rows.firstOrNull { ours(it) && it.accountName == name && it.id == l.androidCalId }?.displayName)
            }
            val lastText = lastDecisionText(last, showName = namesShown(name))
            SyncLog.i(
                TAG,
                "calendar_picks: account '$name': $storingOne row(s) store 1 (expected 1 once a calendar sync ran); " +
                    "last decision: $lastText"
            )
        }
        for (rule in CalendarPickRules.Rule.entries) {
            val picked = CalendarPickRules.pick(rule, rows)
            SyncLog.i(TAG, "calendar_picks: pick ${rule.label} -> ${picked?.let { label(it) } ?: "none"}")
        }
    }

    /**
     * An account's last decision as calendar-picks prints it. The chosen calendar's name only when [showName]: with an
     * account named, every other account's calendar names stay out of the log, this one included. A decision without a
     * name (no row on this phone holds the choice) shows none.
     */
    fun lastDecisionText(last: PrimaryCalendarMemory.Last?, showName: Boolean): String = when {
        last == null -> "no decision yet"
        last.protonCalendarId == null -> "none (${reasonText(last.reason)})"
        else -> (last.androidCalId?.let { "row $it" } ?: "no row primary") +
            (if (showName && !last.name.isNullOrBlank()) " '${last.name}'" else "") + " (${reasonText(last.reason)})"
    }

    private fun reasonText(reason: String): String =
        PrimaryCalendarChooser.Reason.entries.firstOrNull { it.name == reason }?.text ?: reason
}

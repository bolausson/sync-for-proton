package me.proton.sync.calendar

import android.accounts.Account
import android.content.ContentProviderClient
import android.content.ContentUris
import android.content.ContentValues
import android.provider.CalendarContract
import me.proton.sync.SyncUtils
import me.proton.sync.db.entities.CalendarSyncEntity

/**
 * Writes one account's primary-calendar decision into Android's calendar provider: IS_PRIMARY = 1 on the chosen
 * row, an explicit 0 on every other row of that account. Not tied to a sync run: the account card's Default calendar
 * row calls [apply] with the new calendar's row right after Proton confirms its PUT settings/calendar
 * ([PrimaryCalendarPicks.applyOutsideSync]).
 *
 *  - **Only this account's rows**: the read selects ACCOUNT_NAME and ACCOUNT_TYPE ([selection]), keeps each row's
 *    own two values from the cursor, and [plan] writes only rows whose values match the account, so a read that
 *    ever lost its account clause still writes nothing outside it. Rows of other accounts and other apps are never
 *    written; a chosen id that is not one of the account's rows gets no 1 ([Applied.chosenRowFound] says so).
 *  - **Never NULL.** The provider returns the column as `COALESCE(isPrimary, ownerAccount = account_name)` (AOSP
 *    CalendarProvider2, sCalendarsProjectionMap), so a stored NULL makes every row whose OWNER_ACCOUNT equals the
 *    account name read as primary, shared calendars included. [Write.value] is a plain Int, 0 or 1.
 *  - **Only what differs** is written, so a run that changes nothing writes nothing.
 *  - **[Target.Leave] writes nothing**: the decision is unknown, or where its row is could not be read, and the
 *    flag never moves or clears because something failed.
 *
 * Stored values are read with the selections `isPrimary = 1` and `isPrimary = 0`. They bind to the table's own
 * column, not to the COALESCE'd projection: CalendarProvider2 hands a Calendars query's selection to SQLite
 * unchanged (the projection map only rewrites the projection), and SQLite resolves a bare name in WHERE against
 * the FROM table's columns before it looks at result aliases. Writes go row by row through the row's sync-adapter
 * URI, as updateLocalCalendar's do. CalendarProvider2's CALENDARS_ID update writes `WHERE _id = ?` and ignores any
 * selection, which is why the ids come from the account-scoped read and [plan] checks each row's account again.
 */
internal object PrimaryCalendarRows {

    /** What one account's rows should store. */
    sealed interface Target {
        /** [androidCalId] stores 1 and every other row of the account 0; null: every row 0 (no primary calendar). */
        data class Primary(val androidCalId: Long?) : Target

        /** Nothing is written: the decision is unknown, or where the chosen calendar's row is could not be read. */
        data object Leave : Target
    }

    /** Where the chosen calendar sits on the phone, from this account's calendar mappings. */
    sealed interface ChosenRow {
        /** The mappings could not be read, so where the row is is not known. */
        data object Unreadable : ChosenRow

        /**
         * [androidCalId] is the chosen calendar's row, null when it has none; [sharedWith] counts the OTHER calendars
         * Proton still lists that map to the same row (an older version's reuse by name could merge two).
         */
        data class Found(val androidCalId: Long?, val sharedWith: Int = 0) : ChosenRow
    }

    /** One calendar row as the table stores it: [stored] is 1, 0, or null for NULL and anything else. */
    data class StoredRow(val id: Long, val accountName: String, val accountType: String, val stored: Int?)

    /** One write: IS_PRIMARY = [value] (0 or 1) on row [id]. */
    data class Write(val id: Long, val value: Int)

    /** What [apply] did: the writes made, and whether the chosen row was one of the account's (true when none was chosen). */
    data class Applied(val writes: List<Write>, val chosenRowFound: Boolean)

    /**
     * Where calendar [protonCalendarId] sits, by this account's [mappings] as (Proton id, Android row) pairs, null
     * when they could not be read. [liveProtonIds] are the calendars Proton listed this run.
     */
    fun locate(protonCalendarId: String, mappings: List<Pair<String, Long?>>?, liveProtonIds: Set<String>): ChosenRow {
        if (mappings == null) return ChosenRow.Unreadable
        val row = mappings.firstOrNull { it.first == protonCalendarId }?.second ?: return ChosenRow.Found(null)
        val others = mappings.count { (id, androidCalId) ->
            androidCalId == row && id != protonCalendarId && id in liveProtonIds
        }
        return ChosenRow.Found(row, others)
    }

    /**
     * The target for [decision], given where its chosen calendar sits ([chosen], from [locate]; not read for a
     * decision that chose none). Unknown, or a row that could not be looked up: [Target.Leave], since the rows the
     * calendar loop touched already carry this run's decision and the others keep theirs. A calendar without a row,
     * or a row that also holds another calendar Proton still lists: no row is primary, because a new event in that
     * row may go up to the other calendar.
     */
    fun targetFor(decision: PrimaryCalendarChooser.Decision, chosen: ChosenRow?): Target = when (decision) {
        is PrimaryCalendarChooser.Decision.Unknown -> Target.Leave
        is PrimaryCalendarChooser.Decision.Known -> when {
            decision.protonCalendarId == null -> Target.Primary(null)
            chosen !is ChosenRow.Found -> Target.Leave
            chosen.sharedWith > 0 -> Target.Primary(null)
            else -> Target.Primary(chosen.androidCalId)
        }
    }

    /** The writes that bring the account's [rows] to [target]: only rows of that account, only where they differ. */
    fun plan(accountName: String, accountType: String, target: Target, rows: List<StoredRow>): List<Write> {
        if (target !is Target.Primary) return emptyList()
        return rows.asSequence()
            .filter { it.accountName == accountName && it.accountType == accountType }
            .mapNotNull { row ->
                val wanted = if (row.id == target.androidCalId) 1 else 0
                if (row.stored == wanted) null else Write(row.id, wanted)
            }
            .toList()
    }

    /** Whether [target]'s row is one of the account's [rows]; true when the target chooses no row. */
    fun chosenRowFound(accountName: String, accountType: String, target: Target, rows: List<StoredRow>): Boolean {
        val id = (target as? Target.Primary)?.androidCalId ?: return true
        return rows.any { it.id == id && it.accountName == accountName && it.accountType == accountType }
    }

    /** The account clause of every read here, with [condition] ANDed on; its arguments are [selectionArgs]. */
    fun selection(condition: String?): String =
        "${CalendarContract.Calendars.ACCOUNT_NAME} = ? AND ${CalendarContract.Calendars.ACCOUNT_TYPE} = ?" +
            (condition?.let { " AND ($it)" } ?: "")

    fun selectionArgs(accountName: String, accountType: String): Array<String> = arrayOf(accountName, accountType)

    /**
     * Brings [account]'s rows to [target] and returns what it did: no writes when nothing differed or the target is
     * [Target.Leave], null when the rows could not be read (then nothing was written). A provider exception is
     * thrown on to the caller, which must not let it fail a sync.
     */
    fun apply(provider: ContentProviderClient, account: Account, target: Target): Applied? {
        if (target !is Target.Primary) return Applied(emptyList(), chosenRowFound = true)
        val rows = read(provider, account) ?: return null
        val writes = plan(account.name, account.type, target, rows)
        for (write in writes) {
            provider.update(
                ContentUris.withAppendedId(
                    SyncUtils.asCallerSyncAdapter(CalendarContract.Calendars.CONTENT_URI, account),
                    write.id
                ),
                ContentValues().apply { put(CalendarContract.Calendars.IS_PRIMARY, write.value) },
                null, null
            )
        }
        return Applied(writes, chosenRowFound(account.name, account.type, target, rows))
    }

    /**
     * Every row of [account] with the value it stores and its own ACCOUNT_NAME and ACCOUNT_TYPE as the provider
     * returned them, or null when the provider gave no cursor.
     */
    fun read(provider: ContentProviderClient, account: Account): List<StoredRow>? {
        val all = provider.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(
                CalendarContract.Calendars._ID,
                CalendarContract.Calendars.ACCOUNT_NAME,
                CalendarContract.Calendars.ACCOUNT_TYPE
            ),
            selection(null),
            selectionArgs(account.name, account.type),
            CalendarContract.Calendars._ID
        )?.use { cursor ->
            val out = ArrayList<Triple<Long, String, String>>(cursor.count)
            while (cursor.moveToNext()) {
                out.add(Triple(cursor.getLong(0), cursor.getString(1).orEmpty(), cursor.getString(2).orEmpty()))
            }
            out
        } ?: return null
        val ones = ids(provider, account, "${CalendarContract.Calendars.IS_PRIMARY} = 1")?.toSet() ?: return null
        val zeros = ids(provider, account, "${CalendarContract.Calendars.IS_PRIMARY} = 0")?.toSet() ?: return null
        return all.map { (id, accountName, accountType) ->
            StoredRow(
                id = id,
                accountName = accountName,
                accountType = accountType,
                stored = when (id) {
                    in ones -> 1
                    in zeros -> 0
                    else -> null
                }
            )
        }
    }

    private fun ids(provider: ContentProviderClient, account: Account, condition: String): List<Long>? =
        provider.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID),
            selection(condition),
            selectionArgs(account.name, account.type),
            CalendarContract.Calendars._ID
        )?.use { cursor ->
            val out = ArrayList<Long>(cursor.count)
            while (cursor.moveToNext()) out.add(cursor.getLong(0))
            out
        }
}

/**
 * This run's primary-calendar decision as the calendar loop writes it (prepareCalendarJob, through updateLocalCalendar
 * and insertLocalCalendar) and the settle pass after the loop reads it. Pure, so it is tested without a device; built
 * by [of] from what performCalendarSync has read before the loop.
 *
 *  - [accountRows]: this account's rows as read before the loop, a COPY (the loop takes claimed ids out of the
 *    lists it was built from). A mapped row that is not among them gets no IS_PRIMARY from the loop: the mapping
 *    may name a row id the provider has since handed to another app's calendar (Calendar Storage's data cleared),
 *    and an update by id is not limited to the account. The settle pass writes the 1 when the row is ours after all.
 *  - [sharedRows]: rows that more than one calendar Proton still lists maps to (merged by name by an earlier
 *    version, see [CalendarRowClaims]). They store 0 whatever the decision: a new event in such a row may go up to
 *    either calendar.
 */
internal class PrimaryCalendarRun(
    val result: PrimaryCalendarChooser.Result,
    private val accountRows: Set<Long>,
    private val sharedRows: Set<Long>,
    /** The calendar ids Proton listed this run. */
    val liveProtonIds: Set<String>,
) {
    /** For the existing row [androidCalId] of calendar [protonCalendarId]: true or false writes 1 or 0, null leaves it. */
    fun forExistingRow(protonCalendarId: String, androidCalId: Long): Boolean? = when (androidCalId) {
        !in accountRows -> null
        in sharedRows -> false
        else -> PrimaryCalendarChooser.rowFlag(result, protonCalendarId)
    }

    /** For a new row of calendar [protonCalendarId]: 1 only for the chosen calendar, else an explicit 0, never NULL. */
    fun forNewRow(protonCalendarId: String): Boolean = PrimaryCalendarChooser.rowFlag(result, protonCalendarId) == true

    companion object {
        fun of(
            result: PrimaryCalendarChooser.Result,
            localCalendarsByName: Map<String, List<Long>>,
            mappings: Collection<CalendarSyncEntity>,
            liveProtonIds: Set<String>,
        ) = PrimaryCalendarRun(
            result = result,
            accountRows = localCalendarsByName.values.flatMapTo(HashSet()) { it },
            sharedRows = CalendarRowClaims.sharedRows(mappings, liveProtonIds),
            liveProtonIds = liveProtonIds,
        )
    }
}

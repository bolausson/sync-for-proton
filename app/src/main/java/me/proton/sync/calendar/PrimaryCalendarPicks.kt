package me.proton.sync.calendar

import android.Manifest
import android.accounts.Account
import android.content.ContentProviderClient
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.preference.PreferenceManager
import me.proton.sync.SyncLog
import me.proton.sync.SyncPreferences
import me.proton.sync.calendar.PrimaryCalendarRows.ChosenRow
import me.proton.sync.calendar.PrimaryCalendarRows.Target

/**
 * Where a primary-calendar decision made outside a calendar sync meets the sync: a change Proton confirmed from the
 * account card's Default calendar row, or that row's fresh read of Proton when Proton's default differs from the
 * phone's record. In memory, because the settings screen and the sync adapter run in one process (the manifest
 * declares no android:process). Should the sync ever get a process of its own, this has to move to a store both read.
 *
 * Every write of an account's IS_PRIMARY rows and of its record ([PrimaryCalendarMemory]) outside the calendar loop
 * runs under [lock]: the sync's settle pass (CalendarSyncAdapter.writePrimaryCalendar) and [applyOutsideSync]. A sync
 * reads [generation] before its settings GET, and its settle pass keeps a pick made since ([since], [settle]), so a
 * sync that read Proton's settings before a choice never undoes it. The lock order is always the account's [lock]
 * first, then this object's own monitor.
 *
 * A pick follows the rules of [PrimaryCalendarRows.targetFor] and [PrimaryCalendarRows.apply]: a row that two
 * calendars Proton still lists map to is never primary, a row that is not this account's is never written, and nothing
 * moves on a failure ([targetForPick], [planned]). Unlike the sync's own decision, a calendar that has no row on this
 * phone yet, or whose mapping names a row that is not one of the account's, leaves the rows as they are.
 */
internal object PrimaryCalendarPicks {

    private const val TAG = "SyncSettings"

    /**
     * A decision applied from the Default calendar row: a change Proton confirmed (reason
     * [PrimaryCalendarChooser.Reason.PICKED_HERE]) or the row's fresh read of Proton (the chooser's reason). [reason]
     * is the Reason's name, as [PrimaryCalendarMemory.Last.reason] stores it; [name] the plain calendar name.
     */
    data class Pick(val generation: Long, val protonCalendarId: String, val name: String, val reason: String)

    private var counter = 0L
    private val lastPicks = HashMap<String, Pick>()
    private val locks = HashMap<String, Any>()

    /** One monitor per account, for every write of its primary rows and its record outside the calendar loop. */
    @Synchronized
    fun lock(accountName: String): Any = locks.getOrPut(accountName) { Any() }

    /** The generation of [accountName]'s last pick, 0 without one. A sync reads it before its settings GET. */
    @Synchronized
    fun generation(accountName: String): Long = lastPicks[accountName]?.generation ?: 0L

    /** [accountName]'s last pick when it is newer than [generation] (what [generation] gave earlier), else null. */
    @Synchronized
    fun since(accountName: String, generation: Long): Pick? = lastPicks[accountName]?.takeIf { it.generation > generation }

    /** Notes a pick of [accountName], newer than every pick before it. Call it only while holding [lock] of [accountName]. */
    @Synchronized
    fun record(accountName: String, protonCalendarId: String, name: String, reason: String): Pick {
        counter += 1
        return Pick(counter, protonCalendarId, name, reason).also { lastPicks[accountName] = it }
    }

    /**
     * What a pick does to the rows, from where its calendar sits ([PrimaryCalendarRows.locate]):
     *  - its own row: that row stores 1 and the account's other rows 0;
     *  - a row that also holds another calendar Proton still lists: no row is primary, as the sync decides for such a
     *    row ([PrimaryCalendarRows.targetFor]), since a new event in it may go up to the other calendar;
     *  - no row on this phone yet, or mappings that could not be read: nothing is written, so a calendar not on the
     *    phone yet never clears every row, and the phone keeps its main calendar until the next calendar sync brings
     *    the new one down and marks it.
     */
    fun targetForPick(chosen: ChosenRow): Target = when (chosen) {
        is ChosenRow.Unreadable -> Target.Leave
        is ChosenRow.Found -> when {
            chosen.androidCalId == null -> Target.Leave
            chosen.sharedWith > 0 -> Target.Primary(null)
            else -> Target.Primary(chosen.androidCalId)
        }
    }

    /**
     * What the settle pass after a sync's calendar loop does. [target] is what the rows are brought to, [chosen] where
     * the kept calendar sits (null when none is chosen), [last] the record to keep, its row before the write confirms
     * it, and [keptPick] whether a pick newer than the run's settings read was kept over the run's own decision.
     * [followsRun]: the kept pick's calendar has no row on this phone yet, so [target] is the run's own decision, which
     * the calendar loop has already written on the rows it touched, and [last] names no row.
     */
    data class Settle(
        val target: Target,
        val chosen: ChosenRow?,
        val last: PrimaryCalendarMemory.Last,
        val keptPick: Boolean,
        val followsRun: Boolean = false,
    )

    /**
     * The settle pass's plan, pure. A newer [pick] wins, also over an Unknown decision: the target is [targetForPick]'s
     * and the record the pick's own, with the row this sync found. A kept pick whose calendar has no row on this phone
     * yet cannot be marked; when the run's own decision marks a row ([runRowTarget]), the rows follow that decision
     * instead of being left as they are, so the clean-up still reaches the rows the calendar loop did not touch, and
     * only the row the loop marked stores 1. Without a pick, a Known [decision] is settled by
     * [PrimaryCalendarRows.targetFor], named [decisionName]; an Unknown one gives null, and nothing is written.
     * [locate] says where a calendar sits on the phone.
     */
    fun settle(
        decision: PrimaryCalendarChooser.Decision,
        decisionName: String?,
        pick: Pick?,
        locate: (String) -> ChosenRow,
    ): Settle? {
        if (pick != null) {
            val chosen = locate(pick.protonCalendarId)
            val own = targetForPick(chosen)
            val notOnPhone = chosen is ChosenRow.Found && chosen.androidCalId == null
            val run = if (notOnPhone) runRowTarget(decision, locate) else null
            return Settle(
                run ?: own, chosen, PrimaryCalendarMemory.Last(pick.protonCalendarId, rowOf(own), pick.reason, pick.name),
                keptPick = true, followsRun = run != null,
            )
        }
        val known = decision as? PrimaryCalendarChooser.Decision.Known ?: return null
        val chosen = known.protonCalendarId?.let(locate)
        val target = PrimaryCalendarRows.targetFor(known, chosen)
        return Settle(
            target, chosen, PrimaryCalendarMemory.Last(known.protonCalendarId, rowOf(target), known.reason.name, decisionName),
            keptPick = false,
        )
    }

    /**
     * The run's own target when its Known [decision] marks a row ([PrimaryCalendarRows.targetFor]), else null. Whether
     * that row is one of the account's shows only when the rows are written: when it is not, PrimaryCalendarRows.apply
     * writes no 1 and no row is primary, as for the run without the pick (CalendarSyncAdapter.writeSettled logs it).
     */
    private fun runRowTarget(decision: PrimaryCalendarChooser.Decision, locate: (String) -> ChosenRow): Target.Primary? {
        val known = decision as? PrimaryCalendarChooser.Decision.Known ?: return null
        val id = known.protonCalendarId ?: return null
        return (PrimaryCalendarRows.targetFor(known, locate(id)) as? Target.Primary)?.takeIf { it.androidCalId != null }
    }

    private fun rowOf(target: Target): Long? = (target as? Target.Primary)?.androidCalId

    /** Why a decision applied by [applyOutsideSync] left no row storing 1 for its calendar. */
    enum class NoRow {
        /** Its calendar has no row on this phone yet: the rows were left as they were. */
        NOT_ON_PHONE,

        /** Its row also holds another calendar Proton still lists: no row is primary. */
        SHARED_ROW,

        /** Its mapping names a row that is not one of this account's calendars: no row was written. */
        NOT_THE_ACCOUNTS,

        /** This account's calendar mappings could not be read: the rows were left as they were. */
        MAPPINGS_UNREADABLE,

        /** Without the calendar permission no row is read or written. */
        NO_PERMISSION,

        /** The calendar provider gave no rows, or a write failed. */
        WRITE_FAILED,
    }

    /** What [applyOutsideSync] did: the writes made, the row that stores 1 now ([rowId]), or why none does ([noRow]). */
    data class Applied(val writes: List<PrimaryCalendarRows.Write>, val rowId: Long?, val noRow: NoRow?)

    /** [applied] for a log line. */
    fun describe(applied: Applied): String {
        val cleared = applied.writes.count { it.value == 0 }
        return when (applied.noRow) {
            null -> "row ${applied.rowId} primary, $cleared other row(s) cleared"
            NoRow.NOT_ON_PHONE -> "calendar not on this phone yet, rows unchanged"
            NoRow.SHARED_ROW -> "its row also holds another calendar on Proton, so no row is primary ($cleared row(s) cleared)"
            // Writes only when the rows changed between the check and the write, and then they are 0s.
            NoRow.NOT_THE_ACCOUNTS -> "its mapping names a row that is not one of this account's calendars, " +
                if (cleared > 0) "$cleared row(s) cleared" else "rows unchanged"
            NoRow.MAPPINGS_UNREADABLE -> "this account's calendar mappings could not be read, rows unchanged"
            NoRow.NO_PERMISSION -> "no calendar permission, rows unchanged"
            NoRow.WRITE_FAILED -> "the calendar rows could not be written; the next calendar sync sets them"
        }
    }

    /**
     * What [applyOutsideSync] does for a decision whose calendar sits at [chosen], with the calendar permission
     * [permitted], when [accountName]'s rows store [rows] (null: they could not be read). Pure: [applyOutsideSync]
     * follows it, and the Default calendar row's load compares the phone with it ([needsBringInLine]).
     *  - no row to mark ([targetForPick] leaves the rows): nothing is written, NOT_ON_PHONE or MAPPINGS_UNREADABLE;
     *  - no permission, or rows that could not be read: nothing is written;
     *  - a chosen row that is not one of the account's: nothing is written, NOT_THE_ACCOUNTS;
     *  - otherwise the writes [PrimaryCalendarRows.plan] gives, and the chosen row stores 1, or none does (SHARED_ROW).
     */
    fun planned(
        accountName: String,
        accountType: String,
        chosen: ChosenRow,
        permitted: Boolean,
        rows: List<PrimaryCalendarRows.StoredRow>?,
    ): Applied {
        val target = targetForPick(chosen)
        return when {
            target !is Target.Primary ->
                Applied(emptyList(), null, if (chosen is ChosenRow.Unreadable) NoRow.MAPPINGS_UNREADABLE else NoRow.NOT_ON_PHONE)
            !permitted -> Applied(emptyList(), null, NoRow.NO_PERMISSION)
            rows == null -> Applied(emptyList(), null, NoRow.WRITE_FAILED)
            !PrimaryCalendarRows.chosenRowFound(accountName, accountType, target, rows) ->
                Applied(emptyList(), null, NoRow.NOT_THE_ACCOUNTS)
            else -> written(
                target,
                PrimaryCalendarRows.Applied(PrimaryCalendarRows.plan(accountName, accountType, target, rows), chosenRowFound = true),
            )
        }
    }

    /** [target] as [PrimaryCalendarRows.apply] left the rows: [applied], or null when it could not read them. */
    fun written(target: Target.Primary, applied: PrimaryCalendarRows.Applied?): Applied = when {
        applied == null -> Applied(emptyList(), null, NoRow.WRITE_FAILED)
        target.androidCalId == null -> Applied(applied.writes, null, NoRow.SHARED_ROW)
        !applied.chosenRowFound -> Applied(applied.writes, null, NoRow.NOT_THE_ACCOUNTS)
        else -> Applied(applied.writes, target.androidCalId, null)
    }

    /**
     * Whether the Default calendar row's load has to bring the phone in line with Proton's default [checkedId]: the
     * phone's [record] names no calendar or another one, or it names [checkedId] while [wouldDo] (what
     * [applyOutsideSync] would do now, [planned] for the rows as stored) keeps another row in the record or still has
     * rows to write, such as a second row that stores 1. [wouldDo] null (no calendar permission, or the rows could not
     * be read): only the calendar is compared, so a failed read moves nothing. A bring-in-line that cannot mark a row
     * (no row yet, a row that is not the account's, no permission) stores the record without one, which then matches,
     * so it is never repeated at every opening.
     */
    fun needsBringInLine(record: PrimaryCalendarMemory.Last?, checkedId: String, wouldDo: Applied?): Boolean = when {
        record == null || record.protonCalendarId != checkedId -> true
        wouldDo == null -> false
        else -> record.androidCalId != wouldDo.rowId || wouldDo.writes.isNotEmpty()
    }

    /**
     * [planned] for [account]'s rows as they are stored now, read without writing anything; null without the calendar
     * permission or when they could not be read. On a background thread; never throws.
     */
    fun wouldApply(context: Context, account: Account, chosen: ChosenRow): Applied? {
        if (targetForPick(chosen) !is Target.Primary) return planned(account.name, account.type, chosen, permitted = true, rows = null)
        if (!hasCalendarPermission(context)) return null
        var client: ContentProviderClient? = null
        return try {
            client = context.contentResolver.acquireContentProviderClient(CalendarContract.AUTHORITY)
            val rows = client?.let { PrimaryCalendarRows.read(it, account) }
            if (rows == null) null else planned(account.name, account.type, chosen, permitted = true, rows = rows)
        } catch (e: Exception) {
            SyncLog.w(TAG, "Default calendar for '${account.name}': the calendar rows could not be read (${e.javaClass.simpleName})")
            null
        } finally {
            client?.close()
        }
    }

    /**
     * Applies a decision made on the Default calendar row: notes it as a pick ([record]), brings [account]'s rows to
     * [targetForPick] of [chosen] as [planned] says (only with the calendar permission, only rows of the account, and
     * only when the chosen row is one of them, through PrimaryCalendarRows and the sync-adapter URI, which marks
     * nothing dirty and asks for no sync), and stores the record with the row that now stores 1, or none. The record
     * is stored also when no row was written, so the card shows what Proton has. On a background thread; never
     * suspends and never throws.
     */
    fun applyOutsideSync(
        context: Context,
        account: Account,
        protonCalendarId: String,
        name: String,
        reason: String,
        chosen: ChosenRow,
    ): Applied {
        val target = targetForPick(chosen)
        val permitted = hasCalendarPermission(context)
        var client: ContentProviderClient? = null
        return try {
            if (target is Target.Primary && permitted) {
                client = context.contentResolver.acquireContentProviderClient(CalendarContract.AUTHORITY)
            }
            val provider = client
            synchronized(lock(account.name)) {
                record(account.name, protonCalendarId, name, reason)
                val applied = when {
                    target !is Target.Primary || !permitted -> planned(account.name, account.type, chosen, permitted, rows = null)
                    provider == null -> Applied(emptyList(), null, NoRow.WRITE_FAILED)
                    else -> writeRows(provider, account, chosen, target)
                }
                storeRecord(context, account.name, PrimaryCalendarMemory.Last(protonCalendarId, applied.rowId, reason, name))
                applied
            }
        } catch (e: Exception) {
            SyncLog.w(TAG, "Default calendar for '${account.name}': the phone could not be brought in line (${e.javaClass.simpleName})")
            Applied(emptyList(), null, NoRow.WRITE_FAILED)
        } finally {
            client?.close()
        }
    }

    /**
     * [target] written to [account]'s rows as [planned] says: nothing when they cannot be read or the chosen row is not
     * one of them, else through PrimaryCalendarRows, which reads them again and writes only what differs. Called under
     * [lock].
     */
    private fun writeRows(provider: ContentProviderClient, account: Account, chosen: ChosenRow, target: Target.Primary): Applied = try {
        val rows = PrimaryCalendarRows.read(provider, account)
        val plan = planned(account.name, account.type, chosen, permitted = true, rows = rows)
        if (rows == null || plan.noRow == NoRow.NOT_THE_ACCOUNTS) plan
        else written(target, PrimaryCalendarRows.apply(provider, account, target))
    } catch (e: Exception) {
        SyncLog.w(TAG, "Default calendar for '${account.name}': the calendar rows could not be written (${e.javaClass.simpleName})")
        Applied(emptyList(), null, NoRow.WRITE_FAILED)
    }

    private fun hasCalendarPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /** Stores [last] as [accountName]'s record when it differs. Called under [lock]. */
    private fun storeRecord(context: Context, accountName: String, last: PrimaryCalendarMemory.Last) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val key = SyncPreferences.primaryCalendarLastKey(accountName)
        val encoded = PrimaryCalendarMemory.encode(last)
        if (prefs.getString(key, null) != encoded) prefs.edit().putString(key, encoded).apply()
    }
}

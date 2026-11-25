package me.proton.sync.calendar

import me.proton.sync.SyncConstants
import me.proton.sync.api.models.CalendarMember

/**
 * Which new events the upload holds back because an app that is not a calendar app wrote them into a calendar
 * someone else shared with this account, and what becomes of the ones already held. Pure, so every rule is tested
 * without a phone: [NewEventHoldStore] keeps the entries, NewEventHolds posts the prompts, NewEventHoldActions applies
 * the user's answer and CalendarSyncAdapter.readDirtyRows runs [plan] before any upload.
 *
 * A row is held when all of these hold:
 *  - it is NEW: not deleted, no _SYNC_ID, no ORIGINAL_SYNC_ID, no ORIGINAL_ID, no mapping ([isCandidate]);
 *  - it is DIRTY (the upload reads only dirty and deleted rows);
 *  - its calendar's [holdDecision] is [HoldDecision.HOLD]: a personal (Type 0) calendar on which the account has
 *    its own member, that member is not the owner, and it may write;
 *  - the FIRST entry of its MUTATORS is not a calendar editor ([verdict]).
 *
 * Why the first entry: AOSP's CalendarProvider2 stamps the calling package on an event insert and REPLACES the value
 * on an event update, and on an update or delete of one of the event's attendees, reminders or extended properties;
 * an insert of such a child row APPENDS the package when it is not listed yet (setEventDirty). A sync adapter that
 * marks the row clean sets the column back to NULL, and only a sync adapter may write the column itself. So the first
 * entry is the app that last wrote the event row, and an app that only added a reminder later never decides.
 *
 * Held rows keep DIRTY = 1, so every upload run reads them again, and their entry decides: a series waiting for the
 * user's choice ([State.WAITING]) or kept on this phone ([State.KEPT]) stays out of the upload, and a published one
 * ([State.PUBLISHED]) goes up WITHOUT a new verdict, because its MUTATORS still names the app that wrote it and a new
 * verdict would hold it again (the trap EventConflictGuard's "Keep mine" marker exists for). One entry per series,
 * keyed by its parent row: the series' exceptions follow it through ORIGINAL_ID, a row with the series' non-blank
 * UID and no ORIGINAL_ID (AOSP's "this and following" split clones the row, UID included) follows it too, and so do
 * that row's own exceptions. When the parent row is deleted or moved away, the entry passes to the oldest such row
 * that is left ([mayPassOn], [passedOn]), so the rest of the series never goes up unasked.
 */
internal object NewEventHoldRules {

    /** What the user made of a held series. [code] is its letter in [NewEventHoldStore]. */
    enum class State(val code: Char) {
        /** Waiting for the user's choice: nothing goes to Proton. */
        WAITING('W'),

        /** Kept on this phone: nothing ever goes to Proton. */
        KEPT('K'),

        /** Published: goes to the shared calendar at the next upload, without a new verdict, until it is uploaded. */
        PUBLISHED('P');

        companion object {
            fun of(code: Char): State? = entries.firstOrNull { it.code == code }
        }
    }

    /**
     * One held series, keyed by its parent row [eventId] (the row with ORIGINAL_ID null) in Android calendar
     * [calendarId]. [writer] is the sanitized first MUTATORS entry, null when Android recorded none; [uid] the
     * parent's non-blank UID_2445, null when it had none.
     */
    data class Entry(
        val state: State,
        val eventId: Long,
        val calendarId: Long,
        val heldAtMillis: Long,
        val writer: String?,
        val uid: String?,
    )

    /** What a calendar means for a new event in it. */
    enum class HoldDecision {
        /** Shared with this account, with the right to write: a new event from an app that is not a calendar app waits. */
        HOLD,

        /** The account's own calendar, a view-only share, or not a personal calendar: the upload goes ahead as before. */
        NO_HOLD,

        /** No member of the calendar is one of the account's loaded addresses: new events wait for the next run, unasked. */
        UNDECIDED,
    }

    /**
     * What a new event in a calendar of Proton [type] with [members] means, for an account whose addresses with
     * unlocked keys are [userAddresses] (lowercase, as KeyManager.getAddressEmails gives them). Our member and the
     * owner test go through [ownMember] and [isCalendarOwner], by address, never by the account name: a calendar shared
     * with an alias of the account lists our member under that alias.
     *  - not Type 0 (subscribed, holiday): [HoldDecision.NO_HOLD], Proton refuses the write anyway;
     *  - no member of ours: [HoldDecision.UNDECIDED];
     *  - our member owns it: [HoldDecision.NO_HOLD], the account's own calendar, also one it shares out to others;
     *  - our member may write ([writableByPermissions]: the WRITE bit, or a Permissions field that was absent, which the
     *    upload treats as writable too): [HoldDecision.HOLD];
     *  - anything else, a view-only share: [HoldDecision.NO_HOLD]; Proton refuses the write, and the upload's refusal
     *    path keeps the event on the phone and says so.
     */
    fun holdDecision(type: Int, members: List<CalendarMember>?, userAddresses: Set<String>): HoldDecision {
        if (type != 0) return HoldDecision.NO_HOLD
        val me = ownMember(members, userAddresses) ?: return HoldDecision.UNDECIDED
        if (isCalendarOwner(members, userAddresses)) return HoldDecision.NO_HOLD
        return if (writableByPermissions(me.permissions)) HoldDecision.HOLD else HoldDecision.NO_HOLD
    }

    /**
     * Whether a calendar of Proton [type] with [members] is one someone else shared with this account, whatever this
     * account may do in it: Type 0, our own member found by [userAddresses] ([ownMember]), and that member not the owner
     * ([isCalendarOwner]). Every calendar that holds ([holdDecision]) is one, and so is a view-only share. For [plan]: a
     * parent row that never reached Proton keeps its changed and cancelled dates in other calendars on the phone while it
     * sits in one ([apartFromSharedParent]).
     */
    fun sharedWithAccount(type: Int, members: List<CalendarMember>?, userAddresses: Set<String>): Boolean =
        type == 0 && ownMember(members, userAddresses) != null && !isCalendarOwner(members, userAddresses)

    /**
     * The decision for an Android row that several mapped Proton calendars name (an older version's reuse by name could
     * merge two): the stricter one, HOLD over UNDECIDED over NO_HOLD. The upload takes the first mapping of the row,
     * which need not be the one read last, so a row is never judged by a calendar its event may not go to.
     */
    fun stricter(a: HoldDecision, b: HoldDecision): HoldDecision = when {
        a == HoldDecision.HOLD || b == HoldDecision.HOLD -> HoldDecision.HOLD
        a == HoldDecision.UNDECIDED || b == HoldDecision.UNDECIDED -> HoldDecision.UNDECIDED
        else -> HoldDecision.NO_HOLD
    }

    /** What the upload does with a new row in a calendar that holds. */
    sealed interface Verdict {
        /** Written in a calendar app's editor: the upload goes ahead. */
        data object Upload : Verdict

        /** Held; [writer] is the sanitized first MUTATORS entry, null when Android recorded no writer. */
        data class Hold(val writer: String?) : Verdict
    }

    /**
     * The verdict on a new row from its MUTATORS ([mutators]; [mutatorsReadable] false when the provider refused the
     * column). Only the FIRST entry counts. No writer recorded (the column unreadable, empty or not text, or this app,
     * [ownPackage], first) holds, with no name: if Android did not record which app wrote an event, the user is asked.
     * A first entry [isEditor] accepts uploads; any other holds, named after that entry.
     */
    fun verdict(mutators: String?, mutatorsReadable: Boolean, ownPackage: String, isEditor: (String) -> Boolean): Verdict {
        if (!mutatorsReadable) return Verdict.Hold(null)
        val first = LocalEventOrigin.entries(mutators).firstOrNull() ?: return Verdict.Hold(null)
        if (first == ownPackage) return Verdict.Hold(null)
        if (isEditor(first)) return Verdict.Upload
        return Verdict.Hold(LocalEventOrigin.sanitizeEntry(first))
    }

    /**
     * The uid in a MUTATORS entry the provider wrote for a caller without a package of its own: "name:uid" for a uid
     * that several packages share ("android.uid.system:1000"), or the bare uid. Null for a package name.
     */
    fun uidOf(entry: String): Int? {
        val tail = entry.substringAfterLast(':')
        if (tail.isEmpty() || !tail.all { it in '0'..'9' }) return null
        return tail.toIntOrNull()
    }

    /** One dirty or deleted row as the upload read it. */
    data class Row(
        val id: Long,
        val calendarId: Long,
        val deleted: Boolean,
        val syncId: String?,
        val originalId: Long?,
        val originalSyncId: String?,
        val uid: String?,
        val mutators: String?,
        val mutatorsReadable: Boolean,
    )

    /**
     * What one upload run does with its rows: [upload] goes on to the upload as before, [skipped] stays out of it
     * (waiting, kept, or new holds and their rows, exceptions in other calendars included), [undecided] stays out of it
     * unasked, [newHolds] are the entries to store, [dropEntries] the ones whose question ends with this run (their
     * parent's tombstone goes up; the caller passes the question on to a row of the series left with its UID, if any),
     * and [apart] stays out of it too: exceptions made on this phone, in a calendar that holds, whose parent row sits in
     * another calendar, exceptions of an undecided series' rows that sit in another calendar (until the series' calendar
     * is decided), exceptions of the rows of a new series in a calendar without a decision that sit in another calendar,
     * exceptions of a published series' rows that sit in another calendar than those, exceptions made on this phone
     * whose parent lives on Proton in another calendar ([livesApart]), and the live exceptions of a parent row that
     * never reached Proton and sits in a calendar shared with this account, or in one this account cannot judge, in
     * every other calendar ([apartFromSharedParent]; see [plan]).
     */
    data class Plan(
        val upload: List<Row>,
        val skipped: List<Row>,
        val undecided: List<Row>,
        val newHolds: List<Entry>,
        val dropEntries: List<Entry>,
        val apart: List<Row> = emptyList(),
    )

    /** A row that may be a new event: not deleted, nothing of Proton's on it, no parent, no mapping. */
    fun isCandidate(row: Row, mappedIds: Set<Long>): Boolean =
        !row.deleted && row.syncId.isNullOrBlank() && row.originalSyncId.isNullOrBlank() && row.originalId == null &&
            row.id !in mappedIds

    /**
     * Whether where [row]'s parent row sits decides its upload ([Plan.apart]): a live exception that carries nothing of
     * Proton's ([phoneOnly], with this account's [mappedIds]), in a calendar that holds or is undecided ([decisions]).
     * The provider copies a synced parent's _SYNC_ID into an exception's ORIGINAL_SYNC_ID, so such an exception's parent
     * never reached Proton, and its upload in this calendar, a cancelled one as its whole parent series, would create
     * that parent here. An exception of one of Proton's events changes an event that lives in this calendar on Proton
     * already, and goes up as before, unless its parent lives on Proton in another calendar ([livesApart], which also
     * keeps this rule's exceptions out once their parent went up from where it sits); a tombstone that carries nothing of
     * Proton's is only removed on the phone.
     */
    fun needsParentCalendar(row: Row, decisions: Map<Long, HoldDecision>, mappedIds: Set<Long>): Boolean =
        row.originalId != null && !row.deleted && phoneOnly(row, mappedIds) &&
            decisions[row.calendarId].let { it == HoldDecision.HOLD || it == HoldDecision.UNDECIDED }

    /**
     * Whether [row] is a live exception (a changed or cancelled date) whose parent row never reached Proton, as far as
     * the row and this account's [mappedIds] tell without that parent: the parent has no mapping, and the row's
     * ORIGINAL_SYNC_ID is empty (the provider copies a synced parent's _SYNC_ID into it). A Proton event of the row's
     * own does not count: a cancelled date goes up as its whole parent series, read by id.
     */
    fun ofPhoneOnlyParent(row: Row, mappedIds: Set<Long>): Boolean {
        val parent = row.originalId ?: return false
        return !row.deleted && parent !in mappedIds && row.originalSyncId.isNullOrBlank()
    }

    /**
     * Whether [plan] needs where [row]'s parent row sits when that parent is not among the run's rows, which
     * CalendarSyncAdapter.parentCalendarsOf then reads for it: for [needsParentCalendar], and for every live exception
     * whose parent never reached Proton ([ofPhoneOnlyParent]), wherever it sits, for [apartFromSharedParent].
     */
    fun parentCalendarCounts(row: Row, decisions: Map<Long, HoldDecision>, mappedIds: Set<Long>): Boolean =
        needsParentCalendar(row, decisions, mappedIds) || ofPhoneOnlyParent(row, mappedIds)

    /**
     * The calendar [plan]'s parentCalendars gives a parent row that could not be read, or that sits in a calendar of
     * another account of this app ([parentCalendar]): no calendar has this id.
     */
    const val UNREAD_CALENDAR = -1L

    /**
     * What [plan]'s parentCalendars says for a parent row read in Android calendar [calendarId] of the account
     * [accountName] of type [accountType], for this account [ownName] of type [ownType]. In another account of this
     * app, [UNREAD_CALENDAR]: only that account's own sync reads the calendar's members, so whether someone shared it
     * with that account is not known here, and the dates in this account's calendars wait as for a parent not read
     * (owner, 2026-11-22, decision L: wherever the dates are). That also holds while the calendar is the other
     * account's own, until its upload sends the parent: the provider then copies the parent's _SYNC_ID into the
     * dates' ORIGINAL_SYNC_ID, and [ofPhoneOnlyParent] no longer asks. Else [calendarId]: a calendar of this account
     * ([inAccount]), which [apartFromSharedParent] judges by its decision this run, or of another app, which is not this
     * rule's.
     */
    fun parentCalendar(calendarId: Long, accountName: String?, accountType: String?, ownName: String, ownType: String): Long =
        if (accountType == ownType && accountName != ownName) UNREAD_CALENDAR else calendarId

    /**
     * Whether a parent row read in a calendar of the account [accountName] of type [accountType] sits in a calendar of
     * this account [ownName] of type [ownType]: for [plan]'s accountCalendars (CalendarSyncAdapter.parentCalendarsOf
     * reads each parent row's account), so that one of this account's calendars without a decision this run keeps the
     * dates of a parent that never reached Proton out ([apartFromSharedParent]), and another app's calendar does not.
     */
    fun inAccount(accountName: String?, accountType: String?, ownName: String, ownType: String): Boolean =
        accountType == ownType && accountName == ownName

    /**
     * The upload's plan for [rows], given the stored [entries] (taken BEFORE the rows were read), each mapped calendar's
     * [decisions] (by Android calendar id, from this run's list; a calendar without one uploads as before, but for the
     * dates of its new series in other calendars), this account's [mappedIds] (Android event ids with a mapping),
     * [verdictOf] for a candidate in a calendar that holds, and the time [nowMillis].
     * [parentCalendars] is the calendar of each exception's parent row that is not among [rows] ([parentCalendarCounts];
     * by row id; a parent that is gone is missing, one that could not be read, or that sits in another account of this
     * app ([parentCalendar]), has [UNREAD_CALENDAR]), [parentHomes] the Android calendars each mapped parent lives in on
     * Proton ([homesOf]; a parent without a mapping is missing), [sharedCalendars] the calendars someone else shared
     * with this account, with or without the right to write ([sharedWithAccount], by this run's list), and
     * [accountCalendars] those of [parentCalendars]' calendars that are this account's ([inAccount]).
     *
     * A row's series ([seriesIndex]): the entry of its own id; else, for an exception, the entry of its parent row,
     * which is an entry's own row or a row that follows one by UID; else, for a row that holds nothing of Proton's, the
     * entry with the same non-blank UID (compared after SyncConstants.MAX_UID_LENGTH characters, as the upload cuts a
     * UID). Only entries of the row's own calendar count: a row moved elsewhere is judged where it is now.
     *  - With a series: the parent's tombstone uploads (an unmapped one is only removed on the phone) and its entry
     *    goes; a waiting or kept series' rows stay out; a published series' rows upload without a verdict.
     *  - Without one, a candidate ([isCandidate]) goes by its calendar's decision: HOLD takes [verdictOf], Upload
     *    uploading and Hold becoming a new waiting entry; UNDECIDED stays out unasked; NO_HOLD or none uploads.
     *  - An exception made on this phone, in a calendar that holds (or is undecided), whose parent row sits in another
     *    calendar stays out ([Plan.apart], [needsParentCalendar]): it would go up in its own calendar, a cancelled one
     *    as its whole parent series, which never reached Proton, and the parent's calendar is not where it was written.
     *    An app moved only the parent there; the provider never moves an event's exceptions along. An exception of one
     *    of Proton's events goes up as before, wherever its parent row sits, but for [livesApart] below.
     *  - Every other row uploads.
     * In the same run, a new hold's rows follow it, and get no entry of their own ([followersOf]): the rows with its
     * non-blank UID and no ORIGINAL_ID, and the exceptions of any held or following row; the first held row of a UID
     * carries the entry. The rows of an undecided candidate's series stay out with it the same way, and so do their
     * exceptions in every other calendar, the user's own and unmapped ones included ([Plan.apart]; owner, 2026-11-22):
     * an app that moved only the parent into that calendar left them where they were, and their upload there, a
     * cancelled one as the whole parent series (CalendarSyncAdapter.uploadCancelledOccurrenceParent reads the parent by
     * id and sends it in the exception's calendar), would put the series on Proton in that calendar before its own
     * calendar is decided, and it would never be asked about. Once that calendar is decided, the series and its
     * exceptions are judged as usual. A candidate in a calendar without a decision (this run's list lacks it, or it has
     * no mapping, as when Proton removed an undecided calendar before a run decided it, or the phone keeps it after
     * that) goes to the upload as before, which has no context for it and leaves it dirty, and the exceptions of its
     * series' rows in every other calendar stay out the same way ([Plan.apart]) for as long as it sits there: the series
     * can go nowhere from where it sits, and those dates would put it on Proton elsewhere.
     *
     * Then an exception whose parent row is one of a waiting or kept series (stored, or held in this run) stays out with
     * that series whatever calendar it sits in, the user's own ones included ([Plan.skipped]): an app that moved only
     * the parent into the shared calendar left it where it was, and its upload there, a cancelled one as the whole
     * parent series, would put the held series on Proton. An exception of a published series' row that sits in another
     * calendar than that row stays out too ([Plan.apart]): the series goes up in its own calendar, not a second time
     * where the exception sits. That pass covers only the run that uploads the parent, while the entry is stored and the
     * parent is among the rows, and [needsParentCalendar] only an exception that still carries nothing of Proton's. So,
     * in every run, an exception made on this phone whose parent lives on Proton in another calendar stays out
     * ([livesApart], [Plan.apart]): once the parent went up (its entry gone, its row clean, its _SYNC_ID copied into the
     * exception), the upload where the exception sits would still put a changed date in a second calendar, or send a
     * cancelled one's parent update to a calendar that event is not in. And last, in every run, while a parent row that
     * never reached Proton sits in a calendar shared with this account ([sharedCalendars], or one that holds or is
     * undecided), or in one this account cannot judge ([UNREAD_CALENDAR]: not read, or of another account of this app;
     * or a calendar of this account without a decision this run, [accountCalendars]: Proton no longer lists it, it has
     * no mapping, or the phone keeps it for a removed calendar), its live exceptions in every other calendar stay out
     * ([apartFromSharedParent], [Plan.apart]; owner, 2026-11-22), whatever app wrote it and whatever became of it: a
     * calendar app's editor wrote it, so it was never held, or Proton refused it for good after a Publish, the upload
     * cleared its DIRTY and the sweep dropped its entry, also once its owner stopped sharing that calendar.
     */
    fun plan(
        rows: List<Row>,
        entries: List<Entry>,
        decisions: Map<Long, HoldDecision>,
        mappedIds: Set<Long>,
        verdictOf: (Row) -> Verdict,
        nowMillis: Long,
        parentCalendars: Map<Long, Long> = emptyMap(),
        parentHomes: Map<Long, Set<Long>> = emptyMap(),
        sharedCalendars: Set<Long> = emptySet(),
        accountCalendars: Set<Long> = emptySet(),
    ): Plan {
        val seriesOf = seriesIndex(rows, entries, mappedIds)
        val calendarOfRow = rows.associate { it.id to it.calendarId }
        val upload = ArrayList<Row>()
        val skipped = ArrayList<Row>()
        val undecided = ArrayList<Row>()
        val apart = ArrayList<Row>()
        val drop = LinkedHashSet<Entry>()
        val held = ArrayList<Pair<Row, String?>>()
        val nowhere = ArrayList<Row>()
        val published = HashSet<Long>()
        for (row in rows) {
            val series = seriesOf(row)
            if (series != null) {
                when {
                    row.id == series.eventId && row.deleted -> {
                        upload.add(row)
                        drop.add(series)
                    }
                    series.state == State.PUBLISHED -> {
                        upload.add(row)
                        published.add(row.id)
                    }
                    else -> skipped.add(row)
                }
                continue
            }
            if (!isCandidate(row, mappedIds)) {
                val parentCalendar = row.originalId?.let { calendarOfRow[it] ?: parentCalendars[it] }
                val moved = parentCalendar != null && parentCalendar != row.calendarId
                if (moved && needsParentCalendar(row, decisions, mappedIds)) apart.add(row) else upload.add(row)
                continue
            }
            when (decisions[row.calendarId]) {
                HoldDecision.HOLD -> when (val v = verdictOf(row)) {
                    Verdict.Upload -> upload.add(row)
                    is Verdict.Hold -> held.add(row to v.writer)
                }
                HoldDecision.UNDECIDED -> undecided.add(row)
                HoldDecision.NO_HOLD -> upload.add(row)
                // No decision: the upload has no context for this calendar, so the row stays on the phone, dirty.
                null -> {
                    upload.add(row)
                    nowhere.add(row)
                }
            }
        }

        // The first held row of each (calendar, UID) carries the new entry; a later one with the same UID follows it.
        val newHolds = ArrayList<Entry>()
        val newByUid = HashSet<Pair<Long, String>>()
        for ((row, writer) in held) {
            skipped.add(row)
            val key = uidKey(row.uid)?.let { row.calendarId to it }
            if (key != null && !newByUid.add(key)) continue
            newHolds.add(Entry(State.WAITING, row.id, row.calendarId, nowMillis, writer, row.uid?.takeIf { it.isNotBlank() }))
        }
        moveFollowers(followersOf(held.map { it.first }, upload, mappedIds), upload, skipped)
        // An undecided series is decided as one at a later run: its exceptions and same-UID rows wait with it.
        moveFollowers(followersOf(undecided.toList(), upload, mappedIds), upload, undecided)
        // So do the exceptions of its rows in every other calendar, the user's own and unmapped ones included (owner,
        // 2026-11-22): going up there, a cancelled one as the whole series, they would put it on Proton unasked.
        val waitIds = undecided.mapTo(HashSet()) { it.id }
        moveFollowers(upload.filter { r -> r.originalId?.let { it in waitIds } == true }, upload, apart)
        // A new series in a calendar without a decision can go nowhere from there, and its dates in other calendars wait
        // the same way: Proton removed an undecided calendar before a run decided it, or the phone keeps it unmapped.
        val nowhereIds = (nowhere + followersOf(nowhere, upload, mappedIds)).mapTo(HashSet()) { it.id }
        moveFollowers(
            upload.filter { r -> r.originalId?.let { it in nowhereIds && calendarOfRow[it] != r.calendarId } == true },
            upload, apart
        )
        // An exception keeps to its series in whatever calendar it sits: out with a waiting or kept one, and apart from a
        // published one when it sits in another calendar than its parent row.
        val outIds = skipped.mapTo(HashSet()) { it.id }
        moveFollowers(upload.filter { r -> r.originalId?.let { it in outIds } == true }, upload, skipped)
        moveFollowers(
            upload.filter { r -> r.originalId?.let { it in published && calendarOfRow[it] != r.calendarId } == true },
            upload, apart
        )
        // Once the parent went up (its entry gone, its row clean), where it lives on Proton keeps such an exception apart.
        moveFollowers(upload.filter { livesApart(it, parentHomes, decisions, mappedIds) }, upload, apart)
        // Last: while a parent row that never reached Proton sits in a calendar shared with this account, or in one of its
        // calendars without a decision this run, its dates in every other calendar stay on the phone, whatever wrote it
        // and whatever became of it (owner, 2026-11-22).
        val rowOf = rows.associateBy { it.id }
        moveFollowers(
            upload.filter { apartFromSharedParent(it, rowOf, parentCalendars, decisions, sharedCalendars, mappedIds, accountCalendars) },
            upload, apart
        )
        return Plan(upload, skipped, undecided, newHolds, drop.toList(), apart)
    }

    /**
     * Whether [row] stays out ([Plan.apart]) because its parent lives on Proton in another calendar: a live exception
     * with no Proton event of its own (no _SYNC_ID, no mapping in [mappedIds]) whose parent row is mapped
     * ([parentHomes]: by parent row id, the Android calendars mapped to the Proton calendar of the parent's mapping),
     * in a calendar that is not one of those. Going up where it sits, a changed date would land in a second calendar,
     * and a cancelled one would send its parent's update to a calendar that event is not in. Only where a calendar that
     * holds or is undecided ([decisions]) is involved, the exception's own or one of the parent's: between the user's own
     * calendars an exception goes up as in 2.1.0. The parent's mapping, not where its row sits, decides: an exception of
     * a Proton series whose parent row an app moved alone goes up where the series lives, as before.
     */
    fun livesApart(row: Row, parentHomes: Map<Long, Set<Long>>, decisions: Map<Long, HoldDecision>, mappedIds: Set<Long>): Boolean {
        val homes = row.originalId?.let { parentHomes[it] } ?: return false
        if (row.deleted || !row.syncId.isNullOrBlank() || row.id in mappedIds || row.calendarId in homes) return false
        return holdsOrUndecided(decisions[row.calendarId]) || homes.any { holdsOrUndecided(decisions[it]) }
    }

    /**
     * Whether [row] stays out ([Plan.apart]) as a changed or cancelled date of a repeating event that never reached
     * Proton while that event's parent row sits in a calendar shared with this account, another than the row's (owner,
     * 2026-11-22): whatever app wrote the parent, and whatever became of it (held, published, refused by Proton for
     * good, its row clean again). Going up where it sits, a cancelled date as its whole parent series, read by id
     * (CalendarSyncAdapter.uploadCancelledOccurrenceParent), and a changed one as an occurrence of its own with the
     * series' UID, it would put the series, guests included, on Proton in a calendar it is not in. The same holds for a
     * date of a split's clone that follows the series there: that clone is the date's parent row.
     *
     * When the parent row is among the run's rows ([rowOf], by id), its own Proton ids and mapping tell whether it reached
     * Proton; else [parentCalendars] tells where it sits ([UNREAD_CALENDAR]: not read, or in another account of this app,
     * so it may sit in such a calendar; missing: gone) and [ofPhoneOnlyParent] whether it reached Proton. A calendar shared with this account is
     * one of [sharedCalendars] ([sharedWithAccount], with or without the right to write), or one that holds or is
     * undecided by [decisions] (an undecided one may be shared).
     *
     * A calendar of this account without a decision this run counts as one this account cannot judge, like a parent not
     * read (owner, 2026-11-22): Proton no longer lists it (its owner stopped sharing it, or the user left it, and its
     * removal from the phone is still to come or waits for the user's answer), it has no mapping, or the phone keeps it
     * for a removed calendar (CalendarRowClaims.keptFor). Whether someone shared it is not known from this run's list,
     * so after a Publish that Proton refused for good the dates stay on the phone also once the calendar is gone from
     * that list. [accountCalendars] says which calendars of [parentCalendars] are this account's ([inAccount]); a parent
     * among the run's rows sits in one by definition. The user's own calendars have a decision
     * ([HoldDecision.NO_HOLD]) while Proton lists them, so their dates go as before. Not this rule's: a tombstone, which
     * sends nothing that could put the series anywhere, and a date whose parent row sits in one of the user's own
     * calendars, or in a calendar of another app.
     */
    fun apartFromSharedParent(
        row: Row,
        rowOf: Map<Long, Row>,
        parentCalendars: Map<Long, Long>,
        decisions: Map<Long, HoldDecision>,
        sharedCalendars: Set<Long>,
        mappedIds: Set<Long>,
        accountCalendars: Set<Long> = emptySet(),
    ): Boolean {
        val parentId = row.originalId ?: return false
        if (row.deleted) return false
        val parent = rowOf[parentId]
        val calendar = parent?.calendarId ?: parentCalendars[parentId] ?: return false
        if (calendar == row.calendarId) return false
        val neverOnProton = if (parent != null) phoneOnly(parent, mappedIds) else ofPhoneOnlyParent(row, mappedIds)
        val unjudged = decisions[calendar] == null && (parent != null || calendar in accountCalendars)
        return neverOnProton &&
            (calendar == UNREAD_CALENDAR || unjudged || calendar in sharedCalendars || holdsOrUndecided(decisions[calendar]))
    }

    private fun holdsOrUndecided(d: HoldDecision?): Boolean = d == HoldDecision.HOLD || d == HoldDecision.UNDECIDED

    /**
     * [plan]'s parentHomes: by parent row id, the Android calendars that [calendarRows] (each calendar mapping's Proton
     * calendar id and Android row) map to one of the Proton calendars of the parent's mappings ([parentProtonCalendars]).
     * A parent whose Proton calendar no row maps to gets an empty set.
     */
    fun homesOf(parentProtonCalendars: Map<Long, Set<String>>, calendarRows: List<Pair<String, Long>>): Map<Long, Set<Long>> {
        if (parentProtonCalendars.isEmpty()) return emptyMap()
        val rowsOf = HashMap<String, MutableSet<Long>>()
        for ((protonId, androidId) in calendarRows) rowsOf.getOrPut(protonId) { HashSet() }.add(androidId)
        return parentProtonCalendars.mapValues { (_, ids) -> ids.flatMapTo(HashSet()) { rowsOf[it].orEmpty() } }
    }

    /**
     * Whether Move can take a series from [source] to [target] while live exceptions of its rows sit in
     * [exceptionCalendars]: only when each sits in one of the two. Move's closing assert refuses any other (an app that
     * moved only the parent left them where they were), so a prompt does not offer it then (NewEventHolds.prompts).
     */
    fun moveKeepsTogether(source: Long, target: Long, exceptionCalendars: Set<Long>): Boolean =
        exceptionCalendars.all { it == source || it == target }

    private fun moveFollowers(followers: List<Row>, from: MutableList<Row>, to: MutableList<Row>) {
        if (followers.isEmpty()) return
        val out = followers.toHashSet()
        from.removeAll { it in out }
        to.addAll(followers)
    }

    /**
     * The rows of [pool] that belong to the series of [roots] in this run: the rows with a root's non-blank UID and no
     * ORIGINAL_ID that carry nothing of Proton's (a "this and following" split's clone, a second insert of one booking),
     * the exceptions (ORIGINAL_ID) of a root or of such a row, and an exception that carries nothing of Proton's and a
     * root's UID (the provider copies the UID into an exception). Only rows of a root's own calendar.
     */
    private fun followersOf(roots: List<Row>, pool: List<Row>, mappedIds: Set<Long>): List<Row> {
        if (roots.isEmpty() || pool.isEmpty()) return emptyList()
        val uids = roots.mapNotNullTo(HashSet()) { r -> uidKey(r.uid)?.let { r.calendarId to it } }
        fun sameUid(r: Row) = phoneOnly(r, mappedIds) && uidKey(r.uid)?.let { (r.calendarId to it) in uids } == true
        val parents = roots.mapTo(HashSet()) { it.id to it.calendarId }
        for (r in pool) if (r.originalId == null && sameUid(r)) parents.add(r.id to r.calendarId)
        return pool.filter { r ->
            val parent = r.originalId
            if (parent == null) sameUid(r) else (parent to r.calendarId) in parents || sameUid(r)
        }
    }

    /**
     * The stored entry (of [entries]) each row belongs to, as [plan] reads it, for the rows of one run ([rows]): the
     * entry of the row's own id; else, for an exception, the entry of its parent row, which is an entry's own row or a
     * row of [rows] without a parent that follows an entry by UID, so a split clone's exceptions follow the series too;
     * else, for a row that carries nothing of Proton's, the entry with its non-blank UID. Only entries of the row's own
     * calendar count. [mappedIds] are the account's mapped Android event ids.
     */
    fun seriesIndex(rows: List<Row>, entries: List<Entry>, mappedIds: Set<Long>): (Row) -> Entry? {
        val byRow = HashMap<Pair<Long, Long>, Entry>()
        val byUid = HashMap<Pair<Long, String>, Entry>()
        for (e in entries) {
            byRow.putIfAbsent(e.eventId to e.calendarId, e)
            uidKey(e.uid)?.let { byUid.putIfAbsent(e.calendarId to it, e) }
        }
        fun byUidOf(r: Row): Entry? = if (phoneOnly(r, mappedIds)) uidKey(r.uid)?.let { byUid[r.calendarId to it] } else null
        val followers = HashMap<Pair<Long, Long>, Entry>()
        for (r in rows) {
            if (r.originalId == null && (r.id to r.calendarId) !in byRow) byUidOf(r)?.let { followers[r.id to r.calendarId] = it }
        }
        return { row ->
            val parent = row.originalId
            byRow[row.id to row.calendarId]
                ?: if (parent != null) {
                    byRow[parent to row.calendarId] ?: followers[parent to row.calendarId] ?: byUidOf(row)
                } else {
                    byUidOf(row)
                }
        }
    }

    /** Nothing of Proton's on the row: no _SYNC_ID, no ORIGINAL_SYNC_ID, no mapping ([PhoneOnlyRows.isFromProton]). */
    private fun phoneOnly(row: Row, mappedIds: Set<Long>): Boolean =
        !PhoneOnlyRows.isFromProton(row.id, row.syncId, row.originalSyncId, mappedIds)

    /** A UID as the series match compares it: non-blank, cut to what the upload sends. */
    private fun uidKey(uid: String?): String? = uid?.takeIf { it.isNotBlank() }?.take(SyncConstants.MAX_UID_LENGTH)

    /** A parent row as the provider holds it now, for the sweep. */
    data class PresentRow(val calendarId: Long, val deleted: Boolean, val dirty: Boolean, val syncId: String?)

    /**
     * The entries to drop, on positive evidence only: the parent row is gone, sits in another calendar, carries a
     * _SYNC_ID or has a mapping (it reached Proton, or the provider handed its id to another row); a published one also
     * once its row is clean. [present] is the parent rows the provider still holds by id, null when they could not be
     * read, which drops nothing.
     */
    fun staleEntries(entries: List<Entry>, present: Map<Long, PresentRow>?, mappedIds: Set<Long>): List<Entry> {
        if (present == null) return emptyList()
        return entries.filter { e ->
            val row = present[e.eventId]
            row == null || row.calendarId != e.calendarId || !row.syncId.isNullOrBlank() || e.eventId in mappedIds ||
                (e.state == State.PUBLISHED && !row.dirty)
        }
    }

    /**
     * The published entries of [entries] whose calendar has no mapping now ([mappedCalendarIds], Android calendar ids):
     * Proton removed the calendar before the series went up, and the phone keeps the calendar, unmapped, for the rows
     * in it. Such a series can go nowhere, so it waits for the user's choice again (NewEventHoldStore.unpublishCalendar):
     * Move and Keep are offered, Publish only once a re-share maps the calendar again.
     */
    fun publishedNowhere(entries: List<Entry>, mappedCalendarIds: Set<Long>): List<Entry> =
        entries.filter { it.state == State.PUBLISHED && it.calendarId !in mappedCalendarIds }

    /**
     * Whether the stale [entry] ([staleEntries], or one of [Plan.dropEntries]) may live on in another row of its
     * series: its parent row [row] (null: gone, or its tombstone went up with this run's upload) was deleted or moved
     * to another calendar, and the series has a non-blank UID, which a "this and following" split's clone or a second
     * insert of the same booking carries too. Such a row follows the series in [plan]; once the parent is gone it
     * would be judged on its own, and a split clone names the calendar app that made the split. A row that reached
     * Proton, or a published series that was uploaded, ends the question instead.
     */
    fun mayPassOn(entry: Entry, row: PresentRow?): Boolean =
        uidKey(entry.uid) != null && (row == null || row.calendarId != entry.calendarId)

    /**
     * [entry] keyed by the oldest of [followers] (the live rows of its calendar with its UID and no ORIGINAL_ID that
     * carry nothing of Proton's, as NewEventHolds reads them), or null when there is none: then the entry is dropped.
     */
    fun passedOn(entry: Entry, followers: Collection<Long>): Entry? =
        followers.filter { it != entry.eventId && it > 0 }.minOrNull()?.let { entry.copy(eventId = it) }

    /**
     * At most this many waiting series of one account get a notification of their own; the rest share one summary
     * (owner, 2026-11-21). Android refuses every NEW notification of an app that already has 50 up, without an error
     * (NotificationManagerService, MAX_PACKAGE_NOTIFICATIONS), so one prompt per series silenced the app's other
     * notifications once about 50 series waited: the collision and deletion prompts, the sync notification, failures.
     */
    const val MAX_PROMPTS = 5

    /** Which waiting series get a prompt of their own ([shown]) and which the summary counts ([rest]). */
    data class PromptPlan(val shown: List<Entry>, val rest: List<Entry>)

    /**
     * The prompts of one account's waiting series [waiting] (each one a prompt can be drawn for), at most [max]: the
     * series this run alerts about ([alertId], held in this run or sent back to waiting by it) first, then the newest
     * by the time they were held, and series held in the same run in the order of their rows (by row id, the order the
     * other app wrote them). Every other one is counted on the summary, which opens the account card's walk, oldest
     * first.
     *
     * Why: a series held in this run is what the user most likely just did in the other app, so its prompt can be
     * answered at once, and this run's series are always the newest (each is stamped with the run's time). The older
     * ones each had their prompt when they were held, and they are the first the summary's walk shows. The alerting
     * series always keeps its place, so a sound never points at a prompt that is not there. The order does not depend
     * on what is on screen, so a refresh keeps the same prompts up, and an answered or dropped series frees its place
     * for the next one. Both lists keep [waiting]'s order.
     */
    fun promptPlan(waiting: List<Entry>, alertId: Long?, max: Int = MAX_PROMPTS): PromptPlan {
        val order = compareByDescending<Entry> { it.eventId == alertId }
            .thenByDescending { it.heldAtMillis }
            .thenBy { it.eventId }
        val chosen = waiting.sortedWith(order).take(max.coerceAtLeast(0)).toHashSet()
        return PromptPlan(waiting.filter { it in chosen }, waiting.filter { it !in chosen })
    }

    /**
     * [text] as a prompt shows it, from an app that wrote the event or a calendar's name another user chose: line and
     * paragraph breaks and tabs become spaces, other control characters and the bidirectional formatting characters
     * (embeddings, overrides, isolates and marks) are dropped, so a title can never reverse or push away the warning
     * that follows it; runs of spaces become one. Null when nothing is left.
     */
    fun shownText(text: String?): String? {
        if (text == null) return null
        val out = StringBuilder(text.length)
        for (ch in text) {
            when {
                ch == '\n' || ch == '\r' || ch == '\t' || ch == '\u0085' || ch == '\u2028' || ch == '\u2029' -> out.append(' ')
                Character.isISOControl(ch) || ch in BIDI_CONTROLS -> Unit
                else -> out.append(ch)
            }
        }
        return out.toString().replace(Regex(" {2,}"), " ").trim().takeIf { it.isNotEmpty() }
    }

    private val BIDI_CONTROLS = setOf(
        '\u061C', '\u200E', '\u200F', '\u202A', '\u202B', '\u202C', '\u202D', '\u202E', '\u2066', '\u2067', '\u2068', '\u2069',
    )

    /** One row of a series that Move takes to the default calendar, with its ORGANIZER as stored. */
    data class MoveRow(val id: Long, val organizer: String?)

    /**
     * The ORGANIZER Move writes per row id: the target calendar's owner [targetOwner], only where the row's organizer is
     * the source calendar's owner [sourceOwner] (ignoring case), which is what the provider fills in for an app that
     * gives none. A third-party organizer, an empty one or an unknown owner is left alone.
     */
    fun movePlan(rows: List<MoveRow>, sourceOwner: String?, targetOwner: String?): Map<Long, String> {
        if (sourceOwner.isNullOrBlank() || targetOwner.isNullOrBlank()) return emptyMap()
        return rows.filter { it.organizer?.equals(sourceOwner, ignoreCase = true) == true }.associate { it.id to targetOwner }
    }

    /**
     * What the next upload does with [entry], for the event-holds debug verb: [present] as for [staleEntries] (null: the
     * rows could not be read), [mappedIds] this account's mapped event ids.
     */
    fun nextUpload(entry: Entry, present: Map<Long, PresentRow>?, mappedIds: Set<Long>): String {
        if (present == null) return "unknown (the rows could not be read)"
        val row = present[entry.eventId]
        val orPassedOn = if (mayPassOn(entry, row)) ", unless a row with its UID is left in that calendar" else ""
        return when {
            row == null -> "dropped at next sync (gone)$orPassedOn"
            row.calendarId != entry.calendarId -> "dropped at next sync (moved)$orPassedOn"
            !row.syncId.isNullOrBlank() -> "dropped at next sync (synced)"
            entry.eventId in mappedIds -> "dropped at next sync (mapped)"
            entry.state == State.PUBLISHED && !row.dirty -> "dropped at next sync (published, clean)"
            row.deleted -> "upload (tombstone, removed on this phone)" +
                if (mayPassOn(entry, null)) "; dropped, unless a row with its UID is left in that calendar" else ""
            entry.state == State.WAITING -> "skip (waiting)"
            entry.state == State.KEPT -> "skip (kept)"
            else -> "upload (published)"
        }
    }
}

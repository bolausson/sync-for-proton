package me.proton.sync.calendar

import me.proton.sync.api.models.CalendarInfo

/**
 * Which of an account's Proton calendars the phone marks as that account's primary calendar
 * (CalendarContract.Calendars.IS_PRIMARY = 1): the calendar Proton itself treats as the default. Pure, so it is
 * tested without a device. CalendarSyncAdapter fetches the inputs, [PrimaryCalendarRows] writes the result.
 *
 * Why it exists: an app that adds an event without asking which calendar takes "the primary calendar, else the
 * first". Every row this app wrote had IS_PRIMARY = 0, so such an app fell back to its own choice, which in the
 * field was a calendar another user had shared with edit rights, and a private appointment was published to
 * that calendar's members. The flag only steers apps that read it ([CalendarPickRules] lists rules that do not).
 *
 * ## The rule, mirrored from Proton
 *
 * Web, `getDefaultCalendar` (WebClients packages/shared/lib/calendar/calendar.ts):
 * the owned personal calendars, narrowed to the probably active ones, then `getPreferredCalendar` with DefaultCalendarID.
 * The settings router (applications/account/.../calendar/CalendarSettingsRouter.tsx) calls it on the list it has
 * sorted with `sortCalendars`: owned personal calendars first, then by the member's Priority, a stable sort.
 * `getPreferredCalendar` takes the calendar DefaultCalendarID names if it is in that list, else the list's first.
 * That calendar carries the "Default" badge. Only owned personal calendars get a "Set as default" button
 * (MyCalendarsSection.tsx hands CalendarsTable.tsx `myCalendars`, and the button needs
 * a calendar that is not subscribed, not disabled and not the default yet); shared, subscribed and holiday calendars never do.
 *
 * Proton's Android calendar, `getDefaultCalendarIdWithFallback(allowShared = false)` (data/CalendarsRepositoryImpl.kt),
 * which its settings screen and drawer use: the stored default if that calendar is active and owned, else the
 * first owned one of `sortPersonalCalendars(selectActiveUserCalendars())`, which are the active Type-0 calendars
 * by Priority, or in list order when any Priority is missing (common/utils/ProtonUtilsImpl.kt). Its settings
 * screen (presentation/settings/fragment/SettingsFragment.kt) offers "Mark as default" only for a calendar that
 * is not the default, is active, and is not subscribed, shared with the user or a holiday calendar. The
 * `allowShared = true` path of its event editor, which may fall back to a shared calendar the user can write to,
 * is deliberately not mirrored: a shared calendar is never chosen, even when DefaultCalendarID names one.
 *
 * So a candidate is: Type 0; owned through OUR own member ([isCalendarOwner]); active by that member's Flags
 * ([isProbablyActive]). [Result.candidates] lists them in Proton's order, which the account card's Default calendar
 * row offers as they are ([DefaultCalendarChoices]). Where the two clients differ, this follows web:
 *
 *  - **Active.** Web: Flags missing, or the ACTIVE bit (1) set and neither SELF_DISABLED (32) nor
 *    SUPER_OWNER_DISABLED (64) (getIsCalendarProbablyActive). Android also counts UPDATE_PASSPHRASE (2),
 *    RESET_NEEDED (4), INCOMPLETE_SETUP (8) and LOST_ACCESS (16) as inactive (Calendar.isActive) and has no
 *    missing value. A default with Flags 3 is the default on web and is passed over on Android; here it is the
 *    default, as on web.
 *  - **Type of the stored default.** Android takes the stored default when it is active and owned, whatever its
 *    Type; web looks for it among owned PERSONAL calendars only. Here it must be Type 0, as on web.
 *  - **Owned.** Web compares the calendar's Owner.Email with its first own member's address (getIsOwnedCalendar),
 *    Android reads the OWNER bit (2) of the user's member, [isCalendarOwner] the SUPER_OWNER (1) or OWNER (2) bit
 *    of our own member. An owner's member carries every bit (127), so the three agree on real calendars.
 *  - **Order with a missing Priority.** Web sorts by `Priority - Priority`, which is NaN for a missing value and
 *    then compares as equal, so its order is not a consistent one; Android keeps list order when any Priority is
 *    missing. Here too: list order (as `GET calendar/v1` returned it) when any candidate lacks a Priority, else
 *    the lowest Priority first with ties in list order, which is what both clients do then.
 *
 * ## What it answers
 *
 *  - [Decision.Known] with a calendar id: the default when it is a candidate, else the first candidate, with the
 *    [Reason] the default was passed over.
 *  - [Decision.Known] with no id: members of ours exist, but no calendar qualifies. No row is then primary.
 *  - [Decision.Unknown]: the settings could not be read; or no Type-0 calendar holds a member of ours (the address
 *    list was not loaded); or the default is not a candidate while a Type-0 calendar lists members on none of the
 *    loaded addresses (the address list was not fully loaded, see [UnknownReason.PARTIAL_ADDRESSES]). The flag
 *    never moves or clears because something failed: a candidate's row keeps what it stores. Only a row that can
 *    never be the default whatever the settings say ([Result.neverPrimary]: not Type 0, or our own member of it is
 *    a share or not active) still stores 0 then ([rowFlag]).
 */
internal object PrimaryCalendarChooser {

    /** WebClients CALENDAR_TYPE. */
    const val TYPE_PERSONAL = 0
    const val TYPE_SUBSCRIPTION = 1
    const val TYPE_HOLIDAYS = 2

    /** WebClients CALENDAR_FLAGS, the member's. */
    private const val FLAG_ACTIVE = 1
    private const val FLAG_SELF_DISABLED = 32
    private const val FLAG_SUPER_OWNER_DISABLED = 64

    /** What `GET settings/calendar` answered. */
    sealed interface Settings {
        /** A 2xx with Code 1000. [defaultCalendarId] is null when it is not set, as Proton still sends for old accounts. */
        data class Loaded(val defaultCalendarId: String?) : Settings

        /**
         * No usable answer; [detail] says why (HTTP status and Code, an exception class, a timeout). [httpStatus] is the
         * HTTP status when Proton answered, null for an exception or a timeout.
         */
        data class Failed(val detail: String, val httpStatus: Int? = null) : Settings
    }

    /** Why [Decision.Known] chose what it chose; [text] goes into the log line. */
    enum class Reason(val text: String) {
        PROTON_DEFAULT("Proton default"),
        DEFAULT_NOT_SET("Proton default not set: first own calendar"),
        DEFAULT_NOT_LISTED("Proton default is unknown: first own calendar"),
        DEFAULT_SHARED("Proton default is shared: first own calendar"),
        DEFAULT_SUBSCRIBED("Proton default is subscribed: first own calendar"),
        DEFAULT_HOLIDAY("Proton default is a holiday calendar: first own calendar"),
        DEFAULT_OTHER_TYPE("Proton default is of an unknown type: first own calendar"),
        /**
         * A Type-0 default that lists no member at all. One that lists members, none on a loaded address, is
         * [UnknownReason.PARTIAL_ADDRESSES] instead.
         */
        DEFAULT_NO_MEMBER("Proton default lists no member: first own calendar"),
        DEFAULT_DISABLED("Proton default is disabled: first own calendar"),
        DEFAULT_INACTIVE("Proton default is inactive: first own calendar"),
        NO_ACTIVE_OWN_CALENDAR("no active own calendar"),

        /**
         * Never returned by [choose]: the reason stored for a change Proton confirmed from the account card's Default
         * calendar row ([PrimaryCalendarPicks]).
         */
        PICKED_HERE("set as default in this app"),
    }

    /** Why [Decision.Unknown] could not decide. */
    enum class UnknownReason {
        /** settings/calendar gave no usable answer. */
        SETTINGS_FAILED,

        /** No Type-0 calendar holds a member of ours: the address list was not loaded. */
        NO_OWN_MEMBER,

        /**
         * The default is not a candidate, and a Type-0 calendar lists members, none of them on a loaded address.
         * `GET calendar/v1` lists only the account's own member entries (measured, see CalendarMemberResolution.kt),
         * so that calendar sits on an address of this account whose keys did not load: a key that failed to unlock,
         * or an address added since the key ring was read. Proton's own answer does not depend on keys (web's
         * getIsOwnedCalendar compares Owner.Email with the first member's), so a fallback chosen from the loaded
         * addresses could move the flag to another calendar, or clear it, which brings back the field report.
         * ownMemberIdForWrites reads the same evidence the same way.
         */
        PARTIAL_ADDRESSES,
    }

    sealed interface Decision {
        /** [protonCalendarId] is the primary calendar; null: none is. */
        data class Known(val protonCalendarId: String?, val reason: Reason) : Decision

        /**
         * Nothing can be decided this run: a row that could be the default keeps what it stores, and only one that
         * can never be ([Result.neverPrimary]) is written, with 0 ([rowFlag]).
         */
        data class Unknown(val reason: UnknownReason, val detail: String) : Decision
    }

    /**
     * The calendars Proton allows as a default, in Proton's order, and the decision. [neverPrimary] holds the ids of
     * the calendars that can never be the default whatever DefaultCalendarID says, as far as the loaded addresses
     * tell: every calendar that is not Type 0, and every Type-0 one whose own member is not a candidate (a share, or
     * a calendar that member has disabled or not activated). A Type-0 calendar with no member of ours is not among
     * them, because whose it is is not known.
     *
     * [pickGeneration] is [PrimaryCalendarPicks.generation] as read just before the settings were fetched, so the
     * settle pass after the calendar loop keeps a choice made on the Default calendar row since then. Set only by
     * CalendarSyncAdapter.decidePrimaryCalendar.
     */
    data class Result(
        val candidates: List<CalendarInfo>,
        val decision: Decision,
        val neverPrimary: Set<String> = emptySet(),
        val pickGeneration: Long = 0L,
    )

    /**
     * The decision for one account's [calendars], the full `GET calendar/v1` list before any skip. [userAddresses]
     * are the account's addresses, lowercase, as `KeyManager.getAddressEmails` returns them.
     */
    fun choose(calendars: List<CalendarInfo>, userAddresses: Set<String>, settings: Settings): Result {
        val candidates = candidates(calendars, userAddresses)
        val candidateIds = candidates.mapTo(HashSet()) { it.id }
        val neverPrimary = calendars.filter { calendar ->
            calendar.type != TYPE_PERSONAL ||
                (ownMember(calendar.members, userAddresses) != null && calendar.id !in candidateIds)
        }.mapTo(HashSet()) { it.id }
        fun result(decision: Decision) = Result(candidates, decision, neverPrimary)

        if (settings is Settings.Failed) {
            return result(Decision.Unknown(UnknownReason.SETTINGS_FAILED, settings.detail))
        }
        // No own member on any Type-0 calendar is what an address list that was not loaded looks like, and it
        // would otherwise read as "nothing qualifies" and clear the flag everywhere.
        if (calendars.none { it.type == TYPE_PERSONAL && ownMember(it.members, userAddresses) != null }) {
            return result(
                Decision.Unknown(
                    UnknownReason.NO_OWN_MEMBER,
                    "no personal calendar holds a member of ours (${userAddresses.size} address(es) loaded)"
                )
            )
        }
        val defaultId = (settings as Settings.Loaded).defaultCalendarId?.takeIf { it.isNotEmpty() }
        // A default that is a candidate is Proton's answer whatever else the list holds, so a calendar on an address
        // whose keys did not load cannot change it.
        if (defaultId != null && defaultId in candidateIds) {
            return result(Decision.Known(defaultId, Reason.PROTON_DEFAULT))
        }
        // Every other answer depends on the whole list: the default may sit on an address that did not load, and so
        // may an own calendar that comes before the fallback by Priority (see PARTIAL_ADDRESSES).
        val unloaded = calendars.count { onUnloadedAddress(it, userAddresses) }
        if (unloaded > 0) {
            return result(
                Decision.Unknown(
                    UnknownReason.PARTIAL_ADDRESSES,
                    "$unloaded personal calendar(s) list members on none of the ${userAddresses.size} address(es) " +
                        "whose keys are loaded"
                )
            )
        }
        val first = candidates.firstOrNull()
            ?: return result(Decision.Known(null, Reason.NO_ACTIVE_OWN_CALENDAR))
        val reason = if (defaultId == null) {
            Reason.DEFAULT_NOT_SET
        } else {
            whyPassedOver(calendars.firstOrNull { it.id == defaultId }, userAddresses)
        }
        return result(Decision.Known(first.id, reason))
    }

    /**
     * Whether the account's addresses should be read again at the next sync, because this run's [decision] was left
     * unknown by a Type-0 calendar that lists members on none of the loaded addresses (ownMemberIdForWrites does the
     * same on the same evidence). Never for an account without such a calendar: one with no calendars at all is
     * [UnknownReason.NO_OWN_MEMBER] on every sync and must not re-read its keys every time. Not after a failed
     * settings read either, which is unknown for that reason alone.
     */
    fun wantsAddressRefresh(decision: Decision, calendars: List<CalendarInfo>, userAddresses: Set<String>): Boolean =
        decision is Decision.Unknown && decision.reason != UnknownReason.SETTINGS_FAILED &&
            calendars.any { onUnloadedAddress(it, userAddresses) }

    /** A Type-0 calendar that lists members, none of them on [userAddresses]. */
    private fun onUnloadedAddress(calendar: CalendarInfo, userAddresses: Set<String>): Boolean =
        calendar.type == TYPE_PERSONAL && !calendar.members.isNullOrEmpty() &&
            ownMember(calendar.members, userAddresses) == null

    /**
     * The calendars Proton allows as a default, in Proton's order: Type 0, owned through our own member, active by
     * that member's Flags. By Priority, lowest first and ties in list order, or all in list order when any of them
     * has no Priority.
     */
    fun candidates(calendars: List<CalendarInfo>, userAddresses: Set<String>): List<CalendarInfo> {
        val eligible = calendars.filter { calendar ->
            calendar.type == TYPE_PERSONAL &&
                isCalendarOwner(calendar.members, userAddresses) &&
                isProbablyActive(ownMember(calendar.members, userAddresses)?.flags)
        }
        val priorities = eligible.map { ownMember(it.members, userAddresses)?.priority }
        if (priorities.any { it == null }) return eligible
        // sortedBy is stable, so equal priorities keep the order Proton listed them in.
        return eligible.zip(priorities).sortedBy { it.second ?: Int.MAX_VALUE }.map { it.first }
    }

    /** Web's getIsCalendarProbablyActive on a member's Flags: missing counts as active. */
    fun isProbablyActive(flags: Int?): Boolean =
        flags == null || (!isDisabled(flags) && (flags and FLAG_ACTIVE) != 0)

    /** Web's getIsCalendarDisabled: SELF_DISABLED or SUPER_OWNER_DISABLED. */
    fun isDisabled(flags: Int?): Boolean =
        flags != null && (flags and (FLAG_SELF_DISABLED or FLAG_SUPER_OWNER_DISABLED)) != 0

    /**
     * What the row of calendar [protonCalendarId] stores for [result]: true or false when the decision is known;
     * while it is unknown, false for a calendar that can never be the default ([Result.neverPrimary]), so a reused
     * row cannot keep a 1 it held for another calendar, and null for any other, which leaves the stored value as
     * it is.
     */
    fun rowFlag(result: Result, protonCalendarId: String): Boolean? = when (val decision = result.decision) {
        is Decision.Known -> decision.protonCalendarId == protonCalendarId
        is Decision.Unknown -> if (protonCalendarId in result.neverPrimary) false else null
    }

    /**
     * Why the calendar DefaultCalendarID names is not a candidate. Null: it is not in the list. Only for a calendar
     * that is not a candidate: DefaultCalendarChoices counts the calendars the Default calendar row leaves out with it.
     */
    internal fun whyPassedOver(calendar: CalendarInfo?, userAddresses: Set<String>): Reason {
        if (calendar == null) return Reason.DEFAULT_NOT_LISTED
        when (calendar.type) {
            TYPE_PERSONAL -> Unit
            TYPE_SUBSCRIPTION -> return Reason.DEFAULT_SUBSCRIBED
            TYPE_HOLIDAYS -> return Reason.DEFAULT_HOLIDAY
            else -> return Reason.DEFAULT_OTHER_TYPE
        }
        // Members on none of the loaded addresses never get here (choose() answers PARTIAL_ADDRESSES first).
        val own = ownMember(calendar.members, userAddresses) ?: return Reason.DEFAULT_NO_MEMBER
        if (!isCalendarOwner(calendar.members, userAddresses)) return Reason.DEFAULT_SHARED
        return if (isDisabled(own.flags)) Reason.DEFAULT_DISABLED else Reason.DEFAULT_INACTIVE
    }
}

/**
 * The last primary-calendar decision of an account, kept in preferences
 * ([me.proton.sync.SyncPreferences.primaryCalendarLastKey]) so a sync can say when the choice moved, the account
 * card's Default calendar row can show the calendar, and the calendar-picks debug verb can show it. Only Known
 * decisions are kept: an unknown one leaves the flag, and so this record, as it was. The Default calendar row writes
 * it too, for a change Proton confirmed and for the fresh read of its list ([PrimaryCalendarPicks]), always under
 * [PrimaryCalendarPicks.lock]. Device-local, never in a settings backup.
 *
 * Ids only: the calendar's name is the user's data, which plain preferences never hold, so [encode] leaves it out and
 * whatever shows the calendar reads its name live (the card from the calendar's mapping, in the app's database).
 */
internal object PrimaryCalendarMemory {

    /**
     * [protonCalendarId] is null when no calendar was chosen. [androidCalId] is the row that stores 1, or null: no
     * calendar chosen, no row on this phone yet, a row that cannot be primary or is not the account's, or (from the
     * Default calendar row) no row written. After a calendar sync whose clean-up write failed it is still the row the
     * settle pass chose: the row of the run's own decision, which its calendar loop marked, or for a pick kept over
     * that decision ([PrimaryCalendarPicks.Settle.keptPick]) the pick's row, which the loop may not have marked, since
     * the loop writes the run's own decision. The row's load therefore compares the stored flags themselves, not only
     * this record ([PrimaryCalendarPicks.needsBringInLine]). [name] is the plain calendar name
     * ([DefaultCalendarChoices.nameOf], without the `<account> - ` prefix) of a decision made in this process, for its
     * log lines (with Verbose logging only): never stored, so a decoded record has none.
     */
    data class Last(val protonCalendarId: String?, val androidCalId: Long?, val reason: String, val name: String?)

    /** Whether [now] chose another calendar than [previous] did; false when nothing was stored before. */
    fun moved(previous: Last?, now: Last): Boolean = previous != null && previous.protonCalendarId != now.protonCalendarId

    /**
     * `<proton id>|<android id>|<reason>|`: the fourth field, where builds before this one kept the calendar's name, is
     * always empty now, so the name never reaches plain preferences.
     */
    fun encode(last: Last): String = listOf(
        last.protonCalendarId.orEmpty(),
        last.androidCalId?.toString().orEmpty(),
        last.reason,
        ""
    ).joinToString("|")

    /**
     * The record [encode] wrote, or null for nothing stored or anything else. A record that an earlier build wrote with
     * the calendar's name in its fourth field (which may hold a `|`) still decodes, its name left out: the next calendar
     * sync that decides the primary calendar, or a change made on the Default calendar row, rewrites it without the name.
     */
    fun decode(raw: String?): Last? {
        if (raw.isNullOrEmpty()) return null
        val parts = raw.split("|", limit = 4)
        if (parts.size < 4 || parts[2].isEmpty()) return null
        return Last(
            protonCalendarId = parts[0].ifEmpty { null },
            androidCalId = parts[1].toLongOrNull(),
            reason = parts[2],
            name = null
        )
    }
}

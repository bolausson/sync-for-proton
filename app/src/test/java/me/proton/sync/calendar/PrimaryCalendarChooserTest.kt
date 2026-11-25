package me.proton.sync.calendar

import me.proton.sync.api.models.CalendarInfo
import me.proton.sync.api.models.CalendarMember
import me.proton.sync.calendar.PrimaryCalendarChooser.Decision
import me.proton.sync.calendar.PrimaryCalendarChooser.Reason
import me.proton.sync.calendar.PrimaryCalendarChooser.Settings
import me.proton.sync.calendar.PrimaryCalendarChooser.UnknownReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which calendar the phone marks as the account's primary one: Proton's default when it is an active calendar the
 * account owns, else the first such calendar by Priority, never a shared, subscribed or holiday calendar, and
 * nothing at all when the inputs cannot be trusted. Made-up addresses and calendar names only.
 */
class PrimaryCalendarChooserTest {

    private companion object {
        const val ME = "me@example.test"
        const val ALIAS = "alias@example.test"
        const val SOMEONE = "someone@example.test"
        /** An address of the account whose keys did not load, so it is not in [MINE]. */
        const val MISSING = "missing@example.test"
        val MINE = setOf(ME, ALIAS)

        /** An owner's member: every permission bit, SUPER_OWNER (1) and OWNER (2) included. */
        const val OWNS = 127
        /** A share with edit rights: WRITE, READ and AVAILABILITY (112, as measured). */
        const val EDIT = 16 or 32 or 64
        /** A view-only share: READ and AVAILABILITY (96). */
        const val VIEW = 32 or 64

        const val ACTIVE = 1
    }

    private fun member(
        email: String = ME,
        permissions: Int = OWNS,
        flags: Int? = ACTIVE,
        priority: Int? = null,
    ) = CalendarMember(
        id = "member-$email",
        email = email,
        permissions = permissions,
        addressId = "address-$email",
        calendarId = "calendar",
        color = null,
        display = 1,
        flags = flags,
        priority = priority,
    )

    private fun calendar(id: String, type: Int = 0, members: List<CalendarMember> = listOf(member())) = CalendarInfo(
        id = id,
        name = id,
        description = null,
        color = null,
        display = 1,
        type = type,
        flags = 0,
        members = members,
    )

    private fun owned(id: String, priority: Int? = null, flags: Int? = ACTIVE) =
        calendar(id, members = listOf(member(priority = priority, flags = flags)))

    /** A Type-0 calendar somebody else owns, shared with this account's alias. */
    private fun shared(id: String, permissions: Int = EDIT) =
        calendar(id, members = listOf(member(email = ALIAS, permissions = permissions)))

    private fun choose(calendars: List<CalendarInfo>, defaultId: String?) =
        PrimaryCalendarChooser.choose(calendars, MINE, Settings.Loaded(defaultId))

    private fun known(id: String?, reason: Reason) = Decision.Known(id, reason)

    private fun unknownReason(decision: Decision): UnknownReason? = (decision as? Decision.Unknown)?.reason

    @Test
    fun `the default is chosen when it is an active calendar the account owns`() {
        val result = choose(listOf(owned("Work", priority = 1), owned("Home", priority = 2)), "Home")

        assertEquals(known("Home", Reason.PROTON_DEFAULT), result.decision)
        assertEquals(listOf("Work", "Home"), result.candidates.map { it.id })
    }

    @Test
    fun `no default set falls back to the own calendar with the lowest Priority`() {
        val calendars = listOf(owned("Work", priority = 2), owned("Home", priority = 1))

        assertEquals(known("Home", Reason.DEFAULT_NOT_SET), choose(calendars, null).decision)
        // An empty id, as web's getPreferredCalendar defaults to, is the same as none.
        assertEquals(known("Home", Reason.DEFAULT_NOT_SET), choose(calendars, "").decision)
    }

    @Test
    fun `a default shared with edit rights is never chosen`() {
        val result = choose(listOf(shared("Team"), owned("Home")), "Team")

        assertEquals(known("Home", Reason.DEFAULT_SHARED), result.decision)
        assertEquals(listOf("Home"), result.candidates.map { it.id })
    }

    @Test
    fun `a default shared view-only is never chosen`() {
        assertEquals(
            known("Home", Reason.DEFAULT_SHARED),
            choose(listOf(shared("Team", permissions = VIEW), owned("Home")), "Team").decision
        )
    }

    @Test
    fun `a subscribed default is never chosen, though the account owns it`() {
        val feed = calendar("Feed", type = PrimaryCalendarChooser.TYPE_SUBSCRIPTION)

        assertEquals(known("Home", Reason.DEFAULT_SUBSCRIBED), choose(listOf(feed, owned("Home")), "Feed").decision)
    }

    @Test
    fun `a holiday default is never chosen`() {
        val holidays = calendar("Holidays", type = PrimaryCalendarChooser.TYPE_HOLIDAYS)

        assertEquals(known("Home", Reason.DEFAULT_HOLIDAY), choose(listOf(holidays, owned("Home")), "Holidays").decision)
    }

    @Test
    fun `a default of a type Proton does not know is never chosen`() {
        assertEquals(known("Home", Reason.DEFAULT_OTHER_TYPE), choose(listOf(calendar("Odd", type = 3), owned("Home")), "Odd").decision)
    }

    @Test
    fun `a disabled default is passed over`() {
        for (flags in listOf(ACTIVE or 32, ACTIVE or 64, 32, 64)) {
            assertEquals(
                "flags $flags",
                known("Home", Reason.DEFAULT_DISABLED),
                choose(listOf(owned("Old", flags = flags), owned("Home")), "Old").decision
            )
        }
    }

    @Test
    fun `an inactive default is passed over`() {
        // Without the ACTIVE bit: 0, and each of UPDATE_PASSPHRASE, RESET_NEEDED, INCOMPLETE_SETUP, LOST_ACCESS alone.
        for (flags in listOf(0, 2, 4, 8, 16)) {
            assertEquals(
                "flags $flags",
                known("Home", Reason.DEFAULT_INACTIVE),
                choose(listOf(owned("Old", flags = flags), owned("Home")), "Old").decision
            )
        }
    }

    @Test
    fun `an active calendar with inactive keys stays the default, as on web`() {
        // ACTIVE + UPDATE_PASSPHRASE: web's getIsCalendarProbablyActive says active; Android's Calendar.isActive would not.
        assertEquals(known("Home", Reason.PROTON_DEFAULT), choose(listOf(owned("Work"), owned("Home", flags = 3)), "Home").decision)
    }

    @Test
    fun `a default that is not in the list is passed over`() {
        assertEquals(known("Home", Reason.DEFAULT_NOT_LISTED), choose(listOf(owned("Home")), "gone").decision)
    }

    @Test
    fun `a missing member Flags counts as active`() {
        val result = choose(listOf(owned("Work"), owned("Home", flags = null)), "Home")

        assertEquals(known("Home", Reason.PROTON_DEFAULT), result.decision)
        assertTrue(PrimaryCalendarChooser.isProbablyActive(null))
        assertFalse(PrimaryCalendarChooser.isProbablyActive(0))
    }

    @Test
    fun `equal priorities keep the order Proton listed them in`() {
        val calendars = listOf(owned("A", priority = 2), owned("B", priority = 1), owned("C", priority = 1))
        val result = choose(calendars, null)

        assertEquals(listOf("B", "C", "A"), result.candidates.map { it.id })
        assertEquals(known("B", Reason.DEFAULT_NOT_SET), result.decision)
    }

    @Test
    fun `a missing Priority keeps the whole list in Proton's order`() {
        val calendars = listOf(owned("A", priority = 2), owned("B", priority = null), owned("C", priority = 1))
        val result = choose(calendars, null)

        assertEquals(listOf("A", "B", "C"), result.candidates.map { it.id })
        assertEquals(known("A", Reason.DEFAULT_NOT_SET), result.decision)
    }

    @Test
    fun `a shared calendar's Priority does not order the own ones`() {
        val calendars = listOf(owned("A", priority = 2), shared("Team"), owned("B", priority = 1))

        assertEquals(listOf("B", "A"), choose(calendars, null).candidates.map { it.id })
    }

    @Test
    fun `our own member is found by address when the first member is somebody else`() {
        // Our own entry owns it, though somebody else's entry is listed first.
        val ours = calendar("Home", members = listOf(member(email = SOMEONE, permissions = VIEW), member(email = ME)))
        // And the reverse: the owner listed first, our entry only a share.
        val theirs = calendar("Team", members = listOf(member(email = SOMEONE), member(email = ALIAS, permissions = EDIT)))

        assertEquals(known("Home", Reason.PROTON_DEFAULT), choose(listOf(theirs, ours), "Home").decision)
        assertEquals(known("Home", Reason.DEFAULT_SHARED), choose(listOf(theirs, ours), "Team").decision)
    }

    @Test
    fun `the own member's Flags and Priority count, not the first member's`() {
        val home = calendar(
            "Home",
            members = listOf(member(email = SOMEONE, flags = 0, priority = 9), member(email = ME, flags = ACTIVE, priority = 1))
        )
        val work = owned("Work", priority = 2)

        assertEquals(known("Home", Reason.DEFAULT_NOT_SET), choose(listOf(work, home), null).decision)
    }

    @Test
    fun `a default whose members are on none of the loaded addresses decides nothing`() {
        // The calendar list carries only the account's own entries, so a Type-0 calendar listing an address that is
        // not among the loaded ones sits on an address of ours whose keys did not load. Passing it over moved the flag.
        val strange = calendar("Strange", members = listOf(member(email = SOMEONE)))

        assertEquals(UnknownReason.PARTIAL_ADDRESSES, unknownReason(choose(listOf(strange, owned("Home")), "Strange").decision))
    }

    @Test
    fun `own calendars on an address whose keys did not load never clear the flag`() {
        // Home, Proton's default, is on an address whose keys did not load; Team is shared to a loaded one.
        val home = calendar("Home", members = listOf(member(email = MISSING)))
        val decision = choose(listOf(shared("Team"), home), "Home").decision

        // Not Known(null): that cleared every row, and a "primary, else first" app took the shared row again.
        assertEquals(UnknownReason.PARTIAL_ADDRESSES, unknownReason(decision))
    }

    @Test
    fun `a fallback is not chosen while an own calendar may be missing from the loaded addresses`() {
        val home = calendar("Home", members = listOf(member(email = MISSING, priority = 1)))

        // No default set: Proton would take Home (Priority 1), not Work.
        assertEquals(UnknownReason.PARTIAL_ADDRESSES, unknownReason(choose(listOf(home, owned("Work", priority = 2)), null).decision))
        // A shared default: Proton's fallback could be Home as well.
        assertEquals(
            UnknownReason.PARTIAL_ADDRESSES,
            unknownReason(choose(listOf(home, owned("Work"), shared("Team")), "Team").decision)
        )
        // A default Proton does not list: the same.
        assertEquals(UnknownReason.PARTIAL_ADDRESSES, unknownReason(choose(listOf(home, owned("Work")), "gone").decision))
    }

    @Test
    fun `a default that is a candidate stands, whatever sits on an address that did not load`() {
        val elsewhere = calendar("Elsewhere", members = listOf(member(email = MISSING, priority = 0)))
        val result = choose(listOf(elsewhere, owned("Home", priority = 1)), "Home")

        assertEquals(known("Home", Reason.PROTON_DEFAULT), result.decision)
        // And every other row stores 0: the default is known, so the calendar on the other address is not it.
        assertEquals(false, PrimaryCalendarChooser.rowFlag(result, "Elsewhere"))
    }

    @Test
    fun `a personal calendar that lists no member at all does not stop the decision`() {
        val empty = calendar("Empty", members = emptyList())

        assertEquals(known("Home", Reason.DEFAULT_NOT_SET), choose(listOf(empty, owned("Home")), null).decision)
        assertEquals(known("Home", Reason.DEFAULT_NO_MEMBER), choose(listOf(empty, owned("Home")), "Empty").decision)
    }

    @Test
    fun `the addresses are read again only when a personal calendar lists members on none of them`() {
        val home = calendar("Home", members = listOf(member(email = MISSING)))
        fun wants(calendars: List<CalendarInfo>, addresses: Set<String>, settings: Settings) =
            PrimaryCalendarChooser.wantsAddressRefresh(
                PrimaryCalendarChooser.choose(calendars, addresses, settings).decision, calendars, addresses
            )

        assertTrue(wants(listOf(shared("Team"), home), MINE, Settings.Loaded(null)))
        // No address loaded at all, while calendars list members: the same evidence.
        assertTrue(wants(listOf(owned("Home")), emptySet(), Settings.Loaded(null)))
        // An account without calendars is unknown on every sync, and must not re-read its keys every time.
        assertFalse(wants(emptyList(), MINE, Settings.Loaded(null)))
        // Known decisions and failed settings reads never ask for it.
        assertFalse(wants(listOf(owned("Work"), home), MINE, Settings.Loaded("Work")))
        assertFalse(wants(listOf(shared("Team"), home), MINE, Settings.Failed("HTTP 503")))
        assertFalse(wants(listOf(owned("Home")), MINE, Settings.Loaded(null)))
    }

    @Test
    fun `with members of ours but no calendar that qualifies, no calendar is primary`() {
        val calendars = listOf(
            shared("Team"),
            calendar("Feed", type = PrimaryCalendarChooser.TYPE_SUBSCRIPTION),
            owned("Old", flags = 0),
        )

        assertEquals(known(null, Reason.NO_ACTIVE_OWN_CALENDAR), choose(calendars, "Team").decision)
        assertEquals(known(null, Reason.NO_ACTIVE_OWN_CALENDAR), choose(calendars, null).decision)
    }

    @Test
    fun `a failed settings read decides nothing, but still lists the candidates`() {
        val result = PrimaryCalendarChooser.choose(listOf(owned("Home")), MINE, Settings.Failed("HTTP 503"))

        assertEquals(Decision.Unknown(UnknownReason.SETTINGS_FAILED, "HTTP 503"), result.decision)
        assertEquals(listOf("Home"), result.candidates.map { it.id })
    }

    @Test
    fun `no member of ours anywhere decides nothing`() {
        val calendars = listOf(owned("Home"), shared("Team"))

        // The address list was not loaded.
        val notLoaded = PrimaryCalendarChooser.choose(calendars, emptySet(), Settings.Loaded("Home")).decision
        assertTrue(notLoaded is Decision.Unknown)
        assertEquals(UnknownReason.NO_OWN_MEMBER, (notLoaded as Decision.Unknown).reason)

        // Loaded, but none of its addresses is on any personal calendar.
        val noMatch = PrimaryCalendarChooser.choose(calendars, setOf("other@example.test"), Settings.Loaded("Home")).decision
        assertEquals(UnknownReason.NO_OWN_MEMBER, (noMatch as Decision.Unknown).reason)

        // A subscribed calendar of ours does not count: only a personal calendar's member says the addresses are loaded.
        val onlyFeed = PrimaryCalendarChooser.choose(
            listOf(calendar("Feed", type = PrimaryCalendarChooser.TYPE_SUBSCRIPTION)), MINE, Settings.Loaded(null)
        ).decision
        assertEquals(UnknownReason.NO_OWN_MEMBER, (onlyFeed as Decision.Unknown).reason)
    }

    @Test
    fun `each row's flag follows a known decision`() {
        val calendars = listOf(owned("Home"), shared("Team"), calendar("Feed", type = PrimaryCalendarChooser.TYPE_SUBSCRIPTION))
        val home = choose(calendars, "Home")

        assertEquals(true, PrimaryCalendarChooser.rowFlag(home, "Home"))
        assertEquals(false, PrimaryCalendarChooser.rowFlag(home, "Team"))
        assertEquals(false, PrimaryCalendarChooser.rowFlag(home, "Feed"))
        assertEquals(false, PrimaryCalendarChooser.rowFlag(choose(listOf(owned("Old", flags = 0)), null), "Old"))
    }

    @Test
    fun `while the decision is unknown only a calendar that can never be the default stores 0`() {
        val calendars = listOf(
            owned("Home"),
            shared("Team"),
            shared("Look", permissions = VIEW),
            owned("Old", flags = 0),
            calendar("Feed", type = PrimaryCalendarChooser.TYPE_SUBSCRIPTION),
            calendar("Holidays", type = PrimaryCalendarChooser.TYPE_HOLIDAYS),
            calendar("Elsewhere", members = listOf(member(email = MISSING))),
        )
        val failed = PrimaryCalendarChooser.choose(calendars, MINE, Settings.Failed("HTTP 503"))

        // A reused row cannot keep the 1 it held for another calendar: shares, inactive, subscribed and holiday rows store 0.
        for (id in listOf("Team", "Look", "Old", "Feed", "Holidays")) {
            assertEquals(id, false, PrimaryCalendarChooser.rowFlag(failed, id))
        }
        // A candidate keeps what it stores, and so does a calendar whose member is on an address that did not load.
        assertNull(PrimaryCalendarChooser.rowFlag(failed, "Home"))
        assertNull(PrimaryCalendarChooser.rowFlag(failed, "Elsewhere"))

        // The same without settings and with part of the addresses missing.
        val partial = choose(calendars, null)
        assertEquals(UnknownReason.PARTIAL_ADDRESSES, unknownReason(partial.decision))
        assertEquals(false, PrimaryCalendarChooser.rowFlag(partial, "Team"))
        assertNull(PrimaryCalendarChooser.rowFlag(partial, "Home"))
        assertNull(PrimaryCalendarChooser.rowFlag(partial, "Elsewhere"))
    }

    @Test
    fun `with no address loaded only the external calendars store 0`() {
        val calendars = listOf(owned("Home"), shared("Team"), calendar("Feed", type = PrimaryCalendarChooser.TYPE_SUBSCRIPTION))
        val notLoaded = PrimaryCalendarChooser.choose(calendars, emptySet(), Settings.Failed("HTTP 503"))

        // Without addresses, a share cannot be told from the default: both Type-0 rows keep what they store.
        assertNull(PrimaryCalendarChooser.rowFlag(notLoaded, "Home"))
        assertNull(PrimaryCalendarChooser.rowFlag(notLoaded, "Team"))
        assertEquals(false, PrimaryCalendarChooser.rowFlag(notLoaded, "Feed"))
        assertEquals(
            UnknownReason.NO_OWN_MEMBER,
            unknownReason(PrimaryCalendarChooser.choose(calendars, emptySet(), Settings.Loaded("Home")).decision)
        )
    }

    @Test
    fun `the last decision survives a round trip without its name, and a record that holds one still decodes`() {
        // Plain preferences never hold the calendar's name (the owner's rule, 2026-11-21): ids and the reason only.
        val last = PrimaryCalendarMemory.Last("cal-1==", 42L, Reason.PROTON_DEFAULT.name, "Home | Work")
        val encoded = PrimaryCalendarMemory.encode(last)
        assertEquals("cal-1==|42|${Reason.PROTON_DEFAULT.name}|", encoded)
        assertFalse(encoded, encoded.contains("Home") || encoded.contains("Work"))
        assertEquals(last.copy(name = null), PrimaryCalendarMemory.decode(encoded))

        // A record an earlier build stored with the name, a bar in it included: decoded, the name left out.
        assertEquals(last.copy(name = null), PrimaryCalendarMemory.decode("cal-1==|42|${Reason.PROTON_DEFAULT.name}|Home | Work"))

        val none = PrimaryCalendarMemory.Last(null, null, Reason.NO_ACTIVE_OWN_CALENDAR.name, null)
        assertEquals(none, PrimaryCalendarMemory.decode(PrimaryCalendarMemory.encode(none)))

        assertNull(PrimaryCalendarMemory.decode(null))
        assertNull(PrimaryCalendarMemory.decode(""))
        assertNull(PrimaryCalendarMemory.decode("not a record"))
        assertNull(PrimaryCalendarMemory.decode("id|1||name"))
    }

    @Test
    fun `calendar-picks names another account's chosen calendar only when no account was named`() {
        val last = PrimaryCalendarMemory.Last("cal-1", 42L, Reason.PROTON_DEFAULT.name, "Home")

        assertEquals("row 42 'Home' (Proton default)", PrimaryCalendarDebug.lastDecisionText(last, showName = true))
        assertEquals("row 42 (Proton default)", PrimaryCalendarDebug.lastDecisionText(last, showName = false))
        // A choice no row holds (no row yet, a row two calendars share, or a row that is not the account's).
        assertEquals(
            "no row primary (Proton default not set: first own calendar)",
            PrimaryCalendarDebug.lastDecisionText(
                PrimaryCalendarMemory.Last("cal-1", null, Reason.DEFAULT_NOT_SET.name, "Home"), showName = false
            )
        )
        assertEquals(
            "none (no active own calendar)",
            PrimaryCalendarDebug.lastDecisionText(PrimaryCalendarMemory.Last(null, null, Reason.NO_ACTIVE_OWN_CALENDAR.name, null), true)
        )
        assertEquals("no decision yet", PrimaryCalendarDebug.lastDecisionText(null, true))
    }

    @Test
    fun `a failed read without an HTTP status equals the one the sync always built`() {
        assertEquals(Settings.Failed("x"), Settings.Failed("x", null))
        assertEquals(401, Settings.Failed("HTTP 401", 401).httpStatus)
        assertNull(Settings.Failed("SocketTimeoutException").httpStatus)
    }

    @Test
    fun `a decision carries no pick generation until the sync sets it`() {
        assertEquals(0L, choose(listOf(owned("Home")), "Home").pickGeneration)
    }

    @Test
    fun `a change made on the Default calendar row survives a round trip, without its name`() {
        val picked = PrimaryCalendarMemory.Last("cal-1==", 41L, Reason.PICKED_HERE.name, "Home")
        assertEquals(picked.copy(name = null), PrimaryCalendarMemory.decode(PrimaryCalendarMemory.encode(picked)))
        // Not on the phone yet: no row.
        val noRow = PrimaryCalendarMemory.Last("cal-2==", null, Reason.PICKED_HERE.name, "Work")
        assertEquals(noRow.copy(name = null), PrimaryCalendarMemory.decode(PrimaryCalendarMemory.encode(noRow)))
        assertFalse(PrimaryCalendarMemory.encode(noRow).contains("Work"))
        // The debug verb shows the name it reads live from the chosen row, and none when it has none.
        assertEquals("row 41 'Home' (set as default in this app)", PrimaryCalendarDebug.lastDecisionText(picked, showName = true))
        assertEquals(
            "row 41 (set as default in this app)",
            PrimaryCalendarDebug.lastDecisionText(PrimaryCalendarMemory.decode(PrimaryCalendarMemory.encode(picked)), showName = true)
        )
    }

    @Test
    fun `the choice moved only when another calendar is chosen`() {
        val home = PrimaryCalendarMemory.Last("Home", 41L, Reason.PROTON_DEFAULT.name, "Home")
        val none = PrimaryCalendarMemory.Last(null, null, Reason.NO_ACTIVE_OWN_CALENDAR.name, null)

        assertFalse(PrimaryCalendarMemory.moved(null, home))
        assertFalse(PrimaryCalendarMemory.moved(home, home.copy(androidCalId = 42L, reason = Reason.PICKED_HERE.name)))
        assertTrue(PrimaryCalendarMemory.moved(home, PrimaryCalendarMemory.Last("Work", 42L, Reason.PROTON_DEFAULT.name, "Work")))
        assertTrue(PrimaryCalendarMemory.moved(none, home))
        assertTrue(PrimaryCalendarMemory.moved(home, none))
    }

    @Test
    fun `the reason of a change made on the row is never the chooser's`() {
        val calendars = listOf(owned("Home"), owned("Work"), shared("Team"), calendar("Feed", type = PrimaryCalendarChooser.TYPE_SUBSCRIPTION))
        for (default in listOf("Home", "Work", "Team", "Feed", "gone", null)) {
            val decision = choose(calendars, default).decision as Decision.Known
            assertTrue("$default", decision.reason != Reason.PICKED_HERE)
        }
    }

    @Test
    fun `the debug fixture stands in for Proton only with a value it knows`() {
        assertEquals(Settings.Failed("debug fixture: fail"), PrimaryCalendarDebug.fixtureSettings("fail"))
        assertEquals(Settings.Loaded(null), PrimaryCalendarDebug.fixtureSettings("null"))
        assertEquals(Settings.Loaded("cal-1"), PrimaryCalendarDebug.fixtureSettings("id:cal-1"))
        assertNull(PrimaryCalendarDebug.fixtureSettings("id:"))
        assertNull(PrimaryCalendarDebug.fixtureSettings("real"))
        assertNull(PrimaryCalendarDebug.fixtureSettings(null))
    }
}

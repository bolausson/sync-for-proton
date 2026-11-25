package me.proton.sync.calendar

import me.proton.sync.SyncConstants
import me.proton.sync.api.models.CalendarMember
import me.proton.sync.calendar.NewEventHoldRules.Entry
import me.proton.sync.calendar.NewEventHoldRules.HoldDecision
import me.proton.sync.calendar.NewEventHoldRules.MoveRow
import me.proton.sync.calendar.NewEventHoldRules.PresentRow
import me.proton.sync.calendar.NewEventHoldRules.Row
import me.proton.sync.calendar.NewEventHoldRules.State
import me.proton.sync.calendar.NewEventHoldRules.Verdict
import me.proton.sync.contacts.FakePrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which new events the upload holds back ([NewEventHoldRules]): an app that is not a calendar app wrote them into a
 * calendar someone else shared with the account, with edit rights, and nothing may reach that calendar's members
 * before the user chooses. The customer's case behind it: a booking app inserted an appointment straight into the
 * phone row of a calendar a colleague had shared, and the sync published it there. Made-up addresses and packages only.
 */
class NewEventHoldRulesTest {

    private companion object {
        const val OWN = "me.proton.sync"
        const val BOOKING = "com.example.booking"
        const val CALENDAR_APP = "com.example.calendar"
        const val SHARED = 7L
        const val MINE = 3L
        const val OTHER_SHARED = 9L
        const val NOW = 1_800_000_000_000L
        const val OWNS = 127
        const val EDIT = 112
        const val VIEW = 96
    }

    private val editors = setOf(CALENDAR_APP)

    private fun verdictOf(row: Row): Verdict =
        NewEventHoldRules.verdict(row.mutators, row.mutatorsReadable, OWN) { it in editors }

    private fun row(
        id: Long,
        cal: Long = SHARED,
        deleted: Boolean = false,
        syncId: String? = null,
        originalId: Long? = null,
        originalSyncId: String? = null,
        uid: String? = null,
        mutators: String? = BOOKING,
        readable: Boolean = true,
    ) = Row(id, cal, deleted, syncId, originalId, originalSyncId, uid, mutators, readable)

    private fun entry(id: Long, state: State = State.WAITING, cal: Long = SHARED, uid: String? = null, writer: String? = BOOKING) =
        Entry(state, id, cal, NOW - 1000, writer, uid)

    private val decisions = mapOf(
        SHARED to HoldDecision.HOLD, OTHER_SHARED to HoldDecision.HOLD, MINE to HoldDecision.NO_HOLD,
    )

    private fun plan(
        rows: List<Row>,
        entries: List<Entry> = emptyList(),
        decisions: Map<Long, HoldDecision> = this.decisions,
        mapped: Set<Long> = emptySet(),
    ) = NewEventHoldRules.plan(rows, entries, decisions, mapped, ::verdictOf, NOW)

    private fun member(email: String, permissions: Int) = CalendarMember(
        id = "member-$email", email = email, permissions = permissions, addressId = "addr-$email",
        calendarId = "cal1", color = null, display = 1,
    )

    // ---- verdict: the first MUTATORS entry decides ----

    @Test
    fun `no writer recorded holds, with no name`() {
        for (m in listOf(null, "", " , ")) {
            assertEquals("'$m'", Verdict.Hold(null), NewEventHoldRules.verdict(m, true, OWN) { it in editors })
        }
        // The provider refused the column: nobody knows who wrote it.
        assertEquals(Verdict.Hold(null), NewEventHoldRules.verdict(CALENDAR_APP, false, OWN) { it in editors })
    }

    @Test
    fun `an app that is not a calendar app holds, a calendar app's editor uploads`() {
        assertEquals(Verdict.Hold(BOOKING), NewEventHoldRules.verdict(BOOKING, true, OWN) { it in editors })
        assertEquals(Verdict.Upload, NewEventHoldRules.verdict(CALENDAR_APP, true, OWN) { it in editors })
        assertEquals(Verdict.Hold("com.android.shell"), NewEventHoldRules.verdict("com.android.shell", true, OWN) { it in editors })
    }

    @Test
    fun `only the first entry counts, so an app that only added a reminder never decides`() {
        assertEquals(Verdict.Upload, NewEventHoldRules.verdict("$CALENDAR_APP,$BOOKING", true, OWN) { it in editors })
        assertEquals(Verdict.Hold(BOOKING), NewEventHoldRules.verdict("$BOOKING,$CALENDAR_APP", true, OWN) { it in editors })
    }

    @Test
    fun `this app first is no writer recorded`() {
        assertEquals(Verdict.Hold(null), NewEventHoldRules.verdict(OWN, true, OWN) { it in editors })
        assertEquals(Verdict.Hold(null), NewEventHoldRules.verdict("$OWN,$CALENDAR_APP", true, OWN) { it in editors })
    }

    @Test
    fun `a shared-uid entry uploads when its uid resolves to an editor, else holds`() {
        val entry = "android.uid.calendar:10045"
        val resolves = { e: String -> CalendarEditors.isEditor(e, editors) { uid -> if (uid == 10045) arrayOf(CALENDAR_APP) else null } }
        assertEquals(Verdict.Upload, NewEventHoldRules.verdict(entry, true, OWN, resolves))
        val unknown = { e: String -> CalendarEditors.isEditor(e, editors) { null } }
        assertEquals(Verdict.Hold(entry), NewEventHoldRules.verdict(entry, true, OWN, unknown))
        val otherApps = { e: String -> CalendarEditors.isEditor(e, editors) { arrayOf(BOOKING) } }
        assertEquals(Verdict.Hold("10045"), NewEventHoldRules.verdict("10045", true, OWN, otherApps))
        // A lookup that throws resolves to nothing.
        val failing = { e: String -> CalendarEditors.isEditor(e, editors) { throw SecurityException("no") } }
        assertEquals(Verdict.Hold(entry), NewEventHoldRules.verdict(entry, true, OWN, failing))
    }

    @Test
    fun `the writer is trimmed and sanitized`() {
        assertEquals(Verdict.Hold(BOOKING), NewEventHoldRules.verdict("  $BOOKING  ,x", true, OWN) { it in editors })
        // A line break or a store separator never reaches the log line or the stored entry.
        assertEquals(Verdict.Hold("com.example?bad?name"), NewEventHoldRules.verdict("com.example\nbad|name", true, OWN) { false })
        assertEquals(Verdict.Upload, NewEventHoldRules.verdict(" $CALENDAR_APP ", true, OWN) { it in editors })
    }

    // ---- holdDecision: whose calendar it is, by address ----

    @Test
    fun `an own calendar never holds, an edit share holds, a view-only share keeps the refusal path`() {
        val addresses = setOf("user@example.net")
        val mine = listOf(member("user@example.net", OWNS))
        assertEquals(HoldDecision.NO_HOLD, NewEventHoldRules.holdDecision(0, mine, addresses))
        assertEquals(HoldDecision.HOLD, NewEventHoldRules.holdDecision(0, listOf(member("user@example.net", EDIT)), addresses))
        assertEquals(HoldDecision.NO_HOLD, NewEventHoldRules.holdDecision(0, listOf(member("user@example.net", VIEW)), addresses))
        // Permissions absent (0) on a calendar the account does not own: the upload treats it as writable, so it holds.
        assertEquals(HoldDecision.HOLD, NewEventHoldRules.holdDecision(0, listOf(member("user@example.net", 0)), addresses))
    }

    @Test
    fun `subscribed and holiday calendars never hold, and no member of ours is undecided`() {
        val addresses = setOf("user@example.net")
        for (type in listOf(1, 2)) {
            assertEquals(HoldDecision.NO_HOLD, NewEventHoldRules.holdDecision(type, listOf(member("user@example.net", EDIT)), addresses))
        }
        assertEquals(HoldDecision.UNDECIDED, NewEventHoldRules.holdDecision(0, listOf(member("owner@example.org", OWNS)), addresses))
        assertEquals(HoldDecision.UNDECIDED, NewEventHoldRules.holdDecision(0, null, addresses))
        assertEquals(HoldDecision.UNDECIDED, NewEventHoldRules.holdDecision(0, listOf(member("user@example.net", EDIT)), emptySet()))
    }

    @Test
    fun `our member is found by address when the owner's entry comes first`() {
        val members = listOf(member("owner@example.org", OWNS), member("user@example.net", EDIT))
        assertEquals(HoldDecision.HOLD, NewEventHoldRules.holdDecision(0, members, setOf("user@example.net")))
        // And the owner reading the same list is not held.
        assertEquals(HoldDecision.NO_HOLD, NewEventHoldRules.holdDecision(0, members, setOf("owner@example.org")))
    }

    @Test
    fun `a calendar shared with an alias of the account is held, whatever the account name is`() {
        // The account signs in as its main address; the colleague shared the calendar with its alias. Both are
        // addresses of the account with an unlocked key, so the member of ours is the alias.
        val accountName = "user@example.net"
        val addresses = setOf(accountName, "alias@example.test")
        val shared = listOf(member("colleague@example.org", OWNS), member("Alias@Example.test", EDIT))
        assertEquals(HoldDecision.HOLD, NewEventHoldRules.holdDecision(0, shared, addresses))
        // An own calendar on the alias is never held.
        assertEquals(HoldDecision.NO_HOLD, NewEventHoldRules.holdDecision(0, listOf(member("alias@example.test", OWNS)), addresses))
        // Members on none of our addresses: undecided, and the plan asks nothing.
        val strangers = listOf(member("colleague@example.org", OWNS), member("someone@example.org", EDIT))
        val decision = NewEventHoldRules.holdDecision(0, strangers, addresses)
        assertEquals(HoldDecision.UNDECIDED, decision)
        val p = plan(listOf(row(40)), decisions = mapOf(SHARED to decision))
        assertEquals(listOf(40L), p.undecided.map { it.id })
        assertTrue(p.newHolds.isEmpty())
        assertTrue(p.upload.isEmpty())
    }

    @Test
    fun `a row two calendars map to takes the stricter decision, in either order`() {
        val all = HoldDecision.values().toList()
        for (a in all) for (b in all) assertEquals("$a $b", NewEventHoldRules.stricter(a, b), NewEventHoldRules.stricter(b, a))
        assertEquals(HoldDecision.HOLD, NewEventHoldRules.stricter(HoldDecision.NO_HOLD, HoldDecision.HOLD))
        assertEquals(HoldDecision.HOLD, NewEventHoldRules.stricter(HoldDecision.UNDECIDED, HoldDecision.HOLD))
        assertEquals(HoldDecision.UNDECIDED, NewEventHoldRules.stricter(HoldDecision.NO_HOLD, HoldDecision.UNDECIDED))
        assertEquals(HoldDecision.NO_HOLD, NewEventHoldRules.stricter(HoldDecision.NO_HOLD, HoldDecision.NO_HOLD))
    }

    // ---- plan: entries and the rows that follow them ----

    @Test
    fun `a published event uploads although its MUTATORS still names the app that wrote it`() {
        val p = plan(listOf(row(20)), listOf(entry(20, State.PUBLISHED)))
        assertEquals(listOf(20L), p.upload.map { it.id })
        assertTrue(p.newHolds.isEmpty())
        assertTrue(p.skipped.isEmpty())
    }

    @Test
    fun `waiting and kept rows stay out, but only in their own calendar`() {
        for (state in listOf(State.WAITING, State.KEPT)) {
            val p = plan(listOf(row(21)), listOf(entry(21, state)))
            assertEquals(listOf(21L), p.skipped.map { it.id })
            assertTrue(p.upload.isEmpty())
            assertTrue(p.newHolds.isEmpty())
        }
        // The same id now in an own calendar (moved in a calendar app): judged where it is, and uploaded.
        val moved = plan(listOf(row(21, cal = MINE)), listOf(entry(21)))
        assertEquals(listOf(21L), moved.upload.map { it.id })
        // In another shared calendar it is a new candidate there.
        val other = plan(listOf(row(21, cal = OTHER_SHARED)), listOf(entry(21)))
        assertEquals(listOf(21L), other.newHolds.map { it.eventId })
        assertEquals(OTHER_SHARED, other.newHolds.single().calendarId)
    }

    @Test
    fun `exceptions of a waiting or kept series stay out, exceptions of a published one upload`() {
        val exception = row(31, originalId = 30, mutators = CALENDAR_APP)
        for (state in listOf(State.WAITING, State.KEPT)) {
            assertEquals(listOf(31L), plan(listOf(exception), listOf(entry(30, state))).skipped.map { it.id })
        }
        assertEquals(listOf(31L), plan(listOf(exception), listOf(entry(30, State.PUBLISHED))).upload.map { it.id })
    }

    @Test
    fun `a row with the series' UID follows it, a blank UID or another calendar never does`() {
        val clone = row(33, uid = "series-uid@example.test", mutators = CALENDAR_APP)
        for (state in listOf(State.WAITING, State.KEPT)) {
            assertEquals(listOf(33L), plan(listOf(clone), listOf(entry(30, state, uid = "series-uid@example.test"))).skipped.map { it.id })
        }
        assertEquals(listOf(33L), plan(listOf(clone), listOf(entry(30, State.PUBLISHED, uid = "series-uid@example.test"))).upload.map { it.id })
        // A blank UID matches nothing: the editor's row uploads on its own writer.
        val blank = row(34, uid = "  ", mutators = CALENDAR_APP)
        assertEquals(listOf(34L), plan(listOf(blank), listOf(entry(30, uid = "  "))).upload.map { it.id })
        // The same UID in another calendar does not follow.
        val elsewhere = row(35, cal = MINE, uid = "series-uid@example.test", mutators = BOOKING)
        assertEquals(listOf(35L), plan(listOf(elsewhere), listOf(entry(30, uid = "series-uid@example.test"))).upload.map { it.id })
        // A row of Proton's with that UID never follows, so its edit is never held back.
        val proton = row(36, uid = "series-uid@example.test", syncId = "proton-36")
        assertEquals(listOf(36L), plan(listOf(proton), listOf(entry(30, uid = "series-uid@example.test"))).upload.map { it.id })
        val mapped = row(37, uid = "series-uid@example.test")
        assertEquals(listOf(37L), plan(listOf(mapped), listOf(entry(30, uid = "series-uid@example.test")), mapped = setOf(37L)).upload.map { it.id })
    }

    @Test
    fun `UIDs are compared as the upload cuts them`() {
        val long = "u".repeat(SyncConstants.MAX_UID_LENGTH) + "-tail"
        val clone = row(38, uid = "u".repeat(SyncConstants.MAX_UID_LENGTH) + "-other", mutators = CALENDAR_APP)
        assertEquals(listOf(38L), plan(listOf(clone), listOf(entry(30, uid = long))).skipped.map { it.id })
    }

    @Test
    fun `the parent's tombstone uploads and its entry goes, while its live rows stay out in that run`() {
        val rows = listOf(row(30, deleted = true), row(31, originalId = 30), row(32, uid = "s@example.test"))
        val held = entry(30, uid = "s@example.test")
        val p = plan(rows, listOf(held))
        assertEquals(listOf(30L), p.upload.map { it.id })
        assertEquals(listOf(held), p.dropEntries)
        assertEquals(listOf(31L, 32L), p.skipped.map { it.id })
    }

    // ---- plan: candidates ----

    @Test
    fun `rows of Proton's or of a series are never candidates`() {
        val notNew = listOf(
            row(50) to setOf(50L),                       // mapped
            row(51, syncId = "proton-51") to emptySet(),
            row(52, originalSyncId = "proton-parent") to emptySet(),
            row(53, originalId = 49) to emptySet(),
            row(54, deleted = true) to emptySet(),
        )
        for ((r, mapped) in notNew) {
            assertFalse("${r.id}", NewEventHoldRules.isCandidate(r, mapped))
            val p = plan(listOf(r), mapped = mapped)
            assertEquals("${r.id}", listOf(r.id), p.upload.map { it.id })
            assertTrue("${r.id}", p.newHolds.isEmpty())
        }
        // Blank Proton ids count as none: the row is new.
        assertTrue(NewEventHoldRules.isCandidate(row(55, syncId = " ", originalSyncId = ""), emptySet()))
    }

    @Test
    fun `a calendar's decision rules a new row`() {
        assertEquals(listOf(60L), plan(listOf(row(60, cal = MINE))).upload.map { it.id })
        // A calendar without a decision (not mapped, not listed this run) uploads as before: it has no upload context.
        assertEquals(listOf(61L), plan(listOf(row(61, cal = 99L))).upload.map { it.id })
        val undecided = plan(listOf(row(62)), decisions = mapOf(SHARED to HoldDecision.UNDECIDED))
        assertEquals(listOf(62L), undecided.undecided.map { it.id })
        assertTrue(undecided.newHolds.isEmpty())
        // A calendar app's editor in a shared calendar: the user picked that calendar on its screen.
        assertEquals(listOf(63L), plan(listOf(row(63, mutators = CALENDAR_APP))).upload.map { it.id })
    }

    @Test
    fun `a held row becomes one waiting entry with its time, writer and UID`() {
        val p = plan(listOf(row(64, uid = "booking-64@example.test")))
        assertEquals(listOf(Entry(State.WAITING, 64, SHARED, NOW, BOOKING, "booking-64@example.test")), p.newHolds)
        assertEquals(listOf(64L), p.skipped.map { it.id })
        // No writer recorded, no UID.
        val anonymous = plan(listOf(row(65, mutators = null, uid = " ")))
        assertEquals(listOf(Entry(State.WAITING, 65, SHARED, NOW, null, null)), anonymous.newHolds)
    }

    @Test
    fun `in the run that holds it, a new series' exception and its same-UID row follow it without an entry`() {
        val rows = listOf(
            row(70, uid = "s70@example.test"),
            row(71, originalId = 70, mutators = CALENDAR_APP),
            row(72, uid = "s70@example.test", mutators = CALENDAR_APP),
            row(73, uid = "s70@example.test"),
            row(74, mutators = CALENDAR_APP),
        )
        val p = plan(rows)
        assertEquals(listOf(70L), p.newHolds.map { it.eventId })
        assertEquals(setOf(70L, 71L, 72L, 73L), p.skipped.map { it.id }.toSet())
        assertEquals(listOf(74L), p.upload.map { it.id })
    }

    @Test
    fun `a split clone's exceptions follow a stored series, with or without the UID the provider copies`() {
        // Kept series 83 (UID u); Samsung's "this and following" cloned it into 84 (UID u, no ORIGINAL_ID); deleting one
        // date of the later half made the cancelled exception 85 (ORIGINAL_ID = 84). Without the follow, 85 uploaded its
        // parent, the kept later half, into the shared calendar.
        for (state in listOf(State.WAITING, State.KEPT)) {
            val rows = listOf(
                row(83, uid = "u@example.test"),
                row(84, uid = "u@example.test", mutators = CALENDAR_APP),
                row(85, originalId = 84, uid = "u@example.test", mutators = CALENDAR_APP),
                row(86, originalId = 84, mutators = CALENDAR_APP),
            )
            val p = plan(rows, listOf(entry(83, state, uid = "u@example.test")))
            assertEquals("$state", setOf(83L, 84L, 85L, 86L), p.skipped.map { it.id }.toSet())
            assertTrue("$state", p.upload.isEmpty())
        }
        // A published series lets them all through.
        val published = plan(
            listOf(row(84, uid = "u@example.test", mutators = CALENDAR_APP), row(86, originalId = 84, mutators = CALENDAR_APP)),
            listOf(entry(83, State.PUBLISHED, uid = "u@example.test")),
        )
        assertEquals(listOf(84L, 86L), published.upload.map { it.id })
        // A phone-only exception with the series' UID follows it even when its parent row is not in this run.
        assertEquals(listOf(87L), plan(listOf(row(87, originalId = 70, uid = "u@example.test")), listOf(entry(83, uid = "u@example.test"))).skipped.map { it.id })
        // An exception of Proton's with that UID never does.
        val proton = row(88, originalId = 70, originalSyncId = "proton-70", uid = "u@example.test")
        assertEquals(listOf(88L), plan(listOf(proton), listOf(entry(83, uid = "u@example.test"))).upload.map { it.id })
    }

    @Test
    fun `in the run that holds it, the exceptions of every held or following row of the series follow it`() {
        val rows = listOf(
            row(90, uid = "b@example.test"),                                   // carries the entry
            row(91, uid = "b@example.test"),                                   // a second insert of the booking
            row(92, originalId = 91),                                          // an exception of the second insert
            row(93, uid = "b@example.test", mutators = CALENDAR_APP),          // a split clone
            row(94, originalId = 93, mutators = CALENDAR_APP),                 // and its exception
            row(95, originalId = 96, uid = "b@example.test", mutators = CALENDAR_APP), // an exception with the UID
        )
        val p = plan(rows)
        assertEquals(listOf(90L), p.newHolds.map { it.eventId })
        assertEquals(setOf(90L, 91L, 92L, 93L, 94L, 95L), p.skipped.map { it.id }.toSet())
        assertTrue(p.upload.isEmpty())
    }

    @Test
    fun `an undecided series waits as one, its exceptions and same-UID rows with it`() {
        val rows = listOf(
            row(100, uid = "w@example.test"),
            row(101, originalId = 100, mutators = CALENDAR_APP),
            row(102, uid = "w@example.test", deleted = true),
            row(103, originalId = 104, uid = "w@example.test", mutators = CALENDAR_APP),
            row(105, cal = MINE, originalId = 100, mutators = CALENDAR_APP),
        )
        val p = plan(rows, decisions = mapOf(SHARED to HoldDecision.UNDECIDED, MINE to HoldDecision.NO_HOLD))
        assertEquals(setOf(100L, 101L, 102L, 103L), p.undecided.map { it.id }.toSet())
        // The owner's decision B of 2026-11-22: the series' exception 105 in an own calendar stays out too, apart, until
        // the series' calendar is decided. It used to go up there, and a cancelled one created the whole series in the
        // own calendar on Proton, which was then never held or asked about.
        assertEquals(listOf(105L), p.apart.map { it.id })
        assertTrue(p.upload.isEmpty())
        assertTrue(p.newHolds.isEmpty())
    }

    @Test
    fun `an undecided series' changed and cancelled dates stay out in an own calendar and in an unmapped one`() {
        // A phone-only series with a changed date and a cancelled one; an app that is not a calendar app moved only its
        // parent 300 into SHARED while no member of SHARED was among the loaded addresses. The plan reads no STATUS, so
        // both are live exceptions to it. The upload tells them apart: a cancelled one goes up as its whole parent series,
        // read by id, in the date's own calendar (uploadCancelledOccurrenceParent), a changed one is created there with
        // the series' UID, and either way the series is never asked about.
        val undecided = mapOf(SHARED to HoldDecision.UNDECIDED, MINE to HoldDecision.NO_HOLD)
        for (cal in listOf(MINE, 99L)) {
            val p = plan(
                listOf(
                    row(300),
                    row(301, cal = cal, originalId = 300, mutators = CALENDAR_APP), // a changed date
                    row(302, cal = cal, originalId = 300, mutators = CALENDAR_APP), // a cancelled one
                ),
                decisions = undecided,
            )
            assertEquals("$cal", listOf(300L), p.undecided.map { it.id })
            assertEquals("$cal", listOf(301L, 302L), p.apart.map { it.id })
            assertTrue("$cal", p.upload.isEmpty())
            assertTrue("$cal", p.skipped.isEmpty() && p.newHolds.isEmpty())
        }
        // The same for an exception of a row that follows the series by UID (a split's clone), a tombstone, and one with
        // a Proton event of its own: a cancelled one still goes up as its parent, which never reached Proton.
        val more = plan(
            listOf(
                row(300, uid = "s300@example.test"),
                row(303, uid = "s300@example.test", mutators = CALENDAR_APP),
                row(304, cal = MINE, originalId = 303, mutators = CALENDAR_APP),
                row(305, cal = MINE, originalId = 300, deleted = true),
                row(306, cal = MINE, originalId = 300, syncId = "proton-306", mutators = CALENDAR_APP),
            ),
            decisions = undecided,
            mapped = setOf(306L),
        )
        assertEquals(setOf(300L, 303L), more.undecided.map { it.id }.toSet())
        assertEquals(listOf(304L, 305L, 306L), more.apart.map { it.id })
        assertTrue(more.upload.isEmpty())
        // In another calendar that holds, or another undecided one, it stays apart as before (the moved-parent rule).
        for (d in listOf(HoldDecision.HOLD, HoldDecision.UNDECIDED)) {
            val other = plan(
                listOf(row(300), row(307, cal = OTHER_SHARED, originalId = 300, mutators = CALENDAR_APP)),
                decisions = undecided + (OTHER_SHARED to d),
            )
            assertEquals("$d", listOf(307L), other.apart.map { it.id })
            assertTrue("$d", other.upload.isEmpty())
        }
        // An exception whose parent is no row of an undecided series goes up as before.
        val plain = plan(
            listOf(row(308, cal = MINE, mutators = CALENDAR_APP), row(309, cal = 99L, originalId = 308, mutators = CALENDAR_APP)),
            decisions = undecided,
        )
        assertEquals(listOf(308L, 309L), plain.upload.map { it.id })
        assertTrue(plain.apart.isEmpty())
    }

    @Test
    fun `once its calendar is decided, an undecided series and its dates in other calendars are judged as usual`() {
        val rows = listOf(
            row(320, uid = "s320@example.test"),
            row(321, cal = MINE, originalId = 320, mutators = CALENDAR_APP),
            row(322, cal = 99L, originalId = 320, mutators = CALENDAR_APP),
        )
        // A calendar shared with the account: the series is held, its dates out with it in every calendar.
        val held = plan(rows)
        assertEquals(listOf(320L), held.newHolds.map { it.eventId })
        assertEquals(setOf(320L, 321L, 322L), held.skipped.map { it.id }.toSet())
        assertTrue(held.upload.isEmpty() && held.apart.isEmpty() && held.undecided.isEmpty())
        // The account's own calendar after all, on an address whose keys had not loaded: it goes up with its dates, as before.
        val own = plan(rows, decisions = mapOf(SHARED to HoldDecision.NO_HOLD, MINE to HoldDecision.NO_HOLD))
        assertEquals(listOf(320L, 321L, 322L), own.upload.map { it.id })
        assertTrue(own.apart.isEmpty() && own.undecided.isEmpty() && own.skipped.isEmpty())
        // Through the store: the undecided run stores nothing and sends nothing; the next one holds the series and asks.
        val prefs = FakePrefs()
        val first = NewEventHoldRules.plan(
            rows, NewEventHoldStore.snapshot(prefs, account), mapOf(SHARED to HoldDecision.UNDECIDED, MINE to HoldDecision.NO_HOLD),
            emptySet(), ::verdictOf, NOW,
        )
        NewEventHoldStore.addHolds(prefs, account, first.newHolds)
        assertTrue(NewEventHoldStore.snapshot(prefs, account).isEmpty())
        assertEquals(listOf(321L, 322L), first.apart.map { it.id })
        assertTrue(first.upload.isEmpty())
        val second = holdOnce(prefs, rows)
        assertEquals(320L, stored(prefs).eventId)
        assertEquals(setOf(320L, 321L, 322L), second.skipped.map { it.id }.toSet())
        assertTrue(second.upload.isEmpty())
    }

    @Test
    fun `a new series in a calendar without a decision keeps its dates in other calendars out with it`() {
        // Proton removed the undecided calendar 11 of a phone-only series (its owner stopped sharing it) before a run decided
        // it, so this run's list lacks it and it has no decision, as a calendar the phone keeps without a mapping has none.
        // The parent goes to the upload, which has no context there and leaves it dirty, while its cancelled date in an own
        // calendar went up as the whole series, read by id (uploadCancelledOccurrenceParent), never asked about.
        val removed = 11L
        val rows = listOf(
            row(340, cal = removed, uid = "s340@example.test"),
            row(341, cal = MINE, originalId = 340, mutators = CALENDAR_APP), // a changed date
            row(342, cal = 99L, originalId = 340, mutators = CALENDAR_APP), // a cancelled one, in an unmapped calendar
            row(343, cal = removed, originalId = 340, mutators = CALENDAR_APP), // a date in the series' own calendar
            row(344, cal = removed, uid = "s340@example.test", mutators = CALENDAR_APP), // a split's clone
            row(345, cal = MINE, originalId = 344, mutators = CALENDAR_APP), // and its date
            row(346, cal = MINE, originalId = 340, deleted = true), // a tombstone
        )
        val gone = plan(rows, decisions = mapOf(MINE to HoldDecision.NO_HOLD))
        // The series' own calendar goes to the upload as before; its dates elsewhere stay out, as in the run before.
        assertEquals(listOf(340L, 343L, 344L), gone.upload.map { it.id })
        assertEquals(listOf(341L, 342L, 345L, 346L), gone.apart.map { it.id })
        assertTrue(gone.undecided.isEmpty() && gone.skipped.isEmpty() && gone.newHolds.isEmpty())
        val before = plan(rows, decisions = mapOf(removed to HoldDecision.UNDECIDED, MINE to HoldDecision.NO_HOLD))
        assertEquals(setOf(340L, 343L, 344L), before.undecided.map { it.id }.toSet())
        assertEquals(listOf(341L, 342L, 345L, 346L), before.apart.map { it.id })
        // In a calendar of the user's own the series goes up with all its dates, as in 2.1.0.
        val own = plan(rows, decisions = mapOf(removed to HoldDecision.NO_HOLD, MINE to HoldDecision.NO_HOLD))
        assertEquals(rows.map { it.id }, own.upload.map { it.id })
        assertTrue(own.apart.isEmpty())
        // A series of Proton's there, or a date whose parent is not in this run, is not this rule's: as before.
        val proton = plan(
            listOf(row(347, cal = removed, syncId = "proton-347"), row(348, cal = MINE, originalId = 347, originalSyncId = "proton-347")),
            decisions = mapOf(MINE to HoldDecision.NO_HOLD),
        )
        assertEquals(listOf(347L, 348L), proton.upload.map { it.id })
        val alone = plan(listOf(row(349, cal = MINE, originalId = 350)), decisions = mapOf(MINE to HoldDecision.NO_HOLD))
        assertEquals(listOf(349L), alone.upload.map { it.id })
    }

    @Test
    fun `an exception made on this phone whose parent now sits in another calendar stays out where calendars hold`() {
        // An app moved the parent 110 alone to the own calendar; its exception 111 stayed in the shared one, where it
        // would go up, a cancelled one as its whole parent series, which never reached Proton.
        val inRun = plan(listOf(row(110, cal = MINE, mutators = CALENDAR_APP), row(111, originalId = 110, mutators = CALENDAR_APP)))
        assertEquals(listOf(110L), inRun.upload.map { it.id })
        assertEquals(listOf(111L), inRun.apart.map { it.id })
        // The parent not in this run: where it sits comes from parentCalendars.
        val read = NewEventHoldRules.plan(
            listOf(row(112, originalId = 113)), emptyList(), decisions, emptySet(),
            ::verdictOf, NOW, parentCalendars = mapOf(113L to MINE),
        )
        assertEquals(listOf(112L), read.apart.map { it.id })
        // Undecided calendars wait the same way.
        val undecided = NewEventHoldRules.plan(
            listOf(row(112, originalId = 113)), emptyList(), mapOf(SHARED to HoldDecision.UNDECIDED), emptySet(),
            ::verdictOf, NOW, parentCalendars = mapOf(113L to MINE),
        )
        assertEquals(listOf(112L), undecided.apart.map { it.id })
        // In the same calendar, or gone (missing): uploads as before.
        val same = NewEventHoldRules.plan(listOf(row(114, originalId = 115)), emptyList(), decisions, emptySet(), ::verdictOf, NOW, mapOf(115L to SHARED))
        assertEquals(listOf(114L), same.upload.map { it.id })
        assertEquals(listOf(116L), plan(listOf(row(116, originalId = 117))).upload.map { it.id })
        // Changed by the owner's decision L of 2026-11-22: in a calendar that does not hold, too, while the parent, which
        // never reached Proton, sits in a calendar shared with the account. 118 used to go up in MINE, a cancelled one as
        // the whole series with its guests, once nothing held the parent any more: after a Publish that Proton refused
        // for good, or when a calendar app, which is not held, moved only the parent there.
        val own = NewEventHoldRules.plan(listOf(row(118, cal = MINE, originalId = 119)), emptyList(), decisions, emptySet(), ::verdictOf, NOW, mapOf(119L to SHARED))
        assertEquals(listOf(118L), own.apart.map { it.id })
        assertTrue(own.upload.isEmpty())
        // A series the user answered still decides its own rows.
        val kept = plan(listOf(row(111, originalId = 110)), listOf(entry(110, State.KEPT)))
        assertEquals(listOf(111L), kept.skipped.map { it.id })
    }

    @Test
    fun `an exception of a waiting or kept series stays out in whatever calendar it sits, an own one included`() {
        // A phone-only series in the own calendar MINE with one cancelled date (exception 131); an app that is not a
        // calendar app moved only the parent 130 into the shared calendar, where it waits or was kept. The provider never
        // moves exceptions, so 131 stayed in MINE, which holds nothing: its upload there, a cancelled one as its whole
        // parent series, put the held series on Proton.
        for (state in listOf(State.WAITING, State.KEPT)) {
            val p = plan(listOf(row(130), row(131, cal = MINE, originalId = 130, mutators = CALENDAR_APP)), listOf(entry(130, state)))
            assertEquals("$state", listOf(130L, 131L), p.skipped.map { it.id })
            assertTrue("$state", p.upload.isEmpty())
            assertTrue("$state", p.apart.isEmpty())
            // A calendar without a decision (not mapped) holds nothing either, and its row stays out the same way.
            val unmapped = plan(listOf(row(130), row(132, cal = 99L, originalId = 130, mutators = CALENDAR_APP)), listOf(entry(130, state)))
            assertEquals("$state", listOf(130L, 132L), unmapped.skipped.map { it.id })
            assertTrue("$state", unmapped.upload.isEmpty())
            // Another calendar that holds keeps it apart, as before.
            val shared = plan(listOf(row(130), row(133, cal = OTHER_SHARED, originalId = 130, mutators = CALENDAR_APP)), listOf(entry(130, state)))
            assertEquals("$state", listOf(133L), shared.apart.map { it.id })
            assertTrue("$state", shared.upload.isEmpty())
            // A tombstone of such an exception stays with its series, as in the series' own calendar.
            val gone = plan(listOf(row(130), row(134, cal = MINE, originalId = 130, deleted = true)), listOf(entry(130, state)))
            assertEquals("$state", listOf(130L, 134L), gone.skipped.map { it.id })
            assertTrue("$state", gone.upload.isEmpty())
        }
    }

    @Test
    fun `in the run that holds a series, its exception in an own calendar stays out with it`() {
        // The first run that sees the moved parent holds it: the exception in MINE went up in that very run, before any
        // answer, and the entry then went as synced.
        val p = plan(listOf(row(140, cal = MINE, originalId = 141, mutators = CALENDAR_APP), row(141, uid = "s141@example.test")))
        assertEquals(listOf(141L), p.newHolds.map { it.eventId })
        assertEquals(setOf(140L, 141L), p.skipped.map { it.id }.toSet())
        assertTrue(p.upload.isEmpty())
        // The exception of a split clone that follows a stored series, sitting in an own calendar, stays out too.
        val clone = plan(
            listOf(row(150, uid = "u150@example.test", mutators = CALENDAR_APP), row(151, cal = MINE, originalId = 150, mutators = CALENDAR_APP)),
            listOf(entry(149, State.KEPT, uid = "u150@example.test")),
        )
        assertEquals(setOf(150L, 151L), clone.skipped.map { it.id }.toSet())
        assertTrue(clone.upload.isEmpty())
    }

    @Test
    fun `an exception of a published series stays apart where it sits in another calendar, and goes up in the series' own`() {
        val p = plan(
            listOf(
                row(160),
                row(161, cal = MINE, originalId = 160, mutators = CALENDAR_APP),
                row(162, originalId = 160, mutators = CALENDAR_APP),
            ),
            listOf(entry(160, State.PUBLISHED)),
        )
        assertEquals(listOf(160L, 162L), p.upload.map { it.id })
        // In MINE it would create the series a second time.
        assertEquals(listOf(161L), p.apart.map { it.id })
        assertTrue(p.skipped.isEmpty())
    }

    @Test
    fun `an exception whose parent is no held series uploads as before, wherever it sits`() {
        // Phone-only own events whose parent an app moved between own calendars: nothing is held, and both go up.
        val own = plan(listOf(row(170, cal = MINE, mutators = CALENDAR_APP), row(171, cal = 99L, originalId = 170, mutators = CALENDAR_APP)))
        assertEquals(listOf(170L, 171L), own.upload.map { it.id })
        assertTrue(own.skipped.isEmpty() && own.apart.isEmpty())
        // A held series in another calendar row of the same id does not count: entries are keyed by row and calendar.
        val elsewhere = plan(listOf(row(172, cal = MINE, mutators = CALENDAR_APP), row(173, cal = MINE, originalId = 172)), listOf(entry(172)))
        assertEquals(listOf(172L, 173L), elsewhere.upload.map { it.id })
    }

    @Test
    fun `hold, keep, then the series' exception in an own calendar never goes up`() {
        val prefs = FakePrefs()
        val rows = listOf(row(180), row(181, cal = MINE, originalId = 180, mutators = CALENDAR_APP))
        val first = holdOnce(prefs, rows)
        assertEquals(listOf(180L), first.newHolds.map { it.eventId })
        assertTrue(first.upload.isEmpty())
        val held = stored(prefs)
        assertTrue(NewEventHoldStore.transition(prefs, account, held, held.copy(state = State.KEPT), commit = true))
        val next = holdOnce(prefs, rows)
        assertEquals(listOf(180L, 181L), next.skipped.map { it.id })
        assertTrue(next.upload.isEmpty())
    }

    @Test
    fun `an exception of one of Proton's events goes up as before, wherever its parent row sits`() {
        // A Proton series in the shared calendar: an app moved its parent 50 alone to the own calendar, and a date of it
        // changed in the shared one, where the series lives on Proton. 2.1.0 sent that change there, and so does this.
        val moved = mapOf(50L to MINE)
        val ofProton = listOf(
            row(51, syncId = "proton-51", originalId = 50, originalSyncId = "proton-50", mutators = CALENDAR_APP) to setOf(50L, 51L),
            // Changed on the phone: the provider copied the parent's Proton id into it.
            row(52, originalId = 50, originalSyncId = "proton-50") to emptySet(),
            row(53, originalId = 50) to setOf(53L),
            row(54, syncId = "proton-54", originalId = 50) to emptySet(),
        )
        for ((r, mapped) in ofProton) {
            assertFalse("${r.id}", NewEventHoldRules.needsParentCalendar(r, decisions, mapped))
            val p = NewEventHoldRules.plan(listOf(r), emptyList(), decisions, mapped, ::verdictOf, NOW, parentCalendars = moved)
            assertEquals("${r.id}", listOf(r.id), p.upload.map { it.id })
            assertTrue("${r.id}", p.apart.isEmpty())
        }
        // A tombstone made on this phone is only removed on the phone: the upload takes it, and sends nothing.
        val tombstone = row(55, originalId = 50, deleted = true)
        assertFalse(NewEventHoldRules.needsParentCalendar(tombstone, decisions, emptySet()))
        val gone = NewEventHoldRules.plan(listOf(tombstone), emptyList(), decisions, emptySet(), ::verdictOf, NOW, moved)
        assertEquals(listOf(55L), gone.upload.map { it.id })
        // The rule: a live exception made on this phone, in a calendar that holds or is undecided, and nothing else.
        assertTrue(NewEventHoldRules.needsParentCalendar(row(56, originalId = 50), decisions, emptySet()))
        assertTrue(NewEventHoldRules.needsParentCalendar(row(56, originalId = 50), mapOf(SHARED to HoldDecision.UNDECIDED), emptySet()))
        assertFalse(NewEventHoldRules.needsParentCalendar(row(56, cal = MINE, originalId = 50), decisions, emptySet()))
        assertFalse(NewEventHoldRules.needsParentCalendar(row(56, cal = 99L, originalId = 50), decisions, emptySet()))
        assertFalse(NewEventHoldRules.needsParentCalendar(row(57), decisions, emptySet()))
    }

    @Test
    fun `a changed date made on this phone goes up where its parent lives on Proton, and stays apart elsewhere where a shared calendar is involved`() {
        val own2 = 4L
        val decisions = this.decisions + (own2 to HoldDecision.NO_HOLD)
        val homes = NewEventHoldRules.homesOf(
            mapOf(200L to setOf("cal-mine"), 210L to setOf("cal-shared")),
            listOf("cal-mine" to MINE, "cal-shared" to SHARED, "cal-other" to OTHER_SHARED, "cal-own2" to own2),
        )
        fun planned(r: Row, mapped: Set<Long> = setOf(200L, 210L)) =
            NewEventHoldRules.plan(listOf(r), emptyList(), decisions, mapped, ::verdictOf, NOW, parentHomes = homes)
        // The series went up from MINE, where an app had moved its parent alone; its changed date stayed in another shared
        // calendar, where it would put the series a second time. The provider copied the parent's _SYNC_ID into it, so
        // the moved-parent rule no longer sees it.
        val stray = row(201, cal = OTHER_SHARED, originalId = 200, originalSyncId = "proton-200", mutators = CALENDAR_APP)
        assertFalse(NewEventHoldRules.needsParentCalendar(stray, decisions, setOf(200L)))
        assertTrue(NewEventHoldRules.livesApart(stray, homes, decisions, setOf(200L)))
        assertEquals(listOf(201L), planned(stray).apart.map { it.id })
        assertTrue(planned(stray).upload.isEmpty())
        // The parent in a shared calendar involves it too: a date left in an own calendar stays apart (a published series).
        assertEquals(listOf(212L), planned(row(212, cal = MINE, originalId = 210, originalSyncId = "proton-210")).apart.map { it.id })
        // An undecided calendar counts as one that holds.
        assertTrue(NewEventHoldRules.livesApart(stray, homes, decisions + (OTHER_SHARED to HoldDecision.UNDECIDED), setOf(200L)))
        // Where the series lives it goes up, wherever its parent's row sits: a series of SHARED whose parent row an app
        // moved alone to MINE, and one of MINE whose parent row an app moved alone into SHARED.
        assertEquals(listOf(211L), planned(row(211, originalId = 210, originalSyncId = "proton-210")).upload.map { it.id })
        assertEquals(listOf(202L), planned(row(202, cal = MINE, originalId = 200, originalSyncId = "proton-200")).upload.map { it.id })
        // Between the user's own calendars, mapped or not, as in 2.1.0.
        assertEquals(listOf(203L), planned(row(203, cal = own2, originalId = 200, originalSyncId = "proton-200")).upload.map { it.id })
        assertEquals(listOf(204L), planned(row(204, cal = 99L, originalId = 200, originalSyncId = "proton-200")).upload.map { it.id })
        // A Proton event of its own (a _SYNC_ID or a mapping), a tombstone, or a parent without a mapping: as before.
        assertEquals(listOf(205L), planned(stray.copy(id = 205, syncId = "proton-205")).upload.map { it.id })
        assertEquals(listOf(206L), planned(stray.copy(id = 206), mapped = setOf(200L, 206L)).upload.map { it.id })
        assertEquals(listOf(207L), planned(stray.copy(id = 207, deleted = true)).upload.map { it.id })
        assertEquals(listOf(208L), planned(row(208, cal = OTHER_SHARED, originalId = 220, originalSyncId = "proton-220")).upload.map { it.id })
    }

    @Test
    fun `a parent's homes are the rows its Proton calendars map to`() {
        val rows = listOf("cal-a" to 3L, "cal-a" to 4L, "cal-b" to 7L)
        assertEquals(
            mapOf(1L to setOf(3L, 4L), 2L to setOf(3L, 4L, 7L), 5L to emptySet<Long>()),
            NewEventHoldRules.homesOf(mapOf(1L to setOf("cal-a"), 2L to setOf("cal-a", "cal-b"), 5L to setOf("cal-gone")), rows),
        )
        assertTrue(NewEventHoldRules.homesOf(emptyMap(), rows).isEmpty())
        // A parent whose Proton calendar no row maps to any more lives nowhere on this phone: a date of it in a calendar
        // that holds stays apart.
        assertTrue(
            NewEventHoldRules.livesApart(row(6, originalId = 5, originalSyncId = "proton-5"), mapOf(5L to emptySet<Long>()), decisions, setOf(5L))
        )
    }

    @Test
    fun `Move is offered only while every changed date of the series sits in its own calendar or the target`() {
        assertTrue(NewEventHoldRules.moveKeepsTogether(SHARED, MINE, emptySet()))
        assertTrue(NewEventHoldRules.moveKeepsTogether(SHARED, MINE, setOf(SHARED)))
        // An app moved the parent alone out of the default calendar: Move brings the series back together there.
        assertTrue(NewEventHoldRules.moveKeepsTogether(SHARED, MINE, setOf(SHARED, MINE)))
        // Left in a third calendar, mapped or not: Move's own assert would refuse it.
        assertFalse(NewEventHoldRules.moveKeepsTogether(SHARED, MINE, setOf(OTHER_SHARED)))
        assertFalse(NewEventHoldRules.moveKeepsTogether(SHARED, MINE, setOf(SHARED, 99L)))
    }

    @Test
    fun `hold, publish, then the series' changed date in an own calendar stays apart after the series went up`() {
        val prefs = FakePrefs()
        val parent = row(190)
        val date = row(191, cal = MINE, originalId = 190, mutators = CALENDAR_APP)
        assertEquals(listOf(190L), holdOnce(prefs, listOf(parent, date)).newHolds.map { it.eventId })
        val held = stored(prefs)
        assertTrue(NewEventHoldStore.transition(prefs, account, held, held.copy(state = State.PUBLISHED), commit = true))
        // The run that publishes: the series goes up in SHARED, its date in MINE stays apart.
        val publishing = holdOnce(prefs, listOf(parent, date))
        assertEquals(listOf(190L), publishing.upload.map { it.id })
        assertEquals(listOf(191L), publishing.apart.map { it.id })
        // The parent went up: clean (no longer among the rows), with a _SYNC_ID and a mapping in SHARED's Proton calendar,
        // so the sweep drops its entry; the provider copied its _SYNC_ID into the date's ORIGINAL_SYNC_ID.
        val present = mapOf(190L to PresentRow(SHARED, deleted = false, dirty = false, syncId = "proton-190"))
        NewEventHoldStore.drop(prefs, account, NewEventHoldRules.staleEntries(NewEventHoldStore.snapshot(prefs, account), present, setOf(190L)))
        assertTrue(NewEventHoldStore.snapshot(prefs, account).isEmpty())
        // The next run: no entry and no parent row to go by, only where the parent lives on Proton. A changed date would
        // land in MINE as a second copy, a cancelled one would send the parent's update to MINE's calendar.
        val homes = NewEventHoldRules.homesOf(mapOf(190L to setOf("cal-shared")), listOf("cal-shared" to SHARED, "cal-mine" to MINE))
        val next = NewEventHoldRules.plan(
            listOf(date.copy(originalSyncId = "proton-190")), NewEventHoldStore.snapshot(prefs, account), decisions, setOf(190L),
            ::verdictOf, NOW, parentHomes = homes,
        )
        assertEquals(listOf(191L), next.apart.map { it.id })
        assertTrue(next.upload.isEmpty())
    }

    // ---- plan: a series that never reached Proton, its parent row in a shared calendar (owner, 2026-11-22, L) ----

    @Test
    fun `a calendar app's main event in a shared calendar keeps its stray changed and cancelled dates out wherever they sit`() {
        // The owner's decision L of 2026-11-22. A calendar app, which is not held, moved only the parent 400 of a phone-only
        // series into SHARED; its changed date 401 and its cancelled date 402 stayed in MINE (the plan reads no STATUS, so
        // both are live exceptions to it). In the run that uploads the parent they went up too, the cancelled one as the
        // whole series, read by id, in MINE (uploadCancelledOccurrenceParent), and the first upload decided where it landed.
        val rows = listOf(
            row(400, mutators = CALENDAR_APP),
            row(401, cal = MINE, originalId = 400, mutators = CALENDAR_APP), // a changed date
            row(402, cal = MINE, originalId = 400, mutators = CALENDAR_APP), // a cancelled one
            row(403, cal = 99L, originalId = 400, mutators = CALENDAR_APP), // a date in an unmapped calendar
            row(404, cal = MINE, originalId = 400, syncId = "proton-404", mutators = CALENDAR_APP), // a Proton event of its own
            row(405, originalId = 400, mutators = CALENDAR_APP), // a date in the parent's own calendar goes up with it
            row(406, cal = MINE, originalId = 400, deleted = true), // a tombstone sends nothing, and goes as before
        )
        val p = plan(rows, mapped = setOf(404L))
        assertEquals(listOf(400L, 405L, 406L), p.upload.map { it.id })
        assertEquals(listOf(401L, 402L, 403L, 404L), p.apart.map { it.id })
        assertTrue(p.skipped.isEmpty() && p.undecided.isEmpty() && p.newHolds.isEmpty())
        // In another calendar that holds, the moved-parent rule keeps such a date out, as before.
        val other = plan(listOf(row(400, mutators = CALENDAR_APP), row(407, cal = OTHER_SHARED, originalId = 400, mutators = CALENDAR_APP)))
        assertEquals(listOf(400L), other.upload.map { it.id })
        assertEquals(listOf(407L), other.apart.map { it.id })
        // A share without the right to write holds nothing: its new event goes up, to be refused there, and its dates
        // elsewhere stay out all the same.
        val viewOnly = 12L
        val v = NewEventHoldRules.plan(
            listOf(row(410, cal = viewOnly), row(411, cal = MINE, originalId = 410, mutators = CALENDAR_APP)),
            emptyList(), decisions + (viewOnly to HoldDecision.NO_HOLD), emptySet(), ::verdictOf, NOW,
            sharedCalendars = setOf(viewOnly),
        )
        assertEquals(listOf(410L), v.upload.map { it.id })
        assertEquals(listOf(411L), v.apart.map { it.id })
        // A parent that is no row of this run (clean on the phone): read where it sits, in a calendar undecided this run,
        // or not read at all (the read failed), its date waits the same way.
        val alone = listOf(row(412, cal = MINE, originalId = 413, mutators = CALENDAR_APP))
        val undecided = NewEventHoldRules.plan(
            alone, emptyList(), decisions + (SHARED to HoldDecision.UNDECIDED), emptySet(), ::verdictOf, NOW,
            parentCalendars = mapOf(413L to SHARED),
        )
        assertEquals(listOf(412L), undecided.apart.map { it.id })
        val unread = NewEventHoldRules.plan(
            alone, emptyList(), decisions, emptySet(), ::verdictOf, NOW, parentCalendars = mapOf(413L to NewEventHoldRules.UNREAD_CALENDAR),
        )
        assertEquals(listOf(412L), unread.apart.map { it.id })
        assertTrue(unread.upload.isEmpty())
    }

    @Test
    fun `hold, publish, Proton refuses, and the series' dates in an own calendar still stay on the phone`() {
        // The owner's decision L of 2026-11-22. An app that is not a calendar app moved only the parent 420 of a phone-only
        // series with guests into SHARED; its changed date 421 and its cancelled date 422 stayed in MINE.
        val prefs = FakePrefs()
        val parent = row(420, uid = "s420@example.test")
        val dates = listOf(
            row(421, cal = MINE, originalId = 420, mutators = CALENDAR_APP),
            row(422, cal = MINE, originalId = 420, mutators = CALENDAR_APP),
        )
        val first = holdOnce(prefs, listOf(parent) + dates)
        assertEquals(listOf(420L), first.newHolds.map { it.eventId })
        assertEquals(listOf(420L, 421L, 422L), first.skipped.map { it.id })
        val held = stored(prefs)
        assertTrue(NewEventHoldStore.transition(prefs, account, held, held.copy(state = State.PUBLISHED), commit = true))
        // Run 1, the one that publishes: the parent goes up in SHARED, its dates stay apart.
        val publishing = holdOnce(prefs, listOf(parent) + dates)
        assertEquals(listOf(420L), publishing.upload.map { it.id })
        assertEquals(listOf(421L, 422L), publishing.apart.map { it.id })
        // Proton refuses the create for good (guests, on a calendar the user does not own): the upload clears the parent's
        // DIRTY and keeps it on this phone, with no _SYNC_ID and no mapping, and the sweep drops the published entry.
        val present = mapOf(420L to PresentRow(SHARED, deleted = false, dirty = false, syncId = null))
        NewEventHoldStore.drop(prefs, account, NewEventHoldRules.staleEntries(NewEventHoldStore.snapshot(prefs, account), present, emptySet()))
        assertTrue(NewEventHoldStore.snapshot(prefs, account).isEmpty())
        // Run 2: the parent is no row of it, and nothing holds it any more. The sync reads where it sits for both dates
        // (parentCalendarsOf), and they stay apart, as in every later run while the parent sits there. They used to go up
        // in MINE from here on, the cancelled one as the whole series with its guests.
        for (r in dates) assertTrue("${r.id}", NewEventHoldRules.parentCalendarCounts(r, decisions, emptySet()))
        val next = NewEventHoldRules.plan(
            dates, NewEventHoldStore.snapshot(prefs, account), decisions, emptySet(), ::verdictOf, NOW,
            parentCalendars = mapOf(420L to SHARED),
        )
        assertEquals(listOf(421L, 422L), next.apart.map { it.id })
        assertTrue(next.upload.isEmpty() && next.skipped.isEmpty() && next.newHolds.isEmpty())
        // Moved back into an own calendar in a calendar app, the series goes up there with its dates, as in 2.1.0.
        val home = NewEventHoldRules.plan(
            listOf(parent.copy(calendarId = MINE, mutators = CALENDAR_APP)) + dates, NewEventHoldStore.snapshot(prefs, account),
            decisions, emptySet(), ::verdictOf, NOW,
        )
        assertEquals(listOf(420L, 421L, 422L), home.upload.map { it.id })
        assertTrue(home.apart.isEmpty())
    }

    @Test
    fun `a series whose main event sits in an own calendar goes up with its dates wherever they sit, as before`() {
        val own2 = 4L
        val subscribed = 13L
        val decisions = this.decisions + (own2 to HoldDecision.NO_HOLD) + (subscribed to HoldDecision.NO_HOLD)
        val shared = setOf(SHARED, OTHER_SHARED)
        // In this run: the parent 430 in MINE, its dates in another own calendar and in an unmapped one.
        val p = NewEventHoldRules.plan(
            listOf(
                row(430, cal = MINE, mutators = CALENDAR_APP),
                row(431, cal = own2, originalId = 430, mutators = CALENDAR_APP),
                row(432, cal = 99L, originalId = 430, mutators = CALENDAR_APP),
            ),
            emptyList(), decisions, emptySet(), ::verdictOf, NOW, sharedCalendars = shared,
        )
        assertEquals(listOf(430L, 431L, 432L), p.upload.map { it.id })
        assertTrue(p.apart.isEmpty())
        // No row of this run, read where it sits: an own calendar, a subscribed one (not Type 0), one without a decision
        // (another app's, say; one of another account of this app counts as not read, see parentCalendar), or gone.
        for (parentCal in listOf(MINE, subscribed, 98L, null)) {
            val q = NewEventHoldRules.plan(
                listOf(row(433, cal = own2, originalId = 434, mutators = CALENDAR_APP)), emptyList(), decisions, emptySet(),
                ::verdictOf, NOW, parentCalendars = listOfNotNull(parentCal?.let { 434L to it }).toMap(), sharedCalendars = shared,
            )
            assertEquals("$parentCal", listOf(433L), q.upload.map { it.id })
            assertTrue("$parentCal", q.apart.isEmpty())
        }
        // A series that reached Proton is not this rule's: where its mapping says it lives decides (livesApart).
        assertFalse(
            NewEventHoldRules.apartFromSharedParent(
                row(435, cal = MINE, originalId = 436, originalSyncId = "proton-436"), emptyMap(), mapOf(436L to SHARED),
                decisions, shared, setOf(436L),
            )
        )
        assertFalse(
            NewEventHoldRules.apartFromSharedParent(
                row(438, cal = MINE, originalId = 437), mapOf(437L to row(437, syncId = "proton-437")), emptyMap(),
                decisions, shared, emptySet(),
            )
        )
    }

    @Test
    fun `a calendar shared with the account is one whose member of ours is not the owner, whatever it may write`() {
        val addresses = setOf("user@example.net", "alias@example.test")
        val owner = member("colleague@example.org", OWNS)
        for (permissions in listOf(EDIT, VIEW, 0)) {
            assertTrue("$permissions", NewEventHoldRules.sharedWithAccount(0, listOf(owner, member("Alias@Example.test", permissions)), addresses))
        }
        // The account's own calendar, one with no member of ours (undecided), and a subscribed or holiday one: no.
        assertFalse(NewEventHoldRules.sharedWithAccount(0, listOf(member("user@example.net", OWNS)), addresses))
        assertFalse(NewEventHoldRules.sharedWithAccount(0, listOf(owner), addresses))
        assertFalse(NewEventHoldRules.sharedWithAccount(0, null, addresses))
        for (type in listOf(1, 2)) {
            assertFalse("$type", NewEventHoldRules.sharedWithAccount(type, listOf(owner, member("user@example.net", EDIT)), addresses))
        }
        // Every calendar that holds is one.
        val holds = listOf(owner, member("user@example.net", EDIT))
        assertEquals(HoldDecision.HOLD, NewEventHoldRules.holdDecision(0, holds, addresses))
        assertTrue(NewEventHoldRules.sharedWithAccount(0, holds, addresses))
    }

    @Test
    fun `the sync reads where the parent sits for every live date of a parent that never reached Proton`() {
        // CalendarSyncAdapter.parentCalendarsOf reads the parent rows not in the run for these (NewEventHoldWiringTest).
        for (cal in listOf(MINE, 99L, SHARED)) {
            assertTrue("$cal", NewEventHoldRules.parentCalendarCounts(row(440, cal = cal, originalId = 441), decisions, emptySet()))
        }
        // One with a Proton event of its own too: a cancelled one goes up as its parent.
        assertTrue(NewEventHoldRules.parentCalendarCounts(row(440, cal = MINE, originalId = 441, syncId = "proton-440"), decisions, setOf(440L)))
        // Not a tombstone, a date whose parent has a mapping or whose ORIGINAL_SYNC_ID names one, or a row without a parent.
        assertFalse(NewEventHoldRules.parentCalendarCounts(row(440, cal = MINE, originalId = 441, deleted = true), decisions, emptySet()))
        assertFalse(NewEventHoldRules.parentCalendarCounts(row(440, cal = MINE, originalId = 441), decisions, setOf(441L)))
        assertFalse(NewEventHoldRules.parentCalendarCounts(row(440, cal = MINE, originalId = 441, originalSyncId = "proton-441"), decisions, emptySet()))
        assertFalse(NewEventHoldRules.parentCalendarCounts(row(440, cal = MINE), decisions, emptySet()))
        // And every row the moved-parent rule needs, one whose parent has a mapping too.
        assertTrue(NewEventHoldRules.needsParentCalendar(row(440, originalId = 441), decisions, setOf(441L)))
        assertTrue(NewEventHoldRules.parentCalendarCounts(row(440, originalId = 441), decisions, setOf(441L)))
    }

    @Test
    fun `a parent row in another account of this app keeps its dates out, as one not read`() {
        // The owner's decision L of 2026-11-22 keeps the dates out wherever they are. An app moved only the parent 451 of
        // a phone-only series into a calendar of another account of this app, and its date 450 stayed in MINE. Only that
        // account's sync reads the calendar's members, so this one cannot tell whether it is shared there: the parent
        // counts as not read (CalendarSyncAdapter.parentCalendarsOf reads its account, NewEventHoldWiringTest). The same
        // holds the other way round, for a date in the other account whose parent row sits in one of this account's.
        val ours = "com.example.sync"
        val otherApp = "com.example.othersync"
        val other = "other@example.test"
        val elsewhere = 21L
        assertEquals(NewEventHoldRules.UNREAD_CALENDAR, NewEventHoldRules.parentCalendar(elsewhere, other, ours, account, ours))
        // A calendar of this account, or of another app, is the calendar the row sits in.
        assertEquals(SHARED, NewEventHoldRules.parentCalendar(SHARED, account, ours, account, ours))
        assertEquals(elsewhere, NewEventHoldRules.parentCalendar(elsewhere, other, otherApp, account, ours))
        assertEquals(elsewhere, NewEventHoldRules.parentCalendar(elsewhere, account, otherApp, account, ours))
        assertEquals(elsewhere, NewEventHoldRules.parentCalendar(elsewhere, null, null, account, ours))

        val date = row(450, cal = MINE, originalId = 451, mutators = CALENDAR_APP)
        assertTrue(NewEventHoldRules.parentCalendarCounts(date, decisions, emptySet()))
        fun planWith(parentCalendar: Long) = NewEventHoldRules.plan(
            listOf(date), emptyList(), decisions, emptySet(), ::verdictOf, NOW,
            parentCalendars = mapOf(451L to parentCalendar), sharedCalendars = setOf(SHARED, OTHER_SHARED),
        )
        val p = planWith(NewEventHoldRules.parentCalendar(elsewhere, other, ours, account, ours))
        assertEquals(listOf(450L), p.apart.map { it.id })
        assertTrue(p.upload.isEmpty())
        // In another app's calendar the parent is not this rule's, and the date goes up as before.
        assertEquals(listOf(450L), planWith(NewEventHoldRules.parentCalendar(elsewhere, other, otherApp, account, ours)).upload.map { it.id })
        // In a calendar that holds, the moved-parent rule keeps such a date out as before, the parent's account aside.
        val held = row(452, originalId = 453)
        assertEquals(
            listOf(452L),
            NewEventHoldRules.plan(
                listOf(held), emptyList(), decisions, emptySet(), ::verdictOf, NOW,
                parentCalendars = mapOf(453L to NewEventHoldRules.parentCalendar(elsewhere, other, ours, account, ours)),
            ).apart.map { it.id }
        )
        // Once the other account's upload sent the parent, the provider copies its _SYNC_ID into the date's
        // ORIGINAL_SYNC_ID, and the date no longer asks where its parent sits.
        assertFalse(NewEventHoldRules.parentCalendarCounts(date.copy(originalSyncId = "proton-451"), decisions, emptySet()))
    }

    @Test
    fun `a refused Publish keeps its stray dates on the phone also once Proton stops listing the shared calendar`() {
        // The owner's decision of 2026-11-22. An app that is not a calendar app moved only the parent 460
        // of a phone-only series with guests into SHARED, a colleague's calendar; its changed date 461 and its cancelled
        // date 462 stayed in MINE. The user pressed Publish, and Proton refused it for good.
        val prefs = FakePrefs()
        val parent = row(460, uid = "s460@example.test")
        val dates = listOf(
            row(461, cal = MINE, originalId = 460, mutators = CALENDAR_APP),
            row(462, cal = MINE, originalId = 460, mutators = CALENDAR_APP),
        )
        assertEquals(listOf(460L), holdOnce(prefs, listOf(parent) + dates).newHolds.map { it.eventId })
        val held = stored(prefs)
        assertTrue(NewEventHoldStore.transition(prefs, account, held, held.copy(state = State.PUBLISHED), commit = true))
        val publishing = holdOnce(prefs, listOf(parent) + dates)
        assertEquals(listOf(460L), publishing.upload.map { it.id })
        assertEquals(listOf(461L, 462L), publishing.apart.map { it.id })
        // Refused: the upload clears the parent's DIRTY, it keeps no _SYNC_ID and no mapping, and the sweep drops the entry.
        val present = mapOf(460L to PresentRow(SHARED, deleted = false, dirty = false, syncId = null))
        NewEventHoldStore.drop(prefs, account, NewEventHoldRules.staleEntries(NewEventHoldStore.snapshot(prefs, account), present, emptySet()))
        assertTrue(NewEventHoldStore.snapshot(prefs, account).isEmpty())
        // From here on the parent is no row of the run. parentCalendarsOf reads where it sits, and from the row's account
        // that SHARED is a calendar of this account (inAccount; NewEventHoldWiringTest pins the read).
        fun next(decisions: Map<Long, HoldDecision>, shared: Set<Long>) = NewEventHoldRules.plan(
            dates, NewEventHoldStore.snapshot(prefs, account), decisions, emptySet(), ::verdictOf, NOW,
            parentCalendars = mapOf(460L to SHARED), sharedCalendars = shared, accountCalendars = setOf(SHARED),
        )
        // Run 1: Proton still lists SHARED, shared with the account: the dates stay apart (decision L).
        val listed = next(decisions, setOf(SHARED, OTHER_SHARED))
        assertEquals(listOf(461L, 462L), listed.apart.map { it.id })
        assertTrue(listed.upload.isEmpty())
        // Run 2: the colleague stopped sharing it, so Proton no longer lists SHARED: no decision, and not among the shared
        // calendars, while its mapping is still there (the upload runs before the calendar's removal, which may also wait
        // for the user's answer). The dates stay apart all the same. They used to go up in MINE from here, the cancelled
        // one as the whole series with its guests, and the parent row the user was told stays on the phone got a Proton id.
        val unlisted = decisions - SHARED
        val stopped = next(unlisted, setOf(OTHER_SHARED))
        assertEquals(listOf(461L, 462L), stopped.apart.map { it.id })
        assertTrue(stopped.upload.isEmpty() && stopped.skipped.isEmpty() && stopped.newHolds.isEmpty())
        // Run 3 and every later one: the removal ran and the user kept the events, so the phone keeps SHARED's row for the
        // removed calendar (its kept-for marker) with no mapping. To the plan that is the same: a calendar of this account
        // without a decision this run, and the dates stay apart while the parent sits there.
        val kept = next(unlisted, setOf(OTHER_SHARED))
        assertEquals(listOf(461L, 462L), kept.apart.map { it.id })
        assertTrue(kept.upload.isEmpty())
        // A calendar app's later edit of the parent in the kept row makes it a row of the run again: it goes to the upload,
        // which has no calendar for it there, and its dates stay apart with it.
        val touched = NewEventHoldRules.plan(
            listOf(parent.copy(mutators = CALENDAR_APP)) + dates, NewEventHoldStore.snapshot(prefs, account), unlisted, emptySet(),
            ::verdictOf, NOW, sharedCalendars = setOf(OTHER_SHARED),
        )
        assertEquals(listOf(460L), touched.upload.map { it.id })
        assertEquals(listOf(461L, 462L), touched.apart.map { it.id })
        // So does a never-synced parent's tombstone there (a provider that keeps the dates live), as in a shared calendar.
        val tombstone = NewEventHoldRules.plan(
            listOf(parent.copy(deleted = true)) + dates, emptyList(), unlisted, emptySet(), ::verdictOf, NOW,
            sharedCalendars = setOf(OTHER_SHARED),
        )
        assertEquals(listOf(460L), tombstone.upload.map { it.id })
        assertEquals(listOf(461L, 462L), tombstone.apart.map { it.id })
    }

    @Test
    fun `the user's own listed calendars and another app's still let a series' dates go up as before`() {
        // The owner's answer of 2026-11-22 covers a calendar of this account without a decision this run only.
        val own2 = 4L
        val otherApp = 98L
        val unlisted = 99L
        val decisions = this.decisions + (own2 to HoldDecision.NO_HOLD)
        val shared = setOf(SHARED, OTHER_SHARED)
        val date = row(465, cal = own2, originalId = 466, mutators = CALENDAR_APP)
        fun planWith(parentCal: Long, accountCalendars: Set<Long>) = NewEventHoldRules.plan(
            listOf(date), emptyList(), decisions, emptySet(), ::verdictOf, NOW,
            parentCalendars = mapOf(466L to parentCal), sharedCalendars = shared, accountCalendars = accountCalendars,
        )
        // The parent in one of the user's own calendars, which Proton lists: it has a decision, so the date goes up there
        // as in 2.1.0, a cancelled one as the whole series.
        val own = planWith(MINE, setOf(MINE))
        assertEquals(listOf(465L), own.upload.map { it.id })
        assertTrue(own.apart.isEmpty())
        // In another app's calendar, which the parent row's account tells apart: not this rule's, as before.
        assertEquals(listOf(465L), planWith(otherApp, emptySet()).upload.map { it.id })
        // In a calendar of this account without a decision: the date waits.
        val waiting = planWith(unlisted, setOf(unlisted))
        assertEquals(listOf(465L), waiting.apart.map { it.id })
        assertTrue(waiting.upload.isEmpty())
        // A date that sits with its parent in such a calendar is no stray date: it goes to the upload, which has no calendar
        // for it there and leaves it on the phone.
        val together = NewEventHoldRules.plan(
            listOf(row(467, cal = unlisted, originalId = 466, mutators = CALENDAR_APP)), emptyList(), decisions, emptySet(),
            ::verdictOf, NOW, parentCalendars = mapOf(466L to unlisted), sharedCalendars = shared, accountCalendars = setOf(unlisted),
        )
        assertEquals(listOf(467L), together.upload.map { it.id })
        assertTrue(together.apart.isEmpty())
        // A series that reached Proton is not this rule's, wherever its parent row sits; nor is a date's tombstone.
        assertFalse(
            NewEventHoldRules.apartFromSharedParent(
                row(468, cal = MINE, originalId = 469, originalSyncId = "proton-469"), emptyMap(), mapOf(469L to unlisted),
                decisions, shared, emptySet(), setOf(unlisted),
            )
        )
        assertFalse(
            NewEventHoldRules.apartFromSharedParent(
                row(468, cal = MINE, originalId = 469, deleted = true), emptyMap(), mapOf(469L to unlisted),
                decisions, shared, emptySet(), setOf(unlisted),
            )
        )
        assertTrue(
            NewEventHoldRules.apartFromSharedParent(
                row(468, cal = MINE, originalId = 469), emptyMap(), mapOf(469L to unlisted), decisions, shared, emptySet(), setOf(unlisted),
            )
        )
    }

    @Test
    fun `a parent row's calendar is this account's by its account name and type`() {
        // CalendarSyncAdapter.parentCalendarsOf reads each parent row's account with its calendar (NewEventHoldWiringTest).
        val ours = "com.example.sync"
        val otherApp = "com.example.othersync"
        assertTrue(NewEventHoldRules.inAccount(account, ours, account, ours))
        // Another account of this app (whose calendar counts as not read, see parentCalendar), another app's account of the
        // same name, and a row whose account could not be read are not.
        assertFalse(NewEventHoldRules.inAccount("other@example.test", ours, account, ours))
        assertFalse(NewEventHoldRules.inAccount(account, otherApp, account, ours))
        assertFalse(NewEventHoldRules.inAccount(null, null, account, ours))
        assertFalse(NewEventHoldRules.inAccount(account, null, account, ours))
    }

    @Test
    fun `when the upload sends a held parent's tombstone, the question passes to the split's later half`() {
        // A provider that keeps a never-synced parent as a tombstone (AOSP deletes it outright): the kept series 83 is
        // deleted on the phone, and the later half 84 of a "this and following" split, made in a calendar app, stays.
        val prefs = FakePrefs()
        holdOnce(prefs, listOf(row(83, uid = "s83@example.test")))
        val held = stored(prefs)
        assertTrue(NewEventHoldStore.transition(prefs, account, held, held.copy(state = State.KEPT), commit = true))
        val kept = stored(prefs)
        val clone = row(84, uid = "s83@example.test", mutators = CALENDAR_APP)
        val first = plan(listOf(row(83, deleted = true, uid = "s83@example.test"), clone), listOf(kept))
        assertEquals(listOf(83L), first.upload.map { it.id })
        assertEquals(listOf(84L), first.skipped.map { it.id })
        assertEquals(listOf(kept), first.dropEntries)
        // A plain drop left the later half to be judged on its own, by the calendar app that made the split: it went up.
        assertEquals(listOf(84L), plan(listOf(clone)).upload.map { it.id })
        // The adapter settles a dropped entry as the sweep settles a parent that is gone (NewEventHolds.passOnOrDrop with
        // no row): the question passes to the oldest row left with the series' UID.
        val e = first.dropEntries.single()
        assertTrue(NewEventHoldRules.mayPassOn(e, null))
        assertTrue(NewEventHoldStore.passOn(prefs, account, e, NewEventHoldRules.passedOn(e, listOf(84L))!!))
        val next = holdOnce(prefs, listOf(clone))
        assertEquals(listOf(84L), next.skipped.map { it.id })
        assertTrue(next.upload.isEmpty())
        assertEquals(e.copy(eventId = 84L), stored(prefs))
    }

    @Test
    fun `a published series whose calendar lost its mapping waits again`() {
        val published = entry(120, State.PUBLISHED)
        val kept = entry(121, State.KEPT)
        val elsewhere = entry(122, State.PUBLISHED, cal = OTHER_SHARED)
        val entries = listOf(published, kept, elsewhere)
        // SHARED was removed on Proton and stays on the phone, unmapped: only its published series can go nowhere.
        assertEquals(listOf(published), NewEventHoldRules.publishedNowhere(entries, setOf(OTHER_SHARED, MINE)))
        assertTrue(NewEventHoldRules.publishedNowhere(entries, setOf(SHARED, OTHER_SHARED, MINE)).isEmpty())
        // An unmapped calendar has no decision and no upload context: published, the row went to an upload that could
        // never send it, on every sync; waiting again, it stays out and is asked about.
        val unmapped = mapOf(OTHER_SHARED to HoldDecision.HOLD, MINE to HoldDecision.NO_HOLD)
        assertEquals(listOf(120L), plan(listOf(row(120)), listOf(published), unmapped).upload.map { it.id })
        assertEquals(listOf(120L), plan(listOf(row(120)), listOf(published.copy(state = State.WAITING)), unmapped).skipped.map { it.id })
    }

    @Test
    fun `a question passes to a row left with the series' UID when its parent row is deleted or moved away`() {
        val e = entry(83, State.KEPT, uid = "u@example.test")
        val here = PresentRow(SHARED, deleted = false, dirty = true, syncId = null)
        assertTrue(NewEventHoldRules.mayPassOn(e, null))
        assertTrue(NewEventHoldRules.mayPassOn(e, here.copy(calendarId = MINE)))
        // Synced, or still in its calendar: no.
        assertFalse(NewEventHoldRules.mayPassOn(e, here.copy(syncId = "proton-83")))
        assertFalse(NewEventHoldRules.mayPassOn(e, here))
        // Without a UID nothing follows the series by UID.
        assertFalse(NewEventHoldRules.mayPassOn(entry(83, uid = null), null))
        assertFalse(NewEventHoldRules.mayPassOn(entry(83, uid = " "), null))
        // The oldest row left takes it, with the entry's state, time, writer and UID.
        assertEquals(e.copy(eventId = 84), NewEventHoldRules.passedOn(e, listOf(97L, 84L, 83L)))
        assertNull(NewEventHoldRules.passedOn(e, emptyList()))
        assertNull(NewEventHoldRules.passedOn(e, listOf(83L)))
    }

    @Test
    fun `after the parent of a kept series is deleted, the split's later half and its exception stay out`() {
        val prefs = FakePrefs()
        holdOnce(prefs, listOf(row(83, uid = "s83@example.test")))
        val held = stored(prefs)
        assertTrue(NewEventHoldStore.transition(prefs, account, held, held.copy(state = State.KEPT), commit = true))
        // The parent 83 is hard-deleted on the phone (never synced); the clone 84 survives. The sweep passes the question on.
        val kept = stored(prefs)
        assertTrue(NewEventHoldRules.mayPassOn(kept, null))
        val next = NewEventHoldRules.passedOn(kept, listOf(84L))!!
        assertTrue(NewEventHoldStore.passOn(prefs, account, kept, next))
        val p = holdOnce(prefs, listOf(row(84, uid = "s83@example.test", mutators = CALENDAR_APP), row(85, originalId = 84, mutators = CALENDAR_APP)))
        assertEquals(listOf(84L, 85L), p.skipped.map { it.id })
        assertTrue(p.upload.isEmpty())
        assertEquals(State.KEPT, stored(prefs).state)
    }

    @Test
    fun `a prompt's title and calendar name cannot reorder or push away the text around them`() {
        val rlo = Char(0x202E).toString()
        val isolate = Char(0x2066).toString()
        val paragraph = Char(0x2029).toString()
        assertEquals("Dentist booked", NewEventHoldRules.shownText("Dentist${rlo} booked"))
        assertEquals("Dentist booked", NewEventHoldRules.shownText("Dentist\n\tbooked$isolate"))
        assertEquals("a b", NewEventHoldRules.shownText("a${paragraph}b"))
        assertEquals("ab", NewEventHoldRules.shownText("a" + Char(7) + "b"))
        assertNull(NewEventHoldRules.shownText(" \n "))
        assertNull(NewEventHoldRules.shownText(null))
        // Letters and joined emoji stay as they are.
        val family = "Family " + String(Character.toChars(0x1F468)) + Char(0x200D) + String(Character.toChars(0x1F467))
        assertEquals(family, NewEventHoldRules.shownText(family))
    }

    // ---- end to end, through the store ----

    private val account = "user@example.net"

    private fun holdOnce(prefs: FakePrefs, rows: List<Row>): NewEventHoldRules.Plan {
        val p = plan(rows, NewEventHoldStore.snapshot(prefs, account))
        NewEventHoldStore.addHolds(prefs, account, p.newHolds)
        NewEventHoldStore.drop(prefs, account, p.dropEntries)
        return p
    }

    private fun stored(prefs: FakePrefs) = NewEventHoldStore.snapshot(prefs, account).single()

    @Test
    fun `hold, publish, then the next upload sends it`() {
        val prefs = FakePrefs()
        assertEquals(1, holdOnce(prefs, listOf(row(80))).newHolds.size)
        val held = stored(prefs)
        assertTrue(NewEventHoldStore.transition(prefs, account, held, held.copy(state = State.PUBLISHED), commit = true))
        val next = holdOnce(prefs, listOf(row(80)))
        assertEquals(listOf(80L), next.upload.map { it.id })
        assertTrue(next.newHolds.isEmpty())
    }

    @Test
    fun `hold, move, then the next upload sends it from the own calendar`() {
        val prefs = FakePrefs()
        holdOnce(prefs, listOf(row(81)))
        val held = stored(prefs)
        // Move writes the row into the default calendar (its MUTATORS is this app now), then removes the entry.
        assertTrue(NewEventHoldStore.transition(prefs, account, held, null, commit = true))
        val next = holdOnce(prefs, listOf(row(81, cal = MINE, mutators = OWN)))
        assertEquals(listOf(81L), next.upload.map { it.id })
        assertTrue(NewEventHoldStore.snapshot(prefs, account).isEmpty())
    }

    @Test
    fun `hold, keep, then a calendar app's edit still sends nothing`() {
        val prefs = FakePrefs()
        holdOnce(prefs, listOf(row(82)))
        val held = stored(prefs)
        assertTrue(NewEventHoldStore.transition(prefs, account, held, held.copy(state = State.KEPT), commit = true))
        val next = holdOnce(prefs, listOf(row(82, mutators = CALENDAR_APP)))
        assertEquals(listOf(82L), next.skipped.map { it.id })
        assertTrue(next.upload.isEmpty())
    }

    @Test
    fun `keep, then a split with the series' UID keeps the clone out too`() {
        val prefs = FakePrefs()
        holdOnce(prefs, listOf(row(83, uid = "s83@example.test")))
        val held = stored(prefs)
        assertTrue(NewEventHoldStore.transition(prefs, account, held, held.copy(state = State.KEPT), commit = true))
        // AOSP's "this and following" clones the row, UID included, into a new row without ORIGINAL_ID.
        val next = holdOnce(prefs, listOf(row(83, uid = "s83@example.test"), row(84, uid = "s83@example.test", mutators = CALENDAR_APP)))
        assertEquals(listOf(83L, 84L), next.skipped.map { it.id })
        assertTrue(next.newHolds.isEmpty())
    }

    @Test
    fun `keep, then publish, then the next upload sends it`() {
        val prefs = FakePrefs()
        holdOnce(prefs, listOf(row(85)))
        val held = stored(prefs)
        val kept = held.copy(state = State.KEPT)
        assertTrue(NewEventHoldStore.transition(prefs, account, held, kept, commit = true))
        assertTrue(NewEventHoldStore.transition(prefs, account, kept, kept.copy(state = State.PUBLISHED), commit = true))
        assertEquals(listOf(85L), holdOnce(prefs, listOf(row(85))).upload.map { it.id })
    }

    // ---- staleEntries ----

    @Test
    fun `a waiting or kept entry goes on positive evidence only`() {
        for (state in listOf(State.WAITING, State.KEPT)) {
            val e = entry(90, state)
            val here = PresentRow(SHARED, deleted = false, dirty = true, syncId = null)
            assertTrue(NewEventHoldRules.staleEntries(listOf(e), emptyMap(), emptySet()).contains(e))
            assertTrue(NewEventHoldRules.staleEntries(listOf(e), mapOf(90L to here.copy(calendarId = MINE)), emptySet()).contains(e))
            assertTrue(NewEventHoldRules.staleEntries(listOf(e), mapOf(90L to here.copy(syncId = "proton-90")), emptySet()).contains(e))
            assertTrue(NewEventHoldRules.staleEntries(listOf(e), mapOf(90L to here), setOf(90L)).contains(e))
            assertTrue(NewEventHoldRules.staleEntries(listOf(e), mapOf(90L to here), emptySet()).isEmpty())
            // Clean is no evidence for a waiting or kept one.
            assertTrue(NewEventHoldRules.staleEntries(listOf(e), mapOf(90L to here.copy(dirty = false)), emptySet()).isEmpty())
            assertTrue(NewEventHoldRules.staleEntries(listOf(e), mapOf(90L to here.copy(syncId = " ")), emptySet()).isEmpty())
        }
    }

    @Test
    fun `a published entry also goes once its row is clean, and a failed read keeps everything`() {
        val p = entry(91, State.PUBLISHED)
        val here = PresentRow(SHARED, deleted = false, dirty = true, syncId = null)
        assertTrue(NewEventHoldRules.staleEntries(listOf(p), mapOf(91L to here), emptySet()).isEmpty())
        assertEquals(listOf(p), NewEventHoldRules.staleEntries(listOf(p), mapOf(91L to here.copy(dirty = false)), emptySet()))
        assertTrue(NewEventHoldRules.staleEntries(listOf(p, entry(92)), null, emptySet()).isEmpty())
    }

    // ---- movePlan, uidOf, nextUpload ----

    @Test
    fun `Move rewrites only the organizer the provider filled in from the source calendar`() {
        val rows = listOf(
            MoveRow(1, "Alias@Example.test"),
            MoveRow(2, "guest@example.org"),
            MoveRow(3, null),
            MoveRow(4, ""),
        )
        assertEquals(mapOf(1L to "user@example.net"), NewEventHoldRules.movePlan(rows, "alias@example.test", "user@example.net"))
        assertTrue(NewEventHoldRules.movePlan(rows, "alias@example.test", null).isEmpty())
        assertTrue(NewEventHoldRules.movePlan(rows, null, "user@example.net").isEmpty())
    }

    @Test
    fun `a uid is read from a shared-uid or bare-uid entry only`() {
        assertEquals(10045, NewEventHoldRules.uidOf("x.y:10045"))
        assertEquals(10045, NewEventHoldRules.uidOf("10045"))
        assertNull(NewEventHoldRules.uidOf("com.a.b"))
        assertNull(NewEventHoldRules.uidOf("a:b"))
        assertNull(NewEventHoldRules.uidOf("a:-5"))
        assertNull(NewEventHoldRules.uidOf("a:"))
    }

    @Test
    fun `the debug verb's next upload says what the plan and the sweep will do`() {
        val here = PresentRow(SHARED, deleted = false, dirty = true, syncId = null)
        assertEquals("skip (waiting)", NewEventHoldRules.nextUpload(entry(95), mapOf(95L to here), emptySet()))
        assertEquals("skip (kept)", NewEventHoldRules.nextUpload(entry(95, State.KEPT), mapOf(95L to here), emptySet()))
        assertEquals("upload (published)", NewEventHoldRules.nextUpload(entry(95, State.PUBLISHED), mapOf(95L to here), emptySet()))
        assertEquals("dropped at next sync (moved)", NewEventHoldRules.nextUpload(entry(95), mapOf(95L to here.copy(calendarId = MINE)), emptySet()))
        assertEquals("dropped at next sync (gone)", NewEventHoldRules.nextUpload(entry(95), emptyMap(), emptySet()))
        assertEquals("dropped at next sync (mapped)", NewEventHoldRules.nextUpload(entry(95), mapOf(95L to here), setOf(95L)))
        // A series with a UID may live on in a row left with it.
        assertEquals(
            "dropped at next sync (gone), unless a row with its UID is left in that calendar",
            NewEventHoldRules.nextUpload(entry(95, uid = "s95@example.test"), emptyMap(), emptySet())
        )
        // A tombstone goes up, and the plan settles its question the same way.
        val tombstone = here.copy(deleted = true)
        assertEquals("upload (tombstone, removed on this phone)", NewEventHoldRules.nextUpload(entry(95), mapOf(95L to tombstone), emptySet()))
        assertEquals(
            "upload (tombstone, removed on this phone); dropped, unless a row with its UID is left in that calendar",
            NewEventHoldRules.nextUpload(entry(95, uid = "s95@example.test"), mapOf(95L to tombstone), emptySet())
        )
    }
}

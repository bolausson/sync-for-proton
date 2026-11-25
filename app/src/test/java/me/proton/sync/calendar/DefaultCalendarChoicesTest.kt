package me.proton.sync.calendar

import com.google.gson.JsonSyntaxException
import kotlinx.coroutines.runBlocking
import me.proton.sync.api.models.CalendarInfo
import me.proton.sync.api.models.CalendarMember
import me.proton.sync.calendar.DefaultCalendarChoices.Choice
import me.proton.sync.calendar.DefaultCalendarChoices.LeftOut
import me.proton.sync.calendar.DefaultCalendarChoices.SaveOutcome
import me.proton.sync.calendar.DefaultCalendarChoices.Shape
import me.proton.sync.calendar.PrimaryCalendarChooser.Reason
import me.proton.sync.calendar.PrimaryCalendarChooser.Settings
import me.proton.sync.db.entities.CalendarSyncEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

/**
 * What the account card's Default calendar row offers (exactly the primary-calendar chooser's candidates, in its order,
 * with Proton's default checked), how it names a calendar, and how it reads Proton's answers to a change (the same
 * answers through real Retrofit and OkHttp: CalendarUserSettingsWireTest). Made-up calendar names and addresses only.
 */
class DefaultCalendarChoicesTest {

    private companion object {
        const val ACCOUNT = "user@example.test"
        const val ME = "user@example.test"
        const val ALIAS = "alias@example.test"
        const val OWNER = "owner@example.invalid"
        /** An address of the account whose keys did not load. */
        const val MISSING = "missing@example.test"
        val MINE = setOf(ME, ALIAS)

        const val OWNS = 127
        const val EDIT = 16 or 32 or 64
        const val VIEW = 32 or 64
        const val ACTIVE = 1
        const val SELF_DISABLED = 32
    }

    private fun member(
        email: String = ME,
        permissions: Int = OWNS,
        flags: Int? = ACTIVE,
        priority: Int? = null,
        name: String? = null,
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
        name = name,
    )

    private fun calendar(
        id: String,
        type: Int = PrimaryCalendarChooser.TYPE_PERSONAL,
        members: List<CalendarMember> = listOf(member()),
        name: String? = id,
        description: String? = null,
    ) = CalendarInfo(
        id = id, name = name, description = description, color = null, display = 1, type = type, flags = 0, members = members
    )

    private fun owned(id: String, priority: Int? = null, flags: Int? = ACTIVE) =
        calendar(id, members = listOf(member(priority = priority, flags = flags)))

    /** A Type-0 calendar somebody else owns, shared with this account's alias. */
    private fun shared(id: String, permissions: Int = EDIT) =
        calendar(id, members = listOf(member(email = ALIAS, permissions = permissions)))

    private fun mapping(protonId: String, row: Long?, displayName: String = protonId) =
        CalendarSyncEntity(accountName = ACCOUNT, protonCalendarId = protonId, androidCalendarId = row, displayName = displayName)

    private fun counts(shared: Int = 0, external: Int = 0, inactive: Int = 0, notOurs: Int = 0) = mapOf(
        LeftOut.SHARED to shared, LeftOut.SUBSCRIBED_OR_HOLIDAY to external, LeftOut.INACTIVE to inactive, LeftOut.NOT_OURS to notOurs,
    )

    private fun offer(
        calendars: List<CalendarInfo>,
        defaultId: String?,
        mappings: Map<String, CalendarSyncEntity> = emptyMap(),
        addresses: Set<String> = MINE,
    ): DefaultCalendarChoices.Offer {
        val shape = DefaultCalendarChoices.shape(calendars, addresses) as Shape.Candidates
        return DefaultCalendarChoices.offer(shape, calendars, addresses, Settings.Loaded(defaultId), mappings, ACCOUNT)!!
    }

    // ---- shape ----

    @Test
    fun `a list without a personal calendar offers nothing, whatever addresses are loaded`() {
        val calendars = listOf(
            calendar("Feed", type = PrimaryCalendarChooser.TYPE_SUBSCRIPTION),
            calendar("Holidays", type = PrimaryCalendarChooser.TYPE_HOLIDAYS),
        )

        assertEquals(Shape.NoOwnCalendar(counts(external = 2)), DefaultCalendarChoices.shape(calendars, MINE))
        assertEquals(Shape.NoOwnCalendar(counts(external = 2)), DefaultCalendarChoices.shape(calendars, emptySet()))
    }

    @Test
    fun `members of ours but no calendar that qualifies offers nothing`() {
        val calendars = listOf(shared("Team share"), shared("Club", permissions = VIEW), owned("Home", flags = ACTIVE or SELF_DISABLED))

        assertEquals(Shape.NoOwnCalendar(counts(shared = 2, inactive = 1)), DefaultCalendarChoices.shape(calendars, MINE))
    }

    @Test
    fun `which calendars are ours cannot be told without the addresses`() {
        val calendars = listOf(owned("Home"), shared("Team share"))

        val notLoaded = DefaultCalendarChoices.shape(calendars, emptySet())
        assertTrue(notLoaded is Shape.Unknown)
        assertTrue((notLoaded as Shape.Unknown).detail, notLoaded.detail.contains("member of ours"))
        assertTrue(notLoaded.noAddressLoaded)
    }

    @Test
    fun `no address loaded at all is told apart from calendars on addresses that did not load`() {
        // No address at all: the address read failed (the key unlock goes on without it), so another try may work.
        for (calendars in listOf(
            listOf(owned("Home")),
            listOf(shared("Team share"), calendar("Feed", type = PrimaryCalendarChooser.TYPE_SUBSCRIPTION)),
            // A personal calendar that lists no member at all.
            listOf(calendar("Home", members = emptyList())),
        )) {
            val shape = DefaultCalendarChoices.shape(calendars, emptySet())
            assertTrue("$calendars", shape is Shape.Unknown && shape.noAddressLoaded)
        }
        // Addresses loaded, and the only personal calendar lists members on none of them (the chooser's NO_OWN_MEMBER).
        val elsewhere = DefaultCalendarChoices.shape(listOf(calendar("Work", members = listOf(member(email = MISSING)))), MINE)
        assertTrue("$elsewhere", elsewhere is Shape.Unknown && !elsewhere.noAddressLoaded)
        // Addresses loaded, and one personal calendar of several lists members on none of them (PARTIAL_ADDRESSES).
        val partial = DefaultCalendarChoices.shape(listOf(owned("Home"), calendar("Work", members = listOf(member(email = MISSING)))), MINE)
        assertTrue("$partial", partial is Shape.Unknown && !partial.noAddressLoaded)
    }

    @Test
    fun `a personal calendar on an address whose keys did not load never gives a partial list`() {
        // Work may be an own calendar on that address: a list without it, or with Home checked in its place, could
        // change Proton's default to the wrong one.
        val calendars = listOf(owned("Home"), calendar("Work", members = listOf(member(email = MISSING))))

        val partial = DefaultCalendarChoices.shape(calendars, MINE)
        assertTrue(partial is Shape.Unknown)
        assertTrue((partial as Shape.Unknown).detail, partial.detail.contains("address(es)"))
    }

    @Test
    fun `the own active calendars are offered and the rest counted by why`() {
        val calendars = listOf(
            owned("Home"),
            shared("Team share"),
            calendar("Feed", type = PrimaryCalendarChooser.TYPE_SUBSCRIPTION),
            owned("Work"),
            owned("Old", flags = 0),
        )

        val shape = DefaultCalendarChoices.shape(calendars, MINE)
        assertTrue(shape is Shape.Candidates)
        assertEquals(listOf("Home", "Work"), (shape as Shape.Candidates).candidates.map { it.id })
        assertEquals(counts(shared = 1, external = 1, inactive = 1), shape.leftOut)
    }

    @Test
    fun `every group is counted`() {
        val calendars = listOf(
            owned("Home"),
            shared("Team share"),
            shared("Club", permissions = VIEW),
            calendar("Feed", type = PrimaryCalendarChooser.TYPE_SUBSCRIPTION),
            calendar("Holidays", type = PrimaryCalendarChooser.TYPE_HOLIDAYS),
            calendar("Odd", type = 7),
            owned("Old", flags = ACTIVE or SELF_DISABLED),
            owned("Reset", flags = 0),
            // A personal calendar that lists no member at all: not ours as far as anyone can tell.
            calendar("Empty", members = emptyList()),
        )

        assertEquals(
            counts(shared = 2, external = 3, inactive = 2, notOurs = 1),
            (DefaultCalendarChoices.shape(calendars, MINE) as Shape.Candidates).leftOut
        )
        assertEquals(
            "2 shared, 3 subscribed or holiday, 2 inactive, 1 without a member of ours",
            DefaultCalendarChoices.leftOutText(counts(shared = 2, external = 3, inactive = 2, notOurs = 1))
        )
    }

    // ---- offer ----

    @Test
    fun `the choices are exactly the chooser's candidates, in its order`() {
        val calendars = listOf(owned("Work", priority = 2), shared("Team share"), owned("Home", priority = 1), owned("Club", priority = 3))

        val offer = offer(calendars, "Work")
        assertEquals(PrimaryCalendarChooser.candidates(calendars, MINE).map { it.id }, offer.choices.map { it.protonCalendarId })
        assertEquals(listOf("Home", "Work", "Club"), offer.choices.map { it.name })
    }

    @Test
    fun `the checked calendar is the one Proton uses as the default now`() {
        val calendars = listOf(
            owned("Home", priority = 1),
            owned("Work", priority = 2),
            shared("Team share"),
            calendar("Feed", type = PrimaryCalendarChooser.TYPE_SUBSCRIPTION),
            calendar("Holidays", type = PrimaryCalendarChooser.TYPE_HOLIDAYS),
            owned("Old", flags = ACTIVE or SELF_DISABLED),
        )

        val work = offer(calendars, "Work")
        assertEquals(1, work.checkedIndex)
        assertEquals("Work", work.checked.name)
        assertEquals(PrimaryCalendarChooser.Decision.Known("Work", Reason.PROTON_DEFAULT), work.decision)
        // Not set, unknown, shared, subscribed, holiday or disabled: the first offered calendar, as Proton's clients show it.
        for (stored in listOf(null, "gone", "Team share", "Feed", "Holidays", "Old")) {
            val fallback = offer(calendars, stored)
            assertEquals("$stored", 0, fallback.checkedIndex)
            assertEquals("$stored", "Home", fallback.decision.protonCalendarId)
        }
    }

    @Test
    fun `the stored id is Proton's raw value, empty read as not set`() {
        val calendars = listOf(owned("Home"), shared("Team share"))

        assertEquals("Team share", offer(calendars, "Team share").storedId)
        assertEquals("Home", offer(calendars, "Home").storedId)
        assertNull(offer(calendars, "").storedId)
        assertNull(offer(calendars, null).storedId)
    }

    @Test
    fun `our own member is found by address when the first member is somebody else`() {
        val home = calendar(
            "Home", name = null,
            members = listOf(member(email = OWNER, name = "Their name"), member(email = ME, name = "My name")),
        )

        val offer = offer(listOf(home, shared("Team share")), null)
        assertEquals(listOf(Choice("Home", "My name", null)), offer.choices)
    }

    @Test
    fun `each choice carries its row on the phone, or none`() {
        val calendars = listOf(owned("Home"), owned("Work"))

        val offer = offer(calendars, "Home", mapOf("Home" to mapping("Home", 41L)))
        assertEquals(listOf(Choice("Home", "Home", 41L), Choice("Work", "Work", null)), offer.choices)
        assertEquals(setOf("Home", "Work"), offer.liveProtonIds)
    }

    // ---- nameOf ----

    @Test
    fun `the name is what calendar apps show first, then Proton's names, then the sync's own`() {
        val own = member(name = "Member name")
        val full = calendar("cal-1", name = "Proton name", description = "Description")

        assertEquals("Phone name", DefaultCalendarChoices.nameOf(full, own, "Phone name", ACCOUNT))
        assertEquals("Phone name", DefaultCalendarChoices.nameOf(full, own, "$ACCOUNT - Phone name", ACCOUNT))
        assertEquals("Proton name", DefaultCalendarChoices.nameOf(full, own, null, ACCOUNT))
        assertEquals("Description", DefaultCalendarChoices.nameOf(calendar("cal-1", name = null, description = "Description"), own, null, ACCOUNT))
        assertEquals("Member name", DefaultCalendarChoices.nameOf(calendar("cal-1", name = null), own, null, ACCOUNT))
        assertEquals("My Calendar", DefaultCalendarChoices.nameOf(calendar("cal-1", name = null), member(), null, ACCOUNT))
        assertEquals("My Calendar", DefaultCalendarChoices.nameOf(null, null, null, ACCOUNT))
    }

    @Test
    fun `blank names are skipped`() {
        val blank = calendar("cal-1", name = "", description = "  ")

        assertEquals("Member name", DefaultCalendarChoices.nameOf(blank, member(name = "Member name"), " ", ACCOUNT))
        // The prefix alone, as a calendar with an empty name gets it with several accounts.
        assertEquals("Member name", DefaultCalendarChoices.nameOf(blank, member(name = "Member name"), "$ACCOUNT - ", ACCOUNT))
        assertEquals("My Calendar", DefaultCalendarChoices.nameOf(blank, member(name = " "), "", ACCOUNT))
    }

    @Test
    fun `another account's prefix is part of the name`() {
        assertEquals(
            "other@example.test - Home",
            DefaultCalendarChoices.nameOf(calendar("Home"), member(), "other@example.test - Home", ACCOUNT)
        )
    }

    @Test
    fun `with several accounts a calendar the sync named from its Name keeps that name`() {
        assertEquals("Home", DefaultCalendarChoices.nameOf(calendar("cal-1", name = "Home"), member(), "user@example.test - Home", ACCOUNT))
    }

    // ---- needsPut and matchByNamePart ----

    @Test
    fun `only a calendar Proton does not store yet is sent`() {
        assertTrue(DefaultCalendarChoices.needsPut("cal-a", "cal-b"))
        assertTrue(DefaultCalendarChoices.needsPut("cal-a", null))
        assertFalse(DefaultCalendarChoices.needsPut("cal-a", "cal-a"))
    }

    @Test
    fun `a name part picks one offered calendar or none`() {
        val choices = listOf(Choice("c1", "Home", 1), Choice("c2", "Homework", 2), Choice("c3", "Work", null), Choice("c4", "Club", 4))

        // An exact match, ignoring case, wins over the calendars that only contain it.
        assertEquals(0, DefaultCalendarChoices.matchByNamePart(choices, "home"))
        assertEquals(2, DefaultCalendarChoices.matchByNamePart(choices, "WORK"))
        // One partial match.
        assertEquals(3, DefaultCalendarChoices.matchByNamePart(choices, "lu"))
        assertEquals(1, DefaultCalendarChoices.matchByNamePart(choices, "mew"))
        // Two partial matches, none, or no part at all.
        assertNull(DefaultCalendarChoices.matchByNamePart(choices, "or"))
        assertNull(DefaultCalendarChoices.matchByNamePart(choices, "Team"))
        assertNull(DefaultCalendarChoices.matchByNamePart(choices, ""))
        assertNull(DefaultCalendarChoices.matchByNamePart(choices, "  "))
        assertNull(DefaultCalendarChoices.matchByNamePart(choices, null))
        // Two calendars of one name: neither is the only one.
        assertNull(DefaultCalendarChoices.matchByNamePart(listOf(Choice("c1", "Home", 1), Choice("c2", "Home", 2)), "Home"))
    }

    // ---- classifySave and classifySaveFailure ----

    @Test
    fun `Proton's answer to the change`() {
        fun classify(http: Int, code: Int?, echoPresent: Boolean = false, echo: String? = null) =
            DefaultCalendarChoices.classifySave(http, code, echoPresent, echo, "cal-a")

        assertEquals(SaveOutcome.Saved, classify(200, 1000, echoPresent = true, echo = "cal-a"))
        assertEquals(SaveOutcome.Saved, classify(200, 1000))
        // A 2xx with another Code is a refusal, without a check.
        assertEquals(SaveOutcome.Refused(200, 2001), classify(200, 2001))
        for ((http, code) in listOf(400 to 2001, 422 to 2001, 422 to 2061, 422 to 2501, 403 to null, 404 to 2501)) {
            assertEquals("$http $code", SaveOutcome.Refused(http, code), classify(http, code))
        }
        assertEquals(SaveOutcome.Auth, classify(401, null))
        assertEquals(SaveOutcome.Unreachable("HTTP 408"), classify(408, null))
        assertEquals(SaveOutcome.Unreachable("HTTP 429"), classify(429, 2028))
        for (http in listOf(500, 502, 503, 504)) {
            assertEquals("$http", SaveOutcome.Uncertain("HTTP $http"), classify(http, null))
        }
    }

    @Test
    fun `a 2xx that names no change is checked, and counts as a refusal only when Proton kept another default`() {
        fun classify(http: Int, code: Int?, echoPresent: Boolean = false, echo: String? = null) =
            DefaultCalendarChoices.classifySave(http, code, echoPresent, echo, "cal-a")

        // An echo naming another calendar or none, and a 2xx without a Code (204, no body to read).
        for ((answer, detail) in listOf(
            classify(200, 1000, echoPresent = true, echo = "cal-b") to "HTTP 200 (Code 1000) naming another default",
            classify(200, 1000, echoPresent = true, echo = null) to "HTTP 200 (Code 1000) naming no default",
            classify(204, null) to "HTTP 204 without a Code",
        )) {
            assertTrue(detail, answer is SaveOutcome.Uncertain)
            answer as SaveOutcome.Uncertain
            assertEquals(detail, answer.detail)
            val refusal = answer.refusal!!
            assertEquals(detail, if (detail.startsWith("HTTP 204")) 204 else 200, refusal.http)
            assertTrue(refusal.suffix, refusal.suffix.endsWith("; Proton still has another default"))
        }
        val unconfirmed = classify(200, 1000, echoPresent = true, echo = "cal-b")
        // Proton has it after all: the echo was stale.
        assertEquals(SaveOutcome.Saved, Run(Settings.Loaded("cal-a")).finish(unconfirmed))
        // Proton kept another default (or none): refused, and the phone stays as it was.
        val kept = Run(Settings.Loaded("cal-b"))
        val refused = kept.finish(unconfirmed)
        assertTrue("$refused", refused is SaveOutcome.Refused && refused.http == 200 && refused.code == 1000)
        assertEquals(0, kept.applied)
        assertTrue(Run(Settings.Loaded(null)).finish(unconfirmed) is SaveOutcome.Refused)
        // Proton could not be asked: it may have the change.
        assertEquals(
            SaveOutcome.Unreachable("HTTP 200 (Code 1000) naming another default; Proton could not be asked again", mayHaveArrived = true),
            Run(Settings.Failed("HTTP 503", 503)).finish(unconfirmed)
        )
    }

    @Test
    fun `a change that threw before anything was sent is unreachable, after it uncertain`() {
        for (e in listOf(UnknownHostException("x"), ConnectException("x"), NoRouteToHostException("x"), SSLHandshakeException("x"))) {
            assertEquals(SaveOutcome.Unreachable(e.javaClass.simpleName), DefaultCalendarChoices.classifySaveFailure(e))
        }
        for (e in listOf(SocketTimeoutException("x"), IOException("stream was reset"), JsonSyntaxException("x"))) {
            assertEquals(SaveOutcome.Uncertain(e.javaClass.simpleName), DefaultCalendarChoices.classifySaveFailure(e))
        }
    }

    @Test
    fun `a connect failure after an attempt that may have been sent is uncertain`() {
        // OkHttp's retry after a failure on an open connection: the new attempt's DNS failure is thrown, with the
        // earlier failure (the request may have gone out on it) attached as suppressed.
        val afterSend = UnknownHostException("x").apply { addSuppressed(IOException("stream was reset")) }
        assertEquals(
            SaveOutcome.Uncertain("UnknownHostException after IOException"),
            DefaultCalendarChoices.classifySaveFailure(afterSend)
        )
        // Deeper down too: a route's first connect failure carries the other routes' failures as suppressed.
        val nested = ConnectException("x").apply {
            addSuppressed(ConnectException("y").apply { addSuppressed(java.net.SocketException("Software caused connection abort")) })
        }
        assertTrue(DefaultCalendarChoices.classifySaveFailure(nested) is SaveOutcome.Uncertain)
        // Every attempt failed to connect, on every route: nothing was sent.
        val routes = ConnectException("x").apply { addSuppressed(ConnectException("y")); addSuppressed(NoRouteToHostException("z")) }
        assertEquals(
            SaveOutcome.Unreachable("ConnectException after ConnectException, NoRouteToHostException"),
            DefaultCalendarChoices.classifySaveFailure(routes)
        )
        // More suppressed failures than any real call carries prove nothing.
        val many = ConnectException("x").apply { repeat(40) { addSuppressed(ConnectException("route $it")) } }
        assertTrue(DefaultCalendarChoices.classifySaveFailure(many) is SaveOutcome.Uncertain)
        // A copy that keeps the original as its cause, as a coroutine's stack-trace recovery hands it on.
        val copy = UnknownHostException("x").apply { initCause(afterSend) }
        assertEquals(SaveOutcome.Uncertain("UnknownHostException after IOException"), DefaultCalendarChoices.classifySaveFailure(copy))
        // A cause alone is no earlier attempt: a handshake that failed on a reset still sent nothing.
        val handshake = SSLHandshakeException("x").apply { initCause(java.net.SocketException("Connection reset")) }
        assertEquals(SaveOutcome.Unreachable("SSLHandshakeException"), DefaultCalendarChoices.classifySaveFailure(handshake))
    }

    @Test
    fun `only a settings read Proton answered with 401 needs a new sign-in`() {
        assertTrue(DefaultCalendarChoices.needsSignIn(Settings.Failed("HTTP 401", 401)))
        assertFalse(DefaultCalendarChoices.needsSignIn(Settings.Failed("HTTP 403", 403)))
        assertFalse(DefaultCalendarChoices.needsSignIn(Settings.Failed("HTTP 200, Code 2001", 200)))
        // An exception or a timeout carries no status.
        assertFalse(DefaultCalendarChoices.needsSignIn(Settings.Failed("SocketTimeoutException")))
    }

    // ---- finishSave ----

    private class Run(private val settings: Settings? = null, private val verifyThrows: Exception? = null, private val applyThrows: Exception? = null) {
        var verified = 0
        var applied = 0
        val failures = mutableListOf<Exception>()

        fun finish(first: SaveOutcome): SaveOutcome = runBlocking {
            DefaultCalendarChoices.finishSave(
                first, "cal-a",
                verify = { verified++; verifyThrows?.let { throw it }; settings ?: Settings.Failed("not asked") },
                applyLocally = { applied++; applyThrows?.let { throw it } },
                onApplyFailed = { failures.add(it) },
            )
        }
    }

    @Test
    fun `a lost answer is checked once, and only a confirmed change reaches the phone`() {
        val confirmed = Run(Settings.Loaded("cal-a"))
        assertEquals(SaveOutcome.Saved, confirmed.finish(SaveOutcome.Uncertain("SocketTimeoutException")))
        assertEquals(1, confirmed.verified)
        assertEquals(1, confirmed.applied)

        // Not confirmed: a slow PUT may still land after the check, so these may have arrived.
        val other = Run(Settings.Loaded("cal-b"))
        val stillOther = other.finish(SaveOutcome.Uncertain("SocketTimeoutException"))
        assertEquals(SaveOutcome.Unreachable("SocketTimeoutException; Proton still has another default", mayHaveArrived = true), stillOther)
        assertEquals(0, other.applied)

        val notSet = Run(Settings.Loaded(null))
        val stillNone = notSet.finish(SaveOutcome.Uncertain("HTTP 502"))
        assertTrue(stillNone is SaveOutcome.Unreachable && stillNone.mayHaveArrived)
        assertEquals(0, notSet.applied)

        val unasked = Run(Settings.Failed("HTTP 503", 503))
        assertEquals(
            SaveOutcome.Unreachable("HTTP 502; Proton could not be asked again", mayHaveArrived = true),
            unasked.finish(SaveOutcome.Uncertain("HTTP 502"))
        )
        assertEquals(0, unasked.applied)

        val threw = Run(verifyThrows = IOException("x"))
        assertEquals(
            SaveOutcome.Unreachable("HTTP 500; Proton could not be asked again", mayHaveArrived = true),
            threw.finish(SaveOutcome.Uncertain("HTTP 500"))
        )
        assertEquals(0, threw.applied)
    }

    @Test
    fun `a confirmed change stays confirmed when the phone could not follow`() {
        val failure = IllegalStateException("provider gone")
        val run = Run(applyThrows = failure)

        assertEquals(SaveOutcome.Saved, run.finish(SaveOutcome.Saved))
        assertEquals(0, run.verified)
        assertEquals(1, run.applied)
        assertEquals(listOf<Exception>(failure), run.failures)
    }

    @Test
    fun `a refusal, a sign-in and no connection are returned as they are, without a check or a local change`() {
        for (first in listOf(SaveOutcome.Refused(422, 2001), SaveOutcome.Auth, SaveOutcome.Unreachable("UnknownHostException"))) {
            val run = Run(Settings.Loaded("cal-a"))
            assertEquals(first, run.finish(first))
            assertEquals(0, run.verified)
            assertEquals(0, run.applied)
        }
        // Nothing reached Proton, so it is not changed: no "may have arrived".
        assertFalse(SaveOutcome.Unreachable("UnknownHostException").mayHaveArrived)
    }

    @Test
    fun `a cancellation of the local change is passed on`() {
        val cancel = kotlinx.coroutines.CancellationException("screen gone")
        val failures = mutableListOf<Exception>()
        val thrown = runBlocking {
            try {
                DefaultCalendarChoices.finishSave(
                    SaveOutcome.Saved, "cal-a",
                    verify = { Settings.Loaded("cal-a") },
                    applyLocally = { throw cancel },
                    onApplyFailed = { failures.add(it) },
                )
                null
            } catch (e: kotlinx.coroutines.CancellationException) {
                e
            }
        }
        assertSame(cancel, thrown)
        assertTrue(failures.isEmpty())
    }
}

package me.proton.sync.calendar

import me.proton.sync.db.entities.CalendarSyncEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which existing row a calendar without a mapping may take over by its display name: never one that another
 * calendar Proton still lists maps to, so two calendars of the same name are never merged into one row (a shared
 * "Home" took over the own "Home"'s row, which this app then marked primary). Made-up names only.
 */
class CalendarRowClaimsTest {

    private companion object {
        const val ACCOUNT = "user@example.test"
    }

    private fun mapping(protonId: String, androidId: Long?) =
        CalendarSyncEntity(accountName = ACCOUNT, protonCalendarId = protonId, androidCalendarId = androidId, displayName = "Home")

    @Test
    fun `a row another listed calendar maps to is never taken over`() {
        // The own "Home" is mapped to row 5; a calendar shared with the user, also called "Home", has no mapping yet.
        val mappings = listOf(mapping("own", 5))

        assertNull(CalendarRowClaims.reusableRow(listOf(5L), "shared", mappings, setOf("own", "shared")))
    }

    @Test
    fun `a row of a listed calendar that this run skips is not free either`() {
        // An external calendar keeps its mapping while "Include external calendars" is off, and it is still on Proton.
        val mappings = listOf(mapping("feed", 7))

        assertNull(CalendarRowClaims.reusableRow(listOf(7L), "own", mappings, setOf("own", "feed")))
    }

    @Test
    fun `a calendar deleted and re-created under the same name keeps its row`() {
        // The old id is gone from Proton, so its row goes to the new calendar (and its events are re-keyed).
        val mappings = listOf(mapping("old", 5))

        assertEquals(5L, CalendarRowClaims.reusableRow(listOf(5L), "new", mappings, setOf("new")))
    }

    @Test
    fun `an unmapped row is taken over, the first free one of that name`() {
        val mappings = listOf(mapping("own", 5))

        assertEquals(9L, CalendarRowClaims.reusableRow(listOf(5L, 9L, 11L), "shared", mappings, setOf("own", "shared")))
        assertNull(CalendarRowClaims.reusableRow(null, "shared", mappings, setOf("own", "shared")))
        assertNull(CalendarRowClaims.reusableRow(emptyList(), "shared", mappings, setOf("own", "shared")))
    }

    @Test
    fun `after a resync wiped the mappings the first row of the name is taken, as before`() {
        assertEquals(5L, CalendarRowClaims.reusableRow(listOf(5L, 9L), "own", emptyList(), setOf("own", "shared")))
    }

    @Test
    fun `a row kept for a calendar removed on Proton is listed under that calendar alone, never under its name`() {
        // "Team", shared by a colleague, was removed on Proton while an event that exists only on the phone sat in it:
        // its row stayed, unmapped, marked as kept for "team-old". Another calendar named "Team" (someone else's) must
        // not take it over, or that event goes up in a calendar its writer never chose.
        val marker = CalendarRowClaims.keptForMarker("team-old")
        val key = CalendarRowClaims.listingKey("Team", marker)
        assertEquals(CalendarRowClaims.keptRowKey("team-old"), key)
        assertTrue(key != "Team" && key != CalendarRowClaims.keptRowKey("team-new"))
        // Rows without the marker are listed by their name, as before.
        assertEquals("Team", CalendarRowClaims.listingKey("Team", null))
        assertEquals("Team", CalendarRowClaims.listingKey("Team", "something an older version wrote"))
        // So the lookup by name of "Team" finds only the other rows of that name.
        val listed = mapOf(key to listOf(5L), "Team" to listOf(9L))
        assertEquals(9L, CalendarRowClaims.reusableRow(listed["Team"], "team-new", emptyList(), setOf("team-new")))
        assertNull(CalendarRowClaims.reusableRow(listed["Other"], "team-new", emptyList(), setOf("team-new")))
        // And no display name is a kept row's key.
        assertTrue(CalendarRowClaims.keptRowKey("x").first().code == 0)
    }

    @Test
    fun `a row 2_1_0 kept for a removed calendar is found by its kept events, and marked for that calendar`() {
        // On 2.1.0 Proton removed "Team" ("team-old") and the user answered Keep on phone: its calendar mapping went, its
        // row 5 and the kept events' mappings stayed, and nothing marked the row. Another "Team" ("team-new") has appeared.
        val removed = CalendarRowClaims.removedCalendarsWithEvents(
            eventCalendarIds = listOf("own", "team-old", "own"),
            mappedCalendarIds = setOf("own"),
            listedIds = setOf("own", "team-new"),
        )
        assertEquals(setOf("team-old"), removed)
        // A calendar Proton still lists is not removed, mapped or not (a Local to Remote re-sync wiped every mapping).
        assertEquals(emptySet<String>(), CalendarRowClaims.removedCalendarsWithEvents(listOf("own", "feed"), emptySet(), setOf("own", "feed")))
        // A calendar that is still mapped is settled by the removal pass itself.
        assertEquals(emptySet<String>(), CalendarRowClaims.removedCalendarsWithEvents(listOf("gone"), setOf("gone"), emptySet()))

        assertEquals(mapOf(5L to "team-old"), CalendarRowClaims.unmarkedKeptRows(mapOf("team-old" to setOf(5L)), emptySet(), emptySet()))
        // Marked, it is listed under that calendar's own key, so the new "Team" never finds it by name.
        val key = CalendarRowClaims.listingKey("Team", CalendarRowClaims.keptForMarker("team-old"))
        val listed = mapOf(key to listOf(5L))
        assertNull(CalendarRowClaims.reusableRow(listed["Team"], "team-new", emptyList(), setOf("team-new")))
        // A re-share of the same calendar keeps its id and takes the row back.
        assertEquals(5L, listed[CalendarRowClaims.keptRowKey("team-old")]?.firstOrNull())
    }

    @Test
    fun `only an unmarked row no live calendar uses is marked, and once`() {
        val rowsOfRemoved = mapOf("team-old" to setOf(5L, 7L), "club-old" to setOf(9L))
        // Row 7 is mapped (a live calendar uses it, as 2.1.0's takeover left it), row 9 is marked already.
        assertEquals(mapOf(5L to "team-old"), CalendarRowClaims.unmarkedKeptRows(rowsOfRemoved, mappedRows = setOf(7L), markedRows = setOf(9L)))
        // A row holding the kept events of two removed calendars is marked for the first by id, whatever the order given.
        assertEquals(mapOf(5L to "a-old"), CalendarRowClaims.unmarkedKeptRows(mapOf("b-old" to setOf(5L), "a-old" to setOf(5L)), emptySet(), emptySet()))
        assertEquals(emptyMap<Long, String>(), CalendarRowClaims.unmarkedKeptRows(emptyMap(), emptySet(), emptySet()))
    }

    @Test
    fun `a row a listed calendar's events sit in is never marked, also after a re-sync deleted the calendar mappings`() {
        // On 2.1.0 "Team" ("team-old") was removed and kept in row 5, and a new "Team" ("team-new") took row 5 over by
        // name: it holds the events of both. A Local to Remote re-sync then deleted every calendar mapping and kept the
        // events'. In its run, before the calendars are mapped again, "team-new" is listed without a mapping.
        val listedUnmapped = CalendarRowClaims.listedWithoutMapping(
            eventCalendarIds = listOf("team-old", "team-new", "own", "team-new"),
            mappedCalendarIds = emptySet(),
            listedIds = setOf("own", "team-new"),
        )
        assertEquals(listOf("team-new", "own"), listedUnmapped.toList())
        // A listed calendar with its mapping is known by the mapping; a removed one is not listed.
        assertEquals(emptySet<String>(), CalendarRowClaims.listedWithoutMapping(listOf("own"), setOf("own"), setOf("own")))
        assertEquals(emptySet<String>(), CalendarRowClaims.listedWithoutMapping(listOf("team-old"), emptySet(), setOf("own")))

        // Row 5 holds mapped events of "team-new", row 8 those of "own": both are rows a calendar uses, and row 5 is not
        // marked for "team-old", so "team-new" takes it back by name, as the first 2.2.0 sync left it.
        val inUse = CalendarRowClaims.rowsInUse(emptyList(), rowsOfListedEvents = setOf(5L, 8L))
        assertEquals(setOf(5L, 8L), inUse)
        assertEquals(emptyMap<Long, String>(), CalendarRowClaims.unmarkedKeptRows(mapOf("team-old" to setOf(5L)), inUse, emptySet()))
        assertEquals(5L, CalendarRowClaims.reusableRow(listOf(5L), "team-new", emptyList(), setOf("own", "team-new")))
        // A row only the removed calendar's events sit in is still marked.
        assertEquals(mapOf(6L to "team-old"), CalendarRowClaims.unmarkedKeptRows(mapOf("team-old" to setOf(5L, 6L)), inUse, emptySet()))
        // And the rows the calendar mappings point at still count, with the events' rows.
        assertEquals(setOf(5L, 7L), CalendarRowClaims.rowsInUse(listOf(mapping("own", 7), mapping("new", null)), setOf(5L)))
    }

    @Test
    fun `a marked row a listed calendar maps stays under its name, so the removed calendar never takes it as well`() {
        // On 2.2.0 "Team" ("team-old") was removed and kept in row 5 for an event that exists only on the phone, marked
        // for it; no event mapping of it is left. 2.1.0 was then installed over 2.2.0 with the data kept (a debuggable
        // install) and handed row 5 by name to the own "Team" ("own-team"), never reading the marker; 2.2.0 is back.
        // Row 5 stays listed by its name, where the mapping of "own-team" holds it.
        val marker = CalendarRowClaims.keptForMarker("team-old")
        val eventCalendars = setOf("own-team")
        assertFalse(CalendarRowClaims.listedAsKept(5L, marker, listOf(mapping("own-team", 5)), setOf("own-team", "team-old"), eventCalendars))
        assertNull(CalendarRowClaims.reusableRow(listOf(5L), "team-old", listOf(mapping("own-team", 5)), setOf("own-team", "team-old")))
        // Unmapped, or mapped only by a calendar Proton no longer lists (a removal cut short before its mapping went),
        // a marked row is listed under its calendar's own key, as before.
        assertTrue(CalendarRowClaims.listedAsKept(5L, marker, emptyList(), setOf("own-team"), eventCalendars))
        assertTrue(CalendarRowClaims.listedAsKept(5L, marker, listOf(mapping("team-old", 5)), setOf("own-team"), eventCalendars))
        assertTrue(CalendarRowClaims.listedAsKept(5L, marker, listOf(mapping("own-team", 6), mapping("new", null)), setOf("own-team", "new"), eventCalendars))
    }

    @Test
    fun `a marked row still goes back to its removed calendar while events kept for it have their mappings`() {
        // As above, but the user answered Keep on phone: the events kept for "team-old" sit in row 5 and their mappings
        // stayed. With a row of its own, "team-old", shared again, would find them missing from it and delete them on
        // Proton. So row 5 is listed under that calendar's own key, though "own-team" maps it, and "team-old" takes it
        // back, as earlier builds did before this rule; the two calendars then sync into one row, and nothing is deleted.
        val marker = CalendarRowClaims.keptForMarker("team-old")
        val mappings = listOf(mapping("own-team", 5))
        val listed = setOf("own-team", "team-old")
        assertTrue(CalendarRowClaims.listedAsKept(5L, marker, mappings, listed, eventCalendarIds = setOf("own-team", "team-old")))
        assertEquals(CalendarRowClaims.keptRowKey("team-old"), CalendarRowClaims.listingKey("Team", marker))
        // Also while Proton does not list it yet: no other calendar finds the row there, and the mapping holds it.
        assertTrue(CalendarRowClaims.listedAsKept(5L, marker, mappings, setOf("own-team"), eventCalendarIds = setOf("team-old")))
        // The event mappings of another calendar change nothing.
        assertFalse(CalendarRowClaims.listedAsKept(5L, marker, mappings, listed, eventCalendarIds = setOf("own-team", "club-old")))
        // A value that is no marker lists no row as kept.
        assertFalse(CalendarRowClaims.listedAsKept(5L, "something an older version wrote", emptyList(), listed, setOf("team-old")))
        assertFalse(CalendarRowClaims.listedAsKept(5L, "kept-for:", emptyList(), listed, setOf("team-old")))
    }

    @Test
    fun `the kept-for marker names its calendar, and nothing else reads as one`() {
        assertEquals("cal-1==", CalendarRowClaims.keptFor(CalendarRowClaims.keptForMarker("cal-1==")))
        assertNull(CalendarRowClaims.keptFor(null))
        assertNull(CalendarRowClaims.keptFor(""))
        assertNull(CalendarRowClaims.keptFor("kept-for:"))
        assertNull(CalendarRowClaims.keptFor("something an older version wrote"))
    }

    @Test
    fun `rows two listed calendars share are found`() {
        val mappings = listOf(mapping("own", 5), mapping("shared", 5), mapping("work", 6), mapping("gone", 6), mapping("new", null))

        // Row 6 holds one calendar still on Proton and one that is gone: that is the re-key case, not a merge.
        assertEquals(setOf(5L), CalendarRowClaims.sharedRows(mappings, setOf("own", "shared", "work", "new")))
        assertEquals(emptySet<Long>(), CalendarRowClaims.sharedRows(mappings, setOf("own", "work")))
    }
}

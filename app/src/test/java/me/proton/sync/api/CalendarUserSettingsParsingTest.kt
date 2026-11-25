package me.proton.sync.api

import com.google.gson.Gson
import me.proton.sync.api.models.CalendarListResponse
import me.proton.sync.api.models.CalendarMember
import me.proton.sync.api.models.CalendarUserSettingsResponse
import me.proton.sync.api.models.UpdateCalendarUserSettingsRequest
import me.proton.sync.calendar.DefaultCalendarChoices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The calendar settings and the member fields the primary-calendar choice reads, parsed by a plain Gson as the
 * Retrofit converter does (GsonConverterFactory.create()). A missing value must come out as null, not 0: Gson leaves
 * a missing non-null Int at 0, and a member Flags of 0 reads as "inactive". Made-up ids and addresses only.
 */
class CalendarUserSettingsParsingTest {

    private val gson = Gson()

    private fun settings(json: String) = gson.fromJson(json, CalendarUserSettingsResponse::class.java)

    @Test
    fun `DefaultCalendarID as a string`() {
        val parsed = settings("""{"Code":1000,"CalendarUserSettings":{"DefaultCalendarID":"cal-1==","WeekLength":7}}""")

        assertEquals(1000, parsed.code)
        assertEquals("cal-1==", parsed.settings?.defaultCalendarId)
    }

    @Test
    fun `DefaultCalendarID null`() {
        val parsed = settings("""{"Code":1000,"CalendarUserSettings":{"DefaultCalendarID":null,"WeekLength":7}}""")

        assertEquals(1000, parsed.code)
        assertNull(parsed.settings!!.defaultCalendarId)
    }

    @Test
    fun `DefaultCalendarID absent`() {
        val parsed = settings("""{"Code":1000,"CalendarUserSettings":{"WeekLength":7}}""")

        assertNull(parsed.settings!!.defaultCalendarId)
    }

    @Test
    fun `no settings object and no Code parse as null, which the sync reads as a failed read`() {
        val parsed = settings("""{"Other":1}""")

        assertNull(parsed.code)
        assertNull(parsed.settings)
    }

    @Test
    fun `the change sends DefaultCalendarID alone`() {
        assertEquals("""{"DefaultCalendarID":"cal-a"}""", gson.toJson(UpdateCalendarUserSettingsRequest("cal-a")))
    }

    @Test
    fun `the answer to the change parses as the read does`() {
        fun saved(json: String): DefaultCalendarChoices.SaveOutcome {
            val parsed = settings(json)
            return DefaultCalendarChoices.classifySave(200, parsed.code, parsed.settings != null, parsed.settings?.defaultCalendarId, "cal-a")
        }

        val echoed = settings("""{"Code":1000,"CalendarUserSettings":{"DefaultCalendarID":"cal-a","WeekStart":1}}""")
        assertEquals(1000, echoed.code)
        assertEquals("cal-a", echoed.settings?.defaultCalendarId)
        assertEquals(DefaultCalendarChoices.SaveOutcome.Saved, saved("""{"Code":1000,"CalendarUserSettings":{"DefaultCalendarID":"cal-a"}}"""))
        // An echo naming another calendar, or one without an id, does not confirm the change: one check decides, and
        // Proton keeping another default then counts as a refusal.
        assertNull(settings("""{"Code":1000,"CalendarUserSettings":{"DefaultCalendarID":null}}""").settings!!.defaultCalendarId)
        for (json in listOf(
            """{"Code":1000,"CalendarUserSettings":{"DefaultCalendarID":"cal-b"}}""",
            """{"Code":1000,"CalendarUserSettings":{"DefaultCalendarID":null}}""",
            """{"Code":1000,"CalendarUserSettings":{"WeekStart":1}}""",
        )) {
            val outcome = saved(json)
            assertTrue(json, outcome is DefaultCalendarChoices.SaveOutcome.Uncertain)
            val refusal = (outcome as DefaultCalendarChoices.SaveOutcome.Uncertain).refusal
            assertEquals(json, 200, refusal?.http)
            assertEquals(json, 1000, refusal?.code)
        }
        // No settings object at all, as Proton web reads the answer: the change stands.
        assertNull(settings("""{"Code":1000}""").settings)
        assertEquals(DefaultCalendarChoices.SaveOutcome.Saved, saved("""{"Code":1000}"""))
        // Another Code is a refusal without a check.
        assertEquals(DefaultCalendarChoices.SaveOutcome.Refused(200, 2001), saved("""{"Code":2001}"""))
    }

    @Test
    fun `a member's own calendar name is read when present`() {
        val without = gson.fromJson(
            """{"ID":"m1","Email":"a@example.test","Permissions":127,"AddressID":"a1","CalendarID":"c1","Color":null,"Display":1}""",
            CalendarMember::class.java
        )
        val with = gson.fromJson(
            """{"ID":"m1","Email":"a@example.test","Permissions":127,"AddressID":"a1","CalendarID":"c1","Color":null,"Display":1,"Name":"Home"}""",
            CalendarMember::class.java
        )

        assertNull(without.name)
        assertEquals("Home", with.name)
    }

    @Test
    fun `member Flags and Priority absent parse as null, not 0`() {
        val member = gson.fromJson(
            """{"ID":"m1","Email":"a@example.test","Permissions":127,"AddressID":"a1","CalendarID":"c1","Color":"#000000","Display":1}""",
            CalendarMember::class.java
        )

        assertNull(member.flags)
        assertNull(member.priority)
    }

    @Test
    fun `member Flags and Priority present parse as sent, 0 included`() {
        val member = gson.fromJson(
            """{"ID":"m1","Email":"a@example.test","Permissions":127,"AddressID":"a1","CalendarID":"c1","Color":null,"Display":1,"Flags":0,"Priority":3}""",
            CalendarMember::class.java
        )

        assertEquals(0, member.flags)
        assertEquals(3, member.priority)
    }

    @Test
    fun `the calendar list carries each member's Flags and Priority`() {
        val list = gson.fromJson(
            """{"Code":1000,"Calendars":[{"ID":"c1","Name":null,"Description":null,"Color":null,"Display":1,"Type":0,"Members":[
               {"ID":"m1","Email":"a@example.test","Permissions":127,"AddressID":"a1","CalendarID":"c1","Color":"#000000","Display":1,"Flags":1,"Priority":2},
               {"ID":"m2","Email":"b@example.test","Permissions":112,"AddressID":"a2","CalendarID":"c1","Color":"#000000","Display":1}]}]}""",
            CalendarListResponse::class.java
        )
        val members = list.calendars.single().members!!

        assertEquals(1, members[0].flags)
        assertEquals(2, members[0].priority)
        assertNull(members[1].flags)
        assertNull(members[1].priority)
        // The calendar-level Flags the list does not carry stays Gson's 0: the reason the choice never reads it.
        assertEquals(0, list.calendars.single().flags)
    }
}

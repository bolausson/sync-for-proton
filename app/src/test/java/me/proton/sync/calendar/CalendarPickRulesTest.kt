package me.proton.sync.calendar

import me.proton.sync.calendar.CalendarPickRules.Row
import me.proton.sync.calendar.CalendarPickRules.Rule
import me.proton.sync.calendar.CalendarPickRules.Stored
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What common third-party pick rules choose on a phone shaped like the one in the wrong-calendar report: a calendar
 * another user shared with edit rights holds the account's lowest _ID, and the account's default calendar comes
 * third. Before the fix every row stored 0, and the rules that look for a primary calendar fell back to the first
 * row; with the default storing 1 they take the default. The rules that ignore the flag cannot be steered.
 * Made-up names and addresses only.
 */
class CalendarPickRulesTest {

    private companion object {
        const val ACCOUNT = "user@example.test"
        const val ALIAS = "alias@example.test"
        const val TYPE = "app.account.type"
        const val READ = 200
        const val OWNER = CalendarPickRules.ACCESS_OWNER
    }

    private fun row(
        id: Long,
        name: String?,
        stored: Int?,
        owner: String? = ACCOUNT,
        access: Int = OWNER,
        account: String = ACCOUNT,
        type: String = TYPE,
        visible: Boolean = true,
    ) = Row(
        id = id,
        accountName = account,
        accountType = type,
        displayName = name,
        accessLevel = access,
        visible = visible,
        storedPrimary = when (stored) {
            1 -> Stored.ONE
            0 -> Stored.ZERO
            null -> Stored.NULL
            else -> Stored.OTHER
        },
        readPrimary = CalendarPickRules.readValue(stored, owner, account),
        ownerAccount = owner,
    )

    /** The reported shape. [homeStored] is what the default calendar's row stores. */
    private fun phone(homeStored: Int) = listOf(
        // Shared with edit rights: written as OWNER like the account's own, OWNER_ACCOUNT the alias it was shared to.
        row(3, "Team", 0, owner = ALIAS),
        row(4, "Work", 0),
        row(5, "Home", homeStored),
        row(6, "Appointments", 0),
        row(7, "Holidays", 0, access = READ),
        row(8, "Feed", 0, access = READ),
    )

    private fun pickId(rule: Rule, rows: List<Row>) = CalendarPickRules.pick(rule, rows)?.id

    @Test
    fun `before the fix the primary rules fall back to the shared calendar`() {
        val rows = phone(homeStored = 0)

        assertEquals(3L, pickId(Rule.STORED_PRIMARY_ELSE_VISIBLE, rows))
        assertEquals(3L, pickId(Rule.PRIMARY_ELSE_FIRST, rows))
        assertEquals(3L, pickId(Rule.OWNER_PRIMARY_ELSE_FIRST, rows))
        assertEquals(3L, pickId(Rule.FIRST_WRITABLE, rows))
    }

    @Test
    fun `after the fix the primary rules take the default`() {
        val rows = phone(homeStored = 1)

        assertEquals(5L, pickId(Rule.STORED_PRIMARY_ELSE_VISIBLE, rows))
        assertEquals(5L, pickId(Rule.PRIMARY_ELSE_FIRST, rows))
        assertEquals(5L, pickId(Rule.OWNER_PRIMARY_ELSE_FIRST, rows))
    }

    @Test
    fun `the rules that ignore the flag pick the same row either way`() {
        for (homeStored in listOf(0, 1)) {
            val rows = phone(homeStored)
            assertEquals(3L, pickId(Rule.FIRST_WRITABLE, rows))
            assertNull(pickId(Rule.ID1, rows))
            // Etar's first run matches OWNER_ACCOUNT to the account name, which the shared calendar's alias is not.
            assertEquals(4L, pickId(Rule.ETAR_FIRST_RUN, rows))
            assertEquals(6L, pickId(Rule.NAME_ORDER, rows))
        }
    }

    @Test
    fun `another account's implicit primary with a lower id still wins where the provider reads it`() {
        // A calendar of another account that stores NULL and owns itself reads as primary through the COALESCE.
        val other = row(2, "Other", null, owner = "someone@example.test", account = "someone@example.test", type = "com.example.other")
        val rows = listOf(other) + phone(homeStored = 1)

        assertEquals(2L, pickId(Rule.PRIMARY_ELSE_FIRST, rows))
        assertEquals(2L, pickId(Rule.OWNER_PRIMARY_ELSE_FIRST, rows))
        // A selection on isPrimary sees only what is stored, so the explicit 1 wins there.
        assertEquals(5L, pickId(Rule.STORED_PRIMARY_ELSE_VISIBLE, rows))
    }

    @Test
    fun `calendar id 1 is taken whatever it is`() {
        val rows = listOf(row(1, "Team", 0, owner = ALIAS), row(2, "Home", 1))

        assertEquals(1L, pickId(Rule.ID1, rows))
    }

    @Test
    fun `hidden and read-only rows are skipped where the rule asks for writable visible ones`() {
        val rows = listOf(
            row(1, "Holidays", 0, access = READ),
            row(2, "Hidden", 0, visible = false),
            row(3, "Home", 1),
        )

        assertEquals(3L, pickId(Rule.FIRST_WRITABLE, rows))
        assertEquals(3L, pickId(Rule.NAME_ORDER, rows))
        // This one only asks for visible rows, so a read-only first row is what it falls back to without a primary.
        assertEquals(1L, pickId(Rule.STORED_PRIMARY_ELSE_VISIBLE, rows.map { it.copy(storedPrimary = Stored.ZERO, readPrimary = 0) }))
    }

    @Test
    fun `Etar never takes a local account's calendar on its first run`() {
        val local = row(1, "Local", 0, owner = "local", account = "local", type = CalendarPickRules.ACCOUNT_TYPE_LOCAL)
        val rows = listOf(local, row(2, "Team", 0, owner = ALIAS), row(3, "Home", 1))

        assertEquals(3L, pickId(Rule.ETAR_FIRST_RUN, rows))
    }

    @Test
    fun `name order puts a missing name first and breaks ties by id`() {
        val rows = listOf(row(5, "Home", 0), row(4, null, 0), row(3, "Home", 0))

        assertEquals(4L, pickId(Rule.NAME_ORDER, rows))
        assertEquals(3L, pickId(Rule.NAME_ORDER, rows.filter { it.displayName != null }))
    }

    @Test
    fun `the read value is the provider's COALESCE`() {
        assertEquals(1, CalendarPickRules.readValue(null, ACCOUNT, ACCOUNT))
        assertEquals(0, CalendarPickRules.readValue(null, ALIAS, ACCOUNT))
        assertNull(CalendarPickRules.readValue(null, null, ACCOUNT))
        assertEquals(0, CalendarPickRules.readValue(0, ACCOUNT, ACCOUNT))
        assertEquals(1, CalendarPickRules.readValue(1, ALIAS, ACCOUNT))
    }

    @Test
    fun `nothing to pick from picks nothing`() {
        for (rule in Rule.entries) assertNull(rule.label, CalendarPickRules.pick(rule, emptyList()))
    }
}

package me.proton.sync.calendar

/**
 * How common third-party code picks "the" calendar for a new event, simulated on the phone's calendar rows for
 * the calendar-picks debug verb. Pure, so the simulation is tested. None of these rules is ours; they are the
 * shapes found in libraries and apps while investigating an event that landed in a shared calendar. Several
 * ignore IS_PRIMARY entirely, which is why marking Proton's default ([PrimaryCalendarChooser]) cannot steer every app.
 *
 * Rows are taken in _ID order, the order the stock provider returns a query without ORDER BY in.
 */
internal object CalendarPickRules {

    /** CalendarContract.Calendars access levels (kept here so this stays free of android.* for the JVM tests). */
    const val ACCESS_CONTRIBUTOR = 500
    const val ACCESS_OWNER = 700
    const val ACCESS_ROOT = 800

    /** CalendarContract.ACCOUNT_TYPE_LOCAL. */
    const val ACCOUNT_TYPE_LOCAL = "LOCAL"

    /** IS_PRIMARY as the table stores it. */
    enum class Stored(val label: String) { ONE("1"), ZERO("0"), NULL("null"), OTHER("other") }

    data class Row(
        val id: Long,
        val accountName: String,
        val accountType: String,
        val displayName: String?,
        val accessLevel: Int,
        val visible: Boolean,
        /** What the table stores, which is what a selection on isPrimary sees. */
        val storedPrimary: Stored,
        /**
         * What the provider's projection returns, `COALESCE(isPrimary, ownerAccount = account_name)`; null when
         * SQL gives NULL (no stored value and no OWNER_ACCOUNT), which an app's getInt reads as 0.
         */
        val readPrimary: Int?,
        val ownerAccount: String?,
    ) {
        val ownerIsAccount: Boolean get() = ownerAccount != null && ownerAccount == accountName
        val writable: Boolean get() = accessLevel >= ACCESS_CONTRIBUTOR
    }

    enum class Rule(val label: String) {
        /** Calendar _ID 1, whatever it is: react-native-calendar-events' saveEvent without a calendar id, and the Cordova calendar plugin. */
        ID1("id1"),

        /**
         * `VISIBLE = 1 AND isPrimary = 1 ORDER BY _ID`, else the first visible row: a widely copied Kotlin helper.
         * Its selection binds to the STORED value, so only an explicit 1 counts.
         */
        STORED_PRIMARY_ELSE_VISIBLE("stored-primary-else-visible"),

        /** The first row that reads as primary, else the first row: a Capacitor calendar plugin (capacitor-calendar). */
        PRIMARY_ELSE_FIRST("primary-else-first"),

        /** Among visible rows with access OWNER, CONTRIBUTOR or ROOT, the first that reads as primary, else the first: onejs/one. */
        OWNER_PRIMARY_ELSE_FIRST("owner-primary-else-first"),

        /** The first visible row with access CONTRIBUTOR or higher: plain pickers, and Etar's fallback to position 0. */
        FIRST_WRITABLE("first-writable"),

        /**
         * Etar's first run (no stored default, EditEventView.findDefaultCalendarPosition): the first writable visible
         * row of a non-local account whose OWNER_ACCOUNT equals its account name, else [FIRST_WRITABLE].
         */
        ETAR_FIRST_RUN("etar-first-run"),

        /**
         * The first writable visible row in Calendars.DEFAULT_SORT_ORDER, the display name (nulls first, as SQLite
         * sorts them; ties by _ID). Compared by UTF-16 unit, which differs from SQLite's byte order only for
         * characters outside the basic plane.
         */
        NAME_ORDER("name-order"),
    }

    /** The row [rule] would write a new event into, or null when it finds none. */
    fun pick(rule: Rule, rows: List<Row>): Row? {
        val byId = rows.sortedBy { it.id }
        val writableVisible = byId.filter { it.visible && it.writable }
        return when (rule) {
            Rule.ID1 -> byId.firstOrNull { it.id == 1L }
            Rule.STORED_PRIMARY_ELSE_VISIBLE -> {
                val visible = byId.filter { it.visible }
                visible.firstOrNull { it.storedPrimary == Stored.ONE } ?: visible.firstOrNull()
            }
            Rule.PRIMARY_ELSE_FIRST -> byId.firstOrNull { it.readPrimary == 1 } ?: byId.firstOrNull()
            Rule.OWNER_PRIMARY_ELSE_FIRST -> {
                val eligible = byId.filter {
                    it.visible && it.accessLevel in setOf(ACCESS_OWNER, ACCESS_CONTRIBUTOR, ACCESS_ROOT)
                }
                eligible.firstOrNull { it.readPrimary == 1 } ?: eligible.firstOrNull()
            }
            Rule.FIRST_WRITABLE -> writableVisible.firstOrNull()
            Rule.ETAR_FIRST_RUN ->
                writableVisible.firstOrNull { it.accountType != ACCOUNT_TYPE_LOCAL && it.ownerIsAccount }
                    ?: writableVisible.firstOrNull()
            Rule.NAME_ORDER ->
                writableVisible.sortedWith(compareBy<Row, String?>(nullsFirst()) { it.displayName }.thenBy { it.id })
                    .firstOrNull()
        }
    }

    /** What the provider's projection returns for a stored value: COALESCE(isPrimary, ownerAccount = account_name). */
    fun readValue(stored: Int?, ownerAccount: String?, accountName: String): Int? = when {
        stored != null -> stored
        ownerAccount == null -> null
        ownerAccount == accountName -> 1
        else -> 0
    }
}

package me.proton.sync.calendar

/**
 * The name and colour a calendar's row on the phone gets from a sync (CalendarSyncAdapter.prepareCalendarJob), from what
 * Proton answered. Pure, so it is tested without a device.
 *
 * Proton's calendar list often carries no name, and the sync then reads it from the calendar's bootstrap. Until 2.2.0 a
 * bootstrap read that failed (no network, or an answer that was not a success) was taken for a calendar without a name:
 * the placeholder went in and renamed the calendar's existing row, to "My Calendar" or "Proton Calendar" with the
 * default colour, in every calendar app on the phone, until a later sync read the name. Seen on a test phone on
 * 2026-11-22 with the network cut during a calendar sync: four rows renamed, a shared and two subscribed calendars among
 * them. Since 2.2.0 that later sync may wait for the user to tap Sync. Now only the read that failed is unknown, and an
 * existing row keeps what it has. The placeholder stays for a new row, which needs a name, and for a calendar Proton
 * answered for without one.
 */
internal object CalendarRowLabel {

    /** The name of a calendar Proton names nowhere: one of the user's own type (0), and any other (subscribed, holidays). */
    const val OWN_PLACEHOLDER = "My Calendar"
    const val OTHER_PLACEHOLDER = "Proton Calendar"

    /** The colour of a calendar Proton gives none. */
    const val DEFAULT_COLOR = "#657ee4"

    /** The placeholder name of a calendar of Proton [type]. */
    fun placeholder(type: Int): String = if (type == 0) OWN_PLACEHOLDER else OTHER_PLACEHOLDER

    /**
     * The label of a calendar of Proton [type] whose list entry and bootstrap gave [name] and [color] (null where they gave
     * none). [bootstrapFailed]: the bootstrap was read, because the list had no name, and the read failed, so what it
     * would have given is unknown rather than absent.
     */
    fun of(name: String?, color: String?, type: Int, bootstrapFailed: Boolean): Label = Label(
        name = name ?: placeholder(type),
        color = color ?: DEFAULT_COLOR,
        keepRowName = name == null && bootstrapFailed,
        keepRowColor = color == null && bootstrapFailed,
        placeholder = name == null,
    )

    /**
     * What a sync writes into a calendar's row.
     *
     * [name] and [color] go into a new row, and into an existing one unless it keeps its own ([keepRowName],
     * [keepRowColor]): the calendar's name or colour is unknown only because the read failed, and the row's current one
     * is the best this run has. [placeholder]: [name] is none of the calendar's own (Proton named it nowhere, or the read
     * failed), and the reuse by name (where CalendarRowClaims.reusableRow takes a row of the same name) takes no row by
     * it, since a row with that name may be another calendar's.
     */
    data class Label(
        val name: String,
        val color: String,
        val keepRowName: Boolean,
        val keepRowColor: Boolean,
        val placeholder: Boolean,
    )

    /**
     * The name a run records for an existing row that keeps its own ([Label.keepRowName]), for the calendar's mapping and
     * the run's own lines: the row's [current] name, else the name its mapping stored ([stored]), else [fallback].
     * Blank counts as none. The mapping's name is what the Default calendar picker and the prompts show
     * (DefaultCalendarChoices.nameOf), so a placeholder there would rename the calendar on those screens instead.
     */
    fun keptName(current: String?, stored: String?, fallback: String): String =
        current?.takeIf { it.isNotBlank() } ?: stored?.takeIf { it.isNotBlank() } ?: fallback

    /** The colour recorded for an existing row that keeps its own ([Label.keepRowColor]), as [keptName]; 0 counts as none. */
    fun keptColor(current: Int?, stored: Int?, fallback: Int): Int =
        current ?: stored?.takeIf { it != 0 } ?: fallback
}

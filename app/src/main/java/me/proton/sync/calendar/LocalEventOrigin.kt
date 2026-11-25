package me.proton.sync.calendar

import android.database.Cursor
import me.proton.sync.LogNames

/**
 * Which app wrote a local calendar event, read from CalendarContract.Events.MUTATORS: for the upload's log line, and
 * for the hold of a new event another app wrote into a calendar someone else shared with the account
 * ([NewEventHoldRules.verdict] reads the first of its [entries]). Pure, so it is tested without a device.
 *
 * What the column holds (AOSP CalendarProvider2): on every write that is not a sync adapter's, the provider
 * stamps the calling package (getCallingPackageName: the package, or for a uid shared by several packages its
 * name, such as "android.uid.system:1000", or the bare uid). Only a sync adapter may put MUTATORS in its own
 * values (verifyNoSyncColumns; an exception insert refuses it too), so an event insert by an app stores exactly
 * that app's package. An event update REPLACES it with the updater's package, and so does an update or a delete
 * of one of the event's attendees, reminders or extended properties (the provider writes the event row with fresh
 * values); only an INSERT of such a child row appends the package when it is not yet listed (setEventDirty). When
 * a sync adapter marks the row clean (DIRTY = 0) the provider sets it back to NULL. So the first entry names the app
 * that last wrote the event row: the booking app's package for a direct insert, a calendar app's for an editor the
 * user saved from. Null means the provider recorded no writer.
 *
 * A caller that claims to be a sync adapter can write anything into the column, so it is cut to a few short entries
 * of package-name characters before it reaches the log, and only a text value is read at all ([mutatorsText]).
 */
internal object LocalEventOrigin {

    private const val MAX_ENTRIES = 4
    private const val MAX_ENTRY_LENGTH = 100

    /**
     * MUTATORS as the dirty-row read keeps it: [read] (the cursor's getString) only when the column holds text
     * ([fieldType] is the cursor's getType, Cursor.FIELD_TYPE_STRING); anything else is no writer. The stock provider
     * only ever stores text there, but a caller that claims to be a sync adapter may store any value, and getString
     * on a BLOB throws, which would stop every upload of the account while that row exists.
     */
    inline fun mutatorsText(fieldType: Int, read: () -> String?): String? =
        if (fieldType == Cursor.FIELD_TYPE_STRING) read() else null

    /**
     * The upload's first line for a changed row: [newEventLine] when [protonEventIdOf] (the row's mapping) finds no
     * Proton event, which is when the upload creates one, else the "modified" line. Only a log line, so a failure
     * of that lookup falls back to the "modified" line: it never stops the upload or changes how a failure is
     * counted, since the upload's own lookup then fails where the upload counts it. A cancellation is passed on.
     * [named] as for [newEventLine].
     */
    suspend fun uploadStartLine(
        androidEventId: Long,
        mutators: String?,
        calendarName: String?,
        named: Boolean = LogNames.shown(),
        protonEventIdOf: suspend () -> String?,
    ): String {
        val isNew = try {
            protonEventIdOf() == null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
        return if (isNew) newEventLine(androidEventId, mutators, calendarName, named) else modifiedLine(androidEventId)
    }

    /** The upload's line for an event that is on Proton already. */
    fun modifiedLine(androidEventId: Long): String = "Local event $androidEventId modified; uploading to Proton"

    /**
     * MUTATORS as its entries, in the order the provider kept them: split on commas, each trimmed, empty ones dropped.
     * Empty for null, an empty value or one of commas and blanks only. The entries are as the provider (or a caller
     * claiming to be a sync adapter) stored them: [sanitizeEntry] before one reaches a log line or the screen.
     */
    fun entries(mutators: String?): List<String> =
        mutators?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

    /** MUTATORS as a log fragment ("a.b, c.d"), or null when it names nobody. */
    fun writers(mutators: String?): String? {
        val names = entries(mutators)
        if (names.isEmpty()) return null
        val shown = names.take(MAX_ENTRIES).joinToString(", ") { sanitizeEntry(it) }
        return if (names.size > MAX_ENTRIES) "$shown and ${names.size - MAX_ENTRIES} more" else shown
    }

    /**
     * The upload's line for an event that is not on Proton yet. [calendarName] is the row's calendar, ours. The app(s)
     * that wrote it and the calendar's name only when [named], which is while Verbose logging is on ([LogNames]);
     * otherwise the line says "written by an app" (this app's own Move writes a row too) and names no calendar.
     */
    fun newEventLine(androidEventId: Long, mutators: String?, calendarName: String?, named: Boolean = LogNames.shown()): String {
        val writer = writers(mutators)
        val by = when {
            writer == null -> "no writer recorded"
            named -> "written by $writer"
            else -> "written by an app"
        }
        val where = calendarName?.takeIf { named }?.let { " in '$it'" }.orEmpty()
        return "Local event $androidEventId (new, $by)$where; uploading to Proton"
    }

    /**
     * One entry as a log line or the screen may show it: at most [MAX_ENTRY_LENGTH] characters, each a package-name
     * character (letters, digits and ._:-) or else '?', so no entry can fake a log line or carry a separator.
     */
    fun sanitizeEntry(name: String): String =
        name.take(MAX_ENTRY_LENGTH).map { c ->
            if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in "._:-") c else '?'
        }.joinToString("")
}

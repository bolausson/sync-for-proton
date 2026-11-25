package me.proton.sync

/**
 * Names that other people and apps chose, in the log lines that 2.2.0 added: a calendar's name and the package of the
 * app that wrote an event appear only while Verbose logging is on ([SyncLog.isVerboseEnabled], the app's "Verbose
 * logging" switch, SyncPreferences.KEY_VERBOSE_LOGGING). Otherwise a line names the calendar by an id, which says
 * nothing about the user, and the app as "another app" or "an app". The log goes into Send report, and which health or
 * booking app writes into someone's calendar, or what their calendars are called, is theirs to share.
 */
internal object LogNames {

    /** Whether a log line may carry such a name now: only while Verbose logging is on. */
    fun shown(): Boolean = SyncLog.isVerboseEnabled()

    /** A calendar a line names on its own: its [name] quoted ('Home') while [shown], else "calendar <[id]>". */
    fun calendar(name: String?, id: Any?): String = when {
        shown() && !name.isNullOrBlank() -> "'$name'"
        id != null -> "calendar $id"
        else -> "a calendar"
    }

    /** What follows a calendar's id in a line: " ('[name]')" while [shown] and there is a name, else nothing. */
    fun named(name: String?): String = if (shown() && !name.isNullOrBlank()) " ('$name')" else ""

    /** The app(s) that wrote an event ([writers], sanitized already) while [shown], else [instead]; null stays null. */
    fun app(writers: String?, instead: String = "another app"): String? = writers?.let { if (shown()) it else instead }
}

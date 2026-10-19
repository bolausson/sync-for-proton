package me.proton.sync.calendar

import net.fortuna.ical4j.data.ParserException

/**
 * What a log may say about an event's iCal: its structure, never its values.
 *
 * An event that failed to parse used to log the first 800 characters of the DECRYPTED iCal, and one
 * without a VEVENT the first 400: titles, descriptions, locations and attendee lists. That broke the
 * promise the app makes about its logs (email addresses and calendar names, never the contents of
 * events), and shared debug logs are exactly where it would have surfaced.
 *
 * The parser's own message is not safe either. ical4j wraps whatever its inner parser threw, so the
 * message and the cause quote the token it read, the date it could not parse or the URI it rejected.
 * With debug logging on, SyncLog prints a thrown exception's whole stack trace, messages included,
 * so the ParserException must not be rethrown either; [UnparseableEventException] replaces it.
 *
 * What stays useful without the values: the property on the failing line, the exception types, and
 * the sequence of property names, which is usually enough to spot a missing END, a broken fold or a
 * component nobody expected.
 */

/** Thrown in place of ical4j's [ParserException], carrying only an [iCalParseFailure] description. */
internal class UnparseableEventException(message: String) : Exception(message)

/** RFC 5545 and RFC 7986 property names. Anything else, bar an X- extension, is masked. */
private val PROPERTIES = setOf(
    "BEGIN", "END", "CALSCALE", "METHOD", "PRODID", "VERSION", "ATTACH", "CATEGORIES", "CLASS",
    "COMMENT", "DESCRIPTION", "GEO", "LOCATION", "PERCENT-COMPLETE", "PRIORITY", "RESOURCES", "STATUS",
    "SUMMARY", "COMPLETED", "DTEND", "DUE", "DTSTART", "DURATION", "FREEBUSY", "TRANSP", "TZID", "TZNAME",
    "TZOFFSETFROM", "TZOFFSETTO", "TZURL", "ATTENDEE", "CONTACT", "ORGANIZER", "RECURRENCE-ID",
    "RELATED-TO", "URL", "UID", "EXDATE", "RDATE", "RRULE", "ACTION", "REPEAT", "TRIGGER", "CREATED",
    "DTSTAMP", "LAST-MODIFIED", "SEQUENCE", "REQUEST-STATUS", "NAME", "REFRESH-INTERVAL", "SOURCE",
    "COLOR", "IMAGE", "CONFERENCE",
)
private val COMPONENTS = setOf(
    "VCALENDAR", "VEVENT", "VTODO", "VJOURNAL", "VFREEBUSY", "VTIMEZONE", "STANDARD", "DAYLIGHT", "VALARM",
    "VAVAILABILITY", "AVAILABLE",
)
private val EXTENSION = Regex("^X-[A-Z0-9-]{1,62}$")
private const val MAX_NAMES = 80

private fun isContinuation(line: String) = line.startsWith(" ") || line.startsWith("\t")

private fun known(name: String, names: Set<String>) = name in names || EXTENSION.matches(name)

/**
 * The property name a raw content line starts with, or "?" when it is not a known name. A broken
 * fold leaves free text at the start of a line, and a word in front of a colon ("Agenda: ...") would
 * otherwise pass for a name. BEGIN and END keep their value when it is a component name, because
 * that is structure and the most telling part of the shape.
 */
private fun propertyNameOf(line: String): String {
    val end = line.indexOfFirst { it == ':' || it == ';' }
    if (end <= 0) return "?"
    val name = line.substring(0, end).uppercase()
    if (!known(name, PROPERTIES)) return "?"
    if (line[end] == ':' && (name == "BEGIN" || name == "END")) {
        val component = line.substring(end + 1).trim().uppercase()
        if (known(component, COMPONENTS)) return "$name:$component"
    }
    return name
}

/** Length and the ordered property names of [iCal], e.g. `212 chars, 9 lines: BEGIN:VCALENDAR,...`. */
internal fun iCalShape(iCal: String): String {
    val names = iCal.lines().filter { it.isNotEmpty() && !isContinuation(it) }.map(::propertyNameOf)
    val more = if (names.size > MAX_NAMES) ",+${names.size - MAX_NAMES} more" else ""
    return "${iCal.length} chars, ${names.size} lines: ${names.take(MAX_NAMES).joinToString(",")}$more"
}

/**
 * Where and how [iCal] failed to parse, without the parser's message. ical4j numbers raw (folded)
 * lines from 1; a failure reported on a continuation line is attributed to the line it continues.
 */
internal fun iCalParseFailure(iCal: String, e: ParserException): String {
    val raw = iCal.lines()
    var i = e.lineNo - 1
    while (i in raw.indices && isContinuation(raw[i])) i--
    val property = if (i in raw.indices) " (${propertyNameOf(raw[i])})" else ""
    val cause = e.cause?.let { ", caused by ${it.javaClass.simpleName}" } ?: ""
    return "line ${e.lineNo}$property, ${e.javaClass.simpleName}$cause; ${iCalShape(iCal)}"
}

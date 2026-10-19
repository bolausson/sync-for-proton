package me.proton.sync.calendar

/**
 * iCalendar (RFC 5545) formatting and parsing utilities.
 *
 * Centralizes date/time formatting for DTSTART, DTEND, EXDATE, and DTSTAMP
 * properties, plus ISO 8601 duration parsing used by CalendarContract.
 *
 * All timestamp methods produce strings WITHOUT the iCal property prefix
 * (e.g., "20260326T140000Z" not "DTSTART:20260326T140000Z").
 */
object ICalUtils {

    /**
     * This app's PRODID (RFC 5545 3.7.3) on every iCalendar object it sends: the parts it uploads and the
     * invitation .ics, and on the merged download it keeps on the phone. It names this app, its maker and
     * its version. Backups use [me.proton.sync.backup.CalendarBackupBuilder.PRODID], which has no version.
     * Proton's clients never branch on PRODID: Proton web's unwrap() (WebClients calendar helper.ts) drops
     * the VCALENDAR header, PRODID included, before a stored part is parsed.
     */
    const val PRODID = "-//BEO//Sync for Proton ${me.proton.sync.BuildConfig.VERSION_NAME}//EN"

    /**
     * Format epoch millis as a UTC iCal timestamp: `yyyyMMdd'T'HHmmss'Z'`
     *
     * Used for DTSTAMP, UTC DTSTART/DTEND, and UTC EXDATE values.
     * Example: 1774530000000 → "20260326T130000Z"
     */
    fun formatTimestamp(millis: Long): String {
        val instant = java.time.Instant.ofEpochMilli(millis)
        val zdt = instant.atZone(java.time.ZoneOffset.UTC)
        return java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").format(zdt)
    }

    /**
     * Format epoch millis as a localized iCal timestamp: `yyyyMMdd'T'HHmmss`
     *
     * Used for timezone-aware DTSTART/DTEND (combined with TZID parameter).
     * Example: 1774530000000, "Europe/Berlin" → "20260326T140000"
     */
    fun formatLocalTimestamp(millis: Long, tzId: String): String {
        val instant = java.time.Instant.ofEpochMilli(millis)
        val zdt = instant.atZone(java.time.ZoneId.of(tzId))
        return java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss").format(zdt)
    }

    /**
     * Format epoch seconds as a UTC iCal timestamp: `yyyyMMdd'T'HHmmss'Z'`
     *
     * Same output format as [formatTimestamp] but accepts Unix epoch seconds
     * instead of milliseconds. Used by [me.proton.sync.crypto.CalendarDecryptor]
     * where Proton API returns timestamps in seconds.
     *
     * Example: 1774530000 → "20260326T130000Z"
     */
    fun formatTimestampFromSeconds(epochSeconds: Long): String {
        val instant = java.time.Instant.ofEpochSecond(epochSeconds)
        val zdt = instant.atZone(java.time.ZoneOffset.UTC)
        return java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").format(zdt)
    }

    /**
     * Format epoch millis as a date-only iCal value: `yyyyMMdd`
     *
     * Used for all-day events (VALUE=DATE) and all-day EXDATE values.
     * Example: 1774530000000 → "20260326"
     */
    fun formatDate(millis: Long): String {
        val instant = java.time.Instant.ofEpochMilli(millis)
        val zdt = instant.atZone(java.time.ZoneOffset.UTC)
        return java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd").format(zdt)
    }

    /**
     * Normalize an Android EVENT_TIMEZONE value to a name acceptable to Proton's API.
     *
     * Samsung Calendar (and old imports) emit POSIX-style "GMT+02:00" / "GMT-03:00" /
     * "GMT+5", which Proton rejects with `code=2001 'Invalid timezone'`. Proton's whitelist
     * accepts geographic IANA names (e.g. "Europe/Berlin") but NOT the fixed-offset
     * `Etc/UTC` / `Etc/GMT±N` family — verified empirically against `code=2001`.
     *
     * Fixed-offset zones carry no geographic intent, so we collapse them to "UTC". Event
     * timestamps in CalendarContract are absolute epoch millis, so emitting them in UTC
     * preserves the moment exactly; only the wall-clock-at-original-offset display hint
     * is lost (which was never meaningful for a POSIX-style offset).
     *
     * - "GMT", "GMT±H", "GMT±H:MM" → "UTC"
     * - "Etc/UTC", "Etc/Universal", "Etc/Zulu", "UCT", "Universal", "Zulu" → "UTC"
     * - "Etc/GMT", "Etc/GMT±N" → "UTC"
     * - Any geographic name → mapped to a Proton-accepted zone via [ProtonTimezones]:
     *   deprecated IANA aliases are canonicalized (e.g. "Europe/Belfast" → "Europe/London",
     *   "Asia/Calcutta" → "Asia/Kolkata") and zones Proton's API rejects are redirected to
     *   a supported one. This is what fixes events written by apps (e.g. Business Calendar
     *   Pro) that use deprecated zone ids Proton would otherwise reject with `code=2001`.
     */
    fun normalizeTimezone(tz: String): String {
        val trimmed = tz.trim()
        if (trimmed.isEmpty()) return "UTC"
        val utcAliases = setOf("GMT", "UTC", "UCT", "ZULU", "UNIVERSAL",
            "ETC/UTC", "ETC/UCT", "ETC/GMT", "ETC/ZULU", "ETC/UNIVERSAL", "ETC/GMT0", "ETC/GMT+0", "ETC/GMT-0")
        if (utcAliases.contains(trimmed.uppercase())) return "UTC"
        val isGmtOffset = Regex("^GMT([+-])(\\d{1,2})(?::(\\d{2}))?$", RegexOption.IGNORE_CASE)
            .matches(trimmed)
        if (isGmtOffset) return "UTC"
        val isEtcGmtOffset = Regex("^Etc/GMT([+-])(\\d{1,2})$", RegexOption.IGNORE_CASE)
            .matches(trimmed)
        if (isEtcGmtOffset) return "UTC"
        return ProtonTimezones.resolve(trimmed)
    }

    /**
     * Clamp RRULE values to Proton's accepted limits: COUNT ≤ 499 and UNTIL ≤ 2037-12-31
     * (the end of Proton's representable recurrence window).
     *
     * Official clients REJECT such rules outright at their edit/import gates
     * (android-calendar `IcsSurgeryUtils.kt`, web `rrule.ts`); a sync bridge has no
     * gate, so clamping preserves the series within Proton's window instead of
     * producing a server-rejected upload. The UNTIL value form is preserved
     * (DATE / DATE-TIME / DATE-TIME-UTC).
     *
     * Returns the input unchanged (including null/empty) when no limit is exceeded.
     */
    fun clampRrule(rrule: String?): String? {
        if (rrule.isNullOrEmpty()) return rrule
        var result: String = rrule
        val countMatch = Regex("COUNT=(\\d+)").find(result)
        val count = countMatch?.groupValues?.get(1)?.toIntOrNull()
        if (count != null && count > 499) {
            result = result.replace(countMatch.value, "COUNT=499")
        }
        val untilMatch = Regex("UNTIL=([0-9]{8})(T[0-9]{6}Z?)?").find(result)
        if (untilMatch != null && untilMatch.groupValues[1] > "20371231") {
            val timePart = untilMatch.groupValues[2]
            val clamped = when {
                timePart.isEmpty() -> "20371231"
                timePart.endsWith("Z") -> "20371231T235959Z"
                else -> "20371231T235959"
            }
            result = result.replace(untilMatch.value, "UNTIL=$clamped")
        }
        return result
    }

    /**
     * Parse Android CalendarContract DURATION string to milliseconds.
     *
     * Handles ISO 8601 duration variants used by Android:
     * - "P3600S" → 3,600,000 ms (note: missing T before S is an Android quirk)
     * - "PT1H" → 3,600,000 ms
     * - "PT30M" → 1,800,000 ms
     * - "P1D" → 86,400,000 ms
     * - "P1DT2H30M" → 95,400,000 ms
     *
     * Returns 3,600,000 ms (1 hour) as a fallback if parsing produces 0.
     */
    fun parseDurationToMillis(duration: String): Long {
        var total = 0L
        var s = duration.removePrefix("P")
        // Handle days
        val dayMatch = Regex("(\\d+)D").find(s)
        if (dayMatch != null) {
            total += dayMatch.groupValues[1].toLong() * 86400_000
            s = s.removeRange(dayMatch.range)
        }
        // Remove T separator
        s = s.removePrefix("T")
        // Handle hours
        val hourMatch = Regex("(\\d+)H").find(s)
        if (hourMatch != null) total += hourMatch.groupValues[1].toLong() * 3600_000
        // Handle minutes
        val minMatch = Regex("(\\d+)M").find(s)
        if (minMatch != null) total += minMatch.groupValues[1].toLong() * 60_000
        // Handle seconds
        val secMatch = Regex("(\\d+)S").find(s)
        if (secMatch != null) total += secMatch.groupValues[1].toLong() * 1000
        return if (total > 0) total else 3600_000 // default 1 hour
    }

    /**
     * Fold one content line per RFC 5545 section 3.1: at most 75 octets per physical line, each
     * continuation starting with a single space (so 74 octets of content), breaks being CRLF.
     *
     * Folds only BETWEEN characters. The two folds this replaced cut at fixed positions: the
     * decryptor's at byte offsets, decoding each piece on its own, so a multi-byte character on a
     * boundary ("ü", any Cyrillic or CJK text) came back as two U+FFFD replacement characters in
     * the phone's copy of the event, which a later local edit would upload to Proton; the
     * invitation sender's by UTF-16 units, which could split an emoji and let non-ASCII lines run
     * past 75 octets. For ASCII the output is unchanged.
     */
    fun foldLine(line: String): String {
        if (line.toByteArray(Charsets.UTF_8).size <= 75) return line
        val sb = StringBuilder(line.length + line.length / 24)
        var used = 0
        var limit = 75
        var i = 0
        while (i < line.length) {
            val cp = line.codePointAt(i)
            val size = when {
                cp < 0x80 -> 1
                cp < 0x800 -> 2
                cp < 0x10000 -> 3
                else -> 4
            }
            if (used + size > limit) {
                sb.append("\r\n ")
                used = 0
                limit = 74
            }
            sb.appendCodePoint(cp)
            used += size
            i += Character.charCount(cp)
        }
        return sb.toString()
    }

    /**
     * A TEXT value (RFC 5545 3.3.11). Line breaks of any kind become the escaped `\n`, so no bare
     * CR reaches a content line; other control characters are not allowed in TEXT and are
     * dropped, except TAB. The backslash goes first so the escapes added after it stay single.
     */
    fun escapeText(text: String): String {
        return text
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace('\u000B', '\n')
            .replace('\u000C', '\n')
            .filter { it == '\n' || it == '\t' || (it >= ' ' && it != '\u007F') }
            .replace("\\", "\\\\")
            .replace("\n", "\\n")
            .replace(",", "\\,")
            .replace(";", "\\;")
    }

    /** One TEXT property as a folded content line with its CRLF: `NAME:escaped value`. */
    fun textProperty(name: String, value: String): String = foldLine("$name:${escapeText(value)}") + "\r\n"

    /**
     * At most [max] UTF-16 units of [s], as Proton's own clients cut (a JS slice), but never ending
     * in the first half of a surrogate pair, which would leave half an emoji.
     */
    fun truncateText(s: String, max: Int): String {
        if (s.length <= max) return s
        val cut = if (max > 0 && Character.isHighSurrogate(s[max - 1])) max - 1 else max
        return s.substring(0, cut)
    }

    /**
     * The event's encrypted shared part (SharedEventContent type 3): title, description and location,
     * escaped and folded as Proton's own clients write them (ical.js on the web, biweekly on Android).
     *
     * They used to go in raw. A line break in a description or location then broke Proton web's
     * parser: it glued the lines together, lost the title after a raw ';', or took a description line
     * such as "Summary: x" for the title, and saving the event on the web made that permanent. Our own
     * download lost text too (a line "NOTE: ..." became a property). The server cannot read this part,
     * so nothing but the other clients depends on its form. [created] is set on a create only;
     * [recurrenceIdLine] is passed through with its CRLF, or empty. Title, description and location are
     * already cut to Proton's limits, which apply before escaping.
     */
    fun sharedEncryptedPart(
        prodIdLine: String,
        uid: String,
        dtStamp: String,
        created: String?,
        recurrenceIdLine: String,
        title: String,
        description: String,
        location: String
    ): String {
        val cr = "\r\n"
        return "BEGIN:VCALENDAR${cr}${prodIdLine}VERSION:2.0${cr}BEGIN:VEVENT${cr}" +
            "UID:$uid${cr}" +
            "DTSTAMP:$dtStamp${cr}" +
            (if (created != null) "CREATED:$created${cr}" else "") +
            recurrenceIdLine +
            textProperty("SUMMARY", title) +
            (if (description.isNotEmpty()) textProperty("DESCRIPTION", description) else "") +
            (if (location.isNotEmpty()) textProperty("LOCATION", location) else "") +
            "END:VEVENT${cr}END:VCALENDAR"
    }

    /**
     * The three structural values a mapping row caches as its baseline: the event's start and end
     * lines, verbatim, and its recurrence rule.
     *
     * [timeBlock] is PROPERTY-ONLY text, already unfolded, as the upload builds it: a DTSTART line and
     * an optional DTEND line, never DURATION. Pointed at a whole VCALENDAR this breaks, because a
     * VTIMEZONE's rules carry DTSTART lines of their own and the first one found would be a timezone
     * rule instead of the event's start.
     *
     * [rrule] is the row's rule without the "RRULE:" name, already clamped. An empty string stays
     * empty and must not become null: decideUpdateSequence compares the two sides with
     * (server ?: "") != (current ?: ""), so turning "" into null there would read as a change.
     */
    data class StructuralLines(val dtStart: String?, val dtEnd: String?, val rrule: String?)

    fun structuralLines(timeBlock: String, rrule: String?) = StructuralLines(
        dtStart = timeBlock.lines().firstOrNull { it.startsWith("DTSTART") }?.trim(),
        dtEnd = timeBlock.lines().firstOrNull { it.startsWith("DTEND") }?.trim(),
        rrule = rrule?.trim()
    )

    /**
     * The same three values read off Proton's own signed copy of the event (SharedEventContent type 2),
     * where the rule arrives as a whole property line and the name has to come off.
     *
     * That strip is what makes the comparison in decideUpdateSequence apples to apples, because the
     * local form above never sees a property name. Lose it and every update looks structurally changed:
     * SEQUENCE climbs on a mere title edit and every guest is asked to answer the invitation again.
     *
     * [type2Data] is the event's type-2 parts joined and unfolded, property lines only. RECURRENCE-ID,
     * which a single-occurrence override carries, is kept out by the property NAME and not by the colon;
     * what the colon rejects is a bare or parameterised "RRULE" line, which would otherwise survive
     * removePrefix and read as a rule. The filter that must stay colon-LESS is the RECURRENCE-ID one in
     * extractEventCacheFields, because RECURRENCE-ID does carry parameters.
     */
    fun type2StructuralLines(type2Data: String) = StructuralLines(
        dtStart = type2Data.lines().firstOrNull { it.startsWith("DTSTART") }?.trim(),
        dtEnd = type2Data.lines().firstOrNull { it.startsWith("DTEND") }?.trim(),
        rrule = type2Data.lines().firstOrNull { it.startsWith("RRULE:") }
            ?.removePrefix("RRULE:")?.replace("\r", "")?.trim()
    )

    /**
     * The attendee list's own iCal envelope, which the upload encrypts under the event's shared session
     * key and sends as a further SharedEventContent part.
     *
     * [attendeeLines] are whole ATTENDEE property lines, each with its CRLF, as buildAttendeeIcs wrote
     * them (AttendeeIcsParts.icsLines), where every X-PM-TOKEN is hashed from the ROW's own UID.
     *
     * [uid] must be the UID Proton holds for the event: it rejects a part whose UID is not the stored one
     * with error 2008. Both callers are creates and pass the row's own UID, which is all a create has. The
     * update builds this same envelope itself, with the SERVER's UID, and is deliberately not a caller
     * (see buildUpdateSyncOp, which says why): any change to the shape below has to be made there too.
     */
    fun attendeesEncryptedPart(prodIdLine: String, uid: String, dtStamp: String, attendeeLines: String): String {
        val cr = "\r\n"
        return "BEGIN:VCALENDAR${cr}${prodIdLine}VERSION:2.0${cr}BEGIN:VEVENT${cr}" +
            "UID:$uid${cr}" +
            "DTSTAMP:$dtStamp${cr}" +
            attendeeLines +
            "END:VEVENT${cr}END:VCALENDAR"
    }

    /**
     * The event's signed structural part (SharedEventContent type 2): the properties Proton's server
     * reads and every other client trusts, in one fixed order, with SEQUENCE last.
     *
     * Every line argument arrives whole, with its own CRLF, or empty: [recurrenceIdLine], [timeBlock]
     * (DTSTART and an optional DTEND), [rruleLine], [exdateLine] and [organizerLine] are the strings the
     * upload already built, so no all-day or TZID decision is taken here.
     *
     * Two arguments differ by path and both are load-bearing. [uid] must be the UID Proton holds, which
     * is the server's for an update, since a third-party import is authoritative there, and the row's
     * own for a create, which has nothing else. [sequence] must be 0 on a create, or the parent's value
     * for a RECURRENCE-ID exception, or Gmail and Outlook drop the override as an old version
     * (RFC 5546 3.2.2.2); on an update it must climb monotonically and never fall below the parent's.
     */
    fun sharedStructuralPart(
        prodIdLine: String,
        uid: String,
        dtStamp: String,
        recurrenceIdLine: String,
        timeBlock: String,
        rruleLine: String,
        exdateLine: String,
        organizerLine: String,
        sequence: Int
    ): String {
        val cr = "\r\n"
        return "BEGIN:VCALENDAR${cr}${prodIdLine}VERSION:2.0${cr}BEGIN:VEVENT${cr}" +
            "UID:$uid${cr}" +
            "DTSTAMP:$dtStamp${cr}" +
            recurrenceIdLine +
            timeBlock +
            rruleLine +
            exdateLine +
            organizerLine +
            "SEQUENCE:$sequence${cr}" +
            "END:VEVENT${cr}END:VCALENDAR"
    }

    /**
     * The RECURRENCE-ID line naming one occurrence of a series, as the upload writes it.
     *
     * [instanceMillis] is the provider's ORIGINAL_INSTANCE_TIME, [allDay] its ORIGINAL_ALL_DAY, and
     * [timezone] the event's own zone. The three shapes are the three RFC 5545 allows and Proton accepts:
     * a DATE for an all-day occurrence, a zoned local time, or UTC.
     *
     * Shared because two places have to agree on it exactly: the upload, which sends it, and the
     * cancellation retry, which decides whether a queued cancellation is still about a live occurrence by
     * rebuilding this line for each candidate row and comparing. A disagreement there is silent: the
     * cancellation is dropped and the guest keeps a meeting that no longer exists.
     */
    fun recurrenceIdLine(instanceMillis: Long, allDay: Boolean, timezone: String): String = when {
        allDay -> "RECURRENCE-ID;VALUE=DATE:${formatDate(instanceMillis)}\r\n"
        timezone != "UTC" ->
            "RECURRENCE-ID;TZID=$timezone:${formatLocalTimestamp(instanceMillis, timezone)}\r\n"
        else -> "RECURRENCE-ID:${formatTimestamp(instanceMillis)}\r\n"
    }
}

package me.proton.sync.backup

import me.proton.sync.SyncLog
import net.fortuna.ical4j.data.CalendarBuilder
import net.fortuna.ical4j.data.CalendarOutputter
import net.fortuna.ical4j.model.Calendar
import net.fortuna.ical4j.model.Parameter
import net.fortuna.ical4j.model.TimeZoneRegistryFactory
import net.fortuna.ical4j.model.Property
import net.fortuna.ical4j.model.component.VEvent
import net.fortuna.ical4j.model.component.VTimeZone
import net.fortuna.ical4j.model.parameter.TzId
import net.fortuna.ical4j.model.property.DateProperty
import net.fortuna.ical4j.model.property.ProdId
import net.fortuna.ical4j.model.property.Version
import net.fortuna.ical4j.model.property.XProperty
import java.io.ByteArrayOutputStream
import java.io.StringReader

/**
 * Turns decrypted events back into a `.ics` file that other calendars will actually accept.
 *
 * The backup used to build a fresh [Calendar] and copy across only the VEVENTs, dropping every
 * VTIMEZONE that came with them. That leaves lines like `DTSTART;TZID=Europe/Amsterdam:20260821T200000`
 * with nothing in the file defining `Europe/Amsterdam`, which RFC 5545 forbids and which a good
 * many importers refuse outright. The backup looked complete and healthy right up to the moment
 * somebody needed it.
 *
 * There is a quieter half to the same bug. When a source event carries no VTIMEZONE of its own,
 * ical4j resolves the id through its bundled registry, and the registry maps zones onto whichever
 * definition shares their rules: an event in `Europe/Amsterdam` is written back out as
 * `Europe/Brussels`. The event stays at the right moment in time, so nothing looks wrong, but the
 * user's zone has been silently renamed in their own backup. Carrying the source definition across
 * fixes both at once, because the definition that arrived is the one that goes back out.
 *
 * Kept apart from [CalendarBackupBuilder] so the rule can be tested against real ical4j rather
 * than described.
 */
object IcsSerializer {

    private const val TAG = "IcsSerializer"

    private const val VEVENT = "VEVENT"
    private const val VTIMEZONE = "VTIMEZONE"

    private val registry by lazy { TimeZoneRegistryFactory.getInstance().createRegistry() }
    private val zoneCache = HashMap<String, VTimeZone?>()

    /** TZID= as it appears in the raw text, before ical4j has had a chance to rewrite it. */
    private val TZID_IN_TEXT = Regex("TZID=([^:;\r\n]+)")

    /**
     * Parses an event's iCal without letting ical4j rename the user's timezone.
     *
     * The rename happens during parsing, not output: ical4j resolves each TZID through its registry,
     * the registry consults `tz.alias` (`Europe/Amsterdam = Europe/Brussels`), and the date property
     * is bound to a zone named Brussels. By the time parsing returns, the TZID parameter itself
     * reads Brussels and the user's own id is simply gone from the model, so nothing downstream can
     * put it back.
     *
     * The ids are therefore read out of the raw text first, and the correct definition for each,
     * which ical4j ships as `zoneinfo/<id>.ics` and then declines to use, is registered before
     * parsing begins. A registered zone wins over the alias table. Anything that cannot be loaded
     * is left to the old path, so the worst case is the previous behaviour.
     *
     * Only the backup parses this way. The sync path is deliberately untouched: it converts to the
     * phone's own zone handling and has its own long-settled behaviour.
     */
    fun parseFaithfully(ical: String): Calendar {
        val ids = TZID_IN_TEXT.findAll(ical).map { it.groupValues[1].trim() }.filter { it.isNotEmpty() }.toSet()
        if (ids.isEmpty()) return CalendarBuilder().build(StringReader(ical))
        val faithful = TimeZoneRegistryFactory.getInstance().createRegistry()
        for (id in ids) {
            val definition = definitionNamed(id) ?: continue
            try {
                faithful.register(net.fortuna.ical4j.model.TimeZone(definition))
            } catch (e: Exception) {
                // Leave this id to the default resolution rather than failing the whole parse.
            }
        }
        return CalendarBuilder(faithful).build(StringReader(ical))
    }

    /**
     * Puts the event's reminders back into the VEVENT as VALARMs.
     *
     * Proton keeps reminders in the CLEARTEXT `Notifications` API field and deliberately does not
     * render them into the encrypted iCal - CalendarMapper says as much: "Proton does not put
     * VALARMs in the encrypted parts, so this is the only place reminders come from". The sync path
     * reads that field and writes real reminders to the phone. The backup read only the decrypted
     * iCal, so every backup this app has ever written contained no reminders at all, and restoring
     * from one silently lost every alert the user had set.
     *
     * Found by diffing Proton's own export of a restored calendar against the original: the source
     * event carried a DISPLAY alarm and an EMAIL alarm, and the restored copy carried neither.
     * Proton's exporter renders them, so this brings the backup in line with what Proton itself
     * considers a faithful export.
     *
     * A VALARM already present in the iCal is left alone - an event imported from another tool can
     * legitimately carry one, and the same fallback ordering is what CalendarMapper uses.
     *
     * Only EXPLICIT per-event reminders are written. A null Notifications means "inherit this
     * calendar's defaults", not "no reminders" (CalendarSyncAdapter substitutes
     * defaultNotificationsFor for exactly that case), and materialising the defaults into the file
     * would silently convert an inherited setting into a pinned per-event one. Restoring an
     * inherited event lets it inherit again, which is what it did before. Proton's own exporter
     * chooses the other way and writes the defaults out; the difference only shows if a backup is
     * restored into a calendar whose defaults differ from the original's.
     */
    fun addReminders(event: VEvent, notifications: List<me.proton.sync.api.models.EventNotification>?) {
        if (notifications.isNullOrEmpty()) return
        if (event.alarms?.isNotEmpty() == true) return
        for (notification in notifications) {
            val trigger = notification.trigger.trim()
            if (trigger.isEmpty()) continue
            // Type 0 = EMAIL, 1 = DEVICE, mirroring CalendarMapper.
            val action = if (notification.type == 0) "EMAIL" else "DISPLAY"
            val summary = event.summary?.value.orEmpty().ifBlank { "Reminder" }
            val text = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//x//x//EN\r\n" +
                "BEGIN:VEVENT\r\nUID:x\r\nDTSTAMP:20200101T000000Z\r\n" +
                "BEGIN:VALARM\r\nACTION:" + action + "\r\nTRIGGER:" + trigger + "\r\n" +
                me.proton.sync.calendar.ICalUtils.textProperty("DESCRIPTION", summary) + "END:VALARM\r\n" +
                "END:VEVENT\r\nEND:VCALENDAR\r\n"
            try {
                val alarm = CalendarBuilder().build(StringReader(text))
                    .getComponents<VEvent>(VEVENT).firstOrNull()?.alarms?.firstOrNull() ?: continue
                event.alarms.add(alarm)
            } catch (e: Exception) {
                // A trigger Proton accepted that ical4j will not parse is not worth losing the
                // whole event over; the reminder is dropped and the event still backed up.
                SyncLog.w(TAG, "Could not render reminder '$trigger' as a VALARM: ${e.javaClass.simpleName}")
            }
        }
    }

    /** The VTIMEZONE components of a parsed calendar, keyed by their TZID. */
    fun zonesOf(parsed: Calendar): Map<String, VTimeZone> {
        val out = LinkedHashMap<String, VTimeZone>()
        for (tz in parsed.getComponents<VTimeZone>(VTIMEZONE)) {
            val id = tz.timeZoneId?.value ?: continue
            if (!out.containsKey(id)) out[id] = tz
        }
        return out
    }

    /** Every TZID the events actually reference, in the order first seen. */
    fun referencedZoneIds(vevents: List<VEvent>): Set<String> {
        val ids = LinkedHashSet<String>()
        for (event in vevents) {
            for (property in event.properties) {
                val tzid = property.getParameter<TzId>(Parameter.TZID) ?: continue
                tzid.value?.let { ids.add(it) }
            }
        }
        return ids
    }

    /**
     * Serialises one calendar.
     *
     * @param zones definitions gathered from the source events. Each zone the events reference travels
     *   with them; one missing here is left undefined rather than invented (see the body), and a zone
     *   nothing references is dropped rather than carried as dead weight.
     */
    fun serialize(
        prodId: String,
        name: String,
        vevents: List<VEvent>,
        zones: Map<String, VTimeZone>,
    ): String {
        val calendar = Calendar()
        calendar.properties.add(ProdId(prodId))
        calendar.properties.add(Version.VERSION_2_0)
        if (name.isNotBlank()) calendar.properties.add(XProperty("X-WR-CALNAME", name))

        vevents.forEach(::dropRepeatedSingletons)
        // A VTIMEZONE the SOURCE supplied travels with its events. One is never INVENTED.
        //
        // Three attempts were made at inventing one and all three shipped wrong rules. ical4j's
        // bundled zoneinfo is frozen at 2023, so it still gives Paraguay the DST it abolished in
        // 2024. The platform's database looked like the answer until the test device was checked:
        // a Note 10 on Android 12 carries tzdata2021a, four years stale, and gets those same zones
        // wrong. There is no source of truth on a phone - whatever we emit is a snapshot of what
        // THIS device happens to know, and a device can be years behind.
        //
        // Emitting nothing is not a cop-out, it is the correct answer. The importer then resolves
        // the TZID against its own database, which is far likelier to be current, and that was
        // measurably right for every case tested. The cost is a file that names a zone it does not
        // define, which RFC 5545 disallows - a real cost, but a theoretical one here, since
        // Proton's own import accepts such a file (verified twice against a live account) while
        // the hour-long shift from shipping stale rules was measured, not supposed. A file
        // rejected loudly beats a file accepted and silently misread.
        //
        // The zone NAME is still ours to preserve, and parseFaithfully does that: it stops ical4j
        // renaming the user's Europe/Amsterdam to Europe/Brussels on the way in. The name is ours;
        // the rules are not.
        for (id in referencedZoneIds(vevents)) zones[id]?.let { calendar.components.add(it) }
        for (event in vevents) calendar.components.add(event)

        val bos = ByteArrayOutputStream()
        CalendarOutputter(false).output(calendar, bos) // validation OFF (see CalendarBackupBuilder)
        return bos.toString(Charsets.UTF_8.name())
    }

    /**
     * A VTIMEZONE built from the platform's own timezone database.
     *
     * This is preferred over anything ical4j ships, because what ical4j ships is frozen. Its
     * bundled zoneinfo is dated 2023-11-02, and timezone rules keep changing: Paraguay abolished
     * DST in October 2024, so `zoneinfo/America/Asuncion.ics` still carries an open-ended rule
     * returning the zone to -0400 every March, and Kazakhstan moved to UTC+5 in March 2024 while
     * the bundled Asia/Almaty still says +0600. Checked against tzdb 2025b, both are an hour out
     * for any event from 2025 onwards.
     *
     * That matters more than it looks. Before a VTIMEZONE was written at all, an importer resolved
     * the TZID against its OWN database and got the right answer; shipping a stale definition
     * overrides that with a wrong one. An invalid file is rejected loudly. A wrong file is accepted
     * silently and the appointment is simply an hour out.
     *
     * Android's tz database is updated with the platform, so java.time is the authority here and
     * the bundled copy is only a fallback for an id java.time does not know.
     */
    private fun platformDefinition(tzid: String): VTimeZone? = try {
        val rules = java.time.ZoneId.of(tzid).rules
        val body = StringBuilder()

        // HISTORY FIRST. A calendar is not only future dates, and the previous attempt at this
        // emitted only the recurring rules with DTSTART anchored at 2020, so an RRULE generated no
        // occurrence before then and every earlier date fell back to the raw standard offset:
        // a Berlin appointment in July 2015 restored an hour out, silently, because the DTSTART
        // line still matched Proton's byte for byte. Grouped by the offsets they move between,
        // exactly as a VTIMEZONE is meant to express them.
        val history = LinkedHashMap<Triple<Int, Int, Boolean>, MutableList<java.time.LocalDateTime>>()
        for (transition in rules.transitions) {
            val daylight = rules.getDaylightSavings(transition.instant).seconds != 0L
            val key = Triple(
                transition.offsetBefore.totalSeconds, transition.offsetAfter.totalSeconds, daylight
            )
            history.getOrPut(key) { mutableListOf() }.add(transition.dateTimeBefore)
        }
        for ((key, dates) in history) {
            val (from, to, daylight) = key
            val kind = if (daylight) "DAYLIGHT" else "STANDARD"
            body.append("BEGIN:").append(kind).append("\r\n")
                .append("DTSTART:").append(stamp(dates.first())).append("\r\n")
                .append("TZOFFSETFROM:").append(offsetOf(from)).append("\r\n")
                .append("TZOFFSETTO:").append(offsetOf(to)).append("\r\n")
            for (extra in dates.drop(1)) body.append("RDATE:").append(stamp(extra)).append("\r\n")
            body.append("END:").append(kind).append("\r\n")
        }

        // Then the recurring rules for everything after the last recorded transition. A zone that
        // has stopped switching has none, and its final historical observance simply runs on.
        val lastHistoricYear = rules.transitions.lastOrNull()?.dateTimeBefore?.year
        for (rule in rules.transitionRules) {
            body.append(observanceOf(rule, (lastHistoricYear ?: 2019) + 1))
        }

        if (body.isEmpty()) {
            // No transitions and no rules: a genuinely fixed offset. One observance, from the
            // epoch, so the file still defines what it names.
            val offset = rules.getOffset(java.time.Instant.now()).totalSeconds
            body.append("BEGIN:STANDARD\r\nDTSTART:19700101T000000\r\n")
                .append("TZOFFSETFROM:").append(offsetOf(offset)).append("\r\n")
                .append("TZOFFSETTO:").append(offsetOf(offset)).append("\r\nEND:STANDARD\r\n")
        }
        parseZone(
            "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Sync for Proton//EN\r\n" +
                "BEGIN:VTIMEZONE\r\nTZID:" + tzid + "\r\n" + body + "END:VTIMEZONE\r\nEND:VCALENDAR\r\n"
        )
    } catch (e: Exception) {
        null
    }

    /** Local date-time in iCal form. */
    private fun stamp(t: java.time.LocalDateTime): String =
        "%04d%02d%02dT%02d%02d%02d".format(t.year, t.monthValue, t.dayOfMonth, t.hour, t.minute, t.second)

    /** iCal offset form, e.g. +0200 / -0330. */
    private fun offsetOf(offset: java.time.ZoneOffset): String = offsetOf(offset.totalSeconds)

    private fun offsetOf(total: Int): String {
        val sign = if (total < 0) "-" else "+"
        val abs = kotlin.math.abs(total)
        return "%s%02d%02d".format(sign, abs / 3600, (abs % 3600) / 60)
    }

    /**
     * One STANDARD or DAYLIGHT observance for a recurring transition rule.
     *
     * The BYDAY spec is the fiddly part. java.time expresses "last Sunday in March" as
     * dayOfMonthIndicator=25 with dayOfWeek=SUNDAY, meaning "the first Sunday on or after the
     * 25th" - and in a 31-day month that IS the last Sunday, but writing it as BYDAY=4SU would be
     * wrong in years where the month starts such that a fifth Sunday exists. So a window that
     * reaches the end of the month becomes BYDAY=-1, and anything else becomes its ordinal.
     */
    private fun observanceOf(rule: java.time.zone.ZoneOffsetTransitionRule, firstYear: Int): String {
        val isDaylight = rule.offsetAfter.totalSeconds > rule.standardOffset.totalSeconds
        val kind = if (isDaylight) "DAYLIGHT" else "STANDARD"
        val start = rule.createTransition(firstYear).dateTimeBefore
        val month = rule.month
        val dom = rule.dayOfMonthIndicator
        val dow = rule.dayOfWeek
        val by = when {
            dow == null -> "BYMONTHDAY=" + dom
            dom < 0 -> "BYDAY=-1" + dow.name.take(2)
            dom + 6 >= month.length(false) -> "BYDAY=-1" + dow.name.take(2)
            else -> "BYDAY=" + ((dom - 1) / 7 + 1) + dow.name.take(2)
        }
        return "BEGIN:" + kind + "\r\n" +
            "DTSTART:" + "%04d%02d%02dT%02d%02d%02d".format(
                start.year, start.monthValue, start.dayOfMonth, start.hour, start.minute, start.second
            ) + "\r\n" +
            "TZOFFSETFROM:" + offsetOf(rule.offsetBefore) + "\r\n" +
            "TZOFFSETTO:" + offsetOf(rule.offsetAfter) + "\r\n" +
            "RRULE:FREQ=YEARLY;BYMONTH=" + month.value + ";" + by + "\r\n" +
            "END:" + kind + "\r\n"
    }

    /**
     * A definition that is actually NAMED for the zone the user asked for.
     *
     * ical4j does not have one. `zoneinfo/Europe/Amsterdam.ics` exists in the jar, and its contents
     * read `TZID:Europe/Brussels`: the alias is baked into the shipped data, not merely applied on
     * lookup. So there is no way to fetch a correctly named Amsterdam from the library, and the
     * definition has to be renamed to the id it was requested under.
     *
     * That rename is safe precisely because the alias exists. ical4j aliases two zones only when
     * their observances are identical, so relabelling changes nothing about when the offsets apply;
     * it restores the name the user's calendar actually uses. Doing it as text and re-parsing keeps
     * the result a genuine parsed component rather than a hand-assembled one.
     */
    private fun definitionNamed(tzid: String): VTimeZone? = zoneCache.getOrPut(tzid) {
        platformDefinition(tzid)?.let { return@getOrPut it }
        val text = bundledText(tzid)
        val parsed = text?.let { parseZone(it) } ?: registry.getTimeZone(tzid)?.vTimeZone
        when {
            parsed == null -> null
            parsed.timeZoneId?.value == tzid -> parsed
            text != null -> parseZone(renamed(text, tzid)) ?: parsed
            else -> parsed
        }
    }

    /**
     * Rewrites every place the shipped definition names the alias instead of the zone.
     *
     * `zoneinfo/Europe/Amsterdam.ics` says Brussels three times: TZID, TZURL and X-LIC-LOCATION.
     * TZID is the one that matters for correctness, but a file whose location line still reads
     * Brussels shows the wrong city in the clients that display it, and the TZURL would send anyone
     * following it to the wrong page. If the whole point is that the backup keeps the user's own
     * zone, it should not name someone else's anywhere.
     */
    private fun renamed(text: String, tzid: String): String =
        text.lineSequence()
            .filterNot { it.startsWith("TZURL:") } // would point at the wrong zone’s page
            .map {
                when {
                    it.startsWith("TZID:") -> "TZID:$tzid"
                    it.startsWith("X-LIC-LOCATION:") -> "X-LIC-LOCATION:$tzid"
                    else -> it
                }
            }
            .joinToString("\r\n")

    private fun bundledText(tzid: String): String? = try {
        IcsSerializer::class.java.classLoader
            ?.getResourceAsStream("zoneinfo/$tzid.ics")
            ?.use { it.readBytes().toString(Charsets.UTF_8) }
    } catch (e: Exception) {
        null
    }

    private fun parseZone(text: String): VTimeZone? = try {
        CalendarBuilder().build(StringReader(text)).getComponents<VTimeZone>(VTIMEZONE).firstOrNull()
    } catch (e: Exception) {
        null
    }

    /**
     * Properties RFC 5545 section 3.6.1 says MUST NOT occur more than once in a VEVENT.
     *
     * Deliberately excludes the ones that legitimately repeat - ATTENDEE, EXDATE, RDATE, CATEGORIES,
     * COMMENT, ATTACH, RELATED-TO, RESOURCES and any X- property - because stripping those would
     * destroy real data. RRULE is in: the spec says SHOULD NOT rather than MUST NOT, but a second
     * RRULE is unreadable to most clients and Proton never emits one.
     */
    private val AT_MOST_ONCE = setOf(
        "UID", "DTSTAMP", "DTSTART", "DTEND", "DURATION", "SUMMARY", "DESCRIPTION", "LOCATION",
        "SEQUENCE", "STATUS", "CREATED", "LAST-MODIFIED", "ORGANIZER", "CLASS", "TRANSP",
        "PRIORITY", "URL", "GEO", "RECURRENCE-ID", "RRULE",
    )

    /**
     * Removes the repeats Proton's storage layout leaves behind.
     *
     * An event's iCal does not arrive in one piece: Proton splits it across content parts, and each
     * part is its own little VCALENDAR carrying the identifying properties again. Merging the parts
     * therefore produced a VEVENT holding UID four times and DTSTAMP three times, which RFC 5545
     * forbids outright, and every event in every backup this app has ever written was malformed
     * that way.
     *
     * It was invisible because the sync path never notices: it reads single values out to the
     * Android provider and the repeats fall away. Only the backup serialises the merged component
     * back out again, and only a comparison of one backup against another, across a restore,
     * surfaced it - Proton's own importer had quietly dropped ORGANIZER, CREATED and STATUS from
     * the events it was given, which is what a lenient parser does with a component it cannot
     * reconcile. A stricter one is entitled to reject the file altogether.
     *
     * The first occurrence wins. A later one carrying a DIFFERENT value is a genuine disagreement
     * between parts rather than a duplicate, so it is logged rather than dropped silently.
     */
    private fun dropRepeatedSingletons(event: VEvent) {
        val seen = HashMap<String, String>()
        val identical = HashSet<String>()
        val dropAt = ArrayList<Int>()
        event.properties.forEachIndexed { index, property ->
            val name = property.name?.uppercase() ?: return@forEachIndexed
            // An EXACT repeat is dropped whatever the property is, including ones that may
            // legitimately occur more than once. The content-part merge produced two identical
            // EXDATE lines on a recurring event - the same excluded occurrence listed twice, which
            // means nothing more than listing it once, and which Proton's own export does not do.
            // Only byte-identical repeats go; two EXDATEs for DIFFERENT dates are real data.
            val fingerprint = property.toString().trim()
            if (!identical.add(fingerprint)) {
                dropAt.add(index)
                return@forEachIndexed
            }
            if (name !in AT_MOST_ONCE) return@forEachIndexed
            val kept = seen[name]
            if (kept == null) {
                seen[name] = property.value.orEmpty()
            } else {
                if (kept != property.value.orEmpty()) {
                    SyncLog.w(
                        TAG,
                        "Event ${seen["UID"] ?: "?"}: $name appears more than once with different " +
                            "values; keeping the first"
                    )
                }
                dropAt.add(index)
            }
        }
        // By POSITION, never by value. ical4j's Property.equals compares name, value and
        // parameters, so removeAll() handed a set of duplicates deletes the one being kept along
        // with them: the first attempt at this left events with no UID at all, which is far worse
        // than the repetition it was written to fix. Reversed so earlier indices stay valid.
        for (index in dropAt.asReversed()) event.properties.removeAt(index)
    }

}

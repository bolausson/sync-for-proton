package me.proton.sync.calendar

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * The queue of cancellation emails that could not be sent (PendingCancellations.kt), and the
 * cancellation built for a deleted event or occurrence. The SharedPreferences store is not tested here.
 */
class PendingCancellationsTest {

    private val guest = "guest@example.com"
    private val other = "other@example.com"
    private val me = "me@example.org"

    private val min = 60_000L
    private val hour = 3_600_000L
    private val day = 86_400_000L
    private val now = 1_790_000_000_000L

    /** Monday 5 October 2026, 09:00 in Berlin (summer time). */
    private val start = Instant.parse("2026-10-05T07:00:00Z").toEpochMilli()
    private val dayStart = Instant.parse("2026-10-05T00:00:00Z").toEpochMilli()

    private val ridLine = "RECURRENCE-ID;TZID=Europe/Berlin:20261005T090000\r\n"
    private val ridLine2 = "RECURRENCE-ID;TZID=Europe/Berlin:20261012T090000\r\n"
    private val rruleLine = "RRULE:FREQ=WEEKLY;BYDAY=MO\r\n"

    private fun entry(
        uid: String = "uid-1",
        recurrenceIdLine: String? = null,
        recipients: List<String> = listOf(guest),
        sequence: Int = 1,
        wholeEvent: Boolean = true,
        createdAt: Long = now,
        attempts: Int = 1,
        nextAttemptAt: Long = now + 15 * min,
        summary: String = "Team sync",
        location: String? = "Room 1",
        sharedEventId: String? = "shared-1"
    ) = PendingCancellation(
        uid = uid, recurrenceIdLine = recurrenceIdLine, rruleLine = null, summary = summary, location = location,
        dtStart = start, dtEnd = start + hour, timezone = "Europe/Berlin", allDay = false, sequence = sequence,
        organizer = me, organizerName = "Me", recipients = recipients.map { PendingRecipient(it, null) },
        wholeEvent = wholeEvent, createdAt = createdAt, attempts = attempts, nextAttemptAt = nextAttemptAt,
        sharedEventId = sharedEventId
    )

    private fun emails(e: PendingCancellation) = e.recipients.map { it.email }

    // ---- backoff and first failure ----

    @Test
    fun `backoff starts at 15 minutes and doubles up to 12 hours`() {
        assertEquals(15 * min, PendingCancellations.backoffMillis(1))
        assertEquals(30 * min, PendingCancellations.backoffMillis(2))
        assertEquals(hour, PendingCancellations.backoffMillis(3))
        assertEquals(2 * hour, PendingCancellations.backoffMillis(4))
        assertEquals(4 * hour, PendingCancellations.backoffMillis(5))
        assertEquals(8 * hour, PendingCancellations.backoffMillis(6))
        assertEquals(12 * hour, PendingCancellations.backoffMillis(7))
        assertEquals(12 * hour, PendingCancellations.backoffMillis(8))
        assertEquals(12 * hour, PendingCancellations.backoffMillis(100))
        // No attempt yet counts like the first.
        assertEquals(15 * min, PendingCancellations.backoffMillis(0))
        assertEquals(15 * min, PendingCancellations.backoffMillis(-3))
    }

    @Test
    fun `a first failure counts one attempt and waits 15 minutes`() {
        val base = entry(createdAt = 0, attempts = 0, nextAttemptAt = 0)
        val failed = PendingCancellations.firstFailure(base, now)
        assertEquals(base.copy(createdAt = now, attempts = 1, nextAttemptAt = now + 15 * min), failed)
    }

    // ---- enqueue ----

    @Test
    fun `an entry without recipients is not queued`() {
        val queue = listOf(entry())
        assertEquals(queue, PendingCancellations.enqueue(queue, entry(uid = "uid-2", recipients = emptyList())))
        assertEquals(emptyList<PendingCancellation>(), PendingCancellations.enqueue(emptyList(), entry(recipients = emptyList())))
    }

    @Test
    fun `different events and occurrences stay apart`() {
        var queue = PendingCancellations.enqueue(emptyList(), entry())
        queue = PendingCancellations.enqueue(queue, entry(recurrenceIdLine = ridLine))
        queue = PendingCancellations.enqueue(queue, entry(recurrenceIdLine = ridLine2))
        queue = PendingCancellations.enqueue(queue, entry(uid = "uid-2"))
        assertEquals(4, queue.size)
        assertEquals(
            setOf(
                PendingCancellations.keyOf("uid-1", null),
                PendingCancellations.keyOf("uid-1", ridLine),
                PendingCancellations.keyOf("uid-1", ridLine2),
                PendingCancellations.keyOf("uid-2", null)
            ),
            queue.map { it.key }.toSet()
        )
    }

    @Test
    fun `the same occurrence merges guests sequence and the whole event flag`() {
        val existing = entry(
            recurrenceIdLine = ridLine, recipients = listOf(guest), sequence = 3, wholeEvent = false,
            createdAt = now - hour, attempts = 3, nextAttemptAt = now + 2 * hour, summary = "Old title"
        )
        // The same occurrence without the trailing line break, a guest in other letters, and a new guest.
        val newer = entry(
            recurrenceIdLine = ridLine.trim(), recipients = listOf("GUEST@Example.com", other), sequence = 2,
            wholeEvent = true, createdAt = now, attempts = 1, nextAttemptAt = now + 15 * min, summary = "New title",
            location = "Room 2"
        )
        val queue = PendingCancellations.enqueue(listOf(existing), newer)
        assertEquals(1, queue.size)
        val merged = queue[0]
        assertEquals(listOf(guest, other), emails(merged))
        assertEquals(3, merged.sequence)
        assertTrue(merged.wholeEvent)
        assertEquals("New title", merged.summary)
        assertEquals("Room 2", merged.location)
        // A new guest joined: the count and the age start again, and the sooner attempt is kept.
        assertEquals(1, merged.attempts)
        assertEquals(now, merged.createdAt)
        assertEquals(now + 15 * min, merged.nextAttemptAt)
    }

    @Test
    fun `a merge without new guests keeps the attempt count and the age`() {
        val existing = entry(
            recipients = listOf(guest, other), sequence = 4, wholeEvent = true,
            createdAt = now - hour, attempts = 3, nextAttemptAt = now + 2 * hour
        )
        val newer = entry(
            recipients = listOf("Guest@Example.COM"), sequence = 5, wholeEvent = false,
            createdAt = now, attempts = 1, nextAttemptAt = now + 15 * min, summary = "Renamed"
        )
        val merged = PendingCancellations.enqueue(listOf(existing), newer).single()
        assertEquals(listOf(guest, other), emails(merged))
        assertEquals(5, merged.sequence)
        assertTrue("a whole-event cancellation is never downgraded", merged.wholeEvent)
        assertEquals("Renamed", merged.summary)
        assertEquals(3, merged.attempts)
        assertEquals(now - hour, merged.createdAt)
        assertEquals(now + 15 * min, merged.nextAttemptAt)
    }

    @Test
    fun `the queue keeps at most 100 entries and drops the oldest`() {
        val full = (0 until 100).map { entry(uid = "u$it", createdAt = now + it) }
        val queue = PendingCancellations.enqueue(full, entry(uid = "new", createdAt = now + 1_000))
        assertEquals(100, queue.size)
        val uids = queue.map { it.uid }.toSet()
        assertFalse("u0" in uids)
        assertTrue("u1" in uids && "u99" in uids && "new" in uids)
        // A merge into a full queue adds no entry, so nothing is dropped.
        val mergedIn = PendingCancellations.enqueue(full, entry(uid = "u0", recipients = listOf(other), createdAt = now + 2_000))
        assertEquals(100, mergedIn.size)
        assertEquals(listOf(guest, other), emails(mergedIn.single { it.uid == "u0" }))
    }

    // ---- due, exhausted, after an attempt ----

    @Test
    fun `only entries whose time has come are due and soonest first`() {
        val queue = listOf(
            entry(uid = "later", nextAttemptAt = now + 1),
            entry(uid = "now", nextAttemptAt = now),
            entry(uid = "earlier", nextAttemptAt = now - hour)
        )
        assertEquals(listOf("earlier", "now"), PendingCancellations.due(queue, now).map { it.uid })
        assertTrue(PendingCancellations.due(queue, now - 2 * hour).isEmpty())
    }

    @Test
    fun `at most ten entries are due per run`() {
        val queue = (0 until 12).map { entry(uid = "u$it", nextAttemptAt = now - it * 1_000L) }
        val due = PendingCancellations.due(queue, now)
        assertEquals(10, due.size)
        assertEquals((11 downTo 2).map { "u$it" }, due.map { it.uid })
    }

    @Test
    fun `an entry is exhausted after five attempts or seven days`() {
        assertFalse(PendingCancellations.isExhausted(entry(attempts = 4, createdAt = now), now))
        assertTrue(PendingCancellations.isExhausted(entry(attempts = 5, createdAt = now), now))
        assertTrue(PendingCancellations.isExhausted(entry(attempts = 6, createdAt = now), now))
        assertTrue(PendingCancellations.isExhausted(entry(attempts = 1, createdAt = now - 7 * day), now))
        assertFalse(PendingCancellations.isExhausted(entry(attempts = 1, createdAt = now - 7 * day + 1), now))
    }

    @Test
    fun `an attempt that reached everybody leaves nothing`() {
        val e = entry(recipients = listOf(guest, other))
        assertNull(PendingCancellations.afterAttempt(e, emptySet(), now))
        assertNull(PendingCancellations.afterAttempt(e, setOf("stranger@example.com"), now))
    }

    @Test
    fun `an attempt keeps only the guests it missed and backs off further`() {
        val e = entry(recipients = listOf("Guest@Example.com", other), attempts = 1, nextAttemptAt = now - min)
        val after = PendingCancellations.afterAttempt(e, setOf(guest), now)
        assertEquals(
            e.copy(recipients = listOf(PendingRecipient("Guest@Example.com", null)), attempts = 2, nextAttemptAt = now + 30 * min),
            after
        )
        val again = PendingCancellations.afterAttempt(after!!, setOf(guest), now + hour)
        assertEquals(3, again!!.attempts)
        assertEquals(now + hour + hour, again.nextAttemptAt)
    }

    // ---- guests invited again ----

    @Test
    fun `a reinvited guest is taken off only that event or occurrence`() {
        val series = entry(recipients = listOf(guest, other))
        val occurrence = entry(recurrenceIdLine = ridLine, recipients = listOf("Guest@Example.com"))
        val otherEvent = entry(uid = "uid-2", recipients = listOf(guest))
        val queue = listOf(series, occurrence, otherEvent)

        val afterSeries = PendingCancellations.purgeReinvited(queue, "uid-1", null, setOf(" GUEST@example.com "))
        assertEquals(listOf(series.copy(recipients = listOf(PendingRecipient(other, null))), occurrence, otherEvent), afterSeries)

        // The occurrence loses its only guest and goes; the series and the other event keep theirs.
        val afterOccurrence = PendingCancellations.purgeReinvited(queue, "uid-1", ridLine.trim(), setOf(guest))
        assertEquals(listOf(series, otherEvent), afterOccurrence)
    }

    @Test
    fun `purging nobody or another event changes nothing`() {
        val queue = listOf(entry(recipients = listOf(guest, other)), entry(recurrenceIdLine = ridLine))
        assertEquals(queue, PendingCancellations.purgeReinvited(queue, "uid-1", null, emptySet()))
        assertEquals(queue, PendingCancellations.purgeReinvited(queue, "uid-9", null, setOf(guest)))
        assertEquals(queue, PendingCancellations.purgeReinvited(queue, "uid-1", ridLine2, setOf(guest)))
    }

    @Test
    fun `the key ignores the line break around the recurrence id`() {
        assertEquals(PendingCancellations.keyOf("u", ridLine), PendingCancellations.keyOf("u", ridLine.trim()))
        assertEquals(PendingCancellations.keyOf("u", null), PendingCancellations.keyOf("u", ""))
        assertNotEquals(PendingCancellations.keyOf("u", null), PendingCancellations.keyOf("u", ridLine))
        assertNotEquals(PendingCancellations.keyOf("u", ridLine), PendingCancellations.keyOf("u", ridLine2))
        assertEquals(PendingCancellations.keyOf("uid-1", ridLine), entry(recurrenceIdLine = ridLine).key)
    }

    // ---- stored form ----

    @Test
    fun `encoding and decoding gives the same queue`() {
        val queue = listOf(
            entry(recurrenceIdLine = ridLine, wholeEvent = false, attempts = 3).copy(
                rruleLine = rruleLine, recipients = listOf(PendingRecipient(guest, "Guest, Zoë"), PendingRecipient(other, null))
            ),
            entry(uid = "uid-2", location = null, sharedEventId = null).copy(organizerName = null, allDay = true)
        )
        assertEquals(queue, PendingCancellations.decode(PendingCancellations.encode(queue)))
        assertEquals(emptyList<PendingCancellation>(), PendingCancellations.decode(PendingCancellations.encode(emptyList())))
    }

    @Test
    fun `damaged input decodes to an empty queue`() {
        for (json in listOf(null, "", "   ", "garbage", "{", "{}", "[]", "[null]", "null", "[1,2]", "[\"x\"]")) {
            assertEquals(json.toString(), emptyList<PendingCancellation>(), PendingCancellations.decode(json))
        }
    }

    private fun objectOf(e: PendingCancellation): JsonObject =
        JsonParser.parseString(PendingCancellations.encode(listOf(e))).asJsonArray.get(0).asJsonObject

    private fun jsonArrayOf(vararg objects: JsonObject): String = JsonArray().apply { objects.forEach { add(it) } }.toString()

    @Test
    fun `an entry missing a required field is dropped and the rest kept`() {
        val good = entry(uid = "good")
        val json = jsonArrayOf(
            objectOf(entry(uid = "no-uid")).apply { remove("uid") },
            objectOf(good),
            objectOf(entry(uid = "no-recipients")).apply { remove("recipients") },
            objectOf(entry(uid = "no-summary")).apply { remove("summary") },
            objectOf(entry(uid = "no-timezone")).apply { remove("timezone") },
            objectOf(entry(uid = "no-organizer")).apply { remove("organizer") },
            objectOf(entry(uid = "empty-recipients")).apply { add("recipients", JsonArray()) }
        )
        assertEquals(listOf(good), PendingCancellations.decode(json))
    }

    @Test
    fun `recipients without an address are dropped`() {
        val damaged = JsonParser.parseString(
            """[{"email":null,"name":"Nobody"},{"email":"  ","name":null},{"name":"No email"},null,{"email":"guest@example.com","name":"Guest"}]"""
        )
        val allBad = JsonParser.parseString("""[{"email":null,"name":"Nobody"},{"email":"","name":null}]""")
        val json = jsonArrayOf(
            objectOf(entry(uid = "some")).apply { add("recipients", damaged) },
            objectOf(entry(uid = "none")).apply { add("recipients", allBad) }
        )
        val decoded = PendingCancellations.decode(json)
        assertEquals(listOf("some"), decoded.map { it.uid })
        assertEquals(listOf(PendingRecipient(guest, "Guest")), decoded[0].recipients)
    }

    /** A queue stored before sharedEventId existed. */
    @Test
    fun `an entry stored without sharedEventId decodes with none`() {
        val json = """[{"uid":"uid-1","recurrenceIdLine":null,"rruleLine":"RRULE:FREQ=WEEKLY\r\n","summary":"Team sync",""" +
            """"location":"Room 1","dtStart":$start,"dtEnd":${start + hour},"timezone":"Europe/Berlin","allDay":false,""" +
            """"sequence":2,"organizer":"$me","organizerName":"Me","recipients":[{"email":"$guest","name":"Guest"}],""" +
            """"wholeEvent":true,"createdAt":1000,"attempts":1,"nextAttemptAt":2000}]"""
        val decoded = PendingCancellations.decode(json).single()
        assertNull(decoded.sharedEventId)
        assertEquals(
            PendingCancellation(
                uid = "uid-1", recurrenceIdLine = null, rruleLine = "RRULE:FREQ=WEEKLY\r\n", summary = "Team sync",
                location = "Room 1", dtStart = start, dtEnd = start + hour, timezone = "Europe/Berlin", allDay = false,
                sequence = 2, organizer = me, organizerName = "Me", recipients = listOf(PendingRecipient(guest, "Guest")),
                wholeEvent = true, createdAt = 1000, attempts = 1, nextAttemptAt = 2000
            ),
            decoded
        )
    }

    // ---- from and back to the email's event ----

    private fun event(
        allDay: Boolean = false,
        rrule: String? = rruleLine,
        rid: String? = ridLine
    ) = CalendarInviteSender.EventInfo(
        uid = "uid-1", summary = "Team sync", description = "Agenda", location = "Room 1",
        dtStart = if (allDay) dayStart else start, dtEnd = if (allDay) dayStart + 2 * day else start + hour,
        timezone = "Europe/Berlin", allDay = allDay, organizer = me,
        attendees = listOf(CalendarInviteSender.AttendeeInfo(guest, "Guest", "tok")),
        sharedEventId = "shared-1", sessionKey = "c2Vzc2lvbi1rZXk=", sequence = 4, userAddresses = setOf(me),
        rruleLine = rrule, exdateLines = "EXDATE;TZID=Europe/Berlin:20261012T090000\r\n", recurrenceIdLine = rid,
        organizerName = "Me", cancelWholeEvent = true
    )

    private val removed = listOf(
        CalendarInviteSender.AttendeeInfo(" Guest@Example.com ", "Guest", "old-token", "ACCEPTED"),
        CalendarInviteSender.AttendeeInfo(other, null, "t2")
    )

    private fun assertCarried(source: CalendarInviteSender.EventInfo, back: CalendarInviteSender.EventInfo, wholeEvent: Boolean) {
        assertEquals(source.uid, back.uid)
        assertEquals(source.recurrenceIdLine?.ifEmpty { null }, back.recurrenceIdLine)
        assertEquals(source.rruleLine?.ifEmpty { null }, back.rruleLine)
        assertEquals(source.summary, back.summary)
        assertEquals(source.location, back.location)
        assertEquals(source.dtStart, back.dtStart)
        assertEquals(source.dtEnd, back.dtEnd)
        assertEquals(source.timezone, back.timezone)
        assertEquals(source.allDay, back.allDay)
        assertEquals(source.sequence, back.sequence)
        assertEquals(source.organizer, back.organizer)
        assertEquals(source.organizerName, back.organizerName)
        assertEquals(source.sharedEventId, back.sharedEventId)
        assertEquals(wholeEvent, back.cancelWholeEvent)
        // A cancellation carries no description, no key and no EXDATEs.
        assertNull(back.description)
        assertNull(back.sessionKey)
        assertNull(back.exdateLines)
        assertEquals(
            listOf(
                CalendarInviteSender.AttendeeInfo(guest, "Guest", attendeeToken("uid-1", guest)),
                CalendarInviteSender.AttendeeInfo(other, null, attendeeToken("uid-1", other))
            ),
            back.attendees
        )
    }

    @Test
    fun `a queued occurrence cancellation keeps everything a retry needs`() {
        val source = event()
        val entry = PendingCancellations.of(source, removed, wholeEvent = false)
        assertEquals(listOf(PendingRecipient(guest, "Guest"), PendingRecipient(other, null)), entry.recipients)
        assertEquals(ridLine, entry.recurrenceIdLine)
        assertFalse(entry.wholeEvent)
        assertEquals(0, entry.attempts)
        assertEquals(0L, entry.createdAt)
        assertEquals(0L, entry.nextAttemptAt)

        val addresses = setOf(me, "alias@example.org")
        val back = PendingCancellations.eventOf(entry, addresses)
        assertCarried(source, back, wholeEvent = false)
        assertEquals(addresses, back.userAddresses)

        // The same after a trip through the stored form.
        val stored = PendingCancellations.decode(PendingCancellations.encode(listOf(PendingCancellations.firstFailure(entry, now)))).single()
        assertCarried(source, PendingCancellations.eventOf(stored, addresses), wholeEvent = false)
    }

    @Test
    fun `a queued all-day series cancellation keeps everything a retry needs`() {
        val source = event(allDay = true, rrule = rruleLine, rid = null)
        val entry = PendingCancellations.of(source, removed, wholeEvent = true)
        assertTrue(entry.allDay)
        assertNull(entry.recurrenceIdLine)
        assertCarried(source, PendingCancellations.eventOf(entry, setOf(me)), wholeEvent = true)
    }

    @Test
    fun `empty recurrence lines are stored as none`() {
        val entry = PendingCancellations.of(event(rrule = "", rid = ""), removed, wholeEvent = true)
        assertNull(entry.rruleLine)
        assertNull(entry.recurrenceIdLine)
        assertEquals(PendingCancellations.keyOf("uid-1", null), entry.key)
    }

    @Test
    fun `retry guests carry the token Proton knows them by`() {
        val e = entry().copy(
            recipients = listOf(PendingRecipient(guest, "Guest"), PendingRecipient(other, null))
        )
        assertEquals(
            listOf(
                CalendarInviteSender.AttendeeInfo(guest, "Guest", attendeeToken("uid-1", guest)),
                CalendarInviteSender.AttendeeInfo(other, null, attendeeToken("uid-1", other))
            ),
            PendingCancellations.attendeesOf(e)
        )
    }

    // ---- the cancellation of a deleted event ----

    private fun deleted(
        dtStart: Long = start,
        effectiveDtEnd: Long = start + hour,
        allDay: Boolean = false,
        location: String? = "Room 1",
        rrule: String? = null,
        rid: String? = null,
        storedSequence: Int = 2,
        recipients: Collection<String> = listOf(guest)
    ) = deletedEventCancellation(
        uid = "uid-1", summary = "Team sync", location = location, dtStart = dtStart, effectiveDtEnd = effectiveDtEnd,
        allDay = allDay, timezone = "Europe/Berlin", rruleLine = rrule, recurrenceIdLine = rid, organizer = me,
        organizerName = "Me", storedSequence = storedSequence, userAddresses = setOf(me), recipients = recipients
    )

    @Test
    fun `a proper end is kept`() {
        assertEquals(start + hour, deleted(effectiveDtEnd = start + hour).dtEnd)
        assertEquals(dayStart + 2 * day, deleted(dtStart = dayStart, effectiveDtEnd = dayStart + 2 * day, allDay = true).dtEnd)
    }

    /** A recurring row stores a DURATION and no DTEND, so the end can come out before the start. */
    @Test
    fun `an end not after the start becomes the start for a timed event`() {
        assertEquals(start, deleted(effectiveDtEnd = start).dtEnd)
        assertEquals(start, deleted(effectiveDtEnd = start - hour).dtEnd)
        assertEquals(start, deleted(effectiveDtEnd = 0).dtEnd)
    }

    @Test
    fun `an end not after the start becomes the next day for an all-day event`() {
        assertEquals(dayStart + day, deleted(dtStart = dayStart, effectiveDtEnd = dayStart, allDay = true).dtEnd)
        assertEquals(dayStart + day, deleted(dtStart = dayStart, effectiveDtEnd = 0, allDay = true).dtEnd)
    }

    @Test
    fun `the sequence is one above the stored one`() {
        assertEquals(3, deleted(storedSequence = 2).sequence)
        assertEquals(1, deleted(storedSequence = 0).sequence)
    }

    @Test
    fun `a deleted occurrence names itself and drops the series rule`() {
        val occurrence = deleted(rrule = rruleLine, rid = ridLine)
        assertEquals(ridLine, occurrence.recurrenceIdLine)
        assertNull(occurrence.rruleLine)
        assertEquals(rruleLine, deleted(rrule = rruleLine, rid = null).rruleLine)
        val emptyRid = deleted(rrule = rruleLine, rid = "")
        assertEquals(rruleLine, emptyRid.rruleLine)
        assertNull(emptyRid.recurrenceIdLine)
    }

    @Test
    fun `empty strings become none`() {
        val e = deleted(location = "", rrule = "", rid = "")
        assertNull(e.location)
        assertNull(e.rruleLine)
        assertNull(e.recurrenceIdLine)
        assertNull(deleted(location = null).location)
    }

    @Test
    fun `deleted event guests are normalised and carry their tokens`() {
        val e = deleted(recipients = listOf(" Guest@Example.com ", other))
        assertEquals(
            listOf(
                CalendarInviteSender.AttendeeInfo(guest, null, attendeeToken("uid-1", guest)),
                CalendarInviteSender.AttendeeInfo(other, null, attendeeToken("uid-1", other))
            ),
            e.attendees
        )
    }

    @Test
    fun `a deletion cancels the whole event without its description`() {
        val e = deleted()
        assertTrue(e.cancelWholeEvent)
        assertNull(e.description)
        assertNull(e.sharedEventId)
        assertNull(e.sessionKey)
        assertNull(e.exdateLines)
        assertEquals("uid-1", e.uid)
        assertEquals("Team sync", e.summary)
        assertEquals("Europe/Berlin", e.timezone)
        assertFalse(e.allDay)
        assertEquals(me, e.organizer)
        assertEquals("Me", e.organizerName)
        assertEquals(setOf(me), e.userAddresses)
    }

    private fun veventLines(ics: String): List<String> {
        val all = ics.replace("\r\n ", "").split("\r\n")
        return all.subList(all.indexOf("BEGIN:VEVENT"), all.indexOf("END:VEVENT") + 1)
    }

    @Test
    fun `the email for a deleted occurrence cancels only that occurrence`() {
        val e = deleted(rrule = rruleLine, rid = ridLine)
        val lines = veventLines(CalendarInviteSender.buildIcs(e, "CANCEL"))
        assertTrue(lines.toString(), "RECURRENCE-ID;TZID=Europe/Berlin:20261005T090000" in lines)
        assertTrue(lines.toString(), "STATUS:CANCELLED" in lines)
        assertTrue(lines.toString(), "SEQUENCE:3" in lines)
        // The VTIMEZONE has RRULEs of its own: only the event is checked.
        assertFalse(lines.toString(), lines.any { it.startsWith("RRULE") })
        val dtStart = lines.single { it.startsWith("DTSTART") }
        val dtEnd = lines.single { it.startsWith("DTEND") }
        assertEquals("DTSTART;TZID=Europe/Berlin:20261005T090000", dtStart)
        assertEquals("DTEND;TZID=Europe/Berlin:20261005T100000", dtEnd)
        assertTrue(dtEnd.substringAfter(':') > dtStart.substringAfter(':'))
        assertEquals(
            "ATTENDEE;CUTYPE=INDIVIDUAL;ROLE=REQ-PARTICIPANT;PARTSTAT=NEEDS-ACTION;X-PM-TOKEN=${attendeeToken("uid-1", guest)}:mailto:$guest",
            lines.single { it.startsWith("ATTENDEE") }
        )
        assertEquals(
            "One occurrence of Team sync was canceled.",
            CalendarInviteSender.buildPlainTextBody(e, "CANCEL").lines().first()
        )
    }

    @Test
    fun `the email for a deleted series cancels the series`() {
        val e = deleted(rrule = rruleLine, rid = null)
        val lines = veventLines(CalendarInviteSender.buildIcs(e, "CANCEL"))
        assertTrue(lines.toString(), "RRULE:FREQ=WEEKLY;BYDAY=MO" in lines)
        assertFalse(lines.toString(), lines.any { it.startsWith("RECURRENCE-ID") })
        assertTrue(lines.toString(), "STATUS:CANCELLED" in lines)
        assertEquals("Team sync was canceled.", CalendarInviteSender.buildPlainTextBody(e, "CANCEL").lines().first())
    }

    // ---- writing an attempt back against a queue that changed meanwhile ----

    @Test
    fun `a retry does not resurrect an entry that was purged while it sent`() {
        val attempted = entry(recipients = listOf(guest))
        val next = PendingCancellations.afterAttempt(attempted, setOf(guest), now)
        assertNull(PendingCancellations.mergeAfterAttempt(null, attempted, next))
    }

    @Test
    fun `a retry does not bring back a recipient purged while it sent`() {
        val attempted = entry(recipients = listOf(guest, other))
        val current = entry(recipients = listOf(other))            // guest was invited again meanwhile
        val next = PendingCancellations.afterAttempt(attempted, setOf(guest, other), now)
        val merged = PendingCancellations.mergeAfterAttempt(current, attempted, next)
        assertEquals(listOf(other), emails(merged!!))
    }

    @Test
    fun `a recipient queued while the retry sent stays pending`() {
        val attempted = entry(recipients = listOf(guest))
        val current = entry(recipients = listOf(guest, other))      // other was queued meanwhile
        // The send reached everybody it tried, so afterAttempt has nothing left.
        val merged = PendingCancellations.mergeAfterAttempt(current, attempted, null)
        assertEquals(listOf(other), emails(merged!!))
    }

    @Test
    fun `a failed recipient keeps the new attempt count and the later turn`() {
        val attempted = entry(recipients = listOf(guest), attempts = 2, nextAttemptAt = now)
        val next = PendingCancellations.afterAttempt(attempted, setOf(guest), now)
        val merged = PendingCancellations.mergeAfterAttempt(attempted, attempted, next)!!
        assertEquals(listOf(guest), emails(merged))
        assertEquals(3, merged.attempts)
        assertEquals(now + PendingCancellations.backoffMillis(3), merged.nextAttemptAt)
    }

    @Test
    fun `nothing left to send leaves no entry`() {
        val attempted = entry(recipients = listOf(guest))
        assertNull(PendingCancellations.mergeAfterAttempt(attempted, attempted, null))
    }

}

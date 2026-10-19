package me.proton.sync.calendar

import net.fortuna.ical4j.data.CalendarBuilder
import net.fortuna.ical4j.data.ParserException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.StringReader

/**
 * Logging an event that did not parse: enough structure to diagnose it, none of its content.
 *
 * Every fixture hides the word SECRET in the values a log must never show.
 */
class ICalShapeTest {

    private fun ical(vararg lines: String) = lines.joinToString("\r\n", postfix = "\r\n")

    private fun parseFailure(iCal: String): ParserException =
        try {
            CalendarBuilder().build(StringReader(iCal))
            fail("expected the fixture not to parse")
            throw AssertionError()
        } catch (e: ParserException) {
            e
        }

    private val badStart = ical(
        "BEGIN:VCALENDAR",
        "VERSION:2.0",
        "PRODID:-//test//EN",
        "BEGIN:VEVENT",
        "UID:abc",
        "SUMMARY:SECRET dinner",
        "DTSTART:SECRET-not-a-date",
        "END:VEVENT",
        "END:VCALENDAR",
    )

    /** The premise: the parser's own message quotes the value, so it cannot be logged. */
    @Test
    fun `ical4j's message quotes the offending value`() {
        val e = parseFailure(badStart)
        val trace = generateSequence<Throwable>(e) { it.cause }.joinToString { it.message.orEmpty() }
        assertTrue(trace, trace.contains("SECRET"))
    }

    @Test
    fun `a parse failure names the line and property but no value`() {
        val e = parseFailure(badStart)
        val described = iCalParseFailure(badStart, e)
        assertTrue(described, described.startsWith("line 7 (DTSTART), ParserException"))
        assertFalse(described, described.contains("SECRET"))
    }

    @Test
    fun `folded lines before the failure still point at the right property`() {
        val iCal = ical(
            "BEGIN:VCALENDAR",
            "VERSION:2.0",
            "PRODID:-//test//EN",
            "BEGIN:VEVENT",
            "UID:abc",
            "DESCRIPTION:SECRET first part",
            "  SECRET second part",
            "\tSECRET third part",
            "DTSTART:SECRET-not-a-date",
            "END:VEVENT",
            "END:VCALENDAR",
        )
        val described = iCalParseFailure(iCal, parseFailure(iCal))
        assertTrue(described, described.contains("(DTSTART)"))
        assertFalse(described, described.contains("SECRET"))
    }

    @Test
    fun `a failure reported on a continuation line is attributed to the line it continues`() {
        val iCal = ical("BEGIN:VCALENDAR", "ATTENDEE;CN=SECRET:mailto:a@b", " SECRET", "END:VCALENDAR")
        val described = iCalParseFailure(iCal, ParserException("SECRET", 3))
        assertTrue(described, described.startsWith("line 3 (ATTENDEE), ParserException;"))
        assertFalse(described, described.contains("SECRET"))
    }

    @Test
    fun `a line number outside the body is reported without a property`() {
        val iCal = ical("BEGIN:VCALENDAR", "END:VCALENDAR")
        assertTrue(iCalParseFailure(iCal, ParserException("x", 40)).startsWith("line 40, ParserException;"))
    }

    @Test
    fun `the shape lists property names and component names only`() {
        val iCal = ical(
            "BEGIN:VCALENDAR",
            "BEGIN:VTODO",
            "SUMMARY;LANGUAGE=en:SECRET",
            "LOCATION:SECRET",
            " SECRET",
            "END:VTODO",
            "END:VCALENDAR",
        )
        assertEquals(
            "${iCal.length} chars, 6 lines: BEGIN:VCALENDAR,BEGIN:VTODO,SUMMARY,LOCATION,END:VTODO,END:VCALENDAR",
            iCalShape(iCal),
        )
    }

    /** A broken fold leaves free text at the start of a line; that text is content, not a name. */
    @Test
    fun `text that is not a property name is masked`() {
        val iCal = ical("BEGIN:VCALENDAR", "SECRET words: here", "SECRET", "SECRET:agenda", "END:VCALENDAR")
        val shape = iCalShape(iCal)
        assertTrue(shape, shape.endsWith("5 lines: BEGIN:VCALENDAR,?,?,?,END:VCALENDAR"))
        assertFalse(shape, shape.contains("SECRET"))
    }

    @Test
    fun `extension properties and components are kept`() {
        val shape = iCalShape(ical("BEGIN:X-PM-THING", "X-PM-SHARED-EVENT-ID:SECRET", "END:X-PM-THING"))
        assertTrue(shape, shape.endsWith("3 lines: BEGIN:X-PM-THING,X-PM-SHARED-EVENT-ID,END:X-PM-THING"))
    }

    @Test
    fun `BEGIN keeps its value only when it is a component name`() {
        val shape = iCalShape(ical("BEGIN:SECRET", "BEGIN:SECRET plans", "END"))
        assertTrue(shape, shape.endsWith("3 lines: BEGIN,BEGIN,?"))
    }

    @Test
    fun `a long body is capped`() {
        val lines = Array(100) { "EXDATE:2026010${it % 10}T000000Z" }
        assertTrue(iCalShape(ical(*lines)).endsWith(",+20 more"))
    }
}

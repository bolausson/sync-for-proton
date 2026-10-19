package me.proton.sync.calendar

import me.proton.sync.crypto.CalendarDecryptor
import net.fortuna.ical4j.data.CalendarBuilder
import net.fortuna.ical4j.model.Property
import net.fortuna.ical4j.model.component.VEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.StringReader

/**
 * The event's encrypted shared part (SharedEventContent type 3) as it goes to Proton.
 *
 * SUMMARY, DESCRIPTION and LOCATION used to go in raw. They are now escaped (RFC 5545 3.3.11) and folded at
 * 75 octets. The round trip tests take the part down the download path: CalendarDecryptor.mergeParts, then
 * ical4j, whose decoded value is what CalendarMapper stores as the title, description and location.
 */
class ProtonSharedPartTest {

    @Before
    fun relaxLikeTheApp() {
        System.setProperty("ical4j.parsing.relaxed", "true")
        System.setProperty("ical4j.unfolding.relaxed", "true")
        System.setProperty("ical4j.compatibility.outlook", "true")
    }

    private val prod = "PRODID:${ICalUtils.PRODID}\r\n"

    /** What 1.3.30 to 1.11.5 wrote. Events they uploaded carry it until their next edit. */
    private val legacyProd = "PRODID:-//Proton AG//Sync for Proton 1.0//EN"
    private val uid = "uid-1"
    private val stamp = "20260919T100000Z"

    /** A small signed part (type 2), as Proton returns it next to the encrypted one. */
    private val signedPart = listOf(
        "BEGIN:VCALENDAR", "VERSION:2.0", legacyProd, "BEGIN:VEVENT",
        "UID:$uid", "DTSTAMP:$stamp", "DTSTART:20260924T080000Z", "DTEND:20260924T090000Z", "SEQUENCE:0",
        "END:VEVENT", "END:VCALENDAR"
    ).joinToString("\r\n")

    private fun part(
        title: String,
        description: String = "",
        location: String = "",
        created: String? = null,
        recurrenceIdLine: String = ""
    ) = ICalUtils.sharedEncryptedPart(
        prodIdLine = prod, uid = uid, dtStamp = stamp, created = created, recurrenceIdLine = recurrenceIdLine,
        title = title, description = description, location = location
    )

    /** A part with its content lines joined as given, the way 1.10.68 wrote it (no escaping). */
    private fun rawPart(vararg lines: String) =
        (listOf("BEGIN:VCALENDAR", legacyProd, "VERSION:2.0", "BEGIN:VEVENT", "UID:$uid", "DTSTAMP:$stamp") +
            lines + listOf("END:VEVENT", "END:VCALENDAR")).joinToString("\r\n")

    private fun parse(ics: String): VEvent =
        CalendarBuilder().build(StringReader(ics)).getComponents<VEvent>("VEVENT").first()

    private fun download(vararg parts: String): VEvent = parse(CalendarDecryptor.mergeParts(parts.toList(), uid))

    private fun unfold(ics: String) = ics.replace("\r\n ", "")
    private fun octets(s: String) = s.toByteArray(Charsets.UTF_8).size

    /** Every physical line: no raw CR or LF, at most 75 octets, and nothing cut in half at a fold. */
    private fun assertWellFolded(ics: String) {
        for (physical in ics.split("\r\n")) {
            assertFalse(physical, physical.contains('\r') || physical.contains('\n'))
            assertTrue("${octets(physical)}: $physical", octets(physical) <= 75)
            // A lone surrogate would not survive encoding the line on its own.
            assertEquals(physical, String(physical.toByteArray(Charsets.UTF_8), Charsets.UTF_8))
            assertFalse(physical, Character.isHighSurrogate(physical.last()))
            if (physical.startsWith(" ") && physical.length > 1) {
                assertFalse(physical, Character.isLowSurrogate(physical[1]))
            }
        }
    }

    // ---- framing ----

    @Test
    fun `a create carries CREATED and every text field in a fixed order`() {
        assertEquals(
            "BEGIN:VCALENDAR\r\n" +
                prod +
                "VERSION:2.0\r\n" +
                "BEGIN:VEVENT\r\n" +
                "UID:uid-1\r\n" +
                "DTSTAMP:20260919T100000Z\r\n" +
                "CREATED:20260919T100000Z\r\n" +
                "SUMMARY:Lunch\r\n" +
                "DESCRIPTION:Bring snacks\r\n" +
                "LOCATION:Room 4\r\n" +
                "END:VEVENT\r\n" +
                "END:VCALENDAR",
            part(title = "Lunch", description = "Bring snacks", location = "Room 4", created = stamp)
        )
        // The recurrence id line is passed through as given, between CREATED and SUMMARY.
        val occurrence = part(
            title = "Lunch", created = stamp, recurrenceIdLine = "RECURRENCE-ID:20260924T080000Z\r\n"
        )
        assertTrue(
            occurrence,
            occurrence.contains("\r\nCREATED:20260919T100000Z\r\nRECURRENCE-ID:20260924T080000Z\r\nSUMMARY:Lunch\r\nEND:VEVENT\r\n")
        )
    }

    @Test
    fun `an update has no CREATED and keeps an empty SUMMARY but no empty DESCRIPTION or LOCATION`() {
        assertEquals(
            "BEGIN:VCALENDAR\r\n" +
                prod +
                "VERSION:2.0\r\n" +
                "BEGIN:VEVENT\r\n" +
                "UID:uid-1\r\n" +
                "DTSTAMP:20260919T100000Z\r\n" +
                "RECURRENCE-ID;TZID=Europe/Berlin:20260924T100000\r\n" +
                "SUMMARY:\r\n" +
                "END:VEVENT\r\n" +
                "END:VCALENDAR",
            part(title = "", recurrenceIdLine = "RECURRENCE-ID;TZID=Europe/Berlin:20260924T100000\r\n")
        )
        val locationOnly = part(title = "T", location = "Here")
        assertTrue(locationOnly, locationOnly.contains("\r\nSUMMARY:T\r\nLOCATION:Here\r\nEND:VEVENT\r\n"))
        assertFalse(locationOnly, locationOnly.contains("DESCRIPTION"))
        val descriptionOnly = part(title = "T", description = "Words")
        assertTrue(descriptionOnly, descriptionOnly.contains("\r\nSUMMARY:T\r\nDESCRIPTION:Words\r\nEND:VEVENT\r\n"))
        assertFalse(descriptionOnly, descriptionOnly.contains("LOCATION"))
    }

    @Test
    fun `the part ends with END VCALENDAR and no line break`() {
        val ics = part(title = "T", description = "D", location = "L", created = stamp)
        assertTrue(ics.endsWith("\r\nEND:VEVENT\r\nEND:VCALENDAR"))
        assertFalse(ics.endsWith("\n"))
    }

    @Test
    fun `a text property is one escaped and folded content line with its CRLF`() {
        assertEquals("LOCATION:a\\;b\\,c\r\n", ICalUtils.textProperty("LOCATION", "a;b,c"))
        assertEquals(
            "DESCRIPTION:" + "x".repeat(63) + "\r\n " + "x".repeat(37) + "\r\n",
            ICalUtils.textProperty("DESCRIPTION", "x".repeat(100))
        )
    }

    // ---- escaping ----

    @Test
    fun `commas semicolons and backslashes are escaped but a colon is not`() {
        assertEquals("Plan: A\\, B\\; C \\\\ D", ICalUtils.escapeText("Plan: A, B; C \\ D"))
        val ics = part(title = "Plan: A, B; C \\ D")
        assertTrue(ics, ics.contains("\r\nSUMMARY:Plan: A\\, B\\; C \\\\ D\r\n"))
    }

    @Test
    fun `every kind of line break becomes an escaped newline`() {
        assertEquals("a\\nb\\nc\\nd\\ne\\nf", ICalUtils.escapeText("a\r\nb\rc\nd\u000Be\u000Cf"))
        // CRLF is one break, LF followed by CR is two.
        assertEquals("x\\ny", ICalUtils.escapeText("x\r\ny"))
        assertEquals("x\\n\\ny", ICalUtils.escapeText("x\n\ry"))
    }

    @Test
    fun `control characters are dropped except TAB and DEL is dropped too`() {
        assertEquals("ghij\tk", ICalUtils.escapeText("\u0000g\u0001h\u001Fi\u007Fj\tk"))
        assertEquals("a b", ICalUtils.escapeText("a\u0007 b\u001B"))
        assertEquals("Grüße 東京 😀", ICalUtils.escapeText("Grüße 東京 😀"))
    }

    @Test
    fun `the invitation sender escapes text the same way`() {
        for (text in listOf("Plan: A, B; C \\ D", "one\r\ntwo\u0000\u000B", "Grüße 😀", "")) {
            assertEquals(ICalUtils.escapeText(text), CalendarInviteSender.escapeIcsText(text))
        }
    }

    // ---- folding ----

    private val longMixed = List(12) { "Grüße aus Köln, Привет; 東京で 😀 C:\\dir ok" }.joinToString(" / ")

    @Test
    fun `no physical line holds a raw CR or LF or more than 75 octets`() {
        val description = "$longMixed\n$longMixed\r\n$longMixed\r$longMixed\u000B$longMixed\u000C$longMixed"
        val ics = part(title = longMixed, description = description, location = longMixed, created = stamp)
        assertWellFolded(ics)
        // Unfolded, each field is a single content line holding the escaped value.
        val logical = unfold(ics).split("\r\n")
        assertEquals(12, logical.size)
        assertEquals("SUMMARY:" + ICalUtils.escapeText(longMixed), logical[7])
        assertEquals("DESCRIPTION:" + ICalUtils.escapeText(description), logical[8])
        assertEquals("LOCATION:" + ICalUtils.escapeText(longMixed), logical[9])
    }

    @Test
    fun `folding never splits a surrogate pair or a multibyte character`() {
        // With its name, each line reaches 73 or 74 octets and the next character does not fit whole.
        val title = "a".repeat(66) + "ü" + "b".repeat(20)          // 74 octets, then 2
        val description = "a".repeat(62) + "ü" + "b".repeat(5)     // 74 octets, then 2
        val location = "a".repeat(64) + "東" + "b".repeat(5)       // 73 octets, then 3
        val physical = part(title = title, description = description, location = location).split("\r\n")
        fun assertFoldedAt(first: String, second: String) {
            val i = physical.indexOf(first)
            assertTrue(first, i >= 0)
            assertEquals(second, physical[i + 1])
        }
        assertFoldedAt("SUMMARY:" + "a".repeat(66), " ü" + "b".repeat(20))
        assertFoldedAt("DESCRIPTION:" + "a".repeat(62), " ü" + "b".repeat(5))
        assertFoldedAt("LOCATION:" + "a".repeat(64), " 東" + "b".repeat(5))

        val emoji = "a".repeat(65) + "😀" + "b".repeat(10)         // 73 octets, then 4
        val emojiLines = part(title = emoji).split("\r\n")
        val i = emojiLines.indexOf("SUMMARY:" + "a".repeat(65))
        assertTrue(i >= 0)
        assertEquals(" 😀" + "b".repeat(10), emojiLines[i + 1])

        // Every alignment of the same mixed text against the fold points.
        for (shift in 0 until 8) {
            val text = "x".repeat(shift) + List(15) { "Grüße 😀 東京" }.joinToString(" ")
            assertWellFolded(part(title = text, description = text, location = text))
        }
    }

    // ---- round trip through the download path ----

    private data class Case(val label: String, val title: String, val description: String = "", val location: String = "")

    private fun assertRoundTrips(case: Case) {
        val shared = part(title = case.title, description = case.description, location = case.location)
        for (parts in listOf(listOf(shared), listOf(signedPart, shared))) {
            val label = "${case.label} (${parts.size} part(s))"
            val event = parse(CalendarDecryptor.mergeParts(parts, uid))
            assertEquals(label, case.title, event.summary.value)
            assertEquals(label, case.description, event.description?.value.orEmpty())
            assertEquals(label, case.location, event.location?.value.orEmpty())
        }
    }

    @Test
    fun `title description and location come back as they went up`() {
        val unit = "Line: ü, 😀; \\ ok\n"
        val big = unit.repeat(166).let { it + "x".repeat(3000 - it.length) }
        assertEquals(3000, big.length)
        val cases = listOf(
            Case(
                "url", "Call", "Join: https://meet.example.com/j?id=42;pwd=a=b&x=1\nsecond line",
                "https://maps.example.com/?q=a;b=c"
            ),
            Case("note line", "Standup", "first\nsecond\nNote: third"),
            Case("upper case NOTE line", "Standup", "intro\nNOTE: x"),
            Case("summary line", "Real title", "agenda\nSummary: not the title"),
            Case("indented line", "List", "list:\n  - indented item\n\ttabbed item"),
            Case("blank line", "Paragraphs", "para one\n\npara two"),
            Case("literal backslash n", "a\\nb", "path a\\nb and \\n", "x\\ny"),
            Case("escape look alikes", "odd \\, and \\\\ both", "odd \\, and \\\\ both\\;", "\\,"),
            Case("windows path", "C:\\new folder", "see C:\\new folder\\file.txt", "C:\\new folder"),
            Case("3000 characters", "Big", big),
            Case("long mixed script", longMixed, "$longMixed\n$longMixed", longMixed),
            Case("empty title", "")
        )
        for (case in cases) assertRoundTrips(case)
    }

    @Test
    fun `umlauts and emoji on a fold boundary come back whole`() {
        assertRoundTrips(Case("umlaut", "a".repeat(66) + "ü" + "b".repeat(20), "a".repeat(62) + "ü" + "b".repeat(5), "a".repeat(64) + "東" + "b".repeat(5)))
        assertRoundTrips(Case("emoji", "a".repeat(65) + "😀" + "b".repeat(10), "😀".repeat(60), "ü".repeat(100)))
        for (shift in 0 until 8) {
            val text = "x".repeat(shift) + List(15) { "Grüße 😀 東京" }.joinToString(" ")
            assertRoundTrips(Case("shift $shift", text, text, text))
        }
    }

    /**
     * Known limitation, pinned. ical4j 3.2.14 decodes an escaped newline only when no backslash stands right
     * before it (a lookbehind), so an escaped backslash followed by an escaped line break reads back as a
     * backslash and a literal backslash n. Raw storage was worse: it lost the line break and a backslash.
     */
    @Test
    fun `a backslash right before a line break comes back as a literal backslash n`() {
        val text = "C:\\temp\\" + "\n" + "end"
        assertEquals("C:\\\\temp\\\\\\nend", ICalUtils.escapeText(text))
        val event = download(part(title = text, description = text))
        assertEquals("C:\\temp\\\\nend", event.summary.value)
        assertEquals("C:\\temp\\\\nend", event.description.value)
        // The same title as 1.10.68 sent it.
        assertEquals("C:\\temp\\nend", download(rawPart("SUMMARY:$text")).summary.value)
    }

    // ---- parts written before the fix ----

    @Test
    fun `a raw part as 1 10 68 wrote it still has its stray line stitched onto the description`() {
        val legacy = rawPart("SUMMARY:Title", "DESCRIPTION:line one", "line two")
        val merged = CalendarDecryptor.mergeParts(listOf(legacy), uid)
        assertTrue(merged, merged.lines().contains("DESCRIPTION:line one\\nline two"))
        val event = parse(merged)
        assertEquals("Title", event.summary.value)
        assertEquals("line one\nline two", event.description.value)
        // What the stitching cannot save: a raw line that looks like a property became one.
        val lost = download(rawPart("SUMMARY:Title", "DESCRIPTION:intro", "NOTE: x"))
        assertEquals("intro", lost.description.value)
        assertNotNull(lost.getProperty<Property>("NOTE"))
    }

    // ---- Proton's length limits ----

    @Test
    fun `truncating leaves text that fits unchanged`() {
        assertEquals("abc", ICalUtils.truncateText("abc", 5))
        assertEquals("abcde", ICalUtils.truncateText("abcde", 5))
        assertEquals("", ICalUtils.truncateText("", 0))
    }

    @Test
    fun `a cut inside an emoji drops its first half as well`() {
        val emoji = "😀"
        assertEquals(2, emoji.length)
        val s = "a".repeat(254) + emoji + "b"
        assertTrue(Character.isHighSurrogate(s[254]))
        val cut = ICalUtils.truncateText(s, 255)
        assertEquals(254, cut.length)
        assertEquals("a".repeat(254), cut)
        // An emoji that ends right at the limit is kept whole.
        assertEquals("a".repeat(253) + emoji, ICalUtils.truncateText("a".repeat(253) + emoji + "b", 255))
    }

    @Test
    fun `an ordinary cut keeps exactly max characters`() {
        assertEquals("x".repeat(255), ICalUtils.truncateText("x".repeat(300), 255))
        assertEquals("ab", ICalUtils.truncateText("abc", 2))
        assertEquals("", ICalUtils.truncateText("abc", 0))
    }

    /** The limit applies before escaping, so an escaped title can be twice as long. */
    @Test
    fun `255 commas escape to 510 characters and come back as 255 commas`() {
        val commas = ICalUtils.truncateText(",".repeat(300), 255)
        assertEquals(",".repeat(255), commas)
        assertEquals(510, ICalUtils.escapeText(commas).length)
        val ics = part(title = commas)
        assertWellFolded(ics)
        assertEquals(commas, download(ics).summary.value)
    }

    /** The download keeps no part's PRODID, whoever wrote it (1.x, Proton web), and names this app once. */
    @Test
    fun `the merged download names this app once and drops every part's PRODID`() {
        val fromWeb = listOf(
            "BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Proton AG//web-calendar 5.0.136.4//EN", "BEGIN:VEVENT",
            "UID:$uid", "DTSTAMP:$stamp", "SUMMARY:Lunch", "END:VEVENT", "END:VCALENDAR"
        ).joinToString("\r\n")
        val merged = CalendarDecryptor.mergeParts(listOf(signedPart, fromWeb), uid)
        assertEquals(listOf("PRODID:${ICalUtils.PRODID}"), merged.lines().filter { it.startsWith("PRODID") })
    }

    // ---- the attendee part's envelope (one builder, two create-side call sites) ----

    /**
     * The envelope byte for byte: PRODID before VERSION, the attendee lines passed through untouched,
     * and no trailing CRLF. Proton's own clients read this part, so its shape is a contract.
     */
    @Test
    fun `the attendee part is one envelope with prodid first and no trailing break`() {
        val attendeeLines = "ATTENDEE;CN=a;X-PM-TOKEN=abc:mailto:a@example.com\r\n"
        assertEquals(
            "BEGIN:VCALENDAR\r\n${prod}VERSION:2.0\r\n" +
                "BEGIN:VEVENT\r\nUID:$uid\r\nDTSTAMP:$stamp\r\n" +
                "ATTENDEE;CN=a;X-PM-TOKEN=abc:mailto:a@example.com\r\n" +
                "END:VEVENT\r\nEND:VCALENDAR",
            ICalUtils.attendeesEncryptedPart(
                prodIdLine = prod, uid = uid, dtStamp = stamp, attendeeLines = attendeeLines
            )
        )
    }

    /**
     * The two create-side copies this replaced were laid out differently in the source: the rewrap put
     * UID and DTSTAMP on one line and appended END:VEVENT to the attendee lines. They produced the same
     * bytes, which is what made repointing both at one builder a move rather than a change; this test
     * is that claim, written out.
     */
    @Test
    fun `the rewrap's own layout produced the same bytes as the builder`() {
        val attendeeLines = "ATTENDEE;CN=a;X-PM-TOKEN=abc:mailto:a@example.com\r\n"
        val cr = "\r\n"
        val asTheRewrapWroteIt = "BEGIN:VCALENDAR${cr}${prod}VERSION:2.0${cr}BEGIN:VEVENT${cr}" +
            "UID:$uid${cr}DTSTAMP:$stamp${cr}" +
            attendeeLines + "END:VEVENT${cr}END:VCALENDAR"
        assertEquals(
            asTheRewrapWroteIt,
            ICalUtils.attendeesEncryptedPart(
                prodIdLine = prod, uid = uid, dtStamp = stamp, attendeeLines = attendeeLines
            )
        )
    }

    // ---- the signed structural part (one template, the create's inputs and the update's) ----

    /**
     * A create's structural part: no recurrence, no rule, no organizer of its own, SEQUENCE:0. The
     * order is the contract, not just the content: SEQUENCE last, PRODID before VERSION.
     */
    @Test
    fun `the structural part puts every property in one fixed order with sequence last`() {
        assertEquals(
            "BEGIN:VCALENDAR\r\n${prod}VERSION:2.0\r\n" +
                "BEGIN:VEVENT\r\nUID:$uid\r\nDTSTAMP:$stamp\r\n" +
                "DTSTART:20260924T080000Z\r\nDTEND:20260924T090000Z\r\n" +
                "SEQUENCE:0\r\nEND:VEVENT\r\nEND:VCALENDAR",
            ICalUtils.sharedStructuralPart(
                prodIdLine = prod, uid = uid, dtStamp = stamp, recurrenceIdLine = "",
                timeBlock = "DTSTART:20260924T080000Z\r\nDTEND:20260924T090000Z\r\n",
                rruleLine = "", exdateLine = "", organizerLine = "", sequence = 0
            )
        )
    }

    /**
     * An update of a single occurrence, with everything filled: the server's UID, a RECURRENCE-ID
     * between DTSTAMP and DTSTART, a rule, a deleted occurrence and an organizer, and a SEQUENCE that
     * has climbed. Each line keeps the place the template gives it.
     */
    @Test
    fun `the structural part of an occurrence update keeps recurrence id before the times`() {
        assertEquals(
            "BEGIN:VCALENDAR\r\n${prod}VERSION:2.0\r\n" +
                "BEGIN:VEVENT\r\nUID:server-uid\r\nDTSTAMP:$stamp\r\n" +
                "RECURRENCE-ID;TZID=Europe/Berlin:20260924T080000\r\n" +
                "DTSTART;TZID=Europe/Berlin:20260924T143000\r\nDTEND;TZID=Europe/Berlin:20260924T153000\r\n" +
                "RRULE:FREQ=WEEKLY\r\n" +
                "EXDATE;TZID=Europe/Berlin:20260925T080000\r\n" +
                "ORGANIZER;CN=a@b.c:mailto:a@b.c\r\n" +
                "SEQUENCE:4\r\nEND:VEVENT\r\nEND:VCALENDAR",
            ICalUtils.sharedStructuralPart(
                prodIdLine = prod, uid = "server-uid", dtStamp = stamp,
                recurrenceIdLine = "RECURRENCE-ID;TZID=Europe/Berlin:20260924T080000\r\n",
                timeBlock = "DTSTART;TZID=Europe/Berlin:20260924T143000\r\n" +
                    "DTEND;TZID=Europe/Berlin:20260924T153000\r\n",
                rruleLine = "RRULE:FREQ=WEEKLY\r\n",
                exdateLine = "EXDATE;TZID=Europe/Berlin:20260925T080000\r\n",
                organizerLine = "ORGANIZER;CN=a@b.c:mailto:a@b.c\r\n", sequence = 4
            )
        )
    }
}

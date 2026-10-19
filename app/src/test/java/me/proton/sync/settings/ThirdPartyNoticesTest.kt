package me.proton.sync.settings

import me.proton.sync.settings.ThirdPartyNotices.Bullet
import me.proton.sync.settings.ThirdPartyNotices.Code
import me.proton.sync.settings.ThirdPartyNotices.CodeLines
import me.proton.sync.settings.ThirdPartyNotices.CodeMark
import me.proton.sync.settings.ThirdPartyNotices.Heading
import me.proton.sync.settings.ThirdPartyNotices.Inline
import me.proton.sync.settings.ThirdPartyNotices.LinkMark
import me.proton.sync.settings.ThirdPartyNotices.Paragraph
import me.proton.sync.settings.ThirdPartyNotices.Table
import me.proton.sync.settings.ThirdPartyNotices.TableRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [ThirdPartyNotices] against the real THIRD-PARTY-NOTICES.md at the repository root, the file the build bundles for
 * the licences screen, and on invented text for each construct it reads.
 *
 * What the real file should give is counted from its raw lines with plain scans, never with the parser, so a parser
 * mistake cannot agree with itself.
 */
class ThirdPartyNoticesTest {

    // The working directory of a unit test is the module's; the notice sits at the repository root above it.
    private val raw = File("../THIRD-PARTY-NOTICES.md").readText().replace("\r\n", "\n")
    private val lines = raw.split('\n')
    private val notices = ThirdPartyNotices.parse(raw)

    /** The raw lines outside the fenced blocks, where the headings, text and tables are. */
    private val outsideFences: List<String> = buildList {
        var inside = false
        for (line in lines) {
            if (line.startsWith("```")) inside = !inside else if (!inside) add(line)
        }
    }

    private val delimiter = Regex("""\|(\s*:?-+:?\s*\|)+""")

    private fun rawCells(line: String) = line.trim().removePrefix("|").removeSuffix("|").split('|').map { it.trim() }

    @Test
    fun `every table row of the file is parsed, cell for cell`() {
        // Each table is a header line, a delimiter line, then rows until the first line that does not start with "|".
        val rawTables = mutableListOf<List<List<String>>>()
        for ((i, line) in outsideFences.withIndex()) {
            if (!delimiter.matches(line)) continue
            rawTables += outsideFences.drop(i + 1).takeWhile { it.startsWith("|") }.map(::rawCells)
        }
        assertEquals("the file's two tables", 2, rawTables.size)

        val tables = notices.blocks.filterIsInstance<Table>()
        assertEquals(rawTables, tables.map { it.rows })
        val libraries = tables.single { it.buildColumn >= 0 }
        assertEquals(listOf("Library", "Version", "License", "Build"), libraries.header)
        assertTrue("a cell per column", libraries.rows.all { it.size == libraries.header.size })

        // And as the screen gets them: one row each, in one card per table.
        val rows = notices.rows().filterIsInstance<TableRow>()
        assertEquals(rawTables.sumOf { it.size }, rows.size)
        assertEquals(rawTables.size, rows.count { it.first })
        assertEquals(rawTables.size, rows.count { it.last })
        assertEquals(rawTables.flatten(), rows.map { it.cells })
    }

    @Test
    fun `no Markdown syntax reaches the text on screen`() {
        val texts = notices.rows().flatMap { row ->
            when (row) {
                is Heading -> listOf(row.text.text)
                is Paragraph -> listOf(row.text.text)
                is Bullet -> listOf(row.text.text)
                is TableRow -> row.header + row.cells
                is CodeLines -> emptyList() // verbatim, so a "#" there is the text's own: see the next check
            }
        }
        assertTrue(texts.size > 100)
        for (text in texts) {
            assertFalse("a pipe in '$text'", '|' in text)
            assertFalse("a backtick in '$text'", '`' in text)
            assertFalse("a heading marker in '$text'", text.trimStart().startsWith("#"))
            assertFalse("a bullet marker in '$text'", text.startsWith("- "))
            assertFalse("link syntax in '$text'", "](" in text || "<http" in text)
            assertFalse("emphasis in '$text'", "**" in text || "__" in text)
            assertFalse("a hard line break in '$text'", '\n' in text)
            assertFalse("a delimiter row in '$text'", text.startsWith("---"))
        }
        for (row in notices.rows().filterIsInstance<CodeLines>()) {
            assertFalse("a fence inside '${row.text}'", row.text.lines().any { it.trimStart().startsWith("```") })
        }
    }

    @Test
    fun `every heading of the file is a heading on screen`() {
        val rawHeadings = outsideFences.filter { it.startsWith("#") }.map { line ->
            line.takeWhile { it == '#' }.length to line.dropWhile { it == '#' }.trim()
        }
        assertEquals(rawHeadings, notices.rows().filterIsInstance<Heading>().map { it.level to it.text.text })
        assertEquals(Heading(1, Inline("Third-party software")), notices.blocks.first())
    }

    @Test
    fun `every address outside the fenced blocks is a link`() {
        val rawCount = outsideFences.sumOf { line -> Regex("https?://").findAll(line).count() }
        val links = notices.rows().flatMap { row ->
            when (row) {
                is Heading -> row.text.marks
                is Paragraph -> row.text.marks
                is Bullet -> row.text.marks
                else -> emptyList()
            }.filterIsInstance<LinkMark>()
        }
        assertTrue(rawCount > 0)
        assertEquals(rawCount, links.size)
        assertTrue("no link ends in the sentence's punctuation", links.none { it.url.last() in ".,;:)" })
    }

    @Test
    fun `every code block is kept exactly`() {
        val expected = mutableListOf<String>()
        var open = -1
        for ((i, line) in lines.withIndex()) {
            if (!line.startsWith("```")) continue
            if (open < 0) {
                open = i
            } else {
                expected += lines.subList(open + 1, i).joinToString("\n")
                open = -1
            }
        }
        assertEquals("every fence is closed", -1, open)
        assertTrue(expected.size > 20)
        assertEquals(expected, notices.blocks.filterIsInstance<Code>().map { it.text })

        // And as the screen gets them: each block's runs of lines, put back together, are the block again.
        val blocks = mutableListOf<MutableList<String>>()
        var inBlock = false
        for (row in notices.rows().filterIsInstance<CodeLines>()) {
            assertEquals("a block starts only after the last one ended", !inBlock, row.first)
            if (row.first) blocks += mutableListOf<String>()
            blocks.last() += row.text
            inBlock = !row.last
        }
        assertFalse("the last block ends", inBlock)
        assertEquals(expected, blocks.map { it.joinToString("\n") })
    }

    @Test
    fun `the direct build leaves out exactly the play only rows`() {
        val rawPlayOnly = outsideFences.filter { it.startsWith("|") && rawCells(it).last() == "play only" }
            .map { rawCells(it).first() }
        assertTrue("the file marks some rows play only", rawPlayOnly.isNotEmpty())

        val direct = notices.withoutPlayOnly()
        val before = notices.blocks.filterIsInstance<Table>()
        val after = direct.blocks.filterIsInstance<Table>()
        val removed = before.zip(after).flatMap { (b, a) -> (b.rows - a.rows.toSet()).map { it.first() } }
        assertEquals(rawPlayOnly, removed)
        assertEquals(before.sumOf { it.rows.size } - rawPlayOnly.size, after.sumOf { it.rows.size })
        for ((b, a) in before.zip(after)) {
            assertEquals("the rest keep their order", b.rows.filter { it in a.rows }, a.rows)
            assertEquals(b.header, a.header)
        }
        // The Go modules' table has no Build column and keeps every row.
        assertEquals(before.first { it.buildColumn < 0 }, after.first { it.buildColumn < 0 })
    }

    @Test
    fun `the direct build leaves out exactly the sections marked Google Play build only`() {
        // From the raw lines: the headings that stay, and the fenced blocks under a Play-only heading, which go.
        val keptHeadings = mutableListOf<String>()
        val droppedCode = mutableListOf<String>()
        var dropLevel = 0
        var fenceStart = -1
        for ((i, line) in lines.withIndex()) {
            if (line.startsWith("```")) {
                if (fenceStart < 0) {
                    fenceStart = i
                } else {
                    if (dropLevel > 0) droppedCode += lines.subList(fenceStart + 1, i).joinToString("\n")
                    fenceStart = -1
                }
                continue
            }
            if (fenceStart >= 0 || !line.startsWith("#")) continue
            val level = line.takeWhile { it == '#' }.length
            val text = line.dropWhile { it == '#' }.trim()
            if (dropLevel > 0 && level <= dropLevel) dropLevel = 0
            if (dropLevel == 0 && text.endsWith(ThirdPartyNotices.PLAY_ONLY_SECTION)) dropLevel = level
            if (dropLevel == 0) keptHeadings += text
        }
        assertTrue("the file has Play-only sections, with texts", droppedCode.isNotEmpty())

        val direct = notices.withoutPlayOnly()
        assertEquals(keptHeadings, direct.blocks.filterIsInstance<Heading>().map { it.text.text })
        val allCode = notices.blocks.filterIsInstance<Code>().map { it.text }
        assertEquals(allCode.filterNot { it in droppedCode }, direct.blocks.filterIsInstance<Code>().map { it.text })
        // What stays is the full notice's own blocks, in order: the paragraph explaining "play only" included.
        val full = notices.blocks.filterNot { it is Table }
        var at = 0
        for (block in direct.blocks.filterNot { it is Table }) {
            while (at < full.size && full[at] != block) at++
            assertTrue("kept in order: $block", at < full.size)
            at++
        }
        assertTrue(direct.blocks.any { it is Paragraph && it.text.text.startsWith("\"play only\" marks") })
    }

    @Test
    fun `every Build cell is both or play only`() {
        val libraries = notices.blocks.filterIsInstance<Table>().single { it.buildColumn >= 0 }
        val values = libraries.rows.map { it[libraries.buildColumn] }
        assertTrue(values.toSet().toString(), values.all { it == ThirdPartyNotices.BOTH || it == ThirdPartyNotices.PLAY_ONLY })
    }

    @Test
    fun `the file uses no Markdown the parser does not read`() {
        // HTML, a blockquote, a tilde fence, indented code, an ordered list.
        val unread = Regex("""^(<|>|~~~| {4,}|\d+[.)]\s)""")
        // A setext heading's underline.
        val setext = Regex("""^ {0,3}(=+|-{2,})\s*$""")
        // Emphasis with single delimiters, escapes and character references.
        val emphasis = Regex("""(^|[\s(])([*_])[^\s*_][^*_]*?\2($|[\s).,;:!?])""")
        val escapes = Regex("""\\[\\`*_{}\[\]()#+\-.!]|&[a-zA-Z]+;|&#\d+;""")
        for ((i, line) in outsideFences.withIndex()) {
            assertFalse("not read: '$line'", unread.containsMatchIn(line))
            assertFalse("a setext underline: '$line'", i > 0 && outsideFences[i - 1].isNotBlank() && setext.matches(line))
            assertFalse("emphasis: '$line'", emphasis.containsMatchIn(line))
            assertFalse("an escape or entity: '$line'", escapes.containsMatchIn(line))
        }
    }

    @Test
    fun `headings, paragraphs and bullets, their wrapped lines joined`() {
        val parsed = ThirdPartyNotices.parse(
            """
            # Title

            The first line of a paragraph
            and its second line.

            - An item that wraps
              onto a second line
            - Another item
            ## Section ##
            Text right under it.
            """.trimIndent()
        )
        assertEquals(
            listOf(
                Heading(1, Inline("Title")),
                Paragraph(Inline("The first line of a paragraph and its second line.")),
                Bullet(Inline("An item that wraps onto a second line")),
                Bullet(Inline("Another item")),
                Heading(2, Inline("Section")),
                Paragraph(Inline("Text right under it.")),
            ),
            parsed.blocks,
        )
    }

    @Test
    fun `code spans and links are marked, and a link leaves the sentence's punctuation out`() {
        val inline = ThirdPartyNotices.inline(
            "The `direct` build (https://example.org/terms). See https://example.com/, then http://example.net/a."
        )
        assertEquals(
            "The direct build (https://example.org/terms). See https://example.com/, then http://example.net/a.",
            inline.text,
        )
        val code = inline.marks.filterIsInstance<CodeMark>().map { inline.text.substring(it.start, it.end) }
        assertEquals(listOf("direct"), code)
        val links = inline.marks.filterIsInstance<LinkMark>()
        assertEquals(
            listOf("https://example.org/terms", "https://example.com/", "http://example.net/a"),
            links.map { it.url },
        )
        assertTrue(links.all { inline.text.substring(it.start, it.end) == it.url })
        // A backtick without its pair, and an address inside a word, stay text.
        assertEquals(Inline("a ` b xhttps://example.org"), ThirdPartyNotices.inline("a ` b xhttps://example.org"))
    }

    @Test
    fun `a table and a fenced block, with Windows line ends`() {
        val markdown = listOf(
            "| Library | Version | License | Build |",
            "|---|---|---|---|",
            "| example:one | 1.0 | MIT | both |",
            "| example:two | 2.0-beta | Apache-2.0 | play only |",
            "",
            "```",
            "   Indented title",
            "",
            "# not a heading | not a table",
            "```",
            "After the block.",
        ).joinToString("\r\n")
        val parsed = ThirdPartyNotices.parse(markdown)
        assertEquals(ThirdPartyNotices.parse(markdown.replace("\r\n", "\n")).blocks, parsed.blocks)

        val table = parsed.blocks[0] as Table
        assertEquals(3, table.buildColumn)
        val one = listOf("example:one", "1.0", "MIT", "both")
        val two = listOf("example:two", "2.0-beta", "Apache-2.0", "play only")
        assertEquals(listOf(one, two), table.rows)
        assertEquals(Code("   Indented title\n\n# not a heading | not a table"), parsed.blocks[1])
        assertEquals(Paragraph(Inline("After the block.")), parsed.blocks[2])
        assertEquals(listOf(one), (parsed.withoutPlayOnly().blocks[0] as Table).rows)
    }

    @Test
    fun `an unclosed fence runs to the end`() {
        assertEquals(listOf(Code("kept\nas is")), ThirdPartyNotices.parse("```\nkept\nas is").blocks)
        assertNull(ThirdPartyNotices.parse("").blocks.firstOrNull())
    }

    @Test
    fun `a wrapped code line continues under its text, or under the text after a list marker`() {
        assertEquals(0, ThirdPartyNotices.hangingIndent("THE SOFTWARE IS PROVIDED"))
        assertEquals(6, ThirdPartyNotices.hangingIndent("      \"Example\" shall mean"))
        assertEquals(5, ThirdPartyNotices.hangingIndent("   * Redistributions of source code"))
        assertEquals(6, ThirdPartyNotices.hangingIndent("   2. Grant of Copyright License."))
        assertEquals(10, ThirdPartyNotices.hangingIndent("      (a) You must give"))
        assertEquals(3, ThirdPartyNotices.hangingIndent("1. Redistributions"))
        // Words in brackets are not markers, and neither is a year or a version.
        assertEquals(0, ThirdPartyNotices.hangingIndent("(The Example License)"))
        assertEquals(0, ThirdPartyNotices.hangingIndent("(INCLUDING NEGLIGENCE OR OTHERWISE)"))
        assertEquals(0, ThirdPartyNotices.hangingIndent("2009 Example Authors"))
        assertEquals(0, ThirdPartyNotices.hangingIndent("1.80 and later"))
        assertEquals(0, ThirdPartyNotices.hangingIndent(""))
    }

    @Test
    fun `a code block comes in runs that end at blank lines and join back exactly`() {
        val text = "a\nb\n\nc\n\n\nd"
        assertEquals(listOf("a\nb\n", "c\n\n", "d"), ThirdPartyNotices.codeRuns(text))
        val long = (1..60).joinToString("\n") { "line $it" }
        val runs = ThirdPartyNotices.codeRuns(long)
        assertEquals("a long paragraph is cut too", 3, runs.size)
        assertEquals(long, runs.joinToString("\n"))
    }
}

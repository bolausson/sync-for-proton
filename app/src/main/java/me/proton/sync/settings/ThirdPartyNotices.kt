package me.proton.sync.settings

/**
 * THIRD-PARTY-NOTICES.md, the app's third-party notice, read for the licences screen ([LicencesActivity]).
 *
 * The screen shows the notice file itself. The build bundles it from the repository root, where it is also the notice
 * the published source carries, so there is one text to keep current. This reads the part of Markdown that file uses
 * and nothing more: `#` headings, paragraphs, `-` bullets, pipe tables, fenced code blocks, and inside text, code spans
 * and bare URLs. Anything else would stay on screen as written, raw syntax included; a test against the real file
 * fails on the constructs this does not read (HTML, quotes, other fences and lists, emphasis, escapes).
 *
 * Plain Kotlin with no Android types, so those tests read the file straight from the project directory.
 */
internal class ThirdPartyNotices private constructor(val blocks: List<Block>) {

    /** Text with its code spans and links marked. [text] holds no Markdown syntax: no backticks, no pipes. */
    data class Inline(val text: String, val marks: List<Mark> = emptyList())

    /** A code span or a link over `[start, end)` of [Inline.text]. */
    sealed interface Mark {
        val start: Int
        val end: Int
    }

    data class CodeMark(override val start: Int, override val end: Int) : Mark
    data class LinkMark(override val start: Int, override val end: Int, val url: String) : Mark

    /** One Markdown element of the file. */
    sealed interface Block

    /**
     * One item on screen: a heading, paragraph or bullet as it is, a table a row at a time, a code block a run of
     * lines at a time.
     */
    sealed interface Row

    data class Heading(val level: Int, val text: Inline) : Block, Row
    data class Paragraph(val text: Inline) : Block, Row

    /** One `-` item. The file's lists are flat, and a continuation line belongs to the item above it. */
    data class Bullet(val text: Inline) : Block, Row

    data class Table(val header: List<String>, val rows: List<List<String>>) : Block {
        /** The column saying which build a library is in ([BOTH] or [PLAY_ONLY]), or -1 when the table has none. */
        val buildColumn: Int get() = header.indexOfFirst { it.equals(BUILD_HEADER, ignoreCase = true) }
    }

    /** A fenced block, a NOTICE file or a licence text: exactly the lines between the fences. */
    data class Code(val text: String) : Block

    /** One row of a [Table]. [first] and [last] say where it sits in the card the table is drawn as. */
    data class TableRow(
        val header: List<String>,
        val cells: List<String>,
        val buildColumn: Int,
        val first: Boolean,
        val last: Boolean,
    ) : Row

    /** A run of lines of one [Code] block. The runs of a block, joined with newlines, are its text exactly. */
    data class CodeLines(val text: String, val first: Boolean, val last: Boolean) : Row

    /**
     * The notice of the direct build, without what only the Google Play build contains: the table rows marked
     * [PLAY_ONLY] (Google Play Billing and the libraries it brings), and every section whose heading ends with
     * [PLAY_ONLY_SECTION] (the open-source software inside those libraries and its licence texts), up to the next
     * heading of the same or a higher level. Everything else stays as written, the paragraph explaining
     * "play only" included.
     */
    fun withoutPlayOnly(): ThirdPartyNotices {
        val kept = mutableListOf<Block>()
        var droppedLevel = 0 // inside a Play-only section of this heading level, or 0
        for (block in blocks) {
            if (block is Heading) {
                if (droppedLevel > 0 && block.level <= droppedLevel) droppedLevel = 0
                if (droppedLevel == 0 && block.text.text.trimEnd().endsWith(PLAY_ONLY_SECTION, ignoreCase = true)) {
                    droppedLevel = block.level
                }
            }
            if (droppedLevel > 0) continue
            kept += if (block is Table && block.buildColumn >= 0) {
                val column = block.buildColumn
                block.copy(rows = block.rows.filterNot { it.getOrNull(column).equals(PLAY_ONLY, ignoreCase = true) })
            } else {
                block
            }
        }
        return ThirdPartyNotices(kept)
    }

    /**
     * What the screen draws, in order. A table comes a row at a time and a code block a run of lines at a time, so
     * the list lays out only what is on screen: the Apache License alone is 200 lines.
     */
    fun rows(): List<Row> = buildList {
        for (block in blocks) {
            when (block) {
                is Heading -> add(block)
                is Paragraph -> add(block)
                is Bullet -> add(block)
                is Table -> block.rows.forEachIndexed { i, cells ->
                    val last = i == block.rows.lastIndex
                    add(TableRow(block.header, cells, block.buildColumn, first = i == 0, last = last))
                }
                is Code -> {
                    val runs = codeRuns(block.text)
                    runs.forEachIndexed { i, text -> add(CodeLines(text, first = i == 0, last = i == runs.lastIndex)) }
                }
            }
        }
    }

    companion object {
        /** The header of the column naming the build, and its two values. */
        const val BUILD_HEADER = "Build"
        const val BOTH = "both"
        const val PLAY_ONLY = "play only"

        /** How a heading ends whose whole section only the Google Play build contains. */
        const val PLAY_ONLY_SECTION = "(Google Play build only)"

        /** Longest run of code lines in one row. Most runs end sooner, at a blank line. */
        private const val RUN_LINES = 24

        private val HEADING = Regex("""^ {0,3}(#{1,6})[ \t]+(.*?)(?:[ \t]+#+)?[ \t]*$""")
        private val BULLET = Regex("""^ {0,3}[-*+][ \t]+(.*)$""")
        private val OPENING_FENCE = Regex("""^ {0,3}```.*$""")
        private val CLOSING_FENCE = Regex("""^ {0,3}```[ \t]*$""")
        private val TABLE_DELIMITER = Regex("""^ {0,3}\|?[ \t]*:?-+:?[ \t]*(\|[ \t]*:?-+:?[ \t]*)*\|?[ \t]*$""")
        private val LIST_MARKER = Regex("""^(?:[*+-]|\d{1,2}[.)]|\([a-z0-9]{1,3}\)) +""")

        fun parse(markdown: String): ThirdPartyNotices {
            // The bundled asset has LF; the tests read the working tree, which has CRLF on Windows. Both are read.
            val lines = markdown.replace("\r\n", "\n").replace('\r', '\n').split('\n')
            val blocks = mutableListOf<Block>()
            var i = 0
            while (i < lines.size) {
                val heading = HEADING.matchEntire(lines[i])
                i = when {
                    lines[i].isBlank() -> i + 1
                    OPENING_FENCE.matches(lines[i]) -> readCode(lines, i, blocks)
                    isTableStart(lines, i) -> readTable(lines, i, blocks)
                    heading != null -> {
                        blocks += Heading(heading.groupValues[1].length, inline(heading.groupValues[2]))
                        i + 1
                    }
                    else -> readText(lines, i, blocks)
                }
            }
            return ThirdPartyNotices(blocks)
        }

        /** A fenced block, kept exactly: nothing trimmed, nothing unindented. An unclosed fence runs to the end. */
        private fun readCode(lines: List<String>, from: Int, blocks: MutableList<Block>): Int {
            var i = from + 1
            val body = mutableListOf<String>()
            while (i < lines.size && !CLOSING_FENCE.matches(lines[i])) body += lines[i++]
            blocks += Code(body.joinToString("\n"))
            return minOf(i + 1, lines.size)
        }

        private fun readTable(lines: List<String>, from: Int, blocks: MutableList<Block>): Int {
            val header = cells(lines[from])
            val rows = mutableListOf<List<String>>()
            var i = from + 2
            while (i < lines.size && lines[i].trimStart().startsWith("|")) rows += cells(lines[i++])
            blocks += Table(header, rows)
            return i
        }

        /** A paragraph, or one bullet: its lines up to a blank line or the next block, unwrapped into one. */
        private fun readText(lines: List<String>, from: Int, blocks: MutableList<Block>): Int {
            val bullet = BULLET.matchEntire(lines[from])
            val parts = mutableListOf((bullet?.groupValues?.get(1) ?: lines[from]).trim())
            var i = from + 1
            while (i < lines.size && lines[i].isNotBlank() && !startsBlock(lines, i)) parts += lines[i++].trim()
            val text = inline(parts.joinToString(" "))
            blocks += if (bullet != null) Bullet(text) else Paragraph(text)
            return i
        }

        private fun startsBlock(lines: List<String>, i: Int): Boolean =
            OPENING_FENCE.matches(lines[i]) || HEADING.matches(lines[i]) || BULLET.matches(lines[i]) ||
                isTableStart(lines, i)

        private fun isTableStart(lines: List<String>, i: Int): Boolean =
            lines[i].trimStart().startsWith("|") && i + 1 < lines.size && TABLE_DELIMITER.matches(lines[i + 1])

        /** The cells of a table line without its outer pipes. The file's cells hold text and code spans at most. */
        private fun cells(line: String): List<String> =
            line.trim().removePrefix("|").removeSuffix("|").split('|').map { inline(it.trim()).text }

        /** Code spans and bare URLs marked, the backticks dropped; everything else is text as written. */
        fun inline(source: String): Inline {
            val text = StringBuilder()
            val marks = mutableListOf<Mark>()
            var i = 0
            while (i < source.length) {
                val close = if (source[i] == '`') source.indexOf('`', i + 1) else -1
                val url = if (i == 0 || !source[i - 1].isLetterOrDigit()) urlAt(source, i) else null
                when {
                    close > i -> {
                        val start = text.length
                        text.append(source, i + 1, close)
                        marks += CodeMark(start, text.length)
                        i = close + 1
                    }
                    url != null -> {
                        val start = text.length
                        text.append(url)
                        marks += LinkMark(start, text.length, url)
                        i += url.length
                    }
                    else -> text.append(source[i++])
                }
            }
            return Inline(text.toString(), marks)
        }

        /**
         * The bare URL starting at [i], or null. It ends at a space, a bracket or a quote, and a full stop or comma
         * right after it is the sentence's: "(https://developer.android.com/studio/terms)." links the address only.
         */
        private fun urlAt(s: String, i: Int): String? {
            if (!s.startsWith("https://", i) && !s.startsWith("http://", i)) return null
            var end = i
            while (end < s.length && !s[end].isWhitespace() && s[end] !in "<>()[]\"`") end++
            while (end > i && s[end - 1] in ".,;:!?'") end--
            return s.substring(i, end).takeIf { it.substringAfter("://").isNotEmpty() }
        }

        /**
         * The column a wrapped code line continues at: under its first character, or under the text after a list
         * marker such as "*", "2." or "(a)". That is how these texts indent their own wrapped lines, so on a screen
         * too narrow for them a wrap reads like one of theirs.
         */
        fun hangingIndent(line: String): Int {
            val indent = line.length - line.trimStart(' ').length
            return indent + (LIST_MARKER.find(line.substring(indent))?.value?.length ?: 0)
        }

        /**
         * A code block's text in runs of lines, each ending after a blank line where it can, or after [RUN_LINES]
         * lines, so a row holds about a paragraph of a licence. Joined with newlines the runs are the text again.
         */
        fun codeRuns(text: String): List<String> {
            val lines = text.split('\n')
            val runs = mutableListOf<String>()
            var start = 0
            for (i in lines.indices) {
                val blankRunEnds = lines[i].isBlank() && lines.getOrNull(i + 1)?.isBlank() != true
                if (blankRunEnds || i + 1 - start >= RUN_LINES || i == lines.lastIndex) {
                    runs += lines.subList(start, i + 1).joinToString("\n")
                    start = i + 1
                }
            }
            return runs
        }
    }
}

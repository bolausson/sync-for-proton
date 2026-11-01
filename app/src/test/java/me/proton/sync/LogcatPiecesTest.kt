package me.proton.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Since 2.0.4 SyncLog writes its redacted line to logcat itself (Log.println), where Log.e(tag, msg, tr) used to cut
 * a long verbose trace into entries logcat keeps whole. [LogBuffer.logcatPieces] does that cutting now.
 */
class LogcatPiecesTest {

    @Test
    fun `a line that fits is one piece, the very same string`() {
        val line = "Calendar sync completed for test"
        assertSame(line, LogBuffer.logcatPieces(line, 1000).single())
    }

    @Test
    fun `a long trace is cut at line ends`() {
        val trace = "boom\n" + (1..400).joinToString("\n") { "\tat me.proton.sync.Frame$it.run(Frame.kt:$it)" }
        val pieces = LogBuffer.logcatPieces(trace, 1000)
        assertTrue(pieces.size > 1)
        assertTrue(pieces.all { it.length <= 1000 })
        assertEquals(trace, pieces.joinToString("\n"))
    }

    @Test
    fun `a single longer line is cut where it reaches the limit`() {
        val line = "x".repeat(2_500)
        val pieces = LogBuffer.logcatPieces(line, 1000)
        assertEquals(listOf(1000, 1000, 500), pieces.map { it.length })
        assertEquals(line, pieces.joinToString(""))
    }
}

package com.awkoo.libterminal.engine.buffer

import com.awkoo.libterminal.text.TextStyle
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 缓冲区 resize 的三类路径：垂直扩展/收缩（环形窗口平移）、
 * 水平 reflow（内容保序重排）、备用屏不重排（位置保持裁剪补白）。
 */
class TerminalBufferResizeTest {

    private fun fill(buffer: TerminalBuffer, texts: List<String>) {
        texts.forEachIndexed { row, text ->
            text.forEachIndexed { column, c ->
                buffer.setChar(column, row, c.code, TextStyle.NORMAL)
            }
        }
    }

    private fun textsOf(buffer: TerminalBuffer, rows: Int): List<String> =
        (0 until rows).map { String(buffer.getRowForRead(it).textChars()).trimEnd() }

    private fun historyTextsOf(buffer: TerminalBuffer): List<String> =
        (-1 downTo -buffer.activeTranscriptRows).map { String(buffer.getRowForRead(it).textChars()).trimEnd() }

    @Test
    fun growRows_keepsScreenContentAndAddsBlankRowsAtBottom() {
        val buffer = TerminalBuffer(4, 8, 3)
        fill(buffer, listOf("aaaa", "bbbb", "cccc"))

        buffer.resize(4, 5, 8, CursorCoord.pack(0, 0), TextStyle.NORMAL, altScreen = false)

        assertEquals(listOf("aaaa", "bbbb", "cccc", "", ""), textsOf(buffer, 5))
        assertEquals(0, buffer.activeTranscriptRows)
    }

    @Test
    fun shrinkRows_dropsTopRowsIntoHistory() {
        val buffer = TerminalBuffer(4, 8, 5)
        fill(buffer, listOf("aaaa", "bbbb", "cccc", "dddd", "eeee"))

        buffer.resize(4, 3, 8, CursorCoord.pack(0, 0), TextStyle.NORMAL, altScreen = false)

        assertEquals(listOf("cccc", "dddd", "eeee"), textsOf(buffer, 3))
        assertEquals(listOf("bbbb", "aaaa"), historyTextsOf(buffer))
        assertEquals(2, buffer.activeTranscriptRows)
    }

    @Test
    fun growRows_afterScroll_pullsHistoryBackIntoView() {
        val buffer = TerminalBuffer(4, 8, 3)
        fill(buffer, listOf("aaaa", "bbbb", "cccc"))
        buffer.scrollDownOneLine(0, 3, TextStyle.NORMAL)

        buffer.resize(4, 5, 8, CursorCoord.pack(0, 0), TextStyle.NORMAL, altScreen = false)

        assertEquals(listOf("aaaa", "bbbb", "cccc", "", ""), textsOf(buffer, 5))
        assertEquals(0, buffer.activeTranscriptRows)
    }

    @Test
    fun widenColumn_reflowKeepsContentInOrder() {
        val buffer = TerminalBuffer(10, 10, 5)
        fill(buffer, listOf("abcdefghij"))

        buffer.resize(20, 5, 10, CursorCoord.pack(0, 0), TextStyle.NORMAL, altScreen = false)

        val rejoined = textsOf(buffer, 5).filter { it.isNotEmpty() }.joinToString("")
        assertEquals("abcdefghij", rejoined)
    }

    @Test
    fun narrowColumn_reflowSplitsLongLineAndKeepsOrder() {
        val buffer = TerminalBuffer(10, 10, 5)
        fill(buffer, listOf("abcdefghij"))

        buffer.resize(4, 5, 10, CursorCoord.pack(0, 0), TextStyle.NORMAL, altScreen = false)

        assertEquals("abcd", textsOf(buffer, 5)[0])
        val rejoined = textsOf(buffer, 5).filter { it.isNotEmpty() }.joinToString("")
        assertEquals("abcdefghij", rejoined)
    }

    @Test
    fun altScreenResize_neverReflowsKeepsTopAnchor() {
        val buffer = TerminalBuffer(6, 6, 3)
        fill(buffer, listOf("aaaaaa", "bbbbbb", "cccccc"))

        buffer.resize(6, 5, 6, CursorCoord.pack(0, 0), TextStyle.NORMAL, altScreen = true)

        assertEquals(listOf("aaaaaa", "bbbbbb", "cccccc", "", ""), textsOf(buffer, 5))
    }

    @Test
    fun altScreenResize_widen_truncatesEachRowToNewWidth() {
        val buffer = TerminalBuffer(6, 6, 3)
        fill(buffer, listOf("abcdef", "ghijkl", "mnopqr"))

        buffer.resize(4, 3, 6, CursorCoord.pack(0, 0), TextStyle.NORMAL, altScreen = true)

        assertEquals(listOf("abcd", "ghij", "mnop"), textsOf(buffer, 3))
    }

    @Test
    fun resize_returnsNonNegativeCursor() {
        val buffer = TerminalBuffer(4, 8, 3)
        fill(buffer, listOf("aaaa", "bbbb", "cccc"))

        val cursor = buffer.resize(4, 5, 8, CursorCoord.pack(1, 1), TextStyle.NORMAL, altScreen = false)

        assert(cursor.col >= 0 && cursor.row >= 0)
    }
}

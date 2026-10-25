package com.awkoo.libterminal.engine.buffer

import com.awkoo.libterminal.text.TextStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 行级续行标记（lineWrap）必须与行内容同生共死。
 *
 * 覆盖搬移原语 shiftLines 的全部形态：整行上/下移、收窄矩形退化路径、
 * 带滚动历史的整屏滚动，以及 resize 时的跨列宽复制。
 */
class TerminalBufferLineWrapShiftTest {

    private fun newBuffer(columns: Int, rows: Int) = TerminalBuffer(columns, rows, rows)

    private fun fill(buffer: TerminalBuffer, texts: List<String>) {
        texts.forEachIndexed { row, text ->
            text.forEachIndexed { column, c ->
                buffer.setChar(column, row, c.code, TextStyle.NORMAL)
            }
        }
    }

    private fun textsOf(buffer: TerminalBuffer, rows: Int): List<String> =
        (0 until rows).map { String(buffer.getRowForRead(it).textChars()).trimEnd() }

    private fun wrapsOf(buffer: TerminalBuffer, rows: Int): List<Boolean> =
        (0 until rows).map(buffer::getLineWrap)

    private fun historyWrapsOf(buffer: TerminalBuffer): List<Boolean> =
        (-1 downTo -buffer.activeTranscriptRows).map(buffer::getLineWrap)

    @Test
    fun insertLines_carriesWrapFlagDownAndLeavesTheInsertedRowsUnwrapped() {
        val buffer = newBuffer(4, 4)
        fill(buffer, listOf("aaaa", "bbbb", "cccc", "dddd"))
        buffer.setLineWrap(0)
        buffer.setLineWrap(1)

        buffer.insertLines(0, 2, 4, TextStyle.NORMAL)

        assertEquals(listOf("", "", "aaaa", "bbbb"), textsOf(buffer, 4))
        assertEquals(listOf(false, false, true, true), wrapsOf(buffer, 4))
    }

    @Test
    fun deleteLines_carriesWrapFlagUpAndLeavesTheReclaimedRowUnwrapped() {
        val buffer = newBuffer(4, 4)
        fill(buffer, listOf("aaaa", "bbbb", "cccc", "dddd"))
        buffer.setLineWrap(1)
        buffer.setLineWrap(3)

        buffer.deleteLines(0, 2, 4, TextStyle.NORMAL)

        assertEquals(listOf("cccc", "dddd", "", ""), textsOf(buffer, 4))
        assertEquals(listOf(false, true, false, false), wrapsOf(buffer, 4))
    }

    @Test
    fun shiftLinesDown_wrapsRotateWithRowsAndBlankTopResets() {
        val buffer = newBuffer(4, 4)
        fill(buffer, listOf("aaaa", "bbbb", "cccc", "dddd"))
        buffer.setLineWrap(2)

        buffer.shiftLines(0, 4, 1, 0, 4, up = false, style = TextStyle.NORMAL)

        assertEquals(listOf("", "aaaa", "bbbb", "cccc"), textsOf(buffer, 4))
        assertEquals(listOf(false, false, false, true), wrapsOf(buffer, 4))
    }

    @Test
    fun scrollRegionOneLine_pushesWrappedRowIntoHistory() {
        val buffer = TerminalBuffer(4, 6, 4)
        fill(buffer, listOf("aaaa", "bbbb", "cccc", "dddd"))
        buffer.setLineWrap(0)

        buffer.scrollRegionOneLine(0, 4, 0, 4, TextStyle.NORMAL)

        assertEquals(listOf("bbbb", "cccc", "dddd", ""), textsOf(buffer, 4))
        assertEquals(1, buffer.activeTranscriptRows)
        assertEquals(listOf(true), historyWrapsOf(buffer))
    }

    @Test
    fun narrowRectShift_doesNotMoveRowLevelMetadata() {
        val buffer = newBuffer(6, 3)
        fill(buffer, listOf("aaaaaa", "bbbbbb", "cccccc"))
        buffer.setLineWrap(1)

        buffer.shiftLines(0, 3, 1, 1, 5, up = true, style = TextStyle.NORMAL)

        assertEquals(listOf("abbbba", "bccccb", "c    c"), textsOf(buffer, 3))
        assertEquals(listOf(false, true, false), wrapsOf(buffer, 3))
    }

    @Test
    fun shiftLines_countClampedToRegionHeight() {
        val buffer = newBuffer(4, 3)
        fill(buffer, listOf("aaaa", "bbbb", "cccc"))

        buffer.shiftLines(0, 3, 10, 0, 4, up = true, style = TextStyle.NORMAL)

        assertEquals(listOf("", "", ""), textsOf(buffer, 3))
    }

    @Test
    fun shiftLines_zeroOrNegativeCount_isNoOp() {
        val buffer = newBuffer(4, 3)
        fill(buffer, listOf("aaaa", "bbbb", "cccc"))

        buffer.shiftLines(0, 3, 0, 0, 4, up = true, style = TextStyle.NORMAL)
        buffer.shiftLines(0, 3, -1, 0, 4, up = true, style = TextStyle.NORMAL)

        assertEquals(listOf("aaaa", "bbbb", "cccc"), textsOf(buffer, 3))
    }

    @Test
    fun scrollDownOneLine_fullScreen_wrappedTopRowLandsInHistoryWrapped() {
        val buffer = TerminalBuffer(4, 5, 3)
        fill(buffer, listOf("aaaa", "bbbb", "cccc"))
        buffer.setLineWrap(0)

        buffer.scrollDownOneLine(0, 3, TextStyle.NORMAL)

        assertEquals(listOf("bbbb", "cccc", ""), textsOf(buffer, 3))
        assertEquals(listOf(true), historyWrapsOf(buffer))
        assertFalse(buffer.getLineWrap(0))
    }

    @Test
    fun clearRow_resetsWrapFlagOfBlankedRow() {
        val buffer = newBuffer(4, 3)
        fill(buffer, listOf("aaaa", "bbbb", "cccc"))
        buffer.setLineWrap(0)

        buffer.deleteLines(0, 3, 3, TextStyle.NORMAL)

        assertEquals(listOf(false, false, false), wrapsOf(buffer, 3))
    }

    @Test
    fun copyRowResizing_throughResize_carriesWrapFlag() {
        val buffer = TerminalBuffer(6, 6, 3)
        fill(buffer, listOf("aaaaaa", "bbbbbb", "cccccc"))
        buffer.setLineWrap(0)

        buffer.resize(4, 3, 6, longArrayOf(CursorCoord.pack(0, 0).packed), TextStyle.NORMAL, altScreen = true)

        assertEquals(listOf("aaaa", "bbbb", "cccc"), textsOf(buffer, 3))
        assertTrue(wrapsOf(buffer, 3)[0])
    }
}

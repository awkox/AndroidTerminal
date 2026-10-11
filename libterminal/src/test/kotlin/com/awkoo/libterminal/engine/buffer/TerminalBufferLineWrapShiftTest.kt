package com.awkoo.libterminal.engine.buffer

import com.awkoo.libterminal.text.TextStyle
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 整行纵向搬移后，行级续行标记必须与行内容同生共死。
 *
 * 覆盖搬移原语的全部调用方：CSI L（插入行）、CSI M（删除行）、RI、SD、
 * 收窄边距下的矩形退化路径，以及带滚动历史的整屏滚动。
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

    private fun stylesOf(buffer: TerminalBuffer, rows: Int, columns: Int): List<List<TextStyle>> =
        (0 until rows).map { row -> (0 until columns).map { buffer.getStyleAt(row, it) } }

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
    fun reverseIndex_carriesWrapFlagDownWithTheShiftedRow() {
        val buffer = newBuffer(4, 4)
        fill(buffer, listOf("aaaa", "bbbb", "cccc", "dddd"))
        buffer.setLineWrap(1)
        buffer.setLineWrap(3)

        buffer.shiftLines(0, 4, 1, 0, 4, up = false, style = TextStyle.NORMAL)

        assertEquals(listOf("", "aaaa", "bbbb", "cccc"), textsOf(buffer, 4))
        assertEquals(listOf(false, false, true, false), wrapsOf(buffer, 4))
    }

    @Test
    fun scrollDown_carriesWrapFlagWithTheWholeRegion() {
        val buffer = newBuffer(4, 4)
        fill(buffer, listOf("aaaa", "bbbb", "cccc", "dddd"))
        buffer.setLineWrap(0)

        buffer.shiftLines(0, 4, 2, 0, 4, up = false, style = TextStyle.NORMAL)

        assertEquals(listOf("", "", "aaaa", "bbbb"), textsOf(buffer, 4))
        assertEquals(listOf(false, false, true, false), wrapsOf(buffer, 4))
    }

    @Test
    fun scrollRegionWithoutMargins_keepsWrapFlagOnTheRowThatScrolledIntoHistory() {
        val buffer = newBuffer(4, 4)
        fill(buffer, listOf("aaaa", "bbbb", "cccc", "dddd"))
        buffer.setLineWrap(0)
        buffer.setLineWrap(2)

        buffer.scrollRegionOneLine(0, 4, 0, 4, TextStyle.NORMAL)

        assertEquals(listOf("bbbb", "cccc", "dddd", ""), textsOf(buffer, 4))
        assertEquals(listOf(false, true, false, false), wrapsOf(buffer, 4))
    }

    @Test
    fun narrowedRegion_shiftsOnlyItsOwnRectangleAndLeavesWrapFlagsWithTheRows() {
        val buffer = newBuffer(4, 4)
        fill(buffer, listOf("abcd", "efgh", "ijkl", "mnop"))
        buffer.setLineWrap(0)
        buffer.setLineWrap(3)

        buffer.shiftLines(0, 4, 1, 1, 3, up = true, style = TextStyle.NORMAL)

        assertEquals(listOf("afgd", "ejkh", "inol", "m  p"), textsOf(buffer, 4))
        assertEquals(listOf(true, false, false, true), wrapsOf(buffer, 4))
    }

    @Test
    fun wholeRowShift_clearsTheVacatedRowsWithTheEraseFillStyle() {
        val fillStyle =
            TextStyle.encode(TextStyle.COLOR_INDEX_FOREGROUND, 1, TextStyle.CHARACTER_ATTRIBUTE_BOLD)
        val body = listOf("aaaa", "bbbb", "cccc", "dddd")

        val shifted = newBuffer(4, 4)
        fill(shifted, body)
        shifted.blockSet(2, 1, 1, 1, 'x'.code, fillStyle)
        shifted.insertLines(0, 2, 4, fillStyle)

        val legacy = newBuffer(4, 4)
        fill(legacy, body)
        legacy.blockSet(2, 1, 1, 1, 'x'.code, fillStyle)
        legacy.blockCopy(0, 0, 4, 2, 0, 2)
        legacy.blockSet(0, 0, 4, 2, ' '.code, fillStyle)

        assertEquals(textsOf(legacy, 4), textsOf(shifted, 4))
        assertEquals(stylesOf(legacy, 4, 4), stylesOf(shifted, 4, 4))
    }

    @Test
    fun selectionAcrossRowsAfterShift_readsTheWrapFlagOfTheRowItMovedTo() {
        val buffer = newBuffer(5, 3)
        fill(buffer, listOf("hello", "world", "abc"))
        buffer.setLineWrap(0)

        buffer.shiftLines(0, 3, 1, 0, 5, up = false, style = TextStyle.NORMAL)

        assertEquals("hello", buffer.getSelectedText(0, 1, 4, 1))
        assertEquals("helloworld", buffer.getSelectedText(0, 1, 4, 2))
        assertEquals("", buffer.getSelectedText(0, 0, 4, 0))
    }

    @Test
    fun reflowAfterShift_joinsOnlyTheRowsThatStillCarryTheWrapFlag() {
        val buffer = newBuffer(4, 3)
        fill(buffer, listOf("abcd", "efgh", "ijkl"))
        buffer.setLineWrap(0)
        buffer.setLineWrap(1)

        buffer.deleteLines(0, 1, 3, TextStyle.NORMAL)
        buffer.resize(8, 3, 3, CursorCoord.pack(0, 0), TextStyle.NORMAL, false)

        assertEquals("efghijkl", textsOf(buffer, 3)[0])
    }
}

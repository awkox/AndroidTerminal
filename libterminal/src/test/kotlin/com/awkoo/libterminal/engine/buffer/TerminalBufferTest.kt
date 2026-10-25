package com.awkoo.libterminal.engine.buffer

import com.awkoo.libterminal.text.TextStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalBufferTest {

    /** 4 列 × 3 屏行、无滚动历史的最小缓冲。 */
    private fun smallBuffer() = TerminalBuffer(4, 3, 3)

    /** 4 列 × 3 屏行、8 槽环形（5 行滚动历史容量）。 */
    private fun bufferedBuffer() = TerminalBuffer(4, 8, 3)

    private fun TerminalBuffer.write(row: Int, text: String) {
        text.forEachIndexed { column, c -> setChar(column, row, c.code, TextStyle.NORMAL) }
    }

    private fun TerminalBuffer.rowText(row: Int): String =
        String(getRowForRead(row).textChars()).trimEnd()

    @Test
    fun construction_screenRowsAreBlankAndAllocated() {
        val buffer = smallBuffer()

        assertEquals(listOf("", "", ""), (0 until 3).map { buffer.rowText(it) })
        assertTrue(buffer.lines.all { it != null })
    }

    @Test
    fun setChar_outOfRange_throws() {
        val buffer = smallBuffer()

        expectThrows { buffer.setChar(4, 0, 'a'.code, TextStyle.NORMAL) }
        expectThrows { buffer.setChar(0, 3, 'a'.code, TextStyle.NORMAL) }
        expectThrows { buffer.setChar(0, -1, 'a'.code, TextStyle.NORMAL) }
    }

    @Test
    fun externalToInternalRow_outOfRange_throws() {
        val buffer = bufferedBuffer()

        expectThrows { buffer.externalToInternalRow(-6) }
        expectThrows { buffer.externalToInternalRow(3) }
    }

    @Test
    fun rowInRange_coversHistoryAndScreen() {
        val buffer = bufferedBuffer()
        buffer.write(0, "aaaa")
        buffer.scrollDownOneLine(0, 3, TextStyle.NORMAL)

        assertTrue(buffer.rowInRange(-1))
        assertTrue(buffer.rowInRange(0))
        assertTrue(buffer.rowInRange(2))
        assertFalse(buffer.rowInRange(-2))
        assertFalse(buffer.rowInRange(3))
    }

    @Test
    fun clampRow_andClampColumn_stayWithinDomain() {
        val buffer = bufferedBuffer()
        buffer.write(0, "aaaa")
        buffer.scrollDownOneLine(0, 3, TextStyle.NORMAL)

        assertEquals(-1, buffer.clampRow(-100))
        assertEquals(2, buffer.clampRow(100))
        assertEquals(0, buffer.clampColumn(-5))
        assertEquals(3, buffer.clampColumn(50))
    }

    @Test
    fun getRowForRead_outOfRange_returnsSharedBlankRow() {
        val buffer = smallBuffer()

        val first = buffer.getRowForRead(-1)
        val second = buffer.getRowForRead(7)

        assertSame(first, second)
        assertTrue(first.isBlank)
    }

    @Test
    fun getRowForRead_neverAllocatesTranscriptSlots() {
        val buffer = bufferedBuffer()

        buffer.getRowForRead(-1)

        assertTrue(buffer.lines.slice(3..7).all { it == null })
    }

    @Test
    fun scrollDownOneLine_movesScreenIntoHistoryAndBlanksLastRow() {
        val buffer = bufferedBuffer()
        buffer.write(0, "aaaa")
        buffer.write(1, "bbbb")
        buffer.write(2, "cccc")

        buffer.scrollDownOneLine(0, 3, TextStyle.NORMAL)

        assertEquals(1, buffer.activeTranscriptRows)
        assertEquals(listOf("bbbb", "cccc", ""), (0 until 3).map { buffer.rowText(it) })
        assertEquals("aaaa", buffer.rowText(-1))
    }

    @Test
    fun scrollDownOneLine_withTopMargin_keepsRowsAboveRegionInPlace() {
        val buffer = bufferedBuffer()
        buffer.write(0, "keep")
        buffer.write(1, "rrrr")
        buffer.write(2, "ssss")

        buffer.scrollDownOneLine(1, 3, TextStyle.NORMAL)

        assertEquals(listOf("keep", "ssss", ""), (0 until 3).map { buffer.rowText(it) })
        assertEquals("rrrr", buffer.rowText(-1))
    }

    @Test
    fun activeRows_reflectsHistoryAndScreen() {
        val buffer = bufferedBuffer()
        buffer.write(0, "aaaa")
        buffer.scrollDownOneLine(0, 3, TextStyle.NORMAL)

        assertEquals(1, buffer.activeTranscriptRows)
        assertEquals(4, buffer.activeRows)
    }

    @Test
    fun clearTranscript_dropsHistoryWindow() {
        val buffer = bufferedBuffer()
        buffer.write(0, "aaaa")
        buffer.scrollDownOneLine(0, 3, TextStyle.NORMAL)

        buffer.clearTranscript()

        assertEquals(0, buffer.activeTranscriptRows)
        assertFalse(buffer.rowInRange(-1))
    }

    @Test
    fun getSelectedText_singleRow_trimsTrailingBlanks() {
        val buffer = smallBuffer()
        buffer.write(0, "ab  ")
        buffer.write(1, "cd")

        assertEquals("ab", buffer.getSelectedText(0, 0, 3, 0))
    }

    @Test
    fun getSelectedText_columnRange_startsAtX1AndEndsAtX2OnLastRow() {
        val buffer = TerminalBuffer(6, 3, 3)
        buffer.write(0, "abcdef")
        buffer.write(1, "ghijkl")

        assertEquals("bcdef\ngh", buffer.getSelectedText(1, 0, 1, 1))
    }

    @Test
    fun getSelectedText_unwrappedRows_areJoinedWithNewline() {
        val buffer = smallBuffer()
        buffer.write(0, "top")
        buffer.write(1, "bot")

        assertEquals("top\nbot", buffer.getSelectedText(0, 0, 2, 1))
    }

    @Test
    fun getSelectedText_wrappedRows_areJoinedWithoutNewline() {
        val buffer = smallBuffer()
        buffer.write(0, "aaaa")
        buffer.write(1, "bbbb")
        buffer.setLineWrap(0)

        assertEquals("aaaabbbb", buffer.getSelectedText(0, 0, 3, 1))
    }

    @Test
    fun getLineWrap_outOfRangeRow_isFalse() {
        val buffer = smallBuffer()

        assertFalse(buffer.getLineWrap(-1))
        assertFalse(buffer.getLineWrap(5))
    }

    @Test
    fun getStyleAt_outOfRange_returnsNormal() {
        val buffer = smallBuffer()

        assertEquals(TextStyle.NORMAL, buffer.getStyleAt(-1, 0))
        assertEquals(TextStyle.NORMAL, buffer.getStyleAt(9, 9))
    }

    @Test
    fun getExtendedEffectAt_outOfRange_returnsZero() {
        val buffer = smallBuffer()

        assertEquals(0L, buffer.getExtendedEffectAt(-1, 0))
        assertEquals(0L, buffer.getExtendedEffectAt(0, 99))
    }

    @Test
    fun insertColumns_shiftsRightAndFillsVacatedCells() {
        val buffer = smallBuffer()
        buffer.write(0, "abcd")

        buffer.insertColumns(1, 0, 2, 1, 4, TextStyle.NORMAL)

        assertEquals("a  b", buffer.rowText(0))
    }

    @Test
    fun insertColumns_middle_keepsShiftedTail() {
        val buffer = smallBuffer()
        buffer.write(0, "abcd")

        buffer.insertColumns(1, 0, 1, 1, 4, TextStyle.NORMAL)

        assertEquals("a bc", buffer.rowText(0))
        assertEquals('b'.code, buffer.getRowForRead(0).textChars()[2].code)
    }

    @Test
    fun deleteColumns_shiftsLeftAndBlanksTail() {
        val buffer = smallBuffer()
        buffer.write(0, "abcd")

        buffer.deleteColumns(0, 0, 2, 1, 4, TextStyle.NORMAL)

        assertEquals("cd", buffer.rowText(0))
    }

    @Test
    fun blockCopy_outOfRange_throws() {
        val buffer = smallBuffer()

        expectThrows { buffer.blockCopy(0, 0, 5, 1, 0, 0) }
        expectThrows { buffer.blockCopy(0, 0, 1, 1, 0, 3) }
    }

    @Test
    fun blockSet_writesRectangle() {
        val buffer = smallBuffer()

        buffer.blockSet(1, 1, 2, 1, 'x'.code, TextStyle.NORMAL)

        assertEquals("", buffer.rowText(0))
        assertEquals(" xx", buffer.rowText(1))
        assertEquals("", buffer.rowText(2))
    }

    @Test
    fun snapToColumn_clampsAndDelegates() {
        val buffer = smallBuffer()
        buffer.setChar(0, 0, 0x4E2D, TextStyle.NORMAL)

        assertEquals(0, buffer.snapToColumn(0, -5, SnapAlign.Start))
        assertEquals(3, buffer.snapToColumn(0, 99, SnapAlign.Start))
        assertEquals(0, buffer.snapToColumn(0, 1, SnapAlign.Start))
        assertEquals(2, buffer.snapToColumn(0, 1, SnapAlign.End))
        assertEquals(1, buffer.snapToColumn(-1, 1, SnapAlign.Start))
    }

    @Test
    fun setOrClearEffect_rectangular_touchesOnlyInnerColumns() {
        val buffer = TerminalBuffer(6, 3, 3)
        buffer.write(0, "abcdef")
        buffer.write(1, "ghijkl")
        buffer.write(2, "mnopqr")

        buffer.setOrClearEffect(
            bits = TextStyle.CHARACTER_ATTRIBUTE_BOLD,
            setOrClear = true,
            reverse = false,
            rectangular = true,
            leftMargin = 0,
            rightMargin = 6,
            top = 0,
            left = 1,
            bottom = 2,
            right = 5
        )

        assertEquals(0, boldEffectOf(buffer, 0, 0))
        assertEquals(TextStyle.CHARACTER_ATTRIBUTE_BOLD, boldEffectOf(buffer, 0, 1))
        assertEquals(TextStyle.CHARACTER_ATTRIBUTE_BOLD, boldEffectOf(buffer, 1, 4))
        assertEquals(0, boldEffectOf(buffer, 1, 5))
        assertEquals(0, boldEffectOf(buffer, 2, 1))
    }

    private fun boldEffectOf(buffer: TerminalBuffer, row: Int, column: Int): Int {
        val raw = buffer.getRowForRead(row).getRawStyle(column)
        return (raw and TextStyle.CHARACTER_ATTRIBUTE_BOLD.toLong()).toInt()
    }

    private fun expectThrows(block: () -> Unit) {
        try {
            block()
        } catch (_: IllegalArgumentException) {
            return
        }
        throw AssertionError("expected IllegalArgumentException")
    }
}

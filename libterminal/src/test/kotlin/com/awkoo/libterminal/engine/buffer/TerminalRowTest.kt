package com.awkoo.libterminal.engine.buffer

import com.awkoo.libterminal.text.TextStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalRowTest {

    private fun newBlankRow(columns: Int = 8) = TerminalRow(columns, TextStyle.NORMAL)

    private fun TerminalRow.text(): String = String(textChars(), 0, charLength)

    private fun TerminalRow.write(text: String) {
        text.forEachIndexed { index, c -> setChar(index, c.code, TextStyle.NORMAL) }
    }

    @Test
    fun construction_fillsBlankRowWithSpaces() {
        val row = newBlankRow(4)

        assertTrue(row.isBlank)
        assertEquals("    ", row.text())
        assertEquals(4, row.charLength)
    }

    @Test
    fun setChar_asciiFastPath_writesSingleCell() {
        val row = newBlankRow(4)

        row.setChar(1, 'A'.code, TextStyle.NORMAL)

        assertEquals(" A  ", row.text())
        assertFalse(row.isBlank)
        assertFalse(row.hasNonOneWidthOrSurrogateChars)
    }

    @Test
    fun setChar_wideChar_marksRowAndOccupiesTwoColumns() {
        val row = newBlankRow(4)

        row.setChar(0, 0x4E2D, TextStyle.NORMAL)

        assertTrue(row.hasNonOneWidthOrSurrogateChars)
        assertEquals(0x4E2D, row.textChars()[0].code)
        assertFalse(row.isCellBlank(1))
    }

    @Test
    fun startAndEndCharOfColumn_wideCharSharesOneCharRange() {
        val row = newBlankRow(4)
        row.setChar(0, 0x4E2D, TextStyle.NORMAL)
        row.setChar(2, 'a'.code, TextStyle.NORMAL)

        assertEquals(0, row.startCharOfColumn(0))
        assertEquals(0, row.startCharOfColumn(1))
        assertEquals(1, row.startCharOfColumn(2))
        assertEquals(1, row.endCharOfColumn(0))
        assertEquals(1, row.endCharOfColumn(1))
        assertEquals(2, row.endCharOfColumn(2))
    }

    @Test
    fun setChar_overNarrowOverWideCharSecondHalf_splitsIntoSpaceAndChar() {
        val row = newBlankRow(4)
        row.write("中")

        row.setChar(1, 'x'.code, TextStyle.NORMAL)

        assertEquals(' '.code, row.textChars()[0].code)
        assertEquals('x'.code, row.textChars()[1].code)
    }

    @Test
    fun setChar_combiningMark_mergesIntoPreviousCell() {
        val row = newBlankRow(4)
        row.setChar(0, 'e'.code, TextStyle.NORMAL)

        row.setChar(0, 0x0301, TextStyle.NORMAL)

        assertEquals('e'.code, row.textChars()[0].code)
        assertEquals(0x0301, row.textChars()[1].code)
        assertTrue(row.charLength >= 2)
    }

    @Test
    fun isCellBlank_reflectsContentOnly() {
        val row = newBlankRow(4)
        row.write("ab")

        assertFalse(row.isCellBlank(0))
        assertTrue(row.isCellBlank(2))
        assertTrue(row.isCellBlank(-1))
        assertTrue(row.isCellBlank(4))
    }

    @Test
    fun snapToColumn_halfOfWideCharSnapsPerDirection() {
        val row = newBlankRow(4)
        row.write("中")

        assertEquals(0, row.snapToColumn(1, SnapAlign.Start))
        assertEquals(2, row.snapToColumn(1, SnapAlign.End))
        assertEquals(0, row.snapToColumn(0, SnapAlign.Start))
    }

    @Test
    fun markLineWrap_andCopyLineWrapFrom_moveFlagWithContent() {
        val source = newBlankRow(4)
        source.write("aaaa")
        source.markLineWrap()

        val destination = newBlankRow(4)
        destination.copyLineWrapFrom(source)

        assertTrue(source.isLineWrap)
        assertTrue(destination.isLineWrap)
    }

    @Test
    fun clear_resetsContentFlagAndBlankState() {
        val row = newBlankRow(4)
        row.write("abcd")
        row.markLineWrap()

        row.clear(TextStyle.NORMAL)

        assertTrue(row.isLineWrap.not())
        assertTrue(row.isBlank)
        assertEquals("    ", row.text())
    }

    @Test
    fun clear_withColoredBackground_isNotBlank() {
        val style = TextStyle.encode(TextStyle.COLOR_INDEX_FOREGROUND, 1, 0)

        val row = TerminalRow(4, style)

        assertFalse(row.isBlank)
    }

    @Test
    fun setChar_doesNotTouchLineWrapFlag() {
        val row = newBlankRow(4)
        row.markLineWrap()

        row.setChar(0, 'x'.code, TextStyle.NORMAL)

        assertTrue(row.isLineWrap)
    }

    @Test
    fun copyInterval_movesContentAndStylesWithinRow() {
        val row = newBlankRow(8)
        row.write("abcdef")

        row.copyInterval(row, 0, 3, 3)

        assertEquals("abcabc", row.text().substring(0, 6))
    }

    @Test
    fun styleAndExtendedEffect_roundTripThroughSlots() {
        val row = newBlankRow(4)
        val style = TextStyle.encode(
            3,
            4,
            TextStyle.CHARACTER_ATTRIBUTE_BOLD or TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE
        )
        val ext = TextStyle.encodeExtendedEffect(TextStyle.UNDERLINE_STYLE_CURLY, 6)

        row.setChar(2, 'z'.code, style, ext)

        assertEquals(style, row.getStyle(2))
        assertEquals(
            TextStyle.CHARACTER_ATTRIBUTE_BOLD or TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE,
            row.effectAt(2)
        )
        assertEquals(TextStyle.UNDERLINE_STYLE_CURLY, TextStyle.decodeUnderlineStyle(row.extEffectAt(2)))
        assertEquals(6, TextStyle.decodeUnderlineColor(row.extEffectAt(2)))
    }

    @Test
    fun extendedEffect_underlineOff_masksShapeButKeepsColor() {
        val row = newBlankRow(4)
        val noUnderline = TextStyle.NORMAL
        val ext = TextStyle.encodeExtendedEffect(TextStyle.UNDERLINE_STYLE_CURLY, 6)

        row.setChar(0, 'a'.code, noUnderline, ext)

        assertEquals(0, TextStyle.decodeUnderlineStyle(row.extEffectAt(0)))
        assertEquals(6, TextStyle.decodeUnderlineColor(row.extEffectAt(0)))
    }

    @Test
    fun modifyEffect_setClearReverse() {
        val row = newBlankRow(4)

        row.modifyEffect(0, TextStyle.CHARACTER_ATTRIBUTE_BOLD, setOrClear = true, reverse = false)
        assertEquals(TextStyle.CHARACTER_ATTRIBUTE_BOLD, row.effectAt(0))

        row.modifyEffect(0, TextStyle.CHARACTER_ATTRIBUTE_BOLD, setOrClear = false, reverse = false)
        assertEquals(0, row.effectAt(0))

        row.modifyEffect(0, TextStyle.CHARACTER_ATTRIBUTE_BOLD, setOrClear = false, reverse = true)
        assertEquals(TextStyle.CHARACTER_ATTRIBUTE_BOLD, row.effectAt(0))

        row.modifyEffect(0, TextStyle.CHARACTER_ATTRIBUTE_BOLD, setOrClear = false, reverse = true)
        assertEquals(0, row.effectAt(0))
    }

    @Test
    fun sharedRowInstances_areDistinct() {
        assertNotSame(newBlankRow(4), newBlankRow(4))
    }

    @Test
    fun getRowStyle_withoutWrite_returnsDefault() {
        val row = newBlankRow(4)

        assertEquals(TextStyle.NORMAL, row.getStyle(0))
        assertEquals(0L, row.extEffectAt(0))
    }

    private fun TerminalRow.effectAt(column: Int): Int = getRawStyle(column).let { raw ->
        (raw and TextStyle.EFFECT_MASK).toInt()
    }

    private fun TerminalRow.extEffectAt(column: Int): Long = getExtendedEffect(column)
}

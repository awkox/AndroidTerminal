package com.awkoo.libterminal.engine

import com.awkoo.libterminal.engine.buffer.CursorCoord
import com.awkoo.libterminal.text.TextStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 字素簇宽度分配与模式 2027：VS16/VS15 变体、Keycap 序列、肤色修饰、
 * ZWJ 序列、区域旗帜、行末自动换行与重排保持。
 */
class TerminalEmulatorClusterTest {

    private val f = EmulatorFixture()
    private val emulator get() = f.emulator

    @Test
    fun vs16Widening_expandsCellToTwoColumnsAndPreservesSelection() {
        f.feed("\u2764\uFE0Fabc")

        val row = emulator.screen.getRowForRead(0)
        assertEquals(2, row.getCellWidth(0))
        assertEquals(2, row.getCellWidth(1))
        assertEquals(1, row.getCellWidth(2))
        assertEquals(5, emulator.cursorCol)
        assertEquals("\u2764\uFE0F", String(row.copyCellChars(0)))
        assertEquals("\u2764\uFE0F", emulator.getSelectedText(0, 0, 1, 0))
        assertEquals("a", emulator.getSelectedText(2, 0, 2, 0))
    }

    @Test
    fun vs15TextPresentation_remainsOneColumn() {
        f.feed("\u2764\uFE0Eabc")

        val row = emulator.screen.getRowForRead(0)
        assertEquals(1, row.getCellWidth(0))
        assertEquals("\u2764\uFE0E", String(row.copyCellChars(0)))
        assertEquals(4, emulator.cursorCol)
        assertEquals('a', row.getCellFirstCodePoint(1).toChar())
    }

    @Test
    fun keycapSequence_widensToTwoColumns() {
        f.feed("1\uFE0F\u20E3x")

        val row0 = emulator.screen.getRowForRead(0)
        assertEquals(2, row0.getCellWidth(0))
        assertEquals("1\uFE0F\u20E3", String(row0.copyCellChars(0)))
        assertEquals('x', row0.getCellFirstCodePoint(2).toChar())
        assertEquals(3, emulator.cursorCol)

        f.feed("\r\n#\u20E3y")
        val row1 = emulator.screen.getRowForRead(1)
        assertEquals(2, row1.getCellWidth(0))
        assertEquals("#\u20E3", String(row1.copyCellChars(0)))
        assertEquals('y', row1.getCellFirstCodePoint(2).toChar())
    }

    @Test
    fun skinToneZwjAndSubdivisionFlags_allocateTwoColumnsPerCluster() {
        f.feed("👍🏽x")
        val row0 = emulator.screen.getRowForRead(0)
        assertEquals(2, row0.getCellWidth(0))
        assertEquals("👍🏽", String(row0.copyCellChars(0)))
        assertEquals('x', row0.getCellFirstCodePoint(2).toChar())
        assertEquals(3, emulator.cursorCol)

        f.feed("\r\n👨\u200D👩\u200D👧x")
        val row1 = emulator.screen.getRowForRead(1)
        assertEquals(2, row1.getCellWidth(0))
        assertEquals("👨\u200D👩\u200D👧", String(row1.copyCellChars(0)))
        assertEquals('x', row1.getCellFirstCodePoint(2).toChar())

        f.feed("\r\n🏳\uFE0F\u200D🌈x")
        val row2 = emulator.screen.getRowForRead(2)
        assertEquals(2, row2.getCellWidth(0))
        assertEquals("🏳\uFE0F\u200D🌈", String(row2.copyCellChars(0)))
        assertEquals('x', row2.getCellFirstCodePoint(2).toChar())

        f.feed("\r\n🏴\uDB40\uDC67\uDB40\uDC62\uDB40\uDC7Fx")
        val row3 = emulator.screen.getRowForRead(3)
        assertEquals(2, row3.getCellWidth(0))
        assertEquals('x', row3.getCellFirstCodePoint(2).toChar())
    }

    @Test
    fun regionalIndicatorPair_formsTwoColumnFlagAndSplitsThird() {
        f.feed("🇨🇳🇺🇸x")

        val row = emulator.screen.getRowForRead(0)
        assertEquals(2, row.getCellWidth(0))
        assertEquals("🇨🇳", String(row.copyCellChars(0)))
        assertEquals(2, row.getCellWidth(2))
        assertEquals("🇺🇸", String(row.copyCellChars(2)))
        assertEquals('x', row.getCellFirstCodePoint(4).toChar())
        assertEquals(5, emulator.cursorCol)
    }

    @Test
    fun vs16AtLastColumn_withAutoWrap_wrapsWholeClusterToNextLine() {
        f.feed("012345678\u2764\uFE0F")

        assertTrue(emulator.screen.getLineWrap(0))
        assertEquals("012345678", f.rowText(0))
        val row1 = emulator.screen.getRowForRead(1)
        assertEquals(2, row1.getCellWidth(0))
        assertEquals("\u2764\uFE0F", String(row1.copyCellChars(0)))
        assertEquals(1, emulator.cursorRow)
        assertEquals(2, emulator.cursorCol)
    }

    @Test
    fun vs16AtLastColumn_withoutAutoWrap_keepsOneColumnAtEdge() {
        f.feed("\u001b[?7l012345678\u2764\uFE0F")

        assertFalse(emulator.screen.getLineWrap(0))
        val row0 = emulator.screen.getRowForRead(0)
        assertEquals(1, row0.getCellWidth(9))
        assertEquals("\u2764\uFE0F", String(row0.copyCellChars(9)))
        assertEquals(0, emulator.cursorRow)
        assertEquals(9, emulator.cursorCol)
    }

    @Test
    fun vs16AfterCursorMove_doesNotExpandPreviousCell() {
        f.feed("\u2764\u001b[1;5H\uFE0F")

        val row0 = emulator.screen.getRowForRead(0)
        assertEquals(1, row0.getCellWidth(0))
        assertEquals("\u2764", String(row0.copyCellChars(0)))
    }

    @Test
    fun decset2027_queryAndToggle_controlsClusterExpansion() {
        f.clearWritten()
        f.feed("\u001b[?2027\$p")
        assertEquals("\u001b[?2027;1\$y", f.writtenText)

        f.clearWritten()
        f.feed("\u001b[?2027l\u001b[?2027\$p")
        assertEquals("\u001b[?2027;2\$y", f.writtenText)

        f.feed("\u2764\uFE0Fx")
        val row0 = emulator.screen.getRowForRead(0)
        assertEquals(1, row0.getCellWidth(0))
        assertEquals('x', row0.getCellFirstCodePoint(1).toChar())

        f.feed("\r\n\u001b[?2027h\u2764\uFE0Fx")
        val row1 = emulator.screen.getRowForRead(1)
        assertEquals(2, row1.getCellWidth(0))
        assertEquals('x', row1.getCellFirstCodePoint(2).toChar())
    }

    @Test
    fun resizeReflow_preservesAtomicWideClustersAcrossWidthChanges() {
        f.feed("a\u2764\uFE0F👨\u200D👩\u200D👧1\uFE0F\u20E3b")

        emulator.resize(4, 5, 10, 20)
        val tracked = longArrayOf(CursorCoord.pack(emulator.cursorCol, emulator.cursorRow).packed)
        assertEquals(1, emulator.screen.getRowForRead(0).getCellWidth(0))
        assertEquals(2, emulator.screen.getRowForRead(0).getCellWidth(1))
        assertEquals("\u2764\uFE0F", String(emulator.screen.getRowForRead(0).copyCellChars(1)))
        assertEquals(2, emulator.screen.getRowForRead(1).getCellWidth(0))
        assertEquals("👨\u200D👩\u200D👧", String(emulator.screen.getRowForRead(1).copyCellChars(0)))
        assertEquals(2, emulator.screen.getRowForRead(1).getCellWidth(2))
        assertEquals("1\uFE0F\u20E3", String(emulator.screen.getRowForRead(1).copyCellChars(2)))
        assertEquals("b", f.rowText(2))
        assertTrue(tracked[0] != 0L)

        emulator.resize(10, 5, 10, 20)
        val row0 = emulator.screen.getRowForRead(0)
        assertEquals("a", String(row0.copyCellChars(0)))
        assertEquals("\u2764\uFE0F", String(row0.copyCellChars(1)))
        assertEquals("👨\u200D👩\u200D👧", String(row0.copyCellChars(3)))
        assertEquals("1\uFE0F\u20E3", String(row0.copyCellChars(5)))
        assertEquals("b", String(row0.copyCellChars(7)))
        assertEquals(TextStyle.NORMAL, row0.getStyle(0))
    }

    @Test
    fun zeroWidthControlAndFormatChars_breakClusterAndDoNotMerge() {
        f.feed("a\u200B\u0301b\u2060\u0301c\uFEFF\u0301d")
        val row0 = emulator.screen.getRowForRead(0)
        assertEquals("a", String(row0.copyCellChars(0)))
        assertEquals("b", String(row0.copyCellChars(1)))
        assertEquals("c", String(row0.copyCellChars(2)))
        assertEquals("d", String(row0.copyCellChars(3)))
        assertEquals(4, emulator.cursorCol)

        f.feed("\r\n\u0301xy\u001b[2;10H\u0301")
        val row1 = emulator.screen.getRowForRead(1)
        assertEquals("x", String(row1.copyCellChars(0)))
        assertEquals("y", String(row1.copyCellChars(1)))
        assertEquals(" ", String(row1.copyCellChars(8)))
        assertEquals(" ", String(row1.copyCellChars(9)))

        f.feed("\u001b[3;1He\u001b[3;2H\u0301\u001b[3;2H\u007Fz")
        val row2 = emulator.screen.getRowForRead(2)
        assertEquals("e", String(row2.copyCellChars(0)))
        assertEquals("z", String(row2.copyCellChars(1)))
    }

    @Test
    fun zeroWidthControlBreak_alsoBlocksMergeInLegacyMode2027Off() {
        f.feed("\u001b[?2027la\u200B\u0301b\u0301")

        val row0 = emulator.screen.getRowForRead(0)
        assertEquals("a", String(row0.copyCellChars(0)))
        assertEquals("b\u0301", String(row0.copyCellChars(1)))
        assertEquals(2, emulator.cursorCol)
    }

    @Test
    fun invalidZwjSkinToneAndTag_doNotFalselyMerge() {
        f.feed("a\u200D😀\r\n😀🏽x\r\na\uDB40\uDC67b")

        val row0 = emulator.screen.getRowForRead(0)
        assertEquals("a\u200D", String(row0.copyCellChars(0)))
        assertEquals(2, row0.getCellWidth(1))
        assertEquals("😀", String(row0.copyCellChars(1)))

        val row1 = emulator.screen.getRowForRead(1)
        assertEquals("😀", String(row1.copyCellChars(0)))
        assertEquals("🏽", String(row1.copyCellChars(2)))
        assertEquals("x", String(row1.copyCellChars(4)))

        val row2 = emulator.screen.getRowForRead(2)
        assertEquals("a", String(row2.copyCellChars(0)))
        assertEquals("b", String(row2.copyCellChars(1)))
    }

    @Test
    fun hangulJamoAndIndicConjunct_mergeWithinSingleCluster() {
        f.feed("\u1100\u1161\u11A8x")
        val row0 = emulator.screen.getRowForRead(0)
        assertEquals(2, row0.getCellWidth(0))
        assertEquals("\u1100\u1161\u11A8", String(row0.copyCellChars(0)))
        assertEquals("x", String(row0.copyCellChars(2)))
        assertEquals(3, emulator.cursorCol)

        f.feed("\r\n\u0915\u094D\u0937\u092E")
        val row1 = emulator.screen.getRowForRead(1)
        assertEquals(1, row1.getCellWidth(0))
        assertEquals("\u0915\u094D\u0937", String(row1.copyCellChars(0)))
        assertEquals("\u092E", String(row1.copyCellChars(1)))
        assertEquals(2, emulator.cursorCol)
    }

    @Test
    fun dirtyKeycapAndVs16WithCombiningMark_doNotFalselyWiden() {
        f.feed("1\u0301\u20E3x\r\n\u2764\u0301\uFE0Fy")

        val row0 = emulator.screen.getRowForRead(0)
        assertEquals(1, row0.getCellWidth(0))
        assertEquals("1\u0301\u20E3", String(row0.copyCellChars(0)))
        assertEquals("x", String(row0.copyCellChars(1)))

        val row1 = emulator.screen.getRowForRead(1)
        assertEquals(1, row1.getCellWidth(0))
        assertEquals("\u2764\u0301\uFE0F", String(row1.copyCellChars(0)))
        assertEquals("y", String(row1.copyCellChars(1)))
    }

    @Test
    fun spacingMarkAndPrepend_followGb9aAndGb9bInMode2027() {
        f.feed("\u0915\u093Ex\r\n\u0600" + "1y\r\n\u0600\u4E2Dz")

        val row0 = emulator.screen.getRowForRead(0)
        assertEquals(1, row0.getCellWidth(0))
        assertEquals("\u0915\u093E", String(row0.copyCellChars(0)))
        assertEquals("x", String(row0.copyCellChars(1)))

        val row1 = emulator.screen.getRowForRead(1)
        assertEquals(1, row1.getCellWidth(0))
        assertEquals("\u0600" + "1", String(row1.copyCellChars(0)))
        assertEquals("y", String(row1.copyCellChars(1)))

        val row2 = emulator.screen.getRowForRead(2)
        assertEquals(2, row2.getCellWidth(0))
        assertEquals("\u0600\u4E2D", String(row2.copyCellChars(0)))
        assertEquals("z", String(row2.copyCellChars(2)))
    }

    @Test
    fun softHyphen_rendersAsColumnAndBreaksCluster() {
        f.feed("a\u00AD\u0301b")

        val row0 = emulator.screen.getRowForRead(0)
        assertEquals("a", String(row0.copyCellChars(0)))
        assertEquals("\u00AD", String(row0.copyCellChars(1)))
        assertEquals("b", String(row0.copyCellChars(2)))
        assertEquals(3, emulator.cursorCol)
    }

    @Test
    fun cellCodePointCapReached_breaksClusterAndKeepsCursorAndWidthInSync() {
        val zwjChain = buildString {
            append("\uD83D\uDC68")
            repeat(15) { append("\u200D\uD83D\uDC68") }
            append("\u200D")
            append("\uD83D\uDC69")
            append("x")
        }
        f.feed(zwjChain)

        val row0 = emulator.screen.getRowForRead(0)
        assertEquals(32, row0.getCellCodePointCount(0))
        assertEquals(2, row0.getCellWidth(0))
        assertEquals("\uD83D\uDC69", String(row0.copyCellChars(2)))
        assertEquals(2, row0.getCellWidth(2))
        assertEquals("x", String(row0.copyCellChars(4)))
        assertEquals(5, emulator.cursorCol)

        f.feed("\r\na" + "\u0301".repeat(40) + "b")
        val row1 = emulator.screen.getRowForRead(1)
        assertEquals(32, row1.getCellCodePointCount(0))
        assertEquals(1, row1.getCellWidth(0))
        assertEquals("b", String(row1.copyCellChars(1)))
        assertEquals(2, emulator.cursorCol)
    }

    @Test
    fun indicConjunctWithZwnj_breaksBeforeNextConsonant() {
        f.feed("\u0915\u094D\u200C\u0937y")

        val row0 = emulator.screen.getRowForRead(0)
        assertEquals("\u0915\u094D\u200C", String(row0.copyCellChars(0)))
        assertEquals("\u0937", String(row0.copyCellChars(1)))
        assertEquals("y", String(row0.copyCellChars(2)))
        assertEquals(3, emulator.cursorCol)
    }

    @Test
    fun prependChain_handlesMultiplePrependsLastColumnWrapAndInsertMode() {
        f.feed("\u0600\u0600\u4E2Dx")
        val row0 = emulator.screen.getRowForRead(0)
        assertEquals(2, row0.getCellWidth(0))
        assertEquals("\u0600\u0600\u4E2D", String(row0.copyCellChars(0)))
        assertEquals("x", String(row0.copyCellChars(2)))

        f.feed("\u001b[2;1H123456789\u0600\u4E2D")
        assertTrue(emulator.screen.getLineWrap(1))
        val row1 = emulator.screen.getRowForRead(1)
        assertEquals(" ", String(row1.copyCellChars(9)))
        val row2 = emulator.screen.getRowForRead(2)
        assertEquals(2, row2.getCellWidth(0))
        assertEquals("\u0600\u4E2D", String(row2.copyCellChars(0)))
        assertEquals(2, emulator.cursorCol)

        f.feed("\u001b[?7l\u001b[4;1H123456789\u0600\u4E2D")
        assertFalse(emulator.screen.getLineWrap(3))
        val row3 = emulator.screen.getRowForRead(3)
        assertEquals(1, row3.getCellWidth(9))
        assertEquals("\u0600\u4E2D", String(row3.copyCellChars(9)))

        f.feed("\u001b[?7h\u001b[4;1Habc\u001b[4;1H\u001b[4h\u2764\uFE0F")
        assertEquals(2, row3.getCellWidth(0))
        assertEquals("\u2764\uFE0F", String(row3.copyCellChars(0)))
        assertEquals("a", String(row3.copyCellChars(2)))
        assertEquals("b", String(row3.copyCellChars(3)))
    }

    @Test
    fun cellCodePointCap_handlesSpacingMarkPrependMarginsInsertModeScrollAndReflow() {
        f.feed("a" + "\u0301".repeat(31) + "\u093E" + "\u0600" + "\u4E2D")
        val row0 = emulator.screen.getRowForRead(0)
        assertEquals(32, row0.getCellCodePointCount(0))
        assertEquals(1, row0.getCellWidth(0))
        assertEquals("\u093E", String(row0.copyCellChars(1)))
        assertEquals(2, row0.getCellWidth(2))
        assertEquals("\u0600\u4E2D", String(row0.copyCellChars(2)))
        assertEquals(4, emulator.cursorCol)

        val marginFixture = EmulatorFixture(columns = 10, rows = 4)
        marginFixture.feed("\u001b[?69h\u001b[2;6s\u001b[1;6H")
        marginFixture.feed("z" + "\u0301".repeat(35) + "k")
        val mRow0 = marginFixture.emulator.screen.getRowForRead(0)
        val mRow1 = marginFixture.emulator.screen.getRowForRead(1)
        assertEquals(32, mRow0.getCellCodePointCount(5))
        assertEquals("k", String(mRow1.copyCellChars(1)))
        assertEquals(2, marginFixture.emulator.cursorCol)

        val insertFixture = EmulatorFixture(columns = 10, rows = 3)
        insertFixture.feed("TAIL\u001b[1;1H\u001b[4h")
        insertFixture.feed("p" + "\u0301".repeat(31) + "中")
        val iRow0 = insertFixture.emulator.screen.getRowForRead(0)
        assertEquals(32, iRow0.getCellCodePointCount(0))
        assertEquals(2, iRow0.getCellWidth(1))
        assertEquals("中", String(iRow0.copyCellChars(1)))
        assertEquals("T", String(iRow0.copyCellChars(3)))

        val scrollReflowFixture = EmulatorFixture(columns = 8, rows = 3)
        scrollReflowFixture.feed("a" + "\u0301".repeat(31) + "b\r\nc\r\nd\r\ne")
        assertTrue(scrollReflowFixture.emulator.screen.activeTranscriptRows > 0)
        scrollReflowFixture.emulator.resize(4, 3, 10, 20)
        val topTranscriptRow = scrollReflowFixture.emulator.screen.getRowForRead(-scrollReflowFixture.emulator.screen.activeTranscriptRows)
        assertEquals(32, topTranscriptRow.getCellCodePointCount(0))
        assertEquals("b", String(topTranscriptRow.copyCellChars(1)))
    }
}

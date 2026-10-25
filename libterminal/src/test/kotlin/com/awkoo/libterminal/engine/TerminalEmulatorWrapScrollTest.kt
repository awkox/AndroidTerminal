package com.awkoo.libterminal.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自动换行与滚动：软换行标志的建立、DECSET 7 开关、宽字符行末行为、
 * LF 在滚动区底的滚屏、scrollCounter 增量、回滚历史。
 */
class TerminalEmulatorWrapScrollTest {

    private val f = EmulatorFixture()

    private val emulator get() = f.emulator

    @Test
    fun autoWrap_fullRowDefersWrapUntilNextPrintable() {
        f.feed("0123456789")

        assertEquals("0123456789", f.rowText(0))
        assertFalse(emulator.screen.getLineWrap(0))
        assertEquals(0, emulator.cursorRow)
        assertEquals(9, emulator.cursorCol)

        f.feed("x")

        assertEquals(1, emulator.cursorRow)
        assertEquals(1, emulator.cursorCol)
        assertTrue(emulator.screen.getLineWrap(0))
        assertEquals("x", f.rowText(1))
    }

    @Test
    fun autoWrap_disabled_keepsCursorAtLastColumn() {
        f.feed("\u001b[?7l0123456789x")

        assertEquals("012345678x", f.rowText(0))
        assertFalse(emulator.screen.getLineWrap(0))
        assertEquals(0, emulator.cursorRow)
        assertEquals(9, emulator.cursorCol)
    }

    @Test
    fun wideCharAtLastColumn_wrapsImmediately() {
        f.feed("012345678\u4e2d")

        assertEquals(1, emulator.cursorRow)
        assertEquals(2, emulator.cursorCol)
        assertTrue(emulator.screen.getLineWrap(0))
    }

    @Test
    fun wideCharAtLastColumn_withoutAutoWrap_isDropped() {
        f.feed("\u001b[?7l\u001b[1;10H\u4e2d")

        assertEquals(0, emulator.cursorRow)
        assertEquals(9, emulator.cursorCol)
        assertEquals("", f.rowText(1))
    }

    @Test
    fun combiningMark_overPreviousCell_doesNotAdvanceCursor() {
        f.feed("e\u0301")

        assertEquals(1, emulator.cursorCol)
        assertEquals('e'.code, emulator.screen.getRowForRead(0).textChars()[0].code)
        assertEquals(0x0301, emulator.screen.getRowForRead(0).textChars()[1].code)
    }

    @Test
    fun linefeed_onLastRow_scrollsScreenIntoHistory() {
        f.feed("aaaa\r\nbbbb\r\ncccc\r\ndddd\r\neeee\n")

        assertEquals(listOf("bbbb", "cccc", "dddd", "eeee", ""), f.screenText())
        assertEquals(1, emulator.screen.activeTranscriptRows)
        assertEquals("aaaa", f.rowText(-1))
    }

    @Test
    fun linefeed_inMiddle_movesCursorDown() {
        f.feed("\u001b[2;1H\n")

        assertEquals(2, emulator.cursorRow)
        assertEquals(0, emulator.screen.activeTranscriptRows)
    }

    @Test
    fun scrollRegion_linefeedAtBottom_scrollsOnlyRegion() {
        f.feed("0000\r\n1111\r\n2222\r\n3333\r\n4444")
        f.feed("\u001b[2;5r")
        f.feed("\u001b[5;1H\n")

        assertEquals(listOf("0000", "2222", "3333", "4444", ""), f.screenText())
    }

    @Test
    fun scrollUp_scrollsRegionContentUp() {
        f.feed("0000\r\n1111\r\n2222")
        f.feed("\u001b[1;3H\u001b[2S")

        assertEquals(listOf("2222", "", "", "", ""), f.screenText())
    }

    @Test
    fun scrollDown_movesRegionContentDown() {
        f.feed("0000\r\n1111\r\n2222")
        f.feed("\u001b[1;3H\u001b[1T")

        assertEquals(listOf("", "0000", "1111", "2222", ""), f.screenText())
    }

    @Test
    fun reverseIndex_atTopMargin_scrollsDown() {
        f.feed("0000\r\n1111\r\n2222")
        f.feed("\u001b[1;1H\u001bM")

        assertEquals(listOf("", "0000", "1111", "2222", ""), f.screenText())
    }

    @Test
    fun reverseIndex_belowTopMovesCursorUp() {
        f.feed("\u001b[3;1H\u001bM")

        assertEquals(1, emulator.cursorRow)
    }

    @Test
    fun scrollCounter_accumulatesAndIsDrained() {
        f.feed("aaaa\r\nbbbb\r\ncccc\r\ndddd\r\neeee\n\n")

        assertEquals(2, emulator.takeScrollCounter())
        assertEquals(0, emulator.takeScrollCounter())
    }

    @Test
    fun scrollCounter_doesNotGrowOnCursorMoves() {
        f.feed("\u001b[3;3H\u001b[2A\u001b[2B")

        assertEquals(0, emulator.takeScrollCounter())
    }

    @Test
    fun wideChar_wrapsAcrossLine_secondCharLandsOnNextRow() {
        f.feed("01234567\u4e2d\u4e2d")

        assertEquals("01234567\u4e2d", f.rowText(0))
        assertEquals("\u4e2d", f.rowText(1))
    }

    @Test
    fun autoWrap_reenabledAfterDecset() {
        f.feed("\u001b[?7l")
        f.feed("\u001b[?7h0123456789x")

        assertEquals("x", f.rowText(1))
    }

    @Test
    fun transcriptScroll_keepsSoftWrapFlagOnHistoryRow() {
        f.feed("0123456789x")
        assertTrue(emulator.screen.getLineWrap(0))

        f.feed("\r\n\r\n\r\n\r\n")

        assertEquals(1, emulator.screen.activeTranscriptRows)
        assertTrue(emulator.screen.getLineWrap(-1))
    }
}

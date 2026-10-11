package com.awkoo.libterminal.engine

import com.awkoo.libterminal.engine.buffer.TerminalBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DECSLRM 收窄右边距下的光标列域不变量：Tab 落点与自动换行判定
 * 共享同一条排他右边界，越界光标按屏宽续写。
 */
class TerminalEmulatorMarginCursorTest {

    private fun newEmulator(): TerminalEmulator = TerminalEmulator({}, {}, maxTranscriptRows = 100)

    private fun TerminalEmulator.feed(sequence: String) {
        val bytes = sequence.toByteArray()
        append(bytes, bytes.size)
    }

    private fun TerminalEmulator.csi(suffix: String) {
        feed(esc() + "[" + suffix)
    }

    private fun TerminalEmulator.narrowTo(columnCount: Int) {
        csi("?69h")
        csi("1;" + columnCount + "s")
    }

    private fun TerminalEmulator.clearTabStops() {
        csi("3g")
    }

    private fun TerminalEmulator.tabStopAt(column: Int) {
        csi("1;" + (column + 1) + "H")
        feed(esc() + "H")
    }

    private fun TerminalEmulator.moveCursorTo(column: Int) {
        csi("1;" + (column + 1) + "H")
    }

    private fun TerminalBuffer.cell(row: Int, column: Int): Char {
        val chars = getRowForRead(row).textChars()
        return if (column < chars.size) chars[column] else ' '
    }

    @Test
    fun fullWidthTabAndAutoWrap_regression() {
        val emulator = newEmulator()
        emulator.clearTabStops()
        emulator.tabStopAt(30)
        emulator.moveCursorTo(9)
        emulator.feed(tab())
        assertEquals(30, emulator.cursorCol)

        emulator.csi("1;80H")
        emulator.feed("A")
        assertEquals(79, emulator.cursorCol)

        emulator.feed("B")
        assertEquals(1, emulator.cursorRow)
        assertEquals(1, emulator.cursorCol)
        assertTrue(emulator.screen.getLineWrap(0))
        assertEquals('B', emulator.screen.cell(1, 0))
    }

    @Test
    fun tabStopBeyondNarrowedMargin_landsInsideTheMargin() {
        val emulator = newEmulator()
        emulator.narrowTo(20)
        emulator.clearTabStops()
        emulator.tabStopAt(25)
        emulator.moveCursorTo(9)
        emulator.feed(tab())

        assertEquals(19, emulator.cursorCol)
    }

    @Test
    fun tabStopInsideNarrowedMargin_landsExactly() {
        val emulator = newEmulator()
        emulator.narrowTo(20)
        emulator.clearTabStops()
        emulator.tabStopAt(16)
        emulator.moveCursorTo(9)
        emulator.feed(tab())

        assertEquals(16, emulator.cursorCol)
    }

    @Test
    fun tabStopBeyondNarrowedMargin_isNotReachedWhenTheCursorCrossesIt() {
        val emulator = newEmulator()
        emulator.narrowTo(20)
        emulator.clearTabStops()
        emulator.tabStopAt(25)
        emulator.moveCursorTo(17)
        emulator.feed(tab())

        assertEquals(19, emulator.cursorCol)
    }

    @Test
    fun cursorBeyondNarrowedMargin_wrapsAtScreenWidth() {
        val emulator = newEmulator()
        emulator.narrowTo(20)
        emulator.moveCursorTo(79)
        emulator.feed("A")
        assertEquals(79, emulator.cursorCol)

        emulator.feed("B")
        assertEquals(1, emulator.cursorRow)
        assertEquals(1, emulator.cursorCol)
        assertTrue(emulator.screen.getLineWrap(0))
        assertEquals('B', emulator.screen.cell(1, 0))
    }

    @Test
    fun cursorForwardFromBeyondNarrowedMargin_keepsAdvancing() {
        val emulator = newEmulator()
        emulator.narrowTo(20)
        emulator.moveCursorTo(30)
        emulator.csi("5C")

        assertEquals(35, emulator.cursorCol)
    }

    @Test
    fun originModeClamp_lockedBehaviour() {
        val emulator = newEmulator()
        emulator.narrowTo(20)
        emulator.moveCursorTo(30)
        assertEquals(30, emulator.cursorCol)

        emulator.csi("?6h")
        emulator.moveCursorTo(30)
        assertEquals(19, emulator.cursorCol)
    }

    private fun esc(): String = String(charArrayOf(27.toChar()))

    private fun tab(): String = String(charArrayOf(9.toChar()))
}

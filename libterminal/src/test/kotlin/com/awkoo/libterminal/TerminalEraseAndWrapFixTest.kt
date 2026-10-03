package com.awkoo.libterminal

import com.awkoo.libterminal.engine.TerminalEmulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalEraseAndWrapFixTest {

    private val esc = 27.toChar()
    private val cr = 13.toChar()
    private val lf = 10.toChar()
    private val bs = 8.toChar()

    private fun newEmulator(): TerminalEmulator = TerminalEmulator({}, {})

    private fun TerminalEmulator.feed(text: String) {
        val bytes = text.encodeToByteArray()
        append(bytes, bytes.size)
    }

    private fun TerminalEmulator.csi(params: String) = feed("$esc[$params")

    @Test
    fun edMode1AboveScrollRegionErasesCursorRowOnly() {
        val emulator = newEmulator()
        emulator.feed("AAAA${cr}${lf}BBBB${cr}${lf}CCCC")
        emulator.csi("10;20r")
        emulator.csi("1J")

        assertTrue(emulator.screen.isCellBlank(0, 0))
        assertFalse(emulator.screen.isCellBlank(1, 0))
        assertFalse(emulator.screen.isCellBlank(0, 1))
    }

    @Test
    fun edMode0BelowScrollRegionKeepsRowsAboveCursor() {
        val emulator = newEmulator()
        emulator.feed("AAAA")
        emulator.csi("10;20r")
        emulator.csi("23H")
        emulator.csi("J")

        assertFalse(emulator.screen.isCellBlank(0, 0))
    }

    @Test
    fun fullScreenEraseClearsLineWrapFlag() {
        val emulator = newEmulator()
        emulator.feed("x".repeat(81))
        assertTrue(emulator.screen.getLineWrap(0))

        emulator.csi("2J")
        assertFalse(emulator.screen.getLineWrap(0))
    }

    @Test
    fun insertLinesMovesLineWrapFlagWithContent() {
        val emulator = newEmulator()
        emulator.feed("x".repeat(81))
        emulator.csi("H")
        emulator.csi("2L")

        assertFalse(emulator.screen.getLineWrap(0))
        assertTrue(emulator.screen.getLineWrap(2))
    }

    @Test
    fun backspaceAtLeftEdgeStaysInPlace() {
        val emulator = newEmulator()
        emulator.feed("x".repeat(81))
        emulator.feed(bs.toString())
        emulator.feed(bs.toString())

        assertEquals(1, emulator.cursorRow)
        assertEquals(0, emulator.cursorCol)
    }
}

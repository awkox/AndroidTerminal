package com.awkoo.libterminal.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 擦除与编辑：EL/ED/ECH/ICH/DCH/IL/DL 与插入模式（IRM）。
 */
class TerminalEmulatorEraseEditTest {

    private val f = EmulatorFixture()

    private val emulator get() = f.emulator

    private fun fillRow(row: Int, text: String) {
        f.feed("\u001b[${row + 1};1H$text")
    }

    @Test
    fun eraseLineFromCursor_toEndOfLine() {
        fillRow(0, "abcdefghij")
        f.feed("\u001b[1;5H\u001b[K")

        assertEquals("abcd", f.rowText(0))
    }

    @Test
    fun eraseLineFromStart_throughCursor() {
        fillRow(0, "abcdefghij")
        f.feed("\u001b[1;5H\u001b[1K")

        assertEquals("     fghij", f.rowText(0))
    }

    @Test
    fun eraseWholeLine() {
        fillRow(0, "abcdefghij")
        f.feed("\u001b[2K")

        assertEquals("", f.rowText(0))
    }

    @Test
    fun eraseDisplayFromCursor_spansRestOfScreen() {
        fillRow(0, "abcdefghij")
        fillRow(1, "klmnopqrst")
        f.feed("\u001b[1;3H\u001b[J")

        assertEquals("ab", f.rowText(0))
        assertEquals("", f.rowText(1))
    }

    @Test
    fun eraseDisplayThroughCursor() {
        fillRow(0, "abcdefghij")
        fillRow(1, "klmnopqrst")
        f.feed("\u001b[2;3H\u001b[1J")

        assertEquals("", f.rowText(0))
        assertEquals("   nopqrst", f.rowText(1))
    }

    @Test
    fun eraseWholeDisplay() {
        fillRow(0, "abcdefghij")
        fillRow(1, "klmnopqrst")

        f.feed("\u001b[2J")

        assertEquals("", f.rowText(0))
        assertEquals("", f.rowText(1))
    }

    @Test
    fun eraseCharacters_clearsGivenCellCount() {
        fillRow(0, "abcdefghij")
        f.feed("\u001b[1;3H\u001b[3X")

        assertEquals("ab   fghij", f.rowText(0))
    }

    @Test
    fun insertCharacters_shiftsRightWithinLine() {
        fillRow(0, "abcdefghij")
        f.feed("\u001b[1;3H\u001b[2@")

        assertEquals("ab  cdefgh", f.rowText(0))
    }

    @Test
    fun deleteCharacters_shiftsLeftWithinLine() {
        fillRow(0, "abcdefghij")
        f.feed("\u001b[1;3H\u001b[2P")

        assertEquals("abefghij", f.rowText(0))
    }

    @Test
    fun insertLines_pushesRowsDownWithinRegion() {
        fillRow(0, "aaaa")
        fillRow(1, "bbbb")
        fillRow(2, "cccc")
        f.feed("\u001b[2;1H\u001b[1L")

        assertEquals(listOf("aaaa", "", "bbbb", "cccc", ""), f.screenText())
    }

    @Test
    fun deleteLines_pullsRowsUpWithinRegion() {
        fillRow(0, "aaaa")
        fillRow(1, "bbbb")
        fillRow(2, "cccc")
        f.feed("\u001b[2;1H\u001b[1M")

        assertEquals(listOf("aaaa", "cccc", "", "", ""), f.screenText())
    }

    @Test
    fun insertMode_shiftsExistingContentRight() {
        fillRow(0, "abc")
        f.feed("\u001b[4h\u001b[1;1HX")

        assertEquals("Xabc", f.rowText(0))
    }

    @Test
    fun insertMode_offOverwritesInPlace() {
        fillRow(0, "abc")
        f.feed("\u001b[1;1HX")

        assertEquals("Xbc", f.rowText(0))
    }

    @Test
    fun repeat_lastPrintedCharacter() {
        f.feed("A\u001b[3b")

        assertEquals("AAAA", f.rowText(0))
    }

    @Test
    fun repeat_withoutPriorCharacter_isNoOp() {
        f.feed("\u001b[3b")

        assertEquals("", f.rowText(0))
    }

    @Test
    fun erase_keepsCursorPosition() {
        fillRow(0, "abcdefghij")
        f.feed("\u001b[1;5H\u001b[2K")

        assertEquals(4, emulator.cursorCol)
        assertEquals(0, emulator.cursorRow)
    }
}

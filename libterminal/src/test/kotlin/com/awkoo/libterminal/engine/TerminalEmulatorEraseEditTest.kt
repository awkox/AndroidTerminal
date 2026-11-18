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

    @Test
    fun insertDeleteLines_respectLeftRightMargins_homeCol_andNoOpOutside() {
        fillRow(0, "0000000000")
        fillRow(1, "11aaaa1111")
        fillRow(2, "22bbbb2222")
        fillRow(3, "33cccc3333")
        fillRow(4, "4444444444")

        f.feed("\u001b[2;4r\u001b[?69h\u001b[3;6s")
        f.feed("\u001b[2;5H\u001b[1L")

        assertEquals(2, emulator.cursorCol)
        assertEquals(
            listOf("0000000000", "11    1111", "22aaaa2222", "33bbbb3333", "4444444444"),
            f.screenText()
        )

        f.feed("\u001b[2;5H\u001b[1M")
        assertEquals(2, emulator.cursorCol)
        assertEquals(
            listOf("0000000000", "11aaaa1111", "22bbbb2222", "33    3333", "4444444444"),
            f.screenText()
        )

        f.feed("\u001b[1;4H\u001b[1L")
        f.feed("\u001b[2;1H\u001b[1M")
        assertEquals(
            listOf("0000000000", "11aaaa1111", "22bbbb2222", "33    3333", "4444444444"),
            f.screenText()
        )
    }

    @Test
    fun insertDeleteChars_respectRightMargin_andNoOpOutside() {
        fillRow(0, "abcdefghij")
        f.feed("\u001b[?69h\u001b[3;7s")

        f.feed("\u001b[1;4H\u001b[2@")
        assertEquals("abc  dehij", f.rowText(0))

        f.feed("\u001b[1;4H\u001b[1P")
        assertEquals("abc de hij", f.rowText(0))

        f.feed("\u001b[1;2H\u001b[2@\u001b[1;9H\u001b[2P")
        assertEquals("abc de hij", f.rowText(0))
    }

    @Test
    fun decicAndDecdc_respectTopBottomMargins_andNoOpOutside() {
        fillRow(0, "0123456789")
        fillRow(1, "abcdefghij")
        fillRow(2, "klmnopqrst")
        fillRow(3, "uvwxyzABCD")

        f.feed("\u001b[2;3r\u001b[?69h\u001b[2;7s")
        f.feed("\u001b[2;4H\u001b[2'}")

        assertEquals(
            listOf("0123456789", "abc  dehij", "klm  norst", "uvwxyzABCD", ""),
            f.screenText()
        )

        f.feed("\u001b[3;4H\u001b[2'~")
        assertEquals(
            listOf("0123456789", "abcde  hij", "klmno  rst", "uvwxyzABCD", ""),
            f.screenText()
        )

        f.feed("\u001b[1;4H\u001b[1'}\u001b[2;1H\u001b[1'~")
        assertEquals(
            listOf("0123456789", "abcde  hij", "klmno  rst", "uvwxyzABCD", ""),
            f.screenText()
        )
    }
}

package com.awkoo.libterminal.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 光标移动与制表位：CUP/HVP、CUU/CUD/CUF/CUB、CHA/VPA、CNL/CPL、
 * CR/LF/BS、HT/HTS/TBC/CBT、DECSTBM 滚动区与原点模式下的夹取。
 */
class TerminalEmulatorCursorTest {

    private val f = EmulatorFixture()

    private val emulator get() = f.emulator

    @Test
    fun cup_movesToRowAndColumn() {
        f.feed("\u001b[3;4H")

        assertEquals(2, emulator.cursorRow)
        assertEquals(3, emulator.cursorCol)
    }

    @Test
    fun cup_defaultsToOneBasedHome() {
        f.feed("\u001b[H")

        assertEquals(0, emulator.cursorRow)
        assertEquals(0, emulator.cursorCol)
    }

    @Test
    fun cup_missingColumn_defaultsToOne() {
        f.feed("\u001b[3H")

        assertEquals(2, emulator.cursorRow)
        assertEquals(0, emulator.cursorCol)
    }

    @Test
    fun cup_outOfRange_clampsToScreen() {
        f.feed("\u001b[99;99H")

        assertEquals(4, emulator.cursorRow)
        assertEquals(9, emulator.cursorCol)
    }

    @Test
    fun cursorUp_clampsAtTop() {
        f.feed("\u001b[3;1H\u001b[1A")

        assertEquals(1, emulator.cursorRow)

        f.feed("\u001b[99A")

        assertEquals(0, emulator.cursorRow)
    }

    @Test
    fun cursorDown_clampsAtBottom() {
        f.feed("\u001b[99B")

        assertEquals(4, emulator.cursorRow)
    }

    @Test
    fun cursorForward_clampsAtRightEdge() {
        f.feed("\u001b[3C")

        assertEquals(3, emulator.cursorCol)

        f.feed("\u001b[99C")

        assertEquals(9, emulator.cursorCol)
    }

    @Test
    fun cursorBack_clampsAtLeftEdge() {
        f.feed("\u001b[4;5H\u001b[2D")

        assertEquals(2, emulator.cursorCol)

        f.feed("\u001b[99D")

        assertEquals(0, emulator.cursorCol)
    }

    @Test
    fun cha_setsAbsoluteColumn() {
        f.feed("\u001b[6G")

        assertEquals(5, emulator.cursorCol)
    }

    @Test
    fun vpa_setsAbsoluteRow() {
        f.feed("\u001b[3d")

        assertEquals(2, emulator.cursorRow)
    }

    @Test
    fun cnlMovesToColumnOne() {
        f.feed("\u001b[3;8H\u001b[2E")

        assertEquals(4, emulator.cursorRow)
        assertEquals(0, emulator.cursorCol)
    }

    @Test
    fun cplMovesToColumnOne() {
        f.feed("\u001b[4;1H\u001b[2F")

        assertEquals(1, emulator.cursorRow)
        assertEquals(0, emulator.cursorCol)
    }

    @Test
    fun carriageReturn_andLinefeed_moveIndependently() {
        f.feed("ab\r")

        assertEquals(0, emulator.cursorCol)

        f.feed("\n")

        assertEquals(1, emulator.cursorRow)
    }

    @Test
    fun backspace_atLeftEdge_staysPut() {
        f.feed("\u0008")

        assertEquals(0, emulator.cursorCol)
    }

    @Test
    fun backspace_movesLeftWithinLine() {
        f.feed("ab\u0008")

        assertEquals(1, emulator.cursorCol)
    }

    @Test
    fun horizontalTab_stopsAtDefaultStops() {
        f.feed("\t")

        assertEquals(8, emulator.cursorCol)

        f.feed("\t")

        assertEquals(9, emulator.cursorCol)
    }

    @Test
    fun tabForward_withArg_multipleStops() {
        f.feed("\u001b[2I")

        assertEquals(9, emulator.cursorCol)
    }

    @Test
    fun tabBackward_movesToPreviousStop() {
        f.feed("\u001b[9C\u001b[Z")

        assertEquals(8, emulator.cursorCol)
    }

    @Test
    fun tabBackward_withoutMoreStops_keepsColumn() {
        f.feed("\u001b[9C\u001b[2Z")

        assertEquals(9, emulator.cursorCol)
    }

    @Test
    fun tabBackward_atColumnZero_staysPut() {
        f.feed("\u001b[2Z")

        assertEquals(0, emulator.cursorCol)
    }

    @Test
    fun setTabStop_customStopIsUsedByTab() {
        f.feed("\u001b[3G\u001bH\u001b[2;1H\t")

        assertEquals(2, emulator.cursorCol)
    }

    @Test
    fun clearTabStop_removesCustomStop() {
        f.feed("\u001b[3G\u001bH\u001b[g\u001b[2;1H\t")

        assertEquals(8, emulator.cursorCol)
    }

    @Test
    fun clearAllTabStops_tabStopsAtRightEdge() {
        f.feed("\u001b[3g\t")

        assertEquals(9, emulator.cursorCol)
    }

    @Test
    fun scrollRegion_constrainsCursorDownInNormalMode() {
        f.feed("\u001b[2;5r\u001b[99B")

        assertEquals(4, emulator.cursorRow)
    }

    @Test
    fun scrollRegion_resetRestoresFullScreen() {
        f.feed("\u001b[2;5r\u001b[r\u001b[H\u001b[99B")

        assertEquals(4, emulator.cursorRow)
    }

    @Test
    fun originMode_positionsRelativeToRegion() {
        f.feed("\u001b[3;6r\u001b[?6h")

        f.feed("\u001b[H")

        assertEquals(2, emulator.cursorRow)
        assertEquals(0, emulator.cursorCol)

        f.feed("\u001b[99;99H")

        assertEquals(4, emulator.cursorRow)
        assertEquals(9, emulator.cursorCol)
    }

    @Test
    fun originMode_cursorUp_stopsAtRegionTop() {
        f.feed("\u001b[3;6r\u001b[?6h\u001b[99A")

        assertEquals(2, emulator.cursorRow)
    }

    @Test
    fun decstbm_homesCursorWithoutOriginMode() {
        f.feed("\u001b[3;4H\u001b[2;5r")

        assertEquals(0, emulator.cursorRow)
        assertEquals(0, emulator.cursorCol)
    }

    @Test
    fun reset_returnsCursorHome() {
        f.feed("\u001b[3;4H\u001bc")

        assertEquals(0, emulator.cursorRow)
        assertEquals(0, emulator.cursorCol)
    }
}

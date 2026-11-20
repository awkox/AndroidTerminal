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
    fun tabBackward_withoutMoreStops_movesToLeftEdge() {
        f.feed("\u001b[9C\u001b[2Z")

        assertEquals(0, emulator.cursorCol)
    }

    @Test
    fun tabBackward_withLeftMargin_respectsMarginAndIgnoresOuterStops() {
        f.feed("\u001b[3G\u001bH")
        f.feed("\u001b[?69h\u001b[4;10s")
        f.feed("\u001b[1;10H\u001b[2Z")

        assertEquals(3, emulator.cursorCol)
    }

    @Test
    fun tabForward_pastRightMargin_doesNotJumpBackwards() {
        f.feed("\u001b[?69h\u001b[1;6s")
        f.feed("\u001b[1;8H\t")

        assertEquals(8, emulator.cursorCol)

        f.feed("\t")
        assertEquals(9, emulator.cursorCol)
    }

    @Test
    fun cubAndCr_leftOfLeftMargin_doNotJumpRightToMargin() {
        f.feed("\u001b[?69h\u001b[5;9s")
        f.feed("\u001b[1;3H\u001b[1D")
        assertEquals(1, emulator.cursorCol)

        f.feed("\r")
        assertEquals(0, emulator.cursorCol)
    }

    @Test
    fun decstbmAndDecslrm_invalidOrSingleCellRegions_areIgnored() {
        f.feed("\u001b[2;4r\u001b[?69h\u001b[3;8s")
        f.feed("\u001b[3;5H")

        f.feed("\u001b[4;4r")
        assertEquals(2, emulator.cursorRow)
        assertEquals(4, emulator.cursorCol)

        f.feed("\u001b[5;2r")
        assertEquals(2, emulator.cursorRow)
        assertEquals(4, emulator.cursorCol)

        f.feed("\u001b[99r")
        assertEquals(2, emulator.cursorRow)
        assertEquals(4, emulator.cursorCol)

        f.feed("\u001b[5;5s")
        assertEquals(2, emulator.cursorRow)
        assertEquals(4, emulator.cursorCol)

        f.feed("\u001b[6;3s")
        assertEquals(2, emulator.cursorRow)
        assertEquals(4, emulator.cursorCol)

        val singleRow = EmulatorFixture(columns = 10, rows = 1)
        singleRow.feed("\u001b[r")
        assertEquals(0, singleRow.emulator.cursorRow)
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
    fun resize_resetsOriginMode() {
        f.feed("\u001b[?6h\u001b[2;5r")
        emulator.resize(8, 5, 8, 20)

        f.clearWritten()
        f.feed("\u001b[?6\$p")

        assertEquals("\u001b[?6;2\$y", f.writtenText)
    }

    @Test
    fun resize_resetsScrollRegionOnWidthChange() {
        f.feed("\u001b[2;4r")
        emulator.resize(8, 5, 8, 20)
        f.feed("\u001b[5;1H\n")

        // 滚动区已整体复位：底行换行走全屏滚动、离屏行进历史
        assertEquals(1, emulator.screen.activeTranscriptRows)
    }

    @Test
    fun reset_returnsCursorHome() {
        f.feed("\u001b[3;4H\u001bc")

        assertEquals(0, emulator.cursorRow)
        assertEquals(0, emulator.cursorCol)
    }

    @Test
    fun originMode_hpaAndCha_preserveRowAndRespectLeftRightMargins() {
        f.feed("\u001b[2;5r\u001b[?69h\u001b[3;8s\u001b[?6h")
        f.feed("\u001b[2;2H")
        assertEquals(2, emulator.cursorRow)
        assertEquals(3, emulator.cursorCol)

        f.feed("\u001b[4`")
        assertEquals(2, emulator.cursorRow)
        assertEquals(5, emulator.cursorCol)

        f.feed("\u001b[99`")
        assertEquals(2, emulator.cursorRow)
        assertEquals(7, emulator.cursorCol)

        f.feed("\u001b[2G")
        assertEquals(2, emulator.cursorRow)
        assertEquals(3, emulator.cursorCol)

        f.feed("\u001b[99G")
        assertEquals(2, emulator.cursorRow)
        assertEquals(7, emulator.cursorCol)
    }

    @Test
    fun originMode_vpaAndVpr_preserveColAndRespectTopBottomMargins() {
        f.feed("\u001b[2;5r\u001b[?69h\u001b[3;8s\u001b[?6h")
        f.feed("\u001b[1;3H")
        assertEquals(1, emulator.cursorRow)
        assertEquals(4, emulator.cursorCol)

        f.feed("\u001b[2d")
        assertEquals(2, emulator.cursorRow)
        assertEquals(4, emulator.cursorCol)

        f.feed("\u001b[99d")
        assertEquals(4, emulator.cursorRow)
        assertEquals(4, emulator.cursorCol)

        f.feed("\u001b[1;3H\u001b[2e")
        assertEquals(3, emulator.cursorRow)
        assertEquals(4, emulator.cursorCol)

        f.feed("\u001b[99e")
        assertEquals(4, emulator.cursorRow)
        assertEquals(4, emulator.cursorCol)
    }

    @Test
    fun originMode_resetHomesCursor() {
        f.feed("\u001b[2;5r\u001b[?6h")
        f.feed("\u001b[3;4H")
        f.feed("\u001b[?6l")

        assertEquals(0, emulator.cursorRow)
        assertEquals(0, emulator.cursorCol)
    }

    @Test
    fun indexAndNextLine_moveAndScrollWithinMargins() {
        f.feed("00000000\r\n11111111\r\n22222222\r\n33333333\r\n44444444")
        f.feed("\u001b[2;4r\u001b[?69h\u001b[2;6s")
        f.feed("\u001b[3;4H\u001bD")
        assertEquals(3, emulator.cursorRow)
        assertEquals(3, emulator.cursorCol)

        // IND 在底边距触发边距区内滚动
        f.feed("\u001bD")
        assertEquals(3, emulator.cursorRow)
        assertEquals("00000000", f.rowText(0))
        assertEquals("12222211", f.rowText(1))
        assertEquals("23333322", f.rowText(2))
        assertEquals("3     33", f.rowText(3))

        // NEL 归位到左边距并换行
        f.feed("\u001b[2;5H\u001bE")
        assertEquals(2, emulator.cursorRow)
        assertEquals(1, emulator.cursorCol)
    }

    @Test
    fun decbiAndDecfi_stepOrShiftColumnsAtMargins() {
        f.feed("0123456789\r\nabcdefghij\r\nklmnopqrst")
        f.feed("\u001b[2;3r\u001b[?69h\u001b[3;7s")
        f.feed("\u001b[2;4H\u001b6")
        assertEquals(2, emulator.cursorCol)

        // 左边距处 ESC 6 (DECBI) 在上下边距行内右移插入一列
        f.feed("\u001b6")
        assertEquals(2, emulator.cursorCol)
        assertEquals("0123456789", f.rowText(0))
        assertEquals("ab cdefhij", f.rowText(1))
        assertEquals("kl mnoprst", f.rowText(2))

        // 右边距处 ESC 9 (DECFI) 从左边距删除一列并左移边距内内容
        f.feed("\u001b[2;7H\u001b9")
        assertEquals(6, emulator.cursorCol)
        assertEquals("abcdef hij", f.rowText(1))
        assertEquals("klmnop rst", f.rowText(2))
    }

    @Test
    fun originMode_cnlAndCpl_alignToLeftMargin() {
        f.feed("\u001b[2;5r\u001b[?69h\u001b[3;8s\u001b[?6h")
        f.feed("\u001b[1;5H\u001b[2E")
        assertEquals(3, emulator.cursorRow)
        assertEquals(2, emulator.cursorCol)

        f.feed("\u001b[1F")
        assertEquals(2, emulator.cursorRow)
        assertEquals(2, emulator.cursorCol)
    }
}

package com.awkoo.libterminal.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 保存的光标跨几何变更：尺寸变化只重映射当前光标时，保存的光标会滞留在旧几何行号，
 * 恢复点与内容错位（提示符偏高）。保存的光标必须与内容走同一重映射。
 */
class TerminalEmulatorSavedCursorTest {

    @Test
    fun altExit_afterShrinkDuringAlt_restoresCursorAtContentRow() {
        val f = EmulatorFixture(columns = 10, rows = 6)
        f.feed("aaaa\r\nbbbb\r\ncccc\r\ndddd\r\neeee\r\nffff")

        f.feed("\u001b[?1049h")
        f.emulator.resize(10, 4, 10, 20)
        f.feed("\u001b[?1049l")

        // 旧第 6 行（命令行）随收缩落到第 4 行（新屏底部），恢复的光标须与之同址
        assertEquals(3, f.emulator.cursorRow)
        assertEquals(4, f.emulator.cursorCol)
    }

    @Test
    fun savedCursor_acrossShrink_restoresMappedRow() {
        val f = EmulatorFixture(columns = 10, rows = 6)
        f.feed("aaaa\r\nbbbb\r\ncccc\r\ndddd\r\neeee\r\nffff")

        f.feed("\u001b[5;5H\u001b7")
        f.emulator.resize(10, 4, 10, 20)
        f.feed("\u001b8")

        // 旧第 5 行内容重排后落至第 3 行，DECRC 恢复的是内容行而非旧行号
        assertEquals(2, f.emulator.cursorRow)
        assertEquals(4, f.emulator.cursorCol)
    }

    @Test
    fun altExit_afterColumnShrink_restoresCursorWithReflowedContent() {
        val f = EmulatorFixture(columns = 10, rows = 4)
        f.feed("abcde")

        f.feed("\u001b[?1049h")
        f.emulator.resize(4, 4, 10, 20)
        f.feed("\u001b[?1049l")

        // "abcde" 在 4 列下重排为 "abcd" + "e"，行尾光标跟随内容落到 (1, 1)
        assertEquals(1, f.emulator.cursorRow)
        assertEquals(1, f.emulator.cursorCol)
    }

    @Test
    fun savedCursor_belowBlankTail_staysContentAnchoredAcrossShrink() {
        val f = EmulatorFixture(columns = 10, rows = 6)
        f.feed("aaa\r\nbbb\r\n\r\nddd\r\neee")

        f.feed("\u001b[4;2H\u001b7")
        f.feed("\u001b[2;2H")
        f.emulator.resize(10, 4, 10, 20)
        f.feed("\u001b8")

        // 保存光标在第 4 行（其下有空白尾）：空白扣减不得舍弃该行，
        // 恢复的是随收缩平移后的第 3 行，而非被 setter 夹取到的第 4 行
        assertEquals(2, f.emulator.cursorRow)
        assertEquals(1, f.emulator.cursorCol)
    }

    @Test
    fun altExit_withoutResize_restoresSavedCursorUnchanged() {
        val f = EmulatorFixture(columns = 10, rows = 6)
        f.feed("\u001b[2;3H")

        f.feed("\u001b[?1049h")
        f.feed("\u001b[?1049l")

        assertEquals(1, f.emulator.cursorRow)
        assertEquals(2, f.emulator.cursorCol)
    }
}

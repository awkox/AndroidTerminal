package com.awkoo.libterminal.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * VT400 矩形区域与字符保护协议：DECSCA、DECSED/DECSEL、
 * DECFRA/DECERA/DECSERA/DECCRA、DECCARA/DECRARA 及 DECSACE。
 */
class TerminalEmulatorRectAndProtectTest {

    private val f = EmulatorFixture()

    private val emulator get() = f.emulator

    private fun seedRows() {
        f.feed("0123456789\r\n")
        f.feed("abcdefghij\r\n")
        f.feed("klmnopqrst\r\n")
        f.feed("uvwxyzABCD\r\n")
        f.feed("EFGHIJKLMN")
    }

    @Test
    fun decsca_protectsCellsFromDecsedAndDecsel_butNotStandardErases() {
        f.feed("ab\u001b[1\"qcd\u001b[2\"qef")
        assertFalse(emulator.screen.getStyleAt(0, 1).isProtected)
        assertTrue(emulator.screen.getStyleAt(0, 2).isProtected)
        assertTrue(emulator.screen.getStyleAt(0, 3).isProtected)
        assertFalse(emulator.screen.getStyleAt(0, 4).isProtected)

        // DECSEL (CSI ? 2 K) 保留受保护字符
        f.feed("\u001b[1;1H\u001b[?2K")
        assertEquals("  cd", f.rowText(0))

        // DECSED (CSI ? 2 J) 跨行保留受保护字符，同时擦除未受保护字符
        f.feed("\u001b[2;1Hxy\u001b[1\"qzw")
        f.feed("\u001b[?2J")
        assertEquals("  cd", f.rowText(0))
        assertEquals("  zw", f.rowText(1))

        // 标准 ED/EL (CSI 2 J) 无视保护位，全部擦除
        f.feed("\u001b[2J")
        assertEquals("", f.rowText(0))
        assertEquals("", f.rowText(1))
    }

    @Test
    fun decsedAndDecsel_modes0And1_eraseRespectingCursor() {
        f.feed("\u001b[1\"qA\u001b[0\"qBCD\u001b[1\"qE\u001b[0\"qF")
        f.feed("\u001b[1;3H\u001b[?0K")
        assertEquals("AB  E", f.rowText(0))

        f.feed("\u001b[2;1Hxyz\u001b[2;2H\u001b[?1J")
        assertEquals("A   E", f.rowText(0))
        assertEquals("  z", f.rowText(1))
    }

    @Test
    fun decfra_fillsRectangleAndIgnoresInvalidFillChars() {
        seedRows()
        f.feed("\u001b[32m\u001b[35;2;3;4;6\$x")

        assertEquals("0123456789", f.rowText(0))
        assertEquals("ab####ghij", f.rowText(1))
        assertEquals("kl####qrst", f.rowText(2))
        assertEquals("uv####ABCD", f.rowText(3))
        assertEquals("EFGHIJKLMN", f.rowText(4))
        assertEquals(2, emulator.screen.getStyleAt(2, 3).foreColor)

        // 控制字符 7 不属于 32..126 或 160..255，应忽略
        f.feed("\u001b[7;1;1;2;2\$x")
        assertEquals("0123456789", f.rowText(0))
    }

    @Test
    fun deceraAndDecsera_eraseRectAndPreserveProtectedOrAttributes() {
        f.feed("\u001b[31m01\u001b[1\"q23\u001b[0\"q45\r\n")
        f.feed("\u001b[34mabcd\u001b[1\"qe\u001b[0\"qf")
        f.feed("\u001b[0m")

        // DECSERA (CSI ... $ {) 跳过受保护单元且保留已擦除单元原有样式
        f.feed("\u001b[1;2;2;5\${")
        assertEquals("0 23 5", f.rowText(0))
        assertEquals("a   ef", f.rowText(1))
        assertEquals(1, emulator.screen.getStyleAt(0, 1).foreColor)
        assertEquals(4, emulator.screen.getStyleAt(1, 2).foreColor)

        // DECERA (CSI ... $ z) 用当前擦除样式无条件清空矩形
        f.feed("\u001b[43m\u001b[1;2;2;5\$z")
        assertEquals("0    5", f.rowText(0))
        assertEquals("a    f", f.rowText(1))
        assertEquals(3, emulator.screen.getStyleAt(1, 2).backColor)
    }

    @Test
    fun deccra_copiesRectangularAreaRespectingOriginMode() {
        seedRows()
        f.feed("\u001b[2;3;3;5;1;4;6;1\$v")

        assertEquals("0123456789", f.rowText(0))
        assertEquals("abcdefghij", f.rowText(1))
        assertEquals("klmnopqrst", f.rowText(2))
        assertEquals("uvwxycdeCD", f.rowText(3))
        assertEquals("EFGHImnoMN", f.rowText(4))

        // 原点模式下坐标相对滚动区偏移：(1,1)-(2,2) 即物理 (row 1..2, col 2..3) -> 目标 (3,3) 即物理 (row 3..4, col 4..5)
        f.feed("\u001b[2;5r\u001b[?69h\u001b[3;8s\u001b[?6h")
        f.feed("\u001b[1;1;2;2;1;3;3;1\$v")
        assertEquals("uvwxcddeCD", f.rowText(3))
        assertEquals("EFGHmnnoMN", f.rowText(4))
    }

    @Test
    fun deccaraAndDecrara_streamVsRectangularAndReversedCoords() {
        seedRows()
        f.feed("\u001b[3;8;2;4;1;4\$r")
        // 默认 DECSACE=0（流模式）：首行从 left(3) 到行尾，中间或末行从 originLeft 到 right(7)
        assertTrue(emulator.screen.getStyleAt(1, 3).isBold)
        assertTrue(emulator.screen.getStyleAt(1, 9).isBold)
        assertFalse(emulator.screen.getStyleAt(1, 2).isBold)
        assertTrue(emulator.screen.getStyleAt(2, 0).isBold)
        assertTrue(emulator.screen.getStyleAt(2, 7).isBold)
        assertFalse(emulator.screen.getStyleAt(2, 8).isBold)

        // DECSACE=2 切换为矩形属性改变，并使用 22 清除粗体、DECRARA 7 翻转反色
        f.feed("\u001b[2*x")
        f.feed("\u001b[2;4;3;8;22\$r")
        assertFalse(emulator.screen.getStyleAt(1, 4).isBold)
        assertTrue(emulator.screen.getStyleAt(1, 9).isBold)
        assertTrue(emulator.screen.getStyleAt(2, 0).isBold)

        f.feed("\u001b[2;4;3;6;7\$t")
        assertTrue(emulator.screen.getStyleAt(1, 4).isInverse)
        assertTrue(emulator.screen.getStyleAt(1, 5).isInverse)
        assertFalse(emulator.screen.getStyleAt(1, 6).isInverse)
        f.feed("\u001b[2;4;3;6;0\$t")
        assertFalse(emulator.screen.getStyleAt(1, 4).isInverse)
        assertFalse(emulator.screen.getStyleAt(1, 5).isInverse)
    }
}

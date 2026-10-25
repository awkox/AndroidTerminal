package com.awkoo.libterminal.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 输入冻结/重放：选择文本期间屏幕静止，解冻后按序重放。
 */
class TerminalEmulatorFreezeTest {

    private val f = EmulatorFixture()

    private val emulator get() = f.emulator

    @Test
    fun paused_appendDoesNotChangeScreen() {
        f.feed("A")
        emulator.setInputPaused(true)

        f.feed("BC")

        assertEquals("A", f.rowText(0))
    }

    @Test
    fun unpause_replaysBufferedChunksInOrder() {
        f.feed("A")
        emulator.setInputPaused(true)
        f.feed("B")
        f.feed("C")

        emulator.setInputPaused(false)

        assertEquals("ABC", f.rowText(0))
    }

    @Test
    fun pauseDuringEscapeSequence_keepsParserStateConsistent() {
        f.feed("\u001b[3")
        emulator.setInputPaused(true)

        f.feed("1mX")
        assertEquals("", f.rowText(0))

        emulator.setInputPaused(false)

        // 冻结前的半截 CSI 与冻结期间的字节按原顺序拼接解析：SGR 31m 后写入 X
        assertEquals("X", f.rowText(0))
        assertEquals(1, emulator.screen.getStyleAt(0, 0).foreColor)
    }

    @Test
    fun repeatedPauseResume_isIdempotent() {
        emulator.setInputPaused(true)
        emulator.setInputPaused(true)
        f.feed("A")
        emulator.setInputPaused(false)
        emulator.setInputPaused(false)

        assertEquals("A", f.rowText(0))
    }

    @Test
    fun pauseKeepsCursorFrozenToo() {
        f.feed("ab")
        emulator.setInputPaused(true)

        f.feed("\u001b[5;5H")

        assertEquals(0, emulator.cursorRow)
        assertEquals(2, emulator.cursorCol)
    }
}

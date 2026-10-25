package com.awkoo.libterminal.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 终端模式与字符集：DECSET/DECRST 属性、光标保存恢复（DECSC/DECRC 与 CSI s/u）、
 * 备用屏切换（?1049）、G0/G1 线绘制字符集、软复位与硬复位。
 */
class TerminalEmulatorModeCharsetTest {

    private val f = EmulatorFixture()

    private val emulator get() = f.emulator

    @Test
    fun cursorVisibility_toggledByDecset25() {
        assertTrue(emulator.isCursorEnabled)

        f.feed("\u001b[?25l")
        assertFalse(emulator.isCursorEnabled)

        f.feed("\u001b[?25h")
        assertTrue(emulator.isCursorEnabled)
    }

    @Test
    fun reverseVideo_toggledByDecset5() {
        f.feed("\u001b[?5h")
        assertTrue(emulator.isReverseVideo)

        f.feed("\u001b[?5l")
        assertFalse(emulator.isReverseVideo)
    }

    @Test
    fun applicationCursorKeys_toggledByDecset1() {
        f.feed("\u001b[?1h")
        assertTrue(emulator.isCursorKeysApplicationMode)

        f.feed("\u001b[?1l")
        assertFalse(emulator.isCursorKeysApplicationMode)
    }

    @Test
    fun applicationKeypad_toggledByEscPrefixes() {
        f.feed("\u001b=")
        assertTrue(emulator.isKeypadApplicationMode)

        f.feed("\u001b>")
        assertFalse(emulator.isKeypadApplicationMode)
    }

    @Test
    fun mouseTracking_oneModeActiveAtATime() {
        f.feed("\u001b[?1000h")
        assertTrue(emulator.isMouseTrackingActive)

        f.feed("\u001b[?1002h")
        assertTrue(emulator.isMouseButtonEventTrackingActive)
        assertFalse(emulator.isMouseAnyEventTrackingActive)

        f.feed("\u001b[?1003h")
        assertTrue(emulator.isMouseAnyEventTrackingActive)

        f.feed("\u001b[?1003l")
        assertFalse(emulator.isMouseTrackingActive)
    }

    @Test
    fun saveRestoreCursor_decscDecrc() {
        f.feed("\u001b[3;5H\u001b7A")
        f.feed("\u001b[1;1HB\u001b8")

        assertEquals(2, emulator.cursorRow)
        assertEquals(4, emulator.cursorCol)
    }

    @Test
    fun saveRestoreCursor_csiSAndU() {
        f.feed("\u001b[2;3H\u001b[s")
        f.feed("\u001b[1;1H\u001b[u")

        assertEquals(1, emulator.cursorRow)
        assertEquals(2, emulator.cursorCol)
    }

    @Test
    fun saveRestoreCursor_restoresGraphicRendition() {
        f.feed("\u001b[31m\u001b7\u001b[0m\u001b8A")

        assertEquals(1, emulator.screen.getStyleAt(0, 0).foreColor)
    }

    @Test
    fun altBuffer_switchIsolatesContentAndRestoresMain() {
        f.feed("main")

        f.feed("\u001b[?1049h")
        assertTrue(emulator.isAlternateBufferActive)
        assertEquals("", f.rowText(0))

        f.feed("alt")

        f.feed("\u001b[?1049l")
        assertFalse(emulator.isAlternateBufferActive)
        assertEquals("main", f.rowText(0))
    }

    @Test
    fun altBuffer_enterSavesAndLeaveRestoresCursor() {
        f.feed("\u001b[2;3H\u001b[?1049h\u001b[?1049l")

        assertEquals(1, emulator.cursorRow)
        assertEquals(2, emulator.cursorCol)
    }

    @Test
    fun altBuffer_writesDoNotLeakIntoMainBuffer() {
        f.feed("\u001b[?1049h")
        f.feed("scratch")
        f.feed("\u001b[?1049l")

        assertEquals("", f.rowText(0))
    }

    @Test
    fun lineDrawing_g0Designation_mapsBoxCharacters() {
        f.feed("\u001b(0q")

        assertEquals('\u2500', emulator.screen.getRowForRead(0).textChars()[0])
    }

    @Test
    fun lineDrawing_g1ViaShiftOut() {
        f.feed("\u001b)0\u000eq")

        assertEquals('\u2500', emulator.screen.getRowForRead(0).textChars()[0])
    }

    @Test
    fun lineDrawing_shiftInRestoresG0() {
        f.feed("\u001b)0\u000eq\u000fq")

        assertEquals('\u2500', emulator.screen.getRowForRead(0).textChars()[0])
        assertEquals('q', emulator.screen.getRowForRead(0).textChars()[1])
    }

    @Test
    fun lineDrawing_g0DesignationWithAsciiKeepsPlain() {
        f.feed("\u001b(1q")

        assertEquals('q', emulator.screen.getRowForRead(0).textChars()[0])
    }

    @Test
    fun softReset_resetsModesButKeepsScreenContent() {
        f.feed("keep\u001b[?5h\u001b[!p")

        assertEquals("keep", f.rowText(0))
        assertFalse(emulator.isReverseVideo)
        assertTrue(emulator.isCursorEnabled)
    }

    @Test
    fun hardReset_clearsScreenAndModes() {
        f.feed("drop\u001b[?5h\u001bc")

        assertEquals("", f.rowText(0))
        assertFalse(emulator.isReverseVideo)
    }

    @Test
    fun bracketedPaste_toggledByDecset2004() {
        f.feed("\u001b[?2004h")
        f.clearWritten()

        f.emulator.paste("hi")
        assertEquals("\u001b[200~hi\u001b[201~", f.writtenText)

        f.feed("\u001b[?2004l")
        f.clearWritten()

        f.emulator.paste("hi")
        assertEquals("hi", f.writtenText)
    }

    @Test
    fun focusEvents_reportedOnlyWhenEnabled() {
        f.emulator.onWindowFocusChanged(true)
        assertTrue(f.writtenText.isEmpty())

        f.feed("\u001b[?1004h")
        f.clearWritten()

        f.emulator.onWindowFocusChanged(true)
        f.emulator.onWindowFocusChanged(false)

        assertEquals("\u001b[I\u001b[O", f.writtenText)
    }

    @Test
    fun cursorStyle_setByDecscusr() {
        f.feed("\u001b[3 q")
        assertEquals(TerminalCursorStyle.UNDERLINE, emulator.cursorStyle)

        f.feed("\u001b[5 q")
        assertEquals(TerminalCursorStyle.BAR, emulator.cursorStyle)

        f.feed("\u001b[1 q")
        assertEquals(TerminalCursorStyle.BLOCK, emulator.cursorStyle)
    }

    @Test
    fun insertMode_toggledByCSI4() {
        f.feed("\u001b[4h")
        f.feed("ab\u001b[1;1HX")

        assertEquals("Xab", f.rowText(0))

        f.feed("\u001b[4l")
        f.feed("\u001b[1;1HY")

        assertEquals("Yab", f.rowText(0))
    }
}

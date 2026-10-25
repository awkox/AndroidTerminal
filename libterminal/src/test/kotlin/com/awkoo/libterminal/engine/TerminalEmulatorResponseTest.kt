package com.awkoo.libterminal.engine

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 终端对外响应：DA1/DA2、DSR、DECRQM、窗口尺寸报告（XTWINOPS）、
 * 标题栈、OSC 颜色与剪贴板、鼠标事件上报、DCS 应答。
 */
class TerminalEmulatorResponseTest {

    private val f = EmulatorFixture()

    private val emulator get() = f.emulator

    @Test
    fun primaryDeviceAttribute_reportsTerminalClass() {
        f.feed("\u001b[c")

        assertEquals("\u001b[?64;1;2;6;9;15;18;21;22c", f.writtenText)
    }

    @Test
    fun secondaryDeviceAttribute_reportsModel() {
        f.feed("\u001b[>c")

        assertEquals("\u001b[>41;320;0c", f.writtenText)
    }

    @Test
    fun deviceStatusReport_cursorPosition() {
        f.feed("\u001b[3;5H\u001b[6n")

        assertEquals("\u001b[3;5R", f.writtenText)
    }

    @Test
    fun deviceStatusReport_ok() {
        f.feed("\u001b[5n")

        assertEquals(listOf(27, '['.code, '0'.code, 'n'.code), f.writtenBytes.single().map { it.toInt() and 0xFF })
    }

    @Test
    fun decrqm_reportsSetAndResetStates() {
        f.feed("\u001b[?7\$p")
        assertEquals("\u001b[?7;1\$y", f.writtenText)

        f.clearWritten()
        f.feed("\u001b[?7l\u001b[?7\$p")
        assertEquals("\u001b[?7;2\$y", f.writtenText)
    }

    @Test
    fun windowReport_textAreaSizeInChars() {
        f.feed("\u001b[18t")

        assertEquals("\u001b[9;5;10t", f.writtenText)
    }

    @Test
    fun windowReport_textAreaSizeInPixels() {
        f.feed("\u001b[14t")

        assertEquals("\u001b[4;100;100t", f.writtenText)
    }

    @Test
    fun title_setAndPushPopThroughWindowOps() {
        f.feed("\u001b]2;first\u0007")
        f.feed("\u001b[22t")
        f.feed("\u001b]2;second\u0007")

        assertEquals("second", emulator.titleState.value)

        f.feed("\u001b[23t")

        assertEquals("first", emulator.titleState.value)
    }

    @Test
    fun oscColor_overrideReachesPalette() {
        f.feed("\u001b]4;1;#FF0000\u0007")

        assertTrue(emulator.oscColorOverrides.isOverridden(1))
        assertEquals(0xFFFF0000.toInt(), emulator.oscColorOverrides.value(1))
    }

    @Test
    fun oscColor_resetClearsOverride() {
        f.feed("\u001b]4;1;#FF0000\u0007\u001b]104;1\u0007")

        assertTrue(!emulator.oscColorOverrides.isOverridden(1))
    }

    @Test
    fun clipboard_osc52_emitsDecodedText() = runBlocking {
        val pending = async { emulator.copiedText.first() }
        yield()

        f.feed("\u001b]52;c;aGVsbG8=\u0007")

        assertEquals("hello", withTimeout(2_000) { pending.await() })
    }

    @Test
    fun mouse_sgrProtocol_pressReleaseAndWheel() {
        f.feed("\u001b[?1000;1006h")
        f.clearWritten()

        emulator.sendMouseEvent(TerminalEmulator.MOUSE_LEFT_BUTTON, 5, 3, pressed = true)
        emulator.sendMouseEvent(TerminalEmulator.MOUSE_LEFT_BUTTON, 5, 3, pressed = false)
        emulator.sendMouseEvent(TerminalEmulator.MOUSE_WHEELUP_BUTTON, 0, 0, pressed = true)

        assertEquals("\u001b[<0;6;4M\u001b[<0;6;4m\u001b[<64;1;1M", f.writtenText)
    }

    @Test
    fun mouse_x10Protocol_emitsOffsetBytes() {
        f.feed("\u001b[?1000h")
        f.clearWritten()

        emulator.sendMouseEvent(TerminalEmulator.MOUSE_LEFT_BUTTON, 5, 3, pressed = true)

        assertEquals(
            listOf(27, '['.code, 'M'.code, 32, 38, 36),
            f.writtenBytes.single().map { it.toInt() and 0xFF }
        )
    }

    @Test
    fun dcs_deviceAttributesQuery_answered() {
        f.feed("\u001bP\$q\"p\u001b\\")

        assertEquals("\u001bP1\$r64;1\"p\u001b\\", f.writtenText)
    }

    @Test
    fun dcs_xtgettcap_colorsAnswered() {
        f.feed("\u001bP+q436F\u001b\\")

        assertEquals("\u001bP1+r436F=323536\u001b\\", f.writtenText)
    }

    @Test
    fun dcs_xtgettcap_injectionPayload_answeredWithoutEchoingPayload() {
        // 回归（RCE 防护）：恶意 DCS 载荷仅换来规范的裸失败响应，原文绝不回写进程输入通道
        f.feed("\u001bP+q41\nls\n\u001b\\")

        assertEquals("\u001bP0+r\u001b\\", f.writtenText)
        assertTrue(!f.writtenText.contains("\n"))
        assertTrue(f.writtenBytes.isEmpty())
    }

    @Test
    fun dcs_xtgettcap_invalidName_terminatesListProcessing() {
        f.feed("\u001bP+q6B31;6161\u001b\\")

        assertEquals("\u001bP1+r6B31=1B4F50\u001b\\\u001bP0+r\u001b\\", f.writtenText)
    }
}

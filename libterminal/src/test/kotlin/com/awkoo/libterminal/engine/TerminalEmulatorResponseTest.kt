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
    fun deviceStatusReport_cursorPosition_respectsOriginMode() {
        f.feed("\u001b[2;5r\u001b[?69h\u001b[3;8s\u001b[?6h")
        f.feed("\u001b[2;4H")
        f.clearWritten()

        f.feed("\u001b[6n\u001b[?6n")

        assertEquals("\u001b[2;4R\u001b[?2;4;1R", f.writtenText)
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
    fun decrqm_altScreenModesReflectBufferState_andUnknownIsZero() {
        f.feed("\u001b[?47\$p\u001b[?1047\$p\u001b[?1049\$p\u001b[?999\$p")
        assertEquals(
            "\u001b[?47;2\$y\u001b[?1047;2\$y\u001b[?1049;2\$y\u001b[?999;0\$y",
            f.writtenText
        )

        f.clearWritten()
        f.feed("\u001b[?1049h\u001b[?47\$p\u001b[?1047\$p\u001b[?1049\$p")
        assertEquals(
            "\u001b[?47;1\$y\u001b[?1047;1\$y\u001b[?1049;1\$y",
            f.writtenText
        )
    }

    @Test
    fun windowReport_textAreaSizeInChars() {
        f.feed("\u001b[18t")

        assertEquals("\u001b[8;5;10t", f.writtenText)
    }

    @Test
    fun windowReport_screenSizeInChars() {
        f.feed("\u001b[19t")

        assertEquals("\u001b[9;5;10t", f.writtenText)
    }

    @Test
    fun windowReport_stateAndPosition() {
        f.feed("\u001b[11t\u001b[13t")

        assertEquals("\u001b[1t\u001b[3;0;0t", f.writtenText)
    }

    @Test
    fun windowReport_textAreaSizeInPixels() {
        f.feed("\u001b[14t")

        assertEquals("\u001b[4;100;100t", f.writtenText)
    }

    @Test
    fun windowReport_cellSizeInPixels() {
        f.feed("\u001b[16t")
        assertEquals("\u001b[6;20;10t", f.writtenText)

        f.clearWritten()
        emulator.resize(12, 6, 14, 28)
        f.feed("\u001b[16t")
        assertEquals("\u001b[6;28;14t", f.writtenText)
    }

    @Test
    fun windowReport_titleQueries_disabledByDefault_writesNothing() {
        assertTrue(!emulator.isTitleQueryEnabled)
        f.feed("\u001b]0;secret\u0007\u001b[20t\u001b[21t")

        assertEquals("", f.writtenText)
        assertTrue(f.writtenBytes.isEmpty())
    }

    @Test
    fun windowReport_titleQueryPolicy_persistsAcrossSoftAndHardResets() {
        f.feed("\u001b[!p\u001bc\u001b]0;secret\u0007\u001b[20t\u001b[21t")
        assertTrue(!emulator.isTitleQueryEnabled)
        assertEquals("", f.writtenText)

        emulator.isTitleQueryEnabled = true
        f.feed("\u001b[!p\u001bc\u001b]2;after\u0007\u001b[21t")
        assertTrue(emulator.isTitleQueryEnabled)
        assertEquals("\u001b]lafter\u001b\\", f.writtenText)
    }

    @Test
    fun windowReport_windowTitle_reportsCurrentTitleAndEmptyWhenUnset() {
        emulator.isTitleQueryEnabled = true
        f.feed("\u001b[21t")
        assertEquals("\u001b]l\u001b\\", f.writtenText)

        f.clearWritten()
        f.feed("\u001b]2;vim - main.kt\u0007\u001b[21t")
        assertEquals("\u001b]lvim - main.kt\u001b\\", f.writtenText)

        f.clearWritten()
        f.feed("\u001b]1;only-icon\u0007\u001b[21t")
        assertEquals("\u001b]lvim - main.kt\u001b\\", f.writtenText)
    }

    @Test
    fun windowReport_iconLabel_reportsCurrentIconAndEmptyWhenUnset() {
        emulator.isTitleQueryEnabled = true
        f.feed("\u001b[20t")
        assertEquals("\u001b]L\u001b\\", f.writtenText)

        f.clearWritten()
        f.feed("\u001b]1;my-icon\u0007\u001b[20t")
        assertEquals("\u001b]Lmy-icon\u001b\\", f.writtenText)

        f.clearWritten()
        f.feed("\u001b]0;shared\u0007\u001b[20t\u001b[21t")
        assertEquals("\u001b]Lshared\u001b\\\u001b]lshared\u001b\\", f.writtenText)
    }

    @Test
    fun windowReport_title_stripsControlCharactersAgainstInjection() {
        emulator.isTitleQueryEnabled = true
        f.feed("\u001b]2;safe\u001bX\nls\r\u007f\u009c\u0007\u001b[21t")

        assertEquals("\u001b]lsafeXls\u001b\\", f.writtenText)
        assertEquals("safeXls", emulator.titleState.value)
    }

    @Test
    fun title_setAndPushPopThroughWindowOps() {
        f.feed("\u001b]2;first\u0007")
        f.feed("\u001b[22t")
        f.feed("\u001b]2;second\u0007")

        assertEquals("second", emulator.titleState.value)

        f.feed("\u001b[23t\u001b[20t\u001b[21t")

        assertEquals("first", emulator.titleState.value)
        assertEquals("", f.writtenText)
    }

    @Test
    fun title_pushPopBySubMode_isolatesWindowAndIconTitle() {
        emulator.isTitleQueryEnabled = true
        f.feed("\u001b]0;base\u0007")
        f.feed("\u001b[22;1t\u001b]1;tmp-icon\u0007")
        f.feed("\u001b[22;2t\u001b]2;tmp-win\u0007")

        f.feed("\u001b[23;1t\u001b[20t\u001b[21t")
        assertEquals("\u001b]Lbase\u001b\\\u001b]ltmp-win\u001b\\", f.writtenText)

        f.clearWritten()
        f.feed("\u001b[23;2t\u001b[21t")
        assertEquals("\u001b]lbase\u001b\\", f.writtenText)
    }

    @Test
    fun title_pushPopWithThirdParam_preservesStandardTwoParamBehavior() {
        f.feed("\u001b]2;a\u0007\u001b[22;2;99t\u001b]2;b\u0007\u001b[23;2;99t")

        assertEquals("a", emulator.titleState.value)
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

    @Test
    fun dcs_xtgettcap_reflectsApplicationCursorAndKeypadModes() {
        f.feed("\u001b[?1h\u001b=\u001bP+q6B75;4038\u001b\\")

        // "ku" (6B75) -> ESC O A (1B4F41); "@8" (4038) -> ESC O M (1B4F4D)
        assertEquals("\u001bP1+r6B75=1B4F41\u001b\\\u001bP1+r4038=1B4F4D\u001b\\", f.writtenText)
    }

    @Test
    fun oscSpecialColorQuery_echoesPreservingTerminator() {
        f.feed("\u001b]11;#112233\u0007\u001b]12;#445566\u001b\\")
        f.clearWritten()

        f.feed("\u001b]11;?\u001b\\\u001b]12;?\u0007")

        assertEquals(
            "\u001b]11;rgb:1111/2222/3333\u001b\\\u001b]12;rgb:4444/5555/6666\u0007",
            f.writtenText
        )
    }
}

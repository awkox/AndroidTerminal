package com.awkoo.libterminal.engine.protocol

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeySequenceEncoderTest {

    @Test
    fun cursorKeys_normalVsApplicationMode() {
        assertEquals(
            "\u001b[A",
            KeySequenceEncoder.getCode(KeyEvent.KEYCODE_DPAD_UP, 0, cursorApp = false, keypadApplication = false)
        )
        assertEquals(
            "\u001bOA",
            KeySequenceEncoder.getCode(KeyEvent.KEYCODE_DPAD_UP, 0, cursorApp = true, keypadApplication = false)
        )
    }

    @Test
    fun arrowKeys_coverAllDirections() {
        val normal = { code: Int -> KeySequenceEncoder.getCode(code, 0, false, false) }

        assertEquals("\u001b[A", normal(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals("\u001b[B", normal(KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals("\u001b[C", normal(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals("\u001b[D", normal(KeyEvent.KEYCODE_DPAD_LEFT))
    }

    @Test
    fun homeAndEnd_normalMode() {
        assertEquals(
            "\u001b[H",
            KeySequenceEncoder.getCode(KeyEvent.KEYCODE_MOVE_HOME, 0, false, false)
        )
        assertEquals(
            "\u001b[F",
            KeySequenceEncoder.getCode(KeyEvent.KEYCODE_MOVE_END, 0, false, false)
        )
    }

    @Test
    fun functionKeys_plainAndModified() {
        assertEquals("\u001bOP", KeySequenceEncoder.getCode(KeyEvent.KEYCODE_F1, 0, false, false))
        assertEquals("\u001bOQ", KeySequenceEncoder.getCode(KeyEvent.KEYCODE_F2, 0, false, false))
        assertEquals("\u001bOR", KeySequenceEncoder.getCode(KeyEvent.KEYCODE_F3, 0, false, false))
        assertEquals("\u001bOS", KeySequenceEncoder.getCode(KeyEvent.KEYCODE_F4, 0, false, false))
        assertEquals("\u001b[15~", KeySequenceEncoder.getCode(KeyEvent.KEYCODE_F5, 0, false, false))
        assertEquals("\u001b[17~", KeySequenceEncoder.getCode(KeyEvent.KEYCODE_F6, 0, false, false))
        assertEquals("\u001b[18~", KeySequenceEncoder.getCode(KeyEvent.KEYCODE_F7, 0, false, false))
        assertEquals("\u001b[19~", KeySequenceEncoder.getCode(KeyEvent.KEYCODE_F8, 0, false, false))
        assertEquals("\u001b[20~", KeySequenceEncoder.getCode(KeyEvent.KEYCODE_F9, 0, false, false))
        assertEquals("\u001b[21~", KeySequenceEncoder.getCode(KeyEvent.KEYCODE_F10, 0, false, false))
        assertEquals("\u001b[23~", KeySequenceEncoder.getCode(KeyEvent.KEYCODE_F11, 0, false, false))
        assertEquals("\u001b[24~", KeySequenceEncoder.getCode(KeyEvent.KEYCODE_F12, 0, false, false))

        assertEquals(
            "\u001b[1;5P",
            KeySequenceEncoder.getCode(KeyEvent.KEYCODE_F1, KeySequenceEncoder.KEYMOD_CTRL, false, false)
        )
        assertEquals(
            "\u001b[15;2~",
            KeySequenceEncoder.getCode(KeyEvent.KEYCODE_F5, KeySequenceEncoder.KEYMOD_SHIFT, false, false)
        )
        assertEquals(
            "\u001b[24;8~",
            KeySequenceEncoder.getCode(
                KeyEvent.KEYCODE_F12,
                KeySequenceEncoder.KEYMOD_SHIFT or KeySequenceEncoder.KEYMOD_ALT or KeySequenceEncoder.KEYMOD_CTRL,
                false,
                false
            )
        )
    }

    @Test
    fun modifierArithmetic_coversAllThreeModifierBitsFromTwoToEight() {
        val shift = KeySequenceEncoder.KEYMOD_SHIFT
        val alt = KeySequenceEncoder.KEYMOD_ALT
        val ctrl = KeySequenceEncoder.KEYMOD_CTRL

        assertEquals("\u001b[1;2A", KeySequenceEncoder.getCode(KeyEvent.KEYCODE_DPAD_UP, shift, false, false))
        assertEquals("\u001b[1;3A", KeySequenceEncoder.getCode(KeyEvent.KEYCODE_DPAD_UP, alt, false, false))
        assertEquals("\u001b[1;4A", KeySequenceEncoder.getCode(KeyEvent.KEYCODE_DPAD_UP, shift or alt, false, false))
        assertEquals("\u001b[1;5A", KeySequenceEncoder.getCode(KeyEvent.KEYCODE_DPAD_UP, ctrl, false, false))
        assertEquals("\u001b[1;6A", KeySequenceEncoder.getCode(KeyEvent.KEYCODE_DPAD_UP, shift or ctrl, false, false))
        assertEquals("\u001b[1;7A", KeySequenceEncoder.getCode(KeyEvent.KEYCODE_DPAD_UP, alt or ctrl, false, false))
        assertEquals("\u001b[1;8A", KeySequenceEncoder.getCode(KeyEvent.KEYCODE_DPAD_UP, shift or alt or ctrl, false, false))
        // 修饰符存在时即使 cursorApp=true 仍使用 CSI 1;mod 格式
        assertEquals("\u001b[1;5A", KeySequenceEncoder.getCode(KeyEvent.KEYCODE_DPAD_UP, ctrl, cursorApp = true, false))
    }

    @Test
    fun homeAndEnd_applicationAndModifiedModes() {
        assertEquals("\u001bOH", KeySequenceEncoder.getCode(KeyEvent.KEYCODE_MOVE_HOME, 0, cursorApp = true, false))
        assertEquals("\u001bOF", KeySequenceEncoder.getCode(KeyEvent.KEYCODE_MOVE_END, 0, cursorApp = true, false))
        assertEquals(
            "\u001b[1;2H",
            KeySequenceEncoder.getCode(KeyEvent.KEYCODE_MOVE_HOME, KeySequenceEncoder.KEYMOD_SHIFT, true, false)
        )
        assertEquals(
            "\u001b[1;3F",
            KeySequenceEncoder.getCode(KeyEvent.KEYCODE_MOVE_END, KeySequenceEncoder.KEYMOD_ALT, true, false)
        )
    }

    @Test
    fun editingNavigationAndSpecialKeys() {
        assertEquals("\u001b[2~", KeySequenceEncoder.getCode(KeyEvent.KEYCODE_INSERT, 0, false, false))
        assertEquals("\u001b[3~", KeySequenceEncoder.getCode(KeyEvent.KEYCODE_FORWARD_DEL, 0, false, false))
        assertEquals(
            "\u001b[3;5~",
            KeySequenceEncoder.getCode(KeyEvent.KEYCODE_FORWARD_DEL, KeySequenceEncoder.KEYMOD_CTRL, false, false)
        )
        assertEquals("\u001b[5~", KeySequenceEncoder.getCode(KeyEvent.KEYCODE_PAGE_UP, 0, false, false))
        assertEquals("\u001b[6~", KeySequenceEncoder.getCode(KeyEvent.KEYCODE_PAGE_DOWN, 0, false, false))
        assertEquals("\u001b[32~", KeySequenceEncoder.getCode(KeyEvent.KEYCODE_SYSRQ, 0, false, false))
        assertEquals("\u001b[34~", KeySequenceEncoder.getCode(KeyEvent.KEYCODE_BREAK, 0, false, false))
        assertEquals("\u001b", KeySequenceEncoder.getCode(KeyEvent.KEYCODE_ESCAPE, 0, false, false))
        assertEquals("\u001b", KeySequenceEncoder.getCode(KeyEvent.KEYCODE_BACK, 0, false, false))
        assertEquals("\r", KeySequenceEncoder.getCode(KeyEvent.KEYCODE_DPAD_CENTER, 0, false, false))
    }

    @Test
    fun delTabEnterAndSpace_modifiers() {
        assertEquals("\u007F", KeySequenceEncoder.getCode(KeyEvent.KEYCODE_DEL, 0, false, false))
        assertEquals("\u0008", KeySequenceEncoder.getCode(KeyEvent.KEYCODE_DEL, KeySequenceEncoder.KEYMOD_CTRL, false, false))
        assertEquals("\u001b\u007F", KeySequenceEncoder.getCode(KeyEvent.KEYCODE_DEL, KeySequenceEncoder.KEYMOD_ALT, false, false))
        assertEquals(
            "\u001b\u0008",
            KeySequenceEncoder.getCode(KeyEvent.KEYCODE_DEL, KeySequenceEncoder.KEYMOD_ALT or KeySequenceEncoder.KEYMOD_CTRL, false, false)
        )

        assertEquals("\t", KeySequenceEncoder.getCode(KeyEvent.KEYCODE_TAB, 0, false, false))
        assertEquals("\u001b[Z", KeySequenceEncoder.getCode(KeyEvent.KEYCODE_TAB, KeySequenceEncoder.KEYMOD_SHIFT, false, false))

        assertEquals("\r", KeySequenceEncoder.getCode(KeyEvent.KEYCODE_ENTER, 0, false, false))
        assertEquals("\u001b\r", KeySequenceEncoder.getCode(KeyEvent.KEYCODE_ENTER, KeySequenceEncoder.KEYMOD_ALT, false, false))

        assertNull(KeySequenceEncoder.getCode(KeyEvent.KEYCODE_SPACE, 0, false, false))
        assertEquals("\u0000", KeySequenceEncoder.getCode(KeyEvent.KEYCODE_SPACE, KeySequenceEncoder.KEYMOD_CTRL, false, false))
    }

    @Test
    fun numpad_numLockAndApplicationModes() {
        val numLock = KeySequenceEncoder.KEYMOD_NUM_LOCK

        assertNull(KeySequenceEncoder.getCode(KeyEvent.KEYCODE_NUM_LOCK, 0, false, keypadApplication = false))
        assertEquals("\u001bOP", KeySequenceEncoder.getCode(KeyEvent.KEYCODE_NUM_LOCK, 0, false, keypadApplication = true))

        assertEquals("\n", KeySequenceEncoder.getCode(KeyEvent.KEYCODE_NUMPAD_ENTER, 0, false, keypadApplication = false))
        assertEquals("\u001bOM", KeySequenceEncoder.getCode(KeyEvent.KEYCODE_NUMPAD_ENTER, 0, false, keypadApplication = true))
        assertEquals("*", KeySequenceEncoder.getCode(KeyEvent.KEYCODE_NUMPAD_MULTIPLY, 0, false, false))
        assertEquals("\u001bOj", KeySequenceEncoder.getCode(KeyEvent.KEYCODE_NUMPAD_MULTIPLY, 0, false, true))
        assertEquals("+", KeySequenceEncoder.getCode(KeyEvent.KEYCODE_NUMPAD_ADD, 0, false, false))
        assertEquals("\u001bOk", KeySequenceEncoder.getCode(KeyEvent.KEYCODE_NUMPAD_ADD, 0, false, true))
        assertEquals("-", KeySequenceEncoder.getCode(KeyEvent.KEYCODE_NUMPAD_SUBTRACT, 0, false, false))
        assertEquals("\u001bOm", KeySequenceEncoder.getCode(KeyEvent.KEYCODE_NUMPAD_SUBTRACT, 0, false, true))
        assertEquals("/", KeySequenceEncoder.getCode(KeyEvent.KEYCODE_NUMPAD_DIVIDE, 0, false, false))
        assertEquals("\u001bOo", KeySequenceEncoder.getCode(KeyEvent.KEYCODE_NUMPAD_DIVIDE, 0, false, true))
        assertEquals("=", KeySequenceEncoder.getCode(KeyEvent.KEYCODE_NUMPAD_EQUALS, 0, false, false))
        assertEquals("\u001bOX", KeySequenceEncoder.getCode(KeyEvent.KEYCODE_NUMPAD_EQUALS, 0, false, true))
        assertEquals(",", KeySequenceEncoder.getCode(KeyEvent.KEYCODE_NUMPAD_COMMA, 0, false, true))

        assertEquals("0", KeySequenceEncoder.getCode(KeyEvent.KEYCODE_NUMPAD_0, numLock, false, false))
        assertEquals("\u001bOp", KeySequenceEncoder.getCode(KeyEvent.KEYCODE_NUMPAD_0, numLock, false, true))
        assertEquals("\u001b[2~", KeySequenceEncoder.getCode(KeyEvent.KEYCODE_NUMPAD_0, 0, false, false))

        assertEquals(".", KeySequenceEncoder.getCode(KeyEvent.KEYCODE_NUMPAD_DOT, numLock, false, false))
        assertEquals("\u001bOn", KeySequenceEncoder.getCode(KeyEvent.KEYCODE_NUMPAD_DOT, numLock, false, true))
        assertEquals("\u001b[3~", KeySequenceEncoder.getCode(KeyEvent.KEYCODE_NUMPAD_DOT, 0, false, false))

        assertEquals("1", KeySequenceEncoder.getCode(KeyEvent.KEYCODE_NUMPAD_1, numLock, false, false))
        assertEquals("\u001bOq", KeySequenceEncoder.getCode(KeyEvent.KEYCODE_NUMPAD_1, numLock, false, true))
        assertEquals("\u001b[F", KeySequenceEncoder.getCode(KeyEvent.KEYCODE_NUMPAD_1, 0, cursorApp = false, false))
        assertEquals("\u001bOF", KeySequenceEncoder.getCode(KeyEvent.KEYCODE_NUMPAD_1, 0, cursorApp = true, false))

        assertEquals("3", KeySequenceEncoder.getCode(KeyEvent.KEYCODE_NUMPAD_3, numLock, false, false))
        assertEquals("\u001bOs", KeySequenceEncoder.getCode(KeyEvent.KEYCODE_NUMPAD_3, numLock, false, true))
        assertEquals("\u001b[6~", KeySequenceEncoder.getCode(KeyEvent.KEYCODE_NUMPAD_3, 0, false, false))

        assertEquals("5", KeySequenceEncoder.getCode(KeyEvent.KEYCODE_NUMPAD_5, 0, false, false))
        assertEquals("\u001bOu", KeySequenceEncoder.getCode(KeyEvent.KEYCODE_NUMPAD_5, 0, false, true))

        assertEquals("9", KeySequenceEncoder.getCode(KeyEvent.KEYCODE_NUMPAD_9, numLock, false, false))
        assertEquals("\u001bOy", KeySequenceEncoder.getCode(KeyEvent.KEYCODE_NUMPAD_9, numLock, false, true))
        assertEquals("\u001b[5~", KeySequenceEncoder.getCode(KeyEvent.KEYCODE_NUMPAD_9, 0, false, false))
    }

    @Test
    fun enter_isCarriageReturn() {
        assertEquals(
            "\r",
            KeySequenceEncoder.getCode(KeyEvent.KEYCODE_ENTER, 0, false, false)
        )
    }

    @Test
    fun termcapLookup_knownCapability_mapsToKeyCode() {
        assertEquals("\u001bOP", KeySequenceEncoder.getCodeFromTermcap("k1", false, false))
        assertEquals("\u001b[21~", KeySequenceEncoder.getCodeFromTermcap("k;", false, false))
        assertEquals("\u001b[23~", KeySequenceEncoder.getCodeFromTermcap("F1", false, false))
        assertEquals("\u001b[24~", KeySequenceEncoder.getCodeFromTermcap("F2", false, false))
        assertEquals("\u001b[1;2P", KeySequenceEncoder.getCodeFromTermcap("F3", false, false))
        assertEquals("\u001b[24;2~", KeySequenceEncoder.getCodeFromTermcap("FE", false, false))
        assertEquals("\u007F", KeySequenceEncoder.getCodeFromTermcap("kb", false, false))
        assertEquals("\u001b[Z", KeySequenceEncoder.getCodeFromTermcap("kB", false, false))
        assertEquals("\u001b[3~", KeySequenceEncoder.getCodeFromTermcap("kD", false, false))
        assertEquals("\u001b[2~", KeySequenceEncoder.getCodeFromTermcap("kI", false, false))
        assertEquals("\u001b[5~", KeySequenceEncoder.getCodeFromTermcap("kP", false, false))
        assertEquals("\u001b[6~", KeySequenceEncoder.getCodeFromTermcap("kN", false, false))
        assertEquals("\u001bOA", KeySequenceEncoder.getCodeFromTermcap("ku", cursorKeysApplication = true, false))
        assertEquals("\u001bOB", KeySequenceEncoder.getCodeFromTermcap("kd", cursorKeysApplication = true, false))
        assertEquals("\u001b[H", KeySequenceEncoder.getCodeFromTermcap("K1", false, false))
        assertEquals("\u001b[6~", KeySequenceEncoder.getCodeFromTermcap("K5", false, false))
        assertEquals("\u001b[1;2H", KeySequenceEncoder.getCodeFromTermcap("#2", false, false))
        assertEquals("\u001b[1;2D", KeySequenceEncoder.getCodeFromTermcap("#4", false, false))
        assertEquals("\u001b[1;2F", KeySequenceEncoder.getCodeFromTermcap("*7", false, false))
        assertEquals("\u001b[1;2A", KeySequenceEncoder.getCodeFromTermcap("kUP", false, false))
        assertEquals("\u001b[1;2B", KeySequenceEncoder.getCodeFromTermcap("kDN", false, false))
        assertEquals("\n", KeySequenceEncoder.getCodeFromTermcap("@8", false, keypadApplication = false))
        assertEquals("\u001bOM", KeySequenceEncoder.getCodeFromTermcap("@8", false, keypadApplication = true))
    }

    @Test
    fun termcapLookup_unknownCapability_isNull() {
        assertNull(
            KeySequenceEncoder.getCodeFromTermcap("zz", cursorKeysApplication = false, keypadApplication = false)
        )
    }

    @Test
    fun shiftedTermcapCapability_modifiesSequence() {
        // "%i"（右方向键 / terminfo=kRIT 的 shifted 变体）带 SHIFT 修饰
        val sequence = KeySequenceEncoder.getCodeFromTermcap(
            "%i",
            cursorKeysApplication = false,
            keypadApplication = false
        )

        assertEquals("\u001b[1;2C", sequence)
    }
}

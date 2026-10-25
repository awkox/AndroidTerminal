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
        assertEquals(
            "\u001b[1;5P",
            KeySequenceEncoder.getCode(KeyEvent.KEYCODE_F1, KeySequenceEncoder.KEYMOD_CTRL, false, false)
        )
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
        // "k1"（F1 键帽能力）→ F1 序列
        assertEquals(
            "\u001bOP",
            KeySequenceEncoder.getCodeFromTermcap("k1", cursorKeysApplication = false, keypadApplication = false)
        )
    }

    @Test
    fun termcapLookup_unknownCapability_isNull() {
        assertNull(
            KeySequenceEncoder.getCodeFromTermcap("zz", cursorKeysApplication = false, keypadApplication = false)
        )
    }

    @Test
    fun shiftedTermcapCapability_modifiesSequence() {
        // "%i"（kdch1 的 shifted 变体）带 SHIFT 修饰
        val sequence = KeySequenceEncoder.getCodeFromTermcap(
            "%i",
            cursorKeysApplication = false,
            keypadApplication = false
        )

        assertEquals("\u001b[1;2C", sequence)
    }
}

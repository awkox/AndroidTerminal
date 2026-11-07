package com.awkoo.libterminal.view.input

import android.view.KeyEvent
import com.awkoo.libterminal.engine.EmulatorFixture
import com.awkoo.libterminal.engine.protocol.KeySequenceEncoder
import com.awkoo.libterminal.view.ExtraKeysModifierSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 键盘翻页语义（xterm scroll-back/scroll-forw 口径）：Shift+PageUp/Down 是本地回滚动作，
 * 只驱动视口回滚回调，不向应用发滚轮事件、不伪造指针坐标。
 */
class KeyInputProcessorTest {

    private val f = EmulatorFixture()
    private val scrolls = mutableListOf<Int>()
    private val processor = KeyInputProcessor(
        sessionProvider = { null },
        emulatorProvider = { f.emulator },
        pokeCursor = {},
        scrollRows = { scrolls += it },
        isSelectingText = { false },
        stopTextSelection = {},
        modifierReader = { ExtraKeysModifierSnapshot() }
    )

    @Test
    fun shiftPageUp_scrollsViewportBackByPage() {
        assertTrue(processor.handleKeyCode(KeyEvent.KEYCODE_PAGE_UP, KeySequenceEncoder.KEYMOD_SHIFT))

        assertEquals(listOf(-f.rows), scrolls)
    }

    @Test
    fun shiftPageDown_scrollsViewportForwardByPage() {
        assertTrue(processor.handleKeyCode(KeyEvent.KEYCODE_PAGE_DOWN, KeySequenceEncoder.KEYMOD_SHIFT))

        assertEquals(listOf(f.rows), scrolls)
    }

    @Test
    fun pageKeyWithoutShift_isNotViewportScroll() {
        assertFalse(processor.handleKeyCode(KeyEvent.KEYCODE_PAGE_UP, 0))

        assertEquals(emptyList<Int>(), scrolls)
    }
}

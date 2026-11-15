package com.awkoo.libterminal.view.textselection

import android.content.Context
import android.view.ActionMode
import com.awkoo.libterminal.engine.EmulatorFixture
import com.awkoo.libterminal.engine.TerminalEmulator
import com.awkoo.libterminal.engine.buffer.CursorCoord
import com.awkoo.libterminal.view.interact.ActionModeCustomizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextSelectionCursorControllerTest {

    private class FakeSelectionHandle : SelectionHandle {
        override val handleHeight: Int = 20
        override val handleWidth: Int = 10
        var visibleCount = 0
        var hideCount = 0

        override fun updateLayout(x: Int, y: Int, isVisible: Boolean, orientation: Int) {
            if (isVisible) visibleCount++
        }

        override fun hide() {
            hideCount++
        }
    }

    private class FakeSelectionHost(
        override var emulator: TerminalEmulator?
    ) : SelectionHost {
        lateinit var controller: TextSelectionCursorController
        var lastActionModeCallback: ActionMode.Callback? = null
        var stopCount = 0

        override val context: Context
            get() = error("context is not needed for controller lifecycle tests")
        override val paddingLeft: Int = 0
        override val paddingRight: Int = 0
        override val paddingTop: Int = 0
        override val paddingBottom: Int = 0
        override val width: Int = 200
        override val height: Int = 200
        override val bottom: Int = 200
        override val topRow: Int = 0
        override val actionModeCustomizer: ActionModeCustomizer? = null

        override fun bufferCellAt(x: Float, y: Float): CursorCoord {
            val emu = emulator ?: return CursorCoord.pack(0, 0)
            val col = (x.toInt() / 10).coerceIn(0, emu.columns - 1)
            val row = (y.toInt() / 20).coerceIn(0, emu.rows - 1)
            return CursorCoord.pack(col, row)
        }

        override fun getPointX(cx: Int): Int = cx * 10
        override fun getPointY(cy: Int): Int = cy * 20
        override fun scrollbackBy(deltaRows: Int) {}
        override fun invalidate() {}

        override fun startActionMode(callback: ActionMode.Callback, type: Int): ActionMode? {
            lastActionModeCallback = callback
            return null
        }

        override fun copyTextToClipboard() {}
        override fun pasteTextFromClipboard() {}

        override fun stopTextSelectionMode() {
            stopCount++
            controller.hide()
        }
    }

    private fun createController(emulator: TerminalEmulator?): Triple<TextSelectionCursorController, FakeSelectionHost, Pair<FakeSelectionHandle, FakeSelectionHandle>> {
        val host = FakeSelectionHost(emulator)
        val handles = mutableListOf<FakeSelectionHandle>()
        val controller = TextSelectionCursorController(host) { _, _ ->
            FakeSelectionHandle().also { handles += it }
        }
        host.controller = controller
        return Triple(controller, host, handles[0] to handles[1])
    }

    @Test
    fun immediateHide_afterShow_succeedsHandlesHiddenAndUnpausesEmulator() {
        val f = EmulatorFixture()
        f.feed("hello")
        val (controller, _, handles) = createController(f.emulator)

        controller.showAt(0f, 0f)
        assertTrue(controller.isActive)

        f.feed("world")
        assertEquals("hello", f.rowText(0))

        assertTrue(controller.hide())
        assertFalse(controller.isActive)
        assertEquals(1, handles.first.hideCount)
        assertEquals(1, handles.second.hideCount)
        assertEquals("helloworld", f.rowText(0))
    }

    @Test
    fun hideAfterEmulatorSwappedOrDetached_unpausesOriginalPausedEmulator() {
        val f1 = EmulatorFixture()
        val f2 = EmulatorFixture()
        f1.feed("one")
        val (controller, host, _) = createController(f1.emulator)

        controller.showAt(0f, 0f)
        f1.feed("two")
        assertEquals("one", f1.rowText(0))

        host.emulator = f2.emulator
        host.emulator = null

        assertTrue(controller.hide())
        assertFalse(controller.isActive)
        assertEquals("onetwo", f1.rowText(0))
        assertEquals("", f2.rowText(0))
    }

    @Test
    fun onDestroyActionMode_immediatelyAfterShow_exitsSelectionAndUnpauses() {
        val f = EmulatorFixture()
        f.feed("abc")
        val (controller, host, handles) = createController(f.emulator)

        controller.showAt(0f, 0f)
        f.feed("def")
        assertEquals("abc", f.rowText(0))

        host.lastActionModeCallback?.onDestroyActionMode(null)

        assertEquals(1, host.stopCount)
        assertFalse(controller.isActive)
        assertEquals(1, handles.first.hideCount)
        assertEquals(1, handles.second.hideCount)
        assertEquals("abcdef", f.rowText(0))
    }

    @Test
    fun onTouchModeChangedFalse_immediatelyAfterShow_exitsSelectionAndUnpauses() {
        val f = EmulatorFixture()
        f.feed("xyz")
        val (controller, _, _) = createController(f.emulator)

        controller.showAt(0f, 0f)
        f.feed("123")
        assertEquals("xyz", f.rowText(0))

        controller.onTouchModeChanged(false)

        assertFalse(controller.isActive)
        assertEquals("xyz123", f.rowText(0))
    }
}

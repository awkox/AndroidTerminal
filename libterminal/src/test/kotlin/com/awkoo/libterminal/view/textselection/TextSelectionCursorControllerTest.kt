package com.awkoo.libterminal.view.textselection

import android.content.Context
import android.view.ActionMode
import com.awkoo.libterminal.engine.EmulatorFixture
import com.awkoo.libterminal.engine.TerminalEmulator
import com.awkoo.libterminal.engine.buffer.CursorCoord
import com.awkoo.libterminal.view.interact.ActionModeCustomizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

        var capturingCount = 0
        var releasedCount = 0

        override fun stopTextSelectionMode() {
            stopCount++
            controller.hide()
        }

        override fun onSelectionSnapshotCapturing(emulator: TerminalEmulator) {
            capturingCount++
            emulator.takeScrollCounter()
        }

        override fun onSelectionSnapshotReleased() {
            releasedCount++
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
    fun immediateHide_afterShow_succeedsHandlesHiddenAndReleasesFrozenSnapshot() {
        val f = EmulatorFixture()
        f.feed("hello")
        val initialRow = f.emulator.screen.getRowForRead(0)
        val (controller, host, handles) = createController(f.emulator)

        controller.showAt(0f, 0f)
        assertTrue(controller.isActive)
        assertTrue(initialRow.isSharedWithSnapshot)

        f.feed("\u001b[2J\u001b[Hworld")
        assertEquals("world", f.rowText(0))
        assertEquals("hello", controller.selectedText)

        assertTrue(controller.hide())
        assertFalse(controller.isActive)
        assertFalse(initialRow.isSharedWithSnapshot)
        assertEquals(1, host.releasedCount)
        assertEquals(1, handles.first.hideCount)
        assertEquals(1, handles.second.hideCount)
    }

    @Test
    fun hideAfterEmulatorSwappedOrDetached_releasesOriginalFrozenSnapshot() {
        val f1 = EmulatorFixture()
        val f2 = EmulatorFixture()
        f1.feed("one")
        val originalRow = f1.emulator.screen.getRowForRead(0)
        val (controller, host, _) = createController(f1.emulator)

        controller.showAt(0f, 0f)
        assertTrue(originalRow.isSharedWithSnapshot)
        f1.feed("two")
        assertEquals("onetwo", f1.rowText(0))
        assertEquals("one", controller.selectedText)

        host.emulator = f2.emulator
        host.emulator = null

        assertTrue(controller.hide())
        assertFalse(controller.isActive)
        assertFalse(originalRow.isSharedWithSnapshot)
        assertEquals(1, host.releasedCount)
        assertEquals("", f2.rowText(0))
    }

    @Test
    fun onDestroyActionMode_immediatelyAfterShow_exitsSelectionAndReleasesSnapshot() {
        val f = EmulatorFixture()
        f.feed("abc")
        val initialRow = f.emulator.screen.getRowForRead(0)
        val (controller, host, handles) = createController(f.emulator)

        controller.showAt(0f, 0f)
        f.feed("def")
        assertEquals("abcdef", f.rowText(0))
        assertEquals("abc", controller.selectedText)

        host.lastActionModeCallback?.onDestroyActionMode(null)

        assertEquals(1, host.stopCount)
        assertEquals(1, host.releasedCount)
        assertFalse(controller.isActive)
        assertFalse(initialRow.isSharedWithSnapshot)
        assertEquals(1, handles.first.hideCount)
        assertEquals(1, handles.second.hideCount)
    }

    @Test
    fun onTouchModeChangedFalse_immediatelyAfterShow_exitsSelectionAndReleasesSnapshot() {
        val f = EmulatorFixture()
        f.feed("xyz")
        val initialRow = f.emulator.screen.getRowForRead(0)
        val (controller, host, _) = createController(f.emulator)

        controller.showAt(0f, 0f)
        f.feed("123")
        assertEquals("xyz123", f.rowText(0))
        assertEquals("xyz", controller.selectedText)

        controller.onTouchModeChanged(false)

        assertFalse(controller.isActive)
        assertFalse(initialRow.isSharedWithSnapshot)
        assertEquals(1, host.releasedCount)
    }

    @Test
    fun dragEndHandleOntoWideChar_selectsExactWideCharWithoutNeighbor() {
        val f = EmulatorFixture()
        f.feed("中a")
        val (controller, _, handles) = createController(f.emulator)

        controller.showAt(0f, 0f)
        controller.updatePosition(handles.second, 10, 0)

        val sel = IntArray(4)
        controller.getSelectors(sel)
        assertEquals(0, sel[2])
        assertEquals(1, sel[3])
        assertEquals("中", controller.selectedText)

        controller.updatePosition(handles.second, 0, 0)
        controller.getSelectors(sel)
        assertEquals(1, sel[3])
        assertEquals("中", controller.selectedText)
    }

    @Test
    fun longPressOnSpaceBetweenWords_doesNotMergeAdjacentWords() {
        val f = EmulatorFixture()
        f.feed("hello wor")
        val (controller, _, _) = createController(f.emulator)

        controller.showAt(50f, 0f)

        val sel = IntArray(4)
        controller.getSelectors(sel)
        assertEquals(5, sel[2])
        assertEquals(5, sel[3])
        assertEquals("", controller.selectedText)
    }

    @Test
    fun longPressOnWord_expandsOnlyWithinWord() {
        val f = EmulatorFixture()
        f.feed("hello wor")
        val (controller, _, _) = createController(f.emulator)

        controller.showAt(20f, 0f)

        val sel = IntArray(4)
        controller.getSelectors(sel)
        assertEquals(0, sel[2])
        assertEquals(4, sel[3])
        assertEquals("hello", controller.selectedText)
    }

    @Test
    fun showAt_whenEmulatorIsNull_doesNotActivateSelectionOrShowHandles() {
        val (controller, host, handles) = createController(null)

        controller.showAt(10f, 10f)

        assertFalse(controller.isActive)
        assertNull(controller.frozenSnapshot)
        assertNull(host.lastActionModeCallback)
        assertEquals(0, host.capturingCount)
        assertEquals(0, handles.first.visibleCount)
        assertEquals(0, handles.second.visibleCount)
    }

    @Test
    fun showAt_invokesCapturingCallbackBeforeFreezingSnapshot() {
        val f = EmulatorFixture(columns = 10, rows = 2, maxTranscriptRows = 10)
        f.feed("a\r\nb\r\nc\r\nd")
        val (controller, host, _) = createController(f.emulator)

        controller.showAt(0f, 0f)

        assertTrue(controller.isActive)
        assertEquals(1, host.capturingCount)
        assertEquals(0, f.emulator.takeScrollCounter())
        controller.hide()
    }
}

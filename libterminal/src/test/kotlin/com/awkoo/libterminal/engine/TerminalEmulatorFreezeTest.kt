package com.awkoo.libterminal.engine

import com.awkoo.libterminal.view.render.TerminalRenderSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 写时复制（CoW）屏幕快照冻结：选择文本期间后端持续完整解析协议，
 * 冻结视图保持静止与一致，释放后恢复就地写入。
 */
class TerminalEmulatorFreezeTest {

    private val f = EmulatorFixture(columns = 12, rows = 4, maxTranscriptRows = 10)

    private val emulator get() = f.emulator

    @Test
    fun frozenSnapshot_isolatesSubsequentAppendsAndClears() {
        f.feed("hello")
        val snapshot = emulator.captureFrozenSnapshot()
        try {
            f.feed(" world\u001b[2J\u001b[Hcleared")

            assertEquals("cleared", f.rowText(0))
            assertEquals("hello", snapshot.buffer.getSelectedText(0, 0, 11, 0))
            assertEquals("hello", String(snapshot.buffer.getRowForRead(0).textChars()).trimEnd())
        } finally {
            emulator.releaseFrozenSnapshot(snapshot)
        }
    }

    @Test
    fun frozenSnapshot_continuesHandlingProtocolQueriesAndAltScreen() {
        f.feed("main")
        val snapshot = emulator.captureFrozenSnapshot()
        try {
            f.clearWritten()
            f.feed("\u001b[?1049h\u001b[Halt\u001b[6n\u001b[?1049l_done")

            assertTrue("DSR query must be answered immediately while frozen: ${f.writtenText}", f.writtenText.contains("\u001b[1;4R"))
            assertFalse(emulator.isAlternateBufferActive)
            assertEquals("main_done", f.rowText(0))
            assertEquals("main", snapshot.buffer.getSelectedText(0, 0, 11, 0))
            assertFalse(snapshot.isAlternateBufferActive)
        } finally {
            emulator.releaseFrozenSnapshot(snapshot)
        }
    }

    @Test
    fun frozenSnapshot_preservesScrolledOutHistoryRows() {
        for (i in 0 until 8) {
            f.feed("line$i\r\n")
        }
        val snapshot = emulator.captureFrozenSnapshot()
        val expectedTop = -snapshot.buffer.activeTranscriptRows
        val expectedFirstHistory = String(snapshot.buffer.getRowForRead(expectedTop).textChars()).trimEnd()
        val expectedLastVisible = String(snapshot.buffer.getRowForRead(expectedTop + 3).textChars()).trimEnd()
        try {
            for (i in 8 until 25) {
                f.feed("line$i\r\n")
            }

            val renderSnapshot = TerminalRenderSnapshot()
            renderSnapshot.captureFromFrozen(snapshot) { expectedTop }
            assertEquals(expectedFirstHistory, String(renderSnapshot.rowAt(0).textChars()).trimEnd())
            assertEquals(expectedLastVisible, String(renderSnapshot.rowAt(3).textChars()).trimEnd())
            assertEquals("line22", f.rowText(0))
        } finally {
            emulator.releaseFrozenSnapshot(snapshot)
        }
    }

    @Test
    fun releaseFrozenSnapshot_restoresInPlaceRowMutation() {
        f.feed("abc")
        val rowBeforeFreeze = emulator.screen.getRowForRead(0)
        val snapshot = emulator.captureFrozenSnapshot()
        assertTrue(rowBeforeFreeze.isSharedWithSnapshot)

        f.feed("def")
        val rowDuringFreeze = emulator.screen.getRowForRead(0)
        assertNotEquals(rowBeforeFreeze.id, rowDuringFreeze.id)
        assertEquals("abc", String(snapshot.buffer.getRowForRead(0).textChars()).trimEnd())
        assertEquals("abcdef", f.rowText(0))

        emulator.releaseFrozenSnapshot(snapshot)
        assertFalse(rowBeforeFreeze.isSharedWithSnapshot)

        val snapshot2 = emulator.captureFrozenSnapshot()
        assertTrue(rowDuringFreeze.isSharedWithSnapshot)
        emulator.releaseFrozenSnapshot(snapshot2)
        assertFalse(rowDuringFreeze.isSharedWithSnapshot)

        val idBeforeWrite = rowDuringFreeze.id
        f.feed("g")
        assertEquals(idBeforeWrite, emulator.screen.getRowForRead(0).id)
        assertEquals("abcdefg", f.rowText(0))
    }

    @Test
    fun frozenSnapshot_isolatesResizeAndReflow() {
        f.feed("abcdefghijklmno")
        val snapshot = emulator.captureFrozenSnapshot()
        try {
            emulator.resize(6, 4, 10, 20)

            assertEquals(12, snapshot.columns)
            assertEquals("abcdefghijkl", String(snapshot.buffer.getRowForRead(0).textChars()).trimEnd())
            assertEquals("mno", String(snapshot.buffer.getRowForRead(1).textChars()).trimEnd())
            assertEquals(6, emulator.columns)
            assertEquals("abcdef", f.rowText(0))
        } finally {
            emulator.releaseFrozenSnapshot(snapshot)
        }
    }
}

package com.awkoo.libterminal.engine.buffer

import com.awkoo.libterminal.text.TextStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 缓冲区 resize 的三类路径：垂直扩展/收缩（环形窗口平移）、
 * 水平 reflow（内容保序重排）、备用屏不重排（位置保持裁剪补白）。
 */
class TerminalBufferResizeTest {

    private fun fill(buffer: TerminalBuffer, texts: List<String>) {
        texts.forEachIndexed { row, text ->
            text.forEachIndexed { column, c ->
                buffer.setChar(column, row, c.code, TextStyle.NORMAL)
            }
        }
    }

    private fun textsOf(buffer: TerminalBuffer, rows: Int): List<String> =
        (0 until rows).map { String(buffer.getRowForRead(it).textChars()).trimEnd() }

    private fun historyTextsOf(buffer: TerminalBuffer): List<String> =
        (-1 downTo -buffer.activeTranscriptRows).map { String(buffer.getRowForRead(it).textChars()).trimEnd() }

    private fun tracked(vararg packed: Long): LongArray = packed

    @Test
    fun growRows_keepsScreenContentAndAddsBlankRowsAtBottom() {
        val buffer = TerminalBuffer(4, 8, 3)
        fill(buffer, listOf("aaaa", "bbbb", "cccc"))

        buffer.resize(4, 5, 8, tracked(CursorCoord.pack(0, 0).packed), TextStyle.NORMAL, altScreen = false)

        assertEquals(listOf("aaaa", "bbbb", "cccc", "", ""), textsOf(buffer, 5))
        assertEquals(0, buffer.activeTranscriptRows)
    }

    @Test
    fun shrinkRows_dropsTopRowsIntoHistory() {
        val buffer = TerminalBuffer(4, 8, 5)
        fill(buffer, listOf("aaaa", "bbbb", "cccc", "dddd", "eeee"))

        buffer.resize(4, 3, 8, tracked(CursorCoord.pack(0, 0).packed), TextStyle.NORMAL, altScreen = false)

        assertEquals(listOf("cccc", "dddd", "eeee"), textsOf(buffer, 3))
        assertEquals(listOf("bbbb", "aaaa"), historyTextsOf(buffer))
        assertEquals(2, buffer.activeTranscriptRows)
    }

    @Test
    fun growRows_afterScroll_pullsHistoryBackIntoView() {
        val buffer = TerminalBuffer(4, 8, 3)
        fill(buffer, listOf("aaaa", "bbbb", "cccc"))
        buffer.scrollDownOneLine(0, 3, TextStyle.NORMAL)

        buffer.resize(4, 5, 8, tracked(CursorCoord.pack(0, 0).packed), TextStyle.NORMAL, altScreen = false)

        assertEquals(listOf("aaaa", "bbbb", "cccc", "", ""), textsOf(buffer, 5))
        assertEquals(0, buffer.activeTranscriptRows)
    }

    @Test
    fun widenColumn_reflowKeepsContentInOrder() {
        val buffer = TerminalBuffer(10, 10, 5)
        fill(buffer, listOf("abcdefghij"))

        buffer.resize(20, 5, 10, tracked(CursorCoord.pack(0, 0).packed), TextStyle.NORMAL, altScreen = false)

        val rejoined = textsOf(buffer, 5).filter { it.isNotEmpty() }.joinToString("")
        assertEquals("abcdefghij", rejoined)
    }

    @Test
    fun narrowColumn_reflowSplitsLongLineAndKeepsOrder() {
        val buffer = TerminalBuffer(10, 10, 5)
        fill(buffer, listOf("abcdefghij"))

        buffer.resize(4, 5, 10, tracked(CursorCoord.pack(0, 0).packed), TextStyle.NORMAL, altScreen = false)

        assertEquals("abcd", textsOf(buffer, 5)[0])
        val rejoined = textsOf(buffer, 5).filter { it.isNotEmpty() }.joinToString("")
        assertEquals("abcdefghij", rejoined)
    }

    @Test
    fun altScreenResize_neverReflowsKeepsTopAnchor() {
        val buffer = TerminalBuffer(6, 6, 3)
        fill(buffer, listOf("aaaaaa", "bbbbbb", "cccccc"))

        buffer.resize(6, 5, 6, tracked(CursorCoord.pack(0, 0).packed), TextStyle.NORMAL, altScreen = true)

        assertEquals(listOf("aaaaaa", "bbbbbb", "cccccc", "", ""), textsOf(buffer, 5))
    }

    @Test
    fun altScreenResize_widen_truncatesEachRowToNewWidth() {
        val buffer = TerminalBuffer(6, 6, 3)
        fill(buffer, listOf("abcdef", "ghijkl", "mnopqr"))

        buffer.resize(4, 3, 6, tracked(CursorCoord.pack(0, 0).packed), TextStyle.NORMAL, altScreen = true)

        assertEquals(listOf("abcd", "ghij", "mnop"), textsOf(buffer, 3))
    }

    @Test
    fun resize_returnsNonNegativeCursor() {
        val buffer = TerminalBuffer(4, 8, 3)
        fill(buffer, listOf("aaaa", "bbbb", "cccc"))
        val coords = tracked(CursorCoord.pack(1, 1).packed)

        buffer.resize(4, 5, 8, coords, TextStyle.NORMAL, altScreen = false)

        val cursor = CursorCoord(coords[0])
        assert(cursor.col >= 0 && cursor.row >= 0)
    }

    @Test
    fun resize_remapsTrackedCoordsWithContent() {
        val buffer = TerminalBuffer(4, 8, 5)
        fill(buffer, listOf("aaaa", "bbbb", "cccc", "dddd", "eeee"))
        val coords = tracked(CursorCoord.pack(2, 4).packed)

        buffer.resize(4, 3, 8, coords, TextStyle.NORMAL, altScreen = false)

        val coord = CursorCoord(coords[0])
        assertEquals(2, coord.col)
        // 旧第 5 行内容随收缩落到第 3 行，坐标必须跟随内容而非停留在旧行号
        assertEquals(2, coord.row)
    }

    @Test
    fun resize_remapsAllTrackedCoordsIdentically() {
        val buffer = TerminalBuffer(4, 8, 5)
        fill(buffer, listOf("aaaa", "bbbb", "cccc", "dddd", "eeee"))
        val coords = tracked(
            CursorCoord.pack(0, 3).packed,
            CursorCoord.pack(3, 4).packed,
            CursorCoord.pack(1, 2).packed
        )

        buffer.resize(4, 3, 8, coords, TextStyle.NORMAL, altScreen = false)

        assertEquals(1, CursorCoord(coords[0]).row)
        assertEquals(2, CursorCoord(coords[1]).row)
        assertEquals(0, CursorCoord(coords[2]).row)
    }

    @Test
    fun shrink_deductionNeverDropsTrackedRows() {
        val buffer = TerminalBuffer(4, 8, 6)
        fill(buffer, listOf("aaaa", "bbbb", "cccc"))
        val coords = tracked(CursorCoord.pack(1, 1).packed, CursorCoord.pack(0, 5).packed)

        buffer.resize(4, 4, 8, coords, TextStyle.NORMAL, altScreen = false)

        // 末行是跟随坐标：即使其下空白可扣减，该行也不得落入底部舍弃区；
        // 首行随收缩入历史后越出屏幕，按新几何夹取到 0
        assertEquals(0, CursorCoord(coords[0]).row)
        assertEquals(3, CursorCoord(coords[1]).row)
    }

    @Test
    fun reflow_keepsTrailingSpacesUnderCursor() {
        val buffer = TerminalBuffer(10, 10, 5)
        fill(buffer, listOf("ls   "))
        val coords = tracked(CursorCoord.pack(5, 0).packed)

        buffer.resize(4, 5, 10, coords, TextStyle.NORMAL, altScreen = false)

        // 光标覆盖行尾空格，空格须整体保留并断行，光标跟随内容落到续行列尾
        val top = buffer.getRowForRead(0)
        assertEquals("ls  ", String(top.textChars(), 0, top.charLength))
        assertTrue(buffer.getLineWrap(0))
        assertEquals(1, CursorCoord(coords[0]).row)
        assertEquals(1, CursorCoord(coords[0]).col)
    }

    @Test
    fun reflow_placesTrackedCoordsOnUnallocatedBlankRows() {
        val buffer = TerminalBuffer(8, 8, 5)
        fill(buffer, listOf("aaaaaaaa", "bbbbbbbb"))
        buffer.resize(4, 5, 8, tracked(CursorCoord.pack(0, 0).packed), TextStyle.NORMAL, altScreen = false)
        // 首次 reflow 跳过的空白行在新缓冲中保持未分配，坐标落在其上
        val coords = tracked(CursorCoord.pack(3, 4).packed)

        buffer.resize(8, 5, 8, coords, TextStyle.NORMAL, altScreen = false)

        // 未分配空行视同空行：坐标保持列号、随内容流落到第 3 行，不得丢成 (0,0)
        assertEquals(3, CursorCoord(coords[0]).col)
        assertEquals(2, CursorCoord(coords[0]).row)
    }

    @Test
    fun reflow_decrementsPlacedCoordsOnBlankReemitScroll() {
        val buffer = TerminalBuffer(4, 8, 4)
        fill(buffer, listOf("aaaa", "cc", "", "d"))
        val coords = tracked(CursorCoord.pack(0, 1).packed)

        buffer.resize(2, 4, 8, coords, TextStyle.NORMAL, altScreen = false)

        // 空行补偿回放触发的滚动同样使内容整体上移，已落点坐标须随之递减
        assertEquals(0, CursorCoord(coords[0]).col)
        assertEquals(1, CursorCoord(coords[0]).row)
    }
}

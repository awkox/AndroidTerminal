package com.awkoo.libterminal.view.render

import com.awkoo.libterminal.color.SparsePalette
import com.awkoo.libterminal.engine.TerminalCursorStyle
import com.awkoo.libterminal.engine.TerminalEmulator
import com.awkoo.libterminal.engine.buffer.TerminalRow
import com.awkoo.libterminal.text.TextStyle

/**
 * 终端视口渲染快照。
 *
 * 在读锁内将可见行按版本增量同步到私有行缓存，使 Canvas 绘制在锁外无阻塞执行。
 */
internal class TerminalRenderSnapshot {

    private class RowSlot(
        var row: TerminalRow,
        var sourceId: Long = -1L,
        var sourceVersion: Long = -1L
    )

    var rows: Int = 0
    var columns: Int = 0
    var topRow: Int = 0
    var cursorRow: Int = 0
    var cursorCol: Int = 0
    var cursorVisible: Boolean = false
    var cursorStyle: TerminalCursorStyle = TerminalCursorStyle.BAR
    var reverseVideo: Boolean = false
    var textVisible: Boolean = true

    val paletteOverrides: SparsePalette = SparsePalette()
    private var paletteSource: SparsePalette? = null
    private var paletteVersion: Long = -1L

    private var rowSlots: Array<RowSlot?> = emptyArray()

    fun captureFrom(
        emulator: TerminalEmulator,
        topRowProvider: () -> Int
    ) {
        emulator.withReadLock {
            val rowCount = emulator.rows
            val colCount = emulator.columns
            val top = topRowProvider()
            val activeScreen = emulator.screen
            rows = rowCount
            columns = colCount
            topRow = top
            cursorRow = emulator.cursorRow
            cursorCol = emulator.cursorCol
            cursorVisible = emulator.isCursorVisible
            cursorStyle = emulator.cursorStyle
            reverseVideo = emulator.isReverseVideo
            textVisible = emulator.isTextVisible
            syncPalette(emulator.oscColorOverrides)
            ensureRows(rowCount)
            for (i in 0 until rowCount) {
                updateRow(i, activeScreen.getRowForRead(top + i), colCount)
            }
        }
    }

    private fun ensureRows(rowCount: Int) {
        if (rowSlots.size < rowCount) {
            rowSlots = rowSlots.copyOf(rowCount)
        }
    }

    private fun updateRow(viewportIndex: Int, source: TerminalRow, expectedColumns: Int) {
        val existing = rowSlots[viewportIndex]
        if (existing == null) {
            val copy = TerminalRow(expectedColumns, TextStyle.NORMAL)
            copy.copySnapshotFrom(source)
            rowSlots[viewportIndex] = RowSlot(copy, source.id, source.version)
            return
        }
        if (existing.row.columns != expectedColumns) {
            val copy = TerminalRow(expectedColumns, TextStyle.NORMAL)
            copy.copySnapshotFrom(source)
            existing.row = copy
            existing.sourceId = source.id
            existing.sourceVersion = source.version
            return
        }
        if (existing.sourceId != source.id || existing.sourceVersion != source.version) {
            existing.row.copySnapshotFrom(source)
            existing.sourceId = source.id
            existing.sourceVersion = source.version
        }
    }

    fun rowAt(viewportIndex: Int): TerminalRow = rowSlots[viewportIndex]!!.row

    private fun syncPalette(source: SparsePalette) {
        if (paletteSource !== source || paletteVersion != source.version) {
            paletteOverrides.copyFrom(source)
            paletteSource = source
            paletteVersion = source.version
        }
    }
}

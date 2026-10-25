package com.awkoo.libterminal.engine.buffer

import com.awkoo.libterminal.text.TextStyle
import kotlin.math.max
import kotlin.math.min

/**
 * 终端行缓冲区（环形数组）。
 *
 * 管理屏幕可见行和滚动历史，支持文本选择、行列操作、块复制/填充和上下滚动。
 */
internal class TerminalBuffer(
    @JvmField var columns: Int,
    @JvmField var totalRows: Int,
    @JvmField var screenRows: Int
) {
    @JvmField
    var lines: Array<TerminalRow?> = arrayOfNulls(totalRows)

    /**
     * 只读路径用的共享空白行：列宽与当前 mColumns 一致，不写入 mLines。
     *
     * 同一实例会被 [getRowForRead] 返回给多个调用方，调用方只允许读取；
     * 任何写入都会污染全部只读路径。仅在构造时与列宽变化时重建。
     */
    private var sharedBlankRow: TerminalRow = TerminalRow(columns, TextStyle.NORMAL)

    var activeTranscriptRows: Int = 0
        private set

    private var screenFirstRow = 0

    init {
        blockSet(0, 0, columns, screenRows, ' '.code, TextStyle.NORMAL)
    }

    /**
     * 提取指定矩形区域的选中文本。
     *
     * @param joinBackLines 是否将自动换行的续行合并为一行（续行以非换行结束时拼接）
     * @param joinFullLines 是否按整行提取（忽略列范围）
     */
    fun getSelectedText(
        selX1: Int,
        selY1: Int,
        selX2: Int,
        selY2: Int,
        joinBackLines: Boolean = true,
        joinFullLines: Boolean = false
    ): String {
        val y1 = clampRow(selY1)
        val y2 = clampRow(selY2)
        val x1Start = clampColumn(selX1)
        val x2End = clampColumn(selX2) + 1

        val estimatedCapacity = (y2 - y1 + 1).coerceAtLeast(0) * (columns + 1)
        val builder = StringBuilder(estimatedCapacity)

        val columns = columns

        for (row in y1..y2) {
            val x1 = if (row == y1) x1Start else 0
            val x2 = if (row == y2) x2End else columns
            val lineObject = lines[externalToInternalRow(row)]
            if (lineObject == null) {
                if ((!joinBackLines) && row < y2 && row < screenRows - 1) {
                    builder.append('\n')
                }
                continue
            }
            val x1Index = lineObject.startCharOfColumn(x1)
            val x2Index = lineObject.endCharOfColumn(x2 - 1)
            val line = lineObject.textChars()
            val rowLineWrap = getLineWrap(row)
            val lastPrintingCharIndex = if (rowLineWrap && x2 == columns) {
                x2Index - 1
            } else {
                (x2Index - 1 downTo x1Index).firstOrNull { line[it] != ' ' } ?: -1
            }

            val len = lastPrintingCharIndex - x1Index + 1
            if (lastPrintingCharIndex != -1 && len > 0) builder.appendRange(line, x1Index,
                x1Index + len
            )

            val lineFillsWidth = lastPrintingCharIndex == x2Index - 1
            if (
                (!joinBackLines || !rowLineWrap) &&
                (!joinFullLines || !lineFillsWidth) &&
                row < y2 &&
                row < screenRows - 1
            ) {
                builder.append('\n')
            }
        }
        return builder.toString()
    }

    /**
     * 判断外部行的指定列是否是空白格（空格或越界空）。
     *
     * 字符判定收口在 [TerminalRow.isCellBlank]，此处只负责外行号寻址与越界放行。
     */
    fun isCellBlank(column: Int, row: Int): Boolean {
        if (!rowInRange(row)) return true
        return lines[externalToInternalRow(row)]?.isCellBlank(column) ?: true
    }

    val activeRows: Int
        get() = this.activeTranscriptRows + screenRows

    /** 查询类接口允许的外行区间（含端点）。 */
    fun rowInRange(row: Int): Boolean = row in -activeTranscriptRows until screenRows

    /** 把外行号夹取到查询合法域内。 */
    fun clampRow(row: Int): Int = row.coerceIn(-activeTranscriptRows, screenRows - 1)

    /** 把列号夹取到查询合法域内。 */
    fun clampColumn(column: Int): Int = column.coerceIn(0, columns - 1)

    fun externalToInternalRow(externalRow: Int): Int {
        require(externalRow in -this.activeTranscriptRows until screenRows) {
            "extRow=" + externalRow + ", screenRows=" + screenRows + ", activeTranscriptRows=" + this.activeTranscriptRows
        }
        return (screenFirstRow + externalRow).mod(totalRows)
    }

    fun setLineWrap(row: Int) {
        allocateFullLineIfNecessary(externalToInternalRow(row)).markLineWrap()
    }

    fun getLineWrap(row: Int): Boolean {
        if (!rowInRange(row)) return false
        return lines[externalToInternalRow(row)]?.isLineWrap ?: false
    }

    /**
     * 调整缓冲区尺寸，并把跟随坐标就地重映射到新几何坐标。
     *
     * 备用屏不做 reflow，只做位置保持的裁剪与补白；
     * 主屏仅高度变化时执行简单垂直重排，宽度变化时委托给 [TerminalReflower] 执行文本重排。
     *
     * @param trackedCoords 需随内容重排的屏坐标组（[CursorCoord] 打包），必须与本缓冲区同域，
     *  就地写回新几何坐标。保存的光标与当前光标须同组传入：几何变更只映射其一，
     *  未映射坐标在恢复时会与内容错位；随后即被覆盖的坐标（如切屏时即将被 restoreCursor
     *  改写的当前光标）无需参与追踪。收缩裁剪对全部坐标提供保护：
     *  空白扣减只舍弃所有跟随坐标以下的行，任何跟随坐标行都不落入底部舍弃区；
     *  映射结果统一夹取到新几何。
     */
    fun resize(
        newColumns: Int,
        newRows: Int,
        newTotalRows: Int,
        trackedCoords: LongArray,
        currentStyle: TextStyle,
        altScreen: Boolean
    ) {
        if (altScreen) {
            handleAltScreenResize(newColumns, newRows, newTotalRows)
        } else if (newColumns == columns && newRows <= totalRows) {
            handleSimpleVerticalResize(newRows, newTotalRows, trackedCoords, currentStyle)
        } else {
            handleHorizontalResize(newColumns, newRows, newTotalRows, trackedCoords, currentStyle)
        }

        // 统一夹取到新几何：坐标可能落在被舍弃的行（顶部入历史、底部被丢弃）或换行边界列上
        for (t in trackedCoords.indices) {
            val coord = CursorCoord(trackedCoords[t])
            trackedCoords[t] = CursorCoord.pack(
                coord.col.coerceIn(0, newColumns - 1),
                coord.row.coerceIn(0, newRows - 1)
            ).packed
        }
    }

    /**
     * 简单垂直重排：仅行数变化时调整屏幕首行和滚动历史。
     */
    private fun handleSimpleVerticalResize(
        newRows: Int,
        newTotalRows: Int,
        trackedCoords: LongArray,
        currentStyle: TextStyle
    ) {
        // 空白扣减只舍弃所有跟随坐标以下的行：底部舍弃区是末尾连续若干行，
        // 扣减量封顶在最靠下的跟随坐标之下，任何跟随坐标行都不会落入舍弃区
        var lowestTrackedRow = Int.MIN_VALUE
        for (t in trackedCoords.indices) {
            val row = CursorCoord(trackedCoords[t]).row
            if (row > lowestTrackedRow) lowestTrackedRow = row
        }
        var shiftDownOfTopRow = screenRows - newRows
        if (shiftDownOfTopRow in 1..<screenRows) {
            for (i in screenRows - 1 downTo 1) {
                if (lowestTrackedRow >= i) break
                val r = externalToInternalRow(i)
                if (lines[r]?.isBlank ?: true) {
                    if (--shiftDownOfTopRow == 0) break
                }
            }
        } else if (shiftDownOfTopRow < 0) {
            val actualShift = max(shiftDownOfTopRow, -this.activeTranscriptRows)
            if (shiftDownOfTopRow != actualShift) {
                for (i in 0..<actualShift - shiftDownOfTopRow)
                    allocateFullLineIfNecessary((screenFirstRow + screenRows + i) % totalRows)
                        .clear(currentStyle)
                shiftDownOfTopRow = actualShift
            }
        }

        screenFirstRow += shiftDownOfTopRow
        screenFirstRow = if (screenFirstRow < 0) {
            screenFirstRow + totalRows
        } else {
            screenFirstRow % totalRows
        }
        totalRows = newTotalRows
        this.activeTranscriptRows = max(0, this.activeTranscriptRows + shiftDownOfTopRow)
        screenRows = newRows
        for (t in trackedCoords.indices) {
            val coord = CursorCoord(trackedCoords[t])
            trackedCoords[t] = CursorCoord.pack(coord.col, coord.row - shiftDownOfTopRow).packed
        }
    }

    private fun handleHorizontalResize(
        newColumns: Int,
        newRows: Int,
        newTotalRows: Int,
        trackedCoords: LongArray,
        currentStyle: TextStyle
    ) {
        // 保存旧状态
        val oldLines = lines
        val oldActiveTranscriptRows = this.activeTranscriptRows
        val oldScreenFirstRow = screenFirstRow
        val oldScreenRows = screenRows
        val oldTotalRows = totalRows

        // 重新分配新的空缓冲区
        lines = arrayOfNulls(newTotalRows)
        totalRows = newTotalRows
        screenRows = newRows
        screenFirstRow = 0
        this.activeTranscriptRows = 0
        columns = newColumns
        sharedBlankRow = TerminalRow(newColumns, TextStyle.NORMAL)

        // 执行委托排版（Reflow）操作
        TerminalReflower.reflow(
            buffer = this,
            oldLines = oldLines,
            oldTotalRows = oldTotalRows,
            oldScreenRows = oldScreenRows,
            oldScreenFirstRow = oldScreenFirstRow,
            oldActiveTranscriptRows = oldActiveTranscriptRows,
            trackedCoords = trackedCoords,
            currentStyle = currentStyle
        )
    }

    /**
     * 备用屏尺寸调整：顶锚定的位置保持裁剪与补白，不执行 reflow。
     *
     * 备用屏内容归全屏应用所有，应用收到 SIGWINCH 后按原格子差分重绘；
     * 合并或重断行会破坏应用的重绘假设，因此行结构保持不动。
     * 尺寸收缩时保留顶部 rowsToCopy 行、丢弃底部行，与扩展时的补白方向一致。
     */
    private fun handleAltScreenResize(
        newColumns: Int,
        newRows: Int,
        newTotalRows: Int
    ) {
        val oldLines = lines
        val oldScreenFirstRow = screenFirstRow
        val oldScreenRows = screenRows
        val oldTotalRows = totalRows
        val oldColumns = columns

        lines = arrayOfNulls(newTotalRows)
        totalRows = newTotalRows
        screenRows = newRows
        screenFirstRow = 0
        activeTranscriptRows = 0
        columns = newColumns
        sharedBlankRow = TerminalRow(newColumns, TextStyle.NORMAL)

        val rowsToCopy = min(oldScreenRows, newRows)
        for (externalRow in 0 until rowsToCopy) {
            val src = oldLines[(oldScreenFirstRow + externalRow).mod(oldTotalRows)] ?: continue
            lines[externalRow] = if (oldColumns == newColumns) src else copyRowResizing(src, oldColumns, newColumns)
        }

        // 位置保持裁剪：坐标不平移，越出新几何的部分由 resize 尾部统一夹取
    }

    private fun copyRowResizing(src: TerminalRow, oldColumns: Int, newColumns: Int): TerminalRow {
        val dst = TerminalRow(newColumns, TextStyle.NORMAL)
        dst.copyInterval(src, 0, min(oldColumns, newColumns), 0)
        dst.copyLineWrapFrom(src)
        return dst
    }

    private fun blockCopyLinesDown(srcInternal: Int, len: Int) {
        if (len == 0) return
        val totalRows = totalRows

        val start = len - 1
        val lineToBeOverWritten = lines[(srcInternal + start + 1) % totalRows]
        for (i in start downTo 0)
            lines[(srcInternal + i + 1) % totalRows] = lines[(srcInternal + i) % totalRows]
        lines[(srcInternal) % totalRows] = lineToBeOverWritten
    }

    fun scrollDownOneLine(topMargin: Int, bottomMargin: Int, style: TextStyle) {
        require(!(topMargin > bottomMargin - 1 || topMargin < 0 || bottomMargin > screenRows)) {
            "topMargin=$topMargin, bottomMargin=$bottomMargin, screenRows=$screenRows"
        }

        blockCopyLinesDown(screenFirstRow, topMargin)
        val rowsBelowScreen = screenRows - bottomMargin
        if (rowsBelowScreen > 0) blockCopyLinesDown(externalToInternalRow(bottomMargin), rowsBelowScreen)

        screenFirstRow = (screenFirstRow + 1) % totalRows
        if (this.activeTranscriptRows < totalRows - screenRows) this.activeTranscriptRows++

        clearRow(bottomMargin - 1, style)
    }

    /** 把指定外行复位为空白行：行对象存在时就地 [TerminalRow.clear]，否则分配新行。 */
    private fun clearRow(externalRow: Int, style: TextStyle) {
        val internal = externalToInternalRow(externalRow)
        lines[internal]?.clear(style) ?: run { lines[internal] = TerminalRow(columns, style) }
    }

    /** 列区间是否覆盖整行：只有整行搬移才能让行级元数据随行走。 */
    private fun coversWholeRow(left: Int, right: Int): Boolean = left == 0 && right == columns

    /**
     * 把源矩形 [sx, sy, w, h] 的内容按行复制到目标矩形 [dx, dy, w, h]。
     *
     * 源与目标都必须是屏幕内的合法矩形：列区间落在 `[0, columns)`，行区间落在
     * `[0, screenRows)`。滚动历史行（负行号）不参与搬运，与 [blockSet] 的取值域一致。
     */
    fun blockCopy(sx: Int, sy: Int, w: Int, h: Int, dx: Int, dy: Int) {
        require(!(w < 0 || h < 0 || sx < 0 || sx + w > columns || dx < 0 || dx + w > columns ||
                sy < 0 || sy + h > screenRows || dy < 0 || dy + h > screenRows)) {
            "Illegal arguments! blockCopy($sx, $sy, $w, $h, $dx, $dy, $columns, $screenRows)"
        }
        if (w == 0) return
        val copyingUp = sy > dy
        for (y in 0..<h) {
            val y2 = if (copyingUp) y else (h - (y + 1))

            val srcInternal = externalToInternalRow(sy + y2)
            val sourceRow = lines[srcInternal]

            if (sourceRow == null || sourceRow.isBlank) {
                blockSet(dx, dy + y2, w, 1, ' '.code, TextStyle.NORMAL)
            } else {
                val destInternal = externalToInternalRow(dy + y2)
                allocateFullLineIfNecessary(destInternal)
                    .copyInterval(sourceRow, sx, sx + w, dx)
            }
        }
    }

    fun blockSet(sx: Int, sy: Int, w: Int, h: Int, value: Int, style: TextStyle, extendedEffect: Long = 0L) {
        require(!(w < 0 || h < 0 || sx < 0 || sx + w > columns || sy < 0 || sy + h > screenRows)) {
            "Illegal arguments! blockSet($sx, $sy, $w, $h, $value, $columns, $screenRows)"
        }
        for (y in 0..<h) for (x in 0..<w) setChar(sx + x, sy + y, value, style, extendedEffect)
    }

    /**
     * 获取指定外部行的只读行对象。
     *
     * - 若行不在查询合法域内（越界），返回共享空白行。
     * - 若对应槽位为 null（尚未分配），返回共享空白行。
     * - 否则返回已有的 TerminalRow。
     *
     * 注意：此方法**永不**向 lines 写入任何内容，用于纯读路径以消除读时写副作用。
     */
    fun getRowForRead(externalRow: Int): TerminalRow {
        if (!rowInRange(externalRow)) {
            return sharedBlankRow
        }
        val internal = externalToInternalRow(externalRow)
        return lines[internal] ?: sharedBlankRow
    }

    fun allocateFullLineIfNecessary(row: Int): TerminalRow {
        return lines[row] ?: TerminalRow(columns, TextStyle.NORMAL).also { lines[row] = it }
    }

    fun setChar(column: Int, row: Int, codePoint: Int, style: TextStyle, extendedEffect: Long = 0L) {
        require(!(row !in 0..<screenRows || column < 0 || column >= columns)) {
            "TerminalBuffer.setChar(): row=$row, column=$column, screenRows=$screenRows, columns=$columns"
        }
        val row = externalToInternalRow(row)
        allocateFullLineIfNecessary(row).setChar(column, codePoint, style, extendedEffect)
    }

    fun getStyleAt(externalRow: Int, column: Int): TextStyle {
        if (!rowInRange(externalRow) || column !in 0 until columns) return TextStyle.NORMAL
        return lines[externalToInternalRow(externalRow)]?.getStyle(column) ?: TextStyle.NORMAL
    }

    /** 读取指定单元格的扩展特效位；行或列越界、以及该行尚未分配时返回 0。 */
    fun getExtendedEffectAt(externalRow: Int, column: Int): Long {
        if (!rowInRange(externalRow) || column !in 0 until columns) return 0L
        return lines[externalToInternalRow(externalRow)]?.getExtendedEffect(column) ?: 0L
    }

    /**
     * 把列号校准到字符边界，落在宽字符后半格内时按 [align] 吸附。
     *
     * [column] 先夹取到 [0, columns)；[row] 越界或该行无内容时按无数据处理，
     * 返回夹取后的列号。
     */
    fun snapToColumn(row: Int, column: Int, align: SnapAlign): Int {
        val clamped = clampColumn(column)
        if (!rowInRange(row)) return clamped
        return lines[externalToInternalRow(row)]?.snapToColumn(clamped, align) ?: clamped
    }

    fun setOrClearEffect(
        bits: Int,
        setOrClear: Boolean,
        reverse: Boolean,
        rectangular: Boolean,
        leftMargin: Int,
        rightMargin: Int,
        top: Int,
        left: Int,
        bottom: Int,
        right: Int
    ) {
        for (y in top..<bottom) {
            val line = allocateFullLineIfNecessary(externalToInternalRow(y))
            val startOfLine = if (rectangular || y == top) left else leftMargin
            val endOfLine = if (rectangular || y + 1 == bottom) right else rightMargin
            for (x in startOfLine..<endOfLine) {
                line.modifyEffect(x, bits, setOrClear, reverse)
            }
        }
    }

    fun clearTranscript() {
        this.activeTranscriptRows = 0
    }

    /** 在指定行插入空行，并将后续行向下移动 */
    fun insertLines(y: Int, count: Int, bottomMargin: Int, style: TextStyle) {
        shiftLines(y, bottomMargin, count, 0, columns, up = false, style = style)
    }

    /** 删除指定行，并将后续行向上移动填补 */
    fun deleteLines(y: Int, count: Int, bottomMargin: Int, style: TextStyle) {
        shiftLines(y, bottomMargin, count, 0, columns, up = true, style = style)
    }

    /**
     * 在行区间 [top, bottom) 内把内容纵向搬移 [count] 行，腾出的 [count] 行以 [style] 复位。
     *
     * [up] 为 true 时内容整体上移、空行落在区间底部；为 false 时内容整体下移、空行落在区间顶部。
     *
     * 列区间 [left, right) 覆盖整行时以行对象整体搬移，行级元数据（续行标记）随行走；
     * 收窄时只能搬矩形内容，行级元数据不参与。全宽与收窄的分派在此收口，调用方只报意图。
     *
     * 本操作只改区间内容，不推进滚动历史；带滚动历史的滚动见 [scrollRegionOneLine]。
     */
    fun shiftLines(
        top: Int,
        bottom: Int,
        count: Int,
        left: Int,
        right: Int,
        up: Boolean,
        style: TextStyle
    ) {
        val region = bottom - top
        val n = min(count, region)
        if (n <= 0) return

        if (!coversWholeRow(left, right)) {
            val width = right - left
            val moved = region - n
            if (moved > 0) {
                if (up) blockCopy(left, top + n, width, moved, left, top)
                else blockCopy(left, top, width, moved, left, top + n)
            }
            blockSet(left, if (up) top + moved else top, width, n, ' '.code, style)
            return
        }

        if (up) repeat(n) { rotateRegionUp(top, bottom) } else repeat(n) { rotateRegionDown(top, bottom) }
        val firstBlank = if (up) bottom - n else top
        for (k in 0 until n) clearRow(firstBlank + k, style)
    }

    /**
     * 把行区间 [top, bottom) 的内容整体上移一行并推进滚动历史。
     *
     * 列区间收窄时该区间不参与滚动历史，退化为 [shiftLines] 的单行上移。
     */
    fun scrollRegionOneLine(top: Int, bottom: Int, left: Int, right: Int, style: TextStyle) {
        if (coversWholeRow(left, right)) scrollDownOneLine(top, bottom, style)
        else shiftLines(top, bottom, 1, left, right, up = true, style = style)
    }

    /** 把外行区间 [top, bottom) 的行对象整体上移一行，区间首行绕回区间末行。 */
    private fun rotateRegionUp(top: Int, bottom: Int) {
        val head = lines[externalToInternalRow(top)]
        for (row in top until bottom - 1) {
            lines[externalToInternalRow(row)] = lines[externalToInternalRow(row + 1)]
        }
        lines[externalToInternalRow(bottom - 1)] = head
    }

    /** 把外行区间 [top, bottom) 的行对象整体下移一行，区间末行绕回区间首行。 */
    private fun rotateRegionDown(top: Int, bottom: Int) {
        val tail = lines[externalToInternalRow(bottom - 1)]
        for (row in bottom - 1 downTo top + 1) {
            lines[externalToInternalRow(row)] = lines[externalToInternalRow(row - 1)]
        }
        lines[externalToInternalRow(top)] = tail
    }

    /** 在指定列插入空白字符，并将后续字符向右平移 */
    fun insertColumns(x: Int, y: Int, count: Int, rows: Int, rightMargin: Int, style: TextStyle) {
        if (count <= 0) return
        val charsToMove = rightMargin - x - count
        if (charsToMove > 0) blockCopy(x, y, charsToMove, rows, x + count, y)
        blockSet(x, y, count, rows, ' '.code, style)
    }

    /** 删除指定列的字符，并将后续字符向左平移填补 */
    fun deleteColumns(x: Int, y: Int, count: Int, rows: Int, rightMargin: Int, style: TextStyle) {
        if (count <= 0) return
        val charsToMove = rightMargin - x - count
        if (charsToMove > 0) blockCopy(x + count, y, charsToMove, rows, x, y)
        blockSet(x + charsToMove, y, count, rows, ' '.code, style)
    }
}
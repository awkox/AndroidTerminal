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
    @JvmField var mColumns: Int,
    @JvmField var mTotalRows: Int,
    @JvmField var mScreenRows: Int
) {
    @JvmField
    var mLines: Array<TerminalRow?> = arrayOfNulls(mTotalRows)

    /**
     * 只读路径用的共享空白行：列宽与当前 mColumns 一致，不写入 mLines。
     *
     * 同一实例会被 [getRowForRead] 返回给多个调用方，调用方只允许读取；
     * 任何写入都会污染全部只读路径。仅在构造时与列宽变化时重建。
     */
    private var mSharedBlankRow: TerminalRow = TerminalRow(mColumns, TextStyle.NORMAL)

    var activeTranscriptRows: Int = 0
        private set

    private var mScreenFirstRow = 0

    init {
        blockSet(0, 0, mColumns, mScreenRows, ' '.code, TextStyle.NORMAL)
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

        val estimatedCapacity = (y2 - y1 + 1).coerceAtLeast(0) * (mColumns + 1)
        val builder = StringBuilder(estimatedCapacity)

        val columns = mColumns

        for (row in y1..y2) {
            val x1 = if (row == y1) x1Start else 0
            val x2 = if (row == y2) x2End else columns
            val lineObject = mLines[externalToInternalRow(row)]
            if (lineObject == null) {
                if ((!joinBackLines) && row < y2 && row < mScreenRows - 1) {
                    builder.append('\n')
                }
                continue
            }
            val x1Index = lineObject.findStartOfColumn(x1)
            var x2Index =
                if (x2 < mColumns) lineObject.findStartOfColumn(x2) else lineObject.mSpaceUsed
            if (x2Index == x1Index && x2 < mColumns) {
                x2Index = lineObject.findStartOfColumn(x2 + 1)
            }
            val line = lineObject.mText
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
                row < mScreenRows - 1
            ) {
                builder.append('\n')
            }
        }
        return builder.toString()
    }

    /**
     * 判断外部行的指定列是否是空白格（空格或越界空）。
     *
     * 与 [getSelectedText] 单格选择"是否为空"的语义保持一致，
     * 但在 [TextSelectionCursorController] 的单词扩展中避免每次构建字符串的开销，
     * 直接扫描 char 数组判断。
     */
    fun isCellBlank(column: Int, row: Int): Boolean {
        if (column !in 0 until mColumns || !rowInRange(row)) return true
        val lineObject = mLines[externalToInternalRow(row)] ?: return true
        var x1 = lineObject.findStartOfColumn(column)
        var x2 = lineObject.findStartOfColumn(column + 1)
        if (x2 == x1) {
            x2 = lineObject.findStartOfColumn(column + 2)
        }
        x2 = min(x2, lineObject.mSpaceUsed)
        val line = lineObject.mText
        for (i in x1 until x2) {
            if (line[i] != ' ') return false
        }
        return true
    }

    val activeRows: Int
        get() = this.activeTranscriptRows + mScreenRows

    /** 查询类接口允许的外行区间（含端点）。 */
    fun rowInRange(row: Int): Boolean = row in -activeTranscriptRows until mScreenRows

    /** 把外行号夹取到查询合法域内。 */
    fun clampRow(row: Int): Int = row.coerceIn(-activeTranscriptRows, mScreenRows - 1)

    /** 把列号夹取到查询合法域内。 */
    fun clampColumn(column: Int): Int = column.coerceIn(0, mColumns - 1)

    fun externalToInternalRow(externalRow: Int): Int {
        require(externalRow in -this.activeTranscriptRows until mScreenRows) {
            "extRow=" + externalRow + ", mScreenRows=" + mScreenRows + ", mActiveTranscriptRows=" + this.activeTranscriptRows
        }
        return (mScreenFirstRow + externalRow).mod(mTotalRows)
    }

    fun setLineWrap(row: Int) {
        allocateFullLineIfNecessary(externalToInternalRow(row)).mLineWrap = true
    }

    fun getLineWrap(row: Int): Boolean {
        if (!rowInRange(row)) return false
        return mLines[externalToInternalRow(row)]?.mLineWrap ?: false
    }

    fun clearLineWrap(row: Int) {
        mLines[externalToInternalRow(row)]?.mLineWrap = false
    }

    /**
     * 调整缓冲区尺寸。
     *
     * 仅高度变化时执行简单垂直重排；宽度变化时委托给 [TerminalReflower] 执行文本重排。
     */
    fun resize(
        newColumns: Int,
        newRows: Int,
        newTotalRows: Int,
        cursor: CursorCoord,
        currentStyle: TextStyle,
        altScreen: Boolean
    ): CursorCoord {
        val newCursor = if (newColumns == mColumns && newRows <= mTotalRows) {
            handleSimpleVerticalResize(newRows, newTotalRows, cursor, currentStyle, altScreen)
        } else {
            handleHorizontalResize(newColumns, newRows, newTotalRows, cursor, currentStyle)
        }

        // 统一处理越界光标防护
        return CursorCoord.pack(max(0, newCursor.col), max(0, newCursor.row))
    }

    /**
     * 简单垂直重排：仅行数变化时调整屏幕首行和滚动历史。
     */
    private fun handleSimpleVerticalResize(
        newRows: Int,
        newTotalRows: Int,
        cursor: CursorCoord,
        currentStyle: TextStyle,
        altScreen: Boolean
    ): CursorCoord {
        var cursorRow = cursor.row
        var shiftDownOfTopRow = mScreenRows - newRows
        if (shiftDownOfTopRow in 1..<mScreenRows) {
            for (i in mScreenRows - 1 downTo 1) {
                if (cursorRow >= i) break
                val r = externalToInternalRow(i)
                if (mLines[r]?.isBlank ?: true) {
                    if (--shiftDownOfTopRow == 0) break
                }
            }
        } else if (shiftDownOfTopRow < 0) {
            val actualShift = max(shiftDownOfTopRow, -this.activeTranscriptRows)
            if (shiftDownOfTopRow != actualShift) {
                for (i in 0..<actualShift - shiftDownOfTopRow)
                    allocateFullLineIfNecessary((mScreenFirstRow + mScreenRows + i) % mTotalRows)
                        .clear(currentStyle)
                shiftDownOfTopRow = actualShift
            }
        }

        mScreenFirstRow += shiftDownOfTopRow
        mScreenFirstRow = if (mScreenFirstRow < 0) {
            mScreenFirstRow + mTotalRows
        } else {
            mScreenFirstRow % mTotalRows
        }
        mTotalRows = newTotalRows
        this.activeTranscriptRows =
            if (altScreen) 0 else max(0, this.activeTranscriptRows + shiftDownOfTopRow)
        cursorRow -= shiftDownOfTopRow
        mScreenRows = newRows
        return CursorCoord.pack(cursor.col, cursorRow)
    }

    private fun handleHorizontalResize(
        newColumns: Int,
        newRows: Int,
        newTotalRows: Int,
        cursor: CursorCoord,
        currentStyle: TextStyle
    ): CursorCoord {
        // 保存旧状态
        val oldLines = mLines
        val oldActiveTranscriptRows = this.activeTranscriptRows
        val oldScreenFirstRow = mScreenFirstRow
        val oldScreenRows = mScreenRows
        val oldTotalRows = mTotalRows

        // 重新分配新的空缓冲区
        mLines = arrayOfNulls(newTotalRows)
        mTotalRows = newTotalRows
        mScreenRows = newRows
        mScreenFirstRow = 0
        this.activeTranscriptRows = 0
        mColumns = newColumns
        mSharedBlankRow = TerminalRow(newColumns, TextStyle.NORMAL)

        // 执行委托排版（Reflow）操作
        return TerminalReflower.reflow(
            buffer = this,
            oldLines = oldLines,
            oldTotalRows = oldTotalRows,
            oldScreenRows = oldScreenRows,
            oldScreenFirstRow = oldScreenFirstRow,
            oldActiveTranscriptRows = oldActiveTranscriptRows,
            oldCursorColumn = cursor.col,
            oldCursorRow = cursor.row,
            currentStyle = currentStyle
        )
    }

    private fun blockCopyLinesDown(srcInternal: Int, len: Int) {
        if (len == 0) return
        val totalRows = mTotalRows

        val start = len - 1
        val lineToBeOverWritten = mLines[(srcInternal + start + 1) % totalRows]
        for (i in start downTo 0)
            mLines[(srcInternal + i + 1) % totalRows] = mLines[(srcInternal + i) % totalRows]
        mLines[(srcInternal) % totalRows] = lineToBeOverWritten
    }

    fun scrollDownOneLine(topMargin: Int, bottomMargin: Int, style: TextStyle) {
        require(!(topMargin > bottomMargin - 1 || topMargin < 0 || bottomMargin > mScreenRows)) {
            "topMargin=$topMargin, bottomMargin=$bottomMargin, mScreenRows=$mScreenRows"
        }

        blockCopyLinesDown(mScreenFirstRow, topMargin)
        val rowsBelowScreen = mScreenRows - bottomMargin
        if (rowsBelowScreen > 0) blockCopyLinesDown(externalToInternalRow(bottomMargin), rowsBelowScreen)

        mScreenFirstRow = (mScreenFirstRow + 1) % mTotalRows
        if (this.activeTranscriptRows < mTotalRows - mScreenRows) this.activeTranscriptRows++

        val blankRow = externalToInternalRow(bottomMargin - 1)
        mLines[blankRow]?.clear(style) ?: run { mLines[blankRow] = TerminalRow(mColumns, style) }
    }

    fun blockCopy(sx: Int, sy: Int, w: Int, h: Int, dx: Int, dy: Int) {
        if (w == 0) return
        val copyingUp = sy > dy
        for (y in 0..<h) {
            val y2 = if (copyingUp) y else (h - (y + 1))

            val srcInternal = externalToInternalRow(sy + y2)
            val sourceRow = mLines[srcInternal]

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
        require(!(w < 0 || h < 0 || sx < 0 || sx + w > mColumns || sy < 0 || sy + h > mScreenRows)) {
            "Illegal arguments! blockSet($sx, $sy, $w, $h, $value, $mColumns, $mScreenRows)"
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
     * 注意：此方法**永不**向 mLines 写入任何内容，用于纯读路径以消除读时写副作用。
     */
    fun getRowForRead(externalRow: Int): TerminalRow {
        if (!rowInRange(externalRow)) {
            return mSharedBlankRow
        }
        val internal = externalToInternalRow(externalRow)
        return mLines[internal] ?: mSharedBlankRow
    }

    fun allocateFullLineIfNecessary(row: Int): TerminalRow {
        return mLines[row] ?: TerminalRow(mColumns, TextStyle.NORMAL).also { mLines[row] = it }
    }

    fun setChar(column: Int, row: Int, codePoint: Int, style: TextStyle, extendedEffect: Long = 0L) {
        require(!(row !in 0..<mScreenRows || column < 0 || column >= mColumns)) {
            "TerminalBuffer.setChar(): row=$row, column=$column, mScreenRows=$mScreenRows, mColumns=$mColumns"
        }
        val row = externalToInternalRow(row)
        allocateFullLineIfNecessary(row).setChar(column, codePoint, style, extendedEffect)
    }

    fun getStyleAt(externalRow: Int, column: Int): TextStyle {
        if (!rowInRange(externalRow) || column !in 0 until mColumns) return TextStyle.NORMAL
        return mLines[externalToInternalRow(externalRow)]?.getStyle(column) ?: TextStyle.NORMAL
    }

    /**
     * 把列号校准到字符边界：落在宽字符后半格内的列号推进到该字符之后。
     *
     * [column] 先夹取到 [0, mColumns)；[row] 越界或该行无内容时按无数据处理，
     * 返回夹取后的列号。
     */
    fun snapToColumn(row: Int, column: Int): Int {
        val clamped = clampColumn(column)
        if (!rowInRange(row)) return clamped
        return mLines[externalToInternalRow(row)]?.snapToColumn(clamped) ?: clamped
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
        // 仅操作主样式槽（偶数索引）的 effect 位
        // 扩展特效槽（奇数索引）由 setChar 写入时清零
        val effectMask = TextStyle.EFFECT_MASK
        for (y in top..<bottom) {
            val line = allocateFullLineIfNecessary(externalToInternalRow(y))
            val startOfLine = if (rectangular || y == top) left else leftMargin
            val endOfLine = if (rectangular || y + 1 == bottom) right else rightMargin
            for (x in startOfLine..<endOfLine) {
                val raw = line.getRawStyle(x)
                val effect = (raw and effectMask).toInt()
                val newEffect = when {
                    reverse -> (effect and bits.inv()) or (bits and effect.inv())
                    setOrClear -> effect or bits
                    else -> effect and bits.inv()
                }

                line.setRawStyle(x, (raw and effectMask.inv()) or (newEffect.toLong() and effectMask))

                // 主下划线标志位如果发生由开到关的变化，
                // 必须同步清空奇数槽的下划线样式
                val wasUnderline = (effect and TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE) != 0
                val isUnderline = (newEffect and TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE) != 0
                if (wasUnderline && !isUnderline) {
                    val ext = line.getExtendedEffect(x)
                    // 仅抹除低 3 位的 style，保留真彩色扩展位，避免其他状态撕裂
                    line.setExtendedEffect(x, ext and TextStyle.EXT_UNDERLINE_STYLE_MASK.inv())
                }
            }
        }
    }

    fun clearTranscript() {
        this.activeTranscriptRows = 0
    }

    /** 在指定行插入空行，并将后续行向下移动 */
    fun insertLines(y: Int, count: Int, bottomMargin: Int, style: TextStyle) {
        if (count <= 0) return
        val linesToMove = bottomMargin - y - count
        if (linesToMove > 0) blockCopy(0, y, mColumns, linesToMove, 0, y + count)
        blockSet(0, y, mColumns, count, ' '.code, style)
    }

    /** 删除指定行，并将后续行向上移动填补 */
    fun deleteLines(y: Int, count: Int, bottomMargin: Int, style: TextStyle) {
        if (count <= 0) return
        val linesToMove = bottomMargin - y - count
        if (linesToMove > 0) blockCopy(0, y + count, mColumns, linesToMove, 0, y)
        blockSet(0, y + linesToMove, mColumns, count, ' '.code, style)
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
package com.awkoo.terminal.core

import kotlin.math.max

/**
 * 终端行缓冲区（环形数组）。
 *
 * 管理屏幕可见行和滚动历史，支持文本选择、行列操作、块复制/填充和上下滚动。
 */
class TerminalBuffer(
    @JvmField var mColumns: Int,
    @JvmField var mTotalRows: Int,
    @JvmField var mScreenRows: Int
) {
    @JvmField
    var mLines: Array<TerminalRow?> = arrayOfNulls(mTotalRows)

    var activeTranscriptRows: Int = 0
        private set

    private var mScreenFirstRow = 0

    init {
        blockSet(0, 0, mColumns, mScreenRows, ' '.code, TextStyle.NORMAL)
    }

    fun getSelectedText(selX1: Int, selY1: Int, selX2: Int, selY2: Int): String {
        return getSelectedText(selX1, selY1, selX2, selY2, true)
    }

    fun getSelectedText(
        selX1: Int,
        selY1: Int,
        selX2: Int,
        selY2: Int,
        joinBackLines: Boolean
    ): String {
        return getSelectedText(selX1, selY1, selX2, selY2, joinBackLines, false)
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
        joinBackLines: Boolean,
        joinFullLines: Boolean
    ): String {
        var selY1 = selY1
        var selY2 = selY2

        val estimatedCapacity = (selY2 - selY1 + 1) * (mColumns + 1)
        val builder = StringBuilder(estimatedCapacity)

        val columns = mColumns

        if (selY1 < -this.activeTranscriptRows) selY1 = -this.activeTranscriptRows
        if (selY2 >= mScreenRows) selY2 = mScreenRows - 1

        for (row in selY1..selY2) {
            val x1 = if (row == selY1) selX1 else 0
            var x2: Int
            if (row == selY2) {
                x2 = selX2 + 1
                if (x2 > columns) x2 = columns
            } else {
                x2 = columns
            }
            val lineObject = mLines[externalToInternalRow(row)]
            if (lineObject == null) {
                if ((!joinBackLines) && row < selY2 && row < mScreenRows - 1) {
                    builder.append('\n')
                }
                continue
            }
            val x1Index = lineObject.findStartOfColumn(x1)
            var x2Index =
                if (x2 < mColumns) lineObject.findStartOfColumn(x2) else lineObject.mSpaceUsed
            if (x2Index == x1Index) {
                x2Index = lineObject.findStartOfColumn(x2 + 1)
            }
            val line = lineObject.mText
            var lastPrintingCharIndex = -1
            var i: Int
            val rowLineWrap = getLineWrap(row)
            if (rowLineWrap && x2 == columns) {
                lastPrintingCharIndex = x2Index - 1
            } else {
                i = x1Index
                while (i < x2Index) {
                    val c = line[i]
                    if (c != ' ') lastPrintingCharIndex = i
                    ++i
                }
            }

            val len = lastPrintingCharIndex - x1Index + 1
            if (lastPrintingCharIndex != -1 && len > 0) builder.appendRange(line, x1Index,
                x1Index + len
            )

            val lineFillsWidth = lastPrintingCharIndex == x2Index - 1
            if (
                (!joinBackLines || !rowLineWrap) &&
                (!joinFullLines || !lineFillsWidth) &&
                row < selY2 &&
                row < mScreenRows - 1
            ) {
                builder.append('\n')
            }
        }
        return builder.toString()
    }

    val activeRows: Int
        get() = this.activeTranscriptRows + mScreenRows

    fun externalToInternalRow(externalRow: Int): Int {
        require(!(externalRow < -this.activeTranscriptRows || externalRow > mScreenRows)) {
            "extRow=" + externalRow + ", mScreenRows=" + mScreenRows + ", mActiveTranscriptRows=" + this.activeTranscriptRows
        }
        return (mScreenFirstRow + externalRow).mod(mTotalRows)
    }

    fun setLineWrap(row: Int) {
        allocateFullLineIfNecessary(externalToInternalRow(row)).mLineWrap = true
    }

    fun getLineWrap(row: Int): Boolean {
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
        cursor: IntArray,
        currentStyle: TextStyle,
        altScreen: Boolean
    ) {
        if (newColumns == mColumns && newRows <= mTotalRows) {
            handleSimpleVerticalResize(newRows, newTotalRows, cursor, currentStyle, altScreen)
        } else {
            handleHorizontalResize(newColumns, newRows, newTotalRows, cursor, currentStyle)
        }

        // 统一处理越界光标防护
        if (cursor[0] < 0 || cursor[1] < 0) {
            cursor[1] = 0
            cursor[0] = 0
        }
    }

    /**
     * 简单垂直重排：仅行数变化时调整屏幕首行和滚动历史。
     */
    private fun handleSimpleVerticalResize(
        newRows: Int,
        newTotalRows: Int,
        cursor: IntArray,
        currentStyle: TextStyle,
        altScreen: Boolean
    ) {
        var shiftDownOfTopRow = mScreenRows - newRows
        if (shiftDownOfTopRow in 1..<mScreenRows) {
            for (i in mScreenRows - 1 downTo 1) {
                if (cursor[1] >= i) break
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
        cursor[1] -= shiftDownOfTopRow
        mScreenRows = newRows
    }

    private fun handleHorizontalResize(
        newColumns: Int,
        newRows: Int,
        newTotalRows: Int,
        cursor: IntArray,
        currentStyle: TextStyle
    ) {
        // 保存旧状态
        val oldLines = mLines
        val oldActiveTranscriptRows = this.activeTranscriptRows
        val oldScreenFirstRow = mScreenFirstRow
        val oldScreenRows = mScreenRows
        val oldTotalRows = mTotalRows
        val oldCursorColumn = cursor[0]
        val oldCursorRow = cursor[1]

        // 重新分配新的空缓冲区
        mLines = arrayOfNulls(newTotalRows)
        mTotalRows = newTotalRows
        mScreenRows = newRows
        mScreenFirstRow = 0
        this.activeTranscriptRows = 0
        mColumns = newColumns

        // 执行委托排版（Reflow）操作
        val (newCursorColumn, newCursorRow) = TerminalReflower.reflow(
            buffer = this,
            oldLines = oldLines,
            oldTotalRows = oldTotalRows,
            oldScreenRows = oldScreenRows,
            oldScreenFirstRow = oldScreenFirstRow,
            oldActiveTranscriptRows = oldActiveTranscriptRows,
            oldCursorColumn = oldCursorColumn,
            oldCursorRow = oldCursorRow,
            currentStyle = currentStyle
        )

        cursor[0] = newCursorColumn
        cursor[1] = newCursorRow
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
        blockCopyLinesDown(externalToInternalRow(bottomMargin), mScreenRows - bottomMargin)

        mScreenFirstRow = (mScreenFirstRow + 1) % mTotalRows
        if (this.activeTranscriptRows < mTotalRows - mScreenRows) this.activeTranscriptRows++

        val blankRow = externalToInternalRow(bottomMargin - 1)
        mLines[blankRow]?.clear(style) ?: { mLines[blankRow] = TerminalRow(mColumns, style) }()
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
        require(!(sx < 0 || sx + w > mColumns || sy < 0 || sy + h > mScreenRows)) {
            "Illegal arguments! blockSet($sx, $sy, $w, $h, $value, $mColumns, $mScreenRows)"
        }
        for (y in 0..<h) for (x in 0..<w) setChar(sx + x, sy + y, value, style, extendedEffect)
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
        return allocateFullLineIfNecessary(externalToInternalRow(externalRow)).getStyle(column)
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
            }
        }
    }

    fun clearTranscript() {
        this.activeTranscriptRows = 0
    }
}
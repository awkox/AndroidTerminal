package com.awkoo.libterminal.engine.buffer

import com.awkoo.libterminal.text.TextStyle

/**
 * 专门处理终端尺寸发生水平变化时的复杂文本重排（Reflow）操作。
 */
internal object TerminalReflower {

    /**
     * 执行文本重排：将旧布局的行内容重新填充到新宽度的缓冲区中。
     *
     * 遍历旧布局的每一行，处理行尾空格保留（带自定义背景色的空格不会被截断）、
     * 宽字符跨行边界、组合字符偏移、跟随坐标追踪等边界情况。
     *
     * 空行（含未分配的 null 行）在无未落点坐标时跳过；其上有坐标时视同内容行参与落点，
     * 坐标保持列号、随内容流落到新行——丢弃坐标所在行会把坐标压成 (0,0) 与内容脱锚。
     *
     * @param trackedCoords 需随内容重排的旧布局坐标（[CursorCoord] 打包），
     *  与被重排内容同域，按内容落点就地写回新布局坐标，逐个追踪、互不影响；
     *  行不在遍历域内时写回 (-1, -1)，由调用方统一夹取。
     */
    fun reflow(
        buffer: TerminalBuffer,
        oldLines: Array<TerminalRow?>,
        oldTotalRows: Int,
        oldScreenRows: Int,
        oldScreenFirstRow: Int,
        oldActiveTranscriptRows: Int,
        trackedCoords: LongArray,
        currentStyle: TextStyle
    ) {
        val trackedCount = trackedCoords.size
        val trackedOldRows = IntArray(trackedCount)
        val trackedOldCols = IntArray(trackedCount)
        for (t in 0 until trackedCount) {
            val coord = CursorCoord(trackedCoords[t])
            trackedOldRows[t] = coord.row
            trackedOldCols[t] = coord.col
        }
        val trackedNewRows = IntArray(trackedCount) { -1 }
        val trackedNewCols = IntArray(trackedCount) { -1 }
        val placed = BooleanArray(trackedCount)

        var currentOutputExternalRow = 0
        var currentOutputExternalColumn = 0

        /** 输出推进一行：内容整体上移时已落点坐标随之递减，列回行首。 */
        fun advanceOutputRow() {
            if (currentOutputExternalRow == buffer.screenRows - 1) {
                decrementPlacedRows(trackedNewRows, placed)
                buffer.scrollDownOneLine(0, buffer.screenRows, currentStyle)
            } else {
                currentOutputExternalRow++
            }
            currentOutputExternalColumn = 0
        }

        var skippedBlankLines = 0
        for (externalOldRow in -oldActiveTranscriptRows..<oldScreenRows) {
            // 将外部行号转换为环形缓冲区的内部索引
            val internalOldRow = (oldScreenFirstRow + externalOldRow).mod(oldTotalRows)

            val oldLine: TerminalRow? = oldLines[internalOldRow]
            // 跳过空行（未分配行视为空行），但未落点坐标所在行即使为空也不跳过
            if ((oldLine == null || oldLine.isBlank) && !anyUnplacedOnRow(placed, trackedOldRows, externalOldRow)) {
                skippedBlankLines++
                continue
            } else if (skippedBlankLines > 0) {
                // 输出之前跳过的空行（在输出中补回空白行）
                repeat(skippedBlankLines) { advanceOutputRow() }
                skippedBlankLines = 0
            }

            if (oldLine == null) {
                // 未分配空行无内容可重排：坐标沿空格行的逐列落点语义保持列号
                for (t in 0 until trackedCount) {
                    if (!placed[t] && trackedOldRows[t] == externalOldRow) {
                        trackedNewCols[t] = trackedOldCols[t]
                        trackedNewRows[t] = currentOutputExternalRow
                        placed[t] = true
                    }
                }
                if (externalOldRow != (oldScreenRows - 1)) advanceOutputRow()
                continue
            }

            var lastNonSpaceIndex = 0
            if (oldLine.isLineWrap) {
                lastNonSpaceIndex = oldLine.charLength
            } else {
                oldLine.forEachRun { _, _, charEnd, codePoint, _, rawStyle, _ ->
                    val style = TextStyle(rawStyle)

                    // 扩展槽中的下划线位关闭时的残留颜色不可见，不构成需保留的自定义样式；
                    // 下划线可见（含形状）必伴随 style.effect 的下划线位，已由上一项涵盖
                    val hasCustomStyle = style.backColor != TextStyle.COLOR_INDEX_BACKGROUND
                        || style.effect != 0
                    if (codePoint != ' '.code || hasCustomStyle) {
                        lastNonSpaceIndex = charEnd
                    }
                    true
                }

                for (t in 0 until trackedCount) {
                    if (trackedOldRows[t] != externalOldRow) continue
                    var colCursor = 0
                    var cursorIdx = 0
                    oldLine.forEachRun { _, _, charEnd, _, width, _, _ ->
                        if (width > 0) colCursor += width
                        cursorIdx = charEnd
                        colCursor <= trackedOldCols[t]
                    }
                    if (cursorIdx > lastNonSpaceIndex) lastNonSpaceIndex = cursorIdx
                }
            }

            oldLine.forEachRun { column, charStart, _, codePoint, width, rawStyle, extEffect ->
                if (charStart >= lastNonSpaceIndex) return@forEachRun false

                if (currentOutputExternalColumn + width > buffer.columns) {
                    buffer.setLineWrap(currentOutputExternalRow)
                    advanceOutputRow()
                }

                val offsetDueToCombiningChar =
                    (if (width <= 0 && currentOutputExternalColumn > 0) 1 else 0)
                val outputColumn = currentOutputExternalColumn - offsetDueToCombiningChar
                buffer.setChar(outputColumn, currentOutputExternalRow, codePoint, TextStyle(rawStyle), extEffect)

                if (width > 0) {
                    for (t in 0 until trackedCount) {
                        if (!placed[t] && trackedOldRows[t] == externalOldRow && trackedOldCols[t] == column) {
                            trackedNewCols[t] = currentOutputExternalColumn
                            trackedNewRows[t] = currentOutputExternalRow
                            placed[t] = true
                        }
                    }
                    currentOutputExternalColumn += width
                }
                true
            }
            for (t in 0 until trackedCount) {
                if (!placed[t] && trackedOldRows[t] == externalOldRow) {
                    trackedNewCols[t] = currentOutputExternalColumn
                    trackedNewRows[t] = currentOutputExternalRow
                    placed[t] = true
                }
            }
            if (externalOldRow != (oldScreenRows - 1) && !oldLine.isLineWrap) advanceOutputRow()
        }

        for (t in 0 until trackedCount) {
            trackedCoords[t] = CursorCoord.pack(trackedNewCols[t], trackedNewRows[t]).packed
        }
    }

    /** 是否有未落点坐标位于 [externalOldRow]（该行即使为空也不可跳过）。 */
    private fun anyUnplacedOnRow(placed: BooleanArray, trackedOldRows: IntArray, externalOldRow: Int): Boolean {
        for (t in placed.indices) if (!placed[t] && trackedOldRows[t] == externalOldRow) return true
        return false
    }

    /** 输出滚动使既有内容整体上移一行，已落点坐标随之递减。 */
    private fun decrementPlacedRows(trackedNewRows: IntArray, placed: BooleanArray) {
        for (t in placed.indices) if (placed[t]) trackedNewRows[t]--
    }
}

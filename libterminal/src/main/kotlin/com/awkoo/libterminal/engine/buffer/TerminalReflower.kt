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
     * 宽字符跨行边界、组合字符偏移、光标位置追踪等边界情况。
     *
     * @return 新的 (cursorColumn, cursorRow) 游标坐标对，未找到时为 (-1, -1)
     */
    fun reflow(
        buffer: TerminalBuffer,
        oldLines: Array<TerminalRow?>,
        oldTotalRows: Int,
        oldScreenRows: Int,
        oldScreenFirstRow: Int,
        oldActiveTranscriptRows: Int,
        oldCursorColumn: Int,
        oldCursorRow: Int,
        currentStyle: TextStyle
    ): CursorCoord {
        var newCursorRow = -1
        var newCursorColumn = -1
        var newCursorPlaced = false

        var currentOutputExternalRow = 0
        var currentOutputExternalColumn = 0

        var skippedBlankLines = 0
        for (externalOldRow in -oldActiveTranscriptRows..<oldScreenRows) {
            // 将外部行号转换为环形缓冲区的内部索引
            val internalOldRow = (oldScreenFirstRow + externalOldRow).mod(oldTotalRows)

            val oldLine: TerminalRow? = oldLines[internalOldRow]
            val cursorAtThisRow = externalOldRow == oldCursorRow
            val preserveCursorRow = !newCursorPlaced && cursorAtThisRow
            // 跳过空行（但光标所在行即使为空也不跳过，除非光标已放置）
            if (oldLine == null || (oldLine.isBlank && !preserveCursorRow)) {
                skippedBlankLines++
                continue
            } else if (skippedBlankLines > 0) {
                // 输出之前跳过的空行（在输出中补回空白行）
                for (i in 0..<skippedBlankLines) {
                    if (currentOutputExternalRow == buffer.mScreenRows - 1) {
                        buffer.scrollDownOneLine(0, buffer.mScreenRows, currentStyle)
                    } else {
                        currentOutputExternalRow++
                    }
                    currentOutputExternalColumn = 0
                }
                skippedBlankLines = 0
            }

            var lastNonSpaceIndex = 0
            if (oldLine.mLineWrap) {
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

                if (cursorAtThisRow) {
                    var colCursor = 0
                    var cursorIdx = 0
                    oldLine.forEachRun { _, _, charEnd, _, width, _, _ ->
                        if (width > 0) colCursor += width
                        cursorIdx = charEnd
                        colCursor <= oldCursorColumn
                    }
                    if (cursorIdx > lastNonSpaceIndex) lastNonSpaceIndex = cursorIdx
                }
            }

            oldLine.forEachRun { column, charStart, _, codePoint, width, rawStyle, extEffect ->
                if (charStart >= lastNonSpaceIndex) return@forEachRun false

                if (currentOutputExternalColumn + width > buffer.mColumns) {
                    buffer.setLineWrap(currentOutputExternalRow)
                    if (currentOutputExternalRow == buffer.mScreenRows - 1) {
                        if (newCursorPlaced) newCursorRow--
                        buffer.scrollDownOneLine(0, buffer.mScreenRows, currentStyle)
                    } else {
                        currentOutputExternalRow++
                    }
                    currentOutputExternalColumn = 0
                }

                val offsetDueToCombiningChar =
                    (if (width <= 0 && currentOutputExternalColumn > 0) 1 else 0)
                val outputColumn = currentOutputExternalColumn - offsetDueToCombiningChar
                buffer.setChar(outputColumn, currentOutputExternalRow, codePoint, TextStyle(rawStyle), extEffect)

                if (width > 0) {
                    if (oldCursorRow == externalOldRow && oldCursorColumn == column) {
                        newCursorColumn = currentOutputExternalColumn
                        newCursorRow = currentOutputExternalRow
                        newCursorPlaced = true
                    }
                    currentOutputExternalColumn += width
                }
                true
            }
            if (!newCursorPlaced && oldCursorRow == externalOldRow) {
                newCursorColumn = currentOutputExternalColumn
                newCursorRow = currentOutputExternalRow
                newCursorPlaced = true
            }
            if (externalOldRow != (oldScreenRows - 1) && !oldLine.mLineWrap) {
                if (currentOutputExternalRow == buffer.mScreenRows - 1) {
                    if (newCursorPlaced) newCursorRow--
                    buffer.scrollDownOneLine(0, buffer.mScreenRows, currentStyle)
                } else {
                    currentOutputExternalRow++
                }
                currentOutputExternalColumn = 0
            }
        }

        return CursorCoord.pack(newCursorColumn, newCursorRow)
    }
}
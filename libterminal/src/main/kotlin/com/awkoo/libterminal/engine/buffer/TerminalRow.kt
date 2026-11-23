package com.awkoo.libterminal.engine.buffer

import com.awkoo.libterminal.text.TextStyle
import com.awkoo.libterminal.text.WcWidth
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

/**
 * 列吸附方向（闭区间语义）。
 *
 * 宽字符占据 `[col, col + width - 1]` 多列时，选区起止列号分别吸附到该字符占用的首列或末列，
 * 避免漏选宽字符或把终点切在宽字符中间。
 */
internal enum class SnapAlign {
    /** 吸附到字符占用的起始列（inclusive start）。 */
    Start,
    /** 吸附到字符占用的结束列（inclusive end）。 */
    End
}

/**
 * 终端单行数据。
 *
 * 文本存储在私有 char 数组中，样式存储在私有 Long 数组中，
 * 渲染时可直接按列索引访问，无需拆箱。对外只经 [textChars]/[charLength] 读取，
 * 经 [getStyle]/[getRawStyle]/[getExtendedEffect] 取样式。
 *
 * 列与 char 的换算一律经由本类：[startCharOfColumn]/[endCharOfColumn]、
 * [forEachCell]、[snapToColumn]，消费者不得自行扫 char 数组推列。
 */
internal class TerminalRow(
    /** 本行列数。 */
    val columns: Int,
    style: TextStyle
) {
    /** 存储行内文本的字符数组，可能包含用于填充的尾部空格。 */
    private var text = CharArray((SPARE_CAPACITY_FACTOR * columns).toInt()) { ' ' }

    /** 已使用的字符数（Java char 单位）。 */
    private var usedCharCount = 0

    /**
     * 每列占用的 Java char 数（仅在行内出现非单宽或代理项字符时懒分配）。
     * `counts[col] == 0` 表示该列是前一宽字符单元格 `col - 1` 的后半格；
     * `counts[col] > 0` 表示列 `col` 是一个单元格的起始列，其占用的 char 数为 `counts[col]`。
     */
    private var cellCharCounts: IntArray? = null

    /**
     * 行末是否因输出而自动换行。
     *
     * 行级元数据，只在行生命周期事件上变更，写入收口在 [clear]/[markLineWrap]/[copyLineWrapFrom]：
     * 行内的字符写入与横向搬移（[setChar]/[setCell]/[copyInterval]）不持有该状态，因此也不得触碰它。
     */
    val id: Long = nextRowId.incrementAndGet()

    var version: Long = 0L
        private set

    private var snapshotRefCount: Int = 0

    val isSharedWithSnapshot: Boolean
        get() = snapshotRefCount > 0

    internal fun retainSnapshotRef() {
        snapshotRefCount++
    }

    internal fun releaseSnapshotRef() {
        if (snapshotRefCount > 0) snapshotRefCount--
    }

    private var lineWrap = false
    private var trailingWrapPadding = false

    /** [lineWrap] 的唯一读口。 */
    val isLineWrap: Boolean
        get() = lineWrap

    /** 标记本行行末自动换行时末列是否因宽字符折行而留空占位。 */
    val hasTrailingWrapPadding: Boolean
        get() = trailingWrapPadding

    /** 标记本行行末自动换行，本行成为下一行的续行起点。 */
    fun markLineWrap(hasTrailingPadding: Boolean = false) {
        lineWrap = true
        trailingWrapPadding = hasTrailingPadding
        version++
    }

    /** 行对象整体复制时同步续行标记，使标记与内容同生共死。 */
    fun copyLineWrapFrom(source: TerminalRow) {
        lineWrap = source.lineWrap
        trailingWrapPadding = source.trailingWrapPadding
        version++
    }

    /** 各列的样式位，交错存储：偶数索引=主样式，奇数索引=扩展特效。以原始 Long 存储以避免装箱。 */
    private val styleAndEffectBits = LongArray(columns * 2)

    /** 本行是否包含宽度 != 1 的字符或代理项对，用于禁用快速路径。 */
    var hasNonOneWidthOrSurrogateChars: Boolean = false

    /** 本行是否全为空白。 */
    var isBlank = true
        private set

    /** 用指定样式构造空白行（仅含空格）。 */
    init {
        clear(style)
    }

    /**
     * 行内文本的 char 数组，char 空间的唯一读取出口。
     *
     * 返回的引用有效至本行下一次 [setChar]/[clear]：写入可能原地移位内容，
     * 扩容时还会整体替换数组。调用方须在同一次绘制/查询内读完，不得跨写入持有。
     */
    fun textChars(): CharArray = text

    /** 已使用的 char 数（Java char 单位），作为 [textChars] 的读取上界。 */
    val charLength: Int
        get() = usedCharCount

    fun copySnapshotFrom(source: TerminalRow) {
        require(columns == source.columns) {
            "TerminalRow.copySnapshotFrom(): column mismatch $columns != ${source.columns}"
        }
        val sourceLen = source.usedCharCount
        if (text.size < sourceLen) {
            text = CharArray(max(source.text.size, sourceLen))
        }
        System.arraycopy(source.text, 0, text, 0, sourceLen)
        System.arraycopy(source.styleAndEffectBits, 0, styleAndEffectBits, 0, columns * 2)
        val sourceCounts = source.cellCharCounts
        if (sourceCounts == null) {
            cellCharCounts = null
        } else {
            val dstCounts = cellCharCounts ?: IntArray(columns).also { cellCharCounts = it }
            System.arraycopy(sourceCounts, 0, dstCounts, 0, columns)
        }
        usedCharCount = sourceLen
        lineWrap = source.lineWrap
        trailingWrapPadding = source.trailingWrapPadding
        hasNonOneWidthOrSurrogateChars = source.hasNonOneWidthOrSurrogateChars
        isBlank = source.isBlank
        version++
    }

    /**
     * 从源行复制 [sourceX1] 到 [sourceX2)（不含）的内容到本行 [destinationX] 位置。
     *
     * 处理宽字符后半部分的空白填充、组合字符/字素簇集群的完整搬移，以及行内样式保留。
     * 注意：[sourceX2] 为排他端点。
     */
    fun copyInterval(line: TerminalRow, sourceX1: Int, sourceX2: Int, destinationX: Int) {
        if (sourceX1 >= sourceX2) return
        val sourceChars = if (this == line) line.text.copyOf(line.usedCharCount) else line.text
        val sourceStyle = if (this == line) line.styleAndEffectBits.copyOf() else line.styleAndEffectBits
        val sourceCounts = if (this == line) line.cellCharCounts?.copyOf() else line.cellCharCounts

        var srcCol = sourceX1
        var dstCol = destinationX
        val startInSecondHalf = srcCol > 0 && line.isWideSecondHalf(sourceCounts, srcCol)
        if (startInSecondHalf) {
            setChar(dstCol, ' '.code, TextStyle(sourceStyle[srcCol * 2]), sourceStyle[srcCol * 2 + 1])
            dstCol++
            srcCol++
        }

        if (sourceCounts == null) {
            for (col in srcCol until sourceX2) {
                setChar(
                    dstCol,
                    sourceChars[col].code,
                    TextStyle(sourceStyle[col * 2]),
                    sourceStyle[col * 2 + 1]
                )
                dstCol++
            }
            version++
            return
        }

        var charIndex = 0
        var col = 0
        while (col < sourceX2 && col < line.columns) {
            val charCount = sourceCounts[col]
            val width = if (col + 1 < line.columns && sourceCounts[col + 1] == 0) 2 else 1
            if (col >= srcCol) {
                if (col + width <= sourceX2) {
                    setCell(
                        columnToSet = dstCol,
                        sourceChars = sourceChars,
                        charStart = charIndex,
                        charEnd = charIndex + charCount,
                        width = width,
                        style = TextStyle(sourceStyle[col * 2]),
                        extendedEffect = sourceStyle[col * 2 + 1]
                    )
                    dstCol += width
                } else {
                    val truncSrcCol = sourceX2 - 1
                    setChar(
                        dstCol,
                        ' '.code,
                        TextStyle(sourceStyle[truncSrcCol * 2]),
                        sourceStyle[truncSrcCol * 2 + 1]
                    )
                    dstCol++
                }
            }
            charIndex += charCount
            col += width
        }
        version++
    }

    private fun isWideSecondHalf(counts: IntArray?, column: Int): Boolean =
        counts != null && column in 1 until columns && counts[column] == 0

    private fun ensureCellCharCounts(): IntArray {
        return cellCharCounts ?: IntArray(columns) { 1 }.also { cellCharCounts = it }
    }

    /**
     * 查找指定列号对应的字符数组起始索引。
     *
     * 宽字符的后半格返回该宽字符的起始索引。无宽字符时直接返回列号（O(1) 快速路径）。
     */
    private fun findStartOfColumn(column: Int): Int {
        if (column >= columns) return usedCharCount
        val counts = cellCharCounts ?: return column
        val startCol = if (column > 0 && counts[column] == 0) column - 1 else column
        var idx = 0
        for (c in 0 until startCol) {
            idx += counts[c]
        }
        return idx
    }

    /**
     * 按单元格集群迭代本行内容。
     *
     * 回调参数：
     * - [column] 单元格起始显示列；
     * - [charStart]/[charEnd] 该单元格完整字符集群（含全部零宽修饰符/ZWJ 序列）在 [textChars] 中的区间；
     * - [width] 单元格占用显示列数（1 或 2）；
     * - [rawStyle]/[extEffect] 该单元格主样式与扩展特效。
     *
     * 返回 false 可提前停止。迭代期间不得变异本行。
     */
    inline fun forEachCell(
        action: (
            column: Int,
            charStart: Int,
            charEnd: Int,
            width: Int,
            rawStyle: Long,
            extEffect: Long
        ) -> Boolean
    ) {
        val counts = cellCharCounts
        if (counts == null) {
            for (col in 0 until columns) {
                if (!action(col, col, col + 1, 1, getRawStyle(col), getExtendedEffect(col))) {
                    return
                }
            }
            return
        }
        var col = 0
        var charIndex = 0
        while (col < columns) {
            val charCount = counts[col]
            if (charCount <= 0) {
                col++
                continue
            }
            val width = if (col + 1 < columns && counts[col + 1] == 0) 2 else 1
            if (!action(col, charIndex, charIndex + charCount, width, getRawStyle(col), getExtendedEffect(col))) {
                return
            }
            charIndex += charCount
            col += width
        }
    }

    /** 返回包含列 [column] 的单元格起始列（若落在宽字符后半格则回退至前一半起始列）。 */
    fun getCellStartColAt(column: Int): Int {
        if (column <= 0) return 0
        val clamped = column.coerceAtMost(columns - 1)
        val counts = cellCharCounts ?: return clamped
        return if (counts[clamped] == 0) clamped - 1 else clamped
    }

    /** 返回紧邻在列 [column] 之前结束的单元格起始列（宽格返回 `column - 2`，窄格返回 `column - 1`）。 */
    fun getCellStartColBefore(column: Int): Int =
        if (column <= 0) 0 else getCellStartColAt(column - 1)

    fun getCellWidth(column: Int): Int {
        val startCol = getCellStartColAt(column)
        val counts = cellCharCounts ?: return 1
        return if (startCol + 1 < columns && counts[startCol + 1] == 0) 2 else 1
    }

    fun getCellFirstCodePoint(column: Int): Int {
        val start = startCharOfColumn(column)
        if (start >= usedCharCount) return ' '.code
        return Character.codePointAt(text, start, usedCharCount)
    }

    fun getCellLastCodePoint(column: Int): Int {
        val start = startCharOfColumn(column)
        val end = endCharOfColumn(column)
        if (start >= end) return ' '.code
        return Character.codePointBefore(text, end, start)
    }

    fun getCellCodePointCount(column: Int): Int {
        val start = startCharOfColumn(column)
        val end = endCharOfColumn(column)
        if (start >= end) return 0
        return Character.codePointCount(text, start, end - start)
    }

    fun copyCellChars(column: Int): CharArray {
        val start = startCharOfColumn(column)
        val end = endCharOfColumn(column)
        return text.copyOfRange(start, end)
    }

    /**
     * 列 [column] 的内容起始 char 索引。
     *
     * 列号落在宽字符后半格内时返回该宽字符的起始索引，两个半格共享同一 char 区间。
     * 越界列钳到行内容边界：列号 ≤ 0 → 0，列号 ≥ 列数 → [charLength]。
     */
    fun startCharOfColumn(column: Int): Int {
        if (column <= 0) return 0
        if (column >= columns) return usedCharCount
        return findStartOfColumn(column).coerceAtMost(usedCharCount)
    }

    /**
     * 列 [column] 的内容结束 char 索引（排他），与 [startCharOfColumn] 构成该列的完整 char 区间。
     *
     * 宽字符的两个半格返回同一区间；行尾之后返回 [charLength]。
     */
    fun endCharOfColumn(column: Int): Int {
        if (column >= columns) return usedCharCount
        val counts = cellCharCounts ?: return (column + 1).coerceAtMost(usedCharCount)
        val startCol = getCellStartColAt(column)
        val start = findStartOfColumn(startCol)
        return (start + counts[startCol]).coerceAtMost(usedCharCount)
    }

    /**
     * 列 [column] 是否为空白格（空格或行内容之外）。
     *
     * 口径与字符内容一致：只看 char 是否为空格，不看样式。
     */
    fun isCellBlank(column: Int): Boolean {
        if (column < 0 || column >= columns) return true
        val text = text
        for (i in startCharOfColumn(column) until endCharOfColumn(column)) {
            if (text[i] != ' ') return false
        }
        return true
    }

    /**
     * 把列号校准到所属字符的闭区间边界（首列或末列）。
     *
     * 扫描到 NUL 即视为无内容并返回原列号。
     */
    fun snapToColumn(column: Int, align: SnapAlign): Int {
        if (!hasNonOneWidthOrSurrogateChars) return column
        val counts = cellCharCounts ?: return column
        var col = 0
        var charIndex = 0
        while (col < columns) {
            val charCount = counts[col]
            if (charCount <= 0) {
                col++
                continue
            }
            val width = if (col + 1 < columns && counts[col + 1] == 0) 2 else 1
            if (charIndex < usedCharCount && text[charIndex].code == 0) return column
            if (column in col until col + width) {
                return if (align == SnapAlign.Start) col else col + width - 1
            }
            charIndex += charCount
            col += width
        }
        return column
    }

    private fun wideDisplayCharacterStartingAt(column: Int): Boolean {
        val counts = cellCharCounts ?: return false
        return column in 0 until (columns - 1) && counts[column] > 0 && counts[column + 1] == 0
    }

    fun clear(style: TextStyle, extendedEffect: Long = 0L) {
        val ext = normalizeExtendedEffect(style.value, extendedEffect)
        if (!isBlank) text.fill(' ')
        for (i in 0 until columns) {
            styleAndEffectBits[i * 2] = style.value
            styleAndEffectBits[i * 2 + 1] = ext
        }
        usedCharCount = columns
        cellCharCounts = null
        hasNonOneWidthOrSurrogateChars = false
        lineWrap = false
        trailingWrapPadding = false
        // 扩展槽中下划线位关闭时不可见的残留（形状已归零、颜色独立）不影响空白判定
        isBlank = (style.foreColor == TextStyle.COLOR_INDEX_FOREGROUND) &&
                  (style.backColor == TextStyle.COLOR_INDEX_BACKGROUND) &&
                  (style.effect == 0)
        version++
    }

    /**
     * 在指定列写入字符。
     *
     * 处理三种情况：
     * 1. 普通字符：直接写入
     * 2. 组合字符（零宽）：与前一个字符合并，不占新列
     * 3. 宽字符：写入两列，如果目标位置已有字符则清除后续列
     *
     * 如果目标位置被宽字符占据（写入点在其后半部分），会先拆分宽字符。
     */
    fun setChar(columnToSet: Int, codePoint: Int, style: TextStyle, extendedEffect: Long = 0L) {
        require(columnToSet in 0 until columns) { "TerminalRow.setChar(): columnToSet=$columnToSet, codePoint=$codePoint, style=${style.value}" }

        val ext = normalizeExtendedEffect(style.value, extendedEffect)
        markNonBlankIfNeeded(codePoint, style)

        val isAsciiPrintable = codePoint in 0x20..0x7E
        val newCodePointDisplayWidth = if (isAsciiPrintable) 1 else WcWidth.width(codePoint)

        if (!hasNonOneWidthOrSurrogateChars) {
            if (columnToSet == columns - 1) trailingWrapPadding = false
            if (isAsciiPrintable) {
                styleAndEffectBits[columnToSet * 2] = style.value
                styleAndEffectBits[columnToSet * 2 + 1] = ext
                text[columnToSet] = codePoint.toChar()
                if (codePoint != 0x20) isBlank = false
                version++
                return
            }
            if (codePoint >= Character.MIN_SUPPLEMENTARY_CODE_POINT || newCodePointDisplayWidth != 1) {
                hasNonOneWidthOrSurrogateChars = true
                ensureCellCharCounts()
            } else {
                styleAndEffectBits[columnToSet * 2] = style.value
                styleAndEffectBits[columnToSet * 2 + 1] = ext
                text[columnToSet] = codePoint.toChar()
                version++
                return
            }
        }

        if (newCodePointDisplayWidth <= 0) {
            appendCodePointToCell(columnToSet, codePoint, expandToWide = false, style = style, extendedEffect = ext)
            return
        }

        val scratch = charArrayOf('\u0000', '\u0000')
        val charCount = Character.toChars(codePoint, scratch, 0)
        replaceCell(columnToSet, scratch, 0, charCount, newCodePointDisplayWidth, style, ext)
    }

    /**
     * 原子写入完整单元格字符集群（包含基字符与附加修饰符/ZWJ 序列）及显示列宽。
     */
    fun setCell(
        columnToSet: Int,
        sourceChars: CharArray,
        charStart: Int,
        charEnd: Int,
        width: Int,
        style: TextStyle,
        extendedEffect: Long = 0L
    ) {
        require(columnToSet in 0 until columns) { "TerminalRow.setCell(): columnToSet=$columnToSet" }
        val ext = normalizeExtendedEffect(style.value, extendedEffect)
        val count = charEnd - charStart
        if (count == 1 && width == 1 && !hasNonOneWidthOrSurrogateChars) {
            setChar(columnToSet, sourceChars[charStart].code, style, ext)
            return
        }
        hasNonOneWidthOrSurrogateChars = true
        ensureCellCharCounts()
        if (count != 1) isBlank = false else markNonBlankIfNeeded(sourceChars[charStart].code, style)
        replaceCell(columnToSet, sourceChars, charStart, count, width, style, ext)
    }

    /**
     * 将 [codePoint] 追加合并到 [column] 所属的单元格集群中；若 [expandToWide] 为 true 且该格当前为单宽，
     * 则就地吸收右侧相邻格扩展为 2 列宽。若已达 [MAX_CELL_CODEPOINTS] 上限则返回 false 且不变更行状态。
     */
    fun appendCodePointToCell(
        column: Int,
        codePoint: Int,
        expandToWide: Boolean,
        style: TextStyle,
        extendedEffect: Long = 0L
    ): Boolean {
        require(column in 0 until columns) { "TerminalRow.appendCodePointToCell(): column=$column" }
        val existingCounts = cellCharCounts
        if (existingCounts != null) {
            val cellCol = getCellStartColAt(column)
            val oldCharCount = existingCounts[cellCol]
            val oldStartIdx = findStartOfColumn(cellCol)
            if (Character.codePointCount(text, oldStartIdx, oldCharCount) >= MAX_CELL_CODEPOINTS) {
                return false
            }
        }

        val ext = normalizeExtendedEffect(style.value, extendedEffect)
        hasNonOneWidthOrSurrogateChars = true
        val counts = ensureCellCharCounts()
        markNonBlankIfNeeded(codePoint, style)

        val cellCol = getCellStartColAt(column)
        styleAndEffectBits[cellCol * 2] = style.value
        styleAndEffectBits[cellCol * 2 + 1] = ext

        val oldCharCount = counts[cellCol]
        val oldStartIdx = findStartOfColumn(cellCol)
        val cpChars = Character.charCount(codePoint)
        val oldWidth = if (cellCol + 1 < columns && counts[cellCol + 1] == 0) 2 else 1

        if (expandToWide && oldWidth == 1 && cellCol + 1 < columns) {
            if (wideDisplayCharacterStartingAt(cellCol + 1)) {
                val spaceScratch = charArrayOf(' ')
                replaceCell(cellCol + 1, spaceScratch, 0, 1, 1, style, ext)
            }
            val startIdx = findStartOfColumn(cellCol)
            val nextCharCount = counts[cellCol + 1]
            val currentText = shiftTextBuffer(
                this.text,
                startIdx + oldCharCount + nextCharCount,
                startIdx + oldCharCount + cpChars,
                cpChars - nextCharCount
            )
            usedCharCount += cpChars - nextCharCount
            Character.toChars(codePoint, currentText, startIdx + oldCharCount)
            counts[cellCol] = oldCharCount + cpChars
            counts[cellCol + 1] = 0
            styleAndEffectBits[(cellCol + 1) * 2] = style.value
            styleAndEffectBits[(cellCol + 1) * 2 + 1] = ext
            if (cellCol + 2 >= columns) trailingWrapPadding = false
        } else {
            val currentText = shiftTextBuffer(
                this.text,
                oldStartIdx + oldCharCount,
                oldStartIdx + oldCharCount + cpChars,
                cpChars
            )
            usedCharCount += cpChars
            Character.toChars(codePoint, currentText, oldStartIdx + oldCharCount)
            counts[cellCol] = oldCharCount + cpChars
        }
        version++
        return true
    }

    private fun markNonBlankIfNeeded(codePoint: Int, style: TextStyle) {
        if (codePoint != ' '.code) {
            isBlank = false
        } else if (style.foreColor != TextStyle.COLOR_INDEX_FOREGROUND ||
            style.backColor != TextStyle.COLOR_INDEX_BACKGROUND ||
            style.effect != 0
        ) {
            isBlank = false
        }
    }

    private fun replaceCell(
        columnToSet: Int,
        src: CharArray,
        srcStart: Int,
        srcCount: Int,
        requestedWidth: Int,
        style: TextStyle,
        ext: Long
    ) {
        var source = src
        var sourceStart = srcStart
        var sourceCount = srcCount
        var newWidth = requestedWidth

        if (newWidth == 2 && columnToSet == columns - 1) {
            source = SPACE_CHAR_ARRAY
            sourceStart = 0
            sourceCount = 1
            newWidth = 1
        }

        if (columnToSet > 0 && wideDisplayCharacterStartingAt(columnToSet - 1)) {
            replaceCell(columnToSet - 1, SPACE_CHAR_ARRAY, 0, 1, 1, style, ext)
        }
        if (newWidth == 2 && wideDisplayCharacterStartingAt(columnToSet + 1)) {
            replaceCell(columnToSet + 1, SPACE_CHAR_ARRAY, 0, 1, 1, style, ext)
        }

        styleAndEffectBits[columnToSet * 2] = style.value
        styleAndEffectBits[columnToSet * 2 + 1] = ext

        val counts = ensureCellCharCounts()
        val oldWidth = if (columnToSet + 1 < columns && counts[columnToSet + 1] == 0) 2 else 1
        val oldCharCount = counts[columnToSet]
        val startIdx = findStartOfColumn(columnToSet)

        when {
            oldWidth == newWidth -> {
                val delta = sourceCount - oldCharCount
                val dst = shiftTextBuffer(this.text, startIdx + oldCharCount, startIdx + sourceCount, delta)
                usedCharCount += delta
                source.copyInto(dst, destinationOffset = startIdx, startIndex = sourceStart, endIndex = sourceStart + sourceCount)
                counts[columnToSet] = sourceCount
            }
            oldWidth == 2 && newWidth == 1 -> {
                val targetChars = sourceCount + 1
                val delta = targetChars - oldCharCount
                val dst = shiftTextBuffer(this.text, startIdx + oldCharCount, startIdx + targetChars, delta)
                usedCharCount += delta
                source.copyInto(dst, destinationOffset = startIdx, startIndex = sourceStart, endIndex = sourceStart + sourceCount)
                dst[startIdx + sourceCount] = ' '
                counts[columnToSet] = sourceCount
                counts[columnToSet + 1] = 1
                styleAndEffectBits[(columnToSet + 1) * 2] = style.value
                styleAndEffectBits[(columnToSet + 1) * 2 + 1] = ext
            }
            else -> {
                val nextCharCount = counts[columnToSet + 1]
                val replacedChars = oldCharCount + nextCharCount
                val delta = sourceCount - replacedChars
                val dst = shiftTextBuffer(this.text, startIdx + replacedChars, startIdx + sourceCount, delta)
                usedCharCount += delta
                source.copyInto(dst, destinationOffset = startIdx, startIndex = sourceStart, endIndex = sourceStart + sourceCount)
                counts[columnToSet] = sourceCount
                counts[columnToSet + 1] = 0
                styleAndEffectBits[(columnToSet + 1) * 2] = style.value
                styleAndEffectBits[(columnToSet + 1) * 2 + 1] = ext
            }
        }
        if (columnToSet + newWidth >= columns) trailingWrapPadding = false
        version++
    }

    private fun shiftTextBuffer(text: CharArray, oldNextColumnIndex: Int, newNextColumnIndex: Int, charDifference: Int): CharArray {
        var text = text
        if (charDifference > 0) {
            val oldCharactersAfterColumn = usedCharCount - oldNextColumnIndex
            if (usedCharCount + charDifference > text.size) {
                val newText = CharArray(text.size + max(columns, charDifference))
                text.copyInto(destination = newText, endIndex = oldNextColumnIndex)
                text.copyInto(
                    destination = newText,
                    destinationOffset = newNextColumnIndex,
                    startIndex = oldNextColumnIndex,
                    endIndex = oldNextColumnIndex + oldCharactersAfterColumn
                )
                text = newText
                this@TerminalRow.text = text
            } else {
                text.copyInto(
                    destination = text,
                    destinationOffset = newNextColumnIndex,
                    startIndex = oldNextColumnIndex,
                    endIndex = oldNextColumnIndex + oldCharactersAfterColumn
                )
            }
        } else if (charDifference < 0) {
            text.copyInto(
                destination = text,
                destinationOffset = newNextColumnIndex,
                startIndex = oldNextColumnIndex,
                endIndex = usedCharCount
            )
        }
        return text
    }

    inline fun getStyle(column: Int): TextStyle {
        return TextStyle(styleAndEffectBits[column * 2])
    }

    /**
     * 读取主样式原始值。
     *
     * 断言「下划线位为 0 时扩展槽形状为 0」，使新增写入路径若绕过 [normalizeExtendedEffect]
     * 会在测试中立即暴露，而不是等渲染出错。
     */
    inline fun getRawStyle(column: Int): Long {
        val raw = styleAndEffectBits[column * 2]
        assert(
            raw and TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE.toLong() != 0L ||
                (styleAndEffectBits[column * 2 + 1] and TextStyle.EXT_UNDERLINE_STYLE_MASK) == 0L
        ) { "下划线位关闭时扩展槽下划线形状必须为 0：column=$column raw=$raw ext=${styleAndEffectBits[column * 2 + 1]}" }
        return raw
    }

    inline fun getExtendedEffect(column: Int): Long = styleAndEffectBits[column * 2 + 1]

    /**
     * 修改指定列主样式的特效位，保证双槽位状态一致。
     *
     * @param bits 特效位掩码
     * @param setOrClear true=置位，false=清位
     * @param reverse true=翻转 bits 中的位（bits 中原本置位的清零、清零的置位）
     */
    fun modifyEffect(column: Int, bits: Int, setOrClear: Boolean, reverse: Boolean) {
        val raw = styleAndEffectBits[column * 2]
        val effect = (raw and TextStyle.EFFECT_MASK).toInt()
        val newEffect = when {
            reverse -> (effect and bits.inv()) or (bits and effect.inv())
            setOrClear -> effect or bits
            else -> effect and bits.inv()
        }
        styleAndEffectBits[column * 2] =
            (raw and TextStyle.EFFECT_MASK.inv()) or (newEffect.toLong() and TextStyle.EFFECT_MASK)
        styleAndEffectBits[column * 2 + 1] = normalizeExtendedEffect(styleAndEffectBits[column * 2], styleAndEffectBits[column * 2 + 1])
        // 特效位非零即非默认样式，与 setChar 的空白判定口径一致；
        // 清位后无法在不扫描整行的前提下判定能否恢复空白，保守保持非空白
        if (newEffect != 0) isBlank = false
        version++
    }

    /**
     * 下划线开关与扩展槽下划线形状的单向派生：开关关闭时形状归零。
     *
     * 颜色位（bit 3 及以上）独立于开关（SGR 58 可单独设色），予以保留。
     * 下划线形状是位布局上唯一跨槽位耦合的字段，收口在此处，
     * 使 [styleAndEffectBits] 恒满足「开关关闭 ⇒ 形状为 0」的不变量。
     */
    private fun normalizeExtendedEffect(styleValue: Long, extendedEffect: Long): Long {
        val underlineOn = styleValue and TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE.toLong() != 0L
        return if (underlineOn) extendedEffect
        else extendedEffect and TextStyle.EXT_UNDERLINE_STYLE_MASK.inv()
    }

    companion object {
        private val nextRowId = AtomicLong(0L)
        private const val SPARE_CAPACITY_FACTOR = 1.5f
        internal const val MAX_CELL_CODEPOINTS = 32
        private val SPACE_CHAR_ARRAY = charArrayOf(' ')
    }
}
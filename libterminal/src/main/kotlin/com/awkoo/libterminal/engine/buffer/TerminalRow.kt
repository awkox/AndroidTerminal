package com.awkoo.libterminal.engine.buffer

import com.awkoo.libterminal.text.TextStyle
import com.awkoo.libterminal.text.WcWidth
import com.awkoo.libterminal.text.charCountAtSafe
import com.awkoo.libterminal.text.forEachColumn

/**
 * 列吸附方向。
 *
 * 列号落在宽字符后半格内时没有唯一归属，选区两端需要相反的吸附方向：
 * 起点向字符起始吸，否则该宽字符被漏选；终点向字符之后吸，否则该半格无归属。
 */
internal enum class SnapAlign {
    /** 吸附到字符起始列（floor）。 */
    Start,
    /** 吸附到字符之后（ceil）。 */
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
 * [forEachRun]、[snapToColumn]，消费者不得自行扫 char 数组推列。
 */
internal class TerminalRow(
    /** 本行列数。 */
    private val mColumns: Int,
    style: TextStyle
) {
    /** 存储行内文本的字符数组，可能包含用于填充的尾部空格。 */
    private var mText = CharArray((SPARE_CAPACITY_FACTOR * mColumns).toInt()) { ' ' }

    /** 已使用的字符数（Java char 单位）。 */
    private var mSpaceUsed = 0

    /** 行末是否因输出而自动换行。 */
    @JvmField
    var mLineWrap: Boolean = false

    /** 各列的样式位，交错存储：偶数索引=主样式，奇数索引=扩展特效。以原始 Long 存储以避免装箱。 */
    private val mStyle = LongArray(mColumns * 2)

    /** 本行是否包含宽度 != 1 的字符或代理项对，用于禁用快速路径。 */
    var mHasNonOneWidthOrSurrogateChars: Boolean = false

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
    fun textChars(): CharArray = mText

    /** 已使用的 char 数（Java char 单位），作为 [textChars] 的读取上界。 */
    val charLength: Int
        get() = mSpaceUsed

    /**
     * 从源行复制 [sourceX1] 到 [sourceX2)（不含）的内容到本行 [destinationX] 位置。
     *
     * 处理宽字符后半部分的空白填充、组合字符的偏移覆盖，以及行内样式合并。
     * 注意：[sourceX2] 为排他端点。
     */
    fun copyInterval(line: TerminalRow, sourceX1: Int, sourceX2: Int, destinationX: Int) {
        var sourceX1 = sourceX1
        var destinationX = destinationX
        mHasNonOneWidthOrSurrogateChars =
            mHasNonOneWidthOrSurrogateChars or line.mHasNonOneWidthOrSurrogateChars
        val x1 = line.findStartOfColumn(sourceX1)
        val x2 = line.findStartOfColumn(sourceX2)
        var startingFromSecondHalfOfWideChar =
            (sourceX1 > 0 && line.wideDisplayCharacterStartingAt(sourceX1 - 1))
        val sourceChars = if (this == line) line.mText.copyOf(line.mText.size) else line.mText
        val sourceStyle = if (this == line) line.mStyle.copyOf() else line.mStyle
        var latestNonCombiningWidth = 0
        var i = x1
        while (i < x2) {
            val sourceChar = sourceChars[i]
            var codePoint = if (sourceChar.isHighSurrogate()) Character.toCodePoint(
                sourceChar,
                sourceChars[++i]
            ) else sourceChar.code
            if (startingFromSecondHalfOfWideChar) {
                // 宽字符后半部分的复制视同空白填充
                codePoint = ' '.code
                startingFromSecondHalfOfWideChar = false
            }
            val w = WcWidth.width(codePoint)
            if (w > 0) {
                destinationX += latestNonCombiningWidth
                sourceX1 += latestNonCombiningWidth
                latestNonCombiningWidth = w
            }

            // 从安全的快照中读取偶数位（主样式）和奇数位（扩展特效）
            val styleValue = sourceStyle[sourceX1 * 2]
            val extEffect = sourceStyle[sourceX1 * 2 + 1]

            setChar(destinationX, codePoint, TextStyle(styleValue), extEffect)
            i++
        }
    }

    /**
     * 查找指定列号对应的字符数组起始索引。
     *
     * 当行内包含宽字符或代理项对时，列号与字符索引不是一一对应，
     * 需要从头扫描累加显示宽度来定位。无宽字符时直接返回列号（O(1) 快速路径）。
     */
    private fun findStartOfColumn(column: Int): Int {
        if (column == mColumns) return mSpaceUsed
        if (!mHasNonOneWidthOrSurrogateChars) return column

        mText.forEachColumn(0, mSpaceUsed) { i, col, _, w, charCount ->
            if (w > 0) {
                val currentColumn = col + w
                if (currentColumn == column) {
                    var newCharIndex = i + charCount
                    while (newCharIndex < mSpaceUsed) {
                        if (WcWidth.width(mText, newCharIndex) <= 0) {
                            newCharIndex += mText.charCountAtSafe(newCharIndex, mSpaceUsed)
                        } else {
                            break
                        }
                    }
                    return newCharIndex
                } else if (currentColumn > column) {
                    return i
                }
            }
            true
        }
        return mSpaceUsed
    }

    /**
     * 逐码点迭代本行内容，替代消费者手写的 `withCodePointAt + WcWidth + 列推进` 状态机。
     *
     * 回调参数：
     * - [column] 码点起始显示列；正宽码点恒 < 列数，仅零宽码点可能因前一个宽字符而 ≥ 列数
     * - [charStart]/[charEnd] 该码点在 [textChars] 中的区间，[charEnd] 不含其后跟随的零宽码点
     * - [width] 显示宽度，0 或负数表示零宽（组合字符），此类码点不推进列
     * - [rawStyle]/[extEffect] 同帧成对返回：正宽码点取其起始列的槽，零宽码点进位复用所属
     *   正宽码点的槽（写入方 [setChar] 把组合字符样式写在基字符列上，两者本就同槽）；
     *   行首即零宽码点、无槽可复用时恒为 0/默认，与 reflow 的空行样式初值一致
     *
     * 返回 false 可提前停止。迭代期间不得调用 [setChar]/[clear]/[copyInterval] 变异本行。
     */
    inline fun forEachRun(
        action: (
            column: Int,
            charStart: Int,
            charEnd: Int,
            codePoint: Int,
            width: Int,
            rawStyle: Long,
            extEffect: Long
        ) -> Boolean
    ) {
        var carriedStyleColumn = -1
        mText.forEachColumn(0, mSpaceUsed) { charStart, column, codePoint, width, charCount ->
            val rawStyle: Long
            val extEffect: Long
            if (width > 0) {
                carriedStyleColumn = column
                rawStyle = getRawStyle(column)
                extEffect = getExtendedEffect(column)
            } else if (carriedStyleColumn >= 0) {
                rawStyle = getRawStyle(carriedStyleColumn)
                extEffect = getExtendedEffect(carriedStyleColumn)
            } else {
                rawStyle = 0L
                extEffect = 0L
            }
            action(
                column,
                charStart,
                charStart + charCount,
                codePoint,
                width,
                rawStyle,
                extEffect
            )
        }
    }

    /**
     * 列 [column] 的内容起始 char 索引。
     *
     * 列号落在宽字符后半格内时返回该宽字符的起始索引，两个半格共享同一 char 区间。
     * 越界列钳到行内容边界：列号 ≤ 0 → 0，列号 ≥ 列数 → [charLength]。
     */
    fun startCharOfColumn(column: Int): Int {
        if (column <= 0) return 0
        if (column >= mColumns) return mSpaceUsed
        return findStartOfColumn(column).coerceAtMost(mSpaceUsed)
    }

    /**
     * 列 [column] 的内容结束 char 索引（排他），与 [startCharOfColumn] 构成该列的完整 char 区间。
     *
     * 宽字符的两个半格返回同一区间；行尾之后返回 [charLength]。
     */
    fun endCharOfColumn(column: Int): Int {
        if (column >= mColumns) return mSpaceUsed
        val start = startCharOfColumn(column)
        val next = findStartOfColumn(column + 1)
        val end = if (next > start) next else findStartOfColumn(column + 2)
        return end.coerceAtMost(mSpaceUsed)
    }

    /**
     * 列 [column] 是否为空白格（空格或行内容之外）。
     *
     * 口径与字符内容一致：只看 char 是否为空格，不看样式。
     */
    fun isCellBlank(column: Int): Boolean {
        if (column < 0 || column >= mColumns) return true
        val text = mText
        for (i in startCharOfColumn(column) until endCharOfColumn(column)) {
            if (text[i] != ' ') return false
        }
        return true
    }

    /**
     * 本行内容占用的显示列数（列语义，非 char 数）。
     *
     * 行末宽字符无后半格可用时仍按其显示宽度计入，结果可能等于列数 + 1。
     */
    fun getEffectiveTextLength(): Int {
        var width = 0
        mText.forEachColumn(0, mSpaceUsed) { _, _, _, w, _ ->
            if (w > 0) width += w
            true
        }
        return width
    }

    /**
     * 把列号校准到字符边界：落在宽字符后半格内的列号按 [align] 吸附。
     *
     * 扫描到 NUL 即视为无内容并返回原列号。
     */
    fun snapToColumn(column: Int, align: SnapAlign): Int {
        if (!mHasNonOneWidthOrSurrogateChars) return column

        mText.forEachColumn(0, mSpaceUsed) { _, col, codePoint, width, _ ->
            if (codePoint == 0) return@forEachColumn false
            if (column in (col + 1)..<col + width) {
                return if (align == SnapAlign.Start) col else col + width
            }
            true
        }
        return column
    }

    private fun wideDisplayCharacterStartingAt(column: Int): Boolean {
        mText.forEachColumn(0, mSpaceUsed) { _, col, _, w, _ ->
            if (w > 0) {
                if (col == column && w == 2) return true
                if (col + w > column) return false
            }
            true
        }
        return false
    }

    fun clear(style: TextStyle, extendedEffect: Long = 0L) {
        val ext = normalizeExtendedEffect(style.value, extendedEffect)
        if (!isBlank) mText.fill(' ')
        for (i in 0 until mColumns) {
            mStyle[i * 2] = style.value
            mStyle[i * 2 + 1] = ext
        }
        mSpaceUsed = mColumns
        mHasNonOneWidthOrSurrogateChars = false
        mLineWrap = false
        // 扩展槽中下划线位关闭时不可见的残留（形状已归零、颜色独立）不影响空白判定
        isBlank = (style.foreColor == TextStyle.COLOR_INDEX_FOREGROUND) &&
                  (style.backColor == TextStyle.COLOR_INDEX_BACKGROUND) &&
                  (style.effect == 0)
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
        var columnToSet = columnToSet
        require(!(columnToSet < 0 || columnToSet >= mColumns)) { "TerminalRow.setChar(): columnToSet=$columnToSet, codePoint=$codePoint, style=${style.value}" }

        val ext = normalizeExtendedEffect(style.value, extendedEffect)

        if (codePoint != ' '.code && codePoint != 0) {
            isBlank = false
        } else if (style.foreColor != TextStyle.COLOR_INDEX_FOREGROUND ||
                   style.backColor != TextStyle.COLOR_INDEX_BACKGROUND ||
                   style.effect != 0) {
            isBlank = false
        }

        val isAsciiPrintable = codePoint in 0x20..0x7E
        val newCodePointDisplayWidth = if(isAsciiPrintable) 1 else WcWidth.width(codePoint)

        if (!mHasNonOneWidthOrSurrogateChars) {
            // 快速路径：全为 ASCII 单宽字符，直接替换
            if (isAsciiPrintable) {
                mStyle[columnToSet * 2] = style.value
                mStyle[columnToSet * 2 + 1] = ext
                mText[columnToSet] = codePoint.toChar()
                if (codePoint != 0x20) isBlank = false
                return
            }
            if (codePoint >= Character.MIN_SUPPLEMENTARY_CODE_POINT || newCodePointDisplayWidth != 1) {
                mHasNonOneWidthOrSurrogateChars = true
            } else {
                mStyle[columnToSet * 2] = style.value
                mStyle[columnToSet * 2 + 1] = ext
                mText[columnToSet] = codePoint.toChar()
                return
            }
        }

        val newIsCombining = newCodePointDisplayWidth <= 0
        val wasExtraColForWideChar =
            (columnToSet > 0) && wideDisplayCharacterStartingAt(columnToSet - 1)

        if (newIsCombining) {
            // 组合字符：合并到前一列的字符上
            if (wasExtraColForWideChar) columnToSet--
        } else {
            // 普通/宽字符：如果目标在宽字符后半列，先拆分宽字符
            if (wasExtraColForWideChar) setChar(columnToSet - 1, ' '.code, style, ext)
            val overwritingWideCharInNextColumn =
                newCodePointDisplayWidth == 2 && wideDisplayCharacterStartingAt(columnToSet + 1)
            if (overwritingWideCharInNextColumn) setChar(columnToSet + 1, ' '.code, style, ext)
        }

        // 在 columnToSet 调整（组合字符合并）之后写入样式，确保写入正确的列
        mStyle[columnToSet * 2] = style.value
        mStyle[columnToSet * 2 + 1] = ext

        var text = mText
        val oldStartOfColumnIndex = findStartOfColumn(columnToSet)
        val oldCodePointDisplayWidth = WcWidth.width(text, oldStartOfColumnIndex)

        val oldCharactersUsedForColumn = calculateOldCharactersUsed(columnToSet, oldStartOfColumnIndex, oldCodePointDisplayWidth)

        if (newIsCombining) {
            val combiningCharsCount = WcWidth.zeroWidthCharsCount(
                mText,
                oldStartOfColumnIndex,
                oldStartOfColumnIndex + oldCharactersUsedForColumn
            )
            if (combiningCharsCount >= MAX_COMBINING_CHARACTERS_PER_COLUMN) return
        }

        var newCharactersUsedForColumn = Character.charCount(codePoint)
        if (newIsCombining) {
            newCharactersUsedForColumn += oldCharactersUsedForColumn
        }

        val oldNextColumnIndex = oldStartOfColumnIndex + oldCharactersUsedForColumn
        val newNextColumnIndex = oldStartOfColumnIndex + newCharactersUsedForColumn

        text = shiftTextBuffer(text, oldNextColumnIndex, newNextColumnIndex, newCharactersUsedForColumn - oldCharactersUsedForColumn)
        mSpaceUsed += newCharactersUsedForColumn - oldCharactersUsedForColumn

        Character.toChars(
            codePoint,
            text,
            oldStartOfColumnIndex + (if (newIsCombining) oldCharactersUsedForColumn else 0)
        )

        handleWidthChange(columnToSet, oldCodePointDisplayWidth, newCodePointDisplayWidth, newNextColumnIndex, text, style, ext)
    }

    /**
     * 计算旧字符占用的 char 数量。
     *
     * 宽字符在行尾时可能没有完整的后半列，需用 mSpaceUsed 作为边界。
     */
    private fun calculateOldCharactersUsed(columnToSet: Int, oldStartOfColumnIndex: Int, oldCodePointDisplayWidth: Int): Int {
        return if (columnToSet + oldCodePointDisplayWidth < mColumns) {
            val oldEndOfColumnIndex = findStartOfColumn(columnToSet + oldCodePointDisplayWidth)
            oldEndOfColumnIndex - oldStartOfColumnIndex
        } else {
            mSpaceUsed - oldStartOfColumnIndex
        }
    }

    private fun shiftTextBuffer(text: CharArray, oldNextColumnIndex: Int, newNextColumnIndex: Int, charDifference: Int): CharArray {
        var text = text
        if (charDifference > 0) {
            val oldCharactersAfterColumn = mSpaceUsed - oldNextColumnIndex
            if (mSpaceUsed + charDifference > text.size) {
                val newText = CharArray(text.size + mColumns)
                text.copyInto(destination = newText, endIndex = oldNextColumnIndex)
                text.copyInto(
                    destination = newText,
                    destinationOffset = newNextColumnIndex,
                    startIndex = oldNextColumnIndex,
                    endIndex = oldNextColumnIndex + oldCharactersAfterColumn
                )
                text = newText
                mText = text
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
                endIndex = mSpaceUsed
            )
        }
        return text
    }

    /**
     * 当新旧字符宽度不同时，调整后续内容的缓冲区。
     *
     * 宽→窄：在新字符后插入一个空格占位
     * 窄→宽：删除原后半列的字符
     */
    private fun handleWidthChange(columnToSet: Int, oldCodePointDisplayWidth: Int, newCodePointDisplayWidth: Int, newNextColumnIndex: Int, text: CharArray, style: TextStyle, extendedEffect: Long) {
        var text = text
        if (oldCodePointDisplayWidth == 2 && newCodePointDisplayWidth == 1) {
            // 根源：宽字符起始于行末列时，其后半格在行外，columnToSet+1 即越出行界。
            // 此时写 mStyle[(columnToSet+1)*2] 会超出 mStyle(mColumns*2) 的最大索引，
            // 且行末没有后半格内容需要保留，右移插入占位空格并 ++mSpaceUsed 会产出幽灵列。
            // 故行末只保留 setChar 已写入的单列降级结果，整个后半格调整跳过。
            if (columnToSet + 1 < mColumns) {
                if (mSpaceUsed + 1 > text.size) {
                    val newText = CharArray(text.size + mColumns)
                    text.copyInto(destination = newText, endIndex = newNextColumnIndex)
                    text.copyInto(
                        destination = newText,
                        destinationOffset = newNextColumnIndex + 1,
                        startIndex = newNextColumnIndex,
                        endIndex = mSpaceUsed
                    )
                    text = newText
                    mText = text
                } else {
                    text.copyInto(
                        destination = text,
                        destinationOffset = newNextColumnIndex + 1,
                        startIndex = newNextColumnIndex,
                        endIndex = mSpaceUsed
                    )
                }
                text[newNextColumnIndex] = ' '
                mStyle[(columnToSet + 1) * 2] = style.value
                mStyle[(columnToSet + 1) * 2 + 1] = extendedEffect
                ++mSpaceUsed
            }
        } else if (oldCodePointDisplayWidth == 1 && newCodePointDisplayWidth == 2) {
            if (columnToSet == mColumns - 1) {
                val oldCharCount = if (text[newNextColumnIndex - 1].isHighSurrogate() && newNextColumnIndex - 2 >= 0 && !text[newNextColumnIndex - 2].isHighSurrogate()) 2 else 1
                if (oldCharCount == 2) {
                    text[newNextColumnIndex - 2] = ' '.code.toChar()
                }
                text[newNextColumnIndex - 1] = ' '.code.toChar()
            } else if (columnToSet == mColumns - 2) {
                mSpaceUsed = newNextColumnIndex
            } else {
                val newNextNextColumnIndex =
                    newNextColumnIndex + (if (mText[newNextColumnIndex].isHighSurrogate()) 2 else 1)
                val nextLen = newNextNextColumnIndex - newNextColumnIndex

                text.copyInto(
                    destination = text,
                    destinationOffset = newNextColumnIndex,
                    startIndex = newNextNextColumnIndex,
                    endIndex = mSpaceUsed
                )
                mSpaceUsed -= nextLen
            }
        }
    }

    inline fun getStyle(column: Int): TextStyle {
        return TextStyle(mStyle[column * 2])
    }

    /**
     * 读取主样式原始值。
     *
     * 断言「下划线位为 0 时扩展槽形状为 0」，使新增写入路径若绕过 [normalizeExtendedEffect]
     * 会在测试中立即暴露，而不是等渲染出错。
     */
    inline fun getRawStyle(column: Int): Long {
        val raw = mStyle[column * 2]
        assert(
            raw and TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE.toLong() != 0L ||
                (mStyle[column * 2 + 1] and TextStyle.EXT_UNDERLINE_STYLE_MASK) == 0L
        ) { "下划线位关闭时扩展槽下划线形状必须为 0：column=$column raw=$raw ext=${mStyle[column * 2 + 1]}" }
        return raw
    }

    inline fun getExtendedEffect(column: Int): Long = mStyle[column * 2 + 1]

    /**
     * 修改指定列主样式的特效位，保证双槽位状态一致。
     *
     * @param bits 特效位掩码
     * @param setOrClear true=置位，false=清位
     * @param reverse true=翻转 bits 中的位（bits 中原本置位的清零、清零的置位）
     */
    fun modifyEffect(column: Int, bits: Int, setOrClear: Boolean, reverse: Boolean) {
        val raw = mStyle[column * 2]
        val effect = (raw and TextStyle.EFFECT_MASK).toInt()
        val newEffect = when {
            reverse -> (effect and bits.inv()) or (bits and effect.inv())
            setOrClear -> effect or bits
            else -> effect and bits.inv()
        }
        mStyle[column * 2] =
            (raw and TextStyle.EFFECT_MASK.inv()) or (newEffect.toLong() and TextStyle.EFFECT_MASK)
        mStyle[column * 2 + 1] = normalizeExtendedEffect(mStyle[column * 2], mStyle[column * 2 + 1])
        // 特效位非零即非默认样式，与 setChar 的空白判定口径一致；
        // 清位后无法在不扫描整行的前提下判定能否恢复空白，保守保持非空白
        if (newEffect != 0) isBlank = false
    }

    /**
     * 下划线开关与扩展槽下划线形状的单向派生：开关关闭时形状归零。
     *
     * 颜色位（bit 3 及以上）独立于开关（SGR 58 可单独设色），予以保留。
     * 下划线形状是位布局上唯一跨槽位耦合的字段，收口在此处，
     * 使 [mStyle] 恒满足「开关关闭 ⇒ 形状为 0」的不变量。
     */
    private fun normalizeExtendedEffect(styleValue: Long, extendedEffect: Long): Long {
        val underlineOn = styleValue and TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE.toLong() != 0L
        return if (underlineOn) extendedEffect
        else extendedEffect and TextStyle.EXT_UNDERLINE_STYLE_MASK.inv()
    }

    companion object {
        private const val SPARE_CAPACITY_FACTOR = 1.5f
        private const val MAX_COMBINING_CHARACTERS_PER_COLUMN = 15
    }
}
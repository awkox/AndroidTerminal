package com.awkoo.terminal.ui.view

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Typeface
import com.awkoo.terminal.constants.TerminalCursorStyle
import com.awkoo.terminal.core.TerminalEmulator
import com.awkoo.terminal.core.TextStyle
import com.awkoo.terminal.core.WcWidth
import com.awkoo.terminal.core.charCountAtSafe
import com.awkoo.terminal.core.withCodePointAt
import kotlin.math.abs
import kotlin.math.ceil

/**
 * 将 [TerminalEmulator] 渲染到 [Canvas]。
 *
 * 缓存字体度量信息，字体或字号变更时需重新创建。
 */
class TerminalRenderer(textSize: Int, typeface: Typeface) {
    private val mTextPaint = Paint()

    val fontWidth: Float
    val fontLineSpacing: Int
    private val mFontAscent: Int
    val mFontLineSpacingAndAscent: Int
    private val asciiMeasures = FloatArray(127)

    init {
        mTextPaint.textSize = textSize.toFloat()
        mTextPaint.typeface = typeface
        mTextPaint.isAntiAlias = true

        this.fontLineSpacing = ceil(mTextPaint.fontSpacing.toDouble()).toInt()
        mFontAscent = ceil(mTextPaint.ascent().toDouble()).toInt()
        mFontLineSpacingAndAscent = this.fontLineSpacing + mFontAscent
        this.fontWidth = mTextPaint.measureText("X")

        val sb = StringBuilder(" ")
        for (i in asciiMeasures.indices) {
            sb.setCharAt(0, i.toChar())
            asciiMeasures[i] = mTextPaint.measureText(sb, 0, 1)
        }
    }

    fun render(
        mEmulator: TerminalEmulator, canvas: Canvas, topRow: Int,
        selectionY1: Int, selectionY2: Int, selectionX1: Int, selectionX2: Int
    ) {
        val reverseVideo = mEmulator.isReverseVideo
        val endRow = topRow + mEmulator.mRows
        val columns = mEmulator.mColumns
        val cursorCol = mEmulator.cursorCol
        val cursorRow = mEmulator.cursorRow
        val cursorVisible = mEmulator.isCursorVisible
        val screen = mEmulator.screen
        val palette = mEmulator.mColors.mCurrentColors
        val cursorShape = mEmulator.cursorStyle

        if (reverseVideo) canvas.drawColor(
            palette[TextStyle.COLOR_INDEX_FOREGROUND],
            PorterDuff.Mode.SRC
        )

        var heightOffset = mFontLineSpacingAndAscent.toFloat()
        for (row in topRow..<endRow) {
            heightOffset += fontLineSpacing.toFloat()
            renderRow(canvas, mEmulator, row, heightOffset, columns, cursorCol, cursorRow, cursorVisible, selectionY1, selectionY2, selectionX1, selectionX2, reverseVideo, palette, cursorShape, screen)
        }
    }

    /**
     * 渲染单行终端内容。
     *
     * 逐列扫描，将连续相同样式的字符合并为一个文本绘制运行（run），
     * 处理光标高亮、选区反色、字体宽度不匹配等视觉效果，最后批量绘制。
     */
    private fun renderRow(
        canvas: Canvas,
        mEmulator: TerminalEmulator,
        row: Int,
        heightOffset: Float,
        columns: Int,
        cursorCol: Int,
        cursorRow: Int,
        cursorVisible: Boolean,
        selectionY1: Int,
        selectionY2: Int,
        selectionX1: Int,
        selectionX2: Int,
        reverseVideo: Boolean,
        palette: IntArray,
        cursorShape: TerminalCursorStyle,
        screen: com.awkoo.terminal.core.TerminalBuffer
    ) {
        val cursorX = if (row == cursorRow && cursorVisible) cursorCol else -1
        var selx1 = -1
        var selx2 = -1
        if (row in selectionY1..selectionY2) {
            if (row == selectionY1) selx1 = selectionX1
            selx2 = if (row == selectionY2) selectionX2 else mEmulator.mColumns
        }

        val lineObject = screen.allocateFullLineIfNecessary(screen.externalToInternalRow(row))
        val line = lineObject.mText
        val charsUsedInLine = lineObject.mSpaceUsed

        var lastRunRawStyle: Long = 0L
        var lastRunInsideCursor = false
        var lastRunInsideSelection = false
        var lastRunStartColumn = -1
        var lastRunStartIndex = 0
        var lastRunFontWidthMismatch = false
        var currentCharIndex = 0
        var measuredWidthForRun = 0f

        var column = 0
        while (column < columns) {
            line.withCodePointAt(currentCharIndex, charsUsedInLine) { codePoint, charsForCodePoint ->
                val codePointWcWidth = WcWidth.width(codePoint)
                val insideCursor = (cursorX == column || (codePointWcWidth == 2 && cursorX == column + 1))
                val insideSelection = column in selx1..selx2
                val rawStyle = lineObject.getRawStyle(column)

                val measuredCodePointWidth =
                    if (codePoint < asciiMeasures.size) asciiMeasures[codePoint] 
                    else mTextPaint.measureText(line, currentCharIndex, charsForCodePoint)
                    
                val fontWidthMismatch = abs(measuredCodePointWidth / this.fontWidth - codePointWcWidth) > 0.01

                // 直接比较原始 Long，避免 TextStyle 装箱
                // 当样式/光标/选区/字体宽度任一变化时，中断当前文本运行
                if (rawStyle != lastRunRawStyle ||
                    insideCursor != lastRunInsideCursor ||
                    insideSelection != lastRunInsideSelection ||
                    fontWidthMismatch ||
                    lastRunFontWidthMismatch) {
                    if (column == 0) {
                        // 跳过首列（无可绘制内容），仅记录当前样式
                    } else {
                        drawTextRun(
                            canvas,
                            line,
                            palette,
                            heightOffset,
                            lastRunStartColumn,
                            column - lastRunStartColumn,
                            lastRunStartIndex,
                            currentCharIndex - lastRunStartIndex,
                            measuredWidthForRun,
                            if (lastRunInsideCursor) mEmulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_CURSOR] else 0,
                            cursorShape,
                            TextStyle(lastRunRawStyle),
                            reverseVideo || (lastRunInsideCursor && cursorShape == TerminalCursorStyle.BLOCK) || lastRunInsideSelection,
                            mEmulator
                        )
                    }
                    measuredWidthForRun = 0f
                    lastRunRawStyle = rawStyle
                    lastRunInsideCursor = insideCursor
                    lastRunInsideSelection = insideSelection
                    lastRunStartColumn = column
                    lastRunStartIndex = currentCharIndex
                    lastRunFontWidthMismatch = fontWidthMismatch
                }
                measuredWidthForRun += measuredCodePointWidth
                column += codePointWcWidth
                currentCharIndex += charsForCodePoint
                
                // 跳过后续的零宽字符（如组合字符）
                while (currentCharIndex < charsUsedInLine && WcWidth.width(line, currentCharIndex) <= 0) {
                    currentCharIndex += line.charCountAtSafe(currentCharIndex, charsUsedInLine)
                }
            }
        }

        drawTextRun(
            canvas,
            line,
            palette,
            heightOffset,
            lastRunStartColumn,
            columns - lastRunStartColumn,
            lastRunStartIndex,
            currentCharIndex - lastRunStartIndex,
            measuredWidthForRun,
            if (lastRunInsideCursor) mEmulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_CURSOR] else 0,
            cursorShape,
            TextStyle(lastRunRawStyle),
            reverseVideo || (lastRunInsideCursor && cursorShape == TerminalCursorStyle.BLOCK) || lastRunInsideSelection,
            mEmulator
        )
    }

    /**
     * 绘制一个文本运行（连续相同样式的字符片段）。
     *
     * 处理背景色绘制、字符宽度与字体宽度不匹配时的缩放、
     * 组合字符偏移、代理项对处理、以及字体宽度缩放阈值判定。
     */
    private fun drawTextRun(
        canvas: Canvas,
        text: CharArray,
        palette: IntArray,
        y: Float,
        startColumn: Int,
        runWidthColumns: Int,
        startCharIndex: Int,
        runWidthChars: Int,
        mes: Float,
        cursor: Int,
        cursorStyle: TerminalCursorStyle,
        textStyle: TextStyle,
        reverseVideo: Boolean,
        emulator: TerminalEmulator
    ) {
        var mes = mes
        var foreColor = textStyle.foreColor
        var backColor = textStyle.backColor
        
        val bold = textStyle.isBold
        val blink = textStyle.isBlink
        val underline = textStyle.isUnderline
        val italic = textStyle.isItalic
        val strikeThrough = textStyle.isStrikeThrough
        val dim = textStyle.isDim
        val invisible = textStyle.isInvisible

        if ((foreColor and -0x1000000) != -0x1000000) {
            // 粗体使用前 8 色中的亮色（如果适用）
            if (bold && foreColor >= 0 && foreColor < 8) foreColor += 8
            foreColor = palette[foreColor]
        }

        if ((backColor and -0x1000000) != -0x1000000) {
            backColor = palette[backColor]
        }

        val reverseVideoHere = reverseVideo xor textStyle.isInverse
        if (reverseVideoHere) {
            val tmp = foreColor
            foreColor = backColor
            backColor = tmp
        }

        var left = startColumn * this.fontWidth
        var right = left + runWidthColumns * this.fontWidth

        // 如果字体渲染宽度与列宽不匹配，缩放 Canvas 以适配
        mes /= this.fontWidth
        var savedMatrix = false
        if (abs(mes - runWidthColumns) > 0.01) {
            canvas.save()
            canvas.scale(runWidthColumns / mes, 1f)
            left *= mes / runWidthColumns
            right *= mes / runWidthColumns
            savedMatrix = true
        }

        if (backColor != palette[TextStyle.COLOR_INDEX_BACKGROUND]) {
            mTextPaint.color = backColor
            canvas.drawRect(left, y - mFontLineSpacingAndAscent + mFontAscent, right, y, mTextPaint)
        }

        if (cursor != 0) {
            mTextPaint.color = cursor
            var cursorHeight = (mFontLineSpacingAndAscent - mFontAscent).toFloat()
            if (cursorStyle == TerminalCursorStyle.UNDERLINE) cursorHeight /= 4.0.toFloat()
            else if (cursorStyle == TerminalCursorStyle.BAR) right -= (((right - left) * 3) / 4.0).toFloat()
            canvas.drawRect(left, y - cursorHeight, right, y, mTextPaint)
        }

        val textIsVisible = !blink || emulator.isTextVisible

        if (!invisible && textIsVisible) {
            if (dim) {
                var red = (0xFF and (foreColor shr 16))
                var green = (0xFF and (foreColor shr 8))
                var blue = (0xFF and foreColor)
                red = red * 2 / 3
                green = green * 2 / 3
                blue = blue * 2 / 3
                foreColor = -0x1000000 + (red shl 16) + (green shl 8) + blue
            }

            mTextPaint.isFakeBoldText = bold
            mTextPaint.isUnderlineText = underline
            mTextPaint.textSkewX = if (italic) -0.35f else 0f
            mTextPaint.isStrikeThruText = strikeThrough
            mTextPaint.color = foreColor

            canvas.drawTextRun(
                text,
                startCharIndex,
                runWidthChars,
                startCharIndex,
                runWidthChars,
                left,
                y - mFontLineSpacingAndAscent,
                false,
                mTextPaint
            )
        }

        if (savedMatrix) canvas.restore()
    }
}
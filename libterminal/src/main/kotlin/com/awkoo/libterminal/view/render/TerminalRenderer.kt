package com.awkoo.libterminal.view.render

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathDashPathEffect
import android.graphics.PathEffect
import android.graphics.PorterDuff
import android.graphics.Typeface
import com.awkoo.libterminal.engine.TerminalCursorStyle
import com.awkoo.libterminal.engine.buffer.TerminalRow
import com.awkoo.libterminal.text.TextStyle
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

/**
 * 将 [TerminalRenderSnapshot] 渲染到 [Canvas]。
 *
 * 缓存字体度量信息，字体或字号变更时需重新创建。
 */
internal class GlyphWidthCache(
    private val asciiMeasures: FloatArray,
    private val measureRange: (CharArray, Int, Int) -> Float
) {
    private val bmpMeasureCache = FloatArray(0x10000) { -1f }

    fun measure(line: CharArray, charStart: Int, charsForCodePoint: Int, codePoint: Int): Float {
        if (charsForCodePoint == 1) {
            if (codePoint < asciiMeasures.size) return asciiMeasures[codePoint]
            if (codePoint < bmpMeasureCache.size) {
                var cached = bmpMeasureCache[codePoint]
                if (cached < 0f) {
                    cached = measureRange(line, charStart, 1)
                    bmpMeasureCache[codePoint] = cached
                }
                return cached
            }
        }
        return measureRange(line, charStart, charsForCodePoint)
    }
}

internal class TerminalRenderer(textSize: Int, typeface: Typeface) {
    private val textPaint = Paint()
    private val measurePaint = Paint()

    @JvmField
    val fontWidth: Float
    @JvmField
    val fontLineSpacing: Int
    private val fontAscent: Int
    @JvmField
    val baselineOffset: Int
    private val asciiMeasures = FloatArray(127)
    private val glyphWidthCache = GlyphWidthCache(asciiMeasures) { line, start, count ->
        measurePaint.measureText(line, start, count)
    }

    // 下划线独立画笔：描边模式，粗细随字号在 init 中设定，避免依赖 textPaint 状态被文本绘制覆盖
    private val underlinePaint = Paint().apply {
        style = Paint.Style.STROKE
        isAntiAlias = true
    }
    private val underlineThickness: Float
    private val underlineOffset: Float
    private val dashedEffect: PathEffect
    private val dottedEffect: PathEffect
    private val curlyEffect: PathEffect

    init {
        require(textSize > 0) { "textSize must be > 0, got $textSize" }
        measurePaint.textSize = textSize.toFloat()
        measurePaint.typeface = typeface
        measurePaint.isAntiAlias = true

        textPaint.textSize = textSize.toFloat()
        textPaint.typeface = typeface
        textPaint.isAntiAlias = true

        this.fontLineSpacing = max(1, ceil(measurePaint.fontSpacing.toDouble()).toInt())
        fontAscent = ceil(measurePaint.ascent().toDouble()).toInt()
        baselineOffset = this.fontLineSpacing + fontAscent
        this.fontWidth = max(1f, measurePaint.measureText("X"))

        val oneChar = CharArray(1)
        for (i in asciiMeasures.indices) {
            oneChar[0] = i.toChar()
            asciiMeasures[i] = measurePaint.measureText(oneChar, 0, 1)
        }

        // 下划线几何参数随字号缩放，保证不同字号下视觉比例一致
        underlineThickness = max(1f, textSize / 15f)
        underlineOffset = underlineThickness * 1.5f
        underlinePaint.strokeWidth = underlineThickness

        // 虚线/点线相位以屏幕原点为基准，跨文本运行保持连续
        dashedEffect = DashPathEffect(floatArrayOf(fontWidth * 0.6f, fontWidth * 0.4f), 0f)
        dottedEffect = DashPathEffect(floatArrayOf(underlineThickness, underlineThickness * 2f), 0f)

        // 波浪特效以单列宽为周期平铺印章，相邻列波形无缝衔接
        val wavePath = Path().apply {
            val waveLen = fontWidth
            val halfL = waveLen / 2f
            // 振幅，设为字宽的 12%，保证波形明显且优雅
            val amp = waveLen * 0.12f 
            // 丝带的粗细，比普通下划线略粗一点更清晰
            val thickness = underlineThickness * 1.5f
            
            // 1. 上边缘 (从左到右正向绘制完美正弦波)
            moveTo(0f, 0f)
            cubicTo(halfL * 0.364f, -amp, halfL * 0.636f, -amp, halfL, 0f)
            cubicTo(halfL + halfL * 0.364f, amp, halfL + halfL * 0.636f, amp, waveLen, 0f)
            
            // 2. 右侧边缘闭合，并下移厚度
            lineTo(waveLen, thickness)
            
            // 3. 下边缘 (从右到左反向绘制，控制点需加上 thickness)
            cubicTo(
                halfL + halfL * 0.636f, amp + thickness,
                halfL + halfL * 0.364f, amp + thickness,
                halfL, thickness
            )
            cubicTo(
                halfL * 0.636f, -amp + thickness,
                halfL * 0.364f, -amp + thickness,
                0f, thickness
            )
            
            // 4. 闭合左侧边缘
            close()
        }
        curlyEffect = PathDashPathEffect(wavePath, fontWidth, 0f, PathDashPathEffect.Style.TRANSLATE)
    }

    fun render(
        snapshot: TerminalRenderSnapshot,
        resolver: TerminalPaletteResolver,
        canvas: Canvas,
        selectionY1: Int,
        selectionY2: Int,
        selectionX1: Int,
        selectionX2: Int
    ) {
        val reverseVideo = snapshot.reverseVideo
        val topRow = snapshot.topRow
        val rows = snapshot.rows
        val columns = snapshot.columns
        val cursorCol = snapshot.cursorCol
        val cursorRow = snapshot.cursorRow
        val cursorVisible = snapshot.cursorVisible
        val cursorShape = snapshot.cursorStyle
        val textVisible = snapshot.textVisible

        if (reverseVideo) canvas.drawColor(
            resolver.foreground,
            PorterDuff.Mode.SRC
        )

        var heightOffset = baselineOffset.toFloat()
        for (viewportIndex in 0 until rows) {
            val row = topRow + viewportIndex
            heightOffset += fontLineSpacing.toFloat()
            renderRow(
                canvas = canvas,
                lineObject = snapshot.rowAt(viewportIndex),
                row = row,
                heightOffset = heightOffset,
                columns = columns,
                cursorCol = cursorCol,
                cursorRow = cursorRow,
                cursorVisible = cursorVisible,
                textVisible = textVisible,
                selectionY1 = selectionY1,
                selectionY2 = selectionY2,
                selectionX1 = selectionX1,
                selectionX2 = selectionX2,
                reverseVideo = reverseVideo,
                resolver = resolver,
                cursorShape = cursorShape
            )
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
        lineObject: TerminalRow,
        row: Int,
        heightOffset: Float,
        columns: Int,
        cursorCol: Int,
        cursorRow: Int,
        cursorVisible: Boolean,
        textVisible: Boolean,
        selectionY1: Int,
        selectionY2: Int,
        selectionX1: Int,
        selectionX2: Int,
        reverseVideo: Boolean,
        resolver: TerminalPaletteResolver,
        cursorShape: TerminalCursorStyle
    ) {
        val cursorX = if (row == cursorRow && cursorVisible) cursorCol else -1
        var selx1 = -1
        var selx2 = -1
        if (row in selectionY1..selectionY2) {
            if (row == selectionY1) selx1 = selectionX1
            selx2 = if (row == selectionY2) selectionX2 else columns
        }

        val line = lineObject.textChars()

        var lastRunRawStyle: Long = 0L
        var lastRunExtEffect: Long = 0L
        var lastRunInsideCursor = false
        var lastRunInsideSelection = false
        var lastRunStartColumn = -1
        var lastRunStartIndex = 0
        var lastRunFontWidthMismatch = false
        var currentCharIndex = 0
        var measuredWidthForRun = 0f

        lineObject.forEachCell { column, charStart, charEnd, cellWcWidth, rawStyle, extEffect ->
            if (column >= columns) return@forEachCell false

            val insideCursor = (cursorX == column || (cellWcWidth == 2 && cursorX == column + 1))
            val insideSelection = column in selx1..selx2 || (cellWcWidth == 2 && (column + 1) in selx1..selx2)
            val clusterChars = charEnd - charStart
            val firstCodePoint = if (clusterChars > 0) Character.codePointAt(line, charStart, charEnd) else ' '.code

            val measuredClusterWidth =
                glyphWidthCache.measure(line, charStart, clusterChars, firstCodePoint)

            val fontWidthMismatch = abs(measuredClusterWidth / this.fontWidth - cellWcWidth) > 0.01

            // 直接比较原始 Long，避免 TextStyle 装箱
            // 当样式/扩展特效/光标/选区/宽失配任一变化，或前后任一为失配宽单元格时中断运行：
            // 失配单元格独立绘制与缩放，防止多个表情/宽字符合并后按平均比例缩放引起偏移或右半截裁剪。
            if (lastRunStartColumn < 0 ||
                rawStyle != lastRunRawStyle ||
                extEffect != lastRunExtEffect ||
                insideCursor != lastRunInsideCursor ||
                insideSelection != lastRunInsideSelection ||
                lastRunFontWidthMismatch ||
                fontWidthMismatch
            ) {
                if (lastRunStartColumn >= 0) {
                    drawTextRun(
                        canvas,
                        line,
                        resolver,
                        heightOffset,
                        lastRunStartColumn,
                        column - lastRunStartColumn,
                        lastRunStartIndex,
                        currentCharIndex - lastRunStartIndex,
                        measuredWidthForRun,
                        if (lastRunInsideCursor) resolver.cursor else 0,
                        cursorShape,
                        TextStyle(lastRunRawStyle),
                        lastRunExtEffect,
                        reverseVideo || (lastRunInsideCursor && cursorShape == TerminalCursorStyle.BLOCK) || lastRunInsideSelection,
                        textVisible,
                        columns
                    )
                }
                measuredWidthForRun = 0f
                lastRunRawStyle = rawStyle
                lastRunExtEffect = extEffect
                lastRunInsideCursor = insideCursor
                lastRunInsideSelection = insideSelection
                lastRunStartColumn = column
                lastRunStartIndex = charStart
                lastRunFontWidthMismatch = fontWidthMismatch
            }
            measuredWidthForRun += measuredClusterWidth
            currentCharIndex = charEnd
            true
        }

        if (lastRunStartColumn >= 0) {
            drawTextRun(
                canvas,
                line,
                resolver,
                heightOffset,
                lastRunStartColumn,
                columns - lastRunStartColumn,
                lastRunStartIndex,
                currentCharIndex - lastRunStartIndex,
                measuredWidthForRun,
                if (lastRunInsideCursor) resolver.cursor else 0,
                cursorShape,
                TextStyle(lastRunRawStyle),
                lastRunExtEffect,
                reverseVideo || (lastRunInsideCursor && cursorShape == TerminalCursorStyle.BLOCK) || lastRunInsideSelection,
                textVisible,
                columns
            )
        }
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
        resolver: TerminalPaletteResolver,
        y: Float,
        startColumn: Int,
        runWidthColumns: Int,
        startCharIndex: Int,
        runWidthChars: Int,
        mes: Float,
        cursor: Int,
        cursorStyle: TerminalCursorStyle,
        textStyle: TextStyle,
        extendedEffect: Long,
        reverseVideo: Boolean,
        textVisible: Boolean,
        columns: Int
    ) {
        var mes = mes
        val colors = resolver.resolve(textStyle, reverseVideo)
        val foreColor = (colors ushr 32).toInt()
        val backColor = colors.toInt()

        val bold = textStyle.isBold
        val blink = textStyle.isBlink
        val underline = textStyle.isUnderline
        val italic = textStyle.isItalic
        val strikeThrough = textStyle.isStrikeThrough
        val invisible = textStyle.isInvisible
        // 扩展特效中的下划线样式，NONE 表示无自定义下划线
        val underlineStyle = TextStyle.decodeUnderlineStyle(extendedEffect)

        var left = startColumn * this.fontWidth
        var right = left + runWidthColumns * this.fontWidth

        // 如果字体渲染宽度与列宽不匹配，缩放 Canvas 以适配
        mes /= this.fontWidth
        var savedMatrix = false
        if (mes > 0f && abs(mes - runWidthColumns) > 0.01) {
            canvas.save()
            canvas.scale(runWidthColumns / mes, 1f)
            left *= mes / runWidthColumns
            right *= mes / runWidthColumns
            savedMatrix = true
        }

        if (backColor != resolver.background) {
            textPaint.color = backColor
            canvas.drawRect(left, y - baselineOffset + fontAscent, right, y, textPaint)
        }

        if (cursor != 0) {
            textPaint.color = cursor
            var cursorHeight = (baselineOffset - fontAscent).toFloat()
            if (cursorStyle == TerminalCursorStyle.UNDERLINE) cursorHeight /= 4.0.toFloat()
            else if (cursorStyle == TerminalCursorStyle.BAR) right -= (((right - left) * 3) / 4.0).toFloat()
            canvas.drawRect(left, y - cursorHeight, right, y, textPaint)
        }

        val textIsVisible = !blink || textVisible

        if (!invisible && textIsVisible) {
            textPaint.isFakeBoldText = bold
            // 带扩展下划线样式时由独立路径绘制，画笔下划线仅回退用于无扩展样式的数据
            textPaint.isUnderlineText = underline && underlineStyle == TextStyle.UNDERLINE_STYLE_NONE
            textPaint.textSkewX = if (italic) -0.35f else 0f
            textPaint.isStrikeThruText = strikeThrough
            textPaint.color = foreColor

            canvas.drawTextRun(
                text,
                startCharIndex,
                runWidthChars,
                startCharIndex,
                runWidthChars,
                left,
                y - baselineOffset,
                false,
                textPaint
            )
        }

        if (savedMatrix) canvas.restore()

        // 自定义下划线绘制：开关与形状的一致性由 TerminalRow 写入时保证（形状非零 ⇒ 开关已开）；
        // 可见性与文字保持一致（隐藏属性、闪烁熄灭阶段不绘制）
        if (underlineStyle != TextStyle.UNDERLINE_STYLE_NONE && !invisible && textIsVisible) {
            // 解析下划线颜色：默认跟随文字前景色（含 dim/reverseVideo 后的最终渲染色）
            val decodedColor = TextStyle.decodeUnderlineColor(extendedEffect)
            underlinePaint.color = if (decodedColor != TextStyle.COLOR_INDEX_FOREGROUND) {
                resolver.resolveColor(decodedColor)
            } else {
                foreColor
            }

            // 矩阵已还原，使用绝对坐标绘制，避免字体宽度缩放导致线条粗细变形
            val ulY = y - baselineOffset + underlineOffset
            val absoluteLeft = startColumn * this.fontWidth
            val absoluteRight = absoluteLeft + runWidthColumns * this.fontWidth

            canvas.save()
            canvas.clipRect(
                absoluteLeft,
                ulY - underlineThickness * 10f, // 垂直方向彻底放开裁剪，防止波峰被削平
                absoluteRight,
                ulY + underlineThickness * 10f
            )
            when (underlineStyle) {
                TextStyle.UNDERLINE_STYLE_SINGLE -> {
                    underlinePaint.pathEffect = null
                    underlinePaint.strokeCap = Paint.Cap.BUTT
                    canvas.drawLine(absoluteLeft, ulY, absoluteRight, ulY, underlinePaint)
                }
                TextStyle.UNDERLINE_STYLE_DOUBLE -> {
                    underlinePaint.pathEffect = null
                    underlinePaint.strokeCap = Paint.Cap.BUTT
                    val gap = underlineThickness * 1.5f
                    canvas.drawLine(absoluteLeft, ulY - gap / 2, absoluteRight, ulY - gap / 2, underlinePaint)
                    canvas.drawLine(absoluteLeft, ulY + gap / 2, absoluteRight, ulY + gap / 2, underlinePaint)
                }
                else -> {
                    underlinePaint.strokeCap = when (underlineStyle) {
                        TextStyle.UNDERLINE_STYLE_DOTTED -> Paint.Cap.ROUND
                        else -> Paint.Cap.SQUARE
                    }
                    underlinePaint.pathEffect = when (underlineStyle) {
                        TextStyle.UNDERLINE_STYLE_CURLY -> curlyEffect
                        TextStyle.UNDERLINE_STYLE_DOTTED -> dottedEffect
                        else -> dashedEffect
                    }
                    // 从屏幕原点起笔使特效相位全局对齐，clipRect 裁出当前运行区间
                    canvas.drawLine(0f, ulY, columns * this.fontWidth, ulY, underlinePaint)
                }
            }
            canvas.restore()
        }
    }
}
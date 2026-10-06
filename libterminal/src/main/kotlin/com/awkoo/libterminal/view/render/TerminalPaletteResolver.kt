package com.awkoo.libterminal.view.render

import com.awkoo.libterminal.color.SparsePalette
import com.awkoo.libterminal.color.TerminalColorScheme
import com.awkoo.libterminal.text.TextStyle
import com.awkoo.libterminal.text.TextStyle.Companion.isTrueColor

/**
 * 终端渲染侧的合成颜色查询对象。
 *
 * 将[主题基底][TerminalColorScheme]与 OSC 稀疏覆盖板([SparsePalette])合成：
 * 被 OSC 覆盖过的槽位取其覆盖值，否则回退到主题基底。渲染时按此查询取色，
 * 使 shell 的动态改色仅作为一层覆盖盖在主题之上，复位只清空覆盖板。
 *
 * 同时承担样式的色彩合成（本就属于颜色领域、而非 Canvas 绘制的运算）：
 * 真彩判定、粗体提亮、反色交换、暗淡，全部收敛在 [resolve] 内，
 * 渲染器只负责把返回的最终 ARGB 交给画笔。
 */
internal class TerminalPaletteResolver(
    private val colorScheme: TerminalColorScheme,
    private val palette: SparsePalette
) {
    /** 取指定索引的主题/OSC 合成色。 */
    private fun colorOf(index: Int): Int =
        if (palette.isOverridden(index)) palette.value(index) else colorScheme.color(index)

    /** 最终默认前景色。 */
    val foreground: Int get() = colorOf(TextStyle.COLOR_INDEX_FOREGROUND)

    /** 最终默认背景色。 */
    val background: Int get() = colorOf(TextStyle.COLOR_INDEX_BACKGROUND)

    /** 最终默认光标色。 */
    val cursor: Int get() = colorOf(TextStyle.COLOR_INDEX_CURSOR)

    /**
     * 解析单个颜色为最终绘制 ARGB：真彩值原样直通，索引色查主题/OSC 合成色。
     *
     * 真彩必须先判后查：覆盖板槽位只有 [TextStyle.NUM_INDEXED_COLORS] 个，
     * 直接把 0xFFRRGGBB 当索引查表会越界。
     */
    fun resolveColor(color: Int): Int = if (color.isTrueColor) color else colorOf(color)

    /**
     * 解析样式为最终绘制色对：高 32 位前景，低 32 位背景。
     *
     * 顺序不可变：真彩判定 → 粗体提亮（仅前景，且在索引空间提亮后才查表）→
     * 查表 → 反色交换 → 暗淡（仅前景，作用于交换后的显示前景色）。
     *
     * @param reverse 外部要求的反色（全局反视频、块光标、选区），与样式自身的反色位异或
     */
    fun resolve(style: TextStyle, reverse: Boolean): Long {
        val rawFore = style.foreColor
        val fore = if (!rawFore.isTrueColor && style.isBold && rawFore >= 0 && rawFore < 8) {
            resolveColor(rawFore + 8)
        } else {
            resolveColor(rawFore)
        }
        var back = resolveColor(style.backColor)
        var outFore = fore
        if (reverse xor style.isInverse) {
            outFore = back
            back = fore
        }
        if (style.isDim) outFore = dim(outFore)
        return (outFore.toLong() shl 32) or (back.toLong() and 0xFFFFFFFFL)
    }

    /** 暗淡：逐通道降至三分之二强度。 */
    private fun dim(color: Int): Int {
        val red = (0xFF and (color shr 16)) * 2 / 3
        val green = (0xFF and (color shr 8)) * 2 / 3
        val blue = (0xFF and color) * 2 / 3
        return TextStyle.TRUE_COLOR_MASK + (red shl 16) + (green shl 8) + blue
    }
}

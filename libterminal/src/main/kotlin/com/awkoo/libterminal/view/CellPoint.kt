package com.awkoo.libterminal.view

import kotlin.math.ceil

/**
 * 单元格索引与像素坐标之间的换算，独立于 Android 便于单测。
 *
 * 像素 → 索引是命中测试（向下取整）；索引 → 像素取该格的**第一个像素**，
 * 因此列轴严格互逆：`xToColumn(columnToX(cx)) == cx`。
 *
 * 行轴的前边界被判给上一格（`mFontLineSpacingAndAscent > 0`），
 * 即 `yToRow(rowToY(cy), ls, mfa) == cy - 1`；需要「第 cy 格之后」的边界时传 `cy + 1`。
 *
 * 两侧均按 Double 精确计算：Float 的舍入会破坏互逆（`30 * 9.1f == 273.0f`，`273 / 9.1f < 30`）。
 */
internal object CellPoint {
    fun xToColumn(x: Float, fontWidth: Float): Int = (x / fontWidth.toDouble()).toInt()

    fun yToRow(y: Float, lineSpacing: Int, lineSpacingAndAscent: Int): Int =
        ((y.toDouble() - lineSpacingAndAscent) / lineSpacing).toInt()

    fun columnToX(column: Int, fontWidth: Float): Int = ceil(column * fontWidth.toDouble()).toInt()

    fun rowToY(row: Int, lineSpacing: Int): Int = row * lineSpacing
}

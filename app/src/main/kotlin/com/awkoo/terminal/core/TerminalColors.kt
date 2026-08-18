package com.awkoo.terminal.core

import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * 终端颜色管理。
 *
 * 持有当前 256 色调色板，可通过 OSC 4 序列动态修改，也可通过 [reset] 恢复默认值。
 */
class TerminalColors {
    /**
     * 当前调色板数组，索引含义参见 [TextStyle] 中的 COLOR_INDEX_* 常量。
     * 通常从主题加载，也可通过 OSC 4 控制序列动态覆盖。
     */
    @JvmField
    val mCurrentColors: IntArray = IntArray(TextStyle.NUM_INDEXED_COLORS)

    /** 使用主题默认颜色初始化。 */
    init {
        reset()
    }

    /** 重置指定索引的颜色为主题默认值。 */
    fun reset(index: Int) {
        mCurrentColors[index] = COLOR_SCHEME.mDefaultColors[index]
    }

    /** 重置所有索引颜色为主题默认值。 */
    fun reset() {
        COLOR_SCHEME.mDefaultColors.copyInto(
            destination = mCurrentColors,
            endIndex = TextStyle.NUM_INDEXED_COLORS
        )
    }

    /**
     * 尝试将文本参数解析为颜色值并写入指定索引。
     *
     * @param intoIndex 目标颜色索引
     * @param textParameter 颜色文本，支持 #RGB、#RRGGBB、rgb:RR/GG/BB 等格式
     */
    fun tryParseColor(intoIndex: Int, textParameter: String) {
        val c: Int = parse(textParameter)
        if (c != 0) mCurrentColors[intoIndex] = c
    }

    companion object {
        /** 全局颜色主题。 */
        @JvmField
        val COLOR_SCHEME: TerminalColorScheme = TerminalColorScheme()

        /**
         * 解析颜色字符串。
         *
         * 支持格式：#RGB、#RRGGBB、#RRRGGGBBB、#RRRRGGGGBBBB、rgb:RR/GG/BB。
         * 解析成功时高位为 1，返回 0xFFRRGGBB；失败返回 0。
         *
         * @see <a href="http://manpages.ubuntu.com/manpages/intrepid/man3/XQueryColor.3.html">XQueryColor</a>
         */
        @JvmStatic
        fun parse(c: String): Int {
            try {
                val skipInitial: Int
                val skipBetween: Int
                if (c[0] == '#') {
                    // 支持 #RGB、#RRGGBB、#RRRGGGBBB 或 #RRRRGGGGBBBB 格式，高位对齐
                    skipInitial = 1
                    skipBetween = 0
                } else if (c.startsWith("rgb:")) {
                    // rgb:<red>/<green>/<blue>，每个分量为 h | hh | hhh | hhhh，按比例缩放
                    skipInitial = 4
                    skipBetween = 1
                } else {
                    return 0
                }
                val charsForColors = c.length - skipInitial - 2 * skipBetween
                if (charsForColors % 3 != 0) return 0 // 三分量长度不等

                val componentLength = charsForColors / 3
                val mult = 255 / (2.0.pow((componentLength * 4).toDouble()) - 1)

                var currentPosition = skipInitial
                val rString = c.substring(currentPosition, currentPosition + componentLength)
                currentPosition += componentLength + skipBetween
                val gString = c.substring(currentPosition, currentPosition + componentLength)
                currentPosition += componentLength + skipBetween
                val bString = c.substring(currentPosition, currentPosition + componentLength)

                val r = (rString.toInt(16) * mult).toInt()
                val g = (gString.toInt(16) * mult).toInt()
                val b = (bString.toInt(16) * mult).toInt()
                return 0xFF shl 24 or (r shl 16) or (g shl 8) or b
            } catch (e: NumberFormatException) {
                return 0
            } catch (e: IndexOutOfBoundsException) {
                return 0
            }
        }

        /**
         * 基于 RGB 分量计算颜色的感知亮度。
         *
         * @param color 颜色整数值（0xAARRGGBB）
         * @return 0~255 之间的亮度值
         *
         * @see <a href="https://www.nbdtech.com/Blog/archive/2008/04/27/Calculating-the-Perceived-Brightness-of-a-Color.aspx">计算公式</a>
         * @see <a href="http://alienryderflex.com/hsp.html">HSP 模型</a>
         */
        fun getPerceivedBrightnessOfColor(color: Int): Int {
            // 使用标准位运算提取 RGB 通道
            val red = (color shr 16) and 0xFF
            val green = (color shr 8) and 0xFF
            val blue = color and 0xFF

            return floor(
                sqrt(
                    red.toDouble().pow(2.0) * 0.241 +
                    green.toDouble().pow(2.0) * 0.691 +
                    blue.toDouble().pow(2.0) * 0.068
                )
            ).toInt()
        }
    }
}

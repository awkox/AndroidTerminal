package com.awkoo.libterminal.engine

import com.awkoo.libterminal.text.TextStyle
import com.awkoo.libterminal.text.TextStyle.Companion.makeTrueColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SGR（selectGraphicRendition）语义。
 *
 * 参数数组按解析器契约构造：缺省槽为 -1，冒号子参数经 subParams 位图标记。
 */
class RenditionStateTest {

    private val rendition = RenditionState()

    /** 分号形式：无子参数位。 */
    private fun apply(vararg args: Int) = rendition.selectGraphicRendition(args, args.size, 0)

    @Test
    fun sgrZero_resetsEverything() {
        rendition.selectGraphicRendition(intArrayOf(1, 31, 42), 3, 0)

        apply(0)

        assertEquals(TextStyle.COLOR_INDEX_FOREGROUND, rendition.foreColor)
        assertEquals(TextStyle.COLOR_INDEX_BACKGROUND, rendition.backColor)
        assertEquals(0, rendition.effect)
        assertEquals(TextStyle.UNDERLINE_STYLE_NONE, rendition.underlineStyle)
    }

    @Test
    fun bold_toggledBy1And22() {
        apply(1)
        assertTrue(rendition.style.isBold)

        apply(22)
        assertFalse(rendition.style.isBold)
    }

    @Test
    fun sgr22_clearsBoldAndDimButKeepsOtherEffects() {
        apply(1, 2, 7)

        apply(22)

        assertFalse(rendition.style.isBold)
        assertFalse(rendition.style.isDim)
        assertTrue(rendition.style.isInverse)
    }

    @Test
    fun foreground_background_basicAsglColors() {
        apply(31, 42)

        assertEquals(1, rendition.foreColor)
        assertEquals(2, rendition.backColor)
    }

    @Test
    fun brightForeground_background_90to107() {
        apply(91, 105)

        assertEquals(9, rendition.foreColor)
        assertEquals(13, rendition.backColor)
    }

    @Test
    fun defaultColors_restoredBy39And49() {
        apply(31, 42)

        apply(39, 49)

        assertEquals(TextStyle.COLOR_INDEX_FOREGROUND, rendition.foreColor)
        assertEquals(TextStyle.COLOR_INDEX_BACKGROUND, rendition.backColor)
    }

    @Test
    fun indexedExtendedColor_semicolonForm() {
        apply(38, 5, 123, 48, 5, 45)

        assertEquals(123, rendition.foreColor)
        assertEquals(45, rendition.backColor)
    }

    @Test
    fun trueColor_semicolonForm() {
        apply(38, 2, 10, 20, 30)

        assertEquals(makeTrueColor(10, 20, 30), rendition.foreColor)
    }

    @Test
    fun trueColor_colonFormWithColorspaceSlot() {
        // 解析器对 38:2::R:G:B 展开产生 -1 空槽，位图标记 1..5 为子参数
        val args = intArrayOf(38, 2, -1, 10, 20, 30)
        val subParams = 0b111110

        rendition.selectGraphicRendition(args, args.size, subParams)

        assertEquals(makeTrueColor(10, 20, 30), rendition.foreColor)
    }

    @Test
    fun backgroundTrueColor_colonForm() {
        val args = intArrayOf(48, 2, -1, 1, 2, 3)
        val subParams = 0b111110

        rendition.selectGraphicRendition(args, args.size, subParams)

        assertEquals(makeTrueColor(1, 2, 3), rendition.backColor)
    }

    @Test
    fun indexedColor_outOfRange_isIgnored() {
        apply(38, 5, 300)

        assertEquals(TextStyle.COLOR_INDEX_FOREGROUND, rendition.foreColor)
    }

    @Test
    fun underline_plainAndOff() {
        apply(4)
        assertTrue(rendition.style.isUnderline)
        assertEquals(TextStyle.UNDERLINE_STYLE_SINGLE, rendition.underlineStyle)

        apply(24)
        assertFalse(rendition.style.isUnderline)
        assertEquals(TextStyle.UNDERLINE_STYLE_NONE, rendition.underlineStyle)
    }

    @Test
    fun underline_colonStyle_keepsBitAndSetsShape() {
        val args = intArrayOf(4, TextStyle.UNDERLINE_STYLE_CURLY)
        val subParams = 1 shl 1

        rendition.selectGraphicRendition(args, args.size, subParams)

        assertTrue(rendition.style.isUnderline)
        assertEquals(TextStyle.UNDERLINE_STYLE_CURLY, rendition.underlineStyle)
    }

    @Test
    fun underline_colonStyleZero_turnsUnderlineOff() {
        apply(4)

        val args = intArrayOf(4, 0)
        val subParams = 1 shl 1
        rendition.selectGraphicRendition(args, args.size, subParams)

        assertFalse(rendition.style.isUnderline)
        assertEquals(TextStyle.UNDERLINE_STYLE_NONE, rendition.underlineStyle)
    }

    @Test
    fun underlineStyle_outOfRange_fallsBackToSingle() {
        val args = intArrayOf(4, 99)
        val subParams = 1 shl 1

        rendition.selectGraphicRendition(args, args.size, subParams)

        assertEquals(TextStyle.UNDERLINE_STYLE_SINGLE, rendition.underlineStyle)
    }

    @Test
    fun underlineColor_58_colonForm_setsColor() {
        val args = intArrayOf(58, 5, 7)
        val subParams = 0b110

        rendition.selectGraphicRendition(args, args.size, subParams)

        assertEquals(TextStyle.encodeExtendedEffect(rendition.underlineStyle, 7), rendition.currentExtendedEffect)
    }

    @Test
    fun underlineColor_resetBy59() {
        val args = intArrayOf(58, 5, 7)
        rendition.selectGraphicRendition(args, args.size, 0b110)

        apply(59)

        assertEquals(TextStyle.COLOR_INDEX_FOREGROUND, rendition.underlineColor)
    }

    @Test
    fun italicBlinkInverseInvisibleStrikethrough_toggleOffCodes() {
        apply(3, 5, 7, 8, 9)
        assertTrue(rendition.style.isItalic)
        assertTrue(rendition.style.isBlink)
        assertTrue(rendition.style.isInverse)
        assertTrue(rendition.style.isInvisible)
        assertTrue(rendition.style.isStrikeThrough)

        apply(23, 25, 27, 28, 29)
        assertFalse(rendition.style.isItalic)
        assertFalse(rendition.style.isBlink)
        assertFalse(rendition.style.isInverse)
        assertFalse(rendition.style.isInvisible)
        assertFalse(rendition.style.isStrikeThrough)
    }

    @Test
    fun leadingMinusOneArg_isResetToZeroAtHead() {
        // 解析器缺省槽 -1 位于首位时按 0 处理（整条 SGR 复位）
        rendition.selectGraphicRendition(intArrayOf(-1), 1, 0)

        assertEquals(TextStyle.COLOR_INDEX_FOREGROUND, rendition.foreColor)
        assertEquals(0, rendition.effect)
    }

    @Test
    fun protectedBit_toggledByDecrqm() {
        rendition.setProtected(true)
        assertTrue(rendition.style.isProtected)

        rendition.setProtected(false)
        assertFalse(rendition.style.isProtected)
    }

    @Test
    fun eraseFillStyle_keepsBackgroundOnly() {
        apply(31, 42, 1)

        val erase = rendition.eraseFillStyle

        assertEquals(TextStyle.COLOR_INDEX_FOREGROUND, erase.foreColor)
        assertEquals(2, erase.backColor)
        assertEquals(0, erase.effect)
    }

    @Test
    fun unknownExtendedColorSubmode_semicolonForm_consumesRemainingArgs() {
        // 未知子模式（38;9）在纯分号形式下消费整条 SGR 的剩余参数，31 不再生效
        apply(38, 9, 1, 31)

        assertEquals(TextStyle.COLOR_INDEX_FOREGROUND, rendition.foreColor)
    }
}

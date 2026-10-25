package com.awkoo.libterminal.text

import com.awkoo.libterminal.text.TextStyle.Companion.TRUE_COLOR_MASK
import com.awkoo.libterminal.text.TextStyle.Companion.isTrueColor
import com.awkoo.libterminal.text.TextStyle.Companion.makeTrueColor
import com.awkoo.libterminal.text.TextStyle.Companion.trueColorValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextStyleTest {

    @Test
    fun normal_usesDefaultForegroundAndBackground() {
        val style = TextStyle.NORMAL

        assertEquals(TextStyle.COLOR_INDEX_FOREGROUND, style.foreColor)
        assertEquals(TextStyle.COLOR_INDEX_BACKGROUND, style.backColor)
        assertEquals(0, style.effect)
    }

    @Test
    fun indexedColors_roundTripThroughEncode() {
        val style = TextStyle.encode(foreColor = 1, backColor = 2, effect = TextStyle.CHARACTER_ATTRIBUTE_BOLD)

        assertEquals(1, style.foreColor)
        assertEquals(2, style.backColor)
        assertTrue(style.isBold)
    }

    @Test
    fun trueColor_foregroundAndBackground_roundTrip() {
        val fore = makeTrueColor(0x11, 0x22, 0x33)
        val back = makeTrueColor(0xAA, 0xBB, 0xCC)

        val style = TextStyle.encode(fore, back, 0)

        assertEquals(fore, style.foreColor)
        assertEquals(back, style.backColor)
    }

    @Test
    fun effectBits_allRoundTrip() {
        val effect = TextStyle.CHARACTER_ATTRIBUTE_BOLD or
            TextStyle.CHARACTER_ATTRIBUTE_ITALIC or
            TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE or
            TextStyle.CHARACTER_ATTRIBUTE_BLINK or
            TextStyle.CHARACTER_ATTRIBUTE_INVERSE or
            TextStyle.CHARACTER_ATTRIBUTE_INVISIBLE or
            TextStyle.CHARACTER_ATTRIBUTE_STRIKETHROUGH or
            TextStyle.CHARACTER_ATTRIBUTE_PROTECTED or
            TextStyle.CHARACTER_ATTRIBUTE_DIM

        val style = TextStyle.encode(TextStyle.COLOR_INDEX_FOREGROUND, TextStyle.COLOR_INDEX_BACKGROUND, effect)

        assertEquals(effect, style.effect)
        assertTrue(style.isBold)
        assertTrue(style.isItalic)
        assertTrue(style.isUnderline)
        assertTrue(style.isBlink)
        assertTrue(style.isInverse)
        assertTrue(style.isInvisible)
        assertTrue(style.isStrikeThrough)
        assertTrue(style.isProtected)
        assertTrue(style.isDim)
    }

    @Test
    fun effectBits_clearWhenNotSet() {
        val style = TextStyle.NORMAL

        assertFalse(style.isBold)
        assertFalse(style.isUnderline)
        assertFalse(style.isInverse)
    }

    @Test
    fun makeTrueColor_setsHighMarker() {
        val color = makeTrueColor(1, 2, 3)

        assertTrue(color.isTrueColor)
        assertEquals(TRUE_COLOR_MASK or (1 shl 16) or (2 shl 8) or 3, color)
        assertEquals(0x010203, color.trueColorValue)
    }

    @Test
    fun indexedColor_isNotTrueColor() {
        assertFalse(TextStyle.COLOR_INDEX_FOREGROUND.isTrueColor)
        assertFalse(255.isTrueColor)
    }

    @Test
    fun extendedEffect_indexedUnderlineColor_roundTrip() {
        val ext = TextStyle.encodeExtendedEffect(TextStyle.UNDERLINE_STYLE_CURLY, 5)

        assertEquals(TextStyle.UNDERLINE_STYLE_CURLY, TextStyle.decodeUnderlineStyle(ext))
        assertEquals(5, TextStyle.decodeUnderlineColor(ext))
    }

    @Test
    fun extendedEffect_trueColorUnderline_roundTrip() {
        val color = makeTrueColor(9, 8, 7)
        val ext = TextStyle.encodeExtendedEffect(TextStyle.UNDERLINE_STYLE_DOTTED, color)

        assertEquals(TextStyle.UNDERLINE_STYLE_DOTTED, TextStyle.decodeUnderlineStyle(ext))
        assertEquals(color, TextStyle.decodeUnderlineColor(ext))
    }

    @Test
    fun extendedEffect_maxUnderlineStyle_roundTrips() {
        val ext = TextStyle.encodeExtendedEffect(TextStyle.UNDERLINE_STYLE_DASHED, TextStyle.COLOR_INDEX_FOREGROUND)

        assertEquals(TextStyle.UNDERLINE_STYLE_DASHED, TextStyle.decodeUnderlineStyle(ext))
    }
}

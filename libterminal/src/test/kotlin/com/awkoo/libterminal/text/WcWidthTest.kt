package com.awkoo.libterminal.text

import org.junit.Assert.assertEquals
import org.junit.Test

class WcWidthTest {

    @Test
    fun ascii_printableIsOneColumn() {
        assertEquals(1, WcWidth.width('A'.code))
        assertEquals(1, WcWidth.width('z'.code))
        assertEquals(1, WcWidth.width(' '.code))
    }

    @Test
    fun controlChars_areZeroWidth() {
        assertEquals(0, WcWidth.width(0))
        assertEquals(0, WcWidth.width(7))
        assertEquals(0, WcWidth.width(13))
        assertEquals(0, WcWidth.width(127))
    }

    @Test
    fun cjkIdeograph_isTwoColumns() {
        assertEquals(2, WcWidth.width(0x4E2D))
        assertEquals(2, WcWidth.width(0x3042))
    }

    @Test
    fun combiningMark_isZeroWidth() {
        assertEquals(0, WcWidth.width(0x0301))
    }

    @Test
    fun zeroWidthJoinerAndMarks_areZeroWidth() {
        assertEquals(0, WcWidth.width(0x200B))
        assertEquals(0, WcWidth.width(0x200D))
    }

    @Test
    fun supplementaryEmoji_isTwoColumns() {
        assertEquals(2, WcWidth.width(0x1F600))
    }

    @Test
    fun widthOverCharArray_readsCodePointAtIndex() {
        val chars = "中a".toCharArray()

        assertEquals(2, WcWidth.width(chars, 0))
        assertEquals(1, WcWidth.width(chars, 1))
    }

    @Test
    fun widthOverSurrogatePair_readsFullCodePoint() {
        val chars = Character.toChars(0x1F600)

        assertEquals(2, WcWidth.width(chars, 0))
    }

    @Test
    fun zeroWidthCharsCount_countsCombiningSequence() {
        val chars = "e\u0301\u0300x".toCharArray()

        assertEquals(2, WcWidth.zeroWidthCharsCount(chars, 0, 3))
        assertEquals(0, WcWidth.zeroWidthCharsCount(chars, 3, 4))
    }

    @Test
    fun zeroWidthCharsCount_outOfRange_returnsZero() {
        val chars = "abc".toCharArray()

        assertEquals(0, WcWidth.zeroWidthCharsCount(chars, -1, 2))
        assertEquals(0, WcWidth.zeroWidthCharsCount(chars, 5, 6))
    }
}

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

    @Test
    fun unicode16Emojis_andTagChars_haveExpectedWidth() {
        assertEquals(2, WcWidth.width(0x1FA89)) // Harp
        assertEquals(2, WcWidth.width(0x1FAE9)) // Face with bags under eyes
        assertEquals(2, WcWidth.width(0x1FAC6)) // Fingerprint
        assertEquals(2, WcWidth.width(0x1FADC)) // Root vegetable
        assertEquals(0, WcWidth.width(0xE0067)) // Tag Latin Small Letter G
        assertEquals(0, WcWidth.width(0xE007F)) // Cancel Tag
    }

    @Test
    fun formatAndControlChars_areZeroWidth() {
        assertEquals(0, WcWidth.width(0x061C)) // Arabic Letter Mark
        assertEquals(0, WcWidth.width(0x180E)) // Mongolian Vowel Separator
        assertEquals(0, WcWidth.width(0x200B)) // ZWSP
        assertEquals(0, WcWidth.width(0x200E)) // LRM
        assertEquals(0, WcWidth.width(0x2060)) // Word Joiner
        assertEquals(0, WcWidth.width(0x2066)) // LRI
        assertEquals(0, WcWidth.width(0x2069)) // PDI
        assertEquals(0, WcWidth.width(0xFEFF)) // BOM
        assertEquals(0, WcWidth.width(0xFFF9)) // Interlinear Annotation Anchor
        assertEquals(0, WcWidth.width(0x1BCA0)) // Shorthand Format
        assertEquals(0, WcWidth.width(0x1D173)) // Musical Symbol Begin Beam
        assertEquals(0, WcWidth.width(0xE0001)) // Language Tag
    }

    @Test
    fun emojiClusterHelpers_classifyCandidatesAccurately() {
        assertEquals(true, WcWidth.isVs16WideningCandidate(0x2764)) // Heavy Black Heart
        assertEquals(true, WcWidth.isVs16WideningCandidate(0x26A0)) // Warning Sign
        assertEquals(true, WcWidth.isVs16WideningCandidate(0x1F3F3)) // Waving White Flag
        assertEquals(false, WcWidth.isVs16WideningCandidate('A'.code))
        assertEquals(false, WcWidth.isVs16WideningCandidate('1'.code))
        assertEquals(false, WcWidth.isVs16WideningCandidate(0x4E2D))

        assertEquals(true, WcWidth.isKeycapBase('1'.code))
        assertEquals(true, WcWidth.isKeycapBase('#'.code))
        assertEquals(true, WcWidth.isKeycapBase('*'.code))
        assertEquals(false, WcWidth.isKeycapBase('a'.code))

        assertEquals(true, WcWidth.isEmojiOrPictographic(0x1F600))
        assertEquals(true, WcWidth.isEmojiOrPictographic(0x2764))
        assertEquals(false, WcWidth.isEmojiOrPictographic(0x1F3FD))
        assertEquals(true, WcWidth.isEmojiModifier(0x1F3FD))
        assertEquals(false, WcWidth.isEmojiOrPictographic(0x2329))
        assertEquals(false, WcWidth.isEmojiOrPictographic('A'.code))
    }
}

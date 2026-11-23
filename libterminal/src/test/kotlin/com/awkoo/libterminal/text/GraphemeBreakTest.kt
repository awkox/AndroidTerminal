package com.awkoo.libterminal.text

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphemeBreakTest {

    @Test
    fun controlAndFormatBreakChars_breakCluster() {
        val breaks = intArrayOf(
            0x0000, 0x0007, 0x007F, 0x009F,
            0x061C, 0x180E, 0x200B, 0x200E, 0x200F,
            0x2028, 0x202E, 0x2060, 0x2066, 0x2069,
            0xFEFF, 0xFFF9, 0x1BCA0, 0x1D173, 0xE0001
        )
        for (cp in breaks) {
            assertTrue("Expected control/format break for U+${cp.toString(16)}", GraphemeBreak.isControlOrFormatBreak(cp))
            assertFalse("Must not merge U+${cp.toString(16)} into 'a'", GraphemeBreak.shouldMerge(charArrayOf('a'), cp, 0))
        }

        assertFalse(GraphemeBreak.isControlOrFormatBreak(0x0301))
        assertFalse(GraphemeBreak.isControlOrFormatBreak(0x200C))
        assertFalse(GraphemeBreak.isControlOrFormatBreak(0x200D))
        assertFalse(GraphemeBreak.isControlOrFormatBreak(0xFE0F))
    }

    @Test
    fun emojiModifier_onlyMergesOntoEmojiModifierBase() {
        val skinTone = 0x1F3FD
        assertTrue(GraphemeBreak.shouldMerge("👍".toCharArray(), skinTone, 2))
        assertTrue(GraphemeBreak.shouldMerge("\u261D\uFE0F".toCharArray(), skinTone, 2))
        assertTrue(GraphemeBreak.shouldMerge("👩\uD83C\uDFFD\u200D👨".toCharArray(), skinTone, 2))

        assertFalse(GraphemeBreak.shouldMerge("😀".toCharArray(), skinTone, 2))
        assertFalse(GraphemeBreak.shouldMerge("\u2764\uFE0F".toCharArray(), skinTone, 2))
        assertFalse(GraphemeBreak.shouldMerge("🏽".toCharArray(), skinTone, 2))
        assertFalse(GraphemeBreak.shouldMerge("a".toCharArray(), skinTone, 2))

        assertTrue(GraphemeBreak.shouldExpandToWide("\u261D".toCharArray(), 1, skinTone))
    }

    @Test
    fun zwjSequence_requiresExtendedPictographicBeforeZwj() {
        assertTrue(GraphemeBreak.shouldMerge("👨\u200D".toCharArray(), 0x1F469, 2))
        assertTrue(GraphemeBreak.shouldMerge("\uD83C\uDFF3\uFE0F\u200D".toCharArray(), 0x1F308, 2))
        assertTrue(GraphemeBreak.shouldMerge("👩🏽\u200D".toCharArray(), 0x1F373, 2))

        assertFalse(GraphemeBreak.shouldMerge("a\u200D".toCharArray(), 0x1F600, 2))
        assertFalse(GraphemeBreak.shouldMerge("\u2329\u200D".toCharArray(), 0x1F600, 2))
        assertFalse(GraphemeBreak.shouldMerge("🏽\u200D".toCharArray(), 0x1F373, 2))
    }

    @Test
    fun tagSequence_onlyMergesInsideBlackFlagBeforeCancelTag() {
        val blackFlag = Character.toChars(0x1F3F4)
        val completedFlag = "\uD83C\uDFF4\uDB40\uDC67\uDB40\uDC62\uDB40\uDC7F".toCharArray()

        assertTrue(GraphemeBreak.shouldMerge(blackFlag, 0xE0067, 0))
        assertTrue(GraphemeBreak.shouldMerge(blackFlag, 0xE007F, 0))
        assertFalse(GraphemeBreak.shouldMerge("a".toCharArray(), 0xE0067, 0))
        assertFalse(GraphemeBreak.shouldMerge(completedFlag, 0xE0067, 0))
    }

    @Test
    fun hangulJamo_followsGb6Gb7Gb8() {
        assertTrue(GraphemeBreak.shouldMerge("\u1100".toCharArray(), 0x1161, 1)) // L + V
        assertTrue(GraphemeBreak.shouldMerge("\u1100\u1161".toCharArray(), 0x11A8, 1)) // LV-via-L+V + T
        assertTrue(GraphemeBreak.shouldMerge("\uAC00".toCharArray(), 0x11A8, 1)) // LV + T
        assertTrue(GraphemeBreak.shouldMerge("\uAC01".toCharArray(), 0x11A8, 1)) // LVT + T

        assertFalse(GraphemeBreak.shouldMerge("a".toCharArray(), 0x1161, 1))
        assertFalse(GraphemeBreak.shouldMerge("\u1100\u0301".toCharArray(), 0x1161, 1))
    }

    @Test
    fun indicConjunct_followsGb9c() {
        assertTrue(GraphemeBreak.shouldMerge("\u0915\u094D".toCharArray(), 0x0937, 1)) // क् + ष
        assertTrue(GraphemeBreak.shouldMerge("\u0915\u094D\u200D".toCharArray(), 0x0937, 1)) // क्‌ + ष
        assertTrue(GraphemeBreak.shouldMerge("\u0915\u093C\u094D".toCharArray(), 0x0937, 1)) // क़् + ष

        assertFalse(GraphemeBreak.shouldMerge("\u0915".toCharArray(), 0x0937, 1)) // क + ष (missing virama)
        assertFalse(GraphemeBreak.shouldMerge("\u0915\u094D".toCharArray(), 'a'.code, 1))
    }

    @Test
    fun vs16AndKeycapWidening_requireCleanBaseSequence() {
        assertTrue(GraphemeBreak.shouldExpandToWide("\u2764".toCharArray(), 1, 0xFE0F))
        assertFalse(GraphemeBreak.shouldExpandToWide("\u2764\u0301".toCharArray(), 1, 0xFE0F))
        assertFalse(GraphemeBreak.shouldExpandToWide("\u2764\uFE0E".toCharArray(), 1, 0xFE0F))

        assertTrue(GraphemeBreak.shouldExpandToWide("1".toCharArray(), 1, 0x20E3))
        assertTrue(GraphemeBreak.shouldExpandToWide("1\uFE0F".toCharArray(), 1, 0x20E3))
        assertFalse(GraphemeBreak.shouldExpandToWide("1\u0301".toCharArray(), 1, 0x20E3))
    }
}

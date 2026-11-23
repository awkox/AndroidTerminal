package com.awkoo.libterminal.text

import com.awkoo.libterminal.text.GraphemeBreak.GcbClass
import org.junit.Assert.assertEquals
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
            assertFalse("Must not merge U+${cp.toString(16)} into 'a'", GraphemeBreak.shouldMerge(charArrayOf('a'), cp))
        }

        assertEquals(GcbClass.CONTROL, GraphemeBreak.gcbClass(0x00AD))
        assertFalse(GraphemeBreak.isControlOrFormatBreak(0x00AD))
        assertFalse(GraphemeBreak.shouldMerge(charArrayOf('a'), 0x00AD))
        assertFalse(GraphemeBreak.shouldMerge(charArrayOf('\u00AD'), 0x0301))

        assertFalse(GraphemeBreak.isControlOrFormatBreak(0x0301))
        assertFalse(GraphemeBreak.isControlOrFormatBreak(0x200C))
        assertFalse(GraphemeBreak.isControlOrFormatBreak(0x200D))
        assertFalse(GraphemeBreak.isControlOrFormatBreak(0xFE0F))
    }

    @Test
    fun gcbClassification_isDecoupledFromWcWidth() {
        assertEquals(GcbClass.EXTEND, GraphemeBreak.gcbClass(0x09BE))
        assertEquals(GcbClass.EXTEND, GraphemeBreak.gcbClass(0x302E))
        assertEquals(GcbClass.EXTEND, GraphemeBreak.gcbClass(0xFF9E))
        assertEquals(GcbClass.EXTEND, GraphemeBreak.gcbClass(0x1F3FD))
        assertEquals(GcbClass.ZWJ, GraphemeBreak.gcbClass(0x200D))

        assertEquals(GcbClass.SPACING_MARK, GraphemeBreak.gcbClass(0x093E))
        assertEquals(GcbClass.SPACING_MARK, GraphemeBreak.gcbClass(0x0E33))
        assertEquals(GcbClass.SPACING_MARK, GraphemeBreak.gcbClass(0x0EB3))

        assertEquals(GcbClass.PREPEND, GraphemeBreak.gcbClass(0x0600))
        assertEquals(GcbClass.PREPEND, GraphemeBreak.gcbClass(0x0D4E))
        assertEquals(GcbClass.PREPEND, GraphemeBreak.gcbClass(0x110BD))
        assertEquals(GcbClass.PREPEND, GraphemeBreak.gcbClass(0x113D1))

        assertTrue(GraphemeBreak.mayMerge(0x09BE))
        assertTrue(GraphemeBreak.mayMerge(0x093E))
        assertTrue(GraphemeBreak.mayMerge('1'.code, prevEndsWithPrepend = true))
        assertFalse(GraphemeBreak.mayMerge('1'.code, prevEndsWithPrepend = false))
    }

    @Test
    fun spacingMarkAndPrepend_followGb9aAndGb9b() {
        assertTrue(GraphemeBreak.shouldMerge("\u0915".toCharArray(), 0x093E))
        assertFalse(GraphemeBreak.shouldExpandToWide("\u0915".toCharArray(), 1, 0x093E))

        assertTrue(GraphemeBreak.shouldMerge("\u0600".toCharArray(), '1'.code))
        assertFalse(GraphemeBreak.shouldExpandToWide("\u0600".toCharArray(), 1, '1'.code))
        assertTrue(GraphemeBreak.shouldMerge("\u0600".toCharArray(), 0x4E2D))
        assertTrue(GraphemeBreak.shouldExpandToWide("\u0600".toCharArray(), 1, 0x4E2D))
        assertFalse(GraphemeBreak.shouldMerge("\u0600".toCharArray(), 0x00AD))
        assertFalse(GraphemeBreak.shouldMerge("\u0600".toCharArray(), 0x200B))
    }

    @Test
    fun emojiModifier_onlyMergesOntoEmojiModifierBase() {
        val skinTone = 0x1F3FD
        assertTrue(GraphemeBreak.shouldMerge("👍".toCharArray(), skinTone))
        assertTrue(GraphemeBreak.shouldMerge("\u261D\uFE0F".toCharArray(), skinTone))
        assertTrue(GraphemeBreak.shouldMerge("👩\uD83C\uDFFD\u200D👨".toCharArray(), skinTone))

        assertFalse(GraphemeBreak.shouldMerge("😀".toCharArray(), skinTone))
        assertFalse(GraphemeBreak.shouldMerge("\u2764\uFE0F".toCharArray(), skinTone))
        assertFalse(GraphemeBreak.shouldMerge("🏽".toCharArray(), skinTone))
        assertFalse(GraphemeBreak.shouldMerge("a".toCharArray(), skinTone))

        assertTrue(GraphemeBreak.shouldExpandToWide("\u261D".toCharArray(), 1, skinTone))
    }

    @Test
    fun zwjSequence_requiresExtendedPictographicAndOnlyExtendBetweenBaseAndZwj() {
        assertTrue(GraphemeBreak.shouldMerge("👨\u200D".toCharArray(), 0x1F469))
        assertTrue(GraphemeBreak.shouldMerge("\uD83C\uDFF3\uFE0F\u0301\u200D".toCharArray(), 0x1F308))
        assertTrue(GraphemeBreak.shouldMerge("👩🏽\u200D".toCharArray(), 0x1F373))

        assertFalse(GraphemeBreak.shouldMerge("a\u200D".toCharArray(), 0x1F600))
        assertFalse(GraphemeBreak.shouldMerge("a\u0301\u200D".toCharArray(), 0x1F600))
        assertFalse(GraphemeBreak.shouldMerge("👨\u200D".toCharArray(), 'a'.code))
        assertFalse(GraphemeBreak.shouldMerge("👨\u093E\u200D".toCharArray(), 0x1F469))
        assertFalse(GraphemeBreak.shouldMerge("\u2329\u200D".toCharArray(), 0x1F600))
        assertFalse(GraphemeBreak.shouldMerge("🏽\u200D".toCharArray(), 0x1F373))
    }

    @Test
    fun tagSequence_onlyMergesInsideBlackFlagBeforeCancelTag() {
        val blackFlag = Character.toChars(0x1F3F4)
        val completedFlag = "\uD83C\uDFF4\uDB40\uDC67\uDB40\uDC62\uDB40\uDC7F".toCharArray()

        assertTrue(GraphemeBreak.shouldMerge(blackFlag, 0xE0067))
        assertTrue(GraphemeBreak.shouldMerge(blackFlag, 0xE007F))
        assertFalse(GraphemeBreak.shouldMerge("a".toCharArray(), 0xE0067))
        assertFalse(GraphemeBreak.shouldMerge(completedFlag, 0xE0067))
    }

    @Test
    fun hangulJamo_followsGb6Gb7Gb8() {
        assertTrue(GraphemeBreak.shouldMerge("\u1100".toCharArray(), 0x1161))
        assertTrue(GraphemeBreak.shouldMerge("\u1100\u1161".toCharArray(), 0x11A8))
        assertTrue(GraphemeBreak.shouldMerge("\uAC00".toCharArray(), 0x11A8))
        assertTrue(GraphemeBreak.shouldMerge("\uAC01".toCharArray(), 0x11A8))

        assertFalse(GraphemeBreak.shouldMerge("a".toCharArray(), 0x1161))
        assertFalse(GraphemeBreak.shouldMerge("\u1100\u0301".toCharArray(), 0x1161))
    }

    @Test
    fun indicConjunct_followsGb9c() {
        assertTrue(GraphemeBreak.shouldMerge("\u0915\u094D".toCharArray(), 0x0937))
        assertTrue(GraphemeBreak.shouldMerge("\u0915\u094D\u200D".toCharArray(), 0x0937))
        assertTrue(GraphemeBreak.shouldMerge("\u0915\u093C\u094D".toCharArray(), 0x0937))

        assertFalse(GraphemeBreak.shouldMerge("\u0915".toCharArray(), 0x0937))
        assertFalse(GraphemeBreak.shouldMerge("\u0915\u094D\u093E".toCharArray(), 0x0937))
        assertFalse(GraphemeBreak.shouldMerge("\u0915\u094D".toCharArray(), 'a'.code))
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

package com.awkoo.libterminal.view.render

import org.junit.Assert.assertEquals
import org.junit.Test

class GlyphWidthCacheTest {

    @Test
    fun measure_usesAsciiTableWithoutCallingMeasureRange() {
        var calls = 0
        val ascii = FloatArray(127) { it.toFloat() }
        val cache = GlyphWidthCache(ascii) { _, _, _ ->
            calls++
            99f
        }

        val width = cache.measure(charArrayOf('A'), 0, 1, 'A'.code)

        assertEquals('A'.code.toFloat(), width, 0.001f)
        assertEquals(0, calls)
    }

    @Test
    fun measure_cachesBmpCodePointsOnceAndBypassesForMultiChar() {
        var calls = 0
        val ascii = FloatArray(127) { 10f }
        val cache = GlyphWidthCache(ascii) { _, _, count ->
            calls++
            if (count == 1) 20f else 22f
        }
        val line = "中\uD83D\uDE00".toCharArray()

        assertEquals(20f, cache.measure(line, 0, 1, 0x4E2D), 0.001f)
        assertEquals(20f, cache.measure(line, 0, 1, 0x4E2D), 0.001f)
        assertEquals(1, calls)

        assertEquals(22f, cache.measure(line, 1, 2, 0x1F600), 0.001f)
        assertEquals(22f, cache.measure(line, 1, 2, 0x1F600), 0.001f)
        assertEquals(3, calls)
    }

    @Test
    fun measure_bypassesAsciiAndBmpCache_forMultiCharClusters() {
        var calls = 0
        val ascii = FloatArray(127) { 10f }
        val cache = GlyphWidthCache(ascii) { _, _, count ->
            calls++
            count * 10f
        }
        val keycap = "1\uFE0F\u20E3".toCharArray()
        val heartVs16 = "\u2764\uFE0F".toCharArray()

        assertEquals(10f, cache.measure(keycap, 0, 1, '1'.code), 0.001f)
        assertEquals(0, calls)
        assertEquals(30f, cache.measure(keycap, 0, keycap.size, '1'.code), 0.001f)
        assertEquals(1, calls)

        assertEquals(10f, cache.measure(heartVs16, 0, 1, 0x2764), 0.001f)
        assertEquals(2, calls)
        assertEquals(20f, cache.measure(heartVs16, 0, heartVs16.size, 0x2764), 0.001f)
        assertEquals(3, calls)
        // 单字符缓存未被多字符分支污染
        assertEquals(10f, cache.measure(heartVs16, 0, 1, 0x2764), 0.001f)
        assertEquals(3, calls)
    }
}

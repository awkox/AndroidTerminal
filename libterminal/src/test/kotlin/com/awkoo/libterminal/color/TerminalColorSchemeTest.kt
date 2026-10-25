package com.awkoo.libterminal.color

import com.awkoo.libterminal.text.TextStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class TerminalColorSchemeTest {

    private val black = 0xFF000000.toInt()
    private val white = 0xFFFFFFFF.toInt()

    @Test
    fun dark_defaultSlots() {
        val scheme = TerminalColorScheme.dark()

        assertEquals(white, scheme.foreground)
        assertEquals(black, scheme.background)
        assertEquals(white, scheme.cursor)
    }

    @Test
    fun light_defaultSlots() {
        val scheme = TerminalColorScheme.light()

        assertEquals(black, scheme.foreground)
        assertEquals(white, scheme.background)
        assertEquals(black, scheme.cursor)
    }

    @Test
    fun custom_overridesForegroundBackgroundCursor() {
        val foreground = 0xFF010203.toInt()
        val background = 0xFF040506.toInt()
        val cursor = 0xFF070809.toInt()

        val scheme = TerminalColorScheme.custom(foreground, background, cursor)

        assertEquals(foreground, scheme.foreground)
        assertEquals(background, scheme.background)
        assertEquals(cursor, scheme.cursor)
    }

    @Test
    fun custom_ansi16OverridesBaseColors() {
        val ansi16 = IntArray(16) { (0xFF000000L + it).toInt() }

        val scheme = TerminalColorScheme.custom(1, 2, 3, ansi16)

        assertEquals(black, scheme.color(0))
        assertEquals((0xFF000000L + 15).toInt(), scheme.color(15))
    }

    @Test(expected = IllegalArgumentException::class)
    fun custom_fullPalette_rejectsWrongSize() {
        TerminalColorScheme.custom(IntArray(10))
    }

    @Test(expected = IllegalArgumentException::class)
    fun custom_ansi16_rejectsWrongSize() {
        TerminalColorScheme.custom(1, 2, 3, IntArray(15))
    }

    @Test
    fun valueEquality_byColorContent() {
        assertEquals(TerminalColorScheme.dark(), TerminalColorScheme.dark())
        assertEquals(TerminalColorScheme.dark().hashCode(), TerminalColorScheme.dark().hashCode())
        assertNotEquals(TerminalColorScheme.dark(), TerminalColorScheme.light())
    }

    @Test
    fun basePalette_cubeStartsAtZeroAndGrayRampStepsTen() {
        val scheme = TerminalColorScheme.dark()

        assertEquals(black, scheme.color(16))
        assertEquals((0xFF000000L + 0x5F5F5F).toInt(), scheme.color(16 + 36 + 6 + 1))
        assertEquals((0xFF000000L + 0xEEEEEE).toInt(), scheme.color(255))
    }

    @Test
    fun specialSlotConstants_matchTextStyle() {
        assertEquals(TextStyle.COLOR_INDEX_FOREGROUND, TerminalColorScheme.INDEX_FOREGROUND)
        assertEquals(TextStyle.COLOR_INDEX_BACKGROUND, TerminalColorScheme.INDEX_BACKGROUND)
        assertEquals(TextStyle.COLOR_INDEX_CURSOR, TerminalColorScheme.INDEX_CURSOR)
        assertEquals(TextStyle.NUM_INDEXED_COLORS, TerminalColorScheme.COLOR_COUNT)
    }
}

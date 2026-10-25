package com.awkoo.libterminal.color

import com.awkoo.libterminal.text.TextStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SparsePaletteTest {

    private val palette = SparsePalette()

    @Test
    fun freshSlot_isNotOverridden() {
        assertFalse(palette.isOverridden(1))
    }

    @Test
    fun set_marksSlotOverriddenAndStoresValue() {
        palette.set(5, 0xFF112233.toInt())

        assertTrue(palette.isOverridden(5))
        assertEquals(0xFF112233.toInt(), palette.value(5))
    }

    @Test
    fun reset_clearsSingleSlotOverride() {
        palette.set(3, 0xFF00FF00.toInt())
        palette.set(4, 0xFF0000FF.toInt())

        palette.reset(3)

        assertFalse(palette.isOverridden(3))
        assertEquals(0, palette.value(3))
        assertTrue(palette.isOverridden(4))
    }

    @Test
    fun resetAll_clearsEverySlot() {
        palette.set(0, 1)
        palette.set(TextStyle.COLOR_INDEX_FOREGROUND, 2)

        palette.resetAll()

        assertFalse(palette.isOverridden(0))
        assertFalse(palette.isOverridden(TextStyle.COLOR_INDEX_FOREGROUND))
    }

    @Test
    fun specialSlots_overriddenIndependentlyOf256Palette() {
        palette.set(TextStyle.COLOR_INDEX_BACKGROUND, 0xFF123456.toInt())

        assertTrue(palette.isOverridden(TextStyle.COLOR_INDEX_BACKGROUND))
        assertFalse(palette.isOverridden(TextStyle.COLOR_INDEX_FOREGROUND))
    }
}

package com.awkoo.libterminal.view

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalViewFontSizeTest {

    @Test
    fun dpToEvenPx_lowDensityAndMinDp_neverProducesZero() {
        assertEquals(2, TerminalView.dpToEvenPx(1, 0.75f))
        assertEquals(2, TerminalView.dpToEvenPx(1, 1.0f))
        assertEquals(2, TerminalView.dpToEvenPx(1, 1.33125f))
        assertEquals(2, TerminalView.dpToEvenPx(2, 0.5f))
        assertEquals(2, TerminalView.dpToEvenPx(1, 0.25f))
    }

    @Test
    fun dpToEvenPx_preservesNormalEvenRounding() {
        assertEquals(2, TerminalView.dpToEvenPx(1, 1.5f))
        assertEquals(2, TerminalView.dpToEvenPx(1, 2.0f))
        assertEquals(12, TerminalView.dpToEvenPx(12, 1.0f))
        assertEquals(24, TerminalView.dpToEvenPx(12, 2.0f))
        assertEquals(14, TerminalView.dpToEvenPx(15, 1.0f))
    }

    @Test
    fun dpToEvenPx_allAllowedSizesAcrossDensities_arePositiveAndEven() {
        val densities = floatArrayOf(0.5f, 0.75f, 1.0f, 1.33125f, 1.5f, 2.0f, 3.0f)
        for (density in densities) {
            for (dp in 1..100) {
                val px = TerminalView.dpToEvenPx(dp, density)
                assertTrue("dp=$dp, density=$density produced px=$px", px >= 2)
                assertEquals("dp=$dp, density=$density produced odd px=$px", 0, px and 1)
            }
        }
    }
}

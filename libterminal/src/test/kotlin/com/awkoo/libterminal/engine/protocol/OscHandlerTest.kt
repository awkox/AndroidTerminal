package com.awkoo.libterminal.engine.protocol

import com.awkoo.libterminal.color.SparsePalette
import com.awkoo.libterminal.color.TerminalColorScheme
import com.awkoo.libterminal.text.TextStyle
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OscHandlerTest {

    private val palette = SparsePalette()
    private val strings = mutableListOf<String>()
    private val handler = OscHandler(palette, { TerminalColorScheme.dark() }) { strings += it }

    private val written: String get() = strings.joinToString("")

    @Test
    fun title_zeroOneTwo_setTitleState() {
        handler.onOscCommand(2, "my title", "\u0007")

        assertEquals("my title", handler.titleState.value)

        handler.onOscCommand(0, "other", "\u0007")

        assertEquals("other", handler.titleState.value)
    }

    @Test
    fun title_pushAndPop_roundTrip() {
        handler.onOscCommand(2, "first", "\u0007")
        handler.pushTitle()
        handler.onOscCommand(2, "second", "\u0007")

        handler.popTitle()

        assertEquals("first", handler.titleState.value)
    }

    @Test
    fun popTitle_emptyStack_isNoOp() {
        handler.onOscCommand(2, "kept", "\u0007")

        handler.popTitle()

        assertEquals("kept", handler.titleState.value)
    }

    @Test
    fun pushTitle_stackDepthCappedAt20() {
        handler.onOscCommand(2, "base", "\u0007")
        repeat(25) { handler.pushTitle() }

        repeat(20) { handler.popTitle() }

        assertEquals("base", handler.titleState.value)
    }

    @Test
    fun setColor_hexNotation_writesOverride() {
        handler.onOscCommand(4, "1;#FF0000", "\u0007")

        assertTrue(palette.isOverridden(1))
        assertEquals(0xFFFF0000.toInt(), palette.value(1))
    }

    @Test
    fun setColor_rgbNotation_writesOverride() {
        handler.onOscCommand(4, "2;rgb:00/ff/00", "\u0007")

        assertEquals(0xFF00FF00.toInt(), palette.value(2))
    }

    @Test
    fun setColor_multiplePairsInOneCommand() {
        handler.onOscCommand(4, "1;#111111;2;#222222", "\u0007")

        assertEquals(0xFF111111.toInt(), palette.value(1))
        assertEquals(0xFF222222.toInt(), palette.value(2))
    }

    @Test
    fun setColor_unparsableSpec_isIgnored() {
        handler.onOscCommand(4, "1;garbage", "\u0007")

        assertTrue(!palette.isOverridden(1))
    }

    @Test
    fun queryColor_reportsSchemeBaseAsRgb() {
        handler.onOscCommand(10, "?", "\u0007")

        assertEquals("\u001b]10;rgb:ffff/ffff/ffff\u0007", written)
    }

    @Test
    fun queryColor_reportsOverridePreferentially() {
        palette.set(TextStyle.COLOR_INDEX_FOREGROUND, 0xFF000000.toInt())

        handler.onOscCommand(10, "?", "\u0007")

        assertEquals("\u001b]10;rgb:0000/0000/0000\u0007", written)
    }

    @Test
    fun clipboard_writeEmitsDecodedText() = runBlocking {
        val pending = async { handler.copiedText.first() }
        yield()

        handler.onOscCommand(52, "c;aGVsbG8=", "\u0007")

        assertEquals("hello", withTimeout(2_000) { pending.await() })
    }

    @Test
    fun clipboard_invalidBase64_isSwallowed() = runBlocking {
        var received: String? = null
        val collector = launch { handler.copiedText.collect { received = it } }
        yield()

        handler.onOscCommand(52, "c;!!!", "\u0007")
        delay(100)

        assertNull(received)
        collector.cancel()
    }

    @Test
    fun resetColor_singleSlot_clearsOverride() {
        palette.set(1, 0xFF111111.toInt())

        handler.onOscCommand(104, "1", "\u0007")

        assertTrue(!palette.isOverridden(1))
    }

    @Test
    fun resetColor_empty_clearsAllOverrides() {
        palette.set(1, 0xFF111111.toInt())
        palette.set(TextStyle.COLOR_INDEX_FOREGROUND, 0xFF222222.toInt())

        handler.onOscCommand(104, "", "\u0007")

        assertTrue(!palette.isOverridden(1))
        assertTrue(!palette.isOverridden(TextStyle.COLOR_INDEX_FOREGROUND))
    }

    @Test
    fun resetSpecialColors_110to112_clearForegroundBackgroundCursor() {
        palette.set(TextStyle.COLOR_INDEX_FOREGROUND, 1)
        palette.set(TextStyle.COLOR_INDEX_BACKGROUND, 2)
        palette.set(TextStyle.COLOR_INDEX_CURSOR, 3)

        handler.onOscCommand(110, "", "\u0007")
        handler.onOscCommand(111, "", "\u0007")
        handler.onOscCommand(112, "", "\u0007")

        assertTrue(!palette.isOverridden(TextStyle.COLOR_INDEX_FOREGROUND))
        assertTrue(!palette.isOverridden(TextStyle.COLOR_INDEX_BACKGROUND))
        assertTrue(!palette.isOverridden(TextStyle.COLOR_INDEX_CURSOR))
    }
}

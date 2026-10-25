package com.awkoo.libterminal.engine.protocol

import com.awkoo.libterminal.engine.TerminalEmulator.Companion.MOUSE_LEFT_BUTTON
import com.awkoo.libterminal.engine.TerminalEmulator.Companion.MOUSE_LEFT_BUTTON_MOVED
import com.awkoo.libterminal.engine.TerminalEmulator.Companion.MOUSE_WHEELDOWN_BUTTON
import com.awkoo.libterminal.engine.TerminalEmulator.Companion.MOUSE_WHEELUP_BUTTON
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InputSequenceEncoderTest {

    private val strings = mutableListOf<String>()
    private val bytes = mutableListOf<ByteArray>()
    private val encoder = InputSequenceEncoder({ strings += it }, { bytes += it })

    private val written: String get() = strings.joinToString("")

    @Test
    fun sgrMouse_pressAndRelease_useCapitalAndLowerFinal() {
        encoder.encodeMouseEvent(
            button = MOUSE_LEFT_BUTTON, column = 5, row = 3, pressed = true,
            buttonEventTracking = false, anyEventTracking = false, sgrProtocol = true,
            columns = 10, rows = 10
        )
        encoder.encodeMouseEvent(
            button = MOUSE_LEFT_BUTTON, column = 5, row = 3, pressed = false,
            buttonEventTracking = false, anyEventTracking = false, sgrProtocol = true,
            columns = 10, rows = 10
        )

        assertEquals("\u001b[<0;6;4M\u001b[<0;6;4m", written)
    }

    @Test
    fun sgrMouse_wheelButtons() {
        encoder.encodeMouseEvent(
            button = MOUSE_WHEELUP_BUTTON, column = 0, row = 0, pressed = true,
            buttonEventTracking = false, anyEventTracking = false, sgrProtocol = true,
            columns = 10, rows = 10
        )
        encoder.encodeMouseEvent(
            button = MOUSE_WHEELDOWN_BUTTON, column = 0, row = 0, pressed = true,
            buttonEventTracking = false, anyEventTracking = false, sgrProtocol = true,
            columns = 10, rows = 10
        )

        assertEquals("\u001b[<64;1;1M\u001b[<65;1;1M", written)
    }

    @Test
    fun sgrMouse_coordinates_clampedToScreen() {
        encoder.encodeMouseEvent(
            button = MOUSE_LEFT_BUTTON, column = -5, row = 999, pressed = true,
            buttonEventTracking = false, anyEventTracking = false, sgrProtocol = true,
            columns = 10, rows = 10
        )

        assertEquals("\u001b[<0;1;10M", written)
    }

    @Test
    fun x10Protocol_encodesOffsetBytes() {
        encoder.encodeMouseEvent(
            button = MOUSE_LEFT_BUTTON, column = 5, row = 3, pressed = true,
            buttonEventTracking = false, anyEventTracking = false, sgrProtocol = false,
            columns = 10, rows = 10
        )

        assertEquals(listOf(27, '['.code, 'M'.code, 32, 38, 36), bytes.single().map { it.toInt() and 0xFF })
    }

    @Test
    fun x10Protocol_releaseUsesNoButtonCode() {
        encoder.encodeMouseEvent(
            button = MOUSE_LEFT_BUTTON, column = 5, row = 3, pressed = false,
            buttonEventTracking = false, anyEventTracking = false, sgrProtocol = false,
            columns = 10, rows = 10
        )

        assertEquals(35, bytes.single()[3].toInt() and 0xFF)
    }

    @Test
    fun x10Protocol_outOfEncodableRange_isDropped() {
        encoder.encodeMouseEvent(
            button = MOUSE_LEFT_BUTTON, column = 300, row = 3, pressed = true,
            buttonEventTracking = false, anyEventTracking = false, sgrProtocol = false,
            columns = 400, rows = 10
        )

        assertTrue(bytes.isEmpty())
    }

    @Test
    fun moveEvent_suppressedWithoutTrackingModes() {
        encoder.encodeMouseEvent(
            button = MOUSE_LEFT_BUTTON_MOVED, column = 1, row = 1, pressed = true,
            buttonEventTracking = false, anyEventTracking = false, sgrProtocol = true,
            columns = 10, rows = 10
        )

        assertTrue(strings.isEmpty())
    }

    @Test
    fun moveEvent_reportedInButtonEventMode() {
        encoder.encodeMouseEvent(
            button = MOUSE_LEFT_BUTTON_MOVED, column = 1, row = 1, pressed = true,
            buttonEventTracking = true, anyEventTracking = false, sgrProtocol = true,
            columns = 10, rows = 10
        )

        assertEquals("\u001b[<32;2;2M", written)
    }

    @Test
    fun moveEvent_reportedInAnyEventMode() {
        encoder.encodeMouseEvent(
            button = MOUSE_LEFT_BUTTON_MOVED, column = 1, row = 1, pressed = true,
            buttonEventTracking = false, anyEventTracking = true, sgrProtocol = true,
            columns = 10, rows = 10
        )

        assertEquals("\u001b[<32;2;2M", written)
    }

    @Test
    fun focusEvents_onlyWhenReportingEnabled() {
        encoder.encodeFocusEvent(hasFocus = true, reportFocusEvents = false)
        assertTrue(strings.isEmpty())

        encoder.encodeFocusEvent(hasFocus = true, reportFocusEvents = true)
        encoder.encodeFocusEvent(hasFocus = false, reportFocusEvents = true)

        assertEquals("\u001b[I\u001b[O", written)
    }

    @Test
    fun paste_stripsEscapeAndC1Controls() {
        encoder.encodePastedText("a\u001bb\u0085c", bracketedPaste = false)

        assertEquals("abc", written)
    }

    @Test
    fun paste_newlinesNormalizedToCarriageReturn() {
        encoder.encodePastedText("a\nb\rc\r\nd", bracketedPaste = false)

        assertEquals("a\rb\rc\rd", written)
    }

    @Test
    fun paste_bracketedWrapsWithMarkers() {
        encoder.encodePastedText("hi", bracketedPaste = true)

        assertEquals("\u001b[200~hi\u001b[201~", written)
    }

    @Test
    fun paste_emptyText_bracketedStillWraps() {
        encoder.encodePastedText("", bracketedPaste = true)

        assertEquals("\u001b[200~\u001b[201~", written)
    }
}

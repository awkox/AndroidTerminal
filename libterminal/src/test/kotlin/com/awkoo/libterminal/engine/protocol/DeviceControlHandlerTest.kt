package com.awkoo.libterminal.engine.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceControlHandlerTest {

    private val strings = mutableListOf<String>()
    private val handler = DeviceControlHandler { strings += it }

    private val written: String get() = strings.joinToString("")

    @Test
    fun deviceAttributesQuery_reportsVt100Class() {
        handler.handleDeviceControl("\$q\"p", appCursorKeys = false, appKeypad = false)

        assertEquals("\u001bP1\$r64;1\"p\u001b\\", written)
    }

    @Test
    fun xtgettcaps_colorsCapability_reports256() {
        handler.handleDeviceControl("+q436F", appCursorKeys = false, appKeypad = false)

        assertEquals("\u001bP1+r436F=323536\u001b\\", written)
    }

    @Test
    fun xtgettcaps_termName_reportsXterm() {
        handler.handleDeviceControl("+q544E", appCursorKeys = false, appKeypad = false)

        assertEquals("\u001bP1+r544E=787465726D\u001b\\", written)
    }

    @Test
    fun xtgettcaps_knownKeyCapability_reportsHexEncodedSequence() {
        // "k1" = F1 键能力 → "\u001bOP" → hex 1B4F50
        handler.handleDeviceControl("+q6B31", appCursorKeys = false, appKeypad = false)

        assertEquals("\u001bP1+r6B31=1B4F50\u001b\\", written)
    }

    @Test
    fun xtgettcaps_unknownCapability_reportsFailure() {
        handler.handleDeviceControl("+q6161", appCursorKeys = false, appKeypad = false)

        assertEquals("\u001bP0+r6161\u001b\\", written)
    }

    @Test
    fun xtgettcaps_multipleCapabilities_answeredIndividually() {
        handler.handleDeviceControl("+q436F;544E", appCursorKeys = false, appKeypad = false)

        assertEquals("\u001bP1+r436F=323536\u001b\\\u001bP1+r544E=787465726D\u001b\\", written)
    }

    @Test
    fun xtgettcaps_oddLengthPart_isSkipped() {
        handler.handleDeviceControl("+qabc", appCursorKeys = false, appKeypad = false)

        assertEquals("", written)
    }
}

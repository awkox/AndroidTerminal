package com.awkoo.libterminal.engine.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    fun xtgettcaps_unknownCapability_reportsBareFailure() {
        handler.handleDeviceControl("+q6161", appCursorKeys = false, appKeypad = false)

        assertEquals("\u001bP0+r\u001b\\", written)
    }

    @Test
    fun xtgettcaps_invalidName_terminatesTheList() {
        // 前段有效（k1=F1）、后段无效（aa）：成功对先回，失败即终止，后续不再处理
        handler.handleDeviceControl("+q6B31;6161", appCursorKeys = false, appKeypad = false)

        assertEquals("\u001bP1+r6B31=1B4F50\u001b\\\u001bP0+r\u001b\\", written)
    }

    @Test
    fun xtgettcaps_invalidNameFirst_skipsRemainingNames() {
        handler.handleDeviceControl("+q6161;6B31", appCursorKeys = false, appKeypad = false)

        assertEquals("\u001bP0+r\u001b\\", written)
    }

    @Test
    fun xtgettcaps_multipleCapabilities_answeredIndividually() {
        handler.handleDeviceControl("+q436F;544E", appCursorKeys = false, appKeypad = false)

        assertEquals("\u001bP1+r436F=323536\u001b\\\u001bP1+r544E=787465726D\u001b\\", written)
    }

    @Test
    fun xtgettcaps_oddLengthPart_reportsBareFailure() {
        handler.handleDeviceControl("+qabc", appCursorKeys = false, appKeypad = false)

        assertEquals("\u001bP0+r\u001b\\", written)
    }

    @Test
    fun xtgettcaps_nonHexPart_reportsBareFailureWithoutEcho() {
        handler.handleDeviceControl("+q6161zz", appCursorKeys = false, appKeypad = false)

        assertEquals("\u001bP0+r\u001b\\", written)
    }

    @Test
    fun xtgettcaps_injectionPayload_isNeverEchoedIntoInputChannel() {
        // 回归：含换行的注入载荷（原可经失败回显直通进程 stdin 执行命令）
        handler.handleDeviceControl("+q41\nls\n", appCursorKeys = false, appKeypad = false)

        assertEquals("\u001bP0+r\u001b\\", written)
        assertTrue(!written.contains("\n"))
    }

    @Test
    fun xtgettcaps_lowercaseHex_stillWorks() {
        handler.handleDeviceControl("+q436f", appCursorKeys = false, appKeypad = false)

        assertEquals("\u001bP1+r436f=323536\u001b\\", written)
    }
}

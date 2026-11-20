package com.awkoo.libterminal.engine.protocol.ansi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ANSI 转义序列解析器的状态机行为。
 *
 * 用录制型 [TerminalActionHandler] 捕获全部回调，验证：
 * C0 控制分发、CSI 参数与冒号子参数、OSC/DCS 字符串收集、
 * 序列打断与恢复、以及参数边界。
 */
class AnsiEscapeParserTest {

    private data class CsiCall(
        val state: EscapeState,
        val command: Int,
        val args: List<Int>,
        val argCount: Int,
        val subParams: Int
    )

    private class RecordingHandler : TerminalActionHandler {
        val codePoints = mutableListOf<Int>()
        val controls = mutableListOf<String>()
        val escCommands = mutableListOf<Pair<EscapeState, Int>>()
        val csiCommands = mutableListOf<CsiCall>()
        val oscCommands = mutableListOf<Triple<Int, String, String>>()
        val deviceControls = mutableListOf<String>()
        var softResets = 0

        override fun onCodePoint(codePoint: Int) {
            codePoints += codePoint
        }

        override fun onBell() {
            controls += "BEL"
        }

        override fun onBackspace() {
            controls += "BS"
        }

        override fun onHorizontalTab() {
            controls += "HT"
        }

        override fun onLinefeed() {
            controls += "LF"
        }

        override fun onCarriageReturn() {
            controls += "CR"
        }

        override fun onShiftIn() {
            controls += "SI"
        }

        override fun onShiftOut() {
            controls += "SO"
        }

        override fun onEscCommand(state: EscapeState, command: Int) {
            escCommands += state to command
        }

        override fun onCsiCommand(
            state: EscapeState,
            command: Int,
            args: IntArray,
            argCount: Int,
            subParams: Int
        ) {
            csiCommands += CsiCall(state, command, args.take(argCount), argCount, subParams)
        }

        override fun onOscCommand(value: Int, textParameter: String, bellOrStringTerminator: String) {
            oscCommands += Triple(value, textParameter, bellOrStringTerminator)
        }

        override fun onDeviceControl(dcs: String) {
            deviceControls += dcs
        }

        override fun onSoftReset() {
            softResets++
        }
    }

    private val handler = RecordingHandler()
    private val parser = AnsiEscapeParser(handler)

    private fun feed(text: String) {
        for (b in text.toByteArray()) parser.processCodePoint(b.toInt() and 0xFF)
    }

    @Test
    fun printableText_isForwardedAsCodePoints() {
        feed("hi")

        assertEquals(listOf('h'.code, 'i'.code), handler.codePoints)
    }

    @Test
    fun c0Controls_areDispatched() {
        feed("\u0007\u0008\u0009\n\r\u000f\u000e")

        assertEquals(listOf("BEL", "BS", "HT", "LF", "CR", "SI", "SO"), handler.controls)
    }

    @Test
    fun nul_isIgnored() {
        feed("\u0000a")

        assertEquals(listOf('a'.code), handler.codePoints)
    }

    @Test
    fun canOrSub_cancelsActiveSequence() {
        feed("\u001b[12\u0018")

        assertTrue(handler.csiCommands.isEmpty())
        assertEquals(listOf(127), handler.codePoints)
    }

    @Test
    fun escSeven_reportsEscCommand() {
        feed("\u001b7")

        assertEquals(listOf(EscapeState.ESC to '7'.code), handler.escCommands)
    }

    @Test
    fun escLeftParen_designatesCharset() {
        feed("\u001b(0")

        assertEquals(listOf(EscapeState.SELECT_LEFT_PAREN to '0'.code), handler.escCommands)
    }

    @Test
    fun escRightParen_designatesG1() {
        feed("\u001b)0")

        assertEquals(listOf(EscapeState.SELECT_RIGHT_PAREN to '0'.code), handler.escCommands)
    }

    @Test
    fun csiWithoutArgs_reportsEmptyArgsAsMinusOne() {
        feed("\u001b[H")

        val call = handler.csiCommands.single()
        assertEquals(EscapeState.CSI, call.state)
        assertEquals('H'.code, call.command)
        assertEquals(1, call.argCount)
        assertEquals(listOf(-1), call.args)
    }

    @Test
    fun csiWithSemicolonArgs_parsesEachParameter() {
        feed("\u001b[2;10H")

        val call = handler.csiCommands.single()
        assertEquals(listOf(2, 10), call.args)
        assertEquals(2, call.argCount)
    }

    @Test
    fun csiQuestionMarkPrefix_reportsPrivateState() {
        feed("\u001b[?25h")

        val call = handler.csiCommands.single()
        assertEquals(EscapeState.CSI_QUESTIONMARK, call.state)
        assertEquals(listOf(25), call.args)
    }

    @Test
    fun colonSeparatedSubParams_setSubParamBits() {
        feed("\u001b[38:2::10:20:30m")

        val call = handler.csiCommands.single()
        assertEquals(listOf(38, 2, -1, 10, 20, 30), call.args)
        assertEquals(0, call.subParams and 1)
        assertTrue(call.subParams and (1 shl 1) != 0)
        assertTrue(call.subParams and (1 shl 3) != 0)
    }

    @Test
    fun csiIntermediateSpace_reportsSpaceState() {
        feed("\u001b[5 q")

        val call = handler.csiCommands.single()
        assertEquals(EscapeState.CSI_INTERMEDIATE_SPACE, call.state)
        assertEquals('q'.code, call.command)
    }

    @Test
    fun csiGreaterThan_reportsGreaterState() {
        feed("\u001b[>c")

        val call = handler.csiCommands.single()
        assertEquals(EscapeState.CSI_GREATER_THAN, call.state)
    }

    @Test
    fun csiExclamation_reportsExclamationState() {
        feed("\u001b[!p")

        val call = handler.csiCommands.single()
        assertEquals(EscapeState.CSI_EXCLAMATION, call.state)
        assertEquals('p'.code, call.command)
    }

    @Test
    fun unsupportedParameterBytes_areSwallowed() {
        feed("\u001b[<1H")

        assertTrue(handler.csiCommands.isEmpty())
    }

    @Test
    fun oscTerminatedByBell_isReported() {
        feed("\u001b]0;Hello\u0007")

        assertEquals(Triple(0, "Hello", "\u0007"), handler.oscCommands.single())
    }

    @Test
    fun oscTerminatedByStringTerminator_isReported() {
        feed("\u001b]2;Title\u001b\\")

        assertEquals(Triple(2, "Title", "\u001b\\"), handler.oscCommands.single())
    }

    @Test
    fun dcsString_isCollectedAndReported() {
        feed("\u001bP\$q\"p\u001b\\")

        assertEquals(listOf("\$q\"p"), handler.deviceControls)
    }

    @Test
    fun sequenceSplitAcrossFeeds_keepsState() {
        feed("\u001b[12")
        assertTrue(handler.csiCommands.isEmpty())

        feed("H")

        val call = handler.csiCommands.single()
        assertEquals('H'.code, call.command)
        assertEquals(listOf(12), call.args)
    }

    @Test
    fun abandonedEscape_restartsCleanly() {
        feed("\u001b[12\u001b[3H")

        val call = handler.csiCommands.single()
        assertEquals('H'.code, call.command)
        assertEquals(listOf(3), call.args)
    }

    @Test
    fun unknownEscFinalByte_isReportedAsEscCommand() {
        feed("\u001bZ")

        assertEquals(listOf(EscapeState.ESC to 'Z'.code), handler.escCommands)
        assertTrue(handler.csiCommands.isEmpty())
    }

    @Test
    fun argValue_isCappedAt9999() {
        feed("\u001b[1234567H")

        assertEquals(listOf(9999), handler.csiCommands.single().args)
    }

    @Test
    fun argCount_isCappedAtParserCapacity() {
        val many = (1..40).joinToString(";") { "1" }
        feed("\u001b[${many}H")

        val call = handler.csiCommands.single()
        assertTrue(call.argCount <= AnsiEscapeParser.MAX_ESCAPE_PARAMETERS)
    }

    @Test
    fun delCharacter_isForwardedAsCodePoint() {
        feed("\u007f")

        assertEquals(listOf(127), handler.codePoints)
    }

    @Test
    fun reset_clearsPartialState() {
        feed("\u001b[38:2")
        parser.reset()

        feed("a")

        assertEquals(listOf('a'.code), handler.codePoints)
        assertTrue(handler.csiCommands.isEmpty())
    }

    @Test
    fun getArg_defaultsAndTreatZero() {
        val args = intArrayOf(-1, 0, 5)

        assertEquals(7, AnsiEscapeParser.getArg(args, 0, 7, true))
        assertEquals(7, AnsiEscapeParser.getArg(args, 1, 7, true))
        assertEquals(0, AnsiEscapeParser.getArg(args, 1, 7, false))
        assertEquals(5, AnsiEscapeParser.getArg(args, 2, 7, true))
        assertEquals(3, AnsiEscapeParser.getArg(args, 99, 3, true))
    }

    @Test
    fun vtAndFf_dispatchAsLinefeed_andSubCancelsSequence() {
        feed("\u000b\u000c\u001b[12\u001aZ")

        assertEquals(listOf("LF", "LF"), handler.controls)
        assertTrue(handler.csiCommands.isEmpty())
        assertEquals(listOf(127, 'Z'.code), handler.codePoints)
    }

    @Test
    fun escPoundAndPercent_reportExpectedStates() {
        feed("\u001b#8\u001b%G")

        assertEquals(
            listOf(EscapeState.POUND to '8'.code, EscapeState.PERCENT to 'G'.code),
            handler.escCommands
        )
    }

    @Test
    fun apcSequence_isSwallowedIncludingLoneEscapeUntilSt() {
        feed("a\u001b_payload\u001bxmore\u001b\\b")

        assertEquals(listOf('a'.code, 'b'.code), handler.codePoints)
        assertTrue(handler.escCommands.isEmpty())
        assertTrue(handler.csiCommands.isEmpty())
    }

    @Test
    fun csiIntermediateStates_reportDollarQuotesAsteriskAndQuestionDollar() {
        feed("\u001b[1;2\$v\u001b[1\"q\u001b[2'}\u001b[2*x\u001b[?7\$p")

        assertEquals(5, handler.csiCommands.size)
        assertEquals(EscapeState.CSI_DOLLAR, handler.csiCommands[0].state)
        assertEquals('v'.code, handler.csiCommands[0].command)
        assertEquals(listOf(1, 2), handler.csiCommands[0].args)

        assertEquals(EscapeState.CSI_DOUBLE_QUOTE, handler.csiCommands[1].state)
        assertEquals('q'.code, handler.csiCommands[1].command)
        assertEquals(listOf(1), handler.csiCommands[1].args)

        assertEquals(EscapeState.CSI_SINGLE_QUOTE, handler.csiCommands[2].state)
        assertEquals('}'.code, handler.csiCommands[2].command)
        assertEquals(listOf(2), handler.csiCommands[2].args)

        assertEquals(EscapeState.CSI_INTERMEDIATE_ASTERISK, handler.csiCommands[3].state)
        assertEquals('x'.code, handler.csiCommands[3].command)
        assertEquals(listOf(2), handler.csiCommands[3].args)

        assertEquals(EscapeState.CSI_QUESTIONMARK_ARG_DOLLAR, handler.csiCommands[4].state)
        assertEquals('p'.code, handler.csiCommands[4].command)
        assertEquals(listOf(7), handler.csiCommands[4].args)
    }

    @Test
    fun unsupportedEqualsAndChainedIntermediateBytes_areSwallowedCleanly() {
        feed("\u001b[=1H\u001b[<1;2!Ha")

        assertTrue(handler.csiCommands.isEmpty())
        assertEquals(listOf('a'.code), handler.codePoints)
    }

    @Test
    fun oscEdgeCases_nonNumericCodeDropped_noSemicolonEmptyParam_andLoneEscPreserved() {
        feed("\u001b]abc;ignored\u0007")
        feed("\u001b]104\u0007")
        feed("\u001b]2;a\u001bXb\u001b\\")

        assertEquals(2, handler.oscCommands.size)
        assertEquals(Triple(104, "", "\u0007"), handler.oscCommands[0])
        assertEquals(Triple(2, "a\u001bXb", "\u001b\\"), handler.oscCommands[1])
    }

    @Test
    fun oscLengthCap_truncatesBeyondMaxOscStringLength() {
        val longText = "x".repeat(AnsiEscapeParser.MAX_OSC_STRING_LENGTH + 50)
        feed("\u001b]2;$longText\u0007")

        val reported = handler.oscCommands.single()
        assertEquals(2, reported.first)
        assertEquals(AnsiEscapeParser.MAX_OSC_STRING_LENGTH - 2, reported.second.length)
    }

    @Test
    fun osc_dispatchesLinefeedAndCancelSeparately() {
        feed("\u001b]2;ab\ncd\u0018Z")

        assertEquals(listOf("LF"), handler.controls)
        assertTrue(handler.oscCommands.isEmpty())
        assertEquals(listOf(127, 'Z'.code), handler.codePoints)
    }

    @Test
    fun dcs_preservesLoneEscapeAndNewlinesAndCapsLength() {
        feed("\u001bPab\ncd\u001bXe\u001b\\")
        val exactDcs = "z".repeat(AnsiEscapeParser.MAX_OSC_STRING_LENGTH)
        feed("\u001bP$exactDcs\u001b\\")
        val longDcs = "y".repeat(AnsiEscapeParser.MAX_OSC_STRING_LENGTH + 20)
        feed("\u001bP$longDcs\u001b\\")

        assertEquals("ab\ncd\u001bXe", handler.deviceControls[0])
        assertTrue(handler.controls.isEmpty())
        assertEquals(exactDcs, handler.deviceControls[1])
        assertEquals(AnsiEscapeParser.MAX_OSC_STRING_LENGTH, handler.deviceControls[2].length)
        assertEquals("y".repeat(AnsiEscapeParser.MAX_OSC_STRING_LENGTH), handler.deviceControls[2])
    }
}

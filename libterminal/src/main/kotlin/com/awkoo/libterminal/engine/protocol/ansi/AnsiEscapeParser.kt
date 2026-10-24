package com.awkoo.libterminal.engine.protocol.ansi

import kotlin.math.min

internal class AnsiEscapeParser(private val handler: TerminalActionHandler) {
    private var escapeState = EscapeState.NONE
    private val args = IntArray(MAX_ESCAPE_PARAMETERS)
    private var argIndex = 0
    private var subParamSeparatorMask = 0
    private val oscOrDeviceControlArgs = StringBuilder()
    private var isSequenceIncomplete = false

    fun processCodePoint(b: Int) {
        when (escapeState) {
            EscapeState.APC -> { doApc(b); return }
            EscapeState.APC_ESCAPE -> { doApcEscape(b); return }
            EscapeState.P -> { doDeviceControl(b); return }
            EscapeState.P_ESCAPE -> { doDeviceControlEscape(b); return }
            else -> {}
        }

        when (b) {
            0 -> return
            7 -> if (escapeState == EscapeState.OSC) doOsc(b) else handler.onBell()
            8 -> handler.onBackspace()
            9 -> handler.onHorizontalTab()
            10, 11, 12 -> handler.onLinefeed()
            13 -> handler.onCarriageReturn()
            14 -> handler.onShiftOut()
            15 -> handler.onShiftIn()
            24, 26 -> if (escapeState != EscapeState.NONE) {
                escapeState = EscapeState.NONE
                handler.onCodePoint(127)
            }
            27 -> {
                if (escapeState == EscapeState.OSC) doOsc(b) else startEscapeSequence()
            }
            else -> {
                isSequenceIncomplete = false
                when (escapeState) {
                    EscapeState.NONE -> if (b >= 32) handler.onCodePoint(b)
                    EscapeState.ESC -> doEsc(b)
                    EscapeState.POUND -> { handler.onEscCommand(EscapeState.POUND, b); finishSequence() }
                    EscapeState.SELECT_LEFT_PAREN -> { handler.onEscCommand(EscapeState.SELECT_LEFT_PAREN, b); finishSequence() }
                    EscapeState.SELECT_RIGHT_PAREN -> { handler.onEscCommand(EscapeState.SELECT_RIGHT_PAREN, b); finishSequence() }
                    EscapeState.CSI -> doCsi(b)
                    EscapeState.CSI_UNSUPPORTED_PARAMETER_BYTE,
                    EscapeState.CSI_UNSUPPORTED_INTERMEDIATE_BYTE -> doCsiUnsupportedParameterOrIntermediateByte(b)
                    EscapeState.CSI_QUESTIONMARK -> doCsiQuestionMark(b)
                    EscapeState.CSI_BIGGER_THAN -> doCsiBiggerThan(b)

                    EscapeState.CSI_EXCLAMATION,
                    EscapeState.CSI_DOLLAR,
                    EscapeState.CSI_DOUBLE_QUOTE,
                    EscapeState.CSI_SINGLE_QUOTE,
                    EscapeState.CSI_QUESTIONMARK_ARG_DOLLAR,
                    EscapeState.CSI_INTERMEDIATE_SPACE,
                    EscapeState.CSI_INTERMEDIATE_ASTERISK -> {
                        handler.onCsiCommand(escapeState, b, args, argIndex + 1, subParamSeparatorMask)
                    }

                    EscapeState.PERCENT -> { handler.onEscCommand(EscapeState.PERCENT, b); finishSequence() }
                    EscapeState.OSC -> doOsc(b)
                    EscapeState.OSC_ESC -> doOscEsc(b)
                    else -> unknownSequence(b)
                }
                if (!isSequenceIncomplete) finishSequence()
            }
        }
    }

    private fun startEscapeSequence() {
        escapeState = EscapeState.ESC
        argIndex = 0
        args.fill(-1)
        subParamSeparatorMask = 0
    }

    private fun continueSequence(state: EscapeState) {
        escapeState = state
        isSequenceIncomplete = true
    }

    private fun finishSequence() {
        escapeState = EscapeState.NONE
    }

    private fun parseArg(b: Int) {
        when {
            b in '0'.code..'9'.code -> {
                if (argIndex < args.size) {
                    val oldValue = args[argIndex]
                    val thisDigit = b - '0'.code
                    args[argIndex] = min(
                        if (oldValue >= 0) oldValue * 10 + thisDigit else thisDigit,
                        9999
                    )
                }
                continueSequence(escapeState)
            }
            b == ';'.code || b == ':'.code -> {
                if (argIndex + 1 < args.size) {
                    argIndex++
                    if (b == ':'.code) {
                        subParamSeparatorMask = subParamSeparatorMask or (1 shl argIndex)
                    }
                }
                continueSequence(escapeState)
            }
            else -> unknownSequence(b)
        }
    }

    private fun doEsc(b: Int) {
        when (b.toChar()) {
            '#' -> continueSequence(EscapeState.POUND)
            '%' -> continueSequence(EscapeState.PERCENT)
            '(' -> continueSequence(EscapeState.SELECT_LEFT_PAREN)
            ')' -> continueSequence(EscapeState.SELECT_RIGHT_PAREN)
            'P' -> {
                oscOrDeviceControlArgs.setLength(0)
                continueSequence(EscapeState.P)
            }
            '[' -> continueSequence(EscapeState.CSI)
            ']' -> {
                oscOrDeviceControlArgs.setLength(0)
                continueSequence(EscapeState.OSC)
            }
            '_' -> continueSequence(EscapeState.APC)
            else -> handler.onEscCommand(EscapeState.ESC, b)
        }
    }

    private fun doCsi(b: Int) {
        when (b.toChar()) {
            '!' -> continueSequence(EscapeState.CSI_EXCLAMATION)
            '"' -> continueSequence(EscapeState.CSI_DOUBLE_QUOTE)
            '\'' -> continueSequence(EscapeState.CSI_SINGLE_QUOTE)
            '$' -> continueSequence(EscapeState.CSI_DOLLAR)
            '*' -> continueSequence(EscapeState.CSI_INTERMEDIATE_ASTERISK)
            '?' -> continueSequence(EscapeState.CSI_QUESTIONMARK)
            '>' -> continueSequence(EscapeState.CSI_BIGGER_THAN)
            '<', '=' -> continueSequence(EscapeState.CSI_UNSUPPORTED_PARAMETER_BYTE)
            ' ' -> continueSequence(EscapeState.CSI_INTERMEDIATE_SPACE)
            else -> handleCsiCommonArgs(b) {
                handler.onCsiCommand(EscapeState.CSI, b, args, argIndex + 1, subParamSeparatorMask)
            }
        }
    }

    private fun doCsiQuestionMark(b: Int) {
        when (b.toChar()) {
            '$' -> continueSequence(EscapeState.CSI_QUESTIONMARK_ARG_DOLLAR)
            else -> handleCsiCommonArgs(b) {
                handler.onCsiCommand(EscapeState.CSI_QUESTIONMARK, b, args, argIndex + 1, subParamSeparatorMask)
            }
        }
    }

    private fun doCsiBiggerThan(b: Int) {
        handleCsiCommonArgs(b) {
            handler.onCsiCommand(EscapeState.CSI_BIGGER_THAN, b, args, argIndex + 1, subParamSeparatorMask)
        }
    }

    private fun doCsiUnsupportedParameterOrIntermediateByte(b: Int) {
        when {
            escapeState == EscapeState.CSI_UNSUPPORTED_PARAMETER_BYTE && b in 0x30..0x3F -> continueSequence(EscapeState.CSI_UNSUPPORTED_PARAMETER_BYTE)
            b in 0x20..0x2F -> continueSequence(EscapeState.CSI_UNSUPPORTED_INTERMEDIATE_BYTE)
            b in 0x40..0x7E -> finishSequence()
            else -> unknownSequence(b)
        }
    }

    private fun doOsc(b: Int) {
        when (b) {
            7 -> doOscSetTextParameters("\u0007")
            27 -> continueSequence(EscapeState.OSC_ESC)
            else -> collectOSCArgs(b)
        }
    }

    private fun doOscEsc(b: Int) {
        when (b) {
            '\\'.code -> doOscSetTextParameters("\u001b\\")
            else -> {
                collectOSCArgs(27)
                collectOSCArgs(b)
                continueSequence(EscapeState.OSC)
            }
        }
    }

    private fun collectOSCArgs(b: Int) {
        if (oscOrDeviceControlArgs.length < MAX_OSC_STRING_LENGTH) {
            oscOrDeviceControlArgs.appendCodePoint(b)
        }
        continueSequence(escapeState)
    }

    private fun doOscSetTextParameters(bellOrStringTerminator: String) {
        val separatorIndex = oscOrDeviceControlArgs.indexOf(';')
        val valuePart = if (separatorIndex < 0) {
            oscOrDeviceControlArgs.toString()
        } else {
            oscOrDeviceControlArgs.substring(0, separatorIndex)
        }
        val illegal = valuePart.firstOrNull { it !in '0'..'9' }
        if (illegal != null) {
            unknownSequence(illegal.code)
            return
        }
        val value = valuePart.toIntOrNull() ?: -1
        val textParameter =
            if (separatorIndex < 0) "" else oscOrDeviceControlArgs.substring(separatorIndex + 1)
        handler.onOscCommand(value, textParameter, bellOrStringTerminator)
        finishSequence()
    }

    private fun doDeviceControl(b: Int) {
        when (b) {
            '\u001b'.code -> continueSequence(EscapeState.P_ESCAPE)
            else -> {
                if (oscOrDeviceControlArgs.length <= MAX_OSC_STRING_LENGTH) {
                    oscOrDeviceControlArgs.appendCodePoint(b)
                }
                continueSequence(escapeState)
            }
        }
    }

    private fun doDeviceControlEscape(b: Int) {
        if (b == '\\'.code) {
            handler.onDeviceControl(oscOrDeviceControlArgs.toString())
            finishSequence()
        } else {
            if (oscOrDeviceControlArgs.length + 2 <= MAX_OSC_STRING_LENGTH) {
                oscOrDeviceControlArgs.appendCodePoint(27)
                oscOrDeviceControlArgs.appendCodePoint(b)
            }
            continueSequence(EscapeState.P)
        }
    }

    private fun doApc(b: Int) {
        if (b == 27) continueSequence(EscapeState.APC_ESCAPE)
    }

    private fun doApcEscape(b: Int) {
        if (b == '\\'.code) finishSequence() else continueSequence(EscapeState.APC)
    }

    private fun unknownSequence(b: Int) {
        finishSequence()
    }

    private inline fun handleCsiCommonArgs(b: Int, fallback: () -> Unit) {
        val c = b.toChar()
        if (c in '0'..'9' || c == ';' || c == ':') {
            parseArg(b)
        } else {
            fallback()
        }
    }

    fun reset() {
        escapeState = EscapeState.NONE
        argIndex = 0
        isSequenceIncomplete = false
        args.fill(-1)
        oscOrDeviceControlArgs.setLength(0)
    }

    companion object {

        const val MAX_ESCAPE_PARAMETERS = 32
        const val MAX_OSC_STRING_LENGTH = 8192

        inline fun getArg(args: IntArray, index: Int, defaultValue: Int, treatZeroAsDefault: Boolean): Int {
            if (index >= args.size) return defaultValue
            val arg = args[index]
            return if (arg < 0 || (arg == 0 && treatZeroAsDefault)) defaultValue else arg
        }
    }
}
package com.awkoo.libterminal.engine.protocol.ansi

import kotlin.math.min

internal class AnsiEscapeParser(private val handler: TerminalActionHandler) {
    private var mEscapeState = EscapeState.NONE
    private val mArgs = IntArray(MAX_ESCAPE_PARAMETERS)
    private var mArgIndex = 0
    private var mArgsSubParamsBitSet = 0
    private val mOSCOrDeviceControlArgs = StringBuilder()
    private var mContinueSequence = false

    fun processCodePoint(b: Int) {
        when (mEscapeState) {
            EscapeState.APC -> { doApc(b); return }
            EscapeState.APC_ESCAPE -> { doApcEscape(b); return }
            EscapeState.P -> { doDeviceControl(b); return }
            EscapeState.P_ESCAPE -> { doDeviceControlEscape(b); return }
            else -> {}
        }

        when (b) {
            0 -> return
            7 -> if (mEscapeState == EscapeState.OSC) doOsc(b) else handler.onBell()
            8 -> handler.onBackspace()
            9 -> handler.onHorizontalTab()
            10, 11, 12 -> handler.onLinefeed()
            13 -> handler.onCarriageReturn()
            14 -> handler.onShiftOut()
            15 -> handler.onShiftIn()
            24, 26 -> if (mEscapeState != EscapeState.NONE) {
                mEscapeState = EscapeState.NONE
                handler.onCodePoint(127)
            }
            27 -> {
                if (mEscapeState == EscapeState.OSC) doOsc(b) else startEscapeSequence()
            }
            else -> {
                mContinueSequence = false
                when (mEscapeState) {
                    EscapeState.NONE -> if (b >= 32) handler.onCodePoint(b)
                    EscapeState.ESC -> doEsc(b)
                    EscapeState.POUND -> { handler.onEscCommand(EscapeState.POUND, b); finishSequence() }
                    EscapeState.SELECT_LEFT_PAREN -> { handler.onEscCommand(EscapeState.SELECT_LEFT_PAREN, b); finishSequence() }
                    EscapeState.SELECT_RIGHT_PAREN -> { handler.onEscCommand(EscapeState.SELECT_RIGHT_PAREN, b); finishSequence() }
                    EscapeState.CSI -> doCsi(b)
                    EscapeState.CSI_UNSUPPORTED_PARAMETER_BYTE,
                    EscapeState.CSI_UNSUPPORTED_INTERMEDIATE_BYTE -> doCsiUnsupportedParameterOrIntermediateByte(b)
                    EscapeState.CSI_QUESTIONMARK -> doCsiQuestionMark(b)
                    EscapeState.CSI_BIGGERTHAN -> doCsiBiggerThan(b)

                    EscapeState.CSI_EXCLAMATION,
                    EscapeState.CSI_DOLLAR,
                    EscapeState.CSI_DOUBLE_QUOTE,
                    EscapeState.CSI_SINGLE_QUOTE,
                    EscapeState.CSI_QUESTIONMARK_ARG_DOLLAR,
                    EscapeState.CSI_ARGS_SPACE,
                    EscapeState.CSI_ARGS_ASTERIX -> {
                        handler.onCsiCommand(mEscapeState, b, mArgs, mArgIndex + 1, mArgsSubParamsBitSet)
                    }

                    EscapeState.PERCENT -> { handler.onEscCommand(EscapeState.PERCENT, b); finishSequence() }
                    EscapeState.OSC -> doOsc(b)
                    EscapeState.OSC_ESC -> doOscEsc(b)
                    else -> unknownSequence(b)
                }
                if (!mContinueSequence) finishSequence()
            }
        }
    }

    private fun startEscapeSequence() {
        mEscapeState = EscapeState.ESC
        mArgIndex = 0
        mArgs.fill(-1)
        mArgsSubParamsBitSet = 0
    }

    private fun continueSequence(state: EscapeState) {
        mEscapeState = state
        mContinueSequence = true
    }

    private fun finishSequence() {
        mEscapeState = EscapeState.NONE
    }

    private fun parseArg(b: Int) {
        when {
            b in '0'.code..'9'.code -> {
                if (mArgIndex < mArgs.size) {
                    val oldValue = mArgs[mArgIndex]
                    val thisDigit = b - '0'.code
                    mArgs[mArgIndex] = min(
                        if (oldValue >= 0) oldValue * 10 + thisDigit else thisDigit,
                        9999
                    )
                }
                continueSequence(mEscapeState)
            }
            b == ';'.code || b == ':'.code -> {
                if (mArgIndex + 1 < mArgs.size) {
                    mArgIndex++
                    if (b == ':'.code) {
                        mArgsSubParamsBitSet = mArgsSubParamsBitSet or (1 shl mArgIndex)
                    }
                }
                continueSequence(mEscapeState)
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
                mOSCOrDeviceControlArgs.setLength(0)
                continueSequence(EscapeState.P)
            }
            '[' -> continueSequence(EscapeState.CSI)
            ']' -> {
                mOSCOrDeviceControlArgs.setLength(0)
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
            '*' -> continueSequence(EscapeState.CSI_ARGS_ASTERIX)
            '?' -> continueSequence(EscapeState.CSI_QUESTIONMARK)
            '>' -> continueSequence(EscapeState.CSI_BIGGERTHAN)
            '<', '=' -> continueSequence(EscapeState.CSI_UNSUPPORTED_PARAMETER_BYTE)
            ' ' -> continueSequence(EscapeState.CSI_ARGS_SPACE)
            else -> handleCsiCommonArgs(b) {
                handler.onCsiCommand(EscapeState.CSI, b, mArgs, mArgIndex + 1, mArgsSubParamsBitSet)
            }
        }
    }

    private fun doCsiQuestionMark(b: Int) {
        when (b.toChar()) {
            '$' -> continueSequence(EscapeState.CSI_QUESTIONMARK_ARG_DOLLAR)
            else -> handleCsiCommonArgs(b) {
                handler.onCsiCommand(EscapeState.CSI_QUESTIONMARK, b, mArgs, mArgIndex + 1, mArgsSubParamsBitSet)
            }
        }
    }

    private fun doCsiBiggerThan(b: Int) {
        handleCsiCommonArgs(b) {
            handler.onCsiCommand(EscapeState.CSI_BIGGERTHAN, b, mArgs, mArgIndex + 1, mArgsSubParamsBitSet)
        }
    }

    private fun doCsiUnsupportedParameterOrIntermediateByte(b: Int) {
        when {
            mEscapeState == EscapeState.CSI_UNSUPPORTED_PARAMETER_BYTE && b in 0x30..0x3F -> continueSequence(EscapeState.CSI_UNSUPPORTED_PARAMETER_BYTE)
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
        when (b.toChar()) {
            '\\' -> doOscSetTextParameters("\u001b\\")
            else -> {
                collectOSCArgs(27)
                collectOSCArgs(b)
                continueSequence(EscapeState.OSC)
            }
        }
    }

    private fun collectOSCArgs(b: Int) {
        if (mOSCOrDeviceControlArgs.length < MAX_OSC_STRING_LENGTH) {
            mOSCOrDeviceControlArgs.appendCodePoint(b)
        }
        continueSequence(mEscapeState)
    }

    private fun doOscSetTextParameters(bellOrStringTerminator: String) {
        val separatorIndex = mOSCOrDeviceControlArgs.indexOf(';')
        val valuePart = if (separatorIndex < 0) {
            mOSCOrDeviceControlArgs.toString()
        } else {
            mOSCOrDeviceControlArgs.substring(0, separatorIndex)
        }
        val illegal = valuePart.firstOrNull { it !in '0'..'9' }
        if (illegal != null) {
            unknownSequence(illegal.code)
            return
        }
        val value = valuePart.toIntOrNull() ?: -1
        val textParameter =
            if (separatorIndex < 0) "" else mOSCOrDeviceControlArgs.substring(separatorIndex + 1)
        handler.onOscCommand(value, textParameter, bellOrStringTerminator)
        finishSequence()
    }

    private fun doDeviceControl(b: Int) {
        when (b.toByte()) {
            '\\'.code.toByte() -> {
                handler.onDeviceControl(mOSCOrDeviceControlArgs.toString())
                finishSequence()
            }
            27.toByte() -> continueSequence(EscapeState.P_ESCAPE)
            else -> {
                if (mOSCOrDeviceControlArgs.length <= MAX_OSC_STRING_LENGTH) {
                    mOSCOrDeviceControlArgs.appendCodePoint(b)
                }
                continueSequence(mEscapeState)
            }
        }
    }

    private fun doDeviceControlEscape(b: Int) {
        if (b == '\\'.code) {
            handler.onDeviceControl(mOSCOrDeviceControlArgs.toString())
            finishSequence()
        } else {
            if (mOSCOrDeviceControlArgs.length + 2 <= MAX_OSC_STRING_LENGTH) {
                mOSCOrDeviceControlArgs.appendCodePoint(27)
                mOSCOrDeviceControlArgs.appendCodePoint(b)
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
        mEscapeState = EscapeState.NONE
        mArgIndex = 0
        mContinueSequence = false
        mArgs.fill(-1)
        mOSCOrDeviceControlArgs.setLength(0)
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
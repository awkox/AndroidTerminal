package com.awkoo.terminal

import com.awkoo.terminal.constants.LogLevel
import com.awkoo.terminal.constants.TerminalCursorStyle

/**
 * 全局常量与运行时配置。
 */
object Constants {
    val defaultTerminalCursorStyle = TerminalCursorStyle.BAR
    var terminalCursorStyle = defaultTerminalCursorStyle

    const val defaultTerminalTranscriptRows = 5000
    var terminalTranscriptRows = defaultTerminalTranscriptRows
        set(value) { field = value.coerceIn(100, 50000) }

    const val defaultTerminalCursorBlinkerRate: Long = 500L
    var terminalCursorBlinkerRate: Long = defaultTerminalCursorBlinkerRate
        set(value) {
            field = when(value) {
                0L -> 0L
                else -> value.coerceIn(100L, 2000L) 
            }
        }

    var isUsingCtrlSpaceWorkaround = false

    const val DEFAULT_TERMINAL_FONT_SIZE = 12
    const val MIN_TERMINAL_FONT_SIZE = 4
    const val MAX_TERMINAL_FONT_SIZE = 100

    val DEFAULT_LOG_LEVEL = LogLevel.INFO
}

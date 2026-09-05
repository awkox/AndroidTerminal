package com.awkoo.libterminal.engine

import androidx.annotation.Keep

/**
 * 终端光标样式。
 */
@Keep
internal enum class TerminalCursorStyle {
    BLOCK,
    UNDERLINE,
    BAR
}
package com.awkoo.libterminal.engine.protocol.ansi

/**
 * [AnsiEscapeParser] 的序列解析状态，也用作 [TerminalActionHandler] 回调中的状态标识。
 */
internal enum class EscapeState {
    NONE,
    ESC,
    POUND,
    SELECT_LEFT_PAREN,
    SELECT_RIGHT_PAREN,
    CSI,
    CSI_QUESTIONMARK,
    CSI_DOLLAR,
    PERCENT,
    OSC,
    OSC_ESC,
    CSI_BIGGERTHAN,
    P,
    P_ESCAPE,
    CSI_QUESTIONMARK_ARG_DOLLAR,
    CSI_ARGS_SPACE,
    CSI_ARGS_ASTERIX,
    CSI_DOUBLE_QUOTE,
    CSI_SINGLE_QUOTE,
    CSI_EXCLAMATION,
    APC,
    APC_ESCAPE,
    CSI_UNSUPPORTED_PARAMETER_BYTE,
    CSI_UNSUPPORTED_INTERMEDIATE_BYTE
}

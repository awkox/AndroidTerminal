package com.awkoo.terminal

/**
 * 全局常量与运行时配置。
 */
object Constants {
    const val DEFAULT_TERMINAL_FONT_SIZE = 12
    const val MIN_TERMINAL_FONT_SIZE = 4
    const val MAX_TERMINAL_FONT_SIZE = 100
    const val DEFAULT_TERMINAL_TRANSCRIPT_ROWS = 5000
    const val MIN_TERMINAL_TRANSCRIPT_ROWS = 100
    const val MAX_TERMINAL_TRANSCRIPT_ROWS = 100000

    // 临时 SSH 联调参数（测试阶段硬编码，验收后移除）
    const val SSH_TEST_HOST = "127.0.0.1"
    const val SSH_TEST_PORT = 22
    const val SSH_TEST_USER = "awkoo"
    const val SSH_TEST_PASSWORD = "awkoox"
}

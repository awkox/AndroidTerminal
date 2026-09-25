package com.awkoo.terminal.core

import kotlinx.serialization.Serializable

/** 本地 PTY 会话的启动配置（libterminal-pty）。 */
@Serializable
data class PtyConfig(
    val command: String = "sh",
    val args: List<String> = emptyList(),
    val environment: List<PtyEnvVar> = emptyList(),
    val stdin: String = ""
)

/** 单个自定义环境变量，保留用户录入顺序。 */
@Serializable
data class PtyEnvVar(
    val key: String = "",
    val value: String = ""
)
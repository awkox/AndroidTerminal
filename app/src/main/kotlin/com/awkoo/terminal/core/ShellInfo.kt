package com.awkoo.terminal.core

/**
 * Shell 命令启动参数。
 *
 * 在 [CommandInfo] 基础上自动将 HOME 和 TMPDIR 注入环境变量。
 */
class ShellInfo(
    executable: String,
    workingDirectory: String,
    homeDirectory: String? = null,
    tempDirectory: String? = null,
    arguments: Array<String>? = null,
    extraEnvironment: MutableMap<String, String> = mutableMapOf(),
    stdin: String? = null
) : CommandInfo(
    executable,
    workingDirectory,
    arguments,
    extraEnvironment,
    stdin
) {
    init {
        if (!homeDirectory.isNullOrEmpty()) {
            this.extraEnvironment["HOME"] = homeDirectory
        }
        if (!tempDirectory.isNullOrEmpty()) {
            this.extraEnvironment["TMPDIR"] = tempDirectory
        }
    }
}

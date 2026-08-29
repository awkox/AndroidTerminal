package com.awkoo.terminal.core

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 终端命令启动参数。
 *
 * 封装可执行文件路径、工作目录、命令行参数、附加环境变量以及标准输入，
 * 并通过 [ExecutionState] 追踪执行状态。
 */
open class CommandInfo(
    val executable: String,
    val workingDirectory: String,
    arguments: Array<String>? = null,
    val extraEnvironment: MutableMap<String, String> = mutableMapOf(),
    stdin: String? = null
) {
    val stdin: String? = stdin?.let { it + '\r' }

    val arguments: Array<String> = arrayOf("$executable", *(arguments ?: emptyArray()))

    val commandLabel = MutableStateFlow(executable)

    val environmentArray: Array<Array<String>>
        get() = extraEnvironment.map {
            arrayOf(it.key, it.value)
        }.toTypedArray()
}
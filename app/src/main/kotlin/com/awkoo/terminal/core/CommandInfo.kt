package com.awkoo.terminal.core

import androidx.annotation.Keep
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch

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
    /** 命令执行状态。 */
    @Keep
    enum class ExecutionState {
        PRE_EXECUTION,
        EXECUTING,
        EXECUTED,
        SUCCESS,
        FAILED;
        val level = ordinal
    }

    @OptIn(ExperimentalAtomicApi::class)
    val id = endId.incrementAndFetch()

    @Volatile
    var pid: Int = -1

    val stdin: String? = stdin?.let { it + '\r' }

    @Volatile
    var state: ExecutionState = ExecutionState.PRE_EXECUTION
        set(value) {
            if (value.level < field.level || field == ExecutionState.SUCCESS) {
                return
            }
            field = value
        }

    val arguments: Array<String> = arrayOf("-$executable", *(arguments ?: emptyArray()))

    val commandLabel = MutableStateFlow(executable)

    @get:Synchronized
    val environmentArray: Array<Array<String>>
        get() = extraEnvironment.map {
            arrayOf(it.key, it.value)
        }.toTypedArray()

    companion object {
        @OptIn(ExperimentalAtomicApi::class)
        val endId = AtomicInt(0)
    }
}

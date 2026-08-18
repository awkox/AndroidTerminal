package com.awkoo.terminal.core

import com.awkoo.terminal.constants.LogLevel
import timber.log.Timber

/**
 * 基于 Timber 的日志过滤树。
 *
 * 根据 [level] 过滤低于指定级别的日志输出。
 */
class TimberLogTree(
    var level: LogLevel
) : Timber.DebugTree() {
    override fun log(
        priority: Int,
        tag: String?,
        message: String,
        t: Throwable?
    ) {
        if (level == LogLevel.OFF) return
        if (priority < level.value) return

        super.log(priority, tag, message, t)
    }
}

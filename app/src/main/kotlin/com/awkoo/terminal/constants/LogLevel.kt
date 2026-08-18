package com.awkoo.terminal.constants

import android.util.Log
import androidx.annotation.Keep

/**
 * 日志级别枚举。
 */
@Keep
enum class LogLevel(val value: Int) {
    VERBOSE(Log.VERBOSE),   // 2
    DEBUG(Log.DEBUG),       // 3
    INFO(Log.INFO),         // 4
    WARNING(Log.WARN),   // 5
    ERROR(Log.ERROR),       // 6
    OFF(-1);                // -1

    companion object {
        fun fromValue(value: Int): LogLevel? = 
            entries.find { it.value == value }
    }
}

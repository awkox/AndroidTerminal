package com.awkoo.terminal.core

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.awkoo.terminal.Constants
import com.awkoo.terminal.constants.LogLevel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.map
import javax.inject.Inject

private val Context.dataStore by preferencesDataStore("UserSettings")

/**
 * 应用偏好设置。
 *
 * 通过 DataStore 持久化终端字体大小和日志级别等配置。
 */
class AppPreferences @Inject constructor(
    @ApplicationContext context: Context
) {
    private val datastore = context.dataStore

    val terminalFontSize = datastore.data.map {
        (it[terminalFontSizeKey] ?: Constants.DEFAULT_TERMINAL_FONT_SIZE)
            .coerceIn(Constants.MIN_TERMINAL_FONT_SIZE,
                Constants.MAX_TERMINAL_FONT_SIZE)
    }

    suspend fun setTerminalFontSize(size: Int) {
        datastore.edit {
            it[terminalFontSizeKey] = size
        }
    }

    val logLevel = datastore.data.map {
        it[logLevelKey]
            ?.let(LogLevel::fromValue)
            ?: Constants.DEFAULT_LOG_LEVEL
    }

    companion object {
        private val terminalFontSizeKey = intPreferencesKey("TerminalFontSize")
        private val logLevelKey = intPreferencesKey("LogLevel")
    }
}

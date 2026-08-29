package com.awkoo.terminal

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.awkoo.terminal.Constants
import com.awkoo.terminal.ui.theme.ThemeMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
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
            .coerceIn(Constants.MIN_TERMINAL_FONT_SIZE, Constants.MAX_TERMINAL_FONT_SIZE)
    }

    suspend fun setTerminalFontSize(size: Int) {
        datastore.edit {
            it[terminalFontSizeKey] = size
        }
    }

    /** 主题模式偏好，读取未知/非法字符串时回落到默认深色。 */
    val themeMode: Flow<ThemeMode> = datastore.data.map { prefs ->
        try {
            ThemeMode.valueOf(prefs[themeModeKey] ?: return@map ThemeMode.DARK)
        } catch (e: IllegalArgumentException) {
            // 未知或非法字符串回落到默认深色
            ThemeMode.DARK
        }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        datastore.edit {
            it[themeModeKey] = mode.name
        }
    }

    companion object {
        private val terminalFontSizeKey = intPreferencesKey("TerminalFontSize")
        private val themeModeKey = stringPreferencesKey("ThemeMode")
    }
}

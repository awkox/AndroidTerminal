package com.awkoo.terminal

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.awkoo.libterminal.engine.TerminalCursorStyle
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

    /** 历史回滚缓冲区行数偏好，非法值限制在 [Constants.MIN_TERMINAL_TRANSCRIPT_ROWS] 与
     *  [Constants.MAX_TERMINAL_TRANSCRIPT_ROWS] 之间，默认 [Constants.DEFAULT_TERMINAL_TRANSCRIPT_ROWS]。 */
    val transcriptRows = datastore.data.map {
        (it[transcriptRowsKey] ?: Constants.DEFAULT_TERMINAL_TRANSCRIPT_ROWS)
            .coerceIn(Constants.MIN_TERMINAL_TRANSCRIPT_ROWS, Constants.MAX_TERMINAL_TRANSCRIPT_ROWS)
    }

    suspend fun setTranscriptRows(rows: Int) {
        datastore.edit {
            it[transcriptRowsKey] = rows.coerceIn(
                Constants.MIN_TERMINAL_TRANSCRIPT_ROWS,
                Constants.MAX_TERMINAL_TRANSCRIPT_ROWS
            )
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

    /** 光标样式偏好，读取未知/非法字符串时回落到默认条状光标。 */
    val terminalCursorStyle: Flow<TerminalCursorStyle> = datastore.data.map { prefs ->
        try {
            TerminalCursorStyle.valueOf(
                prefs[cursorStyleKey] ?: return@map TerminalCursorStyle.BAR
            )
        } catch (e: IllegalArgumentException) {
            TerminalCursorStyle.BAR
        }
    }

    suspend fun setTerminalCursorStyle(style: TerminalCursorStyle) {
        datastore.edit {
            it[cursorStyleKey] = style.name
        }
    }

    /** 光标闪烁偏好，默认为开启。 */
    val cursorBlinking: Flow<Boolean> = datastore.data.map {
        it[cursorBlinkingKey] ?: true
    }

    suspend fun setCursorBlinking(enabled: Boolean) {
        datastore.edit {
            it[cursorBlinkingKey] = enabled
        }
    }

    /** 文本闪烁偏好，默认为开启。 */
    val textBlinking: Flow<Boolean> = datastore.data.map {
        it[textBlinkingKey] ?: true
    }

    suspend fun setTextBlinking(enabled: Boolean) {
        datastore.edit {
            it[textBlinkingKey] = enabled
        }
    }

    companion object {
        private val terminalFontSizeKey = intPreferencesKey("TerminalFontSize")
        private val themeModeKey = stringPreferencesKey("ThemeMode")
        private val cursorStyleKey = stringPreferencesKey("TerminalCursorStyle")
        private val cursorBlinkingKey = booleanPreferencesKey("CursorBlinking")
        private val textBlinkingKey = booleanPreferencesKey("TextBlinking")
        private val transcriptRowsKey = intPreferencesKey("TranscriptRows")
    }
}

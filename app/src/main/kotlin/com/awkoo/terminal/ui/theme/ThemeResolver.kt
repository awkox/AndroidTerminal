package com.awkoo.terminal.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable

/**
 * 将主题模式解析为实际明暗（深色为其 Boolean 语义）。
 *
 * SYSTEM 依赖 [isSystemInDarkTheme]，系统明暗切换会自动触发重组并更新该值。
 */
@Composable
fun resolvedIsDark(themeMode: ThemeMode): Boolean = when (themeMode) {
    ThemeMode.DARK -> true
    ThemeMode.LIGHT -> false
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
}

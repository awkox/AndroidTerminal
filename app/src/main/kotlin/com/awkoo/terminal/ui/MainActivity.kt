package com.awkoo.terminal.ui

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.awkoo.terminal.ui.compose.SessionListDrawer
import com.awkoo.terminal.ui.theme.resolvedIsDark
import com.awkoo.libterminal.color.TerminalColorScheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.dropWhile

/**
 * 终端模拟器主 Activity。
 *
 * 承载 Compose UI，管理会话列表抽屉和终端视图。
 * 当所有会话关闭时自动结束 Activity。
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
            val isDark = resolvedIsDark(themeMode)

            // edge-to-edge：状态栏/导航栏透明（背景透出主题色），图标明暗随 isDark 自动同步
            LaunchedEffect(isDark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(
                        lightScrim = Color.TRANSPARENT,
                        darkScrim = Color.TRANSPARENT,
                        detectDarkMode = { isDark }
                    ),
                    navigationBarStyle = SystemBarStyle.auto(
                        lightScrim = Color.TRANSPARENT,
                        darkScrim = Color.TRANSPARENT,
                        detectDarkMode = { isDark }
                    )
                )
            }

            MaterialTheme(
                colorScheme = if (isDark) darkColorScheme() else lightColorScheme()
            ) {
                val material = MaterialTheme.colorScheme
                // 终端前景/背景/光标色联动 Material 主题：
                // 前景/背景取 onBackground/background，光标取主题强调色 primary，
                // 跟随主题明暗切换自动换色（remember 避免每次重组重建 256 色板）
                val terminalColorScheme = remember(material) {
                    TerminalColorScheme.custom(
                        foreground = material.onBackground.toArgb(),
                        background = material.background.toArgb(),
                        cursor = material.primary.toArgb()
                    )
                }
                MainScreen(colorScheme = terminalColorScheme)
            }
        }

        viewModel.sessionListState
            .dropWhile { it.isEmpty() } // 一直丢弃初始的空列表状态，直到列表中出现会话后放行
            .filter { it.isEmpty() } // 之后如果列表再次变为空，则放行
            .onEach { finish() }
            .launchIn(lifecycleScope)
    }

    @Composable
    @Preview
    fun MainScreen(colorScheme: TerminalColorScheme = TerminalColorScheme.dark()) {
        SessionListDrawer(colorScheme = colorScheme)
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}

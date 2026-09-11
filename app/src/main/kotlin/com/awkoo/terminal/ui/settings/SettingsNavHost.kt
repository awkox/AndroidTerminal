package com.awkoo.terminal.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.alorma.compose.settings.ui.SettingsGroup
import com.alorma.compose.settings.ui.SettingsSegmented
import com.alorma.compose.settings.ui.SettingsSlider
import com.alorma.compose.settings.ui.SettingsSwitch
import com.awkoo.libterminal.engine.TerminalCursorStyle
import com.awkoo.terminal.Constants
import com.awkoo.terminal.ui.MainViewModel
import com.awkoo.terminal.ui.theme.ThemeMode
import kotlin.math.roundToInt

/**
 * 设置导航宿主。
 *
 * 承载 [SettingsDestination] 的导航图，跟随系统返回键 / 返回按钮正确回退，
 * 并预留嵌套子页扩展点：子页同样用 [composable] 注册，通过 [NavHostController]
 * 弹出或跳转。离开设置区时统一调用 [onExit]。
 *
 * @param onExit 返回按钮在返回栈已空（根页面）时触发，用于退出设置区
 * @param viewModel 用于读写偏好项的主界面 ViewModel
 */
@Composable
fun SettingsNavHost(onExit: () -> Unit, viewModel: MainViewModel) {
    val navController = rememberNavController()

    BackHandler {
        // 有可弹出的子页面时交给返回栈处理；根页面则退出设置区
        if (navController.previousBackStackEntry == null) {
            onExit()
        } else {
            navController.popBackStack()
        }
    }

    NavHost(
        navController = navController,
        startDestination = SettingsDestination.Root
    ) {
        composable<SettingsDestination.Root> {
            val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
            val fontSize by viewModel.terminalFontSize.collectAsStateWithLifecycle()
            val cursorStyle by viewModel.terminalCursorStyle.collectAsStateWithLifecycle()
            val cursorBlinking by viewModel.cursorBlinking.collectAsStateWithLifecycle()
            val textBlinking by viewModel.textBlinking.collectAsStateWithLifecycle()
            val transcriptRows by viewModel.transcriptRows.collectAsStateWithLifecycle()

            SettingsScreen(
                title = "设置",
                showBackButton = true,
                onBack = onExit
            ) {
                item {
                    SettingsGroup(title = { Text("外观") }) {
                        SettingsSegmented(
                            title = { Text("主题模式") },
                            items = ThemeMode.entries,
                            selectedItem = themeMode,
                            itemTitleMap = { mode ->
                                when (mode) {
                                    ThemeMode.DARK -> "深色"
                                    ThemeMode.LIGHT -> "浅色"
                                    ThemeMode.SYSTEM -> "跟随系统"
                                }
                            },
                            onItemSelected = { viewModel.setThemeMode(it) }
                        )
                    }
                }
                item {
                    SettingsGroup(title = { Text("终端") }) {
                        var fontSizeSlider by remember(fontSize) {
                            mutableFloatStateOf(fontSize.toFloat())
                        }
                        SettingsSlider(
                            title = { Text("字体大小") },
                            subtitle = { Text("${fontSizeSlider.roundToInt()}") },
                            value = fontSizeSlider,
                            onValueChange = { fontSizeSlider = it },
                            onValueChangeFinished = {
                                viewModel.setTerminalFontSize(fontSizeSlider.roundToInt())
                            },
                            valueRange = Constants.MIN_TERMINAL_FONT_SIZE.toFloat()..
                                Constants.MAX_TERMINAL_FONT_SIZE.toFloat(),
                            steps = Constants.MAX_TERMINAL_FONT_SIZE -
                                Constants.MIN_TERMINAL_FONT_SIZE - 1
                        )
                        SettingsSegmented(
                            title = { Text("光标样式") },
                            items = TerminalCursorStyle.entries,
                            selectedItem = cursorStyle,
                            itemTitleMap = { style ->
                                when (style) {
                                    TerminalCursorStyle.BLOCK -> "块状"
                                    TerminalCursorStyle.UNDERLINE -> "下划线"
                                    TerminalCursorStyle.BAR -> "竖线"
                                }
                            },
                            onItemSelected = { viewModel.setTerminalCursorStyle(it) }
                        )
                        SettingsSwitch(
                            title = { Text("光标闪烁") },
                            state = cursorBlinking,
                            onCheckedChange = { viewModel.setCursorBlinking(it) }
                        )
                        SettingsSwitch(
                            title = { Text("文本闪烁") },
                            state = textBlinking,
                            onCheckedChange = { viewModel.setTextBlinking(it) }
                        )
                        var rowsSlider by remember(transcriptRows) {
                            mutableFloatStateOf(transcriptRows.toFloat())
                        }
                        SettingsSlider(
                            title = { Text("回滚缓冲行数") },
                            subtitle = { Text("${rowsSlider.roundToInt()}") },
                            value = rowsSlider,
                            onValueChange = { rowsSlider = it },
                            onValueChangeFinished = {
                                viewModel.setTranscriptRows(rowsSlider.roundToInt())
                            },
                            valueRange = Constants.MIN_TERMINAL_TRANSCRIPT_ROWS.toFloat()..
                                Constants.MAX_TERMINAL_TRANSCRIPT_ROWS.toFloat()
                        )
                    }
                }
            }
        }
    }

    // 预留：如需在子页间跳转，可把 navController 传给对应 Composable
    // composable<SettingsDestination.Appearance> { ... }
}
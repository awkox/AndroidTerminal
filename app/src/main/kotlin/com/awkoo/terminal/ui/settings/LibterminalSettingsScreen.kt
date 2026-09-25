package com.awkoo.terminal.ui.settings

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alorma.compose.settings.ui.SettingsGroup
import com.alorma.compose.settings.ui.SettingsSegmented
import com.alorma.compose.settings.ui.SettingsSlider
import com.alorma.compose.settings.ui.SettingsSwitch
import com.awkoo.libterminal.engine.TerminalCursorStyle
import com.awkoo.terminal.Constants
import com.awkoo.terminal.R
import com.awkoo.terminal.ui.MainViewModel
import com.awkoo.terminal.ui.theme.ThemeMode
import kotlin.math.roundToInt

/**
 * libterminal 子页：终端模拟核心设置。
 *
 * 由设置根页跳转进入，承载原根页的全部设置项（外观 + 终端）。
 *
 * @param viewModel 用于读写偏好项的主界面 ViewModel
 * @param onBack 返回按钮回调（弹出本页返回根页）
 */
@Composable
fun LibterminalSettingsScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val fontSize by viewModel.terminalFontSize.collectAsStateWithLifecycle()
    val cursorStyle by viewModel.terminalCursorStyle.collectAsStateWithLifecycle()
    val cursorBlinking by viewModel.cursorBlinking.collectAsStateWithLifecycle()
    val textBlinking by viewModel.textBlinking.collectAsStateWithLifecycle()
    val transcriptRows by viewModel.transcriptRows.collectAsStateWithLifecycle()

    // itemTitleMap 为非 @Composable 回调，标签需在组合上下文提前解析
    val themeLabels = mapOf(
        ThemeMode.DARK to stringResource(R.string.settings_theme_dark),
        ThemeMode.LIGHT to stringResource(R.string.settings_theme_light),
        ThemeMode.SYSTEM to stringResource(R.string.settings_theme_system)
    )
    val cursorStyleLabels = mapOf(
        TerminalCursorStyle.BLOCK to stringResource(R.string.settings_cursor_style_block),
        TerminalCursorStyle.UNDERLINE to stringResource(R.string.settings_cursor_style_underline),
        TerminalCursorStyle.BAR to stringResource(R.string.settings_cursor_style_bar)
    )

    SettingsScreen(
        title = stringResource(R.string.settings_module_libterminal),
        showBackButton = true,
        onBack = onBack
    ) {
        item {
            SettingsGroup(title = { Text(stringResource(R.string.settings_group_appearance)) }) {
                SettingsSegmented(
                    title = { Text(stringResource(R.string.settings_theme_mode)) },
                    items = ThemeMode.entries,
                    selectedItem = themeMode,
                    itemTitleMap = { themeLabels.getValue(it) },
                    onItemSelected = { viewModel.setThemeMode(it) }
                )
            }
        }
        item {
            SettingsGroup(title = { Text(stringResource(R.string.settings_group_terminal)) }) {
                var fontSizeSlider by remember(fontSize) {
                    mutableFloatStateOf(fontSize.toFloat())
                }
                SettingsSlider(
                    title = { Text(stringResource(R.string.settings_font_size)) },
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
                    title = { Text(stringResource(R.string.settings_cursor_style)) },
                    items = TerminalCursorStyle.entries,
                    selectedItem = cursorStyle,
                    itemTitleMap = { cursorStyleLabels.getValue(it) },
                    onItemSelected = { viewModel.setTerminalCursorStyle(it) }
                )
                SettingsSwitch(
                    title = { Text(stringResource(R.string.settings_cursor_blinking)) },
                    state = cursorBlinking,
                    onCheckedChange = { viewModel.setCursorBlinking(it) }
                )
                SettingsSwitch(
                    title = { Text(stringResource(R.string.settings_text_blinking)) },
                    state = textBlinking,
                    onCheckedChange = { viewModel.setTextBlinking(it) }
                )
                var rowsSlider by remember(transcriptRows) {
                    mutableFloatStateOf(transcriptRows.toFloat())
                }
                SettingsSlider(
                    title = { Text(stringResource(R.string.settings_scrollback_lines)) },
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
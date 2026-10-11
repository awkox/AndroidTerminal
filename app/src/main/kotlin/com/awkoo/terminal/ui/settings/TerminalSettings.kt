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
import kotlin.math.roundToInt

/**
 * 终端分组：光标、闪烁与回滚缓冲。
 *
 * 作为设置单页的一个分区渲染，卡片容器为 [SettingsCard]。
 *
 * @param viewModel 用于读写偏好项的主界面 ViewModel
 */
@Composable
fun TerminalSettingsSection(viewModel: MainViewModel) {
    val cursorStyle by viewModel.terminalCursorStyle.collectAsStateWithLifecycle()
    val cursorBlinking by viewModel.cursorBlinking.collectAsStateWithLifecycle()
    val textBlinking by viewModel.textBlinking.collectAsStateWithLifecycle()
    val transcriptRows by viewModel.transcriptRows.collectAsStateWithLifecycle()

    // itemTitleMap 为非 @Composable 回调，标签需在组合上下文提前解析
    val cursorStyleLabels = mapOf(
        TerminalCursorStyle.BLOCK to stringResource(R.string.settings_cursor_style_block),
        TerminalCursorStyle.UNDERLINE to stringResource(R.string.settings_cursor_style_underline),
        TerminalCursorStyle.BAR to stringResource(R.string.settings_cursor_style_bar)
    )

    SettingsGroup(title = { Text(stringResource(R.string.settings_group_terminal)) }) {
        SettingsCard {
            SettingsSegmented(
                title = { Text(stringResource(R.string.settings_cursor_style)) },
                items = TerminalCursorStyle.entries,
                selectedItem = cursorStyle,
                itemTitleMap = { cursorStyleLabels.getValue(it) },
                onItemSelected = { viewModel.setTerminalCursorStyle(it) },
                colors = CardTileColors()
            )
            SettingsSwitch(
                title = { Text(stringResource(R.string.settings_cursor_blinking)) },
                state = cursorBlinking,
                onCheckedChange = { viewModel.setCursorBlinking(it) },
                colors = CardTileColors()
            )
            SettingsSwitch(
                title = { Text(stringResource(R.string.settings_text_blinking)) },
                state = textBlinking,
                onCheckedChange = { viewModel.setTextBlinking(it) },
                colors = CardTileColors()
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
                    Constants.MAX_TERMINAL_TRANSCRIPT_ROWS.toFloat(),
                colors = CardTileColors()
            )
        }
    }
}

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
import com.awkoo.terminal.Constants
import com.awkoo.terminal.R
import com.awkoo.terminal.ui.MainViewModel
import com.awkoo.terminal.ui.theme.ThemeMode
import kotlin.math.roundToInt

/**
 * 外观分组：主题与字体大小。
 *
 * 作为设置单页的一个分区渲染，卡片容器为 [SettingsCard]。
 *
 * @param viewModel 用于读写偏好项的主界面 ViewModel
 */
@Composable
fun AppearanceSettingsSection(viewModel: MainViewModel) {
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val fontSize by viewModel.terminalFontSize.collectAsStateWithLifecycle()

    // itemTitleMap 为非 @Composable 回调，标签需在组合上下文提前解析
    val themeLabels = mapOf(
        ThemeMode.DARK to stringResource(R.string.settings_theme_dark),
        ThemeMode.LIGHT to stringResource(R.string.settings_theme_light),
        ThemeMode.SYSTEM to stringResource(R.string.settings_theme_system)
    )

    SettingsGroup(title = { Text(stringResource(R.string.settings_group_appearance)) }) {
        SettingsCard {
            SettingsSegmented(
                title = { Text(stringResource(R.string.settings_theme_mode)) },
                items = ThemeMode.entries,
                selectedItem = themeMode,
                itemTitleMap = { themeLabels.getValue(it) },
                onItemSelected = { viewModel.setThemeMode(it) },
                colors = CardTileColors()
            )
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
                    Constants.MIN_TERMINAL_FONT_SIZE - 1,
                colors = CardTileColors()
            )
        }
    }
}

package com.awkoo.terminal.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.awkoo.terminal.R
import com.awkoo.terminal.ui.MainViewModel

/**
 * 设置区入口：单页呈现全部设置分组，每组为一个 [SettingsCard]。
 *
 * 不再按模块或主题分页，避免为十几个设置项多套一层导航；
 * 系统返回键与顶部返回按钮统一通过 [onExit] 关闭设置覆盖层。
 *
 * @param onExit 关闭设置区回调（返回栈仅此一页，返回即退出）
 * @param viewModel 用于读写偏好项的主界面 ViewModel
 */
@Composable
fun SettingsView(onExit: () -> Unit, viewModel: MainViewModel) {
    BackHandler { onExit() }

    SettingsScreen(
        title = stringResource(R.string.settings_title),
        showBackButton = true,
        onBack = onExit
    ) {
        item { AppearanceSettingsSection(viewModel) }
        item { TerminalSettingsSection(viewModel) }
        item { LocalSessionSettingsSection(viewModel) }
    }
}

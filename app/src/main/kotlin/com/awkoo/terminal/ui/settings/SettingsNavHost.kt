package com.awkoo.terminal.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.alorma.compose.settings.ui.SettingsGroup
import com.alorma.compose.settings.ui.SettingsMenuLink
import com.awkoo.terminal.R
import com.awkoo.terminal.ui.MainViewModel

/**
 * 设置导航宿主。
 *
 * 根页为模块菜单，跳转 libterminal / libterminal-pty 子页；跟随系统返回键 /
 * 返回按钮正确回退，离开设置区时统一调用 [onExit]。
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
            SettingsScreen(
                title = stringResource(R.string.settings_title),
                showBackButton = true,
                onBack = onExit
            ) {
                item {
                    SettingsGroup(title = { Text(stringResource(R.string.settings_group_modules)) }) {
                        SettingsMenuLink(
                            title = { Text(stringResource(R.string.settings_module_libterminal)) },
                            subtitle = {
                                Text(stringResource(R.string.settings_module_libterminal_subtitle))
                            },
                            action = {
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                            },
                            onClick = { navController.navigate(SettingsDestination.Libterminal) }
                        )
                        SettingsMenuLink(
                            title = { Text(stringResource(R.string.settings_module_pty)) },
                            subtitle = {
                                Text(stringResource(R.string.settings_module_pty_subtitle))
                            },
                            action = {
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                            },
                            onClick = { navController.navigate(SettingsDestination.Pty) }
                        )
                    }
                }
            }
        }

        composable<SettingsDestination.Libterminal> {
            LibterminalSettingsScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() }
            )
        }

        composable<SettingsDestination.Pty> {
            PtySettingsScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
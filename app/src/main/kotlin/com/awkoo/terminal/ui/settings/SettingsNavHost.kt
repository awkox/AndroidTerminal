package com.awkoo.terminal.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

/**
 * 设置导航宿主。
 *
 * 承载 [SettingsDestination] 的导航图，跟随系统返回键 / 返回按钮正确回退，
 * 并预留嵌套子页扩展点：子页同样用 [composable] 注册，通过 [NavHostController]
 * 弹出或跳转。离开设置区时统一调用 [onExit]。
 *
 * @param onExit 返回按钮在返回栈已空（根页面）时触发，用于退出设置区
 */
@Composable
fun SettingsNavHost(onExit: () -> Unit) {
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
                title = "Settings",
                showBackButton = true,
                onBack = onExit
            ) {
                // 未来：在此填充 preference tile（compose-settings 的 SettingsGroup 等）
            }
        }
    }

    // 预留：如需在子页间跳转，可把 navController 传给对应 Composable
    // composable<SettingsDestination.Appearance> { ... }

    // 若需要带参数的子页（如按会话 id 打开），可改为字符串路由：
    // composable(
    //     route = "settings/{id}",
    //     arguments = listOf(navArgument("id") { type = NavType.IntType })
    // ) { ... }
}

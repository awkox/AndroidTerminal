package com.awkoo.terminal.ui.settings

import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.serialization.Serializable

/**
 * 设置页目的地。
 *
 * Navigation Compose 类型安全路由：每个目的地为 `@Serializable` object，作为
 * [androidx.navigation.NavGraphBuilder.composable] 的路由实参，编译期类型安全，
 * 并支持 [rememberSaveable] 保存返回栈。Navigation 依赖 `@Serializable` 推导路由，
 * 缺少标注会导致运行期无法解析目的地。
 *
 * 新增子页时，在此添加一个 `@Serializable object` 即可，并在 [SettingsNavHost] 中
 * 注册对应的 [SettingsScreen]。
 */
object SettingsDestination {
    /** 设置根页面（模块菜单）。 */
    @Serializable
    object Root

    /** libterminal：终端模拟核心设置。 */
    @Serializable
    object Libterminal

    /** libterminal-pty：本地进程启动设置。 */
    @Serializable
    object Pty
}

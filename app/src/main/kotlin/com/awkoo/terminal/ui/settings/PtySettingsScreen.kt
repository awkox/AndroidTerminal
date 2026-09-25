package com.awkoo.terminal.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alorma.compose.settings.ui.SettingsGroup
import com.awkoo.terminal.R
import com.awkoo.terminal.core.PtyConfig
import com.awkoo.terminal.core.PtyEnvVar
import com.awkoo.terminal.ui.MainViewModel

/**
 * libterminal-pty 子页：本地进程启动设置。
 *
 * 配置新建本地会话的启动命令、参数、自定义环境变量与 stdin 内容，
 * 编辑即写回 DataStore（与本仓库其它设置项一致的即时持久化）。
 *
 * @param viewModel 用于读写 [PtyConfig] 的主界面 ViewModel
 * @param onBack 返回按钮回调（弹出本页返回根页）
 */
@Composable
fun PtySettingsScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val config by viewModel.ptyConfig.collectAsStateWithLifecycle()

    // 本地草稿：仅以进入页面时的持久化值初始化，编辑时即时写穿到 DataStore，
    // 避免把共学状态放在异步 Flow 上导致输入延迟/丢字。
    var command by remember { mutableStateOf(config.command) }
    var args by remember { mutableStateOf(config.args) }
    var environment by remember { mutableStateOf(config.environment) }
    var stdin by remember { mutableStateOf(config.stdin) }

    /** 以 [newCommand]/[newArgs]/[newEnvironment]/[newStdin] 构建完整配置并写回。 */
    fun sync(
        newCommand: String = command,
        newArgs: List<String> = args,
        newEnvironment: List<PtyEnvVar> = environment,
        newStdin: String = stdin
    ) {
        viewModel.setPtyConfig(PtyConfig(newCommand, newArgs, newEnvironment, newStdin))
    }

    fun updateEnv(index: Int, key: String, value: String) {
        environment = environment.mapIndexed { i, env ->
            if (i == index) env.copy(key = key, value = value) else env
        }
        sync(newEnvironment = environment)
    }

    fun removeEnv(index: Int) {
        environment = environment.filterIndexed { i, _ -> i != index }
        sync(newEnvironment = environment)
    }

    fun addEnv() {
        environment = environment + PtyEnvVar()
        sync(newEnvironment = environment)
    }

    fun updateArg(index: Int, value: String) {
        args = args.mapIndexed { i, arg -> if (i == index) value else arg }
        sync(newArgs = args)
    }

    fun removeArg(index: Int) {
        args = args.filterIndexed { i, _ -> i != index }
        sync(newArgs = args)
    }

    fun addArg() {
        args = args + ""
        sync(newArgs = args)
    }

    SettingsScreen(
        title = stringResource(R.string.settings_module_pty),
        showBackButton = true,
        onBack = onBack
    ) {
        item {
            SettingsGroup(title = { Text(stringResource(R.string.settings_group_launch)) }) {
                OutlinedTextField(
                    value = command,
                    onValueChange = {
                        command = it
                        sync(newCommand = it)
                    },
                    label = { Text(stringResource(R.string.settings_command)) },
                    supportingText = {
                        Text(stringResource(R.string.settings_command_hint))
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                for ((index, arg) in args.withIndex()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = arg,
                            onValueChange = { updated -> updateArg(index, updated) },
                            label = { Text(stringResource(R.string.settings_args)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { removeArg(index) }) {
                            Icon(
                                Icons.Default.Delete,
                                stringResource(R.string.settings_remove_arg)
                            )
                        }
                    }
                }
                OutlinedButton(
                    onClick = { addArg() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Add, null)
                    Text(stringResource(R.string.settings_add_arg))
                }
            }
        }
        item {
            SettingsGroup(title = { Text(stringResource(R.string.settings_group_env)) }) {
                for ((index, env) in environment.withIndex()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = env.key,
                            onValueChange = { key -> updateEnv(index, key, env.value) },
                            label = { Text(stringResource(R.string.settings_env_key)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = env.value,
                            onValueChange = { value -> updateEnv(index, env.key, value) },
                            label = { Text(stringResource(R.string.settings_env_value)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { removeEnv(index) }) {
                            Icon(
                                Icons.Default.Delete,
                                stringResource(R.string.settings_remove_env)
                            )
                        }
                    }
                }
                OutlinedButton(
                    onClick = { addEnv() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Add, null)
                    Text(stringResource(R.string.settings_add_env))
                }
            }
        }
        item {
            SettingsGroup(title = { Text(stringResource(R.string.settings_stdin)) }) {
                OutlinedTextField(
                    value = stdin,
                    onValueChange = {
                        stdin = it
                        sync(newStdin = it)
                    },
                    label = { Text(stringResource(R.string.settings_stdin_content)) },
                    supportingText = {
                        Text(stringResource(R.string.settings_stdin_hint))
                    },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
package com.awkoo.terminal.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alorma.compose.settings.ui.SettingsGroup
import com.alorma.compose.settings.ui.SettingsMenuLink
import com.awkoo.terminal.R
import com.awkoo.terminal.core.PtyConfig
import com.awkoo.terminal.core.PtyEnvVar
import com.awkoo.terminal.ui.MainViewModel

/**
 * 本地进程启动分组：启动、环境变量与 stdin。
 *
 * 三组共用一份本地草稿，因此作为设置单页的一个分区整体渲染（不拆成多个
 * LazyColumn item，避免草稿状态跨 item 提升到页面层）。
 * 草稿仅以进入页面时的持久化值初始化，编辑时即时写穿到 DataStore，
 * 避免把共享状态放在异步 Flow 上导致输入延迟/丢字。
 * 输入行与 compose-settings tile 共用 16dp 内容边距，参数带序号、
 * 行尾删除、末尾为贴合 tile 的添加行。
 *
 * @param viewModel 用于读写 [PtyConfig] 的主界面 ViewModel
 */
@Composable
fun LocalSessionSettingsSection(viewModel: MainViewModel) {
    val config by viewModel.ptyConfig.collectAsStateWithLifecycle()

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

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        SettingsGroup(title = { Text(stringResource(R.string.settings_group_launch)) }) {
            SettingsCard {
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                )
                for ((index, arg) in args.withIndex()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                    ) {
                        Text(
                            text = "${index + 1}",
                            modifier = Modifier.width(24.dp),
                            textAlign = TextAlign.End,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedTextField(
                            value = arg,
                            onValueChange = { updated -> updateArg(index, updated) },
                            label = { Text(stringResource(R.string.settings_args)) },
                            singleLine = true,
                            trailingIcon = {
                                IconButton(onClick = { removeArg(index) }) {
                                    Icon(
                                        Icons.Default.Delete,
                                        stringResource(R.string.settings_remove_arg)
                                    )
                                }
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                SettingsMenuLink(
                    title = { Text(stringResource(R.string.settings_add_arg)) },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    onClick = { addArg() },
                    colors = CardTileColors()
                )
            }
        }
        SettingsGroup(title = { Text(stringResource(R.string.settings_group_env)) }) {
            SettingsCard {
                for ((index, env) in environment.withIndex()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
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
                            trailingIcon = {
                                IconButton(onClick = { removeEnv(index) }) {
                                    Icon(
                                        Icons.Default.Delete,
                                        stringResource(R.string.settings_remove_env)
                                    )
                                }
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                SettingsMenuLink(
                    title = { Text(stringResource(R.string.settings_add_env)) },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    onClick = { addEnv() },
                    colors = CardTileColors()
                )
            }
        }
        SettingsGroup(title = { Text(stringResource(R.string.settings_stdin)) }) {
            SettingsCard {
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                )
            }
        }
    }
}

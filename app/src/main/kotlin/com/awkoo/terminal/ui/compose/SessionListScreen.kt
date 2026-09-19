package com.awkoo.terminal.ui.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.awkoo.libterminal.engine.TerminalSession
import com.awkoo.terminal.R

/**
 * 会话抽屉：会话列表 + 新建按钮。
 *
 * "New Session" 与本机本地 shell；并排的 "SSH" 弹出连接信息对话框，
 * 暂只处理密码认证（不处理私钥）。
 */
@Composable
fun SessionListScreen(
    sessionList: List<TerminalSession>,
    currentSession: TerminalSession?,
    onSessionSelected: (sessionId: Int) -> Unit,
    onNewSession: () -> Unit,
    onNewSshSession: (host: String, port: Int, user: String, password: String) -> Unit
) {
    val listState = rememberLazyListState()
    var showSshDialog by remember { mutableStateOf(false) }

    ModalDrawerSheet(
        modifier = Modifier
            .statusBarsPadding()
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .padding(vertical = 10.dp)
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(
                items = sessionList,
                key = { it.id },
            ) { session ->
                NavigationDrawerItem(
                    label = {
                        Column {
                            val title by session.titleState.collectAsStateWithLifecycle()
                            val sessionName by session.sessionName.collectAsStateWithLifecycle()
                            val currentTitle = title ?: sessionName
                            if (!currentTitle.isNullOrEmpty()) {
                                Text("[${session.id}] $currentTitle")
                            }
                        }
                    },
                    selected = session == currentSession,
                    onClick = { onSessionSelected(session.id) },
                    shape = MaterialTheme.shapes.medium
                )
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onNewSession,
                        modifier = Modifier.weight(1f),
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Icon(Icons.Default.Add, null)
                        Text(stringResource(R.string.new_session))
                    }
                    OutlinedButton(
                        onClick = { showSshDialog = true },
                        modifier = Modifier.weight(1f),
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Icon(Icons.Default.Lock, null)
                        Text(stringResource(R.string.new_ssh_session))
                    }
                }
            }
        }
    }

    if (showSshDialog) {
        SshConnectDialog(
            onDismiss = { showSshDialog = false },
            onConnect = { host, port, user, password ->
                showSshDialog = false
                onNewSshSession(host, port, user, password)
            }
        )
    }
}

/** SSH 连接信息对话框：主机/端口/用户名/密码，仅密码认证。 */
@Composable
private fun SshConnectDialog(
    onDismiss: () -> Unit,
    onConnect: (host: String, port: Int, user: String, password: String) -> Unit
) {
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("22") }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    val hostError = if (host.isBlank()) stringResource(R.string.ssh_host_required) else null
    val userError = if (user.isBlank()) stringResource(R.string.ssh_user_required) else null
    val portValue = port.toIntOrNull()
    val portError = if (portValue == null || portValue !in 1..65535) {
        stringResource(R.string.ssh_port_required)
    } else {
        null
    }
    val canSubmit = hostError == null && userError == null && portError == null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ssh_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text(stringResource(R.string.ssh_host)) },
                    isError = hostError != null,
                    supportingText = hostError?.let { { Text(it) } },
                    singleLine = true
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it },
                    label = { Text(stringResource(R.string.ssh_port)) },
                    isError = portError != null,
                    supportingText = portError?.let { { Text(it) } },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                OutlinedTextField(
                    value = user,
                    onValueChange = { user = it },
                    label = { Text(stringResource(R.string.ssh_user)) },
                    isError = userError != null,
                    supportingText = userError?.let { { Text(it) } },
                    singleLine = true
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.ssh_password)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSubmit,
                onClick = {
                    onConnect(host.trim(), portValue!!, user.trim(), password)
                }
            ) {
                Text(stringResource(R.string.ssh_connect))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
package com.awkoo.terminal.ui.compose

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.awkoo.libterminal.engine.TerminalSession
import com.awkoo.libterminal.ssh.SshFactory
import com.awkoo.terminal.R
import com.awkoo.terminal.core.HostKeyStore
import com.awkoo.terminal.core.SshKeyImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 会话抽屉：会话列表 + 新建按钮。
 *
 * "New Session" 与本机本地 shell；并排的 "SSH" 弹出连接信息对话框，
 * 支持密码与私钥（可带 passphrase）两种认证方式。
 */
@Composable
fun SessionListScreen(
    sessionList: List<TerminalSession>,
    currentSession: TerminalSession?,
    onSessionSelected: (sessionId: Int) -> Unit,
    onNewSession: () -> Unit,
    onNewSshSession: (
        host: String,
        port: Int,
        user: String,
        password: String?,
        keyPath: String?,
        keyPassphrase: String?,
        hostKeyFingerprint: String?
    ) -> Unit
) {
    val listState = rememberLazyListState()
    var showSshDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val hostKeyStore = remember { HostKeyStore(context) }

    /** 无已知指纹、等待用户确认服务器指纹的连接请求。 */
    var pendingHostKey by remember { mutableStateOf<PendingHostKeyConnect?>(null) }
    var hostKeyProbeError by remember { mutableStateOf<String?>(null) }

    // 连接前策略：已知指纹直接带证连接；未知则先探测、弹确认框。
    fun verifyAndConnect(
        host: String,
        port: Int,
        user: String,
        password: String?,
        keyPath: String?,
        keyPassphrase: String?
    ) {
        scope.launch {
            val known = hostKeyStore.get(host.trim(), port)
            if (known != null) {
                onNewSshSession(host, port, user, password, keyPath, keyPassphrase, known)
                return@launch
            }
            val probe = withContext(Dispatchers.IO) {
                SshFactory.getServerFingerprint(host.trim(), port, 10000)
            }
            if (probe.startsWith("ERROR: ")) {
                hostKeyProbeError = probe.removePrefix("ERROR: ")
            } else {
                pendingHostKey = PendingHostKeyConnect(
                    host, port, user, password, keyPath, keyPassphrase, probe
                )
            }
        }
    }

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
            onConnect = { host, port, user, password, keyPath, keyPassphrase ->
                showSshDialog = false
                verifyAndConnect(host, port, user, password, keyPath, keyPassphrase)
            }
        )
    }

    // 首连确认对话框：向用户展示服务器指纹，确认后存储并带证连接。
    pendingHostKey?.let { pending ->
        AlertDialog(
            onDismissRequest = { pendingHostKey = null },
            title = { Text(stringResource(R.string.ssh_confirm_host_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.ssh_confirm_host_body,
                        "${pending.host}:${pending.port}",
                        pending.fingerprint
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    hostKeyStore.put(pending.host.trim(), pending.port, pending.fingerprint)
                    onNewSshSession(
                        pending.host, pending.port, pending.user, pending.password,
                        pending.keyPath, pending.keyPassphrase, pending.fingerprint
                    )
                    pendingHostKey = null
                }) {
                    Text(stringResource(R.string.ssh_confirm_host_accept))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingHostKey = null }) {
                    Text(stringResource(R.string.ssh_confirm_host_reject))
                }
            }
        )
    }

    hostKeyProbeError?.let { message ->
        AlertDialog(
            onDismissRequest = { hostKeyProbeError = null },
            title = { Text(stringResource(R.string.ssh_host_probe_failed_title)) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { hostKeyProbeError = null }) {
                    Text(stringResource(R.string.ok))
                }
            }
        )
    }
}

private enum class SshAuthMode { Password, PrivateKey }

/** 首连指纹待确认的连接请求快照。 */
private data class PendingHostKeyConnect(
    val host: String,
    val port: Int,
    val user: String,
    val password: String?,
    val keyPath: String?,
    val keyPassphrase: String?,
    val fingerprint: String
)

/** SSH 连接信息对话框：主机/端口/用户名 + 认证方式（密码或私钥）。 */
@Composable
private fun SshConnectDialog(
    onDismiss: () -> Unit,
    onConnect: (
        host: String,
        port: Int,
        user: String,
        password: String?,
        keyPath: String?,
        keyPassphrase: String?
    ) -> Unit
) {
    val context = LocalContext.current

    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("22") }
    var user by remember { mutableStateOf("") }

    var authMode by remember { mutableStateOf(SshAuthMode.Password) }
    var password by remember { mutableStateOf("") }
    var keyPath by remember { mutableStateOf<String?>(null) }
    var keyPassphrase by remember { mutableStateOf("") }
    var keyError by remember { mutableStateOf<String?>(null) }
    var keySource by remember { mutableStateOf(KeySource.File) }
    var keyPaste by remember { mutableStateOf("") }

    val hostError = if (host.isBlank()) stringResource(R.string.ssh_host_required) else null
    val userError = if (user.isBlank()) stringResource(R.string.ssh_user_required) else null
    val portValue = port.toIntOrNull()
    val portError = if (portValue == null || portValue !in 1..65535) {
        stringResource(R.string.ssh_port_required)
    } else {
        null
    }

    val keyPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val result = SshKeyImporter.import(context, uri)
        keyPath = result.path
        keyError = if (result.path == null) {
            result.error ?: context.getString(R.string.ssh_key_import_failed)
        } else {
            null
        }
    }

    // 私钥选择/口令变化后立即校验格式与 passphrase，连接前给出就地反馈。
    LaunchedEffect(keyPath, keyPassphrase) {
        val path = keyPath
        if (path == null) {
            keyError = null
            return@LaunchedEffect
        }
        keyError = withContext(Dispatchers.IO) {
            SshFactory.checkPrivateKey(path, keyPassphrase.ifBlank { null })
        }
    }

    val keyReady = keyPath != null && keyError == null
    val canSubmit = hostError == null && userError == null && portError == null &&
        (authMode == SshAuthMode.Password || keyReady)

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

                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = authMode == SshAuthMode.Password,
                        onClick = { authMode = SshAuthMode.Password },
                        shape = SegmentedButtonDefaults.itemShape(0, 2)
                    ) {
                        Text(stringResource(R.string.ssh_auth_password))
                    }
                    SegmentedButton(
                        selected = authMode == SshAuthMode.PrivateKey,
                        onClick = { authMode = SshAuthMode.PrivateKey },
                        shape = SegmentedButtonDefaults.itemShape(1, 2)
                    ) {
                        Text(stringResource(R.string.ssh_auth_key))
                    }
                }

                if (authMode == SshAuthMode.Password) {
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(stringResource(R.string.ssh_password)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                    )
                } else {
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = keySource == KeySource.File,
                            onClick = { keySource = KeySource.File },
                            shape = SegmentedButtonDefaults.itemShape(0, 2)
                        ) {
                            Text(stringResource(R.string.ssh_key_source_file))
                        }
                        SegmentedButton(
                            selected = keySource == KeySource.Paste,
                            onClick = { keySource = KeySource.Paste },
                            shape = SegmentedButtonDefaults.itemShape(1, 2)
                        ) {
                            Text(stringResource(R.string.ssh_key_source_paste))
                        }
                    }
                    if (keySource == KeySource.File) {
                        OutlinedButton(
                            onClick = {
                                keyPicker.launch(arrayOf("*/*"))
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Lock, null)
                            Text(
                                if (keyPath != null) {
                                    stringResource(R.string.ssh_key_replaced)
                                } else {
                                    stringResource(R.string.ssh_select_key)
                                }
                            )
                        }
                    } else {
                        OutlinedTextField(
                            value = keyPaste,
                            onValueChange = { keyPaste = it },
                            label = { Text(stringResource(R.string.ssh_paste_key_hint)) },
                            supportingText = { Text(stringResource(R.string.ssh_paste_key_apply)) },
                            minLines = 5
                        )
                        OutlinedButton(
                            onClick = {
                                SshKeyImporter.delete(keyPath)
                                val result = SshKeyImporter.importText(context, keyPaste)
                                keyPath = result.path
                                keyError = if (result.path == null) {
                                    result.error
                                        ?: context.getString(R.string.ssh_key_paste_failed)
                                } else {
                                    null
                                }
                            },
                            enabled = keyPaste.isNotBlank(),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                if (keyPath != null) {
                                    stringResource(R.string.ssh_key_replaced)
                                } else {
                                    stringResource(R.string.ssh_paste_key_use)
                                }
                            )
                        }
                    }
                    keyError?.let { error ->
                        Text(
                            error,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    OutlinedTextField(
                        value = keyPassphrase,
                        onValueChange = { keyPassphrase = it },
                        label = { Text(stringResource(R.string.ssh_key_passphrase)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSubmit,
                onClick = {
                    val isKey = authMode == SshAuthMode.PrivateKey
                    onConnect(
                        host.trim(),
                        portValue!!,
                        user.trim(),
                        if (isKey) null else password,
                        if (isKey) keyPath else null,
                        if (isKey) keyPassphrase.ifBlank { null } else null
                    )
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

/** SSH 私钥来源：文件选择（SAF）或直接粘贴文本。 */
private enum class KeySource { File, Paste }
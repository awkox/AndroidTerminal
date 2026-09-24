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
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.awkoo.libterminal.engine.TerminalSession
import com.awkoo.libterminal.ssh.SshInspector
import com.awkoo.libterminal.ssh.SshPreConnector
import com.awkoo.terminal.R
import com.awkoo.terminal.core.HostKeyDecision
import com.awkoo.terminal.core.HostKeyStore
import com.awkoo.terminal.core.LastSshConnection
import com.awkoo.terminal.core.SshAuthMode
import com.awkoo.terminal.core.SshKeyImporter
import com.awkoo.terminal.core.SshTrustPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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
        hostKeyFingerprint: String?,
        authMode: SshAuthMode,
        rememberPassword: Boolean,
        rememberKeyPassphrase: Boolean
    ) -> Unit,
    sshPreConnector: SshPreConnector = SshInspector,
    lastConnection: LastSshConnection = LastSshConnection()
) {
    val listState = rememberLazyListState()
    var showSshDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val hostKeyStore = remember { HostKeyStore(context) }

    // 把不稳定的 TerminalSession（跨模块、非 Compose 编译）映射为 @Immutable 稳定壳，
    // 让列表项可跳过重组；当选会话变化时随 currentSessionId 重建选中标记。
    val currentSessionId = currentSession?.id
    val rows = remember(sessionList, currentSessionId) {
        sessionList.map { session ->
            SessionRow(
                id = session.id,
                selected = session.id == currentSessionId,
                title = session.titleState,
                name = session.sessionName,
                running = session.isRunning
            )
        }
    }

    /** 主机指纹确认类的单槽状态：首连/变更/探测失败统一走这一个对话框槽。 */
    var pendingPrompt by remember { mutableStateOf<HostKeyPrompt?>(null) }

    // 连接前统一先探测服务器指纹，交给纯策略规则 [SshTrustPolicy.decide] 决策：
    // - 无记录 → 弹首连确认框；记录一致 → 带证直连；记录不符 → 弹变更确认框；
    // - 探测失败但有记录 → 回退带已存指纹直连（native 严格比对兜底）；
    // - 探测失败且无记录 → 直接报错。
    fun verifyAndConnect(
        host: String,
        port: Int,
        user: String,
        password: String?,
        keyPath: String?,
        keyPassphrase: String?,
        authMode: SshAuthMode,
        rememberPassword: Boolean,
        rememberKeyPassphrase: Boolean
    ) {
        scope.launch {
            val trimmedHost = host.trim()
            val probe = withContext(Dispatchers.IO) {
                sshPreConnector.serverFingerprint(trimmedHost, port, 10000)
            }
            val known = hostKeyStore.get(trimmedHost, port)
            when (val decision = SshTrustPolicy.decide(known, probe)) {
                is HostKeyDecision.Connect -> onNewSshSession(
                    trimmedHost, port, user, password, keyPath, keyPassphrase,
                    decision.fingerprint, authMode, rememberPassword, rememberKeyPassphrase
                )
                is HostKeyDecision.AskFirst -> pendingPrompt = HostKeyPrompt.FirstConnect(
                    trimmedHost, port, user, password, keyPath, keyPassphrase,
                    decision.fingerprint, authMode, rememberPassword, rememberKeyPassphrase
                )
                is HostKeyDecision.AskChange -> pendingPrompt = HostKeyPrompt.KeyChanged(
                    trimmedHost, port, user, password, keyPath, keyPassphrase,
                    decision.storedFingerprint, decision.currentFingerprint,
                    authMode, rememberPassword, rememberKeyPassphrase
                )
                is HostKeyDecision.ProbeFailed -> pendingPrompt = HostKeyPrompt.ProbeFailed(
                    trimmedHost, port, user, password, keyPath, keyPassphrase,
                    decision.message, authMode, rememberPassword, rememberKeyPassphrase
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
                items = rows,
                key = { it.id },
                contentType = { "session" }
            ) { row ->
                NavigationDrawerItem(
                    label = {
                        Column {
                            val title by row.title.collectAsStateWithLifecycle()
                            val sessionName by row.name.collectAsStateWithLifecycle()
                            val running by row.running.collectAsStateWithLifecycle()
                            val currentTitle = title ?: sessionName
                            if (!currentTitle.isNullOrEmpty()) {
                                Text(
                                    "[${row.id}] $currentTitle",
                                    color = if (running) {
                                        Color.Unspecified
                                    } else {
                                        MaterialTheme.colorScheme.error
                                    },
                                    textDecoration = if (running) {
                                        TextDecoration.None
                                    } else {
                                        TextDecoration.LineThrough
                                    }
                                )
                            }
                        }
                    },
                    selected = row.selected,
                    onClick = { onSessionSelected(row.id) },
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
            sshPreConnector = sshPreConnector,
            initial = lastConnection,
            onDismiss = { showSshDialog = false },
            onConnect = { host, port, user, password, keyPath, keyPassphrase,
                          authMode, rememberPassword, rememberKeyPassphrase ->
                showSshDialog = false
                verifyAndConnect(
                    host, port, user, password, keyPath, keyPassphrase,
                    authMode, rememberPassword, rememberKeyPassphrase
                )
            }
        )
    }

    // 主机指纹确认单槽对话框：按留住的状态分支呈现首连/变更/探测失败三种形态。
    (pendingPrompt as? HostKeyPrompt)?.let { pending ->
        when (pending) {
            is HostKeyPrompt.FirstConnect -> AlertDialog(
                onDismissRequest = { pendingPrompt = null },
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
                        hostKeyStore.put(pending.host, pending.port, pending.fingerprint)
                        onNewSshSession(
                            pending.host, pending.port, pending.user, pending.password,
                            pending.keyPath, pending.keyPassphrase, pending.fingerprint,
                            pending.authMode, pending.rememberPassword,
                            pending.rememberKeyPassphrase
                        )
                        pendingPrompt = null
                    }) {
                        Text(stringResource(R.string.ssh_confirm_host_accept))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingPrompt = null }) {
                        Text(stringResource(R.string.ssh_confirm_host_reject))
                    }
                }
            )

            is HostKeyPrompt.KeyChanged -> AlertDialog(
                onDismissRequest = { pendingPrompt = null },
                title = { Text(stringResource(R.string.ssh_host_key_changed_title)) },
                text = {
                    Text(
                        stringResource(
                            R.string.ssh_host_key_changed_body,
                            "${pending.host}:${pending.port}",
                            pending.storedFingerprint,
                            pending.currentFingerprint
                        )
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        hostKeyStore.put(
                            pending.host, pending.port, pending.currentFingerprint
                        )
                        onNewSshSession(
                            pending.host, pending.port, pending.user, pending.password,
                            pending.keyPath, pending.keyPassphrase, pending.currentFingerprint,
                            pending.authMode, pending.rememberPassword,
                            pending.rememberKeyPassphrase
                        )
                        pendingPrompt = null
                    }) {
                        Text(stringResource(R.string.ssh_host_key_changed_accept))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingPrompt = null }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            )

            is HostKeyPrompt.ProbeFailed -> AlertDialog(
                onDismissRequest = { pendingPrompt = null },
                title = { Text(stringResource(R.string.ssh_host_probe_failed_title)) },
                text = { Text(pending.message) },
                confirmButton = {
                    TextButton(onClick = { pendingPrompt = null }) {
                        Text(stringResource(R.string.ok))
                    }
                }
            )
        }
    }
}

/** 主机指纹确认类连接请求快照：保留待连接参数，等待用户决定或直接报错。 */
private sealed interface HostKeyPrompt {
    val host: String
    val port: Int
    val user: String
    val password: String?
    val keyPath: String?
    val keyPassphrase: String?
    val authMode: SshAuthMode
    val rememberPassword: Boolean
    val rememberKeyPassphrase: Boolean

    /** 首连确认：无已存记录，展示 [fingerprint] 等待用户确认。 */
    data class FirstConnect(
        override val host: String,
        override val port: Int,
        override val user: String,
        override val password: String?,
        override val keyPath: String?,
        override val keyPassphrase: String?,
        val fingerprint: String,
        override val authMode: SshAuthMode,
        override val rememberPassword: Boolean,
        override val rememberKeyPassphrase: Boolean
    ) : HostKeyPrompt

    /** 指纹变更确认：已存与当前不符，用户选择更新并连接或取消。 */
    data class KeyChanged(
        override val host: String,
        override val port: Int,
        override val user: String,
        override val password: String?,
        override val keyPath: String?,
        override val keyPassphrase: String?,
        val storedFingerprint: String,
        val currentFingerprint: String,
        override val authMode: SshAuthMode,
        override val rememberPassword: Boolean,
        override val rememberKeyPassphrase: Boolean
    ) : HostKeyPrompt

    /** 探测失败且无已存记录：展示 [message] 报错（无可恢复动作）。 */
    data class ProbeFailed(
        override val host: String,
        override val port: Int,
        override val user: String,
        override val password: String?,
        override val keyPath: String?,
        override val keyPassphrase: String?,
        val message: String,
        override val authMode: SshAuthMode,
        override val rememberPassword: Boolean,
        override val rememberKeyPassphrase: Boolean
    ) : HostKeyPrompt
}

/** SSH 连接信息对话框：主机/端口/用户名 + 认证方式（密码或私钥）。 */
@Composable
private fun SshConnectDialog(
    sshPreConnector: SshPreConnector,
    initial: LastSshConnection,
    onDismiss: () -> Unit,
    onConnect: (
        host: String,
        port: Int,
        user: String,
        password: String?,
        keyPath: String?,
        keyPassphrase: String?,
        authMode: SshAuthMode,
        rememberPassword: Boolean,
        rememberKeyPassphrase: Boolean
    ) -> Unit
) {
    val context = LocalContext.current

    // 用"上一次连接"回填；凭据仅在用户曾勾选"记住"时回填，私钥路径先校验文件仍在。
    var host by remember { mutableStateOf(initial.host) }
    var port by remember { mutableStateOf(if (initial.port > 0) initial.port.toString() else "22") }
    var user by remember { mutableStateOf(initial.user) }

    var authMode by remember { mutableStateOf(initial.authMode) }
    var password by remember {
        mutableStateOf(if (initial.rememberPassword) initial.password.orEmpty() else "")
    }
    var keyPath by remember {
        mutableStateOf(initial.keyPath?.takeIf { File(it).exists() })
    }
    var keyPassphrase by remember {
        mutableStateOf(if (initial.rememberKeyPassphrase) initial.keyPassphrase.orEmpty() else "")
    }
    var rememberPassword by remember { mutableStateOf(initial.rememberPassword) }
    var rememberKeyPassphrase by remember { mutableStateOf(initial.rememberKeyPassphrase) }
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
            sshPreConnector.checkPrivateKey(path, keyPassphrase.ifBlank { null })
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = rememberPassword,
                            onCheckedChange = { rememberPassword = it }
                        )
                        Text(stringResource(R.string.ssh_remember_password))
                    }
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = rememberKeyPassphrase,
                            onCheckedChange = { rememberKeyPassphrase = it }
                        )
                        Text(stringResource(R.string.ssh_remember_key_passphrase))
                    }
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
                        if (isKey) keyPassphrase.ifBlank { null } else null,
                        authMode,
                        rememberPassword,
                        rememberKeyPassphrase
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

/**
 * 会话列表项稳定壳：仅承载稳定字段（StateFlow 为 Compose 已知稳定类型），
 * 使 [SessionListScreen] 的 items 列表可被组合编译器判定为可跳过。
 */
@Immutable
private data class SessionRow(
    val id: Int,
    val selected: Boolean,
    val title: StateFlow<String?>,
    val name: StateFlow<String>,
    val running: StateFlow<Boolean>
)
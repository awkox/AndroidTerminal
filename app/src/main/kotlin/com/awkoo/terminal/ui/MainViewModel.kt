package com.awkoo.terminal.ui

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat.startForegroundService
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.awkoo.terminal.TerminalService
import com.awkoo.terminal.AppPreferences
import com.awkoo.terminal.Constants
import com.awkoo.terminal.core.LastSshConnection
import com.awkoo.terminal.core.PtyConfig
import com.awkoo.terminal.core.SessionManager
import com.awkoo.terminal.core.ShellInfo
import com.awkoo.terminal.core.SshAuthMode
import com.awkoo.libterminal.ssh.SshAuth
import com.awkoo.libterminal.ssh.SshInfo
import com.awkoo.terminal.extrakeys.ExtraKeysConfig
import com.awkoo.terminal.ui.theme.ThemeMode
import com.awkoo.libterminal.engine.TerminalCursorStyle
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 主界面 ViewModel。
 *
 * 封装会话管理和偏好设置，驱动 Compose UI 数据流。
 */
@HiltViewModel
class MainViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    val sessionManager: SessionManager,
    val preferences: AppPreferences
): ViewModel() {
    // 数据流
    val sessionListState = sessionManager.sessionList
    /** 新建本地 shell 会话，启动参数取自 [ptyConfig]（命令/args/环境变量/stdin）。 */
    fun addSession(name: String?, ptyConfig: PtyConfig) {
        val shellInfo = ShellInfo(
            executable = ptyConfig.command.ifBlank { "sh" },
            workingDirectory = context.filesDir.absolutePath,
            homeDirectory = context.filesDir.absolutePath,
            tempDirectory = context.cacheDir.absolutePath,
            arguments = ptyConfig.args
                .filter { it.isNotBlank() }
                .toTypedArray()
                .takeIf { it.isNotEmpty() },
            extraEnvironment = linkedMapOf<String, String>().apply {
                for (env in ptyConfig.environment) {
                    if (env.key.trim().isEmpty()) continue
                    put(env.key, env.value)
                }
            },
            stdin = ptyConfig.stdin.ifBlank { null }
        )
        if (name != null)
            shellInfo.commandLabel.update { name }

        sessionManager.addSession(shellInfo, maxTranscriptRows = transcriptRows.value)
        ensureTerminalService()
    }

    /** 新建 SSH 会话；私钥模式传 [keyPath]，否则用 [password]（可空=空认证）。
     *  [authMode]/[rememberPassword]/[rememberKeyPassphrase] 用于回写"上一次连接"
     *  快照：凭据仅在对应"记住"开关开启时落盘，否则只记身份信息。 */
    fun addSshSession(
        host: String,
        port: Int,
        user: String,
        password: String?,
        keyPath: String? = null,
        keyPassphrase: String? = null,
        hostKeyFingerprint: String? = null,
        authMode: SshAuthMode = SshAuthMode.Password,
        rememberPassword: Boolean = false,
        rememberKeyPassphrase: Boolean = false
    ) {
        val auth = if (keyPath != null) {
            SshAuth.PrivateKey(keyPath, keyPassphrase)
        } else {
            SshAuth.Password(password ?: "")
        }
        val sshInfo = SshInfo(
            name = "$user@$host",
            host = host,
            port = port,
            user = user,
            auth = auth,
            hostKeyFingerprint = hostKeyFingerprint
        )
        sessionManager.addSshSession(sshInfo, name = "$user@$host",
            maxTranscriptRows = transcriptRows.value)
        ensureTerminalService()
        persistLastSshConnection(
            host, port, user, password, keyPath, keyPassphrase,
            authMode, rememberPassword, rememberKeyPassphrase
        )
    }

    /** 覆盖写"上一次 SSH 连接"；凭据只在对应记住开关开启且认证模式匹配时写入。 */
    private fun persistLastSshConnection(
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
        viewModelScope.launch {
            preferences.setLastSshConnection(
                LastSshConnection(
                    host = host.trim(),
                    port = port,
                    user = user.trim(),
                    authMode = authMode,
                    rememberPassword = rememberPassword,
                    password = if (authMode == SshAuthMode.Password && rememberPassword) {
                        password
                    } else {
                        null
                    },
                    keyPath = if (authMode == SshAuthMode.PrivateKey) keyPath else null,
                    rememberKeyPassphrase = rememberKeyPassphrase,
                    keyPassphrase = if (authMode == SshAuthMode.PrivateKey &&
                        rememberKeyPassphrase
                    ) {
                        keyPassphrase
                    } else {
                        null
                    }
                )
            )
        }
    }

    /** 首个会话创建后启动前台服务，增加 Activity 生命周期外终端稳定性。 */
    private fun ensureTerminalService() {
        if (TerminalService.isRunning) return
        val serviceIntent = Intent(context, TerminalService::class.java)
        startForegroundService(context, serviceIntent)
    }

    val currentSessionState = sessionManager.currentSession.stateIn(
        viewModelScope,
        SharingStarted.Lazily,
        null
    )
    fun setCurrentSession(id: Int) {
        sessionManager.setCurrentSession(id)
    }

    /** 上一次 SSH 连接参数（Post dialog 回填用）。 */
    val lastSshConnection = preferences.lastSshConnection.stateIn(
        viewModelScope,
        SharingStarted.Lazily,
        LastSshConnection()
    )

    val terminalFontSize = preferences.terminalFontSize.stateIn(
        viewModelScope,
        SharingStarted.Lazily,
        0
    )

    fun setTerminalFontSize(size: Int) {
        viewModelScope.launch { preferences.setTerminalFontSize(size) }
    }

    val themeMode = preferences.themeMode.stateIn(
        viewModelScope,
        SharingStarted.Lazily,
        ThemeMode.DARK
    )

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { preferences.setThemeMode(mode) }
    }

    val terminalCursorStyle = preferences.terminalCursorStyle.stateIn(
        viewModelScope,
        SharingStarted.Lazily,
        TerminalCursorStyle.BAR
    )

    fun setTerminalCursorStyle(style: TerminalCursorStyle) {
        viewModelScope.launch { preferences.setTerminalCursorStyle(style) }
    }

    val cursorBlinking = preferences.cursorBlinking.stateIn(
        viewModelScope,
        SharingStarted.Lazily,
        true
    )

    fun setCursorBlinking(enabled: Boolean) {
        viewModelScope.launch { preferences.setCursorBlinking(enabled) }
    }

    val textBlinking = preferences.textBlinking.stateIn(
        viewModelScope,
        SharingStarted.Lazily,
        true
    )

    fun setTextBlinking(enabled: Boolean) {
        viewModelScope.launch { preferences.setTextBlinking(enabled) }
    }

    val transcriptRows = preferences.transcriptRows.stateIn(
        viewModelScope,
        SharingStarted.Lazily,
        Constants.DEFAULT_TERMINAL_TRANSCRIPT_ROWS
    )

    fun setTranscriptRows(rows: Int) {
        viewModelScope.launch { preferences.setTranscriptRows(rows) }
    }

    val extraKeysConfig = preferences.extraKeysConfig.stateIn(
        viewModelScope,
        SharingStarted.Lazily,
        ExtraKeysConfig()
    )

    fun setExtraKeysConfig(config: ExtraKeysConfig) {
        viewModelScope.launch { preferences.setExtraKeysConfig(config) }
    }

    val ptyConfig = preferences.ptyConfig.stateIn(
        viewModelScope,
        SharingStarted.Lazily,
        PtyConfig()
    )

    fun setPtyConfig(config: PtyConfig) {
        viewModelScope.launch { preferences.setPtyConfig(config) }
    }
}

package com.awkoo.terminal.ui

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat.startForegroundService
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.awkoo.terminal.TerminalService
import com.awkoo.terminal.AppPreferences
import com.awkoo.terminal.core.SessionManager
import com.awkoo.terminal.core.ShellInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
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
    fun addSession(name: String?) {
        val shellInfo = ShellInfo(
            "sh",
            context.filesDir.absolutePath,
            context.filesDir.absolutePath,
            context.cacheDir.absolutePath
        )
        if (name != null)
            shellInfo.commandLabel.update { name }

        sessionManager.addSession(shellInfo)

        // 启动前台服务用于增加生命周期稳定性
        if (!TerminalService.isRunning) {
            val serviceIntent = Intent(context, TerminalService::class.java)
            startForegroundService(context, serviceIntent)
        }
    }

    val currentSessionState = sessionManager.currentSession.stateIn(
        viewModelScope,
        SharingStarted.Lazily,
        null
    )
    fun setCurrentSession(id: Int) {
        sessionManager.setCurrentSession(id)
    }

    val terminalFontSize = preferences.terminalFontSize.stateIn(
        viewModelScope,
        SharingStarted.Lazily,
        0
    )
}

package com.awkoo.terminal.core

import com.awkoo.libterminal.engine.TerminalSession
import com.awkoo.libterminal.pty.CommandInfo
import com.awkoo.libterminal.pty.PtyFactory
import com.awkoo.libterminal.ssh.SshFactory
import com.awkoo.libterminal.ssh.SshInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch

@Singleton
@OptIn(ExperimentalAtomicApi::class)
class SessionManager @Inject constructor() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val idGenerator = AtomicInt(0)

    val sessionList: StateFlow<List<TerminalSession>>
        field = MutableStateFlow(emptyList())

    private val currentSessionId = MutableStateFlow(0)

    fun setCurrentSession(id: Int) {
        currentSessionId.update { current ->
            if (sessionList.value.any { it.id == id }) id
            else current
        }
    }

    val currentSession: Flow<TerminalSession?> =
        combine(currentSessionId, sessionList) { id, sessions ->
            sessions.find { it.id == id }
        }.distinctUntilChanged()

    fun removeSession(id: Int) {
        if (currentSessionId.value == id) {
            currentSessionId.update {
                sessionList.value.lastOrNull { it.id != id }?.id ?: 0
            }
        }
        sessionList.update { list ->
            list.filter { it.id != id }
        }
    }

    fun addSession(commandInfo: CommandInfo, maxTranscriptRows: Int = 5000) {
        // 设置终端环境变量
        commandInfo.extraEnvironment["TERM"] = "xterm-256color"
        commandInfo.extraEnvironment["COLORTERM"] = "truecolor"

        val targetSession = TerminalSession(
            id = idGenerator.incrementAndFetch(),
            sessionName = commandInfo.commandLabel,
            stdin = commandInfo.stdin?.toByteArray(),
            maxTranscriptRows = maxTranscriptRows
        ) { rows, cols, w, h ->
            PtyFactory(commandInfo, rows, cols, w, h)
        }

        registerSession(targetSession)
    }

    /**
     * 新建 SSH 会话：进程工厂为 [SshFactory]，凭据仅用密码认证。
     *
     * [name] 非空时作为会话显示名，否则回退为 "user@host"。
     */
    fun addSshSession(sshInfo: SshInfo, name: String?, maxTranscriptRows: Int = 5000) {
        val displayName = name ?: "${sshInfo.user}@${sshInfo.host}"
        val targetSession = TerminalSession(
            id = idGenerator.incrementAndFetch(),
            sessionName = MutableStateFlow(displayName),
            maxTranscriptRows = maxTranscriptRows
        ) { rows, cols, w, h ->
            SshFactory(sshInfo, rows, cols, w, h)
        }

        registerSession(targetSession)
    }

    /** 入列、设为当前会话，并启动 isRemove/列表剔除兜底清理协程。 */
    private fun registerSession(targetSession: TerminalSession) {
        targetSession.execute()

        sessionList.update { it + targetSession }
        currentSessionId.update { targetSession.id }

        // 使用 combine 联合监听 Session 自身的 isRemove 状态与全局的 sessionList
        // 一旦会话要求移除，或者它已经被外部手段从列表中剔除，first { it } 都会立刻放行
        // 随后执行兜底的 removeSession 并自然结束协程，杜绝任何内存泄漏的可能。
        scope.launch {
            combine(targetSession.isRemove, sessionList) { isRemove, list ->
                isRemove || list.none { it.id == targetSession.id }
            }.first { it }

            removeSession(targetSession.id)
        }
    }

    val isSessionsListEmpty: Boolean
        get() = sessionList.value.isEmpty()

    val sessionListSize: Int
        get() = sessionList.value.size

    @OptIn(ExperimentalAtomicApi::class)
    fun clear() {
        sessionList.value.forEach { it.finishIfRunning() }
        currentSessionId.update { 0 }
        sessionList.update { emptyList() }
        idGenerator.store(0)
    }
}
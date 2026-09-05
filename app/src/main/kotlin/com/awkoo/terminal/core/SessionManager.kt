package com.awkoo.terminal.core

import com.awkoo.libterminal.engine.TerminalSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    private val _sessionList = MutableStateFlow(emptyList<TerminalSession>())
    val sessionList = _sessionList.asStateFlow()

    private val currentSessionId = MutableStateFlow(0)

    fun setCurrentSession(id: Int) {
        currentSessionId.update { current ->
            if (_sessionList.value.any { it.id == id }) id
            else current
        }
    }

    val currentSession: Flow<TerminalSession?> =
        combine(currentSessionId, _sessionList) { id, sessions ->
            sessions.find { it.id == id }
        }.distinctUntilChanged()

    fun removeSession(id: Int) {
        if (currentSessionId.value == id) {
            currentSessionId.update {
                _sessionList.value.lastOrNull { it.id != id }?.id ?: 0
            }
        }
        _sessionList.update { list ->
            list.filter { it.id != id }
        }
    }

    fun addSession(commandInfo: CommandInfo, maxTranscriptRows: Int = 5000) {
        val sessionId = idGenerator.incrementAndFetch()

        // 设置终端环境变量
        commandInfo.extraEnvironment["TERM"] = "xterm-256color"
        commandInfo.extraEnvironment["COLORTERM"] = "truecolor"

        val targetSession = TerminalSession(
            id = sessionId,
            sessionName = commandInfo.commandLabel,
            stdin = commandInfo.stdin?.toByteArray(),
            maxTranscriptRows = maxTranscriptRows
        ) { rows, cols, w, h ->
            LocalPtyProcess(commandInfo, rows, cols, w, h)
        }

        targetSession.execute()

        _sessionList.update { it + targetSession }
        currentSessionId.update { targetSession.id }

        // 使用 combine 联合监听 Session 自身的 isRemove 状态与全局的 _sessionList
        // 一旦会话要求移除，或者它已经被外部手段从列表中剔除，first { it } 都会立刻放行
        // 随后执行兜底的 removeSession 并自然结束协程，杜绝任何内存泄漏的可能。
        scope.launch {
            combine(targetSession.isRemove, _sessionList) { isRemove, list ->
                isRemove || list.none { it.id == targetSession.id }
            }.first { it }
            
            removeSession(targetSession.id)
        }
    }

    val isSessionsListEmpty: Boolean
        get() = _sessionList.value.isEmpty()

    val sessionListSize: Int
        get() = _sessionList.value.size

    @OptIn(ExperimentalAtomicApi::class)
    fun clear() {
        _sessionList.value.forEach { it.finishIfRunning() }
        currentSessionId.update { 0 }
        _sessionList.update { emptyList() }
        idGenerator.store(0)
    }
}
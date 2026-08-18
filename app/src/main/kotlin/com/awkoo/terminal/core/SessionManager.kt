package com.awkoo.terminal.core

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
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.atomics.ExperimentalAtomicApi

@Singleton
class SessionManager @Inject internal constructor() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // 彻底移除了原先的 private val sessionJobs = mutableMapOf<Int, Job>()

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

    @Synchronized
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

    @Synchronized
    fun addSession(commandInfo: CommandInfo) {
        val targetSession = TerminalSession(commandInfo) { info, rows, cols, w, h ->
            LocalPtyProcess(info, rows, cols, w, h)
        }

        targetSession.execute()

        _sessionList.update { it + targetSession }
        currentSessionId.update { targetSession.id }

        // 优雅的清理逻辑：
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

    @get:Synchronized
    val isSessionsListEmpty: Boolean
        get() = _sessionList.value.isEmpty()

    @get:Synchronized
    val sessionListSize: Int
        get() = _sessionList.value.size

    @OptIn(ExperimentalAtomicApi::class)
    @Synchronized
    fun clear() {
        killAllSessions()
        currentSessionId.update { 0 }
        _sessionList.update { emptyList() }
        CommandInfo.endId.store(0)
    }

    @Synchronized
    private fun killAllSessions() {
        Timber.i("Killing $sessionListSize sessions")

        // 结束进程
        _sessionList.value.forEach { it.finishIfRunning() }
    }
}
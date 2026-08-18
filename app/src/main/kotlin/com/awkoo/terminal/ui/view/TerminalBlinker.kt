package com.awkoo.terminal.ui.view

import com.awkoo.terminal.Constants
import com.awkoo.terminal.core.TerminalEmulator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * 通用的终端闪烁动画管理器（可用于光标闪烁、文本闪烁等）。
 *
 * 结合 [poke] 方法，可以在用户输入文字时打断闪烁周期，保持常亮并重新计时。
 */
class TerminalBlinker(
    private val blinkerName: String,
    private val scope: CoroutineScope,
    private val onInvalidate: () -> Unit,
    private val shouldStart: (TerminalEmulator) -> Boolean = { true },
    private val setBlinkingEnabled: (TerminalEmulator, Boolean) -> Unit,
    private val setBlinkState: (TerminalEmulator, Boolean) -> Unit
) {
    private var job: Job? = null
    
    // 挂起通道，用于接收输入中断信号。CONFLATED 保证如果瞬间触发多次，只保留最后一次信号。
    private val resetChannel = Channel<Unit>(Channel.CONFLATED)

    @Synchronized
    fun start(emulator: TerminalEmulator) {
        stop()

        val rate = Constants.terminalCursorBlinkerRate
        if (rate == 0L || !shouldStart(emulator)) {
            Timber.i("Ignoring call to start $blinkerName blinker (disabled or rate=0)")
            return
        }

        // 启动前清空旧的信号积压
        while (resetChannel.tryReceive().isSuccess) {}

        Timber.i("Starting $blinkerName blinker with blink rate $rate")
        job = scope.launch {
            try {
                synchronized(emulator) { setBlinkingEnabled(emulator, true) }
                var isVisible = true
                while (isActive) {
                    synchronized(emulator) { setBlinkState(emulator, isVisible) }
                    onInvalidate()

                    // 挂起等待：如果在 rate 时间内没有收到信号，将返回 null
                    val interrupted = withTimeoutOrNull(rate) {
                        resetChannel.receive()
                    }

                    if (interrupted != null) {
                        // 被打断（即用户正在输入）：强制光标进入可见状态，并立刻进入下一轮 while 循环重新计时
                        isVisible = true
                    } else {
                        // 正常超时：闪烁状态翻转
                        isVisible = !isVisible
                    }
                }
            } finally {
                // 确保协程被取消时关闭闪烁状态
                synchronized(emulator) { setBlinkingEnabled(emulator, false) }
            }
        }
    }

    @Synchronized
    fun stop() {
        job?.let {
            Timber.i("Stopping $blinkerName blinker")
            it.cancel()
            job = null
        }
    }

    /**
     * 唤醒 / 重置闪烁器。
     * 在用户输入文字或执行操作时调用，可使光标保持常亮并重新开始计时。
     */
    fun poke() {
        resetChannel.trySend(Unit)
    }
}
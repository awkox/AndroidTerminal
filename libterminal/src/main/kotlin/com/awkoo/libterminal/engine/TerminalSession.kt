package com.awkoo.libterminal.engine

import com.awkoo.libterminal.process.ITerminalProcess
import com.awkoo.libterminal.text.Utf8Decoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import java.io.IOException

/**
 * 终端会话，包含一个子进程及其对应的终端模拟器。
 *
 * 构造时仅预分配缓冲池并组装模拟器；调用 [execute] 后才创建并拉起子进程。
 * 子进程 I/O 和模拟器回调均在协程中运行，屏幕更新通过 [uiEvent] 通知 UI 层。
 *
 * 注意：会话可能比 UI 组件存活更久，回调中需谨慎处理生命周期。
 *
 * @param id 会话 ID，用于 UI 层标识与会话列表键控。
 * @param sessionName 会话名（可更新的 StateFlow），顶栏与会话列表显示用。
 * @param stdin 可选：启动时写入进程的初始输入，默认 null。
 * @param maxTranscriptRows 历史回滚缓冲区行数，默认 5000；仅作用于本会话。
 * @param processFactory (rows, cols, cellWidth, cellHeight) -> [ITerminalProcess] 的进程工厂，
 *        在 [execute] 时被调用来创建子进程。
 */
class TerminalSession(
    val id: Int,
    val sessionName: MutableStateFlow<String>,
    private val stdin: ByteArray? = null,
    private val maxTranscriptRows: Int = 5000,
    private val processFactory: (Int, Int, Int, Int) -> ITerminalProcess
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var inputReaderJob: Job? = null
    private var outputWriterJob: Job? = null
    private var emulatorProcessorJob: Job? = null
    private var exitHandlerJob: Job? = null

    private class DataChunk(val buffer: ByteArray, var length: Int)

    private val terminalReadChannel: Channel<DataChunk> = Channel(Channel.UNLIMITED)
    private val terminalReadBufferPoolChannel = Channel<DataChunk>(POOL_SIZE)
    private val terminalWriteChannel: Channel<ByteArray> = Channel(Channel.BUFFERED)
    private val resumeChannel: Channel<Unit> = Channel(Channel.CONFLATED)

    @Volatile
    private var processorRunning: Boolean = false

    init {
        repeat(POOL_SIZE) {
            terminalReadBufferPoolChannel.trySend(DataChunk(ByteArray(CHUNK_SIZE), 0))
        }
    }

    /** 进程是否仍在运行：绑定成功后为 true，进程退出后置 false。App 可订阅监听结束/删除线。 */
    val isRunning = MutableStateFlow(false)

    /** 进程退出状态，仅在 [isRunning]（值为 false）时有效。 */
    /** Shell 进程的退出码，仅在进程结束后有效。 */
    @Volatile
    var exitStatus: Int = 0
        private set

    internal val emulator = TerminalEmulator(
        writeString = ::write,
        writeByteArray = ::write,
        maxTranscriptRows = maxTranscriptRows,
        onDeferredResumeRequested = ::onEmulatorResumeRequested
    )

    private var process: ITerminalProcess? = null

    /** OSC 0/1/2 设置的终端标题（如 vim 标签），未设置时为 null。 */
    val titleState = emulator.titleState

    /** OSC 52 剪贴板写入事件流，仅供模块内 view 层消费。 */
    internal val copiedText = emulator.copiedText

    private fun onEmulatorResumeRequested() {
        if (processorRunning) {
            resumeChannel.trySend(Unit)
        } else {
            emulator.drainPausedAll()
            notifyScreenUpdate()
        }
    }

    /** 通知伪终端新尺寸，并执行文本重排或重新初始化模拟器。 */
    internal fun updateSize(columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int) {
        if (isRunning.value) {
            process?.resize(columns, rows, cellWidthPixels, cellHeightPixels)
        }
        emulator.withWriteLock {
            emulator.resize(columns, rows, cellWidthPixels, cellHeightPixels)
        }
    }

    /** 启动子进程（通过 processFactory）并拉起读/写/仿真/退出协程；会话须先调用一次。 */
    fun execute() {
        val p = processFactory(
            emulator.rows,
            emulator.columns,
            emulator.cellWidthPixels,
            emulator.cellHeightPixels
        )
        this.process = p
        isRunning.value = true

        // 先启动消费者，确保读取端发送数据时有人接收。
        emulatorProcessorJob = launchEmulatorProcessor()

        // 再启动读取端。
        inputReaderJob = launchInputReader(p)

        // 启动输入写入端。
        outputWriterJob = launchOutputWriter(p)

        // 最后启动退出监控。
        exitHandlerJob = launchExitHandler(p)
    }

    private fun launchInputReader(p: ITerminalProcess): Job {
        return scope.launch {
            try {
                p.inputStream.use { termIn ->
                    while (true) {
                        val chunk = terminalReadBufferPoolChannel.receive()

                        val read = try {
                            termIn.read(chunk.buffer)
                        } catch (e: IOException) {
                            // 读取异常时归还当前缓冲区。
                            terminalReadBufferPoolChannel.trySend(chunk)
                            throw e
                        }

                        if (read == -1) {
                            terminalReadBufferPoolChannel.trySend(chunk)
                            break
                        }

                        chunk.length = read

                        // 成功发送后，缓冲区所有权交给 Processor。
                        terminalReadChannel.send(chunk)
                    }
                }
            } catch (e: IOException) {
                // 输入流异常关闭或 SSH 断线。
                // 由 finally 关闭 Channel，通知 Processor 结束。
            } finally {
                // 只关闭读取 Channel，不关闭缓冲池。
                //
                // Channel.close() 后，已经成功发送的元素仍然可以被消费。
                terminalReadChannel.close()
            }
        }
    }

    private fun launchOutputWriter(p: ITerminalProcess): Job {
        return scope.launch {
            try {
                p.outputStream.use { termOut ->
                    stdin?.let { termOut.write(it) }

                    for (buffer in terminalWriteChannel) {
                        termOut.write(buffer, 0, buffer.size)
                    }

                    termOut.flush()
                }
            } catch (e: IOException) {
                // 进程退出或输入流关闭。
            } finally {
                terminalWriteChannel.close()
            }
        }
    }

    private fun launchEmulatorProcessor(): Job {
        return scope.launch(Dispatchers.Default) {
            processorRunning = true
            var pendingChunk: DataChunk? = null
            var pendingOffset = 0
            try {
                var channelClosed = false
                while (true) {
                    var yieldToWaiters = false
                    var processedAny = false

                    if (emulator.hasReadyPausedBacklog()) {
                        var bytesProcessed = 0
                        val startNs = System.nanoTime()
                        emulator.withWriteLock {
                            while (bytesProcessed < MAX_BATCH_BYTES) {
                                val hasMore = emulator.drainPausedSlice(PROCESS_SLICE_BYTES)
                                bytesProcessed += PROCESS_SLICE_BYTES
                                processedAny = true
                                if (!hasMore) break
                                if (emulator.hasPendingLockWaiter() ||
                                    System.nanoTime() - startNs >= MAX_BATCH_HOLD_NS
                                ) {
                                    yieldToWaiters = true
                                    break
                                }
                            }
                        }
                    } else {
                        if (pendingChunk == null && !channelClosed) {
                            val immediate = terminalReadChannel.tryReceive()
                            if (immediate.isSuccess) {
                                pendingChunk = immediate.getOrThrow()
                                pendingOffset = 0
                            } else if (immediate.isClosed) {
                                channelClosed = true
                            }
                        }

                        if (pendingChunk == null && !channelClosed && !emulator.hasReadyPausedBacklog()) {
                            select<Unit> {
                                terminalReadChannel.onReceiveCatching { result ->
                                    val chunk = result.getOrNull()
                                    if (chunk != null) {
                                        pendingChunk = chunk
                                        pendingOffset = 0
                                    } else {
                                        channelClosed = true
                                    }
                                }
                                resumeChannel.onReceive {
                                }
                            }
                        }

                        if (pendingChunk != null && !emulator.hasReadyPausedBacklog()) {
                            var bytesProcessed = 0
                            val startNs = System.nanoTime()
                            emulator.withWriteLock {
                                while (bytesProcessed < MAX_BATCH_BYTES) {
                                    val chunk = pendingChunk ?: break
                                    while (pendingOffset < chunk.length) {
                                        val step = minOf(PROCESS_SLICE_BYTES, chunk.length - pendingOffset)
                                        emulator.append(chunk.buffer, pendingOffset, step)
                                        pendingOffset += step
                                        bytesProcessed += step
                                        processedAny = true

                                        if (bytesProcessed >= MAX_BATCH_BYTES ||
                                            emulator.hasPendingLockWaiter() ||
                                            System.nanoTime() - startNs >= MAX_BATCH_HOLD_NS
                                        ) {
                                            if (pendingOffset < chunk.length) {
                                                yieldToWaiters = true
                                                break
                                            }
                                        }
                                    }

                                    if (pendingOffset >= chunk.length) {
                                        terminalReadBufferPoolChannel.trySend(chunk)
                                        pendingChunk = null
                                        pendingOffset = 0
                                    }

                                    if (yieldToWaiters ||
                                        bytesProcessed >= MAX_BATCH_BYTES ||
                                        emulator.hasPendingLockWaiter() ||
                                        System.nanoTime() - startNs >= MAX_BATCH_HOLD_NS
                                    ) {
                                        if (pendingChunk == null && !channelClosed) {
                                            val nextResult = terminalReadChannel.tryReceive()
                                            val nextChunk = nextResult.getOrNull()
                                            if (nextChunk != null) {
                                                pendingChunk = nextChunk
                                                pendingOffset = 0
                                            } else if (nextResult.isClosed) {
                                                channelClosed = true
                                            }
                                        }
                                        if (pendingChunk != null) {
                                            yieldToWaiters = true
                                        }
                                        break
                                    }

                                    val nextResult = terminalReadChannel.tryReceive()
                                    val nextChunk = nextResult.getOrNull()
                                    if (nextChunk != null) {
                                        pendingChunk = nextChunk
                                        pendingOffset = 0
                                    } else {
                                        if (nextResult.isClosed) channelClosed = true
                                        break
                                    }
                                }
                            }
                        }
                    }

                    if (processedAny) {
                        notifyScreenUpdate()
                    }
                    if (channelClosed && pendingChunk == null && !emulator.hasReadyPausedBacklog()) {
                        break
                    }
                    if (yieldToWaiters) {
                        yield()
                    }
                }
            } finally {
                processorRunning = false
                pendingChunk?.let { terminalReadBufferPoolChannel.trySend(it) }
                if (emulator.hasReadyPausedBacklog()) {
                    emulator.withWriteLock {
                        emulator.drainPausedAll()
                    }
                    notifyScreenUpdate()
                }
            }
        }
    }

    private fun launchExitHandler(p: ITerminalProcess): Job {
        return scope.launch {
            var exitCode = -1
            var exitReason: String? = null

            try {
                // 1. 等待子进程退出。
                exitCode = p.waitFor()

                // 2. 在释放进程资源之前保存退出原因。
                exitReason = p.failureReason

                // 3. 等待读取协程结束；Reader 结束时会关闭 terminalReadChannel。
                //    进程已退出但流未 EOF（PTY 被孤儿进程占用）时 Reader 永不结束，
                //    超时即放弃排空照常收尾，否则退出路径整体假死（提示不出、
                //    isRunning 恒 true、会话无法关闭）。SSH 的 EOF 在 waitFor
                //    返回前已发生，正常路径不受超时影响。
                val drained = withTimeoutOrNull(DRAIN_TIMEOUT_MS) {
                    inputReaderJob?.join()
                } != null

                // 4. Reader 已关通道时 Processor 必然排空后退出；
                //    超时分支由步骤 10 的 scope.cancel() 在挂起点收尾。
                if (drained) {
                    emulatorProcessorJob?.join()
                }

            } finally {
                // 5. 输出排空后释放进程资源。
                try {
                    p.close()
                } catch (_: Exception) {
                }

                // 6. 关闭写入 Channel，通知 Writer 结束。
                terminalWriteChannel.close()

                // 7. 等待 Writer 退出；写端可能卡在满缓冲的 pty 输入上，同样限时。
                withTimeoutOrNull(DRAIN_TIMEOUT_MS) {
                    outputWriterJob?.join()
                }

                // 8. 最后追加退出提示。
                withContext(Dispatchers.Main.immediate) {
                    handleProcessExit(exitCode, exitReason)
                    isRunning.value = false
                }

                // 9. 释放缓冲池。
                terminalReadBufferPoolChannel.close()

                // 10. 结束会话协程。
                scope.cancel()
            }
        }
    }

    private fun handleProcessExit(
        exitCode: Int,
        exitReason: String?
    ) {
        exitStatus = exitCode

        emulator.withWriteLock {
            // SSH 等进程的非正常退出原因。
            if (!exitReason.isNullOrEmpty()) {
                val reasonBytes = ("\r\n$exitReason\r\n").toByteArray()

                emulator.append(
                    reasonBytes,
                    reasonBytes.size
                )
            }

            val status = when {
                exitCode > 0 -> " (code $exitCode)"
                exitCode < 0 -> " (signal ${-exitCode})"
                else -> ""
            }

            val buffer =
                "\r\n[Process completed$status - press Enter]".toByteArray()

            emulator.append(buffer, buffer.size)
        }

        notifyScreenUpdate()
    }

    /** 请求移除标记：进程结束回显后回车置 true，供 UI 层移除会话。 */
    val isRemove = MutableStateFlow(false)

    /** 向 Shell 进程写入数据。 */
    fun write(data: ByteArray) {
        if (isRunning.value) {
            terminalWriteChannel.trySend(data)
        } else if (
            data.size == 1 &&
            (data[0] == '\n'.code.toByte() ||
                    data[0] == '\r'.code.toByte())
        ) {
            isRemove.update { true }
        }
    }

    fun write(data: String) {
        write(data.toByteArray())
    }

    /** 将码点编码为 UTF-8 后写入进程输出的缓冲区。 */
    private val writeBuffer = ByteArray(5)

    /** 非法码点（超出 Unicode 范围或落在代理区）统一降级为替换字符。 */
    private fun sanitizeCodePoint(codePoint: Int): Int =
        if (codePoint in 0..0x10FFFF && codePoint !in 0xD800..0xDFFF) {
            codePoint
        } else {
            Utf8Decoder.UNICODE_REPLACEMENT_CHAR
        }

    /** 将 Unicode 码点以 UTF-8 编码写入终端。 */
    internal fun writeCodePoint(prependEscape: Boolean, rawCodePoint: Int) {
        val codePoint = sanitizeCodePoint(rawCodePoint)

        var bufferPosition = 0
        if (prependEscape) writeBuffer[bufferPosition++] = 27

        if (codePoint <=  /* 7 位 */127) {
            writeBuffer[bufferPosition++] = codePoint.toByte()
        } else if (codePoint <=  /* 11 位 */2047) {
            /* 110xxxxx 首字节，取高 5 位 */
            writeBuffer[bufferPosition++] = (192 or (codePoint shr 6)).toByte()
            /* 10xxxxxx 后续字节，取低 6 位 */
            writeBuffer[bufferPosition++] = (128 or (codePoint and 63)).toByte()
        } else if (codePoint <=  /* 16 位 */65535) {
            /* 1110xxxx 首字节，取高 4 位 */
            writeBuffer[bufferPosition++] = (224 or (codePoint shr 12)).toByte()
            /* 10xxxxxx 后续字节，取次高 6 位 */
            writeBuffer[bufferPosition++] = (128 or ((codePoint shr 6) and 63)).toByte()
            /* 10xxxxxx 后续字节，取低 6 位 */
            writeBuffer[bufferPosition++] = (128 or (codePoint and 63)).toByte()
        } else { /* sanitizeCodePoint 已保证 codePoint <= 1114111，最多 21 位 = 0b111111111111111111111 */
            /* 11110xxx 首字节，取高 3 位 */
            writeBuffer[bufferPosition++] = (240 or (codePoint shr 18)).toByte()
            /* 10xxxxxx 后续字节，取第 12~17 位 */
            writeBuffer[bufferPosition++] = (128 or ((codePoint shr 12) and 63)).toByte()
            /* 10xxxxxx 后续字节，取第 6~11 位 */
            writeBuffer[bufferPosition++] = (128 or ((codePoint shr 6) and 63)).toByte()
            /* 10xxxxxx 后续字节，取低 6 位 */
            writeBuffer[bufferPosition++] = (128 or (codePoint and 63)).toByte()
        }
        write(writeBuffer.copyOf(bufferPosition))
    }

    /** 屏幕变更通知事件流，供模块内 view 层订阅重绘。 */
    internal val uiEvent = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /** 通过 uiEvent 通知 UI 层屏幕已更新。 */
    private fun notifyScreenUpdate() {
        uiEvent.tryEmit(Unit)
    }

    /** 重置终端模拟器状态。 */
    fun reset() {
        emulator.withWriteLock {
            emulator.reset()
        }
        notifyScreenUpdate()
    }

    /** 向 Shell 发送 SIGKILL 终止会话。 */
    fun finishIfRunning() {
        process?.kill()
    }

    companion object {
        private const val POOL_SIZE = 32
        private const val CHUNK_SIZE = 16384
        private const val PROCESS_SLICE_BYTES = 4096
        private const val MAX_BATCH_BYTES = 64 * 1024
        private const val MAX_BATCH_HOLD_NS = 4_000_000L

        /** 退出汇合各 join 的排空期限：正常 EOF 毫秒级，超时视为流已不可结束。 */
        private const val DRAIN_TIMEOUT_MS = 3_000L
    }
}

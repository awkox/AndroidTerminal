package com.awkoo.ssh

import android.os.ParcelFileDescriptor
import com.awkoo.libterminal.process.ITerminalProcess
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

/** SSH 连接失败的根异常；按类别派生，UI 依据类型分支提示与重试。 */
open class SshException(message: String) : RuntimeException(message)

/** 建连/会话建立类错误（不可达、超时、通道/PTY 失败等）。 */
class SshConnectException(message: String) : SshException(message)

/** 主机密钥与期望值不符（可能是重装系统或被劫持）。 */
class SshHostKeyMismatchException(
    message: String,
    val expectedFingerprint: String,
    val gotFingerprint: String
) : SshException(message)

/** 凭据无效或认证方式可用性错误。 */
class SshAuthException(message: String) : SshException(message)

/**
 * 基于 libssh（mbedTLS 后端）的 SSH 会话进程。
 *
 * 构造立即返回：App 侧先拿到 socketpair 的流（读阻塞、写积压、键盘可立即弹出），
 * 真正的连接在后台线程进行。连接成功后 native 的 I/O 线程开始搬运字节，
 * 期间用户先行输入的字节保留在 socket 缓冲中不会丢失；连接失败时 native
 * 关闭对应 fd，App 侧流读到 EOF，会话按"进程立即退出"结束，不会崩溃。
 *
 * 凭据（[SshCredentials]）为调用方解密后的明文，仅存在于本进程对象生命周期内。
 */
class SshProcess(
    sshInfo: SshInfo,
    credentials: SshCredentials,
    rows: Int,
    columns: Int
) : ITerminalProcess {

    companion object {
        init { System.loadLibrary("sshterm") }

        @JvmStatic
        private external fun sshConnect(
            host: String,
            port: Int,
            user: String,
            password: String?,
            privateKeyPath: String?,
            keyPassphrase: String?,
            expectedHostKeyFingerprint: String?,
            timeoutMs: Int,
            nativeFd: Int,
            rows: Int,
            cols: Int
        ): Long

        @JvmStatic
        private external fun sshResize(handle: Long, rows: Int, cols: Int)

        /** OpenSSH 风格服务器指纹（如 "SHA256:..."），返回会话的实际主机密钥指纹。 */
        @JvmStatic
        private external fun sshGetServerFingerprint(handle: Long): String?

        @JvmStatic
        private external fun sshWait(handle: Long): Int

        @JvmStatic
        private external fun sshKill(handle: Long)

        @JvmStatic
        private external fun sshClose(handle: Long)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 连接结果句柄：0 = 失败；仅在连接协程完成后被置位。 */
    private val handleDeferred = CompletableDeferred<Long>()

    /** 连接完成后的有效句柄，供 resize/kill/close 即时读取。 */
    @Volatile
    private var handle: Long = 0

    private var fdObj: ParcelFileDescriptor? = null

    override var inputStream: InputStream
    override var outputStream: OutputStream

    init {
        val socketPair = ParcelFileDescriptor.createSocketPair()
        fdObj = socketPair[0]
        inputStream = FileInputStream(socketPair[0].fileDescriptor)
        outputStream = FileOutputStream(socketPair[0].fileDescriptor)

        val connFd = socketPair[1].detachFd()
        scope.launch {
            val h = try {
                sshConnect(
                    sshInfo.host, sshInfo.port, sshInfo.user,
                    credentials.password, sshInfo.keyPath, credentials.keyPassphrase,
                    sshInfo.hostKeyFingerprint,
                    sshInfo.timeoutMs, connFd, rows, columns
                )
            } catch (e: Throwable) {
                // native 负责关闭 connFd；对会话表现为"进程立即退出"。
                0L
            }
            handle = h
            handleDeferred.complete(h)
        }
    }

    override fun resize(columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int) {
        if (handle != 0L) {
            sshResize(handle, rows, columns)
        }
    }

    override fun waitFor(): Int = runBlocking {
        val h = handleDeferred.await()
        if (h == 0L) -1 else sshWait(h)
    }

    override fun kill() {
        // 连接中无句柄可杀；连接完成后由 close() 统一释放。
        if (handle != 0L) {
            sshKill(handle)
        }
    }

    override fun close() {
        try { fdObj?.close() } catch (e: Exception) {}
        val h = handle
        if (h != 0L) {
            sshClose(h)
            handle = 0
        } else if (!handleDeferred.isCompleted) {
            // 连接尚未结束：等它收敛后释放，避免泄漏 native 会话。
            scope.launch {
                val h2 = handleDeferred.await()
                if (h2 != 0L) sshClose(h2)
            }
        }
    }
}
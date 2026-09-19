package com.awkoo.libterminal.ssh

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

/**
 * 基于 libssh（mbedTLS 后端）的 SSH 会话进程，与 PtyFactory 同构。
 *
 * 构造立即返回：App 侧先拿到 socketpair 的流（读阻塞、写积压），
 * 真正的连接在后台线程异步进行。连接失败（不可达/超时/认证失败/
 * 主机指纹不符）时 native 关闭对应 fd，App 侧流读到 EOF，会话按
 * "进程立即退出"结束，不会抛出任何异常。
 */
class SshFactory(
    info: SshInfo,
    rows: Int,
    columns: Int,
    cellWidthPixels: Int,
    cellHeightPixels: Int
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

        @JvmStatic
        private external fun sshWait(handle: Long): Int

        @JvmStatic
        private external fun sshKill(handle: Long)

        @JvmStatic
        private external fun sshClose(handle: Long)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 连接结果句柄：0 = 失败（native 已关 fd）；仅在连接协程完成后被置位。 */
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
        val (password, keyPath, keyPassphrase) = when (val auth = info.auth) {
            is SshAuth.Password -> Triple(auth.password, null, null)
            is SshAuth.PrivateKey -> Triple(null, auth.path, auth.passphrase)
        }
        scope.launch {
            // native 负责失败时关闭 connFd；对会话表现为"进程立即退出"。
            val h = sshConnect(
                info.host, info.port, info.user,
                password, keyPath, keyPassphrase,
                info.hostKeyFingerprint,
                info.timeoutMs, connFd, rows, columns
            )
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
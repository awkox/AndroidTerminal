package com.awkoo.libterminal.ssh

import android.os.ParcelFileDescriptor
import com.awkoo.libterminal.process.ITerminalProcess
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * 基于 libssh（mbedTLS 后端）的 SSH 会话进程，与 PtyFactory 同构。
 *
 * 构造立即返回：App 侧先拿到 socketpair 的流（读阻塞、写积压），native 同步
 * 创建会话对象与终止通道、启动后台握手线程后即返回，真正的连接在 native 线程
 * 异步进行。连接失败（不可达/超时/认证失败/主机指纹不符/被杀）时 native 关闭
 * 对应 fd，App 侧流读到 EOF，会话按"进程立即退出"结束，不会抛出任何异常。
 *
 * 句柄在构造期立即可用：[kill] 在握手进行中也会生效（唤醒 native 折叠连接），
 * 无需等待 [waitFor] 返回。
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
        private external fun sshErrorText(handle: Long): String

        @JvmStatic
        private external fun sshKill(handle: Long)

        @JvmStatic
        private external fun sshClose(handle: Long)
    }

    /** 立即有效的会话句柄：0 表示构造期致命失败（native 已关 fd，App 侧到 EOF）。 */
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
        handle = sshConnect(
            info.host, info.port, info.user,
            password, keyPath, keyPassphrase,
            info.hostKeyFingerprint,
            info.timeoutMs, connFd, rows, columns
        )
    }

    override fun resize(columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int) {
        if (handle != 0L) {
            sshResize(handle, rows, columns)
        }
    }

    override fun waitFor(): Int {
        val h = handle
        if (h == 0L) {
            return -1
        }
        // native 阻塞等待握手收敛；失败（含被 kill）返回 -1，成功则等待 shell 退出。
        val code = sshWait(h)
        // 非零退出时快照结构化原因，供行程处理在 close() 前读取。
        if (_failureReason == null && code != 0) {
            _failureReason = sshErrorText(h).takeIf { it.isNotEmpty() }
        }
        return code
    }

    private var _failureReason: String? = null

    /** 非正常退出原因（认证失败/连接中断等）；正常退出或本地进程为 null。 */
    override val failureReason: String?
        get() = _failureReason

    override fun kill() {
        // 句柄立即可用：握手进行中唤醒 native 立即折返，已连接则由 killed 轮询收尾。
        if (handle != 0L) {
            sshKill(handle)
        }
    }

    override fun close() {
        // 必须先关本地 fd，native teardown join reader 才能读到 EOF 返回。
        try { fdObj?.close() } catch (e: Exception) {}
        val h = handle
        if (h != 0L) {
            sshClose(h)
            handle = 0
        }
    }
}
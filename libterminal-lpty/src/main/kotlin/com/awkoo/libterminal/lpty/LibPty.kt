package com.awkoo.libterminal.lpty

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.awkoo.libterminal.process.ITerminalProcess
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * 本地终端启动参数（模块原生层参数载体）。
 *
 * 仅描述"拉起什么进程"，环境排序与命令组装由调用方负责；
 * [arguments] 应为完整 argv（含可执行名），风格与 AppCompat 的
 * 命令行组装保持一致。
 */
data class PtyParams(
    val executable: String,
    val cwd: String,
    val arguments: Array<String> = emptyArray(),
    val environment: Array<Array<String>> = emptyArray()
)

/**
 * 本地 PTY 进程（fork/exec + pseudoterminal）。
 *
 * 与 [PtyParams] 的解耦：本模块只负责通过 POSIX ptmx 拉起
 * 本地子进程并暴露标准 Java 流；工作目录、环境变量、argv 等
 * 一律由调用方通过 [PtyParams] 传入。
 */
class LibPtyProcess(
    params: PtyParams,
    rows: Int,
    columns: Int,
    cellWidth: Int,
    cellHeight: Int
) : ITerminalProcess {

    companion object {
        init { System.loadLibrary("pty") }

        @JvmStatic
        private external fun createSubprocess(
            cmd: String,
            cwd: String,
            args: Array<String>?,
            env: Array<Array<String>>?,
            processId: IntArray,
            rows: Int,
            columns: Int,
            cellWidth: Int,
            cellHeight: Int
        ): Int

        @JvmStatic
        private external fun setPtyWindowSize(fd: Int, rows: Int, cols: Int, cellWidth: Int, cellHeight: Int)

        @JvmStatic
        private external fun waitFor(processId: Int): Int
    }

    /** 本地 OS 进程号，仅本类内部用于 [kill]/[waitFor]，不对外暴露。 */
    private var pid: Int = -1

    private var fdObj: ParcelFileDescriptor? = null
    override var inputStream: InputStream
    override var outputStream: OutputStream

    init {
        val processIdArray = IntArray(1)
        val fdInt = createSubprocess(
            params.executable, params.cwd,
            params.arguments, params.environment,
            processIdArray, rows, columns, cellWidth, cellHeight
        )

        this.pid = processIdArray[0]
        this.fdObj = ParcelFileDescriptor.adoptFd(fdInt)

        // 隐藏文件描述符细节，直接向上层暴露标准 Java 流
        this.inputStream = FileInputStream(fdObj!!.fileDescriptor)
        this.outputStream = FileOutputStream(fdObj!!.fileDescriptor)
    }

    override fun resize(columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int) {
        val fd = fdObj ?: return
        try {
            setPtyWindowSize(fd.fd, rows, columns, cellWidthPixels, cellHeightPixels)
        } catch (e: IllegalStateException) {
            // 描述符已关闭，忽略本次尺寸调整，避免在退出竞态期间崩溃
        }
    }

    override fun waitFor(): Int = waitFor(pid)

    override fun kill() {
        if (pid > 0) {
            try { Os.kill(pid, OsConstants.SIGKILL) }
            catch (e: ErrnoException) {}
        }
    }

    override fun close() {
        try { fdObj?.close() } catch (e: Exception) {}
    }
}
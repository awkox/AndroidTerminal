package com.awkoo.terminal.core

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.awkoo.terminal.core.CommandInfo
import com.awkoo.terminal.core.ITerminalProcess
import timber.log.Timber
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

class LocalPtyProcess(
    commandInfo: CommandInfo,
    rows: Int,
    columns: Int,
    cellWidth: Int,
    cellHeight: Int
) : ITerminalProcess {

    companion object {
        init { System.loadLibrary("pty") }
        @JvmStatic private external fun createSubprocess(
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
        @JvmStatic private external fun setPtyWindowSize(fd: Int, rows: Int, cols: Int, cellWidth: Int, cellHeight: Int)
        @JvmStatic private external fun waitFor(processId: Int): Int
    }

    override var pid: Int = -1
        private set
        
    private var fdObj: ParcelFileDescriptor? = null
    override var inputStream: InputStream
    override var outputStream: OutputStream

    init {
        val processIdArray = IntArray(1)
        val fdInt = createSubprocess(
            commandInfo.executable, commandInfo.workingDirectory,
            commandInfo.arguments, commandInfo.environmentArray,
            processIdArray, rows, columns, cellWidth, cellHeight
        )
        
        this.pid = processIdArray[0]
        this.fdObj = ParcelFileDescriptor.adoptFd(fdInt)
        
        // 隐藏文件描述符细节，直接向上层暴露标准 Java 流
        this.inputStream = FileInputStream(fdObj!!.fileDescriptor)
        this.outputStream = FileOutputStream(fdObj!!.fileDescriptor)
    }

    override fun resize(columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int) {
        fdObj?.fd?.let { fd ->
            setPtyWindowSize(fd, rows, columns, cellWidthPixels, cellHeightPixels)
        }
    }

    override fun waitFor(): Int = waitFor(pid)

    override fun kill() {
        if (pid > 0) {
            try { Os.kill(pid, OsConstants.SIGKILL) } 
            catch (e: ErrnoException) { Timber.w("Kill failed") }
        }
    }

    override fun close() {
        try { fdObj?.close() } catch (e: Exception) {}
    }
}

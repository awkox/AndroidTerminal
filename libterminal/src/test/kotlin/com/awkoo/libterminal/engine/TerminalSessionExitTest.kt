package com.awkoo.libterminal.engine

import com.awkoo.libterminal.process.ITerminalProcess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * TerminalSession 退出路径：输出排空与退出提示的时序、流不 EOF 的超时兜底。
 *
 * 退出汇合依赖 Dispatchers.Main，JVM 无 Main 调度器，以 Unconfined 注入；
 * 协程运行在真实 IO 线程上，断言用轮询等待而非虚拟时间。
 */
class TerminalSessionExitTest {

    /** 释放阻塞流，保证测试结束时 Reader 线程退出。 */
    private val streamRelease = CountDownLatch(1)

    private class ExitFakeProcess(
        private val exitCode: Int,
        stream: InputStream
    ) : ITerminalProcess {
        override val inputStream: InputStream = stream
        override val outputStream: OutputStream = object : OutputStream() {
            override fun write(b: Int) = Unit
        }

        override fun resize(columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int) = Unit
        override fun waitFor(): Int = exitCode
        override fun kill() = Unit
        override fun close() = Unit
    }

    /** 模拟进程已退出但流未 EOF（PTY 被孤儿进程占用）：读取永久阻塞直到释放。 */
    private inner class BlockingInputStream : InputStream() {
        override fun read(): Int {
            streamRelease.await(30, TimeUnit.SECONDS)
            return -1
        }
    }

    private fun newSession(fake: ITerminalProcess) = TerminalSession(
        id = 1,
        sessionName = MutableStateFlow("exit"),
        maxTranscriptRows = 10,
        processFactory = { _, _, _, _ -> fake }
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        streamRelease.countDown()
        Dispatchers.resetMain()
    }

    private fun awaitCondition(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(20)
        }
        return condition()
    }

    private fun screenText(session: TerminalSession): String = synchronized(session.emulator) {
        (0 until session.emulator.rows).joinToString("\n") { row ->
            String(session.emulator.screen.getRowForRead(row).textChars())
        }
    }

    @Test(timeout = 20_000)
    fun exit_promptFollowsProcessOutput() {
        val fake = ExitFakeProcess(0, ByteArrayInputStream("bye\r\n".toByteArray()))
        val session = newSession(fake)

        session.execute()

        assertTrue(awaitCondition(10_000) { !session.isRunning.value })
        val text = screenText(session)
        assertTrue("missing process output, got: $text", text.contains("bye"))
        assertTrue("missing exit prompt, got: $text", text.contains("Process completed"))
        assertTrue(
            "prompt appeared before output, got: $text",
            text.indexOf("Process completed") > text.indexOf("bye")
        )
    }

    @Test(timeout = 20_000)
    fun exit_streamWithoutEof_completesAfterDrainTimeout() {
        val fake = ExitFakeProcess(0, BlockingInputStream())
        val session = newSession(fake)

        session.execute()

        assertTrue(
            "session never finished with a stream that never reaches EOF",
            awaitCondition(10_000) { !session.isRunning.value }
        )
        assertTrue("missing exit prompt", screenText(session).contains("Process completed"))
    }

    @Test(timeout = 20_000)
    fun exit_normalEof_setsExitStatusAndEnterRequestsRemoval() {
        val fake = ExitFakeProcess(3, ByteArrayInputStream("done\r\n".toByteArray()))
        val session = newSession(fake)

        session.execute()

        assertTrue(awaitCondition(10_000) { !session.isRunning.value })
        assertEquals(3, session.exitStatus)
        val text = screenText(session)
        assertTrue("missing exit status, got: $text", text.contains("Process completed (code 3"))

        session.write("\r")
        assertTrue(session.isRemove.value)
    }
}

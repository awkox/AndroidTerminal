package com.awkoo.libterminal.engine

import com.awkoo.libterminal.process.ITerminalProcess
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch

/**
 * TerminalSession 与假进程的 I/O 管道集成。
 *
 * 假进程的 [waitFor] 永久阻塞，退出路径（依赖 Android Main 调度器）
 * 不在本测试范围内。
 */
class TerminalSessionProcessTest {

    private class FakeProcess(
        private val processOutput: ByteArray
    ) : ITerminalProcess {
        val capturedWrites = ByteArrayOutputStream()
        val resizeCalls = mutableListOf<Triple<Int, Int, Pair<Int, Int>>>()

        override val inputStream: InputStream = ByteArrayInputStream(processOutput)
        override val outputStream: OutputStream = capturedWrites

        override fun resize(columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int) {
            resizeCalls += Triple(columns, rows, cellWidthPixels to cellHeightPixels)
        }

        override fun waitFor(): Int = CountDownLatch(1).await().let { 0 }

        override fun kill() = Unit

        override fun close() = Unit
    }

    private fun newSession(
        stdin: ByteArray? = null,
        processOutput: ByteArray = ByteArray(0)
    ): Pair<TerminalSession, FakeProcess> {
        val fake = FakeProcess(processOutput)
        val session = TerminalSession(
            id = 1,
            sessionName = MutableStateFlow("test"),
            stdin = stdin,
            maxTranscriptRows = 10,
            processFactory = { _, _, _, _ -> fake })
        return session to fake
    }

    /** 轮询等待假进程捕获到包含 [expected] 的写入，超时返回当前累计值。 */
    private fun awaitCaptured(fake: FakeProcess, expected: String, timeoutMs: Long = 5_000): String {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val text = fake.capturedWrites.toString(Charsets.UTF_8.name())
            if (text.contains(expected)) return text
            Thread.sleep(10)
        }
        return fake.capturedWrites.toString(Charsets.UTF_8.name())
    }

    /** 轮询等待模拟器屏幕出现 [expected]。 */
    private fun awaitScreen(session: TerminalSession, row: Int, expected: String, timeoutMs: Long = 5_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val text = synchronized(session.emulator) {
                String(session.emulator.screen.getRowForRead(row).textChars()).trimEnd()
            }
            if (text.contains(expected)) return
            Thread.sleep(10)
        }
        val text = String(session.emulator.screen.getRowForRead(row).textChars()).trimEnd()
        assertTrue("screen row $row never contained '$expected', got '$text'", text.contains(expected))
    }

    @Test
    fun write_beforeRunning_loneNewlineRequestsRemoval() {
        val (session, _) = newSession()

        session.write("x".toByteArray())
        assertFalse(session.isRemove.value)

        session.write("\n".toByteArray())
        assertTrue(session.isRemove.value)
    }

    @Test
    fun write_beforeRunning_carriageReturnRequestsRemoval() {
        val (session, _) = newSession()

        session.write("\r")

        assertTrue(session.isRemove.value)
    }

    @Test
    fun execute_writesInitialStdinToProcess() {
        val (session, fake) = newSession(stdin = "BOOT\n".toByteArray())

        session.execute()

        assertEquals("BOOT\n", awaitCaptured(fake, "BOOT\n"))
    }

    @Test
    fun write_afterExecute_reachesProcess() {
        val (session, fake) = newSession()

        session.execute()
        session.write("ls -la\n")

        assertEquals("ls -la\n", awaitCaptured(fake, "ls -la\n"))
    }

    @Test
    fun writeCodePoint_ascii_encodesSingleByte() {
        val (session, fake) = newSession()

        session.execute()
        session.writeCodePoint(prependEscape = false, rawCodePoint = 'A'.code)

        awaitCaptured(fake, "A")
        assertArrayEquals(byteArrayOf('A'.code.toByte()), fake.capturedWrites.toByteArray())
    }

    @Test
    fun writeCodePoint_cjk_encodesThreeBytes() {
        val (session, fake) = newSession()

        session.execute()
        session.writeCodePoint(prependEscape = false, rawCodePoint = 0x4E2D)

        awaitCaptured(fake, "\u4e2d")
        assertArrayEquals(byteArrayOf(0xE4.toByte(), 0xB8.toByte(), 0xAD.toByte()), fake.capturedWrites.toByteArray())
    }

    @Test
    fun writeCodePoint_supplementary_encodesFourBytes() {
        val (session, fake) = newSession()

        session.execute()
        session.writeCodePoint(prependEscape = false, rawCodePoint = 0x1F600)

        awaitCaptured(fake, String(Character.toChars(0x1F600)))
        assertArrayEquals(
            byteArrayOf(0xF0.toByte(), 0x9F.toByte(), 0x98.toByte(), 0x80.toByte()),
            fake.capturedWrites.toByteArray()
        )
    }

    @Test
    fun writeCodePoint_surrogateRange_isReplacedWithUfffd() {
        val (session, fake) = newSession()

        session.execute()
        session.writeCodePoint(prependEscape = false, rawCodePoint = 0xD800)

        awaitCaptured(fake, "\uFFFD")
        assertArrayEquals(
            byteArrayOf(0xEF.toByte(), 0xBF.toByte(), 0xBD.toByte()),
            fake.capturedWrites.toByteArray()
        )
    }

    @Test
    fun writeCodePoint_prependEscapePrefixesEsc() {
        val (session, fake) = newSession()

        session.execute()
        session.writeCodePoint(prependEscape = true, rawCodePoint = '['.code)

        awaitCaptured("\u001b[") { fake.capturedWrites.toByteArray() }
        assertArrayEquals(
            byteArrayOf(27, '['.code.toByte()),
            fake.capturedWrites.toByteArray()
        )
    }

    private fun awaitCaptured(expected: String, actual: () -> ByteArray) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (String(actual()).contains(expected)) return
            Thread.sleep(10)
        }
        assertTrue("never captured '$expected'", String(actual()).contains(expected))
    }

    @Test
    fun processOutput_isDecodedIntoScreen() {
        val (session, _) = newSession(processOutput = "hello".toByteArray())

        session.execute()

        awaitScreen(session, row = 0, expected = "hello")
    }

    @Test
    fun updateSize_notifiesProcessAndResizesEmulator() {
        val (session, fake) = newSession()
        session.execute()

        session.updateSize(40, 12, 5, 10)

        assertEquals(listOf(Triple(40, 12, 5 to 10)), fake.resizeCalls)
        assertEquals(40, session.emulator.columns)
        assertEquals(12, session.emulator.rows)
    }

    @Test
    fun execute_passesEmulatorDimensionsToFactory() {
        val dims = mutableListOf<List<Int>>()
        val fake = FakeProcess(ByteArray(0))
        val session = TerminalSession(
            id = 1,
            sessionName = MutableStateFlow("test"),
            processFactory = { rows, cols, cellW, cellH ->
                dims += listOf(rows, cols, cellW, cellH)
                fake
            })

        session.execute()

        assertEquals(listOf(listOf(24, 80, 10, 20)), dims)
    }

    @Test
    fun execute_marksSessionRunning() {
        val (session, _) = newSession()

        assertFalse(session.isRunning.value)

        session.execute()

        assertTrue(session.isRunning.value)
    }
}

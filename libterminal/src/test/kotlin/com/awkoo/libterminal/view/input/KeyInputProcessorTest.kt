package com.awkoo.libterminal.view.input

import android.view.KeyEvent
import com.awkoo.libterminal.engine.EmulatorFixture
import com.awkoo.libterminal.engine.TerminalSession
import com.awkoo.libterminal.engine.protocol.KeySequenceEncoder
import com.awkoo.libterminal.process.ITerminalProcess
import com.awkoo.libterminal.view.ExtraKeysModifierSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch

/**
 * 键盘处理链路：本地翻页（Shift+PageUp/Down）与功能键/Ctrl/Alt 码点按终端当前模式写入会话。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KeyInputProcessorTest {

    private class CapturingProcess : ITerminalProcess {
        val capturedWrites = ByteArrayOutputStream()
        private val exitLatch = CountDownLatch(1)

        @Volatile
        var killed = false
            private set

        override val inputStream: InputStream = ByteArrayInputStream(ByteArray(0))
        override val outputStream: OutputStream = capturedWrites
        override fun resize(columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int) = Unit
        override fun waitFor(): Int {
            exitLatch.await()
            return if (killed) -9 else 0
        }

        override fun kill() {
            killed = true
            exitLatch.countDown()
        }

        override fun close() {
            exitLatch.countDown()
        }
    }

    private val f = EmulatorFixture()
    private val scrolls = mutableListOf<Int>()
    private val startedSessions = mutableListOf<TerminalSession>()
    private val processor = KeyInputProcessor(
        sessionProvider = { null },
        emulatorProvider = { f.emulator },
        pokeCursor = {},
        scrollRows = { scrolls += it },
        isSelectingText = { false },
        stopTextSelection = {},
        modifierReader = { ExtraKeysModifierSnapshot() }
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        for (session in startedSessions) {
            session.finishIfRunning()
            awaitSessionStopped(session)
        }
        Dispatchers.resetMain()
    }

    private fun awaitBytes(fake: CapturingProcess, expected: ByteArray): ByteArray {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            val actual = fake.capturedWrites.toByteArray()
            if (actual.size >= expected.size) return actual
            Thread.sleep(10)
        }
        return fake.capturedWrites.toByteArray()
    }

    private fun awaitSessionStopped(session: TerminalSession, timeoutMs: Long = 5_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!session.isRunning.value) return true
            Thread.sleep(10)
        }
        return !session.isRunning.value
    }

    private fun runningSession(): Pair<TerminalSession, CapturingProcess> {
        val fake = CapturingProcess()
        val session = TerminalSession(
            id = 1,
            sessionName = MutableStateFlow("test"),
            processFactory = { _, _, _, _ -> fake }
        )
        startedSessions += session
        session.execute()
        return session to fake
    }

    @Test
    fun shiftPageUp_scrollsViewportBackByPage() {
        assertTrue(processor.handleKeyCode(KeyEvent.KEYCODE_PAGE_UP, KeySequenceEncoder.KEYMOD_SHIFT))

        assertEquals(listOf(-f.rows), scrolls)
    }

    @Test
    fun shiftPageDown_scrollsViewportForwardByPage() {
        assertTrue(processor.handleKeyCode(KeyEvent.KEYCODE_PAGE_DOWN, KeySequenceEncoder.KEYMOD_SHIFT))

        assertEquals(listOf(f.rows), scrolls)
    }

    @Test
    fun pageKeyWithoutShift_isNotViewportScroll() {
        assertFalse(processor.handleKeyCode(KeyEvent.KEYCODE_PAGE_UP, 0))

        assertEquals(emptyList<Int>(), scrolls)
    }

    @Test
    fun handleKeyCode_writesCursorAndKeypadModesToSession() {
        val (session, fake) = runningSession()
        val wired = KeyInputProcessor(
            sessionProvider = { session },
            emulatorProvider = { session.emulator },
            pokeCursor = {},
            scrollRows = {},
            isSelectingText = { false },
            stopTextSelection = {},
            modifierReader = { ExtraKeysModifierSnapshot() }
        )

        session.emulator.append("\u001b[?1h\u001b=".toByteArray(), 7)
        assertTrue(wired.handleKeyCode(KeyEvent.KEYCODE_DPAD_UP, 0))
        assertTrue(wired.handleKeyCode(KeyEvent.KEYCODE_NUMPAD_ENTER, 0))

        val expected = "\u001bOA\u001bOM".toByteArray()
        assertArrayEquals(expected, awaitBytes(fake, expected))
        session.finishIfRunning()
        assertTrue(fake.killed)
        assertTrue(awaitSessionStopped(session))
    }

    @Test
    fun inputCodePoint_appliesSoftImeStickyModifiersAndAltPrefix() {
        val (session, fake) = runningSession()
        var snapshot = ExtraKeysModifierSnapshot(ctrl = true, alt = true)
        val wired = KeyInputProcessor(
            sessionProvider = { session },
            emulatorProvider = { session.emulator },
            pokeCursor = {},
            scrollRows = {},
            isSelectingText = { false },
            stopTextSelection = {},
            modifierReader = { snapshot }
        )

        // SOFT_IME 读取 ExtraKeys 粘性 Ctrl+Alt+'c' -> ESC 0x03
        wired.inputCodePoint(InputSource.SOFT_IME, 'c'.code, controlDownFromEvent = false, leftAltDownFromEvent = false)
        // VIRTUAL_KEY_BAR 不叠加粘性栏修饰，仅按显式 Alt+'x' -> ESC 'x'
        snapshot = ExtraKeysModifierSnapshot(ctrl = true, alt = false)
        wired.inputCodePoint(InputSource.VIRTUAL_KEY_BAR, 'x'.code, controlDownFromEvent = false, leftAltDownFromEvent = true)

        val expected = byteArrayOf(27, 3, 27, 'x'.code.toByte())
        assertArrayEquals(expected, awaitBytes(fake, expected))
        session.finishIfRunning()
        assertTrue(fake.killed)
        assertTrue(awaitSessionStopped(session))
    }
}

package com.awkoo.libterminal.engine

import com.awkoo.libterminal.view.render.TerminalRenderSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class TerminalEmulatorLockAndSnapshotTest {

    @Test
    fun captureRenderSnapshot_syncsDirtyRowsAndIsolatesSubsequentMutations() {
        val f = EmulatorFixture(columns = 10, rows = 4)
        f.feed("row0\r\nrow1")

        val snapshot = TerminalRenderSnapshot()
        snapshot.captureFrom(f.emulator) { 0 }

        assertEquals(4, snapshot.rows)
        assertEquals(10, snapshot.columns)
        assertEquals("row0", String(snapshot.rowAt(0).textChars()).trimEnd())
        assertEquals("row1", String(snapshot.rowAt(1).textChars()).trimEnd())

        val cachedRow0 = snapshot.rowAt(0)
        val cachedRow0Version = cachedRow0.version

        f.feed("!")
        snapshot.captureFrom(f.emulator) { 0 }

        assertSame(cachedRow0, snapshot.rowAt(0))
        assertEquals(cachedRow0Version, snapshot.rowAt(0).version)
        assertEquals("row1!", String(snapshot.rowAt(1).textChars()).trimEnd())

        f.feed("\r\n\u001b[1;1Hmutated")
        assertEquals("row0", String(snapshot.rowAt(0).textChars()).trimEnd())
    }

    @Test
    fun captureRenderSnapshot_syncsPaletteAcrossDifferentEmulators() {
        val f1 = EmulatorFixture(columns = 10, rows = 3)
        val f2 = EmulatorFixture(columns = 10, rows = 3)
        f1.feed("\u001b]4;1;rgb:ff/00/00\u0007")
        f2.feed("\u001b]4;1;rgb:00/ff/00\u0007")

        val snapshot = TerminalRenderSnapshot()
        snapshot.captureFrom(f1.emulator) { 0 }
        assertTrue(snapshot.paletteOverrides.isOverridden(1))
        assertEquals(0xFFFF0000.toInt(), snapshot.paletteOverrides.value(1))

        snapshot.captureFrom(f2.emulator) { 0 }
        assertTrue(snapshot.paletteOverrides.isOverridden(1))
        assertEquals(0xFF00FF00.toInt(), snapshot.paletteOverrides.value(1))
    }

    @Test
    fun deferredResume_preservesFifoOrderAcrossSlicedDrainAndLiveAppend() {
        var resumeCalls = 0
        val emulator = TerminalEmulator(
            writeString = {},
            writeByteArray = {},
            maxTranscriptRows = 20,
            onDeferredResumeRequested = { resumeCalls++ }
        ).apply { resize(20, 4, 10, 20) }

        emulator.setInputPaused(true)
        emulator.append("AB".toByteArray(), 2)
        emulator.append("CD".toByteArray(), 2)
        emulator.setInputPaused(false)

        assertEquals(1, resumeCalls)
        assertTrue(emulator.hasReadyPausedBacklog())
        assertEquals("", String(emulator.screen.getRowForRead(0).textChars()).trimEnd())

        emulator.append("EF".toByteArray(), 2)
        assertTrue(emulator.drainPausedSlice(2))
        assertEquals("AB", String(emulator.screen.getRowForRead(0).textChars()).trimEnd())

        assertTrue(emulator.drainPausedSlice(2))
        assertEquals("ABCD", String(emulator.screen.getRowForRead(0).textChars()).trimEnd())

        assertFalse(emulator.drainPausedSlice(2))
        assertFalse(emulator.hasReadyPausedBacklog())
        assertEquals("ABCDEF", String(emulator.screen.getRowForRead(0).textChars()).trimEnd())
    }

    @Test
    fun pausedQueue_capsMemoryAndRetainsLatestSuffix() {
        val f = EmulatorFixture(columns = 32, rows = 4)
        f.emulator.setInputPaused(true)

        val filler = ByteArray(256 * 1024) { 'x'.code.toByte() }
        repeat(10) {
            f.emulator.append(filler, filler.size)
        }
        val marker = "\r\nTAIL_MARKER".toByteArray()
        f.emulator.append(marker, marker.size)
        f.emulator.setInputPaused(false)

        val screen = (0 until f.rows).joinToString("\n") {
            String(f.emulator.screen.getRowForRead(it).textChars()).trimEnd()
        }
        assertTrue("expected TAIL_MARKER on screen, got:\n$screen", screen.contains("TAIL_MARKER"))
    }

    @Test(timeout = 10_000)
    fun concurrentWriteAndReadSnapshot_noStarvationOrTornRow() {
        val emulator = TerminalEmulator({}, {}, maxTranscriptRows = 200).apply {
            resize(40, 10, 10, 20)
        }
        val stop = AtomicBoolean(false)
        val failure = AtomicReference<Throwable?>(null)
        val writerStarted = CountDownLatch(1)

        val writer = Thread {
            try {
                writerStarted.countDown()
                val line = "0123456789abcdefghijklmnopqrstuvwxyz\r\n".toByteArray()
                var iter = 0
                while (!stop.get() && iter < 2000) {
                    emulator.withWriteLock {
                        var offset = 0
                        while (offset < line.size) {
                            val step = minOf(8, line.size - offset)
                            emulator.append(line, offset, step)
                            offset += step
                            if (emulator.hasPendingLockWaiter()) break
                        }
                        if (offset < line.size) {
                            emulator.append(line, offset, line.size - offset)
                        }
                    }
                    iter++
                }
            } catch (t: Throwable) {
                failure.compareAndSet(null, t)
            }
        }

        writer.start()
        assertTrue(writerStarted.await(2, TimeUnit.SECONDS))

        val snapshot = TerminalRenderSnapshot()
        var snapshotsCaptured = 0
        while (writer.isAlive && snapshotsCaptured < 200) {
            snapshot.captureFrom(emulator) { 0 }
            for (r in 0 until snapshot.rows) {
                val chars = snapshot.rowAt(r).textChars()
                assertTrue(chars.size >= snapshot.columns)
            }
            snapshotsCaptured++
            Thread.yield()
        }

        stop.set(true)
        writer.join(5_000)
        assertFalse(writer.isAlive)
        failure.get()?.let { throw it }
        assertTrue(snapshotsCaptured > 0)
    }
}

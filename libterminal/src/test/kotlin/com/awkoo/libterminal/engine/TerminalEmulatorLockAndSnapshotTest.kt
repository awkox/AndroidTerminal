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
    fun captureFromFrozen_isolatesRenderSnapshotAcrossConcurrentWrites() {
        val f = EmulatorFixture(columns = 20, rows = 4, maxTranscriptRows = 20)
        f.feed("first line\r\nsecond line")

        val frozen = f.emulator.withWriteLock { f.emulator.captureFrozenSnapshot() }
        try {
            f.emulator.withWriteLock {
                repeat(10) { idx ->
                    val line = "\r\nlive_$idx".toByteArray()
                    f.emulator.append(line, line.size)
                }
            }

            val renderSnapshot = TerminalRenderSnapshot()
            renderSnapshot.captureFromFrozen(frozen) { 0 }
            assertEquals("first line", String(renderSnapshot.rowAt(0).textChars()).trimEnd())
            assertEquals("second line", String(renderSnapshot.rowAt(1).textChars()).trimEnd())
        } finally {
            f.emulator.withWriteLock { f.emulator.releaseFrozenSnapshot(frozen) }
        }
    }

    @Test
    fun nestedFrozenSnapshots_keepRowSharedUntilAllReleased() {
        val f = EmulatorFixture(columns = 16, rows = 4)
        f.feed("shared")
        val originalRow = f.emulator.screen.getRowForRead(0)

        val snap1 = f.emulator.captureFrozenSnapshot()
        val snap2 = f.emulator.captureFrozenSnapshot()
        assertTrue(originalRow.isSharedWithSnapshot)

        f.emulator.releaseFrozenSnapshot(snap1)
        assertTrue(originalRow.isSharedWithSnapshot)

        f.feed("_mut")
        assertEquals("shared", String(snap2.buffer.getRowForRead(0).textChars()).trimEnd())
        assertEquals("shared_mut", f.rowText(0))

        f.emulator.releaseFrozenSnapshot(snap2)
        assertFalse(originalRow.isSharedWithSnapshot)
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

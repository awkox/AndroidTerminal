package com.awkoo.libterminal.engine

/**
 * 模拟器测试夹具：固定尺寸的小屏 + 捕获输出回调。
 *
 * 捕获分两路：[writtenStrings]（writeString 回调，转义序列响应）与
 * [writtenBytes]（writeByteArray 回调，X10 鼠标/DSR 等字节序列）。
 */
internal class EmulatorFixture(
    val columns: Int = 10,
    val rows: Int = 5,
    maxTranscriptRows: Int = 100
) {
    val writtenStrings = mutableListOf<String>()
    val writtenBytes = mutableListOf<ByteArray>()

    val emulator: TerminalEmulator = TerminalEmulator(
        { writtenStrings += it },
        { writtenBytes += it },
        maxTranscriptRows
    ).also { it.resize(columns, rows, 10, 20) }

    /** 全部 writeString 输出的拼接。 */
    val writtenText: String get() = writtenStrings.joinToString("")

    fun clearWritten() {
        writtenStrings.clear()
        writtenBytes.clear()
    }

    fun feed(text: String) {
        val bytes = text.toByteArray()
        emulator.append(bytes, bytes.size)
    }

    fun rowText(row: Int): String =
        String(emulator.screen.getRowForRead(row).textChars()).trimEnd()

    fun screenText(): List<String> = (0 until rows).map(::rowText)

    fun wrapFlags(): List<Boolean> = (0 until rows).map(emulator.screen::getLineWrap)
}

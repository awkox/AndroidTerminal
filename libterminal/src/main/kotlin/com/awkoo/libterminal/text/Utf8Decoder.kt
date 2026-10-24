package com.awkoo.libterminal.text

/**
 * UTF-8 字节流解码器。
 *
 * 逐字节接收输入，自动组装多字节序列并输出完整的 Unicode 码点。
 * 遇到过长编码、非法字节或孤立代理项时，替换为 U+FFFD（替换字符）。
 */
internal class Utf8Decoder(private val onCodePoint: (Int) -> Unit) {

    companion object {
        const val UNICODE_REPLACEMENT_CHAR: Int = 0xFFFD
    }

    private var utf8ToFollow = 0
    private var utf8Index = 0
    private val sequenceBuffer = ByteArray(4)

    fun decode(buffer: ByteArray, length: Int) {
        for (i in 0 until length) {
            val b = buffer[i]
            if (utf8ToFollow == 0 && b >= 0) {
                onCodePoint(b.toInt())
            } else {
                processByte(b)
            }
        }
    }

    private fun processByte(byteToProcess: Byte) {
        val b = byteToProcess.toInt()
        if (utf8ToFollow > 0) {
            if ((b and 192) == 128) {
                // 10xxxxxx，UTF-8 后续字节
                sequenceBuffer[utf8Index++] = byteToProcess
                if (--utf8ToFollow == 0) {
                    val firstByteMask = when (utf8Index) {
                        2 -> 31
                        3 -> 15
                        else -> 7
                    }.toByte()
                    var codePoint = (sequenceBuffer[0].toInt() and firstByteMask.toInt())
                    for (i in 1 until utf8Index)
                        codePoint = ((codePoint shl 6) or (sequenceBuffer[i].toInt() and 63))
                    if ((codePoint <= 127 && utf8Index > 1) ||
                        (codePoint < 2048 && utf8Index > 2) ||
                        (codePoint < 65536 && utf8Index > 3)) {
                        // 过长编码（overlong encoding），视为非法
                        codePoint = UNICODE_REPLACEMENT_CHAR
                    }

                    utf8ToFollow = 0
                    utf8Index = 0

                    if (codePoint in 0x80..0x9F) {
                        // C1 控制字符，直接忽略
                    } else {
                        when (Character.getType(codePoint).toByte()) {
                            Character.UNASSIGNED,
                            Character.SURROGATE ->
                                codePoint = UNICODE_REPLACEMENT_CHAR
                        }
                        onCodePoint(codePoint)
                    }
                }
            } else {
                // 非 UTF-8 后续字节，重置状态并替换
                utf8ToFollow = 0
                utf8Index = 0
                onCodePoint(UNICODE_REPLACEMENT_CHAR)
                processByte(byteToProcess)
            }
        } else {
            utf8ToFollow = when {
                (b and 128) == 0 -> {
                    onCodePoint(b)
                    return
                }
                (b and 224) == 192 -> 1
                (b and 240) == 224 -> 2
                (b and 248) == 240 -> 3
                else -> {
                    onCodePoint(UNICODE_REPLACEMENT_CHAR)
                    return
                }
            }
            sequenceBuffer[utf8Index++] = byteToProcess
        }
    }

    fun reset() {
        utf8ToFollow = 0
        utf8Index = 0
    }
}
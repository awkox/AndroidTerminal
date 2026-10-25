package com.awkoo.libterminal.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Utf8DecoderTest {

    private val emitted = mutableListOf<Int>()
    private val decoder = Utf8Decoder { emitted += it }

    private fun decode(bytes: List<Byte>) {
        val array = bytes.toByteArray()
        decoder.decode(array, array.size)
    }

    private fun decode(vararg bytes: Int) = decode(bytes.map { it.toByte() })

    @Test
    fun ascii_isForwardedAsIs() {
        decode(0x41, 0x42)

        assertEquals(listOf(0x41, 0x42), emitted)
    }

    @Test
    fun twoByteSequence_isAssembled() {
        decode(0xC3, 0xA9)

        assertEquals(listOf(0xE9), emitted)
    }

    @Test
    fun threeByteCjk_isAssembled() {
        decode(0xE4, 0xB8, 0xAD)

        assertEquals(listOf(0x4E2D), emitted)
    }

    @Test
    fun fourByteSupplementary_isAssembled() {
        decode(0xF0, 0x9F, 0x98, 0x80)

        assertEquals(listOf(0x1F600), emitted)
    }

    @Test
    fun sequenceSplitAcrossDecodeCalls_isStitched() {
        decode(0xE4)
        assertTrue(emitted.isEmpty())

        decode(0xB8, 0xAD)

        assertEquals(listOf(0x4E2D), emitted)
    }

    @Test
    fun overlongEncoding_isReplaced() {
        decode(0xC0, 0x80)

        assertEquals(listOf(Utf8Decoder.UNICODE_REPLACEMENT_CHAR), emitted)
    }

    @Test
    fun overlongThreeByteEncoding_isReplaced() {
        decode(0xE0, 0x80, 0xAF)

        assertEquals(listOf(Utf8Decoder.UNICODE_REPLACEMENT_CHAR), emitted)
    }

    @Test
    fun invalidContinuationByte_isReplacedAndByteReprocessed() {
        decode(0xE4, 0x28)

        assertEquals(listOf(Utf8Decoder.UNICODE_REPLACEMENT_CHAR, 0x28), emitted)
    }

    @Test
    fun loneContinuationByte_isReplaced() {
        decode(0x80)

        assertEquals(listOf(Utf8Decoder.UNICODE_REPLACEMENT_CHAR), emitted)
    }

    @Test
    fun strayLeadingByte_isReplaced() {
        decode(0xFE)

        assertEquals(listOf(Utf8Decoder.UNICODE_REPLACEMENT_CHAR), emitted)
    }

    @Test
    fun encodedSurrogate_isReplaced() {
        decode(0xED, 0xA0, 0x80)

        assertEquals(listOf(Utf8Decoder.UNICODE_REPLACEMENT_CHAR), emitted)
    }

    @Test
    fun c1Control_isSwallowed() {
        decode(0xC2, 0x85)

        assertTrue(emitted.isEmpty())
    }

    @Test
    fun reset_midSequenceDropsPartialState() {
        decode(0xE4)
        decoder.reset()

        decode(0x41)

        assertEquals(listOf(0x41), emitted)
    }

    @Test
    fun mixedStream_decodesInOrder() {
        decode(0x61, 0xE4, 0xB8, 0xAD, 0x62)

        assertEquals(listOf(0x61, 0x4E2D, 0x62), emitted)
    }
}

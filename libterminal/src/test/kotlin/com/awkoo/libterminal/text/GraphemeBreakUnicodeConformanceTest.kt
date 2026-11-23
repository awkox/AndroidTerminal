package com.awkoo.libterminal.text

import com.awkoo.libterminal.engine.EmulatorFixture
import com.awkoo.libterminal.text.GraphemeBreak.GcbClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader

class GraphemeBreakUnicodeConformanceTest {

    private fun openUcd(name: String): BufferedReader {
        val stream = requireNotNull(
            GraphemeBreakUnicodeConformanceTest::class.java.classLoader?.getResourceAsStream("unicode/$name")
        ) { "Missing Unicode 16.0.0 test resource: unicode/$name" }
        return BufferedReader(InputStreamReader(stream, Charsets.UTF_8))
    }

    private fun parsePropertyBitSet(fileName: String, predicate: (List<String>) -> Boolean): BooleanArray {
        val flags = BooleanArray(Character.MAX_CODE_POINT + 1)
        openUcd(fileName).useLines { lines ->
            for (raw in lines) {
                val content = raw.substringBefore('#').trim()
                if (content.isEmpty()) continue
                val parts = content.split(';').map { it.trim() }
                if (parts.size < 2 || !predicate(parts.drop(1))) continue
                val range = parts[0]
                val dotIdx = range.indexOf("..")
                val start: Int
                val end: Int
                if (dotIdx >= 0) {
                    start = range.substring(0, dotIdx).toInt(16)
                    end = range.substring(dotIdx + 2).toInt(16)
                } else {
                    start = range.toInt(16)
                    end = start
                }
                for (cp in start..end) flags[cp] = true
            }
        }
        return flags
    }

    private fun parseAllGcbClasses(): Array<GcbClass> {
        val classes = Array(Character.MAX_CODE_POINT + 1) { GcbClass.OTHER }
        openUcd("GraphemeBreakProperty.txt").useLines { lines ->
            for (raw in lines) {
                val content = raw.substringBefore('#').trim()
                if (content.isEmpty()) continue
                val sep = content.indexOf(';')
                if (sep < 0) continue
                val range = content.substring(0, sep).trim()
                val gcb = when (content.substring(sep + 1).trim()) {
                    "CR" -> GcbClass.CR
                    "LF" -> GcbClass.LF
                    "Control" -> GcbClass.CONTROL
                    "Extend" -> GcbClass.EXTEND
                    "ZWJ" -> GcbClass.ZWJ
                    "Regional_Indicator" -> GcbClass.REGIONAL_INDICATOR
                    "Prepend" -> GcbClass.PREPEND
                    "SpacingMark" -> GcbClass.SPACING_MARK
                    "L" -> GcbClass.L
                    "V" -> GcbClass.V
                    "T" -> GcbClass.T
                    "LV" -> GcbClass.LV
                    "LVT" -> GcbClass.LVT
                    else -> GcbClass.OTHER
                }
                val dotIdx = range.indexOf("..")
                val start: Int
                val end: Int
                if (dotIdx >= 0) {
                    start = range.substring(0, dotIdx).toInt(16)
                    end = range.substring(dotIdx + 2).toInt(16)
                } else {
                    start = range.toInt(16)
                    end = start
                }
                for (cp in start..end) classes[cp] = gcb
            }
        }
        return classes
    }

    @Test
    fun allUnicodeCodePoints_matchUnicode160PropertiesBitForBit() {
        val expectedGcb = parseAllGcbClasses()
        val expectedExtPict = parsePropertyBitSet("emoji-data.txt") { it[0] == "Extended_Pictographic" }
        val expectedModBase = parsePropertyBitSet("emoji-data.txt") { it[0] == "Emoji_Modifier_Base" }
        val expectedIncbConsonant = parsePropertyBitSet("DerivedCoreProperties.txt") {
            it.size >= 2 && it[0] == "InCB" && it[1] == "Consonant"
        }
        val expectedIncbLinker = parsePropertyBitSet("DerivedCoreProperties.txt") {
            it.size >= 2 && it[0] == "InCB" && it[1] == "Linker"
        }
        val expectedIncbExtend = parsePropertyBitSet("DerivedCoreProperties.txt") {
            it.size >= 2 && it[0] == "InCB" && it[1] == "Extend"
        }

        for (cp in 0..Character.MAX_CODE_POINT) {
            val gcb = GraphemeBreak.gcbClass(cp)
            assertEquals("GCB mismatch at U+${cp.toString(16)}", expectedGcb[cp], gcb)
            assertEquals(
                "Extended_Pictographic mismatch at U+${cp.toString(16)}",
                expectedExtPict[cp],
                GraphemeBreak.isExtendedPictographic(cp)
            )
            assertEquals(
                "Emoji_Modifier_Base mismatch at U+${cp.toString(16)}",
                expectedModBase[cp],
                GraphemeBreak.isEmojiModifierBase(cp)
            )
            assertEquals(
                "InCB=Consonant mismatch at U+${cp.toString(16)}",
                expectedIncbConsonant[cp],
                GraphemeBreak.isInCBConsonant(cp)
            )
            assertEquals(
                "InCB=Linker mismatch at U+${cp.toString(16)}",
                expectedIncbLinker[cp],
                GraphemeBreak.isInCBLinker(cp)
            )
            assertEquals(
                "InCB=Extend mismatch at U+${cp.toString(16)}",
                expectedIncbExtend[cp],
                GraphemeBreak.isInCBExtend(cp)
            )

            val width = WcWidth.width(cp)
            assertTrue("WcWidth out of [0,2] at U+${cp.toString(16)}: $width", width in 0..2)

            if (gcb == GcbClass.PREPEND) {
                assertEquals(
                    "GB9b Prepend must occupy column width >= 1 to enter buffer: U+${cp.toString(16)}",
                    1,
                    width
                )
            }

            val expectedControlDrop = cp != 0x00AD &&
                (gcb == GcbClass.CONTROL || gcb == GcbClass.CR || gcb == GcbClass.LF)
            assertEquals(
                "isControlOrFormatBreak mismatch at U+${cp.toString(16)}",
                expectedControlDrop,
                GraphemeBreak.isControlOrFormatBreak(cp)
            )
        }
    }

    @Test
    fun officialGraphemeBreakTestTxt_matchesTerminalUax29Semantics() {
        var totalCases = 0
        var crLfExemptions = 0
        var uts51WellFormedExemptions = 0

        openUcd("GraphemeBreakTest.txt").useLines { lines ->
            for (raw in lines) {
                val data = raw.substringBefore('#').trim()
                if (data.isEmpty()) continue
                totalCases++

                val tokens = data.split(Regex("\\s+"))
                val codePoints = IntArray((tokens.size - 1) / 2) { i ->
                    tokens[i * 2 + 1].toInt(16)
                }
                val expectedBreaks = BooleanArray(codePoints.size + 1) { i ->
                    tokens[i * 2] == "÷"
                }

                val actualBreaks = BooleanArray(codePoints.size + 1)
                actualBreaks[0] = true
                var currentCluster = StringBuilder().appendCodePoint(codePoints[0])
                for (i in 1 until codePoints.size) {
                    val nextCp = codePoints[i]
                    val merge = GraphemeBreak.shouldMerge(currentCluster.toString().toCharArray(), nextCp)
                    actualBreaks[i] = !merge
                    if (merge) {
                        currentCluster.appendCodePoint(nextCp)
                    } else {
                        currentCluster = StringBuilder().appendCodePoint(nextCp)
                    }
                }
                actualBreaks[codePoints.size] = true

                var mismatchIdx = -1
                for (i in expectedBreaks.indices) {
                    if (expectedBreaks[i] != actualBreaks[i]) {
                        mismatchIdx = i
                        break
                    }
                }
                if (mismatchIdx >= 0) {
                    val prevCp = codePoints[mismatchIdx - 1]
                    val nextCp = codePoints[mismatchIdx]
                    when {
                        prevCp == 0x0D && nextCp == 0x0A -> crLfExemptions++
                        WcWidth.isEmojiModifier(nextCp) && !expectedBreaks[mismatchIdx] -> uts51WellFormedExemptions++
                        else -> throw AssertionError(
                            "Unexpected UAX #29 mismatch on case: $raw (at boundary $mismatchIdx: U+${prevCp.toString(16)} / U+${nextCp.toString(16)})"
                        )
                    }
                }
            }
        }

        assertEquals(1093, totalCases)
        assertEquals(2, crLfExemptions)
        assertEquals(2, uts51WellFormedExemptions)
    }

    @Test
    fun officialGraphemeBreakTestTxt_throughTerminalEmulator_preservesClusters() {
        var executedCases = 0
        openUcd("GraphemeBreakTest.txt").useLines { lines ->
            for (raw in lines) {
                val data = raw.substringBefore('#').trim()
                if (data.isEmpty()) continue
                val tokens = data.split(Regex("\\s+"))
                val codePoints = IntArray((tokens.size - 1) / 2) { i ->
                    tokens[i * 2 + 1].toInt(16)
                }
                val expectedBreaks = BooleanArray(codePoints.size + 1) { i ->
                    tokens[i * 2] == "÷"
                }

                if (codePoints.any { cp ->
                        val type = Character.getType(cp).toByte()
                        type == Character.UNASSIGNED ||
                            type == Character.SURROGATE ||
                            GraphemeBreak.isControlOrFormatBreak(cp) ||
                            cp == 0x00AD ||
                            WcWidth.isEmojiModifier(cp) ||
                            GraphemeBreak.isEmojiTagChar(cp)
                    }
                ) {
                    continue
                }

                val expectedClusters = mutableListOf<String>()
                var sb = StringBuilder().appendCodePoint(codePoints[0])
                var allHeadsHaveColumnWidth = WcWidth.width(codePoints[0]) >= 1
                for (i in 1 until codePoints.size) {
                    if (expectedBreaks[i]) {
                        expectedClusters += sb.toString()
                        sb = StringBuilder().appendCodePoint(codePoints[i])
                        if (WcWidth.width(codePoints[i]) <= 0) {
                            allHeadsHaveColumnWidth = false
                            break
                        }
                    } else {
                        sb.appendCodePoint(codePoints[i])
                    }
                }
                expectedClusters += sb.toString()
                if (!allHeadsHaveColumnWidth) continue

                executedCases++
                val fixture = EmulatorFixture(columns = 120, rows = 2)
                val input = buildString {
                    for (cp in codePoints) appendCodePoint(cp)
                }
                fixture.feed(input)

                val row0 = fixture.emulator.screen.getRowForRead(0)
                val actualClusters = mutableListOf<String>()
                var widthSum = 0
                val text = row0.textChars()
                row0.forEachCell { col, charStart, charEnd, width, _, _ ->
                    if (col < fixture.emulator.cursorCol) {
                        actualClusters += String(text, charStart, charEnd - charStart)
                        widthSum += width
                    }
                    col < fixture.emulator.cursorCol
                }

                assertEquals("Cluster split mismatch in TerminalEmulator for: $raw", expectedClusters, actualClusters)
                assertEquals("Cursor col mismatch for: $raw", widthSum, fixture.emulator.cursorCol)
            }
        }
        assertTrue("Expected substantial UCD end-to-end coverage, ran $executedCases", executedCases > 400)
    }

    @Test
    fun mayMergeAndShouldExpand_satisfyImplicationsOverRepresentativeCartesianMatrix() {
        val contexts = listOf(
            "a",
            "\r",
            "\n",
            "\u00AD",
            "\u0301",
            "\u200B",
            "\u200C",
            "\u200D",
            "\u0600",
            "\u0600\u0600",
            "\u0600\u0301",
            "\u093E",
            "\u1100",
            "\u1100\u0301",
            "\u1100\u1161",
            "\uAC00",
            "\uAC01",
            "\u0915",
            "\u0915\u094D",
            "\u0915\u093C\u094D",
            "\u0915\u094D\u200D",
            "\u0915\u094D\u200C",
            "\u0915\u094D\u093E",
            "\u2764",
            "\u2764\u0301",
            "\u2764\uFE0E",
            "1",
            "1\uFE0F",
            "1\u0301",
            "\uD83D\uDC4D",
            "\u261D\uFE0F",
            "\uD83D\uDE00",
            "\uD83D\uDC68\u200D",
            "\uD83C\uDFF3\uFE0F\u0301\u200D",
            "\uD83D\uDC68\u093E\u200D",
            "a\u200D",
            "\uD83C\uDFF4",
            "\uD83C\uDFF4\uDB40\uDC67",
            "\uD83C\uDFF4\uDB40\uDC67\uDB40\uDC7F",
            "\uD83C\uDDE8",
            "\uD83C\uDDE8\uD83C\uDDF3"
        )

        val candidates = intArrayOf(
            0x000D, 0x000A, 0x0000, 0x00AD, 0xFFF0,
            'a'.code, '1'.code, 0x4E2D,
            0x0301, 0x200C, 0x200D, 0xFE0E, 0xFE0F, 0x20E3,
            0x0600, 0x113D1,
            0x093E, 0x0903, 0x0CC0,
            0x1100, 0x1161, 0x16D63, 0x11A8, 0xAC00, 0xAC01,
            0x0915, 0x0937, 0x094D,
            0x2764, 0x1F600, 0x1F46A, 0x1F3FD, 0x1F3F4, 0xE0067, 0xE007F,
            0x1F1E8, 0x1F900
        )

        for (ctx in contexts) {
            val cellChars = ctx.toCharArray()
            val lastCp = Character.codePointBefore(cellChars, cellChars.size)
            val prevEndsWithPrepend = GraphemeBreak.isPrepend(lastCp)
            for (nextCp in candidates) {
                val merge = GraphemeBreak.shouldMerge(cellChars, nextCp)
                if (merge) {
                    assertTrue(
                        "mayMerge false when shouldMerge is true: ctx=$ctx next=U+${nextCp.toString(16)}",
                        GraphemeBreak.mayMerge(nextCp, prevEndsWithPrepend)
                    )
                }
                if (GraphemeBreak.shouldExpandToWide(cellChars, 1, nextCp)) {
                    assertTrue(
                        "shouldExpandToWide true when shouldMerge is false: ctx=$ctx next=U+${nextCp.toString(16)}",
                        merge
                    )
                }
            }
        }
    }
}

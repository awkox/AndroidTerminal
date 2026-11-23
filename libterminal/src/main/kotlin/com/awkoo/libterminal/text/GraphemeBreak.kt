package com.awkoo.libterminal.text

/**
 * Unicode UAX #29 与 UTS #51 字素簇边界判定及宽字符扩展判定。
 *
 * 严格区分控制/格式断簇字符（Control）与可附加延伸字符（Extend/ZWJ），
 * 并实现 GB4/GB5、GB6/GB7/GB8（韩文连写字母）、GB9/GB9c（印度系辅音连字）、
 * GB11（ZWJ 表情序列）、GB12/GB13（区域指示符旗帜）、肤色修饰基字符、
 * 细分旗帜 Tag 序列及 VS16/Keycap 扩展规则。
 */
internal object GraphemeBreak {

    private val EXTENDED_PICTOGRAPHIC = intArrayOf(
        0x000A9, 0x000A9,
        0x000AE, 0x000AE,
        0x0203C, 0x0203C,
        0x02049, 0x02049,
        0x02122, 0x02122,
        0x02139, 0x02139,
        0x02194, 0x02199,
        0x021A9, 0x021AA,
        0x0231A, 0x0231B,
        0x02328, 0x02328,
        0x02388, 0x02388,
        0x023CF, 0x023CF,
        0x023E9, 0x023F3,
        0x023F8, 0x023FA,
        0x024C2, 0x024C2,
        0x025AA, 0x025AB,
        0x025B6, 0x025B6,
        0x025C0, 0x025C0,
        0x025FB, 0x025FE,
        0x02600, 0x02605,
        0x02607, 0x02612,
        0x02614, 0x02685,
        0x02690, 0x02705,
        0x02708, 0x02712,
        0x02714, 0x02714,
        0x02716, 0x02716,
        0x0271D, 0x0271D,
        0x02721, 0x02721,
        0x02728, 0x02728,
        0x02733, 0x02734,
        0x02744, 0x02744,
        0x02747, 0x02747,
        0x0274C, 0x0274C,
        0x0274E, 0x0274E,
        0x02753, 0x02755,
        0x02757, 0x02757,
        0x02763, 0x02767,
        0x02795, 0x02797,
        0x027A1, 0x027A1,
        0x027B0, 0x027B0,
        0x027BF, 0x027BF,
        0x02934, 0x02935,
        0x02B05, 0x02B07,
        0x02B1B, 0x02B1C,
        0x02B50, 0x02B50,
        0x02B55, 0x02B55,
        0x03030, 0x03030,
        0x0303D, 0x0303D,
        0x03297, 0x03297,
        0x03299, 0x03299,
        0x1F000, 0x1F0FF,
        0x1F10D, 0x1F10F,
        0x1F12F, 0x1F12F,
        0x1F16C, 0x1F171,
        0x1F17E, 0x1F17F,
        0x1F18E, 0x1F18E,
        0x1F191, 0x1F19A,
        0x1F1AD, 0x1F1E5,
        0x1F201, 0x1F20F,
        0x1F21A, 0x1F21A,
        0x1F22F, 0x1F22F,
        0x1F232, 0x1F23A,
        0x1F23C, 0x1F23F,
        0x1F249, 0x1F3FA,
        0x1F400, 0x1F53D,
        0x1F546, 0x1F64F,
        0x1F680, 0x1F6FF,
        0x1F774, 0x1F77F,
        0x1F7D5, 0x1F7FF,
        0x1F80C, 0x1F80F,
        0x1F848, 0x1F84F,
        0x1F85A, 0x1F85F,
        0x1F888, 0x1F88F,
        0x1F8AE, 0x1F8FF,
        0x1F900, 0x1F9FF,
        0x1FA00, 0x1FAFF,
        0x1FC00, 0x1FFFD
    ).also { checkSortedRangeTable(it, "EXTENDED_PICTOGRAPHIC") }

    private val EMOJI_MODIFIER_BASE = intArrayOf(
        0x0261D, 0x0261D,
        0x026F9, 0x026F9,
        0x0270A, 0x0270D,
        0x1F385, 0x1F385,
        0x1F3C2, 0x1F3C4,
        0x1F3C7, 0x1F3C7,
        0x1F3CA, 0x1F3CC,
        0x1F442, 0x1F443,
        0x1F446, 0x1F450,
        0x1F466, 0x1F469,
        0x1F46B, 0x1F46E,
        0x1F470, 0x1F478,
        0x1F47C, 0x1F47C,
        0x1F481, 0x1F483,
        0x1F485, 0x1F487,
        0x1F48F, 0x1F48F,
        0x1F491, 0x1F491,
        0x1F4AA, 0x1F4AA,
        0x1F574, 0x1F575,
        0x1F57A, 0x1F57A,
        0x1F590, 0x1F590,
        0x1F595, 0x1F596,
        0x1F645, 0x1F647,
        0x1F64B, 0x1F64F,
        0x1F6A3, 0x1F6A3,
        0x1F6B4, 0x1F6B6,
        0x1F6C0, 0x1F6C0,
        0x1F6CC, 0x1F6CC,
        0x1F90C, 0x1F90C,
        0x1F90F, 0x1F90F,
        0x1F918, 0x1F91F,
        0x1F926, 0x1F926,
        0x1F930, 0x1F939,
        0x1F93D, 0x1F93E,
        0x1F977, 0x1F977,
        0x1F9B5, 0x1F9B6,
        0x1F9B8, 0x1F9B9,
        0x1F9BB, 0x1F9BB,
        0x1F9CD, 0x1F9CF,
        0x1F9D1, 0x1F9DD,
        0x1FAC3, 0x1FAC5,
        0x1FAF0, 0x1FAF8
    ).also { checkSortedRangeTable(it, "EMOJI_MODIFIER_BASE") }

    private val INCB_CONSONANT = intArrayOf(
        0x00915, 0x00939,
        0x00958, 0x0095F,
        0x00978, 0x0097F,
        0x00995, 0x009A8,
        0x009AA, 0x009B0,
        0x009B2, 0x009B2,
        0x009B6, 0x009B9,
        0x009DC, 0x009DD,
        0x009DF, 0x009DF,
        0x009F0, 0x009F1,
        0x00A95, 0x00AA8,
        0x00AAA, 0x00AB0,
        0x00AB2, 0x00AB3,
        0x00AB5, 0x00AB9,
        0x00AF9, 0x00AF9,
        0x00B15, 0x00B28,
        0x00B2A, 0x00B30,
        0x00B32, 0x00B33,
        0x00B35, 0x00B39,
        0x00B5C, 0x00B5D,
        0x00B5F, 0x00B5F,
        0x00B71, 0x00B71,
        0x00C15, 0x00C28,
        0x00C2A, 0x00C39,
        0x00C58, 0x00C5A,
        0x00D15, 0x00D3A
    ).also { checkSortedRangeTable(it, "INCB_CONSONANT") }

    private fun checkSortedRangeTable(table: IntArray, name: String) {
        require(table.size % 2 == 0) { "$name array length must be even" }
        var prevEnd = -1
        for (i in table.indices step 2) {
            val start = table[i]
            val end = table[i + 1]
            require(start <= end && start > prevEnd) { "$name ranges must be sorted and disjoint" }
            prevEnd = end
        }
    }

    /** UAX #29 GB4/GB5：必须无条件断开字素簇边界、不得合并到前格的控制与格式字符。 */
    fun isControlOrFormatBreak(cp: Int): Boolean =
        cp < 0x20 ||
            cp in 0x007F..0x009F ||
            cp == 0x061C ||
            cp == 0x180E ||
            cp == 0x200B ||
            cp in 0x200E..0x200F ||
            cp in 0x2028..0x202E ||
            cp in 0x2060..0x206F ||
            cp == 0xFEFF ||
            cp in 0xFFF9..0xFFFB ||
            cp in 0x13430..0x1343F ||
            cp in 0x1BCA0..0x1BCA3 ||
            cp in 0x1D173..0x1D17A ||
            cp in 0xE0000..0xE001F ||
            cp in 0xE0080..0xE00FF ||
            cp in 0xE01F0..0xE0FFF

    fun isEmojiTagChar(cp: Int): Boolean = cp in 0xE0020..0xE007F

    fun isExtendedPictographic(cp: Int): Boolean =
        WcWidth.inRangeTable(EXTENDED_PICTOGRAPHIC, cp)

    fun isEmojiModifierBase(cp: Int): Boolean =
        WcWidth.inRangeTable(EMOJI_MODIFIER_BASE, cp)

    fun isHangulL(cp: Int): Boolean =
        cp in 0x1100..0x115F || cp in 0xA960..0xA97C

    fun isHangulV(cp: Int): Boolean =
        cp in 0x1160..0x11A7 || cp in 0xD7B0..0xD7C6

    fun isHangulT(cp: Int): Boolean =
        cp in 0x11A8..0x11FF || cp in 0xD7CB..0xD7FB

    fun isHangulLV(cp: Int): Boolean =
        cp in 0xAC00..0xD7A3 && (cp - 0xAC00) % 28 == 0

    fun isHangulLVT(cp: Int): Boolean =
        cp in 0xAC00..0xD7A3 && (cp - 0xAC00) % 28 != 0

    fun isInCBLinker(cp: Int): Boolean =
        cp == 0x094D || cp == 0x09CD || cp == 0x0ACD || cp == 0x0B4D || cp == 0x0C4D || cp == 0x0D4D

    fun isInCBConsonant(cp: Int): Boolean =
        WcWidth.inRangeTable(INCB_CONSONANT, cp)

    private fun isInCBExtend(cp: Int): Boolean =
        !isControlOrFormatBreak(cp) &&
            !isInCBLinker(cp) &&
            !isEmojiTagChar(cp) &&
            WcWidth.width(cp) <= 0

    private fun isGb11Extend(cp: Int): Boolean =
        !isControlOrFormatBreak(cp) &&
            cp != 0x200D &&
            (WcWidth.isEmojiModifier(cp) || (!isEmojiTagChar(cp) && WcWidth.width(cp) <= 0))

    /**
     * 快速预筛：ASCII 可打印字符及常规 CJK/单宽字符绝不会并入前一格，避免在主路径上复制单元格。
     */
    fun mayMerge(cp: Int, displayWidth: Int): Boolean {
        if (displayWidth <= 0) return true
        if (cp in 0x20..0x7E) return false
        return WcWidth.isEmojiModifier(cp) ||
            WcWidth.isRegionalIndicator(cp) ||
            isExtendedPictographic(cp) ||
            isHangulL(cp) ||
            isHangulV(cp) ||
            isHangulT(cp) ||
            isHangulLV(cp) ||
            isHangulLVT(cp) ||
            isInCBConsonant(cp)
    }

    /**
     * 判断 [nextCp] 是否应与当前活跃单元格 [cellChars] 合并成同一字素簇。
     * 调用方需预先过滤 [isControlOrFormatBreak]。
     */
    fun shouldMerge(cellChars: CharArray, nextCp: Int, nextDisplayWidth: Int): Boolean {
        if (cellChars.isEmpty() || isControlOrFormatBreak(nextCp)) return false

        val firstCp = Character.codePointAt(cellChars, 0)
        val lastCp = Character.codePointBefore(cellChars, cellChars.size)

        if (isEmojiTagChar(nextCp)) {
            return firstCp == 0x1F3F4 && !containsCodePoint(cellChars, 0xE007F)
        }

        if (nextDisplayWidth <= 0) {
            return true
        }

        if (WcWidth.isEmojiModifier(nextCp)) {
            return endsWithEmojiModifierBase(cellChars)
        }

        if (isExtendedPictographic(nextCp) && WcWidth.isZeroWidthJoiner(lastCp)) {
            return hasGb11BaseBeforeTrailingZwj(cellChars)
        }

        if (WcWidth.isRegionalIndicator(nextCp)) {
            return Character.codePointCount(cellChars, 0, cellChars.size) == 1 &&
                WcWidth.isRegionalIndicator(firstCp)
        }

        if (isHangulMerge(lastCp, nextCp)) {
            return true
        }

        if (isInCBConsonant(nextCp) && hasIndicConjunctContext(cellChars)) {
            return true
        }

        return false
    }

    /**
     * 在 [shouldMerge] 已返回 true 的前提下，判断当前 1 列宽单元格合并 [nextCp] 后是否应扩展为 2 列宽。
     */
    fun shouldExpandToWide(cellChars: CharArray, prevWidth: Int, nextCp: Int): Boolean {
        if (prevWidth != 1 || cellChars.isEmpty()) return false

        val firstCp = Character.codePointAt(cellChars, 0)
        val lastCp = Character.codePointBefore(cellChars, cellChars.size)
        val cpCount = Character.codePointCount(cellChars, 0, cellChars.size)

        if (WcWidth.isVariationSelector16(nextCp)) {
            return cpCount == 1 && WcWidth.isVs16WideningCandidate(firstCp)
        }

        if (WcWidth.isCombiningEnclosingKeycap(nextCp)) {
            return WcWidth.isKeycapBase(firstCp) &&
                (cpCount == 1 || (cpCount == 2 && WcWidth.isVariationSelector16(lastCp)))
        }

        if (WcWidth.isRegionalIndicator(nextCp)) {
            return cpCount == 1 && WcWidth.isRegionalIndicator(firstCp)
        }

        if (WcWidth.isEmojiModifier(nextCp)) {
            return true
        }

        if (isExtendedPictographic(nextCp) && WcWidth.isZeroWidthJoiner(lastCp)) {
            return true
        }

        return false
    }

    private fun containsCodePoint(chars: CharArray, targetCp: Int): Boolean {
        var i = 0
        while (i < chars.size) {
            val cp = Character.codePointAt(chars, i)
            if (cp == targetCp) return true
            i += Character.charCount(cp)
        }
        return false
    }

    private fun endsWithEmojiModifierBase(cellChars: CharArray): Boolean {
        var idx = cellChars.size
        if (idx <= 0) return false
        var cp = Character.codePointBefore(cellChars, idx)
        if (WcWidth.isVariationSelector16(cp)) {
            idx -= Character.charCount(cp)
            if (idx <= 0) return false
            cp = Character.codePointBefore(cellChars, idx)
        }
        return isEmojiModifierBase(cp)
    }

    private fun hasGb11BaseBeforeTrailingZwj(cellChars: CharArray): Boolean {
        var idx = cellChars.size
        if (idx <= 0) return false
        val zwj = Character.codePointBefore(cellChars, idx)
        if (!WcWidth.isZeroWidthJoiner(zwj)) return false
        idx -= Character.charCount(zwj)
        while (idx > 0) {
            val cp = Character.codePointBefore(cellChars, idx)
            if (isGb11Extend(cp)) {
                idx -= Character.charCount(cp)
            } else {
                return isExtendedPictographic(cp)
            }
        }
        return false
    }

    private fun isHangulMerge(prevCp: Int, nextCp: Int): Boolean {
        if (isHangulL(prevCp) && (isHangulL(nextCp) || isHangulV(nextCp) || isHangulLV(nextCp) || isHangulLVT(nextCp))) {
            return true
        }
        if ((isHangulLV(prevCp) || isHangulV(prevCp)) && (isHangulV(nextCp) || isHangulT(nextCp))) {
            return true
        }
        if ((isHangulLVT(prevCp) || isHangulT(prevCp)) && isHangulT(nextCp)) {
            return true
        }
        return false
    }

    private fun hasIndicConjunctContext(cellChars: CharArray): Boolean {
        var idx = cellChars.size
        var seenLinker = false
        while (idx > 0) {
            val cp = Character.codePointBefore(cellChars, idx)
            idx -= Character.charCount(cp)
            when {
                isInCBLinker(cp) -> seenLinker = true
                isInCBExtend(cp) -> Unit
                else -> return seenLinker && isInCBConsonant(cp)
            }
        }
        return false
    }
}

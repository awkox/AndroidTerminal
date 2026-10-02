package com.awkoo.libterminal.text

/**
 * 把列号校准到宽字符边界：落在宽字符后半格内的列号推进到该字符之后。
 *
 * [line] 需为从第 0 列到 [column] 列截取的文本。零宽字符与其基字符相邻且不占列，
 * 直接跳过；NUL 及其后的字符视为无内容并停止扫描。返回值恒 ≥ [column]。
 */
internal fun snapToColumnBoundary(line: CharSequence, column: Int): Int {
    line.forEachColumn { _, col, codePoint, width, _ ->
        if (codePoint == 0) return@forEachColumn false
        if (column in (col + 1)..<col + width) return col + width
        true
    }
    return column
}

package com.awkoo.terminal.core

/**
 * 针对 CharArray 安全提取 Unicode 码点（零对象分配）。
 * 自动处理 UTF-16 代理项对 (Surrogate Pairs) 和数组越界安全。
 *
 * @param index 当前遍历的索引
 * @param limit 数组的有效读取边界
 * @param block 提取成功后的回调，包含完整码点 (codePoint) 以及字符占用的索引数 (charCount: 1或2)
 */
inline fun <R> CharArray.withCodePointAt(
    index: Int,
    limit: Int = this.size,
    block: (codePoint: Int, charCount: Int) -> R
): R {
    val c = this[index]
    return if (c.isHighSurrogate() && index + 1 < limit) {
        block(Character.toCodePoint(c, this[index + 1]), 2)
    } else {
        block(c.code, 1)
    }
}

/**
 * 针对 CharSequence (String 等) 安全提取 Unicode 码点。
 */
inline fun <R> CharSequence.withCodePointAt(
    index: Int,
    limit: Int = this.length,
    block: (codePoint: Int, charCount: Int) -> R
): R {
    val c = this[index]
    return if (c.isHighSurrogate() && index + 1 < limit) {
        block(Character.toCodePoint(c, this[index + 1]), 2)
    } else {
        block(c.code, 1)
    }
}

/** 快速获取当前字符占用的 Char 数量（1或2） */
fun CharArray.charCountAtSafe(index: Int, limit: Int = this.size): Int =
    if (this[index].isHighSurrogate() && index + 1 < limit) 2 else 1

fun CharSequence.charCountAtSafe(index: Int, limit: Int = this.length): Int =
    if (this[index].isHighSurrogate() && index + 1 < limit) 2 else 1

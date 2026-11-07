package com.awkoo.libterminal.view.input

import com.awkoo.libterminal.engine.protocol.KeySequenceEncoder

/**
 * 物理/软键盘输入到终端字节流的转换辅助。
 *
 * 功能键/能力键的转义序列编码已下沉至 [KeySequenceEncoder]（引擎协议层），
 * 本类仅保留与 Android 输入语义相关的可打印字符处理。
 */
internal object KeyHandler {

    /**
     * 将用户输入的原始打印字符转换为最终发送给终端的代码点。
     *
     * @param codePoint 原始输入的 Unicode CodePoint
     * @param isCtrlDown 是否按下了 Ctrl 键
     * @param fromKeyEvent 字符是否来自框架 [android.view.KeyEvent] 的 `getUnicodeChar`
     * @return 最终终端接收的代码点
     */
    @JvmStatic
    fun processPrintableChar(
        codePoint: Int,
        isCtrlDown: Boolean,
        fromKeyEvent: Boolean
    ): Int {
        var result = codePoint
        var finalCtrl = isCtrlDown

        // 1. 处理软键盘（如 Penti）或 getUnicodeChar 产生的控制字符，将其转回普通字符并附加 Ctrl 标记
        if (result <= 31 && result != 27) {
            if (result == '\n'.code) {
                // 大多数输入法发送 \n 代表回车，但终端期望 \r
                result = '\r'.code
            }
            finalCtrl = true
            result = when (result) {
                31 -> '_'.code
                30 -> '^'.code
                29 -> ']'.code
                28 -> '\\'.code
                else -> result + 96
            }
        }

        // 2. 处理 Control 组合键逻辑 (ASCII 数学运算)
        if (finalCtrl) {
            result = when (result) {
                in 'a'.code..'z'.code -> result - 'a'.code + 1
                in 'A'.code..'Z'.code -> result - 'A'.code + 1
                ' '.code, '2'.code -> 0
                '['.code, '3'.code -> 27 // ^[ 代表 Esc
                '\\'.code, '4'.code -> 28
                ']'.code, '5'.code -> 29
                '^'.code, '6'.code -> 30
                '_'.code, '7'.code, '/'.code -> 31
                '8'.code -> 127 // DEL
                else -> result
            }
        }

        // 3. 修正 getUnicodeChar 产出的死键显示字符（外部硬件键盘如蓝牙键盘的字符映射）：
        //    U+02DC/U+02CB/U+02C6 是 Android 死键显示字符（波浪/重音/抑扬），应还原为 ASCII
        if (fromKeyEvent && result > -1) {
            result = when (result) {
                0x02DC -> 0x007E // 蓝牙键盘输入的小波浪号转为标准的 ~
                0x02CB -> 0x0060 // 修复 `
                0x02C6 -> 0x005E // 修复 ^
                else -> result
            }
        }

        return result
    }
}
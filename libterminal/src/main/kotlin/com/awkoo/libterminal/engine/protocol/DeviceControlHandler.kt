package com.awkoo.libterminal.engine.protocol

/**
 * DCS（Device Control String）设备控制串处理器。
 *
 * 支持两种格式：
 * - $q：VT100 设备属性查询（DA1）
 * - +q：XTGETTCAP 终端能力查询（xterm 扩展）
 *
 * 无状态纯处理器：应用键盘模式由调用方查询后以布尔参数传入。
 */
internal class DeviceControlHandler(
    private val writeString: (data: String) -> Unit
) {

    fun handleDeviceControl(dcs: String, appCursorKeys: Boolean, appKeypad: Boolean) {
        when {
            dcs == "\$q\"p" -> writeString("\u001bP1\$r64;1\"p\u001b\\")

            dcs.startsWith("+q") -> {
                for (part in dcs.removePrefix("+q").split(';').filter { it.isNotEmpty() }) {
                    // 安全收口：能力名按规范必须是 hex 文本，且回显原文会把不可信内容注入进程输入通道。
                    // 失败响应采用规范的 DCS 0 + r ST。
                    if (part.length % 2 != 0 || !part.all { it.isHexDigit() }) {
                        writeString(FAILED_TCAP_RESPONSE)
                        return
                    }
                    val trans = part.chunked(2)
                        .map { it.toInt(16).toChar() }
                        .joinToString("")

                    val responseValue = when (trans) {
                        "Co", "colors" -> "256"
                        "TN", "name" -> "xterm"
                        else -> KeySequenceEncoder.getCodeFromTermcap(trans, appCursorKeys, appKeypad)
                    }
                    if (responseValue == null) {
                        // 规范：无效名终止整个列表的处理
                        writeString(FAILED_TCAP_RESPONSE)
                        return
                    } else {
                        writeString("\u001bP1+r$part=${responseValue.toHexEncoded()}\u001b\\")
                    }
                }
            }
        }
    }

    private fun String.toHexEncoded(): String = buildString {
        for (ch in this@toHexEncoded) {
            val code = ch.code
            append(HEX_DIGITS[code ushr 4])
            append(HEX_DIGITS[code and 0xF])
        }
    }

    private fun Char.isHexDigit(): Boolean =
        this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    companion object {
        private const val HEX_DIGITS = "0123456789ABCDEF"

        /** XTGETTCAP 失败响应（xterm ctlseqs：DCS 0 + r ST）。 */
        private const val FAILED_TCAP_RESPONSE = "\u001bP0+r\u001b\\"
    }
}

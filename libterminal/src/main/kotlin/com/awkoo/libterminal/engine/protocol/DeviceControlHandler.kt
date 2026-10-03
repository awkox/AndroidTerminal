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
                    if (part.length % 2 != 0) continue
                    val trans = part.chunked(2)
                        .mapNotNull { it.toIntOrNull(16)?.toChar() }
                        .joinToString("")

                    val responseValue = when (trans) {
                        "Co", "colors" -> "256"
                        "TN", "name" -> "xterm"
                        else -> KeySequenceEncoder.getCodeFromTermcap(trans, appCursorKeys, appKeypad)
                    }
                    if (responseValue == null) {
                        writeString("\u001bP0+r$part\u001b\\")
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

    companion object {
        private const val HEX_DIGITS = "0123456789ABCDEF"
    }
}
package com.awkoo.terminal.core

import kotlin.io.encoding.Base64
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

/**
 * OSC（Operating System Command）序列处理器。
 *
 * 拥有窗口标题状态、标题栈和剪贴板事件流，负责处理颜色设置/查询/重置、
 * 剪贴板写入以及标题管理（OSC 0/1/2 与 CSI 22/23 的压栈弹栈）。
 */
class OscHandler(
    private val colors: TerminalColors,
    private val writeString: (data: String) -> Unit
) {

    private val titleStack = ArrayDeque<String?>()
    private val _titleState = MutableStateFlow<String?>(null)
    val titleState = _titleState.asStateFlow()

    val copiedText = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /** CSI 22：将当前标题压入栈中，栈深上限 20。 */
    fun pushTitle() {
        titleStack.addLast(_titleState.value)
        if (titleStack.size > 20) titleStack.removeAt(0)
    }

    /** CSI 23：从栈中弹出标题恢复，栈空时忽略。 */
    fun popTitle() {
        if (!titleStack.isEmpty()) this._titleState.value = titleStack.removeLast()
    }

    fun onOscCommand(value: Int, textParameter: String, bellOrStringTerminator: String) {
        when (value) {
            0, 1, 2 -> this._titleState.value = textParameter
            4 -> handleOscSetColor(textParameter)
            10, 11, 12 -> handleOscQuerySetColor(value, textParameter, bellOrStringTerminator)
            52 -> handleOscClipboard(textParameter)
            104 -> handleOscResetColor(textParameter)
            110, 111, 112 -> colors.reset(TextStyle.COLOR_INDEX_FOREGROUND + (value - 110))
            119 -> {} // 忽略
        }
    }

    private fun handleOscSetColor(textParameter: String) {
        var colorIndex = -1
        var parsingPairStart = -1
        var i = 0
        while (i <= textParameter.length) {
            val endOfInput = i == textParameter.length
            val b = if (endOfInput) ';' else textParameter[i]
            if (b == ';') {
                if (parsingPairStart < 0) {
                    parsingPairStart = i + 1
                } else {
                    if (colorIndex in 0..255) {
                        colors.tryParseColor(colorIndex, textParameter.substring(parsingPairStart, i))
                    }
                    colorIndex = -1
                    parsingPairStart = -1
                }
            } else if (parsingPairStart < 0 && b in '0'..'9') {
                colorIndex = (if (colorIndex < 0) 0 else colorIndex * 10) + (b.code - '0'.code)
            }
            if (endOfInput) break
            i++
        }
    }

    /**
     * 处理 OSC 查询/设置颜色命令 (XTOSC)。
     *
     * 查询：参数为 "?" 时返回当前颜色的 rgb 格式响应。
     * 设置：参数为颜色值时解析并应用。
     * 支持连续设置多个颜色（以分号分隔，从前景色开始递增）。
     */
    private fun handleOscQuerySetColor(value: Int, textParameter: String, bellOrStringTerminator: String) {
        var specialIndex = TextStyle.COLOR_INDEX_FOREGROUND + (value - 10)
        var lastSemiIndex = 0
        var charIndex = 0
        while (charIndex <= textParameter.length) {
            val endOfInput = charIndex == textParameter.length
            if (endOfInput || textParameter[charIndex] == ';') {
                try {
                    val colorSpec = textParameter.substring(lastSemiIndex, charIndex)
                    if ("?" == colorSpec) {
                        val rgb = colors.mCurrentColors[specialIndex]
                        val r = ((65535 * ((rgb and 0x00FF0000) shr 16)) / 255).hex4
                        val g = ((65535 * ((rgb and 0x0000FF00) shr 8)) / 255).hex4
                        val b = ((65535 * ((rgb and 0x000000FF))) / 255).hex4
                        writeString("\u001b]$value;rgb:$r/$g/$b$bellOrStringTerminator")
                    } else {
                        colors.tryParseColor(specialIndex, colorSpec)
                    }
                    specialIndex++
                    if (endOfInput || specialIndex > TextStyle.COLOR_INDEX_CURSOR) break
                    lastSemiIndex = charIndex + 1
                } catch (e: Exception) {}
            }
            charIndex++
        }
    }

    private fun handleOscClipboard(textParameter: String) {
        val startIndex = textParameter.indexOf(";") + 1
        try {
            val data = Base64.decode(textParameter.substring(startIndex))
            copiedText.tryEmit(data.toString(Charsets.UTF_8))
        } catch (e: Exception) {
            Timber.w("OSC Manipulate selection, invalid string '$textParameter'")
        }
    }

    private fun handleOscResetColor(textParameter: String) {
        if (textParameter.isEmpty()) colors.reset()
        else {
            var lastIndex = 0
            for (i in 0..textParameter.length) {
                if (i == textParameter.length || textParameter[i] == ';') {
                    textParameter.substring(lastIndex, i)
                        .toIntOrNull()
                        ?.let { colors.reset(it) }
                    lastIndex = i + 1
                }
            }
        }
    }

    private val Int.hex4: String get() = "%04x".format(this)
}

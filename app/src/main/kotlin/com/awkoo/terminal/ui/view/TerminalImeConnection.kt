package com.awkoo.terminal.ui.view

import android.view.KeyEvent
import android.view.inputmethod.BaseInputConnection
import com.awkoo.terminal.core.TerminalEmulator
import com.awkoo.terminal.core.Utf8Decoder
import com.awkoo.terminal.core.withCodePointAt
import timber.log.Timber

/**
 * IME 输入连接，将软键盘输入桥接到终端。
 *
 * [commitText] 逐字符处理（代理项对、Penti 等键盘的 Ctrl 码点反转、回车键 \n→\r 转换）。
 * [sendKeyEvent] 绕过 Compose 的 AndroidComposeView 拦截，直接派发到
 * [TerminalView.onKeyDown] / [TerminalView.onKeyUp]。
 */
class TerminalImeConnection(
    private val terminalView: TerminalView
) : BaseInputConnection(terminalView, true) {

    override fun finishComposingText(): Boolean {
        Timber.v("IME: finishComposingText()")
        super.finishComposingText()
        flushAndClearEditable()
        return true
    }

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        Timber.v("IME: commitText(\"$text\", $newCursorPosition)")
        super.commitText(text, newCursorPosition)
        flushAndClearEditable()
        return true
    }

    override fun deleteSurroundingText(leftLength: Int, rightLength: Int): Boolean {
        Timber.v("IME: deleteSurroundingText($leftLength, $rightLength)")
        // 三星原生键盘开启「自动拼写检查」时会发送 leftLength > 1
        val deleteKey = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL)
        for (i in 0..<leftLength) sendKeyEvent(deleteKey)
        return super.deleteSurroundingText(leftLength, rightLength)
    }

    override fun sendKeyEvent(event: KeyEvent): Boolean {
        Timber.v("IME: sendKeyEvent($event)")
        // 绕过 Compose 的 AndroidComposeView 拦截，直接路由到 TerminalView
        if (event.action == KeyEvent.ACTION_DOWN) {
            terminalView.onKeyDown(event.keyCode, event)
        } else if (event.action == KeyEvent.ACTION_UP) {
            terminalView.onKeyUp(event.keyCode, event)
        }
        return true
    }

    private fun flushAndClearEditable() {
        val buffer = editable ?: return
        if (buffer.isEmpty()) return
        if (terminalView.currentSession != null) {
            sendTextToTerminal(buffer)
        }
        buffer.clear()
    }

    private fun sendTextToTerminal(text: CharSequence) {
        terminalView.stopTextSelectionMode()
        val textLengthInChars = text.length
        var i = 0
        while (i < textLengthInChars) {
            text.withCodePointAt(i, textLengthInChars) { cp, charCount ->
                // 特殊兜底：如果是孤立的高位代理项被发送过来，视为替换字符
                val codePoint = if (charCount == 1 && text[i].isHighSurrogate()) {
                    Utf8Decoder.UNICODE_REPLACEMENT_CHAR
                } else {
                    cp
                }

                terminalView.inputCodePoint(KEY_EVENT_SOURCE_SOFT_KEYBOARD, codePoint, false, false)
                i += charCount
            }
        }
    }

    companion object {
        /** 该 [KeyEvent] 来自非物理设备（如软键盘）。 */
        const val KEY_EVENT_SOURCE_SOFT_KEYBOARD: Int = 0
    }
}

package com.awkoo.libterminal.view.input

import android.R
import android.view.KeyEvent
import android.view.inputmethod.BaseInputConnection
import com.awkoo.libterminal.text.forEachColumn
import com.awkoo.libterminal.view.TerminalView

/**
 * IME 输入连接，将软键盘输入桥接到终端。
 *
 * [commitText] 逐字符处理（代理项对、Penti 等键盘的 Ctrl 码点反转、回车键 \n→\r 转换）。
 * [sendKeyEvent] 绕过 Compose 的 AndroidComposeView 拦截，直接派发到
 * [TerminalView.onKeyDown] / [TerminalView.onKeyUp]。
 */
internal class TerminalImeConnection(
    private val terminalView: TerminalView
) : BaseInputConnection(terminalView, true) {

    override fun finishComposingText(): Boolean {
        super.finishComposingText()
        flushAndClearEditable()
        return true
    }

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        super.commitText(text, newCursorPosition)
        flushAndClearEditable()
        return true
    }

    override fun deleteSurroundingText(leftLength: Int, rightLength: Int): Boolean {
        // 三星原生键盘开启「自动拼写检查」时会发送 leftLength > 1
        val deleteKey = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL)
        repeat(leftLength) { sendKeyEvent(deleteKey) }
        return super.deleteSurroundingText(leftLength, rightLength)
    }

    override fun sendKeyEvent(event: KeyEvent): Boolean {
        // 绕过 Compose 的 AndroidComposeView 拦截，直接路由到 TerminalView
        when (event.action) {
            KeyEvent.ACTION_DOWN -> terminalView.onKeyDown(event.keyCode, event)
            KeyEvent.ACTION_UP -> terminalView.onKeyUp(event.keyCode, event)
        }
        return true
    }

    override fun performContextMenuAction(id: Int): Boolean {
        return when (id) {
            R.id.paste -> {
                terminalView.pasteTextFromClipboard()
                true
            }
            R.id.copy -> {
                terminalView.copyTextToClipboard()
                true
            }
            else -> super.performContextMenuAction(id)
        }
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
        text.forEachColumn { _, _, codePoint, _, _ ->
            terminalView.inputCodePoint(KEY_EVENT_SOURCE_SOFT_KEYBOARD, codePoint, false, false)
            true
        }
    }

    companion object {
        /** 该 [KeyEvent] 来自非物理设备（如软键盘）。 */
        const val KEY_EVENT_SOURCE_SOFT_KEYBOARD: Int = 0
    }
}
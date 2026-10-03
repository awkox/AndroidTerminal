package com.awkoo.libterminal.view.input

import android.view.KeyCharacterMap
import android.view.KeyEvent
import com.awkoo.libterminal.engine.TerminalEmulator
import com.awkoo.libterminal.engine.TerminalSession
import com.awkoo.libterminal.engine.protocol.KeySequenceEncoder
import com.awkoo.libterminal.view.ExtraKeysModifierSnapshot

/**
 * 物理键盘与虚拟键盘输入处理器。
 *
 * 负责按键事件到会话字节流的转换：修饰键融合（ExtraKeys 粘性修饰键）、
 * 功能键编码、死键组合状态机、可打印字符处理。
 * 通过回调访问宿主 View 的会话/模拟器/选择状态，自身不持有 Android View 引用。
 */
internal class KeyInputProcessor(
    private val sessionProvider: () -> TerminalSession?,
    private val emulatorProvider: () -> TerminalEmulator?,
    private val pokeCursor: () -> Unit,
    private val scrollPages: (Int) -> Unit,
    private val isSelectingText: () -> Boolean,
    private val stopTextSelection: () -> Unit,
    private val modifierReader: () -> ExtraKeysModifierSnapshot
) {

    /** 按键处理结果：已消费、未消费（返回 false）、交还系统处理。 */
    enum class KeyDownResult { HANDLED, NOT_HANDLED, PASS_TO_SUPER }

    /** 最后收到的组合字符码点，非零时表示处于死键组合状态。 */
    private var combiningAccent: Int = 0

    /** 重置死键组合状态（会话切换时调用，避免跨会话残留组合输入）。 */
    fun reset() {
        combiningAccent = 0
    }

    fun onKeyDown(keyCode: Int, event: KeyEvent): KeyDownResult {
        val currentSession = sessionProvider() ?: return KeyDownResult.NOT_HANDLED
        if (isSelectingText()) stopTextSelection()

        if (event.isSystem) return KeyDownResult.PASS_TO_SUPER
        if (event.action == KeyEvent.ACTION_MULTIPLE && keyCode == KeyEvent.KEYCODE_UNKNOWN) {
            currentSession.write(event.characters)
            return KeyDownResult.HANDLED
        }
        if (keyCode == KeyEvent.KEYCODE_LANGUAGE_SWITCH) return KeyDownResult.PASS_TO_SUPER

        val metaState = event.metaState
        val extraMods = modifierReader()
        val controlDown = event.isCtrlPressed || extraMods.ctrl
        val leftAltDown = (metaState and KeyEvent.META_ALT_LEFT_ON) != 0 || extraMods.alt
        val shiftDown = event.isShiftPressed || extraMods.shift
        val fnDown = event.isFunctionPressed || extraMods.fn
        val rightAltDownFromEvent = (metaState and KeyEvent.META_ALT_RIGHT_ON) != 0

        var keyMod = 0
        if (controlDown) keyMod = keyMod or KeySequenceEncoder.KEYMOD_CTRL
        if (event.isAltPressed || leftAltDown) keyMod = keyMod or KeySequenceEncoder.KEYMOD_ALT
        if (shiftDown) keyMod = keyMod or KeySequenceEncoder.KEYMOD_SHIFT
        if (event.isNumLockOn) keyMod = keyMod or KeySequenceEncoder.KEYMOD_NUM_LOCK
        if (!fnDown && handleKeyCode(keyCode, keyMod)) {
            return KeyDownResult.HANDLED
        }

        var bitsToClear = KeyEvent.META_CTRL_MASK
        // 右 Alt / Alt Gr 按下时不清除 ALT 位，交由 getUnicodeChar 组合出字符
        if (!rightAltDownFromEvent) {
            bitsToClear = bitsToClear or (KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON)
        }
        var effectiveMetaState = event.metaState and bitsToClear.inv()

        if (shiftDown) effectiveMetaState =
            effectiveMetaState or (KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON)

        var result = event.getUnicodeChar(effectiveMetaState)
        if (result == 0) return KeyDownResult.NOT_HANDLED

        if ((result and KeyCharacterMap.COMBINING_ACCENT) != 0) {
            if (combiningAccent != 0) inputCodePoint(event.deviceId, combiningAccent, controlDown, leftAltDown)
            combiningAccent = result and KeyCharacterMap.COMBINING_ACCENT_MASK
        } else {
            if (combiningAccent != 0) {
                val combinedChar = KeyCharacterMap.getDeadChar(combiningAccent, result)
                if (combinedChar > 0) result = combinedChar
                combiningAccent = 0
            }
            inputCodePoint(event.deviceId, result, controlDown, leftAltDown)
        }

        return KeyDownResult.HANDLED
    }

    fun inputCodePoint(
        eventSource: Int,
        codePoint: Int,
        controlDownFromEvent: Boolean,
        leftAltDownFromEvent: Boolean
    ) {
        val currentSession = sessionProvider() ?: return

        // 按需获取键盘修饰符
        // 只有软键盘输入需要单独获取 extraMods，物理按键和 ExtraKeys 在发送前已经在 onKeyDown 里获取并合并了
        val extraMods = if (eventSource == TerminalImeConnection.KEY_EVENT_SOURCE_SOFT_KEYBOARD) {
            modifierReader()
        } else {
            ExtraKeysModifierSnapshot()
        }

        val controlDown = controlDownFromEvent || extraMods.ctrl
        val leftAltDown = leftAltDownFromEvent || extraMods.alt

        // 判断来源是否是真实的硬件键盘
        val isHardwareKeyboard = eventSource > TerminalImeConnection.KEY_EVENT_SOURCE_SOFT_KEYBOARD

        // 委托给 KeyHandler 处理转换
        val finalCodePoint = KeyHandler.processPrintableChar(
            codePoint = codePoint,
            isCtrlDown = controlDown,
            isHardwareKeyboard = isHardwareKeyboard
        )

        pokeCursor()
        currentSession.writeCodePoint(leftAltDown, finalCodePoint)
    }

    fun handleKeyCode(keyCode: Int, keyMod: Int): Boolean {
        val emulator = emulatorProvider() ?: return false
        if (handleKeyCodeAction(emulator, keyCode, keyMod)) return true
        val code = KeySequenceEncoder.getCode(
            keyCode,
            keyMod,
            emulator.isCursorKeysApplicationMode,
            emulator.isKeypadApplicationMode
        ) ?: return false
        val currentSession = sessionProvider() ?: return false
        pokeCursor()
        currentSession.write(code)
        return true
    }

    private fun handleKeyCodeAction(emulator: TerminalEmulator, keyCode: Int, keyMod: Int): Boolean {
        val shiftDown = (keyMod and KeySequenceEncoder.KEYMOD_SHIFT) != 0
        val pageKey = keyCode == KeyEvent.KEYCODE_PAGE_UP || keyCode == KeyEvent.KEYCODE_PAGE_DOWN
        if (shiftDown && pageKey) {
            scrollPages(if (keyCode == KeyEvent.KEYCODE_PAGE_UP) -emulator.mRows else emulator.mRows)
            return true
        }
        return false
    }
}
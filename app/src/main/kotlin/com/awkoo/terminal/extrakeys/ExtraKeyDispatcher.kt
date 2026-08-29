package com.awkoo.terminal.extrakeys

import android.view.KeyEvent

/** 应用级动作回调，由 [ExtraKey.SpecialKey] 按钮触发。 */
interface ExtraKeyActions {
    fun onToggleKeyboard()
    fun onToggleDrawer()
    fun onPaste()
    fun onToggleScroll()

    fun sendKeyDown(keyCode: Int, event: KeyEvent)
    fun sendCodePoint(codePoint: Int, ctrlDown: Boolean, altDown: Boolean)
}

/**
 * 将 [ExtraKeyButton] 点击事件路由到 [TerminalView]。
 *
 * - [ExtraKey.Key] → [KeyEvent]（若在 [KEY_CODE_MAP] 中）或字面码点
 * - [ExtraKey.MacroKey] → 空格分隔的令牌；CTRL/ALT/SHIFT/FN 设置粘性修饰符
 * - [ExtraKey.SpecialKey] → 切换 [ExtraKeysModifierState] 或调用 [ExtraKeyActions]
 *
 * 修饰符集成：
 * 用户在扩展按键栏上切换修饰符（如 CTRL）后，后续点击自动通过
 * [ExtraKeysModifierState.readCtrl] 获取修饰符状态。
 * 未锁定的修饰符在首次按键后自动释放。
 */
class ExtraKeyDispatcher(
    private val modifierState: ExtraKeysModifierState,
    private val actions: ExtraKeyActions,
) {
    fun dispatch(key: ExtraKey) {
        when (key) {
            is ExtraKey.Key -> dispatchKey(key.value)
            is ExtraKey.MacroKey -> dispatchMacro(key.keys)
            is ExtraKey.SpecialKey -> dispatchSpecial(key.type)
        }
    }

    private fun dispatchSpecial(type: SpecialKeyType) {
        when (type) {
            SpecialKeyType.CTRL,
            SpecialKeyType.ALT,
            SpecialKeyType.SHIFT,
            SpecialKeyType.FN -> modifierState.toggle(type)

            SpecialKeyType.KEYBOARD -> actions.onToggleKeyboard()
            SpecialKeyType.DRAWER -> actions.onToggleDrawer()
            SpecialKeyType.PASTE -> actions.onPaste()
            SpecialKeyType.SCROLL -> actions.onToggleScroll()
        }
    }

    private fun dispatchMacro(keys: List<ExtraKey.SingleKey>) {
        var ctrl = false
        var alt = false
        var shift = false
        var fn = false

        for (token in keys) {
            when (token) {
                is ExtraKey.SpecialKey -> {
                    when (token.type) {
                        SpecialKeyType.CTRL -> ctrl = true
                        SpecialKeyType.ALT -> alt = true
                        SpecialKeyType.SHIFT -> shift = true
                        SpecialKeyType.FN -> fn = true
                        else -> dispatchSpecial(token.type)
                    }
                }
                is ExtraKey.Key -> {
                    dispatchKey(token.value, ctrl, alt, shift, fn, false)
                    ctrl = false
                    alt = false
                    shift = false
                    fn = false
                }
            }
        }
    }

    private fun dispatchKey(
        key: String,
        ctrl: Boolean = false,
        alt: Boolean = false,
        shift: Boolean = false,
        fn: Boolean = false,
        useSticky: Boolean = true
    ) {
            // 合并每令牌标志与粘性修饰符状态
        val ctrlDown = ctrl || (useSticky && modifierState.readCtrl())
        val altDown = alt || (useSticky && modifierState.readAlt())
        val shiftDown = shift || (useSticky && modifierState.readShift())
        val fnDown = fn || (useSticky && modifierState.readFn())

        val keyCode = KEY_CODE_MAP[key]
        if (keyCode != null) {
            var metaState = 0
            if (ctrlDown) metaState = metaState or (KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON)
            if (altDown) metaState = metaState or (KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON)
            if (shiftDown) metaState = metaState or (KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON)
            if (fnDown) metaState = metaState or KeyEvent.META_FUNCTION_ON

            // 合成携带修饰符的 KeyEvent，TerminalView.onKeyDown() 会检测到 isCtrlPressed
            // 并通过 extraKeysModifierReader 消耗粘性修饰符（未锁定时自动释放）
            val event = KeyEvent(0, 0, KeyEvent.ACTION_DOWN, keyCode, 0, metaState)
            actions.sendKeyDown(keyCode, event)
        } else {
            // 字面文本 — 直接发送码点及修饰符标志
            key.codePoints().forEach { codePoint ->
                actions.sendCodePoint(
                    codePoint,
                    ctrlDown,
                    altDown
                )
            }
        }
    }
}

package com.awkoo.terminal.extrakeys

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 扩展按键栏分发语义：
 * 文本路径只消费 Ctrl/Alt——字符配置即最终字符，粘性 Shift 不得被无谓消耗；
 * 功能键路径读即消费（全量）；Fn 已从修饰键体系移除（编译期保证）。
 */
class ExtraKeyDispatcherTest {

    private val modifierState = ExtraKeysModifierState()
    private val sentCodePoints = mutableListOf<Triple<Int, Boolean, Boolean>>()
    private val sentKeyCodes = mutableListOf<Int>()

    private val dispatcher = ExtraKeyDispatcher(
        modifierState,
        object : ExtraKeyActions {
            override fun onToggleKeyboard() {}
            override fun onToggleDrawer() {}
            override fun onPaste() {}
            override fun onToggleScroll() {}

            override fun sendKeyDown(keyCode: Int, event: KeyEvent) {
                sentKeyCodes += keyCode
            }

            override fun sendCodePoint(codePoint: Int, ctrlDown: Boolean, altDown: Boolean) {
                sentCodePoints += Triple(codePoint, ctrlDown, altDown)
            }
        }
    )

    @Test
    fun stickyShift_textPath_notConsumed() {
        modifierState.toggle(SpecialKeyType.SHIFT)

        dispatcher.dispatch(ExtraKey.Key("a"))

        assertEquals(listOf(Triple('a'.code, false, false)), sentCodePoints)
        assertTrue("文本路径不得消费粘性 Shift", modifierState.shift.isActive)
    }

    @Test
    fun stickyCtrl_textPath_consumedOnce() {
        modifierState.toggle(SpecialKeyType.CTRL)

        dispatcher.dispatch(ExtraKey.Key("a"))

        assertEquals(listOf(Triple('a'.code, true, false)), sentCodePoints)
        assertFalse("未锁定 Ctrl 在输入后自动释放", modifierState.ctrl.isActive)
    }

    @Test
    fun stickyCtrlLocked_textPath_staysActive() {
        modifierState.lockAndToggle(SpecialKeyType.CTRL)

        dispatcher.dispatch(ExtraKey.Key("a"))
        dispatcher.dispatch(ExtraKey.Key("b"))

        assertEquals(
            listOf(Triple('a'.code, true, false), Triple('b'.code, true, false)),
            sentCodePoints
        )
        assertTrue("锁定的 Ctrl 跨输入保持激活", modifierState.ctrl.isActive)
    }

    @Test
    fun stickyShift_functionKeyPath_consumed() {
        modifierState.toggle(SpecialKeyType.SHIFT)

        dispatcher.dispatch(ExtraKey.Key("TAB"))

        assertEquals(listOf(KeyEvent.KEYCODE_TAB), sentKeyCodes)
        assertFalse("功能键路径读即消费粘性 Shift", modifierState.shift.isActive)
    }
}

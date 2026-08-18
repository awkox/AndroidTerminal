package com.awkoo.terminal.extrakeys

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** 单个修饰键的切换状态。 */
class ModifierState {
    var isActive by mutableStateOf(false)
        private set
    var isLocked by mutableStateOf(false)
        private set

    /** 切换激活状态；取消激活时同时释放锁定。 */
    fun toggleActive() {
        isActive = !isActive
        if (!isActive) isLocked = false
    }

    /** 长按：锁定到激活状态，然后切换。 */
    fun lockAndToggle() {
        isLocked = !isActive
        isActive = !isActive
    }

    /** 未锁定时取消激活。返回是否发生了取消激活。 */
    fun deactivateIfUnlocked(): Boolean {
        if (!isLocked) {
            isActive = false
            return true
        }
        return false
    }

    /** 重置激活和锁定状态为 false。 */
    fun reset() {
        isActive = false
        isLocked = false
    }
}

/**
 * 扩展按键栏上四个修饰键的可观察状态容器。
 *
 * Compose 中：观察 [ctrl]/[alt]/[shift]/[fn] 及其嵌套状态。
 * View 代码中：调用 [readCtrl] / [readAlt] / [readShift] / [readFn]，
 * autoRelease=true 时未锁定的修饰键在首次物理按键后自动释放。
 */
class ExtraKeysModifierState {
    val ctrl = ModifierState()
    val alt = ModifierState()
    val shift = ModifierState()
    val fn = ModifierState()

    fun readCtrl(autoRelease: Boolean = true): Boolean = read(ctrl, autoRelease)
    fun readAlt(autoRelease: Boolean = true): Boolean = read(alt, autoRelease)
    fun readShift(autoRelease: Boolean = true): Boolean = read(shift, autoRelease)
    fun readFn(autoRelease: Boolean = true): Boolean = read(fn, autoRelease)

    fun toggle(type: SpecialKeyType) {
        when (type) {
            SpecialKeyType.CTRL -> ctrl.toggleActive()
            SpecialKeyType.ALT -> alt.toggleActive()
            SpecialKeyType.SHIFT -> shift.toggleActive()
            SpecialKeyType.FN -> fn.toggleActive()
            else -> {} // KEYBOARD、DRAWER、PASTE、SCROLL 不是修饰符
        }
    }

    fun lockAndToggle(type: SpecialKeyType) {
        when (type) {
            SpecialKeyType.CTRL -> ctrl.lockAndToggle()
            SpecialKeyType.ALT -> alt.lockAndToggle()
            SpecialKeyType.SHIFT -> shift.lockAndToggle()
            SpecialKeyType.FN -> fn.lockAndToggle()
            else -> {}
        }
    }

    fun isSpecialKeyActive(type: SpecialKeyType): Boolean = when (type) {
        SpecialKeyType.CTRL -> ctrl.isActive
        SpecialKeyType.ALT -> alt.isActive
        SpecialKeyType.SHIFT -> shift.isActive
        SpecialKeyType.FN -> fn.isActive
        else -> false
    }

    private fun read(state: ModifierState, autoRelease: Boolean): Boolean {
        if (!state.isActive) return false
        if (autoRelease) state.deactivateIfUnlocked()
        return true
    }
}

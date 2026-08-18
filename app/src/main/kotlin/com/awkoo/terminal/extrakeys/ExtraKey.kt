package com.awkoo.terminal.extrakeys

import android.view.KeyEvent
import androidx.annotation.Keep
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
sealed class ExtraKey {
    abstract val display: String
    abstract val customDisplay: String?

    @Serializable
    sealed class SingleKey : ExtraKey()

    @Serializable
    @SerialName("key")
    data class Key(
        val value: String,
        override val customDisplay: String? = null
    ) : SingleKey() {
        @Transient
        override val display: String = customDisplay ?: value
    }

    @Serializable
    @SerialName("special")
    data class SpecialKey(
        val type: SpecialKeyType,
        override val customDisplay: String? = null
    ) : SingleKey() {
        @Transient
        override val display: String = customDisplay ?: type.name
    }

    @Serializable
    @SerialName("macro")
    data class MacroKey(
        val keys: List<SingleKey>,
        override val customDisplay: String? = null
    ) : ExtraKey() {
        constructor(vararg keys: SingleKey, customDisplay: String? = null) :
            this(keys.toList(), customDisplay)
        @Transient
        override val display: String = customDisplay ?: keys.joinToString(" + ") { it.display }
    }
}

@Keep
@Serializable
enum class SpecialKeyType {
    CTRL,
    ALT,
    SHIFT,
    FN,
    KEYBOARD,
    DRAWER,
    PASTE,
    SCROLL
}

/** 按键名称到 Android [KeyEvent] 键码的映射。 */
val KEY_CODE_MAP: Map<String, Int> = mapOf(
    "SPACE" to KeyEvent.KEYCODE_SPACE,
    "ESC" to KeyEvent.KEYCODE_ESCAPE,
    "TAB" to KeyEvent.KEYCODE_TAB,
    "HOME" to KeyEvent.KEYCODE_MOVE_HOME,
    "END" to KeyEvent.KEYCODE_MOVE_END,
    "PGUP" to KeyEvent.KEYCODE_PAGE_UP,
    "PGDN" to KeyEvent.KEYCODE_PAGE_DOWN,
    "INS" to KeyEvent.KEYCODE_INSERT,
    "DEL" to KeyEvent.KEYCODE_FORWARD_DEL,
    "BKSP" to KeyEvent.KEYCODE_DEL,
    "UP" to KeyEvent.KEYCODE_DPAD_UP,
    "LEFT" to KeyEvent.KEYCODE_DPAD_LEFT,
    "RIGHT" to KeyEvent.KEYCODE_DPAD_RIGHT,
    "DOWN" to KeyEvent.KEYCODE_DPAD_DOWN,
    "ENTER" to KeyEvent.KEYCODE_ENTER,
    "F1" to KeyEvent.KEYCODE_F1,
    "F2" to KeyEvent.KEYCODE_F2,
    "F3" to KeyEvent.KEYCODE_F3,
    "F4" to KeyEvent.KEYCODE_F4,
    "F5" to KeyEvent.KEYCODE_F5,
    "F6" to KeyEvent.KEYCODE_F6,
    "F7" to KeyEvent.KEYCODE_F7,
    "F8" to KeyEvent.KEYCODE_F8,
    "F9" to KeyEvent.KEYCODE_F9,
    "F10" to KeyEvent.KEYCODE_F10,
    "F11" to KeyEvent.KEYCODE_F11,
    "F12" to KeyEvent.KEYCODE_F12
)

/** 长按触发自动重复的按键集合。 */
val REPETITIVE_KEYS: Set<String> = setOf(
    "UP",
    "DOWN",
    "LEFT",
    "RIGHT",
    "BKSP",
    "DEL",
    "PGUP",
    "PGDN"
)



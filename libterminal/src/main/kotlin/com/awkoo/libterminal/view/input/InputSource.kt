package com.awkoo.libterminal.view.input

/**
 * 终端可打印字符的输入源类型，由各输入入口一次性定性，下游按类型取义。
 *
 * 不得用整数大小反推来源：Android `KeyEvent.getDeviceId()` 与自定义标记共处同一
 * 取值空间（内置键盘 deviceId 恒为 0、注入事件为 -1、外接键盘为任意正数），
 * 数值推断必然与真实设备身份相撞。
 */
internal enum class InputSource {
    /** IME 软键盘提交的文本。 */
    SOFT_IME,

    /** 应用自建虚拟 / 扩展按键栏注入的码点。 */
    VIRTUAL_KEY_BAR,

    /** 框架 [android.view.KeyEvent] 经 `getUnicodeChar` 解出的字符。 */
    KEY_EVENT
}

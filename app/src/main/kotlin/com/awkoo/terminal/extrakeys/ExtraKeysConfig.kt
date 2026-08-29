package com.awkoo.terminal.extrakeys

import kotlinx.serialization.Serializable

@Serializable
data class ExtraKeyButton(
    val key: ExtraKey,
    val popup: ExtraKey? = null
)

@Serializable
data class ExtraKeyRow(
    val buttons: List<ExtraKeyButton>
) {
    constructor(vararg buttons: ExtraKeyButton) : this(buttons.toList())
}

@Serializable
data class ExtraKeysConfig(
    val rows: List<ExtraKeyRow> = DEFAULT_EXTRA_KEYS_CONFIG
)

/**
 * 默认扩展按键配置，匹配经典 Termux 双行布局。
 * 直接从 [Serializable] 数据类构建，可通过 ProtoBuf 序列化，无 JSON 依赖。
 */
private val DEFAULT_EXTRA_KEYS_CONFIG = listOf(
    ExtraKeyRow(
        ExtraKeyButton(ExtraKey.Key("ESC")),
        ExtraKeyButton(ExtraKey.Key("DEL")),
        ExtraKeyButton(ExtraKey.Key("INS")),
        ExtraKeyButton(ExtraKey.Key("HOME")),
        ExtraKeyButton(ExtraKey.Key("UP")),
        ExtraKeyButton(ExtraKey.Key("END")),
        ExtraKeyButton(ExtraKey.Key("PGUP")),
    ),
    ExtraKeyRow(
        ExtraKeyButton(ExtraKey.Key("TAB")),
        ExtraKeyButton(ExtraKey.SpecialKey(SpecialKeyType.CTRL)),
        ExtraKeyButton(ExtraKey.SpecialKey(SpecialKeyType.ALT)),
        ExtraKeyButton(ExtraKey.Key("LEFT")),
        ExtraKeyButton(ExtraKey.Key("DOWN")),
        ExtraKeyButton(ExtraKey.Key("RIGHT")),
        ExtraKeyButton(ExtraKey.Key("PGDN")),
    ),
)

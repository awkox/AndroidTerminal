# TerminalEmulator OSC 职责拆分设计

## 目标

将 `TerminalEmulator` 中的 OSC（Operating System Command）处理职责切分到独立的 `OscHandler` 类，作为上帝类拆分的第一步。纯重构，零行为变更。

## 背景

`TerminalEmulator`（1170 行）混杂了转义解析、屏幕操作、模式管理、输入编码、剪贴板、标题发布等 8+ 种职责，违反 AGENTS.md 的单一职责原则。OSC 处理自成一体（约 120 行），与核心 VT 状态机耦合最少，是最安全的第一个切分点。

## 方案：状态归属式

新建 `core/OscHandler.kt`，拥有全部标题与剪贴板状态；`TerminalEmulator` 保留门面转发，公共 API 零变化。

### OscHandler 结构

```kotlin
class OscHandler(
    private val colors: TerminalColors,
    private val writeString: (String) -> Unit
) {
    private val _titleState = MutableStateFlow<String?>(null)
    val titleState = _titleState.asStateFlow()
    val copiedText = MutableSharedFlow<String>(replay=0, extraBufferCapacity=1, onBufferOverflow=DROP_OLDEST)
    private val titleStack = ArrayDeque<String?>()

    fun onOscCommand(value: Int, textParameter: String, bellOrStringTerminator: String)
    fun pushTitle()   // 原 CSI 22 分支
    fun popTitle()    // 原 CSI 23 分支

    // 私有：handleOscSetColor / handleOscQuerySetColor / handleOscClipboard / handleOscResetColor / hex4
}
```

### 迁移清单

| 项目 | 去向 |
|---|---|
| `onOscCommand` 分发 + 4 个 `handleOsc*` 方法 + `hex4` 扩展属性 | OscHandler |
| `_titleState` / `titleState` / `copiedText` / `titleStack` | 所有权移至 OscHandler |
| CSI 22/23 分支（原 409-413 行） | 改为 `osc.pushTitle()` / `osc.popTitle()` |
| `mColors` | 留在 TerminalEmulator（渲染层引用），构造时传入 OscHandler |
| `Base64` / `Timber` / Flow 相关 import | 随迁 |

### 兼容性保证

- `TerminalEmulator.titleState` / `copiedText` 改为门面转发 → `TerminalSession`（2 处）、`TerminalView`（1 处）、Compose UI（2 处）零改动
- `AnsiEscapeParser` 回调接口不变：`TerminalEmulator.onOscCommand` 保留为一行委托
- OSC 序列处理结果逐字节一致

## 验证

```bash
./gradlew assembleRelease
```

设备回归序列：
- `printf '\e]0;新标题\a'` — 窗口标题更新
- `printf '\e]4;1;#ff0000\e\\'` — 调色板设置
- `printf '\e]10;?\a'` — 前景色查询响应
- `printf '\e]52;c;<base64>\e\\'` — 剪贴板写入
- CSI 22/23 标题压栈/弹栈（vim/tmux 切换窗口时触发）

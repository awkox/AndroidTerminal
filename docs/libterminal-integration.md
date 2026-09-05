# libterminal 集成文档

## 1. 引入依赖

在 `settings.gradle.kts` 中确保包含 `mavenCentral()`，然后在模块的 `build.gradle.kts`：

```kotlin
dependencies {
    implementation("io.github.awkox:libterminal:<xxx>")
}
```

### 环境要求

| 项目 | 要求 |
|------|------|
| minSdk | 28 |
| compileSdk | 37 |
| JVM / Kotlin | 建议 `jvmToolchain(21)`、Kotlin 2.x |

---

## 2. 快速开始

### 2.1 创建一个终端进程（实现 `ITerminalProcess`）

`ITerminalProcess`（`com.awkoo.libterminal.process`）模拟了一个伪终端：

```kotlin
interface ITerminalProcess {
    val pid: Int
    val inputStream: InputStream   // 进程输出 → 喂给终端模拟器
    val outputStream: OutputStream // 终端输入 → 写入进程
    fun resize(columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int)
    fun waitFor(): Int
    fun kill()
    fun close()
}
```

demo（`app/.../core/LocalPtyProcess.kt`）通过 JNI 的 `createSubprocess`/`setPtyWindowSize`
实现本地 pty：将 pty 文件描述符包装为 `FileInputStream` / `FileOutputStream` 暴露给上层，
`kill()` 使用 `Os.kill(pid, SIGKILL)`。你可以用同样模式接入 SSH 或任意远程终端。

### 2.2 创建会话（`TerminalSession`）

```kotlin
val session = TerminalSession(
    id = 1,
    sessionName = MutableStateFlow("sh"),      // 会话名称（可更新的 StateFlow）
    stdin = null,                              // 可选：启动时写入进程的初始输入
) { rows, cols, cellWidth, cellHeight ->       // 工厂函数，参数依次为 (行, 列, 单元格宽, 单元格高)
    LocalPtyProcess(command, rows, cols, cellWidth, cellHeight)
}
session.execute()   // 必须调用：拉起读/写/仿真协程
```

> Tips：启动 shell 前设置 `TERM=xterm-256color` 与 `COLORTERM=truecolor`
> （见 demo `SessionManager.addSession`），以获得 256 色与真彩支持。

### 2.3 将会话挂载到 `TerminalView`

**Compose 方式**（demo `app/.../ui/compose/SessionListDrawer.kt`）：

```kotlin
@Composable
fun TerminalScreen(session: TerminalSession?) {
    AndroidView(
        modifier = Modifier.fillMaxSize().clipToBounds(),
        factory = { context ->
            TerminalView(context).also {
                it.isFocusable = true            // 必须：接收键盘/触摸
                it.isFocusableInTouchMode = true // 必须：触摸即可获取焦点
            }
        },
        update = { view ->
            if (session != view.currentSession) view.currentSession = session
        },
        onRelease = { view -> view.dispose() }   // 必须：释放协程与view树监听
    )
}
```

**传统 View 方式**：把 `TerminalView` 放进布局即可，其余配置相同。

---

## 3. 公开 API 参考

### 3.1 `com.awkoo.libterminal.view.TerminalView`

核心 View，既是渲染器也是统一入口。

| 成员 | 类型 | 说明 |
|------|------|------|
| `TerminalView(context, useLightTheme)` | 构造 | `useLightTheme=false` 为深色（黑底白字），否则浅色 |
| `currentSession` | `TerminalSession?`（var） | 切换当前会话；内部自动重绑定、重建配色 |
| `textSize` | `Int`（var） | 字号（dp，自动约束在 4..100） |
| `typeface` | `Typeface`（var） | 等宽字体，例如 `Typeface.MONOSPACE` 或 Assets 中字体 |
| `useLightTheme` | `Boolean`（var） | 切换浅色/深色基底（OSC 动态改色仍作为覆盖生效） |
| `actionModeCustomizer` | `ActionModeCustomizer?`（var） | 定制长按选择的浮动工具栏 |
| `extraKeysModifierReader` | `(() -> ExtraKeysModifierSnapshot)?`（var） | 外部粘性修饰键状态快照提供者 |
| `stopTextSelectionMode()` | fun | 手动退出文本选择模式 |
| `toggleIme(show: Boolean? = null)` | fun | 切换/强制软键盘显隐 |
| `toggleAutoScrollDisabled()` | fun | 切换自动滚动禁用（配合滚动锁定） |
| `pasteTextFromClipboard()` | fun | 从剪贴板粘贴到终端 |
| `dispose()` | fun | 释放协程、移除触摸模式监听，View 分离时**必须**调用 |
| `inputVirtualKeyCodePoint(codePoint, controlDown, leftAltDown)` | fun | 注入扩展按键栏产生的 Unicode 码点（`controlDown`/`leftAltDown` 默认 `false`） |
| `onKeyDown(keyCode, event)` | fun | 注入完整 `KeyEvent`（扩展键/物理键盘复用） |
| `onKeyUp(keyCode, event)` | fun | 与 `onKeyDown` 对称；扩展键栏如需注入松开事件可调用 |

内置手势行为：

| 手势 | 行为 |
|------|------|
| 单击 | 唤起软键盘；选择模式下点击空白处退出选择 |
| 双击 | 唤起软键盘（仅鼠标追踪模式生效） |
| 长按 | 进入文本选择（选择单词、显示 Copy/Paste 浮动工具栏与拖动手柄） |
| 拖动 | 历史回滚导航 |
| 双指缩放 | 调整字号 |
| 滚轮 / 触控板 | 历史导航或鼠标追踪事件（视 vt 模式） |

> 注意：选择模式在窗口失焦（`onWindowFocusChanged`）、View 失焦（`onFocusChanged`）时自动退出，

### 3.2 `com.awkoo.libterminal.engine.TerminalSession`

会话 = 一个子进程 + 一个模拟器。构造时不启动，需调用 `execute()`。

| 成员 | 类型 | 说明 |
|------|------|------|
| `id` | `Int` | 会话 ID |
| `pid` | `Int` | 子进程 PID（`-1` 表示未运行） |
| `sessionName` | `MutableStateFlow<String>` | 会话名（顶栏显示用） |
| `titleState` | `StateFlow<String?>` | OSC 0/1/2 设置的终端标题（如 vim 标签） |
| `isRunning` | `Boolean` | `pid > 0` 即为运行中 |
| `exitStatus` | `Int` | 进程退出码（负数表示信号编号） |
| `isRemove` | `MutableStateFlow<Boolean>` | 请求移除标记（回车退出场景） |
| `execute()` | fun | 启动进程与 I/O 协程 |
| `write(data)` | fun | 写入字节 / 字符串到进程 stdin |
| `reset()` | fun | 复位模拟器状态 |
| `finishIfRunning()` | fun | SIGKILL 终止进程 |

### 3.3 `com.awkoo.libterminal.view.interact.ActionModeCustomizer` / `ActionModeItem`

自定义长按选择后浮动工具栏的文字与额外按钮（可做本地化 / 添加"分享"等操作）：

```kotlin
terminalView.actionModeCustomizer = object : ActionModeCustomizer() {
    override fun copyText() = "复制"
    override fun pasteText() = "粘贴"
    override fun createActionModeItems() = listOf(
        ActionModeItem(title = "分享", icon = myShareIcon) { selectedText -> share(selectedText) }
    )
}
```

### 3.4 `com.awkoo.libterminal.view.ExtraKeysModifierSnapshot`

外部修饰键状态快照（Ctrl/Alt/Shift/Fn），配合 `TerminalView.extraKeysModifierReader`，
使物理键盘与扩展按键栏共享同一套粘性修饰键状态：

```kotlin
view.extraKeysModifierReader = {
    ExtraKeysModifierSnapshot(ctrl = ctrlOn, alt = altOn, shift = shiftOn, fn = fnOn)
}
```

`ExtraKeysModifierSnapshot` 为内联值类，构造函数四个参数均有默认值（`false`），
底层 `mask` 属性位掩码编码四个修饰键，一般无需直接操作。

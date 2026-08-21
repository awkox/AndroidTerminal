# AndroidTerminal

一款基于原生 Android 开发的终端模拟器应用，内置完整的 VT100/xterm 终端仿真与本地 PTY 子进程管理，可在设备上直接运行 shell 会话。

## 软件功能

### 终端仿真
- 完整的 VT100/xterm 终端仿真：支持 ANSI 转义序列解析、DEC 私有模式、滚动区域、备用屏幕缓冲区、字符集切换（含线绘制字符）等
- OSC 序列处理（窗口标题等）与 DCS 设备控制串处理
- UTF-8 解码与宽字符（CJK / Emoji）宽度计算，正确处理全角字符排版
- 主/备屏幕缓冲区独立保存光标状态，支持行重排（Reflow）

### 会话管理
- 多会话并行运行，会话列表抽屉快速切换
- 前台服务托管会话生命周期，退出界面后会话继续存活
- 通知栏快捷操作：获取/释放 WakeLock，防止后台休眠中断任务
- 无会话时服务自动停止，不常驻耗电

### 交互体验
- 自定义 Canvas 渲染的终端视图，支持双指缩放调整字体大小
- 手势操作：长按唤出文本选择模式，带选择手柄，支持复制/粘贴
- 可配置光标样式与光标闪烁
- 终端字体大小持久化保存（DataStore）
- 内置 Maple Mono NF CN 等宽字体，开箱即用中文与 Nerd Font 图标

### 扩展按键
- Termux 风格双行扩展按键栏：ESC / DEL / INS / HOME / END / PGUP / PGDN / TAB / 方向键
- CTRL、ALT 修饰键支持上滑弹出组合键（popup）
- 按键布局通过 Kotlin Serialization + ProtoBuf 序列化，可自定义扩展

## 技术栈

| 类别 | 技术 |
|------|------|
| 语言 | Kotlin 2.4 / Java 21 / C23 & C++23 |
| UI | Jetpack Compose (Material 3) + 自定义 View |
| 依赖注入 | Hilt |
| 数据存储 | DataStore Preferences |
| 序列化 | Kotlin Serialization (ProtoBuf) |
| 日志 | Timber |
| 原生层 | NDK r29 + CMake 4.1（JNI 实现 PTY） |

## 项目结构

```
AndroidTerminal/
├── app/
│   ├── cpp/                          # 原生代码（JNI）
│   │   ├── include/macro.h           # 公共宏定义
│   │   └── pty/pty.cpp               # PTY 创建与子进程管理（forkpty + execve）
│   └── src/main/
│       ├── kotlin/com/awkoo/terminal/
│       │   ├── Application.kt        # Hilt 应用入口，Timber 初始化
│       │   ├── TerminalService.kt    # 前台服务：托管会话、通知栏控制
│       │   ├── Constants.kt          # 全局常量
│       │   ├── constants/            # 枚举常量（日志级别、光标样式）
│       │   ├── core/                 # 核心逻辑层
│       │   │   ├── TerminalEmulator.kt    # VT100/xterm 终端仿真器
│       │   │   ├── AnsiEscapeParser.kt    # ANSI/CSI 转义序列解析
│       │   │   ├── OscHandler.kt          # OSC 序列处理
│       │   │   ├── DeviceControlHandler.kt# DCS 设备控制处理
│       │   │   ├── TerminalBuffer.kt      # 屏幕缓冲区（主/备屏）
│       │   │   ├── TerminalRow.kt         # 单行数据结构
│       │   │   ├── TerminalReflower.kt    # 行重排（窗口尺寸变化时）
│       │   │   ├── TerminalSession.kt     # 终端会话（子进程 + 仿真器）
│       │   │   ├── SessionManager.kt      # 会话集合管理
│       │   │   ├── LocalPtyProcess.kt     # 本地 PTY 进程（JNI 封装）
│       │   │   ├── ITerminalProcess.kt    # 进程抽象接口
│       │   │   ├── KeyHandler.kt          # 按键事件转终端序列
│       │   │   ├── InputSequenceEncoder.kt# 输入序列编码
│       │   │   ├── Utf8Decoder.kt         # UTF-8 流式解码
│       │   │   ├── UnicodeUtils.kt / WcWidth.kt # Unicode 与宽字符宽度
│       │   │   ├── TerminalColorScheme.kt / TerminalColors.kt # 配色方案
│       │   │   ├── AppPreferences.kt      # 用户偏好（DataStore）
│       │   │   └── TimberLogTree.kt       # 日志树实现
│       │   ├── extrakeys/            # 扩展按键栏
│       │   │   ├── ExtraKeysBar.kt        # 按键栏视图
│       │   │   ├── ExtraKey.kt            # 按键模型
│       │   │   ├── ExtraKeysConfig.kt     # 布局配置（可序列化）
│       │   │   ├── ExtraKeysModifierState.kt # CTRL/ALT 修饰键状态机
│       │   │   └── ExtraKeyDispatcher.kt  # 按键分发
│       │   ├── ui/
│       │   │   ├── MainActivity.kt   # Compose 宿主 Activity
│       │   │   ├── MainViewModel.kt  # 主界面状态
│       │   │   ├── compose/          # Compose 界面
│       │   │   │   ├── SessionListScreen.kt  # 会话列表页
│       │   │   │   ├── SessionViewScreen.kt  # 终端会话页
│       │   │   │   ├── SessionListDrawer.kt  # 会话切换抽屉
│       │   │   │   └── TopBar.kt             # 顶栏
│       │   │   └── view/             # 自定义终端 View 层
│       │   │       ├── TerminalView.kt         # 终端视图入口
│       │   │       ├── TerminalRenderer.kt     # Canvas 渲染
│       │   │       ├── TerminalTouchHandler.kt # 触摸处理
│       │   │       ├── GestureAndScaleRecognizer.kt # 缩放手势识别
│       │   │       ├── KeyInputProcessor.kt    # 按键输入处理
│       │   │       ├── ImeController.kt        # 输入法控制
│       │   │       ├── TerminalClipboard.kt    # 剪贴板
│       │   │       ├── TerminalBlinker.kt      # 光标闪烁
│       │   │       ├── SessionBinder.kt        # View 与会话绑定
│       │   │       └── textselection/          # 文本选择手柄组件
│       │   ├── assets/font/          # 内置等宽字体
│       │   └── res/                  # 资源文件
│   └── build.gradle.kts              # 模块构建配置
├── gradle/                           # Gradle Wrapper 与版本目录
├── .github/workflows/                # CI（编译 + CodeQL 安全扫描）
├── build.gradle.kts                  # 根构建配置
└── settings.gradle.kts               # 工程设置
```

### 核心数据流

```
TerminalService（前台服务）
    └── SessionManager（会话集合）
            └── TerminalSession（单个会话）
                    ├── LocalPtyProcess ←→ JNI ←→ C++ PTY 子进程
                    └── TerminalEmulator（解析输出 → 更新屏幕缓冲区）
                            └── TerminalView（Canvas 渲染屏幕内容）
```

## 构建

环境要求：

- JDK 21
- Android SDK（compileSdk 37）
- Android NDK r29（修改 `app/cpp/` 原生代码时必需）
- Gradle 9.7+（项目已含 Wrapper）

```bash
# Release 构建（CI 使用）
./gradlew assembleRelease

# Debug 构建
./gradlew assembleDebug
```

构建产物位于 `app/build/outputs/apk/release/app-release.apk`。

> 说明：`targetSdk` 固定为 28 是有意为之；Release 包使用调试签名密钥以保证可复现构建。

## 系统要求

- Android 9.0（API 28）及以上

## 许可证

本项目基于 [GPL-3.0](LICENSE) 协议开源。

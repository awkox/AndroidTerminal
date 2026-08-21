# AndroidTerminal

[![编译](https://github.com/awkox/AndroidTerminal/actions/workflows/ci.yml/badge.svg)](https://github.com/awkox/AndroidTerminal/actions/workflows/ci.yml)
[![CodeQL](https://github.com/awkox/AndroidTerminal/actions/workflows/codeql.yml/badge.svg)](https://github.com/awkox/AndroidTerminal/actions/workflows/codeql.yml)
[![License](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
![MinSdk](https://img.shields.io/badge/Android-9.0%2B-informational)

基于原生 Android 的终端模拟器，内置 VT100/xterm 仿真与本地 PTY 子进程管理。

## 功能特性

**终端仿真**：VT100/xterm 转义序列解析、DEC 私有模式、滚动区域、备用屏幕缓冲区、字符集切换（线绘字符）、OSC/DCS 序列处理、UTF-8 解码与宽字符（CJK/Emoji）宽度计算、主/备缓冲区独立光标状态、行重排（Reflow）。

**会话管理**：多会话并行，会话列表抽屉切换；前台服务托管生命周期，退出界面后会话继续存活；通知栏快捷操作；无会话时服务自动停止。

**交互体验**：自定义 Canvas 渲染终端视图，双指缩放字体；长按文本选择带手柄，支持复制/粘贴；可配置光标样式与闪烁，字体大小持久化（DataStore）；内置 Maple Mono NF CN 等宽字体。

**扩展按键**：Termux 风格双行扩展按键栏（ESC/DEL/INS/HOME/END/PGUP/PGDN/TAB/方向键）；CTRL/ALT 修饰键上滑弹出组合键；按键布局通过 Kotlin Serialization + ProtoBuf 序列化。

## 下载安装

要求 Android 9.0（API 28）及以上。暂未发布正式 Release：

1. **CI 构建产物**：[Actions](https://github.com/awkox/AndroidTerminal/actions) 页面选择成功的「编译」工作流，下载 Artifacts 中的 `package`（需登录 GitHub）。
2. **自行构建**：见[开发构建](#开发构建)。

> `targetSdk` 固定为 28 是有意为之；Release 包使用调试签名密钥以保证可复现构建。

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

## 架构概览

```
TerminalService（前台服务）
    └── SessionManager（会话集合）
            └── TerminalSession（单个会话）
                    ├── LocalPtyProcess ←→ JNI ←→ C++ PTY 子进程
                    └── TerminalEmulator（解析输出 → 更新屏幕缓冲区）
                            └── TerminalView（Canvas 渲染屏幕内容）
```

| 目录 | 职责 |
|------|------|
| `core/` | VT100/xterm 仿真器、转义序列解析、屏幕缓冲区与行重排、会话管理、PTY 进程封装、UTF-8 解码与字符宽度计算 |
| `ui/compose/` | Compose 界面：会话列表页、终端会话页、会话切换抽屉 |
| `ui/view/` | 自定义终端 View：Canvas 渲染、触摸与缩放手势、文本选择、IME 与剪贴板 |
| `extrakeys/` | 扩展按键栏：按键模型、布局配置序列化、CTRL/ALT 修饰键状态机 |
| `app/cpp/` | JNI 实现 PTY 创建与子进程管理（forkpty + execve） |
| `docs/` | 项目文档 |

## 开发构建

环境：JDK 21、Android SDK（compileSdk 37）、Android NDK r29（修改 `app/cpp/` 时必需）。Gradle Wrapper 随仓库提供。

```bash
./gradlew assembleDebug    # Debug 构建
./gradlew assembleRelease  # Release 构建（CI 使用）
```

产物位于 `app/build/outputs/apk/release/app-release.apk`。

## 项目文档

- [生命周期与进程模型](docs/Lifecycle-and-Process-Model.md)：从 Application、前台服务、会话管理器到原生 PTY 子进程的完整生命周期，协程模型、I/O 数据流与状态机
- [屏幕缓冲区架构设计](docs/ScreenBuffer.md)：双缓冲区机制、样式位压缩编码、复杂文本布局与行重排
- [支持的 xterm 转义序列](docs/xterm-sequences.md)：仿真器支持的全部转义序列参考，含输入方向编码、终端响应与已知限制

## 鸣谢

**参考项目**：
- [Termux](https://github.com/termux/termux-app)：扩展按键栏设计参考；`WcWidth.kt` 与 [termux/wcwidth](https://github.com/termux/wcwidth)、[libandroid-support](https://github.com/termux/libandroid-support) 保持同步
- [jquast/wcwidth](https://github.com/jquast/wcwidth)：Unicode 字符宽度表移植来源

**开源依赖**：
- [Jetpack Compose](https://developer.android.com/compose) / [Material 3](https://developer.android.com/develop/ui/compose/designsystems/material3)
- [Hilt](https://dagger.dev/hilt/)
- [DataStore Preferences](https://developer.android.com/topic/libraries/architecture/datastore)
- [Kotlinx Serialization](https://github.com/Kotlin/kotlinx.serialization)
- [Timber](https://github.com/JakeWharton/timber)
- [LeakCanary](https://github.com/square/leakcanary)
- [Compose Settings](https://github.com/alorma/Compose-Settings)
- [desugar_jdk_libs](https://github.com/google/desugar_jdk_libs)
- [Maple Mono](https://github.com/subframe7536/maple-font)：内置等宽字体 Maple Mono NF CN

## 许可证

[GPL-3.0](LICENSE)

# AndroidTerminal

[![编译](https://github.com/awkox/AndroidTerminal/actions/workflows/ci.yml/badge.svg)](https://github.com/awkox/AndroidTerminal/actions/workflows/ci.yml)
[![CodeQL](https://github.com/awkox/AndroidTerminal/actions/workflows/codeql.yml/badge.svg)](https://github.com/awkox/AndroidTerminal/actions/workflows/codeql.yml)
[![License](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
![MinSdk](https://img.shields.io/badge/Android-9.0%2B-informational)

一款基于原生 Android 开发的终端模拟器应用，内置完整的 VT100/xterm 终端仿真与本地 PTY 子进程管理，可在设备上直接运行 shell 会话。

## 功能特性

### 终端仿真
- 完整的 VT100/xterm 终端仿真：ANSI 转义序列解析、DEC 私有模式、滚动区域、备用屏幕缓冲区、字符集切换（含线绘制字符）等
- OSC 序列处理（窗口标题等）与 DCS 设备控制串处理
- UTF-8 解码与宽字符（CJK / Emoji）宽度计算，正确处理全角字符排版
- 主/备屏幕缓冲区独立保存光标状态，支持行重排（Reflow）

### 会话管理
- 多会话并行运行，会话列表抽屉快速切换
- 前台服务托管会话生命周期，退出界面后会话继续存活
- 通知栏快捷操作：获取/释放 WakeLock，防止后台休眠中断任务
- 无会话时服务自动停止，不常驻耗电

### 交互体验
- 自定义 Canvas 渲染的终端视图，双指缩放调整字体大小
- 手势操作：长按唤出文本选择模式，带选择手柄，支持复制/粘贴
- 可配置光标样式与光标闪烁，字体大小持久化保存（DataStore）
- 内置 Maple Mono NF CN 等宽字体，开箱即用中文与 Nerd Font 图标

### 扩展按键
- Termux 风格双行扩展按键栏：ESC / DEL / INS / HOME / END / PGUP / PGDN / TAB / 方向键
- CTRL、ALT 修饰键支持上滑弹出组合键（popup）
- 按键布局通过 Kotlin Serialization + ProtoBuf 序列化，可自定义扩展

## 下载安装

系统要求：Android 9.0（API 28）及以上。

本项目暂未发布正式 Release 版本，可通过以下两种方式获取：

1. **下载 CI 构建产物**：前往 [Actions](https://github.com/awkox/AndroidTerminal/actions) 页面，选择任意一次成功的「编译」工作流运行，在页面底部的 Artifacts 中下载 `package`（即 Release APK）。下载构建产物需要登录 GitHub 账号。
2. **自行构建**：参见下文[开发构建](#开发构建)。

> 说明：`targetSdk` 固定为 28 是有意为之；Release 包使用调试签名密钥以保证可复现构建。

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

核心数据流：

```
TerminalService（前台服务）
    └── SessionManager（会话集合）
            └── TerminalSession（单个会话）
                    ├── LocalPtyProcess ←→ JNI ←→ C++ PTY 子进程
                    └── TerminalEmulator（解析输出 → 更新屏幕缓冲区）
                            └── TerminalView（Canvas 渲染屏幕内容）
```

主要目录职责：

| 目录 | 职责 |
|------|------|
| `app/src/main/kotlin/com/awkoo/terminal/core/` | 核心逻辑层：VT100/xterm 仿真器、转义序列解析、屏幕缓冲区与行重排、会话管理、PTY 进程封装、UTF-8 解码与字符宽度计算 |
| `app/src/main/kotlin/com/awkoo/terminal/ui/compose/` | Compose 界面层：会话列表页、终端会话页、会话切换抽屉 |
| `app/src/main/kotlin/com/awkoo/terminal/ui/view/` | 自定义终端 View 层：Canvas 渲染、触摸与缩放手势、文本选择、IME 与剪贴板控制 |
| `app/src/main/kotlin/com/awkoo/terminal/extrakeys/` | 扩展按键栏：按键模型、布局配置序列化、CTRL/ALT 修饰键状态机 |
| `app/cpp/` | 原生代码：JNI 实现的 PTY 创建与子进程管理（forkpty + execve），CMake 构建 |
| `docs/` | 项目文档 |

各目录内的具体文件说明可直接查阅源码，文件命名与其职责一一对应。

## 开发构建

环境要求：

- JDK 21
- Android SDK（compileSdk 37）
- Android NDK r29（仅修改 `app/cpp/` 原生代码时必需）
- Gradle Wrapper 已随仓库提供，无需单独安装 Gradle

```bash
# Debug 构建
./gradlew assembleDebug

# Release 构建（CI 使用）
./gradlew assembleRelease
```

构建产物位于 `app/build/outputs/apk/release/app-release.apk`。

> 提示：项目已启用 Gradle 配置缓存，首次构建可能较慢。

## 项目文档

- [生命周期与进程模型](docs/Lifecycle-and-Process-Model.md)：从 Application、前台服务、会话管理器到原生 PTY 子进程的完整生命周期，涵盖协程模型、I/O 数据流、状态机与资源清理机制
- [支持的 xterm 转义序列](docs/xterm-sequences.md)：仿真器所支持的全部转义序列参考，含输入方向编码、终端响应一览与已知限制
- [屏幕缓冲区架构设计](docs/ScreenBuffer.md)：双缓冲区机制、样式位压缩编码、复杂文本布局与行重排等实现细节

## 鸣谢

### 参考项目

- [Termux](https://github.com/termux/termux-app)：扩展按键栏设计参考；`WcWidth.kt` 与 [termux/wcwidth](https://github.com/termux/wcwidth)、[libandroid-support](https://github.com/termux/libandroid-support) 保持同步
- [jquast/wcwidth](https://github.com/jquast/wcwidth)：Unicode 字符宽度表的移植来源

### 开源依赖

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

本项目基于 [GPL-3.0](LICENSE) 协议开源。

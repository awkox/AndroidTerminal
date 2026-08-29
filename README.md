[![GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4.10-purple.svg)](https://kotlinlang.org)
[![Platform: Android](https://img.shields.io/badge/Platform-Android-green.svg)](https://developer.android.com)
[![minSdk](https://img.shields.io/badge/minSdk-28-orange.svg)](https://developer.android.com/studio)
[![GitHub Workflow Status](https://img.shields.io/github/actions/workflow/status/awkox/AndroidTerminal/ci.yml?label=编译&logo=github)](.github/workflows/ci.yml)


# AndroidTerminal

一个基于 Termux 并使用 Kotlin + Compose 重写的高性能 Android 终端模拟器

## 特性

- （待完善）

## 构建

（实际编译请查看[ci.yml](.github/workflows/ci.yml)）

环境要求：

- JDK 21
- Android SDK 37
- Android NDK 29.0.14206865

构建 debug 版本：

```bash
./gradlew assembleDebug
```

release 版本需要环境变量 `KEYSTORE_PASS`、`KEY_ALIAS`、`KEY_PASS` 指向 `app/release.keystore`：

```bash
./gradlew assembleRelease
```

## 项目结构

```
.
├── app/                    # 应用模块（namespace: com.awkoo.terminal）
│   └── src/main/
│       ├── cpp/            # 原生 PTY 实现（C23/C++23，JNI）
│       └── kotlin/com/awkoo/terminal/
│           ├── core/       # PTY 进程、会话管理
│           ├── extrakeys/  # 扩展按键栏
│           └── ui/         # Compose 界面（会话列表、终端视图）
└── libterminal/            # 终端模拟核心库（namespace: com.awkoo.libterminal）
    └── src/main/kotlin/com/awkoo/libterminal/
        ├── color/          # 颜色方案与稀疏调色板
        ├── engine/         # VT100/xterm 模拟器、ANSI 解析、会话
        ├── process/        # 终端进程抽象接口
        ├── text/           # 文本宽度、UTF-8 解码
        └── view/           # Canvas 渲染 View、文本选择、输入处理
```

## 技术栈

- Kotlin 2.4.10，Gradle 9.7.0，AGP 9.3.1
- Jetpack Compose (Material3)
- Hilt 依赖注入
- DataStore（Preferences）存储配置
- Kotlin Serialization
- 原生 JNI / CMake（C23/C++23）

## 贡献

欢迎通过 GitHub Issues 提交问题与建议，或通过 Pull Request 贡献代码

## 许可证

本项目基于 [GNU GPL v3](LICENSE) 开源，详见 LICENSE 文件。

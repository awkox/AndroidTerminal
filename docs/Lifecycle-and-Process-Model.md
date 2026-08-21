# 生命周期与进程模型

本文档详细描述 AndroidTerminal 中从应用启动到子进程终止的完整生命周期，以及各层之间的进程、线程与 I/O 模型。

## 目录

- [1. 整体架构](#1-整体架构)
- [2. 组件生命周期](#2-组件生命周期)
  - [2.1 Application](#21-application)
  - [2.2 TerminalService（前台服务）](#22-terminalservice前台服务)
  - [2.3 SessionManager（会话管理器）](#23-sessionmanager会话管理器)
  - [2.4 TerminalSession（终端会话）](#24-terminalsession终端会话)
  - [2.5 LocalPtyProcess（本地 PTY 进程）](#25-localptyprocess本地-pty-进程)
  - [2.6 原生子进程（C++ PTY 层）](#26-原生子进程c-pty-层)
- [3. 进程模型](#3-进程模型)
  - [3.1 PTY 机制](#31-pty-机制)
  - [3.2 父子进程关系](#32-父子进程关系)
  - [3.3 文件描述符管理](#33-文件描述符管理)
- [4. 协程与线程模型](#4-协程与线程模型)
  - [4.1 协程作用域总览](#41-协程作用域总览)
  - [4.2 单会话四协程](#42-单会话四协程)
- [5. I/O 数据流](#5-io-数据流)
  - [5.1 输入路径（用户 → 子进程）](#51-输入路径用户--子进程)
  - [5.2 输出路径（子进程 → 屏幕）](#52-输出路径子进程--屏幕)
  - [5.3 缓冲区池复用机制](#53-缓冲区池复用机制)
- [6. 状态机](#6-状态机)
  - [6.1 CommandInfo.ExecutionState](#61-commandinfoexecutionstate)
  - [6.2 会话生命周期状态转换](#62-会话生命周期状态转换)
- [7. 资源清理与泄漏防护](#7-资源清理与泄漏防护)
- [8. 设计决策与边界情况](#8-设计决策与边界情况)

---

## 1. 整体架构

```
┌─────────────────────────────────────────────────────────────────┐
│                        Android 应用进程                           │
│                                                                   │
│  ┌──────────────┐    ┌──────────────────────────────────────┐   │
│  │ Application  │    │         TerminalService (前台)         │   │
│  │  (Hilt 入口) │    │  ┌────────────────────────────────┐  │   │
│  └──────────────┘    │  │     SessionManager (Singleton) │  │   │
│                       │  │  ┌──────────────────────────┐  │  │   │
│                       │  │  │   TerminalSession #1     │  │  │   │
│                       │  │  │  ┌────────────────────┐  │  │  │   │
│                       │  │  │  │ LocalPtyProcess    │  │  │  │   │
│                       │  │  │  │ (JNI ↔ ptm fd)    │  │  │  │   │
│                       │  │  │  └─────────┬──────────┘  │  │  │   │
│                       │  │  │  ┌─────────▼──────────┐  │  │  │   │
│                       │  │  │  │ TerminalEmulator   │  │  │  │   │
│                       │  │  │  │ (VT100/xterm 解析) │  │  │  │   │
│                       │  │  │  └────────────────────┘  │  │  │   │
│                       │  │  └──────────────────────────┘  │  │   │
│                       │  │  ┌──────────────────────────┐  │  │   │
│                       │  │  │   TerminalSession #2 ... │  │  │   │
│                       │  │  └──────────────────────────┘  │  │   │
│                       │  └────────────────────────────────┘  │   │
│                       └──────────────────────────────────────┘   │
└─────────────────────────────────┬─────────────────────────────────┘
                                  │ PTY (主从设备对)
                                  ▼
┌─────────────────────────────────────────────────────────────────┐
│                    子进程 #1 (sh / 其他命令)                      │
│   stdin / stdout / stderr → pts (从设备)                         │
└─────────────────────────────────────────────────────────────────┘
```

核心分层：

| 层级 | 组件 | 职责 |
|------|------|------|
| 应用入口 | `Application` | Hilt 初始化、日志配置 |
| 服务层 | `TerminalService` | 前台服务托管、通知管理、服务自停 |
| 会话管理 | `SessionManager` | 会话集合维护、当前会话追踪、自动清理 |
| 会话层 | `TerminalSession` | 单会话生命周期、I/O 协程调度、模拟器桥接 |
| 进程封装 | `LocalPtyProcess` | JNI 调用、文件描述符封装、进程控制 |
| 原生层 | `pty.cpp` | `forkpty` + `execve`、termios 配置、子进程环境 |

---

## 2. 组件生命周期

### 2.1 Application

**类**: `com.awkoo.terminal.Application`

**生命周期**:

```
进程创建 → Application.onCreate() → 常驻进程直到被系统回收
```

**关键行为**:

1. **Hilt 注入**: `@HiltAndroidApp` 标记，触发 Dagger Hilt 依赖图构建
2. **日志初始化**: 种植 `TimberLogTree`，初始级别为 `VERBOSE`
3. **动态日志级别**: 通过 `preferences.logLevel` Flow 监听用户偏好，实时调整日志树级别

**协程作用域**: `SupervisorJob() + Dispatchers.Main.immediate`，与 Application 同生命周期，进程死亡时自动回收。

**设计要点**:
- `SessionManager` 作为 `@Singleton` 在此处的 Hilt 图中创建，生命周期等同于 Application 进程
- 即使 Activity 销毁重建，SessionManager 中的会话数据不会丢失

---

### 2.2 TerminalService（前台服务）

**类**: `com.awkoo.terminal.TerminalService`

**启动方式**: `ContextCompat.startForegroundService()`，由 `MainViewModel.addSession()` 在创建首个会话时触发。

**生命周期状态图**:

```
                    addSession() 且服务未运行
                           │
                           ▼
┌─────────┐    onStartCommand()    ┌──────────────┐
│ 未运行  │ ──────────────────────▶ │   前台运行   │
└─────────┘                          └──────┬───────┘
       ▲                                     │
       │                                     │ 无会话时 stopSelf()
       │                                     │ 或通知 EXIT 动作
       │                                     ▼
       │                              ┌──────────────┐
       └──────────────────────────── │   销毁中     │
              系统回收进程            └──────┬───────┘
                                              │
                                              ▼ onDestroy()
                                        ┌──────────────┐
                                        │   已销毁     │
                                        └──────────────┘
```

**onStartCommand() 关键步骤**:

1. **通知渠道创建**: `setupNotificationChannel()` 创建 `IMPORTANCE_LOW` 渠道（无声音、常驻显示）
2. **启动前台**: `startForeground(NOTIFICATION_ID, buildNotification())`，传入会话数量作为标题
3. **动作处理**:
   - `null`: 普通启动，无额外动作
   - `NotificationActions.EXIT`: 调用 `stopSelf()` 终止服务
4. **会话监听注册**（仅首次）: 订阅 `sessionManager.sessionList`，每次变更调用 `updateNotification()`
5. **返回值**: `START_NOT_STICKY` — 服务被系统杀死后**不会**自动重启，需用户下次主动启动

**onDestroy() 关键步骤**:

1. `serviceScope.cancel()` — 取消服务级所有协程
2. `sessionManager.clear()` — 杀死所有会话进程并清空列表
3. `stopForeground(STOP_FOREGROUND_REMOVE)` — 移除前台通知
4. `isRunning = false`

**自动停止机制**:

`updateNotification()` 中实现了服务自停逻辑：

```kotlin
@Synchronized
private fun updateNotification() {
    if (sessionManager.isSessionsListEmpty) {
        stopSelf()  // 无会话 → 停止服务
    } else {
        startForeground(NOTIFICATION_ID, buildNotification())
    }
}
```

**通知内容**:
- 标题: `"{N} session(s)"` 实时反映当前会话数
- 动作按钮: `EXIT` — 通过 `PendingIntent` 发送 `ACTION_STOP_SERVICE`，终止服务并杀死所有会话

**设计要点**:
- 服务不绑定（`onBind()` 返回 `null`），UI 层通过 Hilt 注入的 `SessionManager` 单例间接交互
- `START_NOT_STICKY` 确保异常被杀后不会留下无会话的空服务
- 通知使用 `FLAG_IMMUTABLE`，符合 Android 12+ 安全要求

---

### 2.3 SessionManager（会话管理器）

**类**: `com.awkoo.terminal.core.SessionManager`
**作用域**: `@Singleton`，Hilt 管理，生命周期等同于 Application 进程

**核心状态**:

| 字段 | 类型 | 说明 |
|------|------|------|
| `_sessionList` | `MutableStateFlow<List<TerminalSession>>` | 会话列表，响应式数据流 |
| `currentSessionId` | `MutableStateFlow<Int>` | 当前选中会话 ID |
| `scope` | `CoroutineScope` | `SupervisorJob() + Dispatchers.IO` |

**生命周期操作**:

#### addSession(commandInfo)

```
创建 TerminalSession
       │
       ▼
session.execute()  ← 启动子进程 + 4个协程
       │
       ▼
加入 _sessionList
       │
       ▼
currentSessionId → 新会话 ID
       │
       ▼
启动自动清理协程（combine isRemove + sessionList）
```

**自动清理协程**是关键设计：

```kotlin
scope.launch {
    combine(targetSession.isRemove, _sessionList) { isRemove, list ->
        isRemove || list.none { it.id == targetSession.id }
    }.first { it }  // 等待任一条件成立

    removeSession(targetSession.id)
}
```

该协程在以下任一条件满足时触发清理：
1. 会话自身 `isRemove` 变为 `true`（用户在进程结束后按 Enter）
2. 会话已被其他手段从 `_sessionList` 中移除

双重条件确保**不会出现孤儿会话**，协程自然结束后无内存泄漏。

#### removeSession(id)

1. 若移除的是当前会话，自动切换到列表中最后一个其他会话（或 0 表示无）
2. 从 `_sessionList` 中过滤掉目标会话

> **注意**: `removeSession` 仅从列表移除，**不主动杀死进程**。进程终止由 `TerminalSession.finishIfRunning()` 或子进程自然退出处理。

#### clear()

服务销毁时调用，执行完整清理：

1. `killAllSessions()` — 遍历所有会话调用 `finishIfRunning()`（发送 `SIGKILL`）
2. `currentSessionId` 重置为 0
3. `_sessionList` 清空
4. `CommandInfo.endId` 原子计数器重置为 0

**设计要点**:
- 所有修改 `_sessionList` 的方法均标记 `@Synchronized`，保证线程安全
- `currentSession` 通过 `combine(currentSessionId, _sessionList)` 派生，自动处理会话被移除后的空状态
- 会话 ID 由 `CommandInfo.endId` 全局原子计数器分配，保证唯一性

---

### 2.4 TerminalSession（终端会话）

**类**: `com.awkoo.terminal.core.TerminalSession`

每个会话对应一个独立的子进程 + 一个终端模拟器实例，是生命周期管理的核心单元。

**完整生命周期**:

```
构造 (init)
  │  ├─ 设置环境变量 TERM=xterm-256color, COLORTERM=truecolor
  │  └─ 预创建 64 个 4KB 缓冲区入池
  │
  ▼
execute()
  │  ├─ state → EXECUTING
  │  ├─ processFactory() 创建 LocalPtyProcess（fork 子进程）
  │  ├─ commandInfo.pid = 子进程 PID
  │  ├─ launchInputReader()    ← 协程 1
  │  ├─ launchOutputWriter()   ← 协程 2
  │  ├─ launchEmulatorProcessor() ← 协程 3
  │  └─ launchExitHandler()    ← 协程 4
  │
  ▼
运行中 (isRunning = true)
  │  ◄──────────────────────────┐
  │  用户输入 → write()         │
  │  进程输出 → 模拟器解析       │
  │  屏幕更新 → uiEvent 通知 UI │
  │                             │
  ▼                             │ updateSize()
子进程退出                       │ (resize 窗口)
  │                             │
  ▼                             │
exitHandler 捕获退出码           │
  │                             │
  ▼                             │
handleProcessExit()              │
  │  ├─ 排空 terminalReadChannel 剩余数据
  │  ├─ 追加 "[Process completed ... - press Enter]"
  │  ├─ state → SUCCESS / EXECUTED
  │  └─ pid = -1, isRunning = false
  │
  ▼
等待用户按 Enter
  │
  ▼
write('\n') → isRemove = true
  │
  ▼
SessionManager 自动清理协程触发
  │
  ▼
removeSession() → 从列表移除
  │
  ▼
scope.cancel() → 所有协程终止
```

**构造阶段 (init)**:

1. **环境变量注入**:
   - `TERM=xterm-256color` — 声明终端能力
   - `COLORTERM=truecolor` — 声明 24 位真彩色支持
2. **缓冲区池初始化**: 创建 64 个 `DataChunk(ByteArray(4096), 0)` 放入 `terminalReadBufferPoolChannel`

**execute() 阶段**:

通过 `processFactory` 函数式接口创建进程，解耦具体进程实现（当前为 `LocalPtyProcess`，未来可扩展 SSH/Mock）：

```kotlin
val p = processFactory(
    commandInfo, emulator.mRows, emulator.mColumns,
    emulator.mCellWidthPixels, emulator.mCellHeightPixels
)
```

**进程退出处理 (handleProcessExit)**:

退出码约定：
- `> 0`: 正常退出，值为退出码
- `== 0`: 成功退出
- `< 0`: 被信号终止，绝对值为信号编号（如 `-9` 表示 `SIGKILL`）

退出后在屏幕追加提示文本：
```
[Process completed (code 1) - press Enter]
```

此时会话仍保留在列表中，**用户按 Enter 键**才触发移除：

```kotlin
fun write(data: ByteArray) {
    if (this.isRunning) {
        terminalWriteChannel.trySend(data)
    } else if (data.size == 1 &&
        (data[0] == '\n'.code.toByte() || data[0] == '\r'.code.toByte())) {
        isRemove.update { true }  // 进程已结束 + 按回车 → 请求移除
    }
}
```

**关键方法**:

| 方法 | 作用 | 线程安全 |
|------|------|----------|
| `execute()` | 启动进程与协程 | 仅调用一次 |
| `updateSize()` | 通知 PTY + 模拟器调整尺寸 | `synchronized(emulator)` |
| `write(ByteArray)` | 向子进程 stdin 写入 | 无锁，Channel 保证 |
| `writeCodePoint()` | UTF-8 编码后写入 | 无锁 |
| `reset()` | 重置模拟器状态 | `synchronized(emulator)` |
| `finishIfRunning()` | 发送 `SIGKILL` | 无锁 |

**设计要点**:
- 会话**不持有 Context 引用**，可独立于 UI 存活
- `uiEvent` 使用 `MutableSharedFlow`，`extraBufferCapacity=1` + `DROP_OLDEST`，确保屏幕更新不阻塞 I/O
- `emulator` 所有访问均需 `synchronized(emulator)`，因为模拟器处理在 `Dispatchers.Default`，而 UI 渲染在主线程

---

### 2.5 LocalPtyProcess（本地 PTY 进程）

**类**: `com.awkoo.terminal.core.LocalPtyProcess`
**实现接口**: `ITerminalProcess`

**接口抽象**:

```kotlin
interface ITerminalProcess {
    val pid: Int
    val inputStream: InputStream   // 子进程 stdout → 应用读取
    val outputStream: OutputStream // 应用写入 → 子进程 stdin
    fun resize(columns, rows, cellWidthPixels, cellHeightPixels)
    fun waitFor(): Int             // 阻塞等待退出
    fun kill()                     // SIGKILL
    fun close()                    // 释放 fd
}
```

> **注意命名方向**: `inputStream` 是**从子进程输出读取**的流（子进程 stdout → 应用），`outputStream` 是**写入子进程输入**的流（应用 → 子进程 stdin）。

**生命周期**:

```
init 构造
  │
  ├─ System.loadLibrary("pty")  ← 类加载时一次性加载
  │
  ├─ JNI createSubprocess()
  │    ├─ posix_openpt → ptm fd
  │    ├─ grantpt / unlockpt / ptsname_r
  │    ├─ termios 配置 (IUTF8, 无软件流控)
  │    ├─ TIOCSWINSZ 设置初始窗口
  │    └─ fork() → 子进程 execvp(cmd)
  │
  ├─ pid = 子进程 PID
  ├─ fdObj = ParcelFileDescriptor.adoptFd(ptm)
  ├─ inputStream = FileInputStream(fdObj.fileDescriptor)
  └─ outputStream = FileOutputStream(fdObj.fileDescriptor)
       │
       ▼
运行中
  │  ◄── resize() → ioctl TIOCSWINSZ
  │  ◄── kill()   → Os.kill(pid, SIGKILL)
  │  ◄── waitFor()→ JNI waitpid(pid)
  │
       ▼
close()
  └─ fdObj.close() → 关闭 ptm 文件描述符
```

**JNI 方法映射**:

| Kotlin 方法 | JNI 函数 | 系统调用 |
|-------------|----------|----------|
| `createSubprocess()` | `Java_..._createSubprocess` | `posix_openpt`, `grantpt`, `unlockpt`, `ptsname_r`, `fork`, `execvp` |
| `setPtyWindowSize()` | `Java_..._setPtyWindowSize` | `ioctl(TIOCSWINSZ)` |
| `waitFor(pid)` | `Java_..._waitFor` | `waitpid(pid, &status, 0)` |

**退出码转换** (JNI `waitFor`):

```c
if (WIFEXITED(status))   return WEXITSTATUS(status);   // 0~255
else if (WIFSIGNALED(status)) return -WTERMSIG(status); // 负数 = 信号号
```

**设计要点**:
- `ParcelFileDescriptor.adoptFd()` 接管原生 fd 的生命周期，GC 时自动关闭
- `kill()` 使用 `SIGKILL`（不可捕获），确保进程一定终止
- `resize()` 仅在 `fdObj` 非空时操作，避免已关闭后的空指针

---

### 2.6 原生子进程（C++ PTY 层）

**文件**: `app/cpp/pty/pty.cpp`

子进程创建是整个进程模型的底层基础，使用标准 UNIX PTY 机制。

**create_subprocess() 完整流程**:

```
父进程（应用）                          子进程（shell）
─────────────                          ─────────────
posix_openpt(O_RDWR|O_CLOEXEC)
       │
grantpt(ptm)                           ──┐
unlockpt(ptm)                             │
ptsname_r(ptm, devname)                  │
       │                                  │
tcgetattr → 设置 IUTF8                   │
            禁用 IXON/IXOFF               │
tcsetattr(TCSANOW)                        │
       │                                  │
ioctl(TIOCSWINSZ) 设置窗口大小            │
       │                                  │
fork() ───────────────────────────────────┤
       │                                  │
       │                          sigfillset + sigprocmask(UNBLOCK)
       │                          close(ptm)
       │                          setsid() ← 创建新会话，脱离控制终端
       │                          open(devname) → pts fd
       │                          dup2(pts, STDIN/STDOUT/STDERR)
       │                          close(pts)
       │                          关闭 3~max_fd 所有多余 fd
       │                          setenv() 注入环境变量
       │                          setenv("PWD", cwd)
       │                          chdir(cwd)
       │                          execvp(cmd, argv)
       │                                  │
       │                          失败 → _exit(127)
       │
return ptm, *pProcessId = pid
```

**关键系统调用说明**:

| 调用 | 作用 |
|------|------|
| `posix_openpt(O_RDWR \| O_CLOEXEC)` | 打开 PTY 主设备（master），`O_CLOEXEC` 确保 exec 时自动关闭 |
| `grantpt(ptm)` | 更改从设备属主为当前用户，授予读写权限 |
| `unlockpt(ptm)` | 解锁从设备，使其可被打开 |
| `ptsname_r(ptm, ...)` | 线程安全地获取从设备路径（如 `/dev/pts/0`） |
| `setsid()` | 子进程创建新会话，成为会话首进程，脱离原控制终端 |
| `dup2(pts, 0/1/2)` | 将从设备复制为 stdin/stdout/stderr |
| `execvp(cmd, argv)` | 用目标程序替换子进程镜像 |

**termios 配置**:

```c
tios.c_iflag |= IUTF8;           // 启用 UTF-8 输入模式
tios.c_iflag &= ~(IXON | IXOFF); // 禁用 XON/XOFF 软件流控
```

- `IUTF8`: 让内核正确处理 UTF-8 多字节字符的擦除（按字符而非字节退格）
- 禁用软件流控: 避免 `Ctrl+S`/`Ctrl+Q` 被终端驱动拦截，透传给应用

**窗口大小结构**:

```c
struct winsize sz = {
    .ws_row = rows,
    .ws_col = columns,
    .ws_xpixel = columns * cell_width,   // 像素宽度
    .ws_ypixel = rows * cell_height      // 像素高度
};
```

子进程中的程序（如 `vim`、`tmux`）可通过 `ioctl(TIOCGWINSZ)` 查询此大小，并在尺寸变化时收到 `SIGWINCH` 信号。

**文件描述符清理**:

子进程在 `execvp` 前关闭所有 `STDERR_FILENO + 1`（即 3）以上的文件描述符，上限取 `RLIMIT_NOFILE` 的当前值（默认 1024）。这防止父进程的其他 fd 意外泄漏到子进程。

**失败处理**:
- `execvp` 失败时，向 stderr 写入错误信息并 `_exit(127)`
- `127` 是 shell 约定的"命令未找到"退出码

---

## 3. 进程模型

### 3.1 PTY 机制

PTY（Pseudo-Terminal，伪终端）是一对互联的字符设备：

```
┌──────────────┐      写入 ──────▶      ┌──────────────┐
│   ptm (主)   │                          │   pts (从)   │
│  应用进程持有 │      ◀────── 读取       │  子进程持有   │
└──────────────┘                          └──────────────┘
```

- **主设备 (ptm)**: 应用进程通过 `LocalPtyProcess` 持有，读写对应子进程的输出/输入
- **从设备 (pts)**: 子进程的 `stdin/stdout/stderr` 全部指向此设备

写入主设备的数据会作为从设备的输入出现，反之亦然。这使得子进程认为自己连接在一个真实终端上。

### 3.2 父子进程关系

```
Android 应用进程 (PID = P_app)
  │
  ├─ fork() ──▶ 子进程 #1 (PID = P1)  execvp("sh", ...)
  │
  ├─ fork() ──▶ 子进程 #2 (PID = P2)  execvp("sh", ...)
  │
  └─ ...
```

- 每个 `TerminalSession` 对应**一次 `fork()`**，产生一个独立子进程
- 子进程通过 `setsid()` 创建新会话，**不再是应用进程的进程组成员**
- 子进程的父进程（PPID）是应用进程，但由于 `setsid()`，它拥有独立的会话 ID 和进程组 ID

**多会话并行**: N 个会话 = N 个独立子进程，各自拥有独立的 PTY 对、独立的 stdin/stdout/stderr，互不干扰。

### 3.3 文件描述符管理

**应用进程侧**:

| fd | 来源 | 生命周期 |
|----|------|----------|
| ptm fd | `posix_openpt` | `LocalPtyProcess` 对象存活期，`close()` 时释放 |
| `FileInputStream` | 包装 ptm fd | `inputStream.use {}` 块结束自动关闭 |
| `FileOutputStream` | 包装 ptm fd | `outputStream.use {}` 块结束自动关闭 |

**子进程侧**:

| fd | 指向 |
|----|------|
| 0 (stdin) | pts 从设备 |
| 1 (stdout) | pts 从设备 |
| 2 (stderr) | pts 从设备 |
| 3+ | 全部关闭 |

**O_CLOEXEC 的作用**: `posix_openpt` 时指定 `O_CLOEXEC`，确保子进程 `execvp` 后 ptm fd 自动关闭，不会泄漏到子进程中。

---

## 4. 协程与线程模型

### 4.1 协程作用域总览

```
Application
  └─ scope: SupervisorJob + Main.immediate  (日志级别监听)

TerminalService
  └─ serviceScope: SupervisorJob + Main.immediate  (通知更新)

SessionManager (Singleton)
  └─ scope: SupervisorJob + IO  (会话自动清理协程)

TerminalSession (每个会话独立)
  └─ scope: SupervisorJob + IO
       ├─ inputReader:       IO 调度器
       ├─ outputWriter:      IO 调度器
       ├─ emulatorProcessor: Default 调度器 (显式指定)
       └─ exitHandler:       IO 调度器，收尾切 Main.immediate
```

**SupervisorJob 的意义**: 子协程失败不会取消兄弟协程。例如 `inputReader` 因 IOException 终止，`outputWriter` 和 `emulatorProcessor` 仍可继续运行直到 `exitHandler` 统一清理。

### 4.2 单会话四协程

每个 `TerminalSession.execute()` 启动 4 个协程，形成生产者-消费者管道：

```
┌─────────────────┐    terminalReadChannel     ┌────────────────────┐
│  inputReader    │ ──────────────────────────▶ │ emulatorProcessor  │
│  (读子进程输出)  │    (UNLIMITED 缓冲)         │  (解析→更新屏幕)   │
└─────────────────┘                              └─────────┬──────────┘
                                                            │ uiEvent
                                                            ▼
                                                          UI 渲染

┌─────────────────┐    terminalWriteChannel    ┌────────────────────┐
│  用户输入/模拟器 │ ──────────────────────────▶ │   outputWriter     │
│  write() 调用    │    (BUFFERED 缓冲)          │  (写子进程输入)     │
└─────────────────┘                              └────────────────────┘

┌─────────────────┐
│   exitHandler   │ ← 独立协程，waitFor() 阻塞等待子进程退出
└─────────────────┘
```

#### 协程 1: inputReader（输入读取器）

```kotlin
scope.launch {
    p.inputStream.use { termIn ->
        while (isActive) {
            val chunk = terminalReadBufferPoolChannel.receive()  // 从池取缓冲区
            val read = termIn.read(chunk.buffer)                   // 阻塞读
            chunk.length = read
            if (read != -1) {
                terminalReadChannel.send(chunk)                     // 送入处理管道
            } else {
                terminalReadBufferPoolChannel.trySend(chunk)        // EOF，归还缓冲区
                break
            }
        }
    }
}
```

- **调度器**: `Dispatchers.IO`
- **阻塞点**: `termIn.read()` — 阻塞等待子进程输出
- **终止条件**: 读到 EOF（子进程关闭 stdout）或协程被取消
- **资源管理**: `inputStream.use {}` 确保流自动关闭

#### 协程 2: outputWriter（输出写入器）

```kotlin
scope.launch {
    p.outputStream.use { termOut ->
        if (commandInfo.stdin != null) {
            termOut.write(commandInfo.stdin.toByteArray())  // 初始 stdin 注入
        }
        for (buffer in terminalWriteChannel) {               // 消费写入请求
            termOut.write(buffer, 0, buffer.size)
        }
    }
}
```

- **调度器**: `Dispatchers.IO`
- **阻塞点**: `termOut.write()` — 写入子进程 stdin（PTY 缓冲区满时阻塞）
- **初始输入**: 若 `commandInfo.stdin` 非空，启动时先写入
- **终止条件**: `terminalWriteChannel` 关闭（会话 scope 取消时）

#### 协程 3: emulatorProcessor（模拟器处理器）

```kotlin
scope.launch(Dispatchers.Default) {
    for (chunk in terminalReadChannel) {
        var bytesProcessed = chunk.length

        synchronized(emulator) {
            emulator.append(chunk.buffer, chunk.length)

            // 批量合并：尝试从 Channel 中拉取更多数据，减少 synchronized 次数
            while (bytesProcessed < 32 * 1024) {
                val moreChunk = terminalReadChannel.tryReceive().getOrNull() ?: break
                emulator.append(moreChunk.buffer, moreChunk.length)
                bytesProcessed += moreChunk.length
                terminalReadBufferPoolChannel.trySend(moreChunk)
            }
        }

        terminalReadBufferPoolChannel.trySend(chunk)  // 归还缓冲区
        notifyScreenUpdate()                            // 通知 UI 重绘
        yield()                                         // 让出调度权
    }
}
```

- **调度器**: `Dispatchers.Default`（显式指定，CPU 密集型解析）
- **批量优化**: 每次处理时尝试拉取最多 32KB 的累积数据，减少 `synchronized` 进入次数
- **线程安全**: `synchronized(emulator)` 保护模拟器状态，因为 UI 线程也会读取模拟器
- **节流**: `notifyScreenUpdate()` 通过 `SharedFlow(DROP_OLDEST)` 自然合并高频更新

#### 协程 4: exitHandler（退出处理器）

```kotlin
scope.launch {
    val exitCode = p.waitFor()          // 阻塞等待子进程退出
    p.close()                            // 关闭 PTY fd

    withContext(Dispatchers.Main.immediate) {
        handleProcessExit(exitCode)     // 主线程更新状态
    }

    scope.cancel()                       // 取消整个会话作用域
}
```

- **调度器**: `Dispatchers.IO`（waitFor 阻塞），收尾切 `Main.immediate`
- **阻塞点**: `p.waitFor()` → JNI `waitpid(pid, &status, 0)`
- **关键动作**: 子进程退出后，`scope.cancel()` 会级联取消其他 3 个协程
- **主线程切换**: `handleProcessExit` 在主线程执行，确保 UI 状态更新的线程安全

---

## 5. I/O 数据流

### 5.1 输入路径（用户 → 子进程）

```
用户按键 / 扩展按键 / 粘贴
       │
       ▼
TerminalView (onKeyEvent / 粘贴)
       │
       ▼
TerminalSession.write(ByteArray)
       │  若 isRunning=false 且是 Enter → isRemove=true
       │
       ▼
terminalWriteChannel.trySend(data)
       │  (Channel.BUFFERED, 背压时丢弃)
       │
       ▼
outputWriter 协程 (Dispatchers.IO)
       │
       ▼
FileOutputStream.write(buffer)
       │
       ▼
ptm (主设备) ──PTY 内核缓冲──▶ pts (从设备)
       │
       ▼
子进程 stdin (fd=0)
```

**特殊输入**:
- `writeCodePoint(prependEscape, codePoint)`: 将 Unicode 码点编码为 UTF-8 后写入，支持可选的 ESC 前缀（用于 Alt 组合键）
- `commandInfo.stdin`: 会话启动时一次性注入的初始输入（自动追加 `\r`）

### 5.2 输出路径（子进程 → 屏幕）

```
子进程 stdout / stderr (fd=1/2)
       │
       ▼
pts (从设备) ──PTY 内核缓冲──▶ ptm (主设备)
       │
       ▼
FileInputStream.read(chunk.buffer)  ← inputReader 协程 (Dispatchers.IO)
       │  从缓冲区池取 4KB 缓冲区
       │
       ▼
terminalReadChannel.send(chunk)  (UNLIMITED 缓冲)
       │
       ▼
emulatorProcessor 协程 (Dispatchers.Default)
       │
       ├─ synchronized(emulator)
       │    └─ TerminalEmulator.append(bytes, length)
       │         ├─ Utf8Decoder 解码
       │         ├─ AnsiEscapeParser 解析转义序列
       │         └─ 更新 TerminalBuffer（屏幕缓冲区）
       │
       ├─ 缓冲区归还到 terminalReadBufferPoolChannel
       │
       └─ notifyScreenUpdate()
            │
            ▼
       uiEvent.tryEmit(Unit)  (SharedFlow, DROP_OLDEST)
            │
            ▼
       TerminalView (Compose / 自定义 View) 监听 uiEvent
            │
            ▼
       Canvas 渲染 TerminalBuffer 内容
```

### 5.3 缓冲区池复用机制

为避免高频 I/O 产生大量临时 `ByteArray` 导致 GC 压力，实现了固定大小的缓冲区池：

```
初始化: 64 × DataChunk(ByteArray(4096), 0) → terminalReadBufferPoolChannel

使用流程:
  inputReader: receive() 取空缓冲区 → read() 填充 → send 到 terminalReadChannel
  emulatorProcessor: receive 取满缓冲区 → append 到模拟器 → trySend 归还到池
```

| 参数 | 值 | 说明 |
|------|-----|------|
| 池大小 | 64 | 最多同时存在 64 个在途缓冲区 |
| 单缓冲区 | 4096 字节 | 典型的一次 read 大小 |
| 池 Channel | `Channel(64)` | 有界，满时 `trySend` 失败则缓冲区被 GC |
| 数据 Channel | `Channel(UNLIMITED)` | 无界，保证子进程输出不丢失 |

**背压处理**: 若模拟器处理速度跟不上子进程输出速度，`terminalReadChannel`（UNLIMITED）会缓存所有数据，缓冲区池耗尽后新分配的缓冲区不再归还（直接 GC），不会阻塞子进程输出。

---

## 6. 状态机

### 6.1 CommandInfo.ExecutionState

`CommandInfo` 追踪每个命令的执行状态，状态只能单向前进（不可回退）：

```
PRE_EXECUTION ──▶ EXECUTING ──┬─▶ SUCCESS   (exitCode == 0)
                               │
                               ├─▶ EXECUTED  (exitCode != 0, 非信号)
                               │
                               └─▶ FAILED    (保留，当前未使用)
```

**状态转换规则**:

```kotlin
var state: ExecutionState = ExecutionState.PRE_EXECUTION
    set(value) {
        // 只允许前进，不允许回退；SUCCESS 为终态不可变更
        if (value.level < field.level || field == ExecutionState.SUCCESS) {
            return
        }
        field = value
    }
```

| 状态 | 触发时机 | level |
|------|----------|-------|
| `PRE_EXECUTION` | `CommandInfo` 构造时 | 0 |
| `EXECUTING` | `TerminalSession.execute()` 开始 | 1 |
| `EXECUTED` | 进程非零退出码退出 | 2 |
| `SUCCESS` | 进程零退出码退出 | 3 |
| `FAILED` | 预留状态 | 4 |

### 6.2 会话生命周期状态转换

结合 `isRunning`（pid > 0）和 `isRemove`，会话经历以下阶段：

```
┌─────────────┐     execute()      ┌─────────────┐
│  已创建      │ ─────────────────▶ │   运行中     │
│ (构造完成)   │                    │ (isRunning)  │
└─────────────┘                    └──────┬───────┘
                                           │
                              子进程自然退出 / kill()
                                           │
                                           ▼
                                    ┌─────────────┐
                                    │  已结束      │
                                    │ (isRunning=false)│
                                    │ 显示退出提示  │
                                    └──────┬───────┘
                                           │
                                      用户按 Enter
                                           │
                                           ▼
                                    ┌─────────────┐
                                    │  待移除      │
                                    │ (isRemove=true)│
                                    └──────┬───────┘
                                           │
                                  SessionManager 清理
                                           │
                                           ▼
                                    ┌─────────────┐
                                    │  已移除      │
                                    │ scope.cancel │
                                    └─────────────┘
```

---

## 7. 资源清理与泄漏防护

本项目在多个层级实施了资源清理保障：

### 7.1 协程泄漏防护

| 层级 | 机制 |
|------|------|
| `TerminalService` | `onDestroy()` 中 `serviceScope.cancel()` |
| `SessionManager` | 每个会话的自动清理协程使用 `first { it }`，条件满足后自然结束 |
| `TerminalSession` | `exitHandler` 在进程退出后调用 `scope.cancel()`，级联取消所有子协程 |

### 7.2 进程泄漏防护

| 场景 | 处理 |
|------|------|
| 服务正常销毁 | `onDestroy()` → `sessionManager.clear()` → 遍历 `finishIfRunning()` (SIGKILL) |
| 通知 EXIT 按钮 | `stopSelf()` → 触发 `onDestroy()` 同上 |
| 应用进程被系统杀死 | 子进程成为孤儿，init 收养；PTY 主端关闭后子进程 read 得到 EOF，通常自行退出 |
| 单会话用户关闭 | `isRemove=true` → `removeSession` 从列表移除；进程若仍在运行则由用户已通过其他方式终止 |

### 7.3 文件描述符泄漏防护

| 资源 | 清理方式 |
|------|----------|
| ptm fd | `LocalPtyProcess.close()` → `fdObj.close()`；`ParcelFileDescriptor` 有 finalizer 兜底 |
| `inputStream` | `inputStream.use {}` 块自动关闭 |
| `outputStream` | `outputStream.use {}` 块自动关闭 |
| 子进程多余 fd | `fork` 后 `execvp` 前关闭 3~RLIMIT_NOFILE 所有 fd |
| ptm fd 的 O_CLOEXEC | `posix_openpt` 时指定，exec 后子进程自动关闭主端 |

### 7.4 UI 层生命周期防护

`MainActivity` 监听会话列表，当所有会话移除时自动 `finish()`：

```kotlin
viewModel.sessionListState
    .flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
    .filter { it.isEmpty() }
    .drop(1)  // 跳过初始空状态
    .onEach { finish() }
    .launchIn(lifecycleScope)
```

`flowWithLifecycle` 确保 Flow 仅在 Activity 至少 STARTED 时收集，避免后台不必要的 UI 更新。

---

## 8. 设计决策与边界情况

### 8.1 为什么使用前台服务而非绑定服务？

- **后台存活**: 终端会话可能运行长时间任务（如编译、下载），前台服务优先级更高，不易被系统回收
- **用户感知**: 通知栏显示会话数量，用户可随时知晓应用状态并一键退出
- **解耦**: 不使用绑定机制，UI 层通过 Hilt 单例 `SessionManager` 交互，Activity 重建不影响会话

### 8.2 为什么 START_NOT_STICKY？

服务被系统杀死后，所有子进程通常也已终止（或成为孤儿后退出）。自动重启会创建一个无会话的空服务，毫无意义且耗电。因此选择不自动重启，等待用户下次主动打开应用。

### 8.3 为什么进程结束后不立即移除会话？

进程结束后，屏幕上可能还有用户需要查看的输出内容。保留会话直到用户按 Enter 确认，符合终端模拟器的常见行为（参考 Termux、macOS Terminal 的 `[Process completed]` 提示）。

### 8.4 子进程的 shell 是什么？

默认启动 `/system/bin/sh`（Android 内置的 mksh 或 toybox shell），工作目录为应用的 `filesDir`。通过 `ShellInfo` 自动注入 `HOME` 和 `TMPDIR` 环境变量。

### 8.5 多会话的 PID 与 ID 关系

- **会话 ID**: `CommandInfo.id`，由全局原子计数器 `endId` 分配，从 1 开始递增，`clear()` 时重置
- **进程 PID**: 由内核分配，`LocalPtyProcess.pid`，进程退出后置为 -1
- 两者独立，会话 ID 仅用于应用内标识，PID 用于系统调用（`kill`、`waitpid`）

### 8.6 窗口大小变化的处理流程

```
TerminalView 尺寸变化 (onSizeChanged / Compose onGloballyPositioned)
       │
       ▼
TerminalSession.updateSize(columns, rows, cellWidth, cellHeight)
       │
       ├─ process.resize() → JNI ioctl(TIOCSWINSZ) → 内核更新 PTY 窗口
       │                                                        │
       │                                                        ▼
       │                                                  子进程收到 SIGWINCH
       │
       └─ synchronized(emulator) { emulator.resize(...) }
              ├─ 调整屏幕缓冲区行列数
              └─ 触发 TerminalReflower 行重排（Reflow）
```

PTY 窗口大小更新后，内核会向子进程前台进程组发送 `SIGWINCH` 信号，全屏程序（如 `vim`、`tmux`、`htop`）捕获此信号后重新查询窗口大小并重绘。

### 8.7 信号处理

- **应用发送**: `TerminalSession.finishIfRunning()` → `Os.kill(pid, SIGKILL)`，强制终止
- **子进程接收**: 子进程可自行处理 `SIGINT`（Ctrl+C）、`SIGTSTP`（Ctrl+Z）等，这些信号由 PTY 行规程（line discipline）在读取到对应控制字符时自动生成
- **退出码**: 被信号终止的进程，`waitFor()` 返回负值（`-信号编号`），如 `-9` 表示 `SIGKILL`

---

## 相关文档

- [屏幕缓冲区架构设计](ScreenBuffer.md) — TerminalBuffer、TerminalRow、行重排等实现细节
- [支持的 xterm 转义序列](xterm-sequences.md) — 仿真器支持的全部转义序列参考

## 源码索引

| 组件 | 文件路径 |
|------|----------|
| Application | `app/src/main/kotlin/com/awkoo/terminal/Application.kt` |
| TerminalService | `app/src/main/kotlin/com/awkoo/terminal/TerminalService.kt` |
| SessionManager | `app/src/main/kotlin/com/awkoo/terminal/core/SessionManager.kt` |
| TerminalSession | `app/src/main/kotlin/com/awkoo/terminal/core/TerminalSession.kt` |
| LocalPtyProcess | `app/src/main/kotlin/com/awkoo/terminal/core/LocalPtyProcess.kt` |
| ITerminalProcess | `app/src/main/kotlin/com/awkoo/terminal/core/ITerminalProcess.kt` |
| CommandInfo | `app/src/main/kotlin/com/awkoo/terminal/core/CommandInfo.kt` |
| ShellInfo | `app/src/main/kotlin/com/awkoo/terminal/core/ShellInfo.kt` |
| 原生 PTY | `app/cpp/pty/pty.cpp` |
| MainActivity | `app/src/main/kotlin/com/awkoo/terminal/ui/MainActivity.kt` |
| MainViewModel | `app/src/main/kotlin/com/awkoo/terminal/ui/MainViewModel.kt` |

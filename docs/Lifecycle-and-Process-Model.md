# 生命周期与进程模型

AndroidTerminal 从应用启动到子进程终止的完整生命周期，以及各层之间的进程、线程与 I/O 模型。

## 目录

- [1. 整体架构](#1-整体架构)
- [2. 组件生命周期](#2-组件生命周期)
  - [2.1 Application](#21-application)
  - [2.2 TerminalService](#22-terminalservice)
  - [2.3 SessionManager](#23-sessionmanager)
  - [2.4 TerminalSession](#24-terminalsession)
  - [2.5 LocalPtyProcess](#25-localptyprocess)
  - [2.6 原生子进程](#26-原生子进程)
- [3. 进程模型](#3-进程模型)
- [4. 协程模型](#4-协程模型)
- [5. I/O 数据流](#5-io-数据流)
- [6. 状态机](#6-状态机)

---

## 1. 整体架构

```
Android 应用进程
├── Application (Hilt 入口)
└── TerminalService (前台服务)
    └── SessionManager (Singleton)
        ├── TerminalSession #1
        │   ├── LocalPtyProcess (JNI ↔ ptm fd)
        │   └── TerminalEmulator (VT100/xterm 解析)
        ├── TerminalSession #2 ...
        └── ...
              │ PTY (主从设备对)
              ▼
        子进程 (sh / 其他命令)
        stdin/stdout/stderr → pts (从设备)
```

| 层级 | 组件 | 职责 |
|------|------|------|
| 应用入口 | `Application` | Hilt 初始化、日志配置 |
| 服务层 | `TerminalService` | 前台服务托管、通知管理、服务自停 |
| 会话管理 | `SessionManager` | 会话集合维护、当前会话追踪 |
| 会话层 | `TerminalSession` | 单会话生命周期、I/O 协程调度、模拟器桥接 |
| 进程封装 | `LocalPtyProcess` | JNI 调用、fd 封装、进程控制 |
| 原生层 | `pty.cpp` | `forkpty` + `execve`、termios 配置 |

---

## 2. 组件生命周期

### 2.1 Application

`@HiltAndroidApp`，`onCreate()` 中种植 `TimberLogTree` 并通过 `preferences.logLevel` Flow 动态调整日志级别。

`SessionManager` 作为 `@Singleton` 在此 Hilt 图中创建，生命周期等同于进程，Activity 销毁重建不影响会话数据。

---

### 2.2 TerminalService

由 `MainViewModel.addSession()` 在创建首个会话时通过 `startForegroundService()` 启动。

**状态流转**:

```
未运行 ──onStartCommand()──▶ 前台运行 ──无会话/EXIT──▶ onDestroy() ──▶ 已销毁
```

**onStartCommand()**:

1. 创建 `IMPORTANCE_LOW` 通知渠道，`startForeground()`，通知标题为 `"{N} session(s)"`
2. 处理 `EXIT` 动作 → `stopSelf()`
3. 首次启动时订阅 `sessionManager.sessionList`，变更时更新通知
4. 返回 `START_NOT_STICKY`

**onDestroy()**: `serviceScope.cancel()` → `sessionManager.clear()` → `stopForeground(REMOVE)`。

**自动停止**: 通知更新时若会话列表为空则调用 `stopSelf()`。

服务不绑定，UI 层通过 Hilt 注入的 `SessionManager` 单例交互。

---

### 2.3 SessionManager

`@Singleton`，维护 `_sessionList`（`MutableStateFlow`）与 `currentSessionId`。

**addSession(commandInfo)**:

```
创建 TerminalSession → execute() → 加入列表 → currentSessionId 指向新会话
→ 启动自动清理协程
```

自动清理协程监听 `isRemove` 与列表存在性，任一条件满足时 `removeSession()` 后自然结束。

**removeSession(id)**: 从列表过滤，若移除的是当前会话则自动切换。仅移除列表引用，不主动杀进程。

**clear()**: 遍历 `finishIfRunning()`（`SIGKILL`）→ 重置 ID 计数器 → 清空列表。

会话 ID 由 `CommandInfo.endId` 全局原子计数器分配。

---

### 2.4 TerminalSession

每个会话对应一个子进程 + 一个终端模拟器实例。

**完整生命周期**:

```
构造 (init)
  ├─ 注入 TERM=xterm-256color, COLORTERM=truecolor
  └─ 预创建 64 个 4KB 缓冲区入池
execute()
  ├─ state → EXECUTING
  ├─ processFactory() 创建 LocalPtyProcess
  └─ 启动 4 个协程 (inputReader / outputWriter / emulatorProcessor / exitHandler)
运行中 (isRunning = pid > 0)
子进程退出
  ├─ exitHandler 捕获退出码
  ├─ handleProcessExit(): 排空剩余数据 → 追加 "[Process completed ... - press Enter]"
  └─ pid = -1, isRunning = false
用户按 Enter → isRemove = true → SessionManager 清理 → scope.cancel()
```

**进程退出处理**: 退出码 `>0` 为正常退出码，`==0` 成功，`<0` 被信号终止（绝对值为信号编号）。

进程结束后会话仍保留，用户按 Enter 才触发移除：

```kotlin
fun write(data: ByteArray) {
    if (isRunning) terminalWriteChannel.trySend(data)
    else if (data.size == 1 && (data[0] == '\n'.code.toByte() || data[0] == '\r'.code.toByte()))
        isRemove.update { true }
}
```

**关键方法**:

| 方法 | 作用 |
|------|------|
| `execute()` | 启动进程与协程 |
| `updateSize()` | 通知 PTY + 模拟器调整尺寸 |
| `write(ByteArray)` | 向子进程 stdin 写入 |
| `writeCodePoint()` | UTF-8 编码后写入 |
| `reset()` | 重置模拟器 |
| `finishIfRunning()` | 发送 `SIGKILL` |

`uiEvent` 为 `MutableSharedFlow(DROP_OLDEST)`，屏幕更新不阻塞 I/O。

---

### 2.5 LocalPtyProcess

实现 `ITerminalProcess` 接口：

```kotlin
interface ITerminalProcess {
    val pid: Int
    val inputStream: InputStream   // 子进程 stdout → 应用读取
    val outputStream: OutputStream // 应用写入 → 子进程 stdin
    fun resize(columns, rows, cellWidthPixels, cellHeightPixels)
    fun waitFor(): Int
    fun kill()
    fun close()
}
```

**生命周期**:

```
init
  ├─ JNI createSubprocess(): posix_openpt → grantpt/unlockpt/ptsname_r
  │   → termios 配置 → TIOCSWINSZ → fork() → 子进程 execvp(cmd)
  ├─ pid = 子进程 PID
  ├─ fdObj = ParcelFileDescriptor.adoptFd(ptm)
  └─ inputStream/outputStream 包装 fdObj.fileDescriptor
运行中: resize() → ioctl TIOCSWINSZ | kill() → Os.kill(SIGKILL) | waitFor() → waitpid
close(): fdObj.close()
```

**JNI 映射**:

| Kotlin | 系统调用 |
|--------|----------|
| `createSubprocess()` | `posix_openpt`, `grantpt`, `unlockpt`, `ptsname_r`, `fork`, `execvp` |
| `setPtyWindowSize()` | `ioctl(TIOCSWINSZ)` |
| `waitFor(pid)` | `waitpid(pid, &status, 0)` |

退出码转换：`WIFEXITED` → `WEXITSTATUS`；`WIFSIGNALED` → `-WTERMSIG`。

---

### 2.6 原生子进程

`app/cpp/pty/pty.cpp`，标准 UNIX PTY 机制。

**create_subprocess() 流程**:

```
父进程                              子进程
──────                              ──────
posix_openpt(O_RDWR|O_CLOEXEC)
grantpt / unlockpt / ptsname_r
termios: IUTF8, 禁用 IXON/IXOFF
ioctl(TIOCSWINSZ)
fork() ────────────────────────────► sigfillset+sigprocmask(UNBLOCK)
                                    close(ptm)
                                    setsid()
                                    open(devname) → pts
                                    dup2(pts, 0/1/2)
                                    close(pts)
                                    关闭 3~RLIMIT_NOFILE 所有 fd
                                    setenv() 注入环境变量
                                    chdir(cwd)
                                    execvp(cmd, argv)
                                    失败 → _exit(127)
return ptm, pid
```

**关键调用**:

| 调用 | 作用 |
|------|------|
| `posix_openpt(O_RDWR \| O_CLOEXEC)` | 打开 PTY 主设备，exec 时自动关闭 |
| `grantpt` / `unlockpt` / `ptsname_r` | 授权、解锁、获取从设备路径 |
| `setsid()` | 子进程创建新会话，脱离原控制终端 |
| `dup2(pts, 0/1/2)` | 从设备作为 stdin/stdout/stderr |
| `execvp(cmd, argv)` | 替换子进程镜像 |

termios 配置：`IUTF8`（按字符退格）、禁用 `IXON/IXOFF`（Ctrl+S/Q 透传）。

子进程 `execvp` 前关闭 fd 3 以上所有描述符，防止 fd 泄漏。exec 失败时 `_exit(127)`。

---

## 3. 进程模型

### PTY 机制

```
ptm (主, 应用持有)  ──写入──▶  pts (从, 子进程持有)
ptm (主, 应用持有)  ◀──读取──  pts (从, 子进程持有)
```

写入主设备的数据作为从设备输入出现，反之亦然，使子进程认为连接在真实终端上。

### 父子进程关系

```
应用进程 (P_app)
├── fork() → 子进程 #1 (P1) execvp("sh")
├── fork() → 子进程 #2 (P2) execvp("sh")
└── ...
```

每个 `TerminalSession` 对应一次 `fork()`。子进程通过 `setsid()` 创建独立会话，拥有独立的会话 ID 和进程组 ID。N 个会话 = N 个独立子进程，各自 PTY 对与 stdio 互不干扰。

### 文件描述符

应用侧：ptm fd 由 `LocalPtyProcess.close()` 释放，`FileInputStream/OutputStream` 由 `use {}` 自动关闭。

子进程侧：fd 0/1/2 → pts，fd 3+ 全部关闭。ptm fd 因 `O_CLOEXEC` 在 exec 后自动关闭。

---

## 4. 协程模型

### 协程作用域

```
Application
  └─ Main.immediate  (日志级别)
TerminalService
  └─ Main.immediate  (通知更新)
SessionManager
  └─ IO  (会话自动清理)
TerminalSession (每个会话独立)
  └─ IO
      ├─ inputReader
      ├─ outputWriter
      ├─ emulatorProcessor
      └─ exitHandler
```

所有作用域均使用 `SupervisorJob`，子协程失败不取消兄弟协程。

### 单会话四协程

```
inputReader ──terminalReadChannel(UNLIMITED)──▶ emulatorProcessor ──uiEvent──▶ UI
用户输入/write() ──terminalWriteChannel(BUFFERED)──▶ outputWriter
exitHandler: waitFor() 阻塞，退出后 scope.cancel() 级联取消其余协程
```

- **inputReader**: 从池取 4KB 缓冲区 → 阻塞读子进程输出 → 送入 `terminalReadChannel`。EOF 时退出。
- **outputWriter**: 启动时若 `commandInfo.stdin` 非空先写入，随后消费 `terminalWriteChannel` 写入子进程 stdin。
- **emulatorProcessor**: 消费 `terminalReadChannel`，调用 `emulator.append()` 解析转义序列并更新屏幕缓冲区。批量合并最多 32KB 数据以减少处理开销。处理后归还缓冲区，`notifyScreenUpdate()` 通知 UI。
- **exitHandler**: `waitFor()` 阻塞 → `close()` → 切主线程执行 `handleProcessExit()` → `scope.cancel()`。

---

## 5. I/O 数据流

### 输入路径（用户 → 子进程）

```
用户按键/粘贴 → TerminalSession.write() → terminalWriteChannel.trySend()
→ outputWriter → FileOutputStream.write() → ptm → PTY 内核缓冲 → pts → 子进程 stdin
```

`writeCodePoint(prependEscape, codePoint)` 将 Unicode 码点编码为 UTF-8 写入，支持可选 ESC 前缀（Alt 组合键）。

### 输出路径（子进程 → 屏幕）

```
子进程 stdout/stderr → pts → PTY 内核缓冲 → ptm
→ inputReader FileInputStream.read() → terminalReadChannel
→ emulatorProcessor { TerminalEmulator.append() → Utf8Decoder → AnsiEscapeParser → TerminalBuffer }
→ uiEvent.tryEmit(Unit) (SharedFlow DROP_OLDEST) → TerminalView → Canvas 渲染
```

### 缓冲区池

```
初始化: 64 × DataChunk(ByteArray(4096)) → terminalReadBufferPoolChannel
inputReader: receive() 取空 → read() 填充 → send 到 terminalReadChannel(UNLIMITED)
emulatorProcessor: receive 取满 → append → trySend 归还
```

| 参数 | 值 |
|------|-----|
| 池大小 | 64 |
| 单缓冲区 | 4096 字节 |
| 数据 Channel | `UNLIMITED`，保证子进程输出不丢失 |

背压时 `terminalReadChannel` 缓存所有数据，池耗尽后新缓冲区不归还（直接 GC），不阻塞子进程输出。

---

## 6. 状态机

### CommandInfo.ExecutionState

单向前进，不可回退，`SUCCESS` 为终态：

```
PRE_EXECUTION → EXECUTING ─┬→ SUCCESS  (exitCode == 0)
                            ├→ EXECUTED (exitCode != 0)
                            └→ FAILED   (预留)
```

| 状态 | 触发时机 | level |
|------|----------|-------|
| `PRE_EXECUTION` | CommandInfo 构造 | 0 |
| `EXECUTING` | TerminalSession.execute() | 1 |
| `EXECUTED` | 非零退出码 | 2 |
| `SUCCESS` | 零退出码 | 3 |
| `FAILED` | 预留 | 4 |

### 会话生命周期

```
已创建 ──execute()──▶ 运行中(isRunning) ──进程退出/kill──▶ 已结束(显示退出提示)
──用户按 Enter──▶ 待移除(isRemove=true) ──SessionManager 清理──▶ 已移除(scope.cancel)
```

---

## 相关文档

- [屏幕缓冲区架构设计](ScreenBuffer.md)
- [支持的 xterm 转义序列](xterm-sequences.md)

## 源码索引

| 组件 | 路径 |
|------|------|
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

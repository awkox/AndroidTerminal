# BUGREPORT：libterminal 核心模块问题清单

> **范围声明**：本报告仅覆盖 `libterminal` 模块。`libterminal-pty` 与 `libterminal-ssh` 未纳入。

---

## 1. 输入源类型混淆：`eventSource` 承载 `deviceId`，魔法数字与系统整数同域

* **数据源根源**：
  `inputCodePoint(eventSource, codePoint, ...)` 中 `eventSource` 混用三类取值：
  * 软键盘：`KEY_EVENT_SOURCE_SOFT_KEYBOARD = 0`
  * 虚拟键盘：`KEY_EVENT_SOURCE_VIRTUAL_KEYBOARD = 2`
  * 物理硬件按键：Android 系统派发的 `event.deviceId`（任意整数）
* **缺陷表现**：
  ```kotlin
  // KeyInputProcessor.inputCodePoint
  val extraMods = if (eventSource == KEY_EVENT_SOURCE_SOFT_KEYBOARD) modifierReader()
                  else ExtraKeysModifierSnapshot()
  val isHardwareKeyboard = eventSource > KEY_EVENT_SOURCE_SOFT_KEYBOARD // 大于 0 即当硬件
  ```
  与 `SOFT_KEYBOARD(0)` 取值空间重叠，判定建立在魔法数字推断之上。
  * `event.deviceId` 是否会被 Android 分配为 `0`（导致物理键被 `== SOFT_KEYBOARD` 分支误判）**尚未经验证**，不得作为既定事实。
  * `eventSource > 0` 把虚拟键盘(2) 判为硬件键盘，误触 `KeyHandler.kt:63-69` 的三项硬件修正表。
* **根因诊断**：数据源未定义明确的输入源类型，把 Android `deviceId` 与自定义标记塞进同一个 `Int`，消费者只能逆向猜测。

---

## 2. 滚动归属倒置：快捷键与滚轮坐标从伪造的 `MotionEvent` 取值

* **数据源根源**：
  视口滚动能力被塞在 `TerminalTouchHandler` 中（混杂鼠标事件与视口点击测试），快捷键发起者为复用滚动代码反过来构造触控事件：
  ```kotlin
  // TerminalView.kt
  scrollPages = { pages ->
      val time = SystemClock.uptimeMillis()
      val motionEvent = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, 0f, 0f, 0)
      touchHandler.doScroll(motionEvent, pages)
      motionEvent.recycle()
  }
  ```
* **缺陷表现**：
  `TerminalTouchHandler.doScroll`（约 90-107 行）经 `viewportCellAt(event.x, event.y)` 取滚轮坐标，而伪造事件喂入 `(0f, 0f)`（`TerminalView.kt:129-131`、`TerminalGestureListener.kt:110`）。后果分两档：
  * **键盘翻页（Shift+PageUp/PageDown）：坐标确定性恒为 1;1** —— `scrollPages` 每次以 `uptimeMillis()` 新建锚点、锚于 (0,0)。
  * **手势合成路径：条件性 1;1** —— fling 复用原始 `downTime` 锚，仅部分时序下发生。
  在鼠标跟踪模式 TUI（vim/less 等）下滚轮事件可能被洗成 1;1，该症状是否用户可见尚未实机验证。
* **根因诊断**：视口滚动本是终端核心控制层的独立能力，触摸/滚轮/快捷键只是发起者。职责倒置后，非触摸发起者被迫伪造触控数据——连带把"指针位置"这一无关信息一并伪造。

---

## 3. 视口系 / 缓冲系坐标在类型层不可区分

* **数据源根源**：
  `TerminalView` 存在两个坐标入口，契约差异已在各自 KDoc 声明，但返回类型相同：
  * `viewportCellAt`（约 380-390 行）：像素直接除字体尺寸，**不加 `topRow`、不夹取**，可产出负数/超界值。
  * `bufferCellAt`（约 393-401 行）：加 `topRow` 后经 `clampColumn`/`clampRow` 夹取。
* **缺陷表现**：两者同返 `CursorCoord`（`CursorCoord.kt:8` 值类），**视口系与缓冲系在编译期不可区分**，调用方拿错域不会得到任何编译错误。
* **根因诊断**：两个语义不同的坐标系共用一个值类型，类型系统无法表达"这个坐标属于哪个系"，正确性只能靠调用方记住约定。

---

## 4. 相对行号坐标系与选区期间冻结输入流（两个独立问题）

* **数据源根源**：
  `TerminalBuffer` 暴露的外部行号以**可见屏幕首行（0）为动态基准**。滚屏（`scrollDownOneLine`）后原点位移，同一行文字的外部行号从 `0` 变为 `-1`、`-2`。
* **缺陷表现**：
  1. `TerminalEmulator` 维护 `scrollCounter`；`TerminalView` 每次刷新 `takeScrollCounter()` 后通知 `TextSelectionCursorController.decrementYTextSelectionCursors(rowShift)` 给选区光标做减法。
  2. 为防选区期间后台输出导致选区错位，引入 `setInputPaused(true)` / `mFrozenInputQueue`，**强行暂停全部子进程 I/O 解码**。
* **两个问题必须拆开**：
  * **冻结输入流**针对的是"选区期间文本内容被改写"（选区锚定的文本本身被滚动/重写），与坐标基准无关，换成绝对行号治不好它。
  * **相对行号的原点位移**牵连环形缓冲驱逐、resize reflow、备用屏切换、scrollback 快照四处联动。
* **根因诊断**：坐标基准的选择（相对 vs 绝对）是数据源的领域决策，但它牵连两套独立机制，不可当做一个问题处理。

---

## 5. 转义语义层：SGR 解析职责错位

* **数据源**：`AnsiEscapeParser` 产出平铺 `mArgs: IntArray` + 子参数位图，缺省值填 `-1`。
* **缺陷表现**：`RenditionState.selectGraphicRendition` 内部充斥指针跳跃（`var i = 0`）、冒号子参数（`38:2::R:G:B`）展开的 `-1` 空占位跳过、未知模式 `i = argCount - 1` 回退——本属协议解析器的语法拆解被推给状态持有者。此外 `AnsiEscapeParser.getArg(...)` 的散落调用实测 **62 处**（`TerminalEmulator` 57 + `RenditionState` 5），每次取值都由消费者手动处理缺省值。
* **根因诊断**：协议语义（参数结构）本应由解析层收口，却泄漏给状态持有者。

---

## 6. 软换行标志 `mLineWrap` 与行内容生命周期脱钩（部分修复）

> **修复状态**：**纵向搬移类已完成**——`insertLines` / `deleteLines` / `ESC M`(RI) / `CSI T`(SD) / `CSI S`(SU) / 带左右边距的 `scrollDownOneLine` 全部收口到 `TerminalBuffer.shiftLines` 单一原语，全宽路径走行对象引用轮转，标志随行走、空位经 `blankRowAt` 复位；`mLineWrap` 写入集由 public 收为 `private`，只剩 `clear()` / `markLineWrap()` / `copyLineWrapFrom()` 三处，`copyRow` 改调 `copyLineWrapFrom`。`libterminal/src/test/.../TerminalBufferLineWrapShiftTest.kt` 9 例覆盖。
>
> **未修复**：下文「缺陷 A」擦除类与「缺陷 B」DCH。二者依赖 xterm `scrEraseLine` / `ScrnDeleteChar` 是否显式清位的语义，审核方未独立验证，维持"未核实"标注，待读 xterm 源定口径后在数据源处修。

* **数据源根源**：
  行级标志 `mLineWrap`（`TerminalRow.kt:48`，已收为 `private`）的写入集仅三处：`TerminalRow.clear()`（`TerminalRow.kt:311`）、`markLineWrap()`（`TerminalRow.kt:55`，由 `TerminalBuffer.setLineWrap`（`TerminalBuffer.kt:128`）调用）、`copyLineWrapFrom()`（`TerminalRow.kt:60`，由 `copyRow`（`TerminalBuffer.kt:284`）调用）。纵向搬移已改走行对象引用随行，但**横向与擦除路径上的 `setChar` / `blockSet` / `copyInterval` 仍全部旁路该标志**——内容被擦除或横移时，标志既不跟随也不复位。
* **缺陷 A：擦除内容不清标志**（`blockSet` / `setChar` / `blockClear`（`TerminalEmulator.kt:1126-1127`）均不触碰 `mLineWrap`）：
  * `EL0` / `EL1` / `EL2` / `ED0` / `ED2` 把整行（或行的左/右段）擦成空白后，该行仍被标记为续行（`handleCsiJ` / `handleCsiK`，`TerminalEmulator.kt:617-647`；EL 三分支均走 `blockClear`，不触碰 `mLineWrap`）
* **缺陷 B：DCH（`CSI P`）不清标志**：`TerminalBuffer.deleteColumns`（`TerminalBuffer.kt:523-528`）左移内容、右尾用 `blockSet` 补清，右尾被清而 `mLineWrap` 留存（与 xterm `ScrnDeleteChar` 是否显式清位的差异未经核实，此处仅陈述本库行为）。
* **后果链（4 条消费链路，均为消费端被错误标志污染）**：
  1. `TerminalBuffer.getSelectedText`（`TerminalBuffer.kt:43-97`）：wrapped 行不裁尾随空格、不插 `\n` → 复制时硬拼两行或向剪贴板注入整行空格；该函数**无护栏**。
  2. `TextSelectionCursorController.kt:139,154`：双击选词跨行扩展按错误标志跳行；其 `!isCellBlank` 护栏（`:141`、`:156`）只护擦除类残留，护不了搬移类错位。
  3. `TerminalEmulator.kt:322-333`：BS 反向换行（需 DECSET 45 + DECAWM 同开）跳到非续行的行尾。
  4. `TerminalReflower.kt:62,124`：resize 重排按错误标志决定续排与 `lastNonSpaceIndex`，拼接/断行错乱，scrollback 一次固化不可逆；本库独有 reflow，此后果在 xterm 无对应物。
* **根因诊断**：行级元数据（`mLineWrap`）的生命周期未与行内容的写入、搬移、擦除绑定在同一路径上，状态靠调用方各自记忆维护，产生"陈旧续行位"。


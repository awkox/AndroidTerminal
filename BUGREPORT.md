# BUGREPORT：libterminal 核心模块职责错乱审查

> **范围声明**：本报告仅覆盖 `libterminal` 模块。`libterminal-pty` 与 `libterminal-ssh` 未纳入本次审查（此前"全面审查"的表述不准确，标题与结论均以本范围为准）。
>
> **审查基准**：区分两类消费者代码——
> * **本来就应由消费者处理**：调用方根据自身上下文业务，对合法但语义多样的输入做决策（如渲染器按深浅色模式决定背景绘制）。
> * **被迫由消费者处理**：数据源产出的数据处于"半成品"、"未收敛边界"或"内部状态可能撕裂"的状态，迫使各处消费者为防崩溃/错位重复编写边界裁剪与防御代码。

---

## 一、已确认的 5 个独立根因

### 1. 输入源类型混淆：`eventSource` 承载 `deviceId`，魔法数字与系统整数同域

* **数据源根源**：
  `inputCodePoint(eventSource, codePoint, ...)` 中 `eventSource` 混用三类取值：
  * 软键盘：`KEY_EVENT_SOURCE_SOFT_KEYBOARD = 0`
  * 虚拟键盘：`KEY_EVENT_SOURCE_VIRTUAL_KEYBOARD = 2`
  * 物理硬件按键：Android 系统派发的 `event.deviceId`（任意整数）
* **类型缺陷与消费者猜测代码**：
  ```kotlin
  // KeyInputProcessor.inputCodePoint
  val extraMods = if (eventSource == KEY_EVENT_SOURCE_SOFT_KEYBOARD) modifierReader()
                  else ExtraKeysModifierSnapshot()
  val isHardwareKeyboard = eventSource > KEY_EVENT_SOURCE_SOFT_KEYBOARD // 大于 0 即当硬件
  ```
  与 `SOFT_KEYBOARD(0)` 的取值空间重叠，使判定建立在魔法数字推断之上。
* **症状澄清（经核验修正）**：
  * 此前报告的"虚拟扩展键吃不到屏幕粘性 Ctrl/Alt"**不成立**：`SessionListDrawer.kt:135` 显式传入 `ctrlDown, altDown`，经 `ExtraKeyDispatcher.kt:91` 粘性状态、`KeyInputProcessor.kt:110-112` 合流生效。
  * 遗留的**类型缺陷本身仍在**：`eventSource` 同时是自定义标记与真实 `deviceId` 的取值域。`event.deviceId` 是否会被 Android 分配为 `0`（导致物理键被 `== SOFT_KEYBOARD` 分支误判）**尚未经验证**，需埋点采样，不得作为既定事实。
  * `eventSource > 0` 会把虚拟键盘(2) 判为硬件键盘，误触 `KeyHandler.kt:63-69` 的三项硬件修正表（后果低危）。
* **根因诊断**：数据源未定义明确的输入源类型，把 Android `deviceId` 与自定义标记塞进同一个 `Int`，消费者只能逆向猜测。

---

### 2. 滚动归属倒置：快捷键与滚轮坐标从伪造的 `MotionEvent` 取值

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
* **衍生缺陷（经核验修正表述）**：
  `TerminalTouchHandler.doScroll`（约 90-107 行）经 `viewportCellAt(event.x, event.y)` 取滚轮坐标，而伪造事件喂入 `(0f, 0f)`（`TerminalView.kt:129-131`、`TerminalGestureListener.kt:110`）。后果分两档：
  * **键盘翻页（Shift+PageUp/PageDown）：确定性坐标恒为 1;1** —— `scrollPages` 每次以 `uptimeMillis()` 新建锚点、锚于 (0,0)。
  * **手势合成路径：条件性 1;1** —— fling 复用原始 `downTime` 锚，仅部分时序下发生。
  在鼠标跟踪模式 TUI（vim/less 等）下滚轮事件可能被洗成 1;1，**该症状是否用户可见尚未实机验证**（见验证清单）。
* **根因诊断**：视口滚动本是终端核心控制层的独立能力，触摸/滚轮/快捷键只是发起者。职责倒置后，非触摸发起者被迫伪造触控数据——连带把"指针位置"这一无关信息一并伪造。

---

### 3. 视口系 / 缓冲系坐标在类型层不可区分

* **数据源根源**：
  `TerminalView` 存在两个坐标入口，契约差异已在各自 KDoc 声明，但返回类型相同：
  * `viewportCellAt`（约 380-390 行）：像素直接除字体尺寸，**不加 `topRow`、不夹取**，可产出负数/超界值。
  * `bufferCellAt`（约 393-401 行）：加 `topRow` 后经 `clampColumn`/`clampRow` 夹取。
* **真缺口**：两者同返 `CursorCoord`（`CursorCoord.kt:8` 值类），**视口系与缓冲系在编译期不可区分**，调用方拿错域不会得到任何编译错误。这才是结构问题所在——而非"KDoc 没写"。
* **已排除的误判**：
  * **禁止合并两个入口的实现**：`topRow` 差异对 X10/SGR 鼠标协议是正确且必要的。
  * `InputSequenceEncoder.encodeMouseEvent` 中的 `min(max(column + 1, 1), columns)` 是**协议域 1..columns 的合法不变量**（协议本身坐标从 1 起），**不属于被迫防护代码**，从病灶清单剔除。
* **根因诊断**：两个语义不同的坐标系共用一个值类型，类型系统无法表达"这个坐标属于哪个系"，正确性只能靠调用方记住约定。

---

### 4. 相对行号坐标系与选区期间冻结输入流（两个独立决策）

* **数据源根源**：
  `TerminalBuffer` 暴露的外部行号以**可见屏幕首行（0）为动态基准**。滚屏（`scrollDownOneLine`）后原点位移，同一行文字的外部行号从 `0` 变为 `-1`、`-2`。
* **被迫防护的现场**：
  1. `TerminalEmulator` 维护 `scrollCounter`；`TerminalView` 每次刷新 `takeScrollCounter()` 后通知 `TextSelectionCursorController.decrementYTextSelectionCursors(rowShift)` 给选区光标做减法。
  2. 为防选区期间后台输出导致选区错位，引入 `setInputPaused(true)` / `mFrozenInputQueue`，**强行暂停全部子进程 I/O 解码**。
* **两个决策必须拆开（经核验修正）**：
  * **冻结输入流**针对的是"选区期间文本内容被改写"（选区锚定的文本本身被滚动/重写），与坐标基准无关。**换成绝对行号治不好它**，两者需各自独立评估。
  * **绝对行号替换**的改动成本此前被低估：涉及环形缓冲驱逐、resize reflow、备用屏切换、scrollback 快照四处联动，须先做影子计数验证再谈立项。
* **根因诊断**：坐标基准的选择（相对 vs 绝对）是数据源的领域决策，但它牵连两套独立机制，不可用一个方案打包处理。

---

### 5. 转义语义层：SGR 解析职责错位

* **数据源**：`AnsiEscapeParser` 产出平铺 `mArgs: IntArray` + 子参数位图，缺省值填 `-1`。
* **被迫处理**：`RenditionState.selectGraphicRendition` 内部充斥指针跳跃（`var i = 0`）、冒号子参数（`38:2::R:G:B`）展开的 `-1` 空占位跳过、未知模式 `i = argCount - 1` 回退——本属协议解析器的语法拆解被推给状态持有者。此外 `AnsiEscapeParser.getArg(...)` 的散落调用实测 **63 处**（`TerminalEmulator` 57 + `RenditionState` 5 + parser 1），每次取值都由消费者手动处理缺省值。
* **根因诊断**：协议语义（参数结构）本应由解析层收口，却泄漏给状态持有者。

---

## 二、经核验后删除或降级的原条目

| 原条目 | 处置 | 依据 |
|---|---|---|
| 长按初始选词产生"非法分裂锚点" | **删除**，确定缺陷并入 `snapToColumn` 对齐方向参数 | `TextSelectionCursorController.kt:132-146` 选词扩展左移循环必然吸收分裂锚（`getSelectedText` 经 `TerminalBuffer` clamp 后返回字符本体），无可复现用例 |
| `handleWidthChange` 因 `copyInterval` 灌入越界宽字符而被迫防护 | **删除该例**（守卫在数据源内部，且归因错误） | 行末宽字符是 `setChar` 显式合法状态；`copyInterval` 与直接写入同为 `setChar` 调用方，非第二来源 |
| `KeyHandler.processPrintableChar` 的 `\n→\r→+96→-97+1` 往返 | **降级为风格问题** | 控制字符折叠的逆运算（0x01→'a' 逆解），非数据源 bug |
| `InputSequenceEncoder` 的 `min(max(...))` 充当几何裁剪器 | **剔除** | 协议域 1..columns 的合法不变量 |
| "虚拟扩展键吃不到粘性 Ctrl/Alt" | **症状证伪，类型缺陷保留** | `SessionListDrawer.kt:135` 显式传修饰键并合流生效 |
| 滚轮坐标"两条路径都洗成 1;1" | **收窄为键盘翻页确定性 1;1、手势路径条件性 1;1** | fling 复用原始 downTime 锚 |

---

## 三、重构 backlog（按 ROI 排序）

| 序 | 项 | 改动面 | 风险 | 前置验证 |
|---|---|---|---|---|
| 1 | SGR 颜色子参数抽取：在 `selectGraphicRendition` 内抽同文件私有函数（约 30 行、零新类型，语义签名如 `colorIndex(default)`） | 极小 | 低 | 调用点已确认单一消费方 |
| 2 | 双入口收口（文档级）：同步 KDoc、`viewportCellAt` 补夹取或 debug assert；**禁止合并实现** | 低 | 低 | 域类型化（拆 `CursorCoord` 为视口系/缓冲系两类型）另立可选项，前置为全调用点域归属审计 |
| 3 | 输入源枚举化：`PhysicalKeyboard` / `ImeSoftKeyboard` / `VirtualToolbar` 密封类型，事件入口统一合并修饰键 | `KeyInputProcessor`/`KeyHandler`，约半天 | 低危（误触仅影响 `KeyHandler.kt:63-69` 三项硬件修正表） | `deviceId==0` 埋点 + 外接键盘回归 |
| 4 | 滚动归属修复：抽离滚动控制器提供 `scrollBy`/`scrollTo`，触控/滚轮/快捷键均为扁平发起方，废弃伪造 `MotionEvent` | `TouchHandler`/`View` | 中 | **滚轮 1;1 症状实机验证，验成才动工** |
| 5 | 绝对行号：选区/记录改用单调行 id | 环形缓冲驱逐 + resize reflow + 备用屏切换 + scrollback 快照四处联动 | 高、收益低 | 影子计数 1-2 天先行 |

**优先级结论**：1-2 高性价比可直接排期；3 待埋点；4 待症状验证；5 暂不立项。第 5 项（绝对行号）是 `TerminalBuffer` 数据源头的治理，其余为下游收口。



我已完成全模块扫描。以下是 libterminal 中所有命名不规范代码的完整清单，按问题类型分组。

一、匈牙利命名法（m 前缀）—— 62 处
 
这是最普遍的问题。Kotlin 官方编码规范不使用 `m` 前缀，且同一模块内 `mRows` 与 `rows`、`cursorRow` 与 `mCursorRow` 两套风格并存，一致性差。
 
TerminalEmulator.kt（29 处）
 
原始名称 位置 真实意义 推荐命名 
`mCursorRow` :26 光标当前行（私有 backing field） `_cursorRow` 或直接用属性 
`mCursorCol` :27 光标当前列（私有 backing field） `_cursorCol` 
`mRows` :29 终端屏幕行数（公开可变） `rows` 
`mColumns` :31 终端屏幕列数（公开可变） `columns` 
`mCellWidthPixels` :33 单字符宽度（像素） `cellWidthPixels` 
`mCellHeightPixels` :35 单字符高度（像素） `cellHeightPixels` 
`mAboutToAutoWrap` :37 下一字符是否触发自动换行（布尔） `isAutoWrapPending` 
`mMainBuffer` :67 主屏幕缓冲区（含滚动历史） `mainBuffer` 
`mAltBuffer` :68 备用屏幕缓冲区（无历史） `altBuffer` 
`mTopMargin` :75 滚动区上边界 `topMargin` 
`mBottomMargin` :76 滚动区下边界 `bottomMargin` 
`mLeftMargin` :77 左右边距模式左边界 `leftMargin` 
`mRightMargin` :78 左右边距模式右边界 `rightMargin` 
`mSavedStateMain` :85 主屏幕保存的光标/样式状态 `savedMainState` 
`mSavedStateAlt` :86 备用屏幕保存的光标/样式状态 `savedAltState` 
`mUseLineDrawingG0` :88 G0 字符集是否为线绘字符集 `isG0LineDrawing` 
`mUseLineDrawingG1` :89 G1 字符集是否为线绘字符集 `isG1LineDrawing` 
`mUseLineDrawingUsesG0` :90 当前活跃字符集是否为 G0 `isUsingG0Charset` 
`mPalette` :100 OSC 动态改色稀疏覆盖板（公开） `oscPalette` 
`mInsertMode` :138 是否处于插入模式（布尔） `isInsertMode` 
`mTabStop` :139 制表位布尔数组 `tabStops` 
`mLastEmittedCodePoint` :151 上一个输出的码点（用于重复字符） `lastEmittedCodePoint` 
`mInputPaused` :256 输入是否冻结（文本选择期间） `isInputPaused` 
`mFrozenInputQueue` :257 冻结期间缓存的字节队列 `frozenInputQueue` 
`mSavedCursorRow` :1167 已保存光标行（内部类字段） `savedCursorRow` 
`mSavedCursorCol` :1169 已保存光标列 `savedCursorCol` 
`mSavedEffect` :1171 已保存特效位 `savedEffect` 
`mSavedForeColor` :1173 已保存前景色 `savedForeColor` 
 
TerminalBuffer.kt（6 处）
 
原始名称 位置 真实意义 推荐命名 
`mColumns` :13 缓冲区列数（构造参数，公开） `columns` 
`mTotalRows` :14 总行数（含滚动历史） `totalRows` 
`mScreenRows` :15 屏幕可见行数 `screenRows` 
`mLines` :18 行数组（环形缓冲） `lines` 
`mSharedBlankRow` :26 只读路径共享的空白行 `sharedBlankRow` 
`mScreenFirstRow` :31 屏幕首行在环形数组中的索引 `screenFirstRow` 
 
TerminalRow.kt（7 处）
 
原始名称 位置 真实意义 推荐命名 
`mColumns` :16 行列数 `columns` 
`mText` :21 字符存储数组 `text` 
`mSpaceUsed` :24 已使用字符数（含尾随空格） `spaceUsed` 或 `charsUsed` 
`mLineWrap` :29 行末是否自动换行（布尔） `isLineWrapped` 
`mStyle` :33 样式 Long 数组（偶数=主样式，奇数=扩展特效） `styles` 
`mHasNonOneWidthOrSurrogateChars` :36 是否含宽字符或代理对（布尔） `hasWideOrSurrogateChars` 
 
AnsiEscapeParser.kt（6 处）
 
原始名称 位置 真实意义 推荐命名 
`mEscapeState` :6 当前转义序列解析状态 `escapeState` 
`mArgs` :7 CSI 参数数组 `args` 
`mArgIndex` :8 当前参数索引 `argIndex` 
`mArgsSubParamsBitSet` :9 冒号子参数位集 `subParamsBitSet` 
`mOSCOrDeviceControlArgs` :10 OSC/DCS 字符串参数缓冲 `oscOrDcsArgs` 
`mContinueSequence` :11 是否继续当前序列（布尔） `isContinuingSequence` 
 
TerminalSession.kt（1 处）
 
原始名称 位置 真实意义 推荐命名 
`mUtf8InputBuffer` :236 UTF-8 编码临时缓冲区（5字节） `utf8InputBuffer` 
 
TerminalView.kt（4 处）
 
原始名称 位置 真实意义 推荐命名 
`mEmulator` :198 当前会话的仿真器（内部公开） `emulator` 
`mRenderer` :218 终端渲染器（内部公开可变） `renderer` 
`mDefaultSelectors` :270 文本选择默认选择器数组（4个-1） `defaultSelectionSelectors` 
`mShowFloatingToolbar` :290 隐藏浮动工具栏的 Runnable `hideFloatingToolbarRunnable` 
 
TerminalRenderer.kt（9 处）
 
原始名称 位置 真实意义 推荐命名 
`mTextPaint` :27 文本绘制画笔 `textPaint` 
`mFontAscent` :33 字体上升量（像素） `fontAscent` 
`mFontLineSpacingAndAscent` :35 行距+上升量（用于垂直定位） `fontLineSpacingAndAscent` 
`mUnderlinePaint` :42 下划线专用画笔 `underlinePaint` 
`mUnderlineThickness` :46 下划线粗细 `underlineThickness` 
`mUnderlineOffset` :47 下划线偏移量 `underlineOffset` 
`mDashedEffect` :48 虚线下划线路径效果 `dashedUnderlineEffect` 
`mDottedEffect` :49 点线下划线路径效果 `dottedUnderlineEffect` 
`mCurlyEffect` :50 波浪线下划线路径效果 `curlyUnderlineEffect` 
 
Utf8Decoder.kt（3 处）
 
原始名称 位置 真实意义 推荐命名 
`mUtf8ToFollow` :15 还需读取的 UTF-8 续字节数 `remainingUtf8Bytes` 
`mUtf8Index` :16 UTF-8 缓冲当前写入位置 `utf8BufferIndex` 
`mUtf8InputBuffer` :17 UTF-8 解码中间缓冲区（4字节） `utf8DecodeBuffer` 
 
二、布尔变量缺少 is/has/can 前缀 —— 8 处
 
Kotlin 规范要求布尔属性/变量使用 `is`/`has`/`can`/`should` 前缀，使调用点读起来像自然语言。
 
原始名称 位置 真实意义 推荐命名 
`mAboutToAutoWrap` TerminalEmulator:37 下一字符是否将触发自动换行 `isAutoWrapPending` 
`mInsertMode` TerminalEmulator:138 是否处于插入模式（替换模式的反义） `isInsertMode` 
`mInputPaused` TerminalEmulator:256 输入流是否被冻结（文本选择期间） `isInputPaused` 
`mLineWrap` TerminalRow:29 行末是否因自动换行而续行 `isLineWrapped` 
`mContinueSequence` AnsiEscapeParser:11 解析器是否应继续当前转义序列 `isContinuingSequence` 
`mUseLineDrawingG0` TerminalEmulator:88 G0 字符集是否为线绘字符集 `isG0LineDrawing` 
`mUseLineDrawingG1` TerminalEmulator:89 G1 字符集是否为线绘字符集 `isG1LineDrawing` 
`mUseLineDrawingUsesG0` TerminalEmulator:90 当前活跃字符集是否选择 G0 `isUsingG0Charset` 
 
三、命名拗口/语义重复 —— 3 处
 
原始名称 位置 问题 推荐命名 
`mUseLineDrawingUsesG0` TerminalEmulator:90 "Use...Uses..." 动词重复，读起来拗口；且 m 前缀+布尔无 is 前缀双重问题 `isUsingG0Charset` 
`toggleAutoScrollDisabled` TerminalEmulator:1147 函数名含双重否定（toggle + Disabled），调用点 `emulator.toggleAutoScrollDisabled()` 语义不直观 `toggleAutoScroll()` 
`colorOf` TerminalPaletteResolver:14 函数名像属性而非动作，且与公开的 `foreground`/`background`/`cursor` 属性命名风格不一致 `lookupColor()` 或 `resolveIndexedColor()` 
 
四、缩写不规范 —— 7 处
 
原始名称 位置 真实意义 推荐命名 
`WcWidth` 文件名/类名 "wc" 是 `wcwidth` C 函数的缩写，Kotlin 类名应使用完整单词 `EastAsianWidth` 或 `CharacterWidth` 
`KEYMOD_ALT` KeySequenceEncoder:12 键盘修饰键 Alt 的位掩码 `KEY_MOD_ALT` 
`KEYMOD_CTRL` KeySequenceEncoder:13 键盘修饰键 Ctrl 的位掩码 `KEY_MOD_CTRL` 
`KEYMOD_SHIFT` KeySequenceEncoder:14 键盘修饰键 Shift 的位掩码 `KEY_MOD_SHIFT` 
`KEYMOD_NUM_LOCK` KeySequenceEncoder:15 键盘修饰键 NumLock 的位掩码 `KEY_MOD_NUM_LOCK` 
`EXT_UNDERLINE_STYLE_MASK` TextStyle:81 扩展特效中下划线样式的位掩码（EXT=extended） `EXTENDED_UNDERLINE_STYLE_MASK` 
`bmpMeasureCache` TerminalRenderer:39 BMP（Basic Multilingual Plane）码点的字符宽度测量缓存 `bmpCharWidthCache`（BMP 是 Unicode 标准术语可保留，但应加注释） 
 
五、接口命名匈牙利前缀 —— 1 处
 
原始名称 位置 问题 推荐命名 
`ITerminalProcess` 接口名 `I` 前缀是 C# 风格，Kotlin 官方编码规范明确不推荐接口加 `I` 前缀 `TerminalProcess` 
 
六、函数 "do" 前缀无意义 —— 11 处
 
`AnsiEscapeParser` 中大量 `doXxx` 函数，"do" 前缀不增加任何语义信息，Kotlin 习惯直接用动词或状态名。
 
原始名称 位置 推荐命名 
`doEsc` AnsiEscapeParser:114 `handleEscByte` 
`doCsi` AnsiEscapeParser:134 `handleCsiByte` 
`doCsiQuestionMark` AnsiEscapeParser:151 `handleCsiQuestionMarkByte` 
`doCsiBiggerThan` AnsiEscapeParser:160 `handleCsiBiggerThanByte` 
`doCsiUnsupportedParameterOrIntermediateByte` AnsiEscapeParser:166 `handleCsiUnsupportedByte` 
`doOsc` AnsiEscapeParser:175 `handleOscByte` 
`doOscEsc` AnsiEscapeParser:183 `handleOscEscByte` 
`doDeviceControl` AnsiEscapeParser:220 `handleDcsByte` 
`doDeviceControlEscape` AnsiEscapeParser:232 `handleDcsEscByte` 
`doApc` AnsiEscapeParser:245 `handleApcByte` 
`doApcEscape` AnsiEscapeParser:249 `handleApcEscByte` 
 
七、函数名与类型不符 —— 2 处
 
原始名称 位置 问题 推荐命名 
`unknownSequence` AnsiEscapeParser:253 这是一个函数（处理未知序列），但命名像变量/状态 `handleUnknownSequence` 
`dim` TerminalPaletteResolver:77 函数名是形容词，实际动作是"对颜色施加暗淡效果" `applyDim` 或 `dimColor` 
 
八、单字母 CSI 处理函数 —— 6 处
 
`TerminalEmulator` 中 `handleCsiJ`、`handleCsiK` 等用单字母标识 CSI 命令，不查终端协议文档无法知道 J=擦除显示、K=擦除行。
 
原始名称 位置 真实意义 推荐命名 
`handleCsiJ` TerminalEmulator CSI J：擦除显示（ED） `handleEraseDisplay` 
`handleCsiK` TerminalEmulator CSI K：擦除行（EL） `handleEraseLine` 
`handleCsiL` TerminalEmulator CSI L：插入行（IL） `handleInsertLines` 
`handleCsiM` TerminalEmulator CSI M：删除行（DL） `handleDeleteLines` 
`handleCsiP` TerminalEmulator CSI P：删除字符（DCH） `handleDeleteChars` 
`effectAttrOf` RenditionState:177 "Attr" 缩写 + "Of" 介词，不直观 `effectAttributeFor` 
 
九、其他不清晰命名 —— 5 处
 
原始名称 位置 真实意义 推荐命名 
`mDefaultSelectors` TerminalView:270 4个-1组成的 IntArray，用于文本选择手柄的默认选择器状态 `defaultSelectionHandleSelectors` 
`asciiMeasures` TerminalRenderer:36 0..126 ASCII 字符的宽度测量缓存数组 `asciiCharWidths` 
`COMBINED_ATTRS` TerminalEmulator:1204 组合属性位掩码（私有常量） `COMBINED_ATTRIBUTE_MASK` 
`DSR_STATUS_RESPONSE` TerminalEmulator:1207 设备状态报告响应字符串（DSR=Device Status Report） `DEVICE_STATUS_RESPONSE` 
`execute` TerminalSession:86 启动子进程并拉起所有协程，函数名太泛 `start` 或 `startProcess` 
 
汇总统计
 
问题类型 数量 严重程度 
匈牙利命名法（m前缀） 62 中（一致性问题，不影响功能） 
布尔变量缺 is/has 前缀 8 中（可读性问题） 
命名拗口/语义重复 3 低-中 
缩写不规范 7 低 
接口 I 前缀 1 低（Kotlin 规范问题） 
函数 do 前缀 11 低 
函数名与类型不符 2 低 
单字母 CSI 函数 6 中（可维护性问题） 
其他不清晰命名 5 低 
合计 105  
 
重构优先级建议：
 
1.第一梯队（影响理解、易引入 bug）：布尔变量前缀、单字母 CSI 函数、`mUseLineDrawingUsesG0` 这类拗口命名
2.第二梯队（一致性）：匈牙利 m 前缀批量重命名（建议用 IDE Shift+F6 逐个文件处理，利用 Preview 预测冲突）
3.第三梯队（风格优化）：do 前缀、缩写、接口 I 前缀
 
所有重命名都建议使用 IntelliJ IDEA / Android Studio 的 Shift+F6 + Preview 功能，它会提前检测冲突并展示所有引用点，避免纯文本替换带来的误改。
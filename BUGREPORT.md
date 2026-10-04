# BUGREPORT（讨论模式复核版）

经对 PTY 输入数据流（`ITerminalProcess` -> `Utf8Decoder` -> `AnsiEscapeParser` -> `TerminalEmulator` / `RenditionState` / `TerminalBuffer` / `TerminalRow`）全路径的多智能体复核，原报告 13 条中确认真 BUG 6 处（含 1 处原报告遗漏），其余为误报或重复条目，已剔除。

---

## 确认为真 BUG

### 1. DECCARA / DECRARA 矩形区域属性改变的坐标换算 off-by-one
* **数据来源与调用链**：
  PTY 发送 `CSI Pt ; Pl ; Pb ; Pr ; ... $ r` 或 `$ t` 矩形属性改变序列。
  `AnsiEscapeParser` 解析后进入 `TerminalEmulator.handleCsiDollarRect`。
* **根源分析**（`TerminalEmulator.kt:822-823`）：
  ```kotlin
  val bottom = (AnsiEscapeParser.getArg(args, 2, mRows, true) + 1 + originTop).coerceAtMost(originBottom)
  val right = (AnsiEscapeParser.getArg(args, 3, mColumns, true) + 1 + originLeft).coerceAtMost(originRight)
  ```
  `top`/`left` 转为 0-based 闭区间起点，而 `bottom`/`right` 在半开区间语义下错误地多加了 `1`，导致操作区域比 PTY 声明的多包含一行一列。同文件 `handleCsiDollarErase` 的半开区间算法为正确参照，二者对照坐实为真 off-by-one，非推断。
* **数据源根治方案**：
  在 `handleCsiDollarRect` 入口将坐标统一换算为 0-based 半开区间，与 `handleCsiDollarErase` 的算法对齐；同时对 `top/bottom`、`left/right` 做升序规范化与屏幕边距夹取。
* **注意**：DECCARA/DECRARA 语法为 `CSI Pt;Pl;Pb;Pr;Pm $ r` / `$ t`（xterm ctlseqs），**无 Pp 参数**；`args[4]` 起为属性码，现有代码语义正确，勿按"参数错位"修改。Pp 属于 DECCRA `$v`，勿混淆。

### 2. SGR 38/48/58 扩展颜色：子模式未知时不推进参数指针导致错位解析
* **数据来源与调用链**：
  PTY 发送非标扩展颜色序列（如 `\e[38;3;100;100m`）。
  `RenditionState.selectGraphicRendition` 处理 `38/48/58`。
* **根源分析**（`RenditionState.kt:113-150`）：
  ```kotlin
  when (args[i + 1]) {
      2 -> { ... }
      5 -> { ...; i += 2 }
  }
  ```
  当子模式参数既非 `2` 也非 `5` 时，`when` 无匹配分支也无 `else`，指针 `i` 不推进。下一循环迭代把后续颜色参数当作顶级 SGR 命令执行（如把 `1` 当作加粗），导致终端样式紊乱。
* **数据源根治方案**：
  在 `when` 中显式处理未知子模式：按 SGR 语义消费掉后续参数并推进 `i`，或直接忽略整条扩展颜色序列，确保指针状态机一致。

### 3. TerminalRow 宽变窄：行末列写入引发数组越界崩溃
* **数据来源与调用链**：
  PTY 输出的单宽字符覆盖行末最后一列的双宽字符后半部分，触发 `TerminalRow.setChar` -> `handleWidthChange`。
* **根源分析**（`TerminalRow.kt:341`）：
  ```kotlin
  mStyle[(columnToSet + 1) * 2] = style.value
  mStyle[(columnToSet + 1) * 2 + 1] = 0L
  ```
  `mStyle` 长度为 `mColumns * 2`（`TerminalRow.kt:33`）。当 `columnToSet == mColumns - 1` 时，`(columnToSet + 1) * 2 == mColumns * 2`，超出最大合法索引，抛出 `ArrayIndexOutOfBoundsException`。
* **数据源根治方案**：
  在 `handleWidthChange` 的宽→窄分支前置 `columnToSet + 1 < mColumns` 约束，行末时只做单列降级。

### 4. CSI `S`（向上滚动）：无循环上限引发大循环卡顿
* **数据来源与调用链**：
  PTY 输出大行数滚动指令（如 `\e[9999S`）。
  `TerminalEmulator.handleCsiStandard` 的 `'S'` 分支：
  ```kotlin
  val linesToScroll = AnsiEscapeParser.getArg(args, 0, 1, true)
  for (i in 0 until linesToScroll) scrollDownOneLine()
  ```
* **根源分析**：
  参数上限可达 9999，同一同步代码块内连续调用数千次 `scrollDownOneLine()`，造成大量缓冲区行移动与内存拷贝，帧率骤降甚至 ANR。同文件 `'T'` 分支已有 `min(linesBetween, …)` 保护，`'S'` 遗漏了对应上限。
* **数据源根治方案**：
  在数据入口将 `linesToScroll` 夹取到当前屏幕行数上限（与 `'T'` 的处理方式对齐）。

### 5. DCS 结束符解析：孤立反斜杠提前截断
* **数据来源与调用链**：
  PTY 输出包含反斜杠文本的 DCS 序列（如携带路径 `C:\test`）。
  `AnsiEscapeParser.doDeviceControl`（`AnsiEscapeParser.kt:222-225`）：
  ```kotlin
  '\\'.code.toByte() -> {
      handler.onDeviceControl(mOSCOrDeviceControlArgs.toString())
      finishSequence()
  }
  ```
* **根源分析**：
  DCS 的标准结束符 ST 是 `ESC \`，但此处遇到单独的 `\` 即终止，导致合法 DCS 数据串被非法截断。
* **数据源根治方案**：
  仅在 `P_ESCAPE` 状态后接 `\` 时才终止 DCS，孤立 `\` 作为普通数据字节处理。

### 6. DCS 数据字节经 `toByte()` 截断导致的误判终止（原报告遗漏）
* **数据来源与调用链**：
  PTY 输出 DCS 序列中包含高码点字符（如 U+015C Ŝ）。
  `AnsiEscapeParser.doDeviceControl` 中以 `b.toByte()` 做 `when` 匹配。
* **根源分析**：
  Char 到 Byte 的窄化截断只保留低 8 位，U+015C 的低字节恰为 `0x5C`（`'\\'`），被误判为 DCS 终止符，与 BUG 5 同函数叠加。
* **数据源根治方案**：
  终止符匹配基于完整码点（Char 值）而非截断后的 Byte，与 BUG 5 一并修复。

---

## 待定问题

1. **OSC 52（剪贴板同步）**：`OscHandler.handleOscClipboard` 缺少 payload 长度上限，且查询标记 `?` 会被送入 Base64 解码（有 `try/catch` 兜底）。是否计入真 BUG 未达成共识。
2. **BUG 3 触发频率**：需核查 `TerminalEmulator.kt:920-1251` 的 `emitCodePoint` 换行路径，确认末列宽字符写入是必现还是条件可达（影响真伪不成立，仅影响触发概率）。
3. 其余条目的修复方案未逐条评审，实施前需单独确认。

---

## 附：原报告剔除项（误报与重复）

| 原条目 | 裁定 | 理由 |
| --- | --- | --- |
| 前1 · SGR 256 色"篡改槽位" | 误报 | 256/257/258 是有意的哨兵值（SGR 39 即写 256），`0 until 259` 为设计如此；其修复方案（改成 0..255）反而有害 |
| 前3 / 后2 · 负坐标穿透 `require` | 误报（互为重复） | `AnsiEscapeParser.getArg` 对负参数一律回落默认值，路径不可达 |
| 前4 · DECSED 未知参数 | 误报 | `-1 until -1` 空循环不崩溃，静默忽略符合 ECMA-48 |
| 前5(a) · 行末宽字符拆分 | 误报 | `wideDisplayCharacterStartingAt(columnToSet + 1)` 在行末恒假 |
| 前6 · 边距倒置崩溃 | 误报 | `'r'`/`'s'` 与 resize 路径（`TerminalEmulator.kt:202-203`）维持边距不变式；`TerminalBuffer.kt:487/495` 有 `if (charsToMove > 0)` 守卫 |
| 后1 · DECSTBM `coerceIn(0, mRows-2)` | 不可达 | 唯一尺寸入口 `TerminalView.kt:521-522` 用 `max(4, …)` 夹取，`mRows ≥ 4` 恒成立（属防御性缺口） |
| 前5(b) ↔ 后4 | 重复 | 同一处 `TerminalRow.kt:341`，计为本报告 BUG 3 |
| 前1 ↔ 后3 | 部分重复 | 256 色部分为共同误报；后3 的"不推进 i"为真缺陷，计为本报告 BUG 2 |

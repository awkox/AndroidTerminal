# 屏幕缓冲区架构设计

终端屏幕数据由 `TerminalEmulator`、`TerminalBuffer`、`TerminalRow` 协同管理。

## 1. 整体架构

**双缓冲区**：`TerminalEmulator` 维护 `mMainBuffer`（主缓冲区，支持滚动历史）与 `mAltBuffer`（备用缓冲区，大小等于可视区域，用于 `vim`/`htop` 等全屏应用）。

**环形数组**：`TerminalBuffer` 使用 `mLines: Array<TerminalRow?>` 管理行，通过 `mScreenFirstRow` 指针记录可视区域首行。滚动时移动指针并覆盖旧数据，避免大规模内存拷贝。

## 2. 单行数据结构 (`TerminalRow`)

采用基础类型数组存储字符与样式，避免高频对象分配。

### 2.1 字符存储 (`mText`)

- `CharArray`，容量为 `mColumns * 1.5`，容纳 UTF-16 代理项对与组合字符
- `mHasNonOneWidthOrSurrogateChars` 标志：全为 ASCII 单宽字符时走 O(1) 快速索引路径，否则 O(N) 累加

### 2.2 样式存储 (`mStyle`)

- `LongArray`，长度 `mColumns * 2`，交错存储
- 偶数索引：主样式（前景/背景色、粗体、斜体、反色等），由 `TextStyle` 内联类管理
- 奇数索引：扩展特效（下划线样式：波浪/虚线/双划线，及独立下划线颜色）

## 3. 样式编码 (`TextStyle`)

`@JvmInline value class TextStyle(val value: Long)`，将所有属性压缩到 64 位，零对象分配。

**主样式位布局**：

| 位段 | 宽度 | 内容 |
|------|------|------|
| 0–10 | 11 bit | 特效标志位（粗体、斜体、下划线、闪烁、反色、隐藏、删除线、受保护、暗淡、前景真色标志、背景真色标志） |
| 16–39 | 24 bit | 背景色（真色标志=0 时低 9 位为 256 色索引，=1 时为 24-bit RGB） |
| 40–63 | 24 bit | 前景色（编码同背景色） |

**扩展特效位布局**：

| 位段 | 宽度 | 内容 |
|------|------|------|
| 0–2 | 3 bit | 下划线样式（0=无, 1=单线, 2=双划线, 3=波浪线, 4=点线, 5=虚线） |
| 3 | 1 bit | 下划线真色标志 |
| 16–39 | 24 bit | 下划线独立颜色 |

## 4. 复杂文本布局

由 `WcWidth.kt` 计算 Unicode 字符视觉列宽（0/1/2）。

- **宽字符（Width=2，如 CJK）**：占用目标列与下一列。若新字符写在已有宽字符后半部分，先将该宽字符替换为空格（拆分），防止渲染撕裂。
- **零宽/组合字符（Width=0）**：不消耗列指针，追加到前一字符后。每基础字符最多附加 15 个组合字符（`MAX_COMBINING_CHARACTERS_PER_COLUMN`）。
- **自动换行**：`mLineWrap` 标记该行是否因达到右边界自动折行，用于 Reflow 与双击选段。

## 5. 屏幕尺寸调整 (Reflow)

`TerminalReflower.kt` 处理窗口大小变化。

- **垂直调整**：高度改变，移动 `mScreenFirstRow` 指针，增减可见历史（`handleSimpleVerticalResize`）。
- **水平调整 (Reflow)**：`handleHorizontalResize`
  1. 分配新缓冲区矩阵
  2. 遍历旧缓冲区，`mLineWrap=true` 的行与下一行视为同一逻辑段落
  3. 忽略行尾对齐空白，但保留带自定义背景色的空白
  4. 按新列宽重新排布字符序列
  5. 遇到原光标位置时计算并更新新坐标

## 6. 文本选择与提取

`TerminalBuffer.getSelectedText()`：

1. `findStartOfColumn(column)` 将网格列号映射为 `CharArray` 绝对索引（非 1:1，因零宽与宽字符）
2. 丢弃行尾填充空字符
3. `mLineWrap=true` 的行不插入 `\n`，将自动换行的多行还原为长字符串

## 7. 其他机制

- **延迟实例化**：`allocateFullLineIfNecessary(row)` 仅在需要写入的行才初始化 `TerminalRow`。
- **区块复制**：`CSI L`/`CSI M`/换行滚动触发 `blockCopyLinesDown` 或 `TerminalRow.copyInterval`，处理宽字符半截断边界清理。
- **UTF-8 状态机解码** (`Utf8Decoder.kt`)：逐字节解码，应对 I/O 流截断（多字节字符被拆分在两次读取中），组装完整 CodePoint 后送入缓冲区。

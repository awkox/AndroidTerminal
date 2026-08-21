# 终端屏幕缓冲区架构设计文档

## 1. 整体架构 (Architecture)
终端的屏幕数据管理主要由 `TerminalEmulator`、`TerminalBuffer` 和 `TerminalRow` 三个核心类协同完成。

*   **双缓冲区机制 (Dual Buffer System)：**
    *   `TerminalEmulator` 维护了两个缓冲区：`mMainBuffer`（主缓冲区）和 `mAltBuffer`（备用缓冲区）。
    *   **主缓冲区**：支持滚动历史记录（Scrollback Transcript），常用于标准的 Shell 交互。
    *   **备用缓冲区**：不支持滚动历史，其大小严格等于屏幕可视区域大小。常用于 `vim`、`nano`、`htop` 等全屏 TUI 应用程序。
*   **环形数组 (Circular Array)：**
    *   `TerminalBuffer` 使用环形对象数组 `mLines: Array<TerminalRow?>` 来管理所有的行。
    *   通过 `mScreenFirstRow` 指针记录当前可视区域的第一行，向上/向下滚动时，只需移动指针并覆盖旧数据，**避免了大规模的内存拷贝操作**（除了必要的局部块复制 `blockCopy`）。

---

## 2. 单行数据结构 (`TerminalRow`)
`TerminalRow` 是缓冲区的基础存储单元。为了避免在极高频的终端输出中产生大量的对象分配（Object Allocation）从而引发 GC 卡顿，`TerminalRow` 完全采用**基础数据类型数组（Primitive Arrays）**来存储字符与样式。

### 2.1 字符存储 (`mText`)
*   **存储媒介：** 使用 Java `CharArray` 存储字符。
*   **容量冗余：** 数组大小不是严格等于列数，而是 `mColumns * SPARE_CAPACITY_FACTOR (1.5)`。这是为了容纳：
    1.  **UTF-16 代理项对 (Surrogate Pairs)：** 像 Emoji 等超出 BMP（基本多语言平面）的字符在 Java 中需要 2 个 `Char`。
    2.  **组合字符 (Combining Characters)：** 宽度为 0，依附在主字符上，占用 `Char` 但不占用终端列。
*   **快速路径 (Fast Path)：** 包含一个布尔标志 `mHasNonOneWidthOrSurrogateChars`。如果整行都是标准的 ASCII 单宽字符，相关的索引计算会直接走 $O(1)$ 的快速路径；否则走 $O(N)$ 的慢速路径累加计算。

### 2.2 样式存储 (`mStyle`)
*   **存储媒介：** 使用 `LongArray` 存储每一列的样式。数组长度是 `mColumns * 2`。
*   **交错存储设计：** 
    *   **偶数索引 (`column * 2`)：** 存储主样式（前景色、背景色、粗体、斜体、反色等），由 `TextStyle` 内联类管理。
    *   **奇数索引 (`column * 2 + 1`)：** 存储扩展特效（扩展下划线样式：波浪线、虚线、双划线，以及独立的下划线颜色）。

---

## 3. 样式编码技术 (`TextStyle`)
终端样式极度消耗内存（每个字符单元都需要记录颜色和属性）。该项目使用 Kotlin 的 `@JvmInline value class TextStyle(val value: Long)`，将所有属性极限压缩到一个 64 位的 `Long` 中，实现**零对象分配 (Zero Allocation)**。

**主样式 (`Long`) 的位布局 (Bit Layout)：**
*   **Bit 0~10 (11 bits)：** 特效标志位掩码（粗体、斜体、下划线、闪烁、反色、隐藏、删除线、受保护、暗淡、前景真色标志、背景真色标志）。
*   **Bit 16~39 (24 bits)：** 背景色。如果背景真色标志为 0，则低 9 位为 256 色调色板索引（0~258）；如果为 1，则为完整的 24-bit RGB 真彩色。
*   **Bit 40~63 (24 bits)：** 前景色。编码方式同背景色。

**扩展特效 (`Long`) 的位布局：**
*   **Bit 0~2 (3 bits)：** 下划线样式（0=无, 1=单线, 2=双划线, 3=波浪线, 4=点线, 5=虚线）。
*   **Bit 3 (1 bit)：** 下划线真色标志。
*   **Bit 16~39 (24 bits)：** 下划线的独立颜色。

---

## 4. 复杂文本布局 (Complex Text Layout)
终端采用严格的等宽网格排版，但 Unicode 打破了这种简单性。由 `WcWidth.kt` 负责计算 Unicode 字符在终端上的视觉列宽（0, 1, 或 2）。

*   **宽字符 (Width = 2，如中日韩汉字)：**
    *   写入时，占用目标列和目标列的下一列。
    *   **拆分处理：** 如果新的字符正好写在某个已有宽字符的“后半部分”，缓冲区会先将该宽字符替换为空格（拆分），防止半个汉字遗留在屏幕上导致渲染撕裂。
*   **零宽字符/组合字符 (Width = 0，如声调、变音符号)：**
    *   写入时，不消耗列指针。
    *   直接在 `CharArray` 中追加到前一个字符后面。
    *   为了防止恶意输出造成内存溢出或拒绝服务（DoS），限制每个基础字符最多附加 15 个组合字符 (`MAX_COMBINING_CHARACTERS_PER_COLUMN`)。
*   **自动换行 (Auto-Wrap)：**
    *   `TerminalRow.mLineWrap` 标记该行是否是因为达到了右边界而自动折行的。这对于调整窗口大小（Reflow）和双击选中整段文本至关重要。

---

## 5. 屏幕尺寸动态调整 (Reflow)
当设备旋转或分屏导致终端窗口大小改变时，缓冲区会调用 `TerminalReflower.kt` 执行复杂的文本重排。

*   **垂直调整 (Vertical Resize)：** 高度改变，宽度不变。这是简单的操作，只需移动 `mScreenFirstRow` 指针，增加或减少可见的历史记录即可（`handleSimpleVerticalResize`）。
*   **水平调整 (Horizontal Resize - Reflow)：** 极其复杂的计算密集型操作（`handleHorizontalResize`）。
    1.  分配一个全新的空缓冲区矩阵。
    2.  遍历旧缓冲区的每一行。
    3.  如果遇到 `mLineWrap == true` 的行，将其与下一行视为同一个逻辑段落。
    4.  忽略单纯用于对齐的行尾空白，但**保留带有自定义背景色的空白符**。
    5.  将提取出的字符序列，结合宽字符和组合字符规则，重新塞入新的列宽限制中。
    6.  如果在重排过程中遇到了原来的光标位置，精确计算并更新光标在新缓冲区中的 `(x, y)` 坐标。

---

## 6. 文本选择与提取
在 `TerminalBuffer.getSelectedText()` 中，实现跨行列文本内容的提取（用于复制到剪贴板）：
1.  **坐标映射：** 通过 `findStartOfColumn(column)` 将网格列号（Column）映射为 `CharArray` 的绝对索引（Index）。由于零宽字符和宽字符的存在，这不是简单的 1:1 映射。
2.  **空白修剪：** 提取文本时，会自动丢弃终端行尾用于填充的空字符。
3.  **换行符拼接：** 如果一行的 `mLineWrap` 属性为 `true`，在提取文本时**不会**在行尾插入 `\n`，从而将自动换行的多行文本完美还原为长字符串。

---

## 7. 其他关键技术
*   **延迟实例化 (Lazy Allocation)：** 
    在 `TerminalBuffer` 中，行对象并不是一次性全部创建的。通过 `allocateFullLineIfNecessary(row)` 方法，只在实际需要写入数据的行才初始化 `TerminalRow`，有效降低了初始内存占用。
*   **区块复制 (Block Copy / Scroll)：**
    `CSI L` (插入行)、`CSI M` (删除行)、或者普通的换行滚动，都会触发 `blockCopyLinesDown` 或 `TerminalRow.copyInterval`。该实现深入处理了宽字符在一半被截断时的边界清理。
*   **UTF-8 状态机解码 (`Utf8Decoder.kt`)：**
    为了应对进程 I/O 流截断（一个汉字的 3 个字节被分在两次 TCP 报文或两次 Pipe 读取中返回），使用状态机逐字节解码 UTF-8，组装成完整的 CodePoint 后再送入终端缓冲区，避免了乱码产生。

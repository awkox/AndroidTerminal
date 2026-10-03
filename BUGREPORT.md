#### 致命问题 1：Backspace 退格键的非标准反向换行 (`ctrl+r` 错乱的主因)
在 bash 中使用 `ctrl+r` 时，`readline` 库会不断通过 `\b` (Backspace) 或 `\r` 配合移动光标来重绘提示符。标准终端（如 xterm）在默认情况下，**退格键遇到屏幕左边缘（第 0 列）时会停留在原地，绝不会退到上一行的行尾**。
但你的 `TerminalEmulator.kt` 中 `onBackspace` 实现了强制反向换行：
```kotlin
// 现有错误代码：
override fun onBackspace() {
    if (mLeftMargin == mCursorCol) {
        val previousRow = mCursorRow - 1
        if (previousRow >= mTopMargin && screen.getLineWrap(previousRow)) {
            screen.clearLineWrap(previousRow)
            setCursorRowCol(previousRow, mRightMargin - 1) // 致命：回退到了上一行！
        }
    } else {
        this.cursorCol = mCursorCol - 1
    }
}
```
**后果**：Bash 认为退格被屏幕边缘挡住了（维持在 0 列），但你的模拟器却把光标移到了上一行。两者的“光标心理模型”发生严重脱节，导致后续输出的内容全部错位覆盖。

#### 致命问题 2：`CSI J` 清屏指令错误应用了滚动边距 (全屏程序错乱的主因)
全屏程序（如 `vim`）通常会设置滚动边距（Scrolling Margins，即 `\e[r`），然后使用 `CSI J`（擦除屏幕）指令。
按照 ANSI 标准，**`CSI J` 和 `CSI K` 必须忽略边距，对物理屏幕的绝对尺寸生效**。但你在 `handleCsiJ` 中错误地使用了 `mRightMargin`, `mTopMargin`, `mBottomMargin`：
```kotlin
// 现有错误代码：
private fun handleCsiJ(args: IntArray) {
    // ...
    0 -> {
        blockClear(mCursorCol, mCursorRow, mRightMargin - mCursorCol) // 错用了 mRightMargin
        blockClear(mLeftMargin, mCursorRow + 1, mRightMargin - mLeftMargin, mBottomMargin - (mCursorRow + 1)) // 错用了 mLeftMargin 和 mBottomMargin
    }
    1 -> {
        blockClear(0, mTopMargin, mColumns, mCursorRow - mTopMargin) // 错用了 mTopMargin
        // ...
    }
```
**后果**：全屏程序清屏时，屏幕外围的区域没有被清掉，残留了大量幽灵字符。

#### 致命问题 3：抛出异常导致缓冲区数据块（Chunk）被丢弃
这是问题 2 引发的级联灾难。看 `handleCsiJ` 的 0 和 1 分支：
如果此时光标处于边距之外（比如 `mCursorRow < mTopMargin`），那么 `mCursorRow - mTopMargin` 就是**负数**。
在 `TerminalBuffer.blockSet` 中有严格的参数检查：
```kotlin
require(!(w < 0 || h < 0 ...)) // 负数高度会抛出 IllegalArgumentException
```
当异常抛出时，`TerminalSession` 的 `launchEmulatorProcessor` 协程中的 `catch (e: Exception)` 会捕获它。**但这会导致当前这一整块字节（Chunk，可能包含几千字节的后续绘图指令）直接被丢弃！** 画面会瞬间严重撕裂。


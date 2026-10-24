package com.awkoo.libterminal.view.textselection

import android.content.ClipboardManager
import android.graphics.Rect
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import com.awkoo.libterminal.R
import com.awkoo.libterminal.engine.buffer.SnapAlign
import com.awkoo.libterminal.engine.buffer.TerminalBuffer
import com.awkoo.libterminal.view.interact.ActionModeItem
import com.awkoo.libterminal.view.TerminalView
import kotlin.math.max

internal class TextSelectionCursorController(private val terminalView: TerminalView) : CursorController {
    private val mStartHandle: TextSelectionHandleView =
        TextSelectionHandleView(terminalView, this, TextSelectionHandleView.LEFT)
    private val mEndHandle: TextSelectionHandleView =
        TextSelectionHandleView(terminalView, this, TextSelectionHandleView.RIGHT)
    private var mIsSelectingText = false
    private var mShowStartTime = System.currentTimeMillis()

    private val mHandleHeight: Int
        get() = max(mStartHandle.handleHeight, mEndHandle.handleHeight)
    private var selectionStartCol = -1
    private var selectionStartRow = -1
    private var selectionEndCol = -1
    private var selectionEndRow = -1

    var actionMode: ActionMode? = null
        private set

    /** 当前 ActionMode 中注册的额外菜单项（与菜单 ID 一一对应）。 */
    private var customItems: List<ActionModeItem> = emptyList()

    override fun show(event: MotionEvent) {
        // 触发选择的瞬间最先冻结屏幕，再执行定位等其它操作，避免冻结前的间隙里后台输出使选区漂移
        terminalView.mEmulator?.let { emulator ->
            synchronized(emulator) { emulator.setInputPaused(true) }
        }

        setInitialTextSelectionPosition(event)

        mShowStartTime = System.currentTimeMillis()
        mIsSelectingText = true

        render()
        setActionModeCallBacks()
    }

    override fun hide(): Boolean {
        if (!isActive) return false

        // 防止在 show 调用后立即 hide（如长按方向键）
        if (System.currentTimeMillis() - mShowStartTime < 300) {
            return false
        }

        mStartHandle.hide()
        mEndHandle.hide()

        actionMode?.finish()
        actionMode = null

        selectionEndRow = -1
        selectionStartRow = -1
        selectionEndCol = -1
        selectionStartCol = -1
        mIsSelectingText = false

        // 退出选择模式即解除冻结，按序重放冻结期间的输出并恢复实时
        terminalView.mEmulator?.let { emulator ->
            synchronized(emulator) { emulator.setInputPaused(false) }
        }

        return true
    }

    override fun render() {
        if (!isActive) return

        val left = terminalView.paddingLeft
        val right = terminalView.width - terminalView.paddingRight
        val top = terminalView.paddingTop
        val bottom = terminalView.height - terminalView.paddingBottom

        // 提取统一渲染逻辑，利用 Kotlin 的局部函数减少重复代码
        fun layoutHandle(
            handle: TextSelectionHandleView,
            selX: Int,
            selY: Int,
            isStart: Boolean
        ) {
            // 注意：结束光标 (isStart=false) 需要停在所选字符的末尾，即 selX + 1
            val pointX = terminalView.getPointX(if (isStart) selX else selX + 1)
            val pointY = terminalView.getPointY(selY + 1)

            val isVisible = pointX in left..right && pointY in top..bottom
            val orientation = when {
                pointX - handle.handleWidth < left -> TextSelectionHandleView.RIGHT
                pointX + handle.handleWidth > right -> TextSelectionHandleView.LEFT
                else -> if (isStart) TextSelectionHandleView.LEFT else TextSelectionHandleView.RIGHT
            }
            
            handle.updateLayout(pointX, pointY, isVisible, orientation)
        }

        // 统一调用，消灭冗余代码
        layoutHandle(mStartHandle, selectionStartCol, selectionEndCol, isStart = true)
        layoutHandle(mEndHandle, selectionStartRow, selectionEndRow, isStart = false)

        actionMode?.invalidate()
    }

    fun setInitialTextSelectionPosition(event: MotionEvent) {
        val emulator = terminalView.mEmulator ?: return

        synchronized(emulator) {
            val screen = emulator.screen

            val coord = terminalView.bufferCellAt(event.x, event.y)
            selectionStartCol = coord.col
            selectionEndCol = coord.row
            selectionStartRow = coord.col
            selectionEndRow = coord.row

            if (" " != screen.getSelectedText(selectionStartCol, selectionEndCol, selectionStartCol, selectionEndCol)) {
                // 选中的不是空白字符，扩展为单词选择。
                // 单词可能跨折行行续到下一行，且折行处无空格：扩展需跨越行边界。
                // 左扩：
                while (true) {
                    if (selectionStartCol > 0) {
                        // 用 isCellBlank 轻量检测，避免逐格构造字符串的性能开销
                        if (!screen.isCellBlank(selectionStartCol - 1, selectionEndCol)) {
                            selectionStartCol--
                        } else break
                    } else if (screen.getLineWrap(selectionEndCol - 1) &&
                        // 上一行折行且行尾格非空白才跨行，避免把空白格兜进选区
                        !screen.isCellBlank(emulator.mColumns - 1, selectionEndCol - 1)
                    ) {
                        // 当前行是上一行的折行继续行，跳回上一行行尾继续扩展
                        selectionEndCol--
                        selectionStartCol = emulator.mColumns - 1
                    } else break
                }
                // 右扩：
                while (true) {
                    if (selectionStartRow < emulator.mColumns - 1) {
                        if (!screen.isCellBlank(selectionStartRow + 1, selectionEndRow)) {
                            selectionStartRow++
                        } else break
                    } else if (screen.getLineWrap(selectionEndRow) &&
                        // 下一行折行起始格非空白才跨行，避免把空白格兜进选区
                        !screen.isCellBlank(0, selectionEndRow + 1)
                    ) {
                        // 当前行折行到下一行，跳到下一行列 0 继续扩展
                        selectionEndRow++
                        selectionStartRow = 0
                    } else break
                }
            }
        }
    }

    fun setActionModeCallBacks() {
        this.actionMode = terminalView.startActionMode(object : ActionMode.Callback2() {
            override fun onCreateActionMode(mode: ActionMode?, menu: Menu): Boolean {
                val show = MenuItem.SHOW_AS_ACTION_IF_ROOM or MenuItem.SHOW_AS_ACTION_WITH_TEXT
                val clipboard = terminalView.context.getSystemService(ClipboardManager::class.java)
                val customizer = terminalView.actionModeCustomizer

                val copyLabel = customizer?.copyText() ?: terminalView.context.getString(R.string.copy_text)
                val pasteLabel = customizer?.pasteText() ?: terminalView.context.getString(R.string.paste_text)

                menu.add(Menu.NONE, ACTION_COPY, Menu.NONE, copyLabel)
                    .setShowAsAction(show)
                menu.add(Menu.NONE, ACTION_PASTE, Menu.NONE, pasteLabel)
                    .setEnabled(clipboard.hasPrimaryClip())
                    .setShowAsAction(show)

                customItems = customizer?.createActionModeItems() ?: emptyList()
                customItems.forEachIndexed { index, item ->
                    menu.add(Menu.NONE, ACTION_CUSTOM_BASE + index, Menu.NONE, item.title)
                        .apply { item.icon?.let { setIcon(it) } }
                        .setShowAsAction(show)
                }
                return true
            }

            override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?) = false

            override fun onActionItemClicked(mode: ActionMode?, item: MenuItem): Boolean {
                if (!isActive) return true

                when (item.itemId) {
                    ACTION_COPY -> {
                        terminalView.copyTextToClipboard()
                        terminalView.stopTextSelectionMode()
                    }
                    ACTION_PASTE -> {
                        terminalView.stopTextSelectionMode()
                        terminalView.pasteTextFromClipboard()
                    }
                    else -> {
                        val customIndex = item.itemId - ACTION_CUSTOM_BASE
                        if (customIndex in customItems.indices) {
                            customItems[customIndex].onClick(selectedText)
                            terminalView.stopTextSelectionMode()
                        }
                    }
                }
                return true
            }

            override fun onDestroyActionMode(mode: ActionMode?) {
                if (actionMode === mode) {
                    actionMode = null
                    customItems = emptyList()
                    terminalView.stopTextSelectionMode()
                }
            }

            override fun onGetContentRect(mode: ActionMode?, view: View?, outRect: Rect) {
                var x1 = terminalView.getPointX(selectionStartCol)
                var x2 = terminalView.getPointX(selectionStartRow + 1)
                val top = terminalView.getPointY(selectionEndCol)
                var bottom = terminalView.getPointY(selectionEndRow + 1)

                if (x1 > x2) {
                    x1 = x2.also { x2 = x1 }
                }

                val terminalBottom = terminalView.bottom
                bottom += mHandleHeight
                if (bottom > terminalBottom) bottom = terminalBottom

                outRect.set(x1, top, x2, bottom)
            }
        }, ActionMode.TYPE_FLOATING)
    }

    override fun updatePosition(handle: TextSelectionHandleView, x: Int, y: Int) {
        val emulator = terminalView.mEmulator ?: return

        synchronized(emulator) {
            val screen = emulator.screen
            val isStart = handle === mStartHandle

            // 1. 获取光标基础坐标（产出方已夹取到缓冲区合法范围）
            val coord = terminalView.bufferCellAt(x.toFloat(), y.toFloat())
            var curX = coord.col
            var curY = coord.row

            // 2. 互斥检查与值更新 (将 mSelX1/mSelY1 和 mSelX2/mSelY2 的交叉修改进行精简)
            if (isStart) {
                if (curY > selectionEndRow || (curY == selectionEndRow && curX > selectionStartRow)) {
                    curY = selectionEndRow
                    curX = selectionStartRow
                }
                selectionEndCol = curY
                selectionStartCol = curX
            } else {
                if (curY < selectionEndCol || (curY == selectionEndCol && curX < selectionStartCol)) {
                    curY = selectionEndCol
                    curX = selectionStartCol
                }
                selectionEndRow = curY
                selectionStartRow = curX
            }

            // 3. 处理超出屏幕的滚动逻辑
            if (!emulator.isAlternateBufferActive) {
                if (curY <= terminalView.topRow) {
                    terminalView.scrollbackBy(1)
                } else if (curY >= terminalView.topRow + emulator.mRows) {
                    terminalView.scrollbackBy(-1)
                }
            }

            // 4. 校准中文字符/宽字符对齐
            if (isStart) {
                selectionStartCol = snapColumnToCharBoundary(screen, selectionEndCol, selectionStartCol, SnapAlign.Start)
            } else {
                selectionStartRow = snapColumnToCharBoundary(screen, selectionEndRow, selectionStartRow, SnapAlign.End)
            }
        }

        terminalView.invalidate()
    }

    /**
     * 把列号校准到字符边界。
     *
     * 起点向字符起始吸附，否则该宽字符被漏选；终点向字符之后吸附，否则该半格无归属。
     */
    private fun snapColumnToCharBoundary(screen: TerminalBuffer, cy: Int, cx: Int, align: SnapAlign): Int =
        screen.snapToColumn(cy, cx, align)

    fun shiftSelectionRows(rowShift: Int) {
        selectionEndCol -= rowShift
        selectionEndRow -= rowShift
    }

    override fun onTouchModeChanged(isInTouchMode: Boolean) {
        if (!isInTouchMode) terminalView.stopTextSelectionMode()
    }

    override val isActive: Boolean
        get() = mIsSelectingText

    fun getSelectors(sel: IntArray) {
        sel[0] = selectionEndCol
        sel[1] = selectionEndRow
        sel[2] = selectionStartCol
        sel[3] = selectionStartRow
    }

    val selectedText: String
        get() {
            val emulator = terminalView.mEmulator ?: return ""
            synchronized(emulator) {
                return emulator.getSelectedText(selectionStartCol, selectionEndCol, selectionStartRow, selectionEndRow)
            }
        }

    companion object {
        const val ACTION_COPY: Int = 1
        const val ACTION_PASTE: Int = 2
        const val ACTION_CUSTOM_BASE: Int = 10000
    }
}
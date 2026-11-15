package com.awkoo.libterminal.view.textselection

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Rect
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import com.awkoo.libterminal.R
import com.awkoo.libterminal.engine.TerminalEmulator
import com.awkoo.libterminal.engine.buffer.CursorCoord
import com.awkoo.libterminal.engine.buffer.SnapAlign
import com.awkoo.libterminal.engine.buffer.TerminalBuffer
import com.awkoo.libterminal.view.TerminalView
import com.awkoo.libterminal.view.interact.ActionModeCustomizer
import com.awkoo.libterminal.view.interact.ActionModeItem
import kotlin.math.max

internal interface SelectionHandle {
    val handleHeight: Int
    val handleWidth: Int
    fun updateLayout(x: Int, y: Int, isVisible: Boolean, orientation: Int)
    fun hide()
}

internal interface SelectionHost {
    val emulator: TerminalEmulator?
    val context: Context
    val paddingLeft: Int
    val paddingRight: Int
    val paddingTop: Int
    val paddingBottom: Int
    val width: Int
    val height: Int
    val bottom: Int
    val topRow: Int
    val actionModeCustomizer: ActionModeCustomizer?
    fun bufferCellAt(x: Float, y: Float): CursorCoord
    fun getPointX(cx: Int): Int
    fun getPointY(cy: Int): Int
    fun scrollbackBy(deltaRows: Int)
    fun invalidate()
    fun startActionMode(callback: ActionMode.Callback, type: Int): ActionMode?
    fun copyTextToClipboard()
    fun pasteTextFromClipboard()
    fun stopTextSelectionMode()
}

private class TerminalViewSelectionHost(
    private val terminalView: TerminalView
) : SelectionHost {
    override val emulator: TerminalEmulator? get() = terminalView.emulator
    override val context: Context get() = terminalView.context
    override val paddingLeft: Int get() = terminalView.paddingLeft
    override val paddingRight: Int get() = terminalView.paddingRight
    override val paddingTop: Int get() = terminalView.paddingTop
    override val paddingBottom: Int get() = terminalView.paddingBottom
    override val width: Int get() = terminalView.width
    override val height: Int get() = terminalView.height
    override val bottom: Int get() = terminalView.bottom
    override val topRow: Int get() = terminalView.topRow
    override val actionModeCustomizer: ActionModeCustomizer? get() = terminalView.actionModeCustomizer

    override fun bufferCellAt(x: Float, y: Float): CursorCoord = terminalView.bufferCellAt(x, y)
    override fun getPointX(cx: Int): Int = terminalView.getPointX(cx)
    override fun getPointY(cy: Int): Int = terminalView.getPointY(cy)
    override fun scrollbackBy(deltaRows: Int) = terminalView.scrollbackBy(deltaRows)
    override fun invalidate() = terminalView.invalidate()
    override fun startActionMode(callback: ActionMode.Callback, type: Int): ActionMode? =
        terminalView.startActionMode(callback, type)
    override fun copyTextToClipboard() = terminalView.copyTextToClipboard()
    override fun pasteTextFromClipboard() = terminalView.pasteTextFromClipboard()
    override fun stopTextSelectionMode() = terminalView.stopTextSelectionMode()
}

internal class TextSelectionCursorController internal constructor(
    private val host: SelectionHost,
    handleFactory: (CursorController, Int) -> SelectionHandle
) : CursorController {
    constructor(terminalView: TerminalView) : this(
        host = TerminalViewSelectionHost(terminalView),
        handleFactory = { controller, orientation ->
            TextSelectionHandleView(terminalView, controller, orientation)
        }
    )

    private val startHandle: SelectionHandle = handleFactory(this, TextSelectionHandleView.LEFT)
    private val endHandle: SelectionHandle = handleFactory(this, TextSelectionHandleView.RIGHT)
    private var isSelectingText = false
    private var pausedEmulator: TerminalEmulator? = null

    private val handleHeight: Int
        get() = max(startHandle.handleHeight, endHandle.handleHeight)
    private var selectionStartCol = -1
    private var selectionEndCol = -1
    private var selectionStartRow = -1
    private var selectionEndRow = -1

    var actionMode: ActionMode? = null
        private set

    private var customItems: List<ActionModeItem> = emptyList()

    override fun show(event: MotionEvent) = showAt(event.x, event.y)

    internal fun showAt(x: Float, y: Float) {
        if (isActive) hide()

        // 触发选择的瞬间最先冻结屏幕，并记住实际冻结的模拟器实例，确保退出时 1:1 精准解冻
        val emulator = host.emulator
        emulator?.let {
            synchronized(it) { it.setInputPaused(true) }
            pausedEmulator = it
        }

        setInitialTextSelectionPosition(x, y)

        isSelectingText = true

        render()
        setSelectionActionMode()
    }

    override fun hide(): Boolean {
        if (!isActive) return false

        startHandle.hide()
        endHandle.hide()

        val mode = actionMode
        actionMode = null
        customItems = emptyList()
        mode?.finish()

        selectionEndRow = -1
        selectionEndCol = -1
        selectionStartRow = -1
        selectionStartCol = -1
        isSelectingText = false

        pausedEmulator?.let { emulator ->
            pausedEmulator = null
            synchronized(emulator) { emulator.setInputPaused(false) }
        }

        return true
    }

    override fun render() {
        if (!isActive) return

        val left = host.paddingLeft
        val right = host.width - host.paddingRight
        val top = host.paddingTop
        val bottom = host.height - host.paddingBottom

        fun layoutHandle(
            handle: SelectionHandle,
            selX: Int,
            selY: Int,
            isStart: Boolean
        ) {
            // 结束光标 (isStart=false) 需要停在所选字符的末尾，即 selX + 1
            val pointX = host.getPointX(if (isStart) selX else selX + 1)
            val pointY = host.getPointY(selY + 1)

            val isVisible = pointX in left..right && pointY in top..bottom
            val orientation = when {
                pointX - handle.handleWidth < left -> TextSelectionHandleView.RIGHT
                pointX + handle.handleWidth > right -> TextSelectionHandleView.LEFT
                else -> if (isStart) TextSelectionHandleView.LEFT else TextSelectionHandleView.RIGHT
            }

            handle.updateLayout(pointX, pointY, isVisible, orientation)
        }

        layoutHandle(startHandle, selectionStartCol, selectionStartRow, isStart = true)
        layoutHandle(endHandle, selectionEndCol, selectionEndRow, isStart = false)

        actionMode?.invalidate()
    }

    fun setInitialTextSelectionPosition(event: MotionEvent) =
        setInitialTextSelectionPosition(event.x, event.y)

    private fun setInitialTextSelectionPosition(x: Float, y: Float) {
        val emulator = host.emulator ?: return

        synchronized(emulator) {
            val screen = emulator.screen

            val coord = host.bufferCellAt(x, y)
            selectionStartCol = coord.col
            selectionStartRow = coord.row
            selectionEndCol = coord.col
            selectionEndRow = coord.row

            if (!screen.isCellBlank(selectionStartCol, selectionStartRow)) {
                // 左扩：
                while (true) {
                    if (selectionStartCol > 0) {
                        if (!screen.isCellBlank(selectionStartCol - 1, selectionStartRow)) {
                            selectionStartCol--
                        } else break
                    } else if (screen.getLineWrap(selectionStartRow - 1) &&
                        !screen.isCellBlank(emulator.columns - 1, selectionStartRow - 1)
                    ) {
                        selectionStartRow--
                        selectionStartCol = emulator.columns - 1
                    } else break
                }
                // 右扩：
                while (true) {
                    if (selectionEndCol < emulator.columns - 1) {
                        if (!screen.isCellBlank(selectionEndCol + 1, selectionEndRow)) {
                            selectionEndCol++
                        } else break
                    } else if (screen.getLineWrap(selectionEndRow) &&
                        !screen.isCellBlank(0, selectionEndRow + 1)
                    ) {
                        selectionEndRow++
                        selectionEndCol = 0
                    } else break
                }
            }
        }
    }

    fun setSelectionActionMode() {
        this.actionMode = host.startActionMode(object : ActionMode.Callback2() {
            override fun onCreateActionMode(mode: ActionMode?, menu: Menu): Boolean {
                val show = MenuItem.SHOW_AS_ACTION_IF_ROOM or MenuItem.SHOW_AS_ACTION_WITH_TEXT
                val clipboard = host.context.getSystemService(ClipboardManager::class.java)
                val customizer = host.actionModeCustomizer

                val copyLabel = customizer?.copyText() ?: host.context.getString(R.string.copy_text)
                val pasteLabel = customizer?.pasteText() ?: host.context.getString(R.string.paste_text)

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
                        host.copyTextToClipboard()
                        host.stopTextSelectionMode()
                    }
                    ACTION_PASTE -> {
                        host.stopTextSelectionMode()
                        host.pasteTextFromClipboard()
                    }
                    else -> {
                        val customIndex = item.itemId - ACTION_CUSTOM_BASE
                        if (customIndex in customItems.indices) {
                            customItems[customIndex].onClick(selectedText)
                            host.stopTextSelectionMode()
                        }
                    }
                }
                return true
            }

            override fun onDestroyActionMode(mode: ActionMode?) {
                if (actionMode === mode) {
                    actionMode = null
                    customItems = emptyList()
                    host.stopTextSelectionMode()
                }
            }

            override fun onGetContentRect(mode: ActionMode?, view: View?, outRect: Rect) {
                var x1 = host.getPointX(selectionStartCol)
                var x2 = host.getPointX(selectionEndCol + 1)
                val top = host.getPointY(selectionStartRow)
                var bottom = host.getPointY(selectionEndRow + 1)

                if (x1 > x2) {
                    x1 = x2.also { x2 = x1 }
                }

                val terminalBottom = host.bottom
                bottom += handleHeight
                if (bottom > terminalBottom) bottom = terminalBottom

                outRect.set(x1, top, x2, bottom)
            }
        }, ActionMode.TYPE_FLOATING)
    }

    override fun updatePosition(handle: SelectionHandle, x: Int, y: Int) {
        val emulator = host.emulator ?: return

        synchronized(emulator) {
            val screen = emulator.screen
            val isStart = handle === startHandle

            val coord = host.bufferCellAt(x.toFloat(), y.toFloat())
            var curX = coord.col
            var curY = coord.row

            if (isStart) {
                if (curY > selectionEndRow || (curY == selectionEndRow && curX > selectionEndCol)) {
                    curY = selectionEndRow
                    curX = selectionEndCol
                }
                selectionStartRow = curY
                selectionStartCol = curX
            } else {
                if (curY < selectionStartRow || (curY == selectionStartRow && curX < selectionStartCol)) {
                    curY = selectionStartRow
                    curX = selectionStartCol
                }
                selectionEndRow = curY
                selectionEndCol = curX
            }

            if (!emulator.isAlternateBufferActive) {
                if (curY <= host.topRow) {
                    host.scrollbackBy(1)
                } else if (curY >= host.topRow + emulator.rows) {
                    host.scrollbackBy(-1)
                }
            }

            if (isStart) {
                selectionStartCol = snapColumnToCharBoundary(screen, selectionStartRow, selectionStartCol, SnapAlign.Start)
            } else {
                selectionEndCol = snapColumnToCharBoundary(screen, selectionEndRow, selectionEndCol, SnapAlign.End)
            }
        }

        host.invalidate()
    }

    private fun snapColumnToCharBoundary(screen: TerminalBuffer, cy: Int, cx: Int, align: SnapAlign): Int =
        screen.snapToColumn(cy, cx, align)

    fun shiftSelectionRows(rowShift: Int) {
        selectionStartRow -= rowShift
        selectionEndRow -= rowShift
    }

    override fun onTouchModeChanged(isInTouchMode: Boolean) {
        if (!isInTouchMode) host.stopTextSelectionMode()
    }

    override val isActive: Boolean
        get() = isSelectingText

    fun getSelectors(sel: IntArray) {
        sel[0] = selectionStartRow
        sel[1] = selectionEndRow
        sel[2] = selectionStartCol
        sel[3] = selectionEndCol
    }

    val selectedText: String
        get() {
            val emulator = host.emulator ?: return ""
            synchronized(emulator) {
                return emulator.getSelectedText(selectionStartCol, selectionStartRow, selectionEndCol, selectionEndRow)
            }
        }

    companion object {
        const val ACTION_COPY: Int = 1
        const val ACTION_PASTE: Int = 2
        const val ACTION_CUSTOM_BASE: Int = 10000
    }
}
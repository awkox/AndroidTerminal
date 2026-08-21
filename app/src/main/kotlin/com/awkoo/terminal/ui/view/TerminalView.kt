package com.awkoo.terminal.ui.view

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Canvas
import android.graphics.Typeface
import android.os.SystemClock
import android.text.InputType
import android.view.ActionMode
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.awkoo.terminal.Constants
import com.awkoo.terminal.core.TerminalEmulator
import com.awkoo.terminal.core.TerminalSession
import com.awkoo.terminal.ui.view.textselection.TextSelectionCursorController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import timber.log.Timber
import kotlin.math.max
import kotlin.math.roundToInt

/** 外部修饰键状态快照，供 [TerminalView.onKeyDown] 消费。 */
data class ExtraKeysModifierSnapshot(
    val ctrl: Boolean = false,
    val alt: Boolean = false,
    val shift: Boolean = false,
    val fn: Boolean = false
)

/**
 * 终端显示与交互的核心 View。
 *
 * 作为中心协调器，整合以下组件：
 * - [TerminalRenderer] 文本渲染
 * - [TerminalTouchHandler] 触摸 / 鼠标 / 滚动
 * - [TerminalCursorBlinker] 光标闪烁动画
 * - [TerminalImeConnection] IME / 软键盘输入
 * - [TextSelectionCursorController] 文本选择
 *
 * 键盘处理（[onKeyDown]、[onKeyUp] 等）因与 View 状态和会话 I/O 紧密耦合，保留在此类中。
 */
class TerminalView(
    context: Context
) : View(context) {
    companion object {
        /** 虚拟 / 扩展按键键盘的事件来源标识。 */
        const val KEY_EVENT_SOURCE_VIRTUAL_KEYBOARD = 2
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var screenRefreshJob: Job? = null
    private var emulatorClipboardCollectJob: Job? = null

    val touchHandler = TerminalTouchHandler(this)

    // 键盘输入处理器：按键到会话字节流的转换逻辑委托对象
    private val keyProcessor = KeyInputProcessor(
        sessionProvider = { currentSession },
        emulatorProvider = { mEmulator },
        pokeCursor = { cursorBlinker.poke() },
        scrollPages = { pages ->
            val time = SystemClock.uptimeMillis()
            val motionEvent = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, 0f, 0f, 0)
            touchHandler.doScroll(motionEvent, pages)
            motionEvent.recycle()
        },
        isSelectingText = { isSelectingText },
        stopTextSelection = { stopTextSelectionMode() },
        modifierReader = { extraKeysModifierReader?.invoke() }
    )

    private val cursorBlinker = TerminalBlinker(
        blinkerName = "cursor",
        scope = scope,
        onInvalidate = { invalidate() },
        shouldStart = { it.isCursorEnabled },
        setBlinkingEnabled = { emulator, enabled -> emulator.isCursorBlinkingEnabled = enabled },
        setBlinkState = { emulator, state -> emulator.cursorBlinkState = state }
    )

    private val textBlinker = TerminalBlinker(
        blinkerName = "text",
        scope = scope,
        onInvalidate = { invalidate() },
        setBlinkingEnabled = { emulator, enabled -> emulator.isTextBlinkingEnabled = enabled },
        setBlinkState = { emulator, state -> emulator.textBlinkState = state }
    )

    /** 当前正在显示的终端会话。 */
    var currentSession: TerminalSession? = null
        set(value) {
            if (value == field) return
            stopTextSelectionMode()
            cursorBlinker.stop()
            textBlinker.stop()
            screenRefreshJob?.cancel()
            screenRefreshJob = null
            emulatorClipboardCollectJob?.cancel()
            emulatorClipboardCollectJob = null
            topRow = 0
            keyProcessor.reset()
            field = value

            if (value != null) {
                updateSize()
                onScreenUpdated()
                screenRefreshJob = value.uiEvent
                    .conflate()
                    .onEach { onScreenUpdated() }
                    .launchIn(scope)
                cursorBlinker.start(value.emulator)
                textBlinker.start(value.emulator)
                emulatorClipboardCollectJob = value.copiedText
                    .onEach { copyTextToClipboard(it) }
                    .launchIn(scope)
                toggleIme(true)
            } else {
                invalidate()
            }
        }

    val mEmulator: TerminalEmulator?
        get() = currentSession?.emulator

    var textSize: Int = Constants.DEFAULT_TERMINAL_FONT_SIZE
        set(value) {
            field = value.coerceIn(Constants.MIN_TERMINAL_FONT_SIZE, Constants.MAX_TERMINAL_FONT_SIZE)
            Timber.d("Setting textSize: ${field}dp, ${field.dp}px")
            mRenderer = TerminalRenderer(field.dp, typeface)
            updateSize()
        }

    var typeface: Typeface = Typeface.MONOSPACE
        set(value) {
            field = value
            Timber.d("Setting typeface")
            mRenderer = TerminalRenderer(textSize.dp, field)
            updateSize()
            invalidate()
        }

    var mRenderer = TerminalRenderer(textSize.dp, typeface)

    private val textSelectionCursorController = TextSelectionCursorController(this)

    internal val isSelectingText: Boolean
        get() = textSelectionCursorController.isActive

    var mDefaultSelectors: IntArray = intArrayOf(-1, -1, -1, -1)

    fun startTextSelectionMode(event: MotionEvent) {
        if (!requestFocus()) return
        textSelectionCursorController.show(event)
        invalidate()
    }

    fun stopTextSelectionMode() {
        if (textSelectionCursorController.hide()) invalidate()
    }

    private fun decrementYTextSelectionCursors(decrement: Int) {
        textSelectionCursorController.decrementYTextSelectionCursors(decrement)
    }

    private val textSelectionActionMode: ActionMode?
        get() = textSelectionCursorController.actionMode

    private val mShowFloatingToolbar: Runnable = Runnable {
        this@TerminalView.textSelectionActionMode?.hide(0)
    }

    fun updateFloatingToolbarVisibility(event: MotionEvent) {
        if (this.textSelectionActionMode != null) {
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> hideFloatingToolbar()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> showFloatingToolbar()
            }
        }
    }

    private fun showFloatingToolbar() {
        textSelectionActionMode?.let {
            val delay = ViewConfiguration.getDoubleTapTimeout()
            postDelayed(mShowFloatingToolbar, delay.toLong())
        }
    }

    private fun hideFloatingToolbar() {
        textSelectionActionMode?.let {
            removeCallbacks(mShowFloatingToolbar)
            it.hide(-1)
        }
    }

    /** 当前显示的顶行索引，范围从 -activeTranscriptRows 到 0。 */
    var topRow: Int = 0

    override fun computeVerticalScrollRange() = mEmulator?.screen?.activeRows ?: 1
    override fun computeVerticalScrollExtent() = mEmulator?.mRows ?: 1
    override fun computeVerticalScrollOffset(): Int {
        val emulator = mEmulator ?: return 1
        return emulator.screen.activeRows + this.topRow - emulator.mRows
    }

    private fun onScreenUpdated(skipScrolling: Boolean = false) {
        val emulator = mEmulator ?: return
        var skipScrolling = skipScrolling

        synchronized(emulator) {
            val rowsInHistory = emulator.screen.activeTranscriptRows
            if (this.topRow < -rowsInHistory) this.topRow = -rowsInHistory

            if (this.isSelectingText || emulator.isAutoScrollDisabled) {
                val rowShift = emulator.scrollCounter
                if (-this.topRow + rowShift > rowsInHistory) {
                    if (this.isSelectingText) stopTextSelectionMode()
                    if (emulator.isAutoScrollDisabled) {
                        this.topRow = -rowsInHistory
                        skipScrolling = true
                    }
                } else {
                    skipScrolling = true
                    this.topRow -= rowShift
                    decrementYTextSelectionCursors(rowShift)
                }
            }
        }

        if (!skipScrolling && this.topRow != 0) {
            if (this.topRow < -3) awakenScrollBars()
            this.topRow = 0
        }

        emulator.clearScrollCounter()
        invalidate()
    }

    /** 获取 MotionEvent 位置对应的 (列, 行) 坐标。 */
    fun getColumnAndRow(event: MotionEvent, relativeToScroll: Boolean): Pair<Int, Int> {
        val renderer = mRenderer
        val column = (event.x / renderer.fontWidth).toInt()
        var row = ((event.y - renderer.mFontLineSpacingAndAscent) / renderer.fontLineSpacing).toInt()
        if (relativeToScroll) row += this.topRow
        return Pair(column, row)
    }

    fun getCursorX(x: Float) = (x / mRenderer.fontWidth).toInt()
    fun getCursorY(y: Float) = (((y - 40) / mRenderer.fontLineSpacing) + this.topRow).toInt()

    fun getPointX(cx: Int): Int {
        val emulator = mEmulator!!
        val clamped = if (cx > emulator.mColumns) emulator.mColumns else cx
        return (clamped * mRenderer.fontWidth).roundToInt()
    }

    fun getPointY(cy: Int) = ((cy - this.topRow) * mRenderer.fontLineSpacing).toFloat().roundToInt()

    fun copyTextToClipboard() {
        copyTextToClipboard(textSelectionCursorController.selectedText)
    }

    fun copyTextToClipboard(text: String) {
        if (text.isEmpty()) return
        val clipboardManager = context.getSystemService(ClipboardManager::class.java)
        Timber.v("Copied text: \"$text\"")
        val clipData = ClipData.newPlainText("", text)
        clipboardManager.setPrimaryClip(clipData)
    }

    fun pasteTextFromClipboard() {
        val emulator = mEmulator ?: return
        val clipboardManager = context.getSystemService(ClipboardManager::class.java)
        val clipData = clipboardManager.primaryClip ?: return
        val clipItem = clipData.getItemAt(0) ?: return
        val text = clipItem.coerceToText(context)?.toString() ?: return
        Timber.v("Pasted text: \"$text\"")
        if (text.isNotEmpty()) {
            cursorBlinker.poke()
            emulator.paste(text)
        }
    }

    internal fun awakenScrollbars(): Boolean = awakenScrollBars()

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent) = touchHandler.onTouchEvent(event)

    override fun onGenericMotionEvent(event: MotionEvent) = touchHandler.onGenericMotionEvent(event)

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN
        return TerminalImeConnection(this)
    }

    override fun onCheckIsTextEditor() = true

    fun toggleIme(show: Boolean? = null) {
        requestFocus()

        val window = generateSequence(context) {
            (it as? ContextWrapper)?.baseContext
        }
            .filterIsInstance<Activity>()
            .firstOrNull()?.window ?: return

        val controller = WindowCompat.getInsetsController(window, this)
        val imeType = WindowInsetsCompat.Type.ime()

        val shouldShow = show ?: (ViewCompat.getRootWindowInsets(this)?.isVisible(imeType) != true)
        if (shouldShow) {
            controller.show(imeType)
        } else {
            controller.hide(imeType)
        }
    }

    /**
     * 外部修饰键状态读取器（如来自屏幕扩展按键栏）。
     * 设置后，[onKeyDown] 会同时参考 [KeyEvent] 元状态和此读取器，
     * 使粘性修饰键也作用于物理键盘输入。
     */
    var extraKeysModifierReader: (() -> ExtraKeysModifierSnapshot)? = null

    override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
        Timber.v("onKeyPreIme(keyCode=$keyCode, event=$event)")
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (this.isSelectingText) {
                stopTextSelectionMode()
                return true
            }
        } else if (Constants.isUsingCtrlSpaceWorkaround && keyCode == KeyEvent.KEYCODE_SPACE && event.isCtrlPressed) {
            return onKeyDown(keyCode, event)
        }
        return super.onKeyPreIme(keyCode, event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        Timber.v("onKeyDown(keyCode=$keyCode, isSystem=${event.isSystem}, event=$event)")
        return when (keyProcessor.onKeyDown(keyCode, event)) {
            KeyInputProcessor.KeyDownResult.HANDLED -> true
            KeyInputProcessor.KeyDownResult.NOT_HANDLED -> false
            KeyInputProcessor.KeyDownResult.PASS_TO_SUPER -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        Timber.v("onKeyUp(keyCode=$keyCode, event=$event)")
        if (mEmulator == null && keyCode != KeyEvent.KEYCODE_BACK) return true
        if (event.isSystem) return super.onKeyUp(keyCode, event)
        return true
    }

    internal fun inputCodePoint(
        eventSource: Int,
        codePoint: Int,
        controlDownFromEvent: Boolean,
        leftAltDownFromEvent: Boolean
    ) = keyProcessor.inputCodePoint(eventSource, codePoint, controlDownFromEvent, leftAltDownFromEvent)

    internal fun handleKeyCode(keyCode: Int, keyMod: Int): Boolean = keyProcessor.handleKeyCode(keyCode, keyMod)

    override fun isOpaque() = true

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(-0x1000000)
        val emulator = mEmulator ?: return
        val sel = mDefaultSelectors
        textSelectionCursorController.getSelectors(sel)
        synchronized(emulator) {
            mRenderer.render(emulator, canvas, this.topRow, sel[0], sel[1], sel[2], sel[3])
        }
        textSelectionCursorController.render()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        updateSize()
    }

    private fun updateSize() {
        val currentSession = currentSession ?: return
        val emulator = currentSession.emulator
        val viewWidth = width
        val viewHeight = height
        if (viewWidth == 0 || viewHeight == 0) return

        val newColumns = max(4, (viewWidth / mRenderer.fontWidth).toInt())
        val newRows = max(4, (viewHeight - mRenderer.mFontLineSpacingAndAscent) / mRenderer.fontLineSpacing)

        if (newColumns != emulator.mColumns || newRows != emulator.mRows) {
            currentSession.updateSize(newColumns, newRows, mRenderer.fontWidth.toInt(), mRenderer.fontLineSpacing)
            this.topRow = 0
            scrollTo(0, 0)
            invalidate()
        }
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        mEmulator?.onWindowFocusChanged(hasWindowFocus)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnTouchModeChangeListener(textSelectionCursorController)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        currentSession = null
        viewTreeObserver.removeOnTouchModeChangeListener(textSelectionCursorController)
        scope.coroutineContext.cancelChildren()
    }

    fun dispose() {
        currentSession = null
        viewTreeObserver.removeOnTouchModeChangeListener(textSelectionCursorController)
        scope.cancel() 
    }

    init {
        isVerticalScrollBarEnabled = true
    }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).roundToInt().and(-2)
}

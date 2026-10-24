package com.awkoo.libterminal.engine

import com.awkoo.libterminal.engine.protocol.ansi.AnsiEscapeParser
import com.awkoo.libterminal.engine.protocol.ansi.EscapeState
import com.awkoo.libterminal.engine.protocol.ansi.TerminalActionHandler
import com.awkoo.libterminal.engine.protocol.OscHandler
import com.awkoo.libterminal.engine.protocol.InputSequenceEncoder
import com.awkoo.libterminal.engine.protocol.DeviceControlHandler
import com.awkoo.libterminal.engine.buffer.TerminalBuffer
import com.awkoo.libterminal.engine.buffer.CursorCoord
import com.awkoo.libterminal.color.SparsePalette
import com.awkoo.libterminal.color.TerminalColorScheme
import com.awkoo.libterminal.text.TextStyle
import com.awkoo.libterminal.text.Utf8Decoder
import com.awkoo.libterminal.text.WcWidth
import kotlin.concurrent.Volatile
import kotlin.math.max
import kotlin.math.min

internal class TerminalEmulator(
    private val writeString: (data: String) -> Unit,
    private val writeByteArray: (data: ByteArray) -> Unit,
    maxTranscriptRows: Int = 5000
) : TerminalActionHandler {

    private var _cursorRow = 0
    private var _cursorCol = 0
    @JvmField
    var rows = DEFAULT_ROWS
    @JvmField
    var columns = DEFAULT_COLUMNS
    @JvmField
    var cellWidthPixels = DEFAULT_CELL_WIDTH_PIXELS
    @JvmField
    var cellHeightPixels = DEFAULT_CELL_HEIGHT_PIXELS

    private var aboutToAutoWrap = false

    var cursorRow: Int
        get() = _cursorRow
        private set(row) {
            _cursorRow = row.coerceIn(0, rows - 1)
            aboutToAutoWrap = false
        }

    var cursorCol: Int
        get() = _cursorCol
        private set(col) {
            _cursorCol = col.coerceIn(0, columns - 1)
            aboutToAutoWrap = false
        }

    /** 光标列 [col] 的排他右边界：已越过右边距时右边距失效，退回屏宽。 */
    private fun rightEdgeExclusive(col: Int): Int =
        if (col >= rightMargin) columns else rightMargin

    private fun setCursorPosition(x: Int, y: Int) {
        val newRow = (originTop + y).coerceIn(originTop, originBottom - 1)
        val newCol = (originLeft + x).coerceIn(originLeft, originRight - 1)
        setCursorRowCol(newRow, newCol)
    }

    private fun setCursorColRespectingOriginMode(col: Int) =
        setCursorPosition(col, _cursorRow)

    private fun setCursorRowCol(row: Int, col: Int) {
        this.cursorRow = row
        this.cursorCol = col
    }

    private val mainBuffer = TerminalBuffer(columns, maxTranscriptRows, rows)
    private val altBuffer = TerminalBuffer(columns, rows, rows)

    var screen = mainBuffer
        private set

    val isAlternateBufferActive: Boolean get() = this.screen == altBuffer

    private var topMargin = 0
    private var bottomMargin = 0
    private var leftMargin = 0
    private var rightMargin = 0

    private val originTop: Int get() = if (isDecsetInternalBitSet(DECSET_BIT_ORIGIN_MODE)) topMargin else 0
    private val originLeft: Int get() = if (isDecsetInternalBitSet(DECSET_BIT_ORIGIN_MODE)) leftMargin else 0
    private val originBottom: Int get() = if (isDecsetInternalBitSet(DECSET_BIT_ORIGIN_MODE)) bottomMargin else rows
    private val originRight: Int get() = if (isDecsetInternalBitSet(DECSET_BIT_ORIGIN_MODE)) rightMargin else columns

    private val savedStateMain = SavedScreenState()
    private val savedStateAlt = SavedScreenState()

    private var g0IsLineDrawing = false
    private var g1IsLineDrawing = false
    private var isG0ShiftedIn = true

    // DEC 私有模式位状态注册表（当前位 + 已保存位）
    private val decModes = DecModeRegistry()

    // SGR 文字属性状态（前景/背景/特效/下划线）
    private val rendition = RenditionState()

    /** shell 通过 OSC 动态改色的稀疏覆盖板（独立于主题基底，复位只清空本板）。 */
    @JvmField
    val oscColorOverrides: SparsePalette = SparsePalette()

    /** 当前主题基底（深/浅），构造默认深色，外观层可在绑定会话时按需替换为浅色基线。 */
    @JvmField
    var colorScheme: TerminalColorScheme = TerminalColorScheme.dark()

    @JvmField
    var cursorStyle: TerminalCursorStyle = TerminalCursorStyle.BAR

    private fun saveCursor() {
        val state = if (isAlternateBufferActive) savedStateAlt else savedStateMain
        state.savedCursorRow = _cursorRow
        state.savedCursorCol = _cursorCol
        state.savedEffect = rendition.effect
        state.savedForeColor = rendition.foreColor
        state.savedBackColor = rendition.backColor
        state.savedUnderlineStyle = rendition.underlineStyle
        state.savedUnderlineColor = rendition.underlineColor
        state.savedDecFlags = decModes.current
        state.useLineDrawingG0 = g0IsLineDrawing
        state.useLineDrawingG1 = g1IsLineDrawing
        state.useLineDrawingUsesG0 = isG0ShiftedIn
    }

    private fun restoreCursor() {
        val state = if (isAlternateBufferActive) savedStateAlt else savedStateMain
        setCursorRowCol(state.savedCursorRow, state.savedCursorCol)
        rendition.effect = state.savedEffect
        rendition.foreColor = state.savedForeColor
        rendition.backColor = state.savedBackColor
        rendition.underlineStyle = state.savedUnderlineStyle
        rendition.underlineColor = state.savedUnderlineColor
        decModes.restoreAutowrapAndOrigin(state.savedDecFlags)
        g0IsLineDrawing = state.useLineDrawingG0
        g1IsLineDrawing = state.useLineDrawingG1
        isG0ShiftedIn = state.useLineDrawingUsesG0
    }

    private var isInsertMode = false
    private var tabStops = BooleanArray(columns)

    @Volatile @JvmField
    var isCursorBlinkingEnabled = false
    @Volatile @JvmField
    var cursorBlinkState = false

    @Volatile @JvmField
    var isTextBlinkingEnabled = true
    @Volatile @JvmField
    var textBlinkState = false

    private var lastPrintedCodePoint = -1

    // OSC 处理器：拥有标题状态与剪贴板事件流，依赖稀疏覆盖板、主题基底与写回回调
    private val osc = OscHandler(oscColorOverrides, { colorScheme }, writeString)

    // 输入序列编码器：将鼠标/焦点/粘贴事件编码为发往 shell 的转义序列
    private val inputEncoder = InputSequenceEncoder(writeString, writeByteArray)

    // DCS 设备控制串处理器：响应 DA1 查询与终端能力查询
    private val dcsHandler = DeviceControlHandler(writeString)

    private val ansiParser = AnsiEscapeParser(this)

    private val utf8Decoder = Utf8Decoder { ansiParser.processCodePoint(it) }

    init {
        reset()
    }

    fun reset() {
        cursorStyle = TerminalCursorStyle.BAR
        isInsertMode = false
        leftMargin = 0
        topMargin = 0
        bottomMargin = rows
        rightMargin = columns
        aboutToAutoWrap = false
        rendition.reset()
        setDefaultTabStops()
        g1IsLineDrawing = false
        g0IsLineDrawing = false
        isG0ShiftedIn = true
        savedStateMain.resetDefaults()
        savedStateAlt.resetDefaults()
        decModes.resetDefault()
        savedStateMain.savedDecFlags = decModes.current
        savedStateAlt.savedDecFlags = decModes.current
        oscColorOverrides.resetAll()
        utf8Decoder.reset()
        ansiParser.reset()
    }

    fun resize(columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int) {
        this.cellWidthPixels = cellWidthPixels
        this.cellHeightPixels = cellHeightPixels

        val sizeChanged = this@TerminalEmulator.columns != columns || this@TerminalEmulator.rows != rows

        if (sizeChanged) {
            if (this@TerminalEmulator.rows != rows) {
                this@TerminalEmulator.rows = rows
                topMargin = 0
                bottomMargin = this@TerminalEmulator.rows
            }
            if (this@TerminalEmulator.columns != columns) {
                val oldColumns = this@TerminalEmulator.columns
                val oldTabStop = tabStops
                this@TerminalEmulator.columns = columns
                tabStops = BooleanArray(this@TerminalEmulator.columns)
                setDefaultTabStops()
                oldTabStop.copyInto(tabStops, endIndex = min(oldColumns, columns))
                leftMargin = 0
                rightMargin = this@TerminalEmulator.columns
            }
        }

        if (sizeChanged || screen.columns != this@TerminalEmulator.columns || screen.screenRows != this@TerminalEmulator.rows) {
            val newTotalRows = if (isAlternateBufferActive) this@TerminalEmulator.rows else mainBuffer.totalRows
            val newCursor = screen.resize(
                this@TerminalEmulator.columns,
                this@TerminalEmulator.rows,
                newTotalRows,
                CursorCoord.pack(_cursorCol, _cursorRow), // 传入打包的 Long
                // 重排生成的空白行使用默认样式，避免把瞬态 SGR 特效/背景泄漏到新空行
                TextStyle.NORMAL,
                this.isAlternateBufferActive
            )
            this.cursorCol = newCursor.col
            this.cursorRow = newCursor.row
        }
    }

    private fun setDefaultTabStops() {
        for (i in 0 until columns) tabStops[i] = (i and 7) == 0 && i != 0
    }

    private fun isDecsetInternalBitSet(bit: Int) = decModes.isSet(bit)
    private fun setDecsetInternalBit(internalBit: Int, set: Boolean) = decModes.set(internalBit, set)

    /** 窗口标题状态（门面转发，实际状态由 [osc] 持有）。 */
    val titleState get() = osc.titleState

    /** OSC 52 剪贴板写入事件流（门面转发）。 */
    val copiedText get() = osc.copiedText

    val isTextVisible: Boolean
        get() = !isTextBlinkingEnabled || textBlinkState

    /** 自上次取走以来的滚动行数增量，仅通过 [takeScrollCounter] 消费。 */
    private var scrollCounter: Int = 0
    var isAutoScrollDisabled: Boolean = false
        private set

    // 输入冻结状态：为真时 append 的字节只进缓存队列而不解码，
    // 用于文本选择期间冻结屏幕，解除后按序重放。全部字段仅在 synchronized(emulator) 内访问。
    private var isPaused: Boolean = false
    private val pausedQueue = ArrayDeque<ByteArray>()

    val isReverseVideo: Boolean get() = isDecsetInternalBitSet(DECSET_BIT_REVERSE_VIDEO)
    val isCursorEnabled: Boolean get() = isDecsetInternalBitSet(DECSET_BIT_CURSOR_ENABLED)
    val isCursorVisible: Boolean
        get() = isCursorEnabled && (!isCursorBlinkingEnabled || cursorBlinkState)
    val isKeypadApplicationMode: Boolean
        get() = isDecsetInternalBitSet(
            DECSET_BIT_APPLICATION_KEYPAD
        )
    val isCursorKeysApplicationMode: Boolean
        get() = isDecsetInternalBitSet(
            DECSET_BIT_APPLICATION_CURSOR_KEYS
        )
    val isMouseTrackingActive: Boolean
        get() = isDecsetInternalBitSet(DECSET_BIT_MOUSE_TRACKING_PRESS_RELEASE) ||
                isDecsetInternalBitSet(DECSET_BIT_MOUSE_TRACKING_BUTTON_EVENT) ||
                isDecsetInternalBitSet(DECSET_BIT_MOUSE_TRACKING_ANY_EVENT)

    /** 仅 1002 按钮事件模式，用于判定按住按钮时才上报拖拽移动。 */
    val isMouseButtonEventTrackingActive: Boolean
        get() = isDecsetInternalBitSet(DECSET_BIT_MOUSE_TRACKING_BUTTON_EVENT)

    /** 1003 任意事件模式，用于无条件上报移动（含未按住按钮的悬停）。 */
    val isMouseAnyEventTrackingActive: Boolean
        get() = isDecsetInternalBitSet(DECSET_BIT_MOUSE_TRACKING_ANY_EVENT)

    fun append(buffer: ByteArray, length: Int) {
        if (isPaused) {
            // 文本选择期间冻结屏幕：仅缓存字节，不进入解码，整屏保持不变
            pausedQueue.add(buffer.copyOf(length))
            return
        }
        utf8Decoder.decode(buffer, length)
    }

    /**
     * 开关输入冻结。
     *
     * paused 为真时后续 [append] 的原始字节被缓存而不解码（屏幕完全静止）；
     * 为假时按序重放缓存并恢复实时。需在 synchronized(emulator) 内调用。
     */
    fun setInputPaused(paused: Boolean) {
        if (paused == isPaused) return
        isPaused = paused
        if (!paused) {
            while (pausedQueue.isNotEmpty()) {
                val bytes = pausedQueue.removeFirst()
                utf8Decoder.decode(bytes, bytes.size)
            }
        }
    }

    override fun onCodePoint(codePoint: Int) {
        emitCodePoint(codePoint)
    }

    override fun onBell() {
        // 默认忽略，可被通知拦截
    }

    override fun onBackspace() {
        if (_cursorCol > leftMargin) {
            this.cursorCol = _cursorCol - 1
        } else if (isDecsetInternalBitSet(DECSET_BIT_REVERSE_WRAPAROUND)
            && isDecsetInternalBitSet(DECSET_BIT_AUTOWRAP)
        ) {
            val previousRow = _cursorRow - 1
            if (previousRow >= topMargin && screen.getLineWrap(previousRow)) {
                setCursorRowCol(previousRow, rightMargin - 1)
            }
        }
    }

    override fun onHorizontalTab() {
        this.cursorCol = nextTabStop(1)
    }

    override fun onLinefeed() {
        doLinefeed()
    }

    override fun onCarriageReturn() {
        this.cursorCol = leftMargin
    }

    override fun onShiftIn() {
        isG0ShiftedIn = true
    }

    override fun onShiftOut() {
        isG0ShiftedIn = false
    }

    override fun onEscCommand(state: EscapeState, command: Int) {
        when (state) {
            EscapeState.ESC -> handleEscStandard(command)
            EscapeState.POUND -> {
                if (command == '8'.code) screen.blockSet(0, 0, columns, rows, 'E'.code, rendition.style)
            }

            EscapeState.SELECT_LEFT_PAREN -> g0IsLineDrawing = (command == '0'.code)
            EscapeState.SELECT_RIGHT_PAREN -> g1IsLineDrawing = (command == '0'.code)
            EscapeState.PERCENT -> {} // 字符集选择，当前忽略
            else -> {}
        }
    }

    private fun handleEscStandard(b: Int) {
        when (b.toChar()) {
            '6' -> if (_cursorCol > leftMargin) {
                this.cursorCol = _cursorCol - 1
            } else {
                val rows = bottomMargin - topMargin
                screen.insertColumns(leftMargin, topMargin, 1, rows, rightMargin, rendition.styleWithoutEffect)
            }

            '7' -> saveCursor()
            '8' -> restoreCursor()
            '9' -> if (_cursorCol < rightMargin - 1) {
                this.cursorCol = _cursorCol + 1
            } else {
                val rows = bottomMargin - topMargin
                screen.deleteColumns(leftMargin, topMargin, 1, rows, rightMargin, rendition.styleWithoutEffect)
            }

            'c' -> {
                reset()
                mainBuffer.clearTranscript()
                blockClear(0, 0, columns, rows)
                setCursorPosition(0, 0)
            }

            'D' -> doLinefeed()
            'E' -> {
                this.cursorCol = originLeft
                doLinefeed()
            }

            'F' -> {} // 仅 xterm 的 hpLowerleftBugCompat 资源（默认 false）开启时才移动光标，默认与其余桌面终端一致忽略
            'H' -> tabStops[_cursorCol] = true
            'M' -> if (_cursorRow <= topMargin) {
                screen.shiftLines(
                    topMargin,
                    bottomMargin,
                    1,
                    leftMargin,
                    rightMargin,
                    up = false,
                    style = rendition.eraseFillStyle
                )
            } else {
                _cursorRow--
            }

            'N', '0' -> {} // 忽略
            '=' -> setDecsetInternalBit(DECSET_BIT_APPLICATION_KEYPAD, true)
            '>' -> setDecsetInternalBit(DECSET_BIT_APPLICATION_KEYPAD, false)
            else -> {}
        }
    }

    override fun onCsiCommand(
        state: EscapeState,
        command: Int,
        args: IntArray,
        argCount: Int,
        subParams: Int
    ) {
        when (state) {
            EscapeState.CSI -> handleCsiStandard(command, args, argCount, subParams)
            EscapeState.CSI_QUESTIONMARK -> handleCsiQuestionMark(command, args, argCount)
            EscapeState.CSI_GREATER_THAN -> handleCsiGreaterThan(command)
            EscapeState.CSI_DOLLAR -> handleCsiDollar(command, args, argCount)
            EscapeState.CSI_DOUBLE_QUOTE -> handleCsiDoubleQuote(command, args)
            EscapeState.CSI_SINGLE_QUOTE -> handleCsiSingleQuote(command, args)
            EscapeState.CSI_QUESTIONMARK_ARG_DOLLAR -> handleCsiQuestionMarkArgDollar(command, args)
            EscapeState.CSI_INTERMEDIATE_SPACE -> handleCsiArgsSpace(command, args)
            EscapeState.CSI_INTERMEDIATE_ASTERISK -> handleCsiArgsAsterix(command, args)
            EscapeState.CSI_EXCLAMATION -> onSoftReset()
            else -> {}
        }
    }

    /**
     * 处理标准 CSI（控制序列介绍）命令。
     *
     * 包括光标移动、行擦除、列操作、字符插入/删除、屏幕擦除、
     * Tab 操作、上下滚动、DEC 私有模式设置/重置、模式切换等。
     */
    private fun handleCsiStandard(b: Int, args: IntArray, argCount: Int, subParams: Int) {
        when (b.toChar()) {
            'A' -> {
                this.cursorRow =
                    max(originTop, _cursorRow - AnsiEscapeParser.getArg(args, 0, 1, true))
            }

            'B' -> this.cursorRow =
                min(originBottom - 1, _cursorRow + AnsiEscapeParser.getArg(args, 0, 1, true))

            'C', 'a' -> this.cursorCol =
                min(rightEdgeExclusive(_cursorCol) - 1, _cursorCol + AnsiEscapeParser.getArg(args, 0, 1, true))

            'D' -> this.cursorCol =
                max(leftMargin, _cursorCol - AnsiEscapeParser.getArg(args, 0, 1, true))

            'E' -> {
                setCursorPosition(
                    0,
                    _cursorRow - originTop + AnsiEscapeParser.getArg(args, 0, 1, true)
                )
            }

            'F' -> {
                setCursorPosition(
                    0,
                    _cursorRow - originTop - AnsiEscapeParser.getArg(args, 0, 1, true)
                )
            }

            'G' -> this.cursorCol =
                AnsiEscapeParser.getArg(args, 0, 1, true).coerceIn(1, columns) - 1

            'H', 'f' -> setCursorPosition(
                AnsiEscapeParser.getArg(args, 1, 1, true) - 1,
                AnsiEscapeParser.getArg(args, 0, 1, true) - 1
            )

            'I' -> this.cursorCol = nextTabStop(AnsiEscapeParser.getArg(args, 0, 1, true))
            'J' -> handleCsiJ(args)
            'K' -> handleCsiK(args)
            'L' -> handleCsiL(args)
            'M' -> handleCsiM(args)
            'P' -> handleCsiP(args)
            'S' -> {
                val linesToScrollArg = AnsiEscapeParser.getArg(args, 0, 1, true)
                val linesBetween = bottomMargin - topMargin
                val linesToScroll = min(linesBetween, linesToScrollArg)
                for (i in 0 until linesToScroll) scrollDownOneLine()
            }

            'T' -> {
                val linesToScrollArg = AnsiEscapeParser.getArg(args, 0, 1, true)
                val linesBetween = bottomMargin - topMargin
                val linesToScroll = min(linesBetween, linesToScrollArg)
                screen.shiftLines(
                    topMargin,
                    bottomMargin,
                    linesToScroll,
                    leftMargin,
                    rightMargin,
                    up = false,
                    style = rendition.eraseFillStyle
                )
            }

            'X' -> {
                aboutToAutoWrap = false
                screen.blockSet(
                    _cursorCol,
                    _cursorRow,
                    min(AnsiEscapeParser.getArg(args, 0, 1, true), columns - _cursorCol),
                    1,
                    ' '.code,
                    rendition.eraseFillStyle
                )
            }

            'Z' -> {
                var numberOfTabs = AnsiEscapeParser.getArg(args, 0, 1, true)
                var newCol = _cursorCol
                for (i in (_cursorCol - 1) downTo 0) {
                    if (tabStops[i]) {
                        if (--numberOfTabs == 0) {
                            newCol = max(i, leftMargin)
                            break
                        }
                    }
                }
                this.cursorCol = newCol
            }

            '`' -> setCursorColRespectingOriginMode(AnsiEscapeParser.getArg(args, 0, 1, true) - 1)
            'b' -> {
                if (lastPrintedCodePoint != -1) {
                    val numRepeat = AnsiEscapeParser.getArg(args, 0, 1, true)
                    for (i in 0 until numRepeat) emitCodePoint(lastPrintedCodePoint)
                }
            }

            'c' -> if (AnsiEscapeParser.getArg(
                    args,
                    0,
                    0,
                    true
                ) == 0
            ) writeString("\u001b[?64;1;2;6;9;15;18;21;22c")

            'd' -> this.cursorRow = AnsiEscapeParser.getArg(args, 0, 1, true).coerceIn(1, rows) - 1
            'e' -> {
                setCursorPosition(
                    _cursorCol,
                    _cursorRow - originTop + AnsiEscapeParser.getArg(args, 0, 1, true)
                )
            }

            'g' -> when (AnsiEscapeParser.getArg(args, 0, 0, true)) {
                0 -> tabStops[_cursorCol] = false
                3 -> tabStops.fill(false)
            }

            'h' -> doSetMode(true, AnsiEscapeParser.getArg(args, 0, 0, true))
            'l' -> doSetMode(false, AnsiEscapeParser.getArg(args, 0, 0, true))
            'm' -> rendition.selectGraphicRendition(args, argCount, subParams)
            'n' -> when (AnsiEscapeParser.getArg(args, 0, 0, true)) {
                5 -> writeByteArray(DSR_STATUS_RESPONSE)

                6 -> writeString("\u001b[${_cursorRow + 1};${_cursorCol + 1}R")
            }

            'r' -> {
                topMargin = (AnsiEscapeParser.getArg(args, 0, 1, true) - 1).coerceIn(0, rows - 2)
                bottomMargin = AnsiEscapeParser.getArg(args, 1, rows, true).coerceIn(topMargin + 1, rows)
                setCursorPosition(0, 0)
            }

            's' -> if (isDecsetInternalBitSet(DECSET_BIT_LEFTRIGHT_MARGIN_MODE)) {
                val pl = AnsiEscapeParser.getArg(args, 0, 1, true)
                val pr = AnsiEscapeParser.getArg(args, 1, columns, true)
                val leftMax = (columns - 2).coerceAtLeast(0)
                val lmCandidate = (pl - 1).coerceIn(0, leftMax)
                var rmCandidate = pr.coerceIn(lmCandidate + 1, columns)
                if (rmCandidate <= lmCandidate) {
                    rmCandidate = (lmCandidate + 1).coerceAtMost(columns)
                }
                leftMargin = lmCandidate
                rightMargin = rmCandidate
                setCursorPosition(0, 0)
            } else saveCursor()

            't' -> handleCsiT(args)
            'u' -> restoreCursor()
            '@' -> {
                aboutToAutoWrap = false
                val columnsAfterCursor = columns - _cursorCol
                val spacesToInsert =
                    min(AnsiEscapeParser.getArg(args, 0, 1, true), columnsAfterCursor)
                screen.insertColumns(_cursorCol, _cursorRow, spacesToInsert, 1, columns, rendition.eraseFillStyle)
            }
        }
    }

    /**
     * ED 擦除显示。擦除边界为整个可见屏幕,不受滚动区域与左右边距约束;
     * ED0 光标起至屏末,ED1 屏首至光标(含光标行),ED2 全屏,ED3 清回滚区。
     */
    private fun handleCsiJ(args: IntArray) {
        aboutToAutoWrap = false
        when (AnsiEscapeParser.getArg(args, 0, 0, true)) {
            0 -> {
                blockClear(_cursorCol, _cursorRow, columns - _cursorCol)
                blockClear(
                    0,
                    _cursorRow + 1,
                    columns,
                    rows - (_cursorRow + 1)
                )
            }

            1 -> {
                blockClear(0, 0, columns, _cursorRow)
                blockClear(0, _cursorRow, _cursorCol + 1)
            }

            2 -> blockClear(0, 0, columns, rows)
            3 -> mainBuffer.clearTranscript()
        }
    }

    private fun handleCsiK(args: IntArray) {
        aboutToAutoWrap = false
        when (AnsiEscapeParser.getArg(args, 0, 0, true)) {
            0 -> blockClear(_cursorCol, _cursorRow, columns - _cursorCol)
            1 -> blockClear(0, _cursorRow, _cursorCol + 1)
            2 -> blockClear(0, _cursorRow, columns)
        }
    }

    private fun handleCsiL(args: IntArray) {
        val linesAfterCursor = (bottomMargin - _cursorRow).coerceAtLeast(0)
        val linesToInsert = min(AnsiEscapeParser.getArg(args, 0, 1, true), linesAfterCursor)
        screen.insertLines(_cursorRow, linesToInsert, bottomMargin, rendition.eraseFillStyle)
    }

    private fun handleCsiM(args: IntArray) {
        val linesAfterCursor = (bottomMargin - _cursorRow).coerceAtLeast(0)
        val linesToDelete = min(AnsiEscapeParser.getArg(args, 0, 1, true), linesAfterCursor)
        screen.deleteLines(_cursorRow, linesToDelete, bottomMargin, rendition.eraseFillStyle)
    }

    private fun handleCsiP(args: IntArray) {
        aboutToAutoWrap = false
        val cellsAfterCursor = columns - _cursorCol
        val cellsToDelete = min(AnsiEscapeParser.getArg(args, 0, 1, true), cellsAfterCursor)
        screen.deleteColumns(_cursorCol, _cursorRow, cellsToDelete, 1, columns, rendition.eraseFillStyle)
    }

    private fun handleCsiT(args: IntArray) {
        when (AnsiEscapeParser.getArg(args, 0, 0, true)) {
            11 -> writeString("\u001b[1t")
            13 -> writeString("\u001b[3;0;0t")
            14 -> writeString("\u001b[4;${rows * cellHeightPixels};${columns * cellWidthPixels}t")
            16 -> writeString("\u001b[6;${rows * cellHeightPixels};${columns * cellWidthPixels}t")
            18, 19 -> writeString("\u001b[9;${rows};${columns}t") // 报告精确字符尺寸
            20 -> writeString("\u001b]LIconLabel\u001b\\")
            21 -> writeString("\u001b]l\u001b\\")
            22 -> osc.pushTitle()
            23 -> osc.popTitle()
        }
    }

    /**
     * 处理 DEC 私有 CSI 命令（? 前缀）。
     *
     * 包括 DECSET/DECRST（设置/重置终端特性）：
     * 1250/1252 编码、应用光标键、应用小键盘、备用屏幕缓冲区、
     * 光标可见、行缓冲区、自动换行、光标样式等。
     */
    private fun handleCsiQuestionMark(b: Int, args: IntArray, argCount: Int) {
        when (b.toChar()) {
            'J', 'K' -> {
                aboutToAutoWrap = false
                val fillChar = ' '.code
                var startCol = -1
                var startRow = -1
                var endCol = -1
                var endRow = -1
                val justRow = (b == 'K'.code)
                when (AnsiEscapeParser.getArg(args, 0, 0, true)) {
                    0 -> {
                        startCol = _cursorCol
                        startRow = _cursorRow
                        endCol = columns
                        endRow = if (justRow) (_cursorRow + 1) else rows
                    }

                    1 -> {
                        startCol = 0
                        startRow = if (justRow) _cursorRow else 0
                        endCol = _cursorCol + 1
                        endRow = _cursorRow + 1
                    }

                    2 -> {
                        startCol = 0
                        startRow = if (justRow) _cursorRow else 0
                        endCol = columns
                        endRow = if (justRow) (_cursorRow + 1) else rows
                    }
                }
                val style = rendition.eraseFillStyle
                for (row in startRow until endRow) {
                    for (col in startCol until endCol) {
                        if (!screen.getStyleAt(row, col).isProtected) screen.setChar(
                            col,
                            row,
                            fillChar,
                            style
                        )
                    }
                }
            }

            'h', 'l' -> {
                for (i in 0 until argCount) doDecSetOrReset(b == 'h'.code, args[i])
            }

            'n' -> if (AnsiEscapeParser.getArg(
                    args,
                    0,
                    -1,
                    true
                ) == 6
            ) writeString("\u001b[?${_cursorRow + 1};${_cursorCol + 1};1R")

            'r', 's' -> {
                for (i in 0 until argCount) {
                    val externalBit = args[i]
                    val internalBit = decModes.mapExternalToInternal(externalBit)
                    if (internalBit != -1) {
                        if (b == 's'.code) decModes.saveModeBit(internalBit)
                        else doDecSetOrReset(decModes.restoreModeBit(internalBit), externalBit)
                    }
                }
            }
        }
    }

    private fun handleCsiGreaterThan(b: Int) {
        if (b == 'c'.code) writeString("\u001b[>41;320;0c")
    }

    private fun handleCsiDollar(b: Int, args: IntArray, argCount: Int) {
        when (b.toChar()) {
            'v' -> {
                val topSource = (AnsiEscapeParser.getArg(args, 0, 1, true) - 1 + originTop).coerceAtMost(rows)
                val leftSource = (AnsiEscapeParser.getArg(args, 1, 1, true) - 1 + originLeft).coerceAtMost(columns)
                val bottomSource = (AnsiEscapeParser.getArg(args, 2, rows, true) + originTop).coerceIn(topSource, rows)
                val rightSource = (AnsiEscapeParser.getArg(args, 3, columns, true) + originLeft).coerceIn(leftSource, columns)
                val destionationTop =
                    min(AnsiEscapeParser.getArg(args, 5, 1, true) - 1 + originTop, rows)
                val destinationLeft =
                    min(AnsiEscapeParser.getArg(args, 6, 1, true) - 1 + originLeft, columns)
                val heightToCopy = min(rows - destionationTop, bottomSource - topSource)
                val widthToCopy = min(columns - destinationLeft, rightSource - leftSource)
                screen.blockCopy(
                    leftSource,
                    topSource,
                    widthToCopy,
                    heightToCopy,
                    destinationLeft,
                    destionationTop
                )
            }

            '{', 'x', 'z' -> handleCsiDollarErase(b, args)
            'r', 't' -> handleCsiDollarRect(b, args, argCount)
        }
    }

    private fun handleCsiDollarErase(b: Int, args: IntArray) {
        val erase = b != 'x'.code
        val selective = b == '{'.code
        val keepVisualAttributes = erase && selective
        var argIdx = 0
        val fillChar = if (erase) ' '.code else AnsiEscapeParser.getArg(args, argIdx++, -1, true)
        when (fillChar) {
            in 32..126, in 160..255 -> {
                val top = (AnsiEscapeParser.getArg(args, argIdx++, 1, true) + originTop).coerceAtMost(originBottom + 1)
                val left = (AnsiEscapeParser.getArg(args, argIdx++, 1, true) + originLeft).coerceAtMost(originRight + 1)
                val bottom = (AnsiEscapeParser.getArg(args, argIdx++, rows, true) + originTop).coerceAtMost(originBottom)
                val right = (AnsiEscapeParser.getArg(args, argIdx, columns, true) + originLeft).coerceAtMost(originRight)
                val style = rendition.style
                for (row in (top - 1) until bottom) {
                    for (col in (left - 1) until right) {
                        if (!selective || !screen.getStyleAt(row, col).isProtected) {
                            val applyStyle =
                                if (keepVisualAttributes) screen.getStyleAt(row, col)
                                else if (erase) rendition.eraseFillStyle
                                else style
                            val applyExt =
                            if (keepVisualAttributes) screen.getExtendedEffectAt(row, col)
                            else if (erase) 0L
                            else rendition.currentExtendedEffect

                            screen.setChar(col, row, fillChar, applyStyle, applyExt)
                        }
                    }
                }
            }
        }
    }

    private fun handleCsiDollarRect(b: Int, args: IntArray, argCount: Int) {
        val reverse = b == 't'.code
        val topArg = AnsiEscapeParser.getArg(args, 0, 1, true)
        val leftArg = AnsiEscapeParser.getArg(args, 1, 1, true)
        val bottomArg = AnsiEscapeParser.getArg(args, 2, rows, true)
        val rightArg = AnsiEscapeParser.getArg(args, 3, columns, true)
        val top = (min(topArg, bottomArg) - 1 + originTop).coerceIn(originTop, originBottom - 1)
        val left = (min(leftArg, rightArg) - 1 + originLeft).coerceIn(originLeft, originRight - 1)
        val bottom = (max(topArg, bottomArg) + originTop).coerceIn(originTop, originBottom)
        val right = (max(leftArg, rightArg) + originLeft).coerceIn(originLeft, originRight)
        for (i in 4 until argCount) {
            val argCode = AnsiEscapeParser.getArg(args, i, 0, false)
            val bits = when (argCode) {
                0 -> COMBINED_ATTRS
                1, 22 -> TextStyle.CHARACTER_ATTRIBUTE_BOLD
                4, 24 -> TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE
                5, 25 -> TextStyle.CHARACTER_ATTRIBUTE_BLINK
                7, 27 -> TextStyle.CHARACTER_ATTRIBUTE_INVERSE
                else -> 0
            }
            val setOrClear = when (argCode) {
                0 -> reverse
                1, 4, 5, 7 -> true
                22, 24, 25, 27 -> false
                else -> true
            }
            if (!(reverse && !setOrClear)) {
                screen.setOrClearEffect(
                    bits,
                    setOrClear,
                    reverse,
                    isDecsetInternalBitSet(DECSET_BIT_RECTANGULAR_CHANGE_ATTRIBUTE),
                    originLeft,
                    originRight,
                    top,
                    left,
                    bottom,
                    right
                )
            }
        }
    }

    private fun handleCsiDoubleQuote(b: Int, args: IntArray) {
        if (b == 'q'.code) {
            when (AnsiEscapeParser.getArg(args, 0, 0, true)) {
                0, 2 -> rendition.setProtected(false)
                1 -> rendition.setProtected(true)
            }
        }
    }

    private fun handleCsiSingleQuote(b: Int, args: IntArray) {
        val columnsAfterCursor = (rightMargin - _cursorCol).coerceAtLeast(0)
        val columnsToChange = min(AnsiEscapeParser.getArg(args, 0, 1, true), columnsAfterCursor)
        val columnsToMove = columnsAfterCursor - columnsToChange
        when (b) {
            '}'.code -> {
                screen.blockCopy(
                    _cursorCol,
                    0,
                    columnsToMove,
                    rows,
                    _cursorCol + columnsToChange,
                    0
                )
                blockClear(_cursorCol, 0, columnsToChange, rows)
            }

            '~'.code -> {
                screen.blockCopy(
                    _cursorCol + columnsToChange,
                    0,
                    columnsToMove,
                    rows,
                    _cursorCol,
                    0
                )
                blockClear(_cursorCol + columnsToMove, 0, columnsToChange, rows)
            }
        }
    }

    private fun handleCsiQuestionMarkArgDollar(b: Int, args: IntArray) {
        if (b == 'p'.code) {
            val mode = AnsiEscapeParser.getArg(args, 0, 0, true)
            val value = when (mode) {
                47, 1047, 1049 -> if (isAlternateBufferActive) 1 else 2
                else -> {
                    val internalBit = decModes.mapExternalToInternal(mode)
                    if (internalBit != -1) (if (isDecsetInternalBitSet(internalBit)) 1 else 2) else 0
                }
            }
            writeString("\u001b[?$mode;$value\$y")
        }
    }

    private fun handleCsiArgsSpace(b: Int, args: IntArray) {
        if (b == 'q'.code) {
            when (AnsiEscapeParser.getArg(args, 0, 0, true)) {
                0, 1, 2 -> this.cursorStyle = TerminalCursorStyle.BLOCK
                3, 4 -> this.cursorStyle = TerminalCursorStyle.UNDERLINE
                5, 6 -> this.cursorStyle = TerminalCursorStyle.BAR
            }
        }
    }

    private fun handleCsiArgsAsterix(b: Int, args: IntArray) {
        if (b == 'x'.code) {
            val attrChangeExtent = AnsiEscapeParser.getArg(args, 0, 0, true)
            if (attrChangeExtent in 0..2) {
                setDecsetInternalBit(DECSET_BIT_RECTANGULAR_CHANGE_ATTRIBUTE, attrChangeExtent == 2)
            }
        }
    }

    override fun onOscCommand(value: Int, textParameter: String, bellOrStringTerminator: String) {
        osc.onOscCommand(value, textParameter, bellOrStringTerminator)
    }

    /**
     * 处理 DCS 设备控制字符串，委托给 [dcsHandler]。
     */
    override fun onDeviceControl(dcs: String) {
        dcsHandler.handleDeviceControl(
            dcs,
            appCursorKeys = isDecsetInternalBitSet(DECSET_BIT_APPLICATION_CURSOR_KEYS),
            appKeypad = isDecsetInternalBitSet(DECSET_BIT_APPLICATION_KEYPAD)
        )
    }

    override fun onSoftReset() {
        reset()
    }

    /**
     * 上报鼠标事件。
     *
     * [column]/[row] 为视口 0-based 单元格坐标，合法性由编码器按协议的 1-based 夹取。
     */
    fun sendMouseEvent(mouseButton: Int, column: Int, row: Int, pressed: Boolean) {
        inputEncoder.encodeMouseEvent(
            button = mouseButton,
            column = column,
            row = row,
            pressed = pressed,
            buttonEventTracking = isDecsetInternalBitSet(DECSET_BIT_MOUSE_TRACKING_BUTTON_EVENT),
            anyEventTracking = isDecsetInternalBitSet(DECSET_BIT_MOUSE_TRACKING_ANY_EVENT),
            sgrProtocol = isDecsetInternalBitSet(DECSET_BIT_MOUSE_PROTOCOL_SGR),
            columns = columns,
            rows = rows
        )
    }

    /**
     * 将字符输出到终端屏幕。
     *
     * 处理流程：
     * 1. 线绘字符集映射（G0/G1 → Unicode 盒绘符号）
     * 2. 自动换行判定（行尾 + 宽字符或预备换行 → 换行 + 滚动）
     * 3. 插入模式（右移后续内容）
     * 4. 组合字符处理（零宽字符写入前一列位置）
     * 5. 更新光标位置和预备换行标志
     */
    private fun emitCodePoint(codePoint: Int) {
        lastPrintedCodePoint = codePoint
        var cp = codePoint
        if (if (isG0ShiftedIn) g0IsLineDrawing else g1IsLineDrawing) {
            cp = mapSpecialCharacters(cp)
        }

        // 自动换行：行尾 + (预备换行 + 普通字符) 或 宽字符 → 换行
        val autoWrap = isDecsetInternalBitSet(DECSET_BIT_AUTOWRAP)
        val displayWidth = WcWidth.width(cp)
        val cursorInLastColumn = _cursorCol == rightEdgeExclusive(_cursorCol) - 1

        if (autoWrap) {
            if (cursorInLastColumn && ((aboutToAutoWrap && displayWidth == 1) || displayWidth == 2)) {
                screen.setLineWrap(_cursorRow)
                this.cursorCol = leftMargin
                if (_cursorRow + 1 < bottomMargin) this.cursorRow++ else scrollDownOneLine()
            }
        } else if (cursorInLastColumn && displayWidth == 2) {
            return
        }

        if (isInsertMode && displayWidth > 0) {
            val destCol = _cursorCol + displayWidth
            if (destCol < rightMargin) screen.blockCopy(
                _cursorCol,
                _cursorRow,
                rightMargin - destCol,
                1,
                destCol,
                _cursorRow
            )
        }

        val offsetDueToCombiningChar =
            (if (displayWidth <= 0 && _cursorCol > 0 && !aboutToAutoWrap) 1 else 0)
        screen.setChar(
            _cursorCol - offsetDueToCombiningChar,
            _cursorRow,
            cp,
            rendition.style,
            rendition.currentExtendedEffect
        )

        val previousCol = _cursorCol
        val wasAboutToAutoWrap = aboutToAutoWrap
        // cursorCol setter 是 mAboutToAutoWrap 唯一清零属主：必须先经 setter 写列、再在此重建标志。
        // 顺序不可调换，否则换行标志被写入即清，自动换行失效（Ctrl+R 光标倒退覆盖的根因）。
        this.cursorCol = min(previousCol + displayWidth, rightEdgeExclusive(previousCol) - 1)
        aboutToAutoWrap = when {
            displayWidth <= 0 -> wasAboutToAutoWrap
            autoWrap -> previousCol == rightEdgeExclusive(previousCol) - displayWidth
            else -> false
        }
    }

    private fun doLinefeed() {
        aboutToAutoWrap = false
        if (_cursorRow >= bottomMargin) {
            if (_cursorRow != rows - 1) this.cursorRow = _cursorRow + 1
        } else {
            if (_cursorRow + 1 == bottomMargin) scrollDownOneLine() else this.cursorRow =
                _cursorRow + 1
        }
    }

    private fun scrollDownOneLine() {
        this.scrollCounter++
        screen.scrollRegionOneLine(
            topMargin,
            bottomMargin,
            leftMargin,
            rightMargin,
            rendition.eraseFillStyle
        )
    }

    private fun doDecSetOrReset(setting: Boolean, externalBit: Int) {
        val internalBit = decModes.mapExternalToInternal(externalBit)
        if (internalBit != -1) setDecsetInternalBit(internalBit, setting)
        when (externalBit) {
            3 -> {
                topMargin = 0
                leftMargin = 0
                bottomMargin = rows
                rightMargin = columns
                setDecsetInternalBit(DECSET_BIT_LEFTRIGHT_MARGIN_MODE, false)
                blockClear(0, 0, columns, rows)
                setCursorRowCol(0, 0)
            }

            6 -> if (setting) setCursorPosition(0, 0)
            69 -> if (!setting) {
                leftMargin = 0
                rightMargin = columns
            }

            1048 -> if (setting) saveCursor() else restoreCursor()
            47, 1047, 1049 -> {
                val newScreen = if (setting) altBuffer else mainBuffer
                if (newScreen != this.screen) {
                    val resized =
                        !(newScreen.columns == columns && newScreen.screenRows == rows)
                    if (setting) saveCursor()
                    this.screen = newScreen
                    if (!setting) {
                        val col = savedStateMain.savedCursorCol
                        val row = savedStateMain.savedCursorRow
                        restoreCursor()
                        if (resized) {
                            this.cursorCol = col
                            this.cursorRow = row
                        }
                    }
                    if (resized) resize(columns, rows, cellWidthPixels, cellHeightPixels)
                    if (externalBit == 1049 && newScreen == altBuffer) newScreen.blockSet(
                        0,
                        0,
                        columns,
                        rows,
                        ' '.code,
                        rendition.eraseFillStyle
                    )
                }
            }
        }
    }

    private fun doSetMode(newValue: Boolean, modeBit: Int) {
        if (modeBit == 4) isInsertMode = newValue
    }

    private fun nextTabStop(numTabs: Int): Int {
        var n = numTabs
        for (i in _cursorCol + 1 until columns) if (tabStops[i] && --n == 0) return min(i, rightMargin - 1)
        return rightMargin - 1
    }

    private fun blockClear(sx: Int, sy: Int, w: Int, h: Int = 1) =
        screen.blockSet(sx, sy, w, h, ' '.code, rendition.eraseFillStyle)

    /** 取走并清零滚动增量；调用方须持有 synchronized(this)。 */
    internal fun takeScrollCounter(): Int {
        val delta = scrollCounter
        scrollCounter = 0
        return delta
    }

    fun toggleAutoScrollDisabled() {
        this.isAutoScrollDisabled = !this.isAutoScrollDisabled
    }

    fun onWindowFocusChanged(hasFocus: Boolean) {
        inputEncoder.encodeFocusEvent(
            hasFocus,
            isDecsetInternalBitSet(DECSET_BIT_SEND_FOCUS_EVENTS)
        )
    }

    fun getSelectedText(x1: Int, y1: Int, x2: Int, y2: Int): String =
        screen.getSelectedText(x1, y1, x2, y2)

    fun paste(text: String) {
        inputEncoder.encodePastedText(text, isDecsetInternalBitSet(DECSET_BIT_BRACKETED_PASTE_MODE))
    }

    private class SavedScreenState {
        @JvmField
        var savedCursorRow: Int = 0
        @JvmField
        var savedCursorCol: Int = 0
        @JvmField
        var savedEffect: Int = 0
        @JvmField
        var savedForeColor: Int = 0
        @JvmField
        var savedBackColor: Int = 0
        @JvmField
        var savedDecFlags: Int = 0
        @JvmField
        var savedUnderlineStyle: Int = TextStyle.UNDERLINE_STYLE_NONE
        @JvmField
        var savedUnderlineColor: Int = TextStyle.COLOR_INDEX_FOREGROUND
        @JvmField
        var useLineDrawingG0: Boolean = false
        @JvmField
        var useLineDrawingG1: Boolean = false
        @JvmField
        var useLineDrawingUsesG0: Boolean = true

        fun resetDefaults() {
            savedCursorRow = 0
            savedCursorCol = 0
            savedEffect = 0
            savedForeColor = TextStyle.COLOR_INDEX_FOREGROUND
            savedBackColor = TextStyle.COLOR_INDEX_BACKGROUND
            savedUnderlineStyle = TextStyle.UNDERLINE_STYLE_NONE
            savedUnderlineColor = TextStyle.COLOR_INDEX_FOREGROUND
            savedDecFlags = 0
        }
    }


    companion object {
        private const val COMBINED_ATTRS =
            TextStyle.CHARACTER_ATTRIBUTE_BOLD or TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE or
                TextStyle.CHARACTER_ATTRIBUTE_BLINK or TextStyle.CHARACTER_ATTRIBUTE_INVERSE
        private val DSR_STATUS_RESPONSE =
            byteArrayOf(27, '['.code.toByte(), '0'.code.toByte(), 'n'.code.toByte())

        private const val DEFAULT_ROWS = 24
        private const val DEFAULT_COLUMNS = 80
        private const val DEFAULT_CELL_WIDTH_PIXELS = 10
        private const val DEFAULT_CELL_HEIGHT_PIXELS = 20

        const val MOUSE_LEFT_BUTTON: Int = 0
        const val MOUSE_LEFT_BUTTON_MOVED: Int = 32
        const val MOUSE_WHEELUP_BUTTON: Int = 64
        const val MOUSE_WHEELDOWN_BUTTON: Int = 65

        private fun mapSpecialCharacters(codePoint: Int): Int = when(codePoint) {
            '_'.code -> '\u0020'.code
            '`'.code -> '\u25c6'.code
            '0'.code -> '\u2588'.code
            'a'.code -> '\u2592'.code
            'b'.code -> '\u2409'.code
            'c'.code -> '\u240c'.code
            'd'.code -> '\r'.code
            'e'.code -> '\u240a'.code
            'f'.code -> '\u00b0'.code
            'g'.code -> '\u00b1'.code
            'h'.code -> '\n'.code
            'i'.code -> '\u240b'.code
            'j'.code -> '\u2518'.code
            'k'.code -> '\u2510'.code
            'l'.code -> '\u250c'.code
            'm'.code -> '\u2514'.code
            'n'.code -> '\u253c'.code
            'o'.code -> '\u23ba'.code
            'p'.code -> '\u23bb'.code
            'q'.code -> '\u2500'.code
            'r'.code -> '\u23bc'.code
            's'.code -> '\u23bd'.code
            't'.code -> '\u251c'.code
            'u'.code -> '\u2524'.code
            'v'.code -> '\u2534'.code
            'w'.code -> '\u252c'.code
            'x'.code -> '\u2502'.code
            'y'.code -> '\u2264'.code
            'z'.code -> '\u2265'.code
            '{'.code -> '\u03c0'.code
            '|'.code -> '\u2260'.code
            '}'.code -> '\u00a3'.code
            '~'.code -> '\u00b7'.code
            else -> codePoint
        }
    }
}
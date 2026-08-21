package com.awkoo.terminal.core

import com.awkoo.terminal.Constants
import com.awkoo.terminal.constants.TerminalCursorStyle
import kotlin.io.encoding.Base64
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import kotlin.math.max
import kotlin.math.min

class TerminalEmulator(
    private val writeString: (data: String) -> Unit,
    private val writeByteArray: (data: ByteArray) -> Unit
) : TerminalActionHandler {

    private val titleStack = ArrayDeque<String?>()
    private val _titleState = MutableStateFlow<String?>(null)
    val titleState = _titleState.asStateFlow()

    val copiedText = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private var mCursorRow = 0
    private var mCursorCol = 0

    @JvmField var mRows = defaultRows
    @JvmField var mColumns = defaultColumns
    @JvmField var mCellWidthPixels = defaultCellWidthPixels
    @JvmField var mCellHeightPixels = defaultCellHeightPixels

    var cursorStyle: TerminalCursorStyle = Constants.terminalCursorStyle

    private val mMainBuffer = TerminalBuffer(mColumns, Constants.terminalTranscriptRows, mRows)
    private val mAltBuffer = TerminalBuffer(mColumns, mRows, mRows)

    var screen = mMainBuffer
        private set

    private val mSavedStateMain = SavedScreenState()
    private val mSavedStateAlt = SavedScreenState()

    private var mUseLineDrawingG0 = false
    private var mUseLineDrawingG1 = false
    private var mUseLineDrawingUsesG0 = true

    private var mCurrentDecSetFlags = 0
    private var mSavedDecSetFlags = 0

    private var mInsertMode = false
    private var mTabStop = BooleanArray(mColumns)

    private var mTopMargin = 0
    private var mBottomMargin = 0
    private var mLeftMargin = 0
    private var mRightMargin = 0

    private var mAboutToAutoWrap = false
    
    var isCursorBlinkingEnabled = false
    var cursorBlinkState = false
    var isTextBlinkingEnabled = true
    var textBlinkState: Boolean = false

    val isTextVisible: Boolean
        get() = if (isTextBlinkingEnabled) textBlinkState else true

    @JvmField var mForeColor: Int = 0
    @JvmField var mBackColor: Int = 0
    @JvmField var mUnderlineColor: Int = 0
    @JvmField var mEffect: Int = 0
    @JvmField var mUnderlineStyle: Int = TextStyle.UNDERLINE_STYLE_NONE

    var scrollCounter: Int = 0
        private set
    var isAutoScrollDisabled: Boolean = false
        private set

    private var mLastEmittedCodePoint = -1

    @JvmField val mColors: TerminalColors = TerminalColors()

    private val ansiParser = AnsiEscapeParser(this)
    private val utf8Decoder = Utf8Decoder { ansiParser.processCodePoint(it) }

    init {
        reset()
    }

    val isAlternateBufferActive: Boolean
        get() = this.screen == mAltBuffer
        
    var cursorRow: Int
        get() = mCursorRow
        private set(row) {
            mCursorRow = row
            mAboutToAutoWrap = false
        }

    var cursorCol: Int
        get() = mCursorCol
        private set(col) {
            mCursorCol = col
            mAboutToAutoWrap = false
        }

    val isReverseVideo: Boolean get() = isDecsetInternalBitSet(DECSET_BIT_REVERSE_VIDEO)
    val isCursorEnabled: Boolean get() = isDecsetInternalBitSet(DECSET_BIT_CURSOR_ENABLED)
    val isCursorVisible: Boolean
        get() {
            if (!isCursorEnabled) return false
            return if (isCursorBlinkingEnabled) cursorBlinkState else true
        }
    val isKeypadApplicationMode: Boolean get() = isDecsetInternalBitSet(DECSET_BIT_APPLICATION_KEYPAD)
    val isCursorKeysApplicationMode: Boolean get() = isDecsetInternalBitSet(DECSET_BIT_APPLICATION_CURSOR_KEYS)
    val isMouseTrackingActive: Boolean
        get() = isDecsetInternalBitSet(DECSET_BIT_MOUSE_TRACKING_PRESS_RELEASE) ||
                isDecsetInternalBitSet(DECSET_BIT_MOUSE_TRACKING_BUTTON_EVENT)

    private val originTop: Int get() = if (isDecsetInternalBitSet(DECSET_BIT_ORIGIN_MODE)) mTopMargin else 0
    private val originLeft: Int get() = if (isDecsetInternalBitSet(DECSET_BIT_ORIGIN_MODE)) mLeftMargin else 0
    private val originBottom: Int get() = if (isDecsetInternalBitSet(DECSET_BIT_ORIGIN_MODE)) mBottomMargin else mRows
    private val originRight: Int get() = if (isDecsetInternalBitSet(DECSET_BIT_ORIGIN_MODE)) mRightMargin else mColumns

    fun append(buffer: ByteArray, length: Int) {
        utf8Decoder.decode(buffer, length)
    }

    override fun onCodePoint(codePoint: Int) {
        emitCodePoint(codePoint)
    }

    override fun onBell() {
        // 默认忽略，可被通知拦截
    }

    override fun onBackspace() {
        if (mLeftMargin == mCursorCol) {
            val previousRow = mCursorRow - 1
            if (previousRow >= mTopMargin && screen.getLineWrap(previousRow)) {
                screen.clearLineWrap(previousRow)
                setCursorRowCol(previousRow, mRightMargin - 1)
            }
        } else {
            val prevCol = mCursorCol - 1
            val row = screen.allocateFullLineIfNecessary(screen.externalToInternalRow(mCursorRow))
            val charIndex = row.findStartOfColumn(prevCol)
            val charWidth = if (charIndex < row.mSpaceUsed) {
                val cp = if (row.mText[charIndex].isHighSurrogate() && charIndex + 1 < row.mSpaceUsed) {
                    Character.toCodePoint(row.mText[charIndex], row.mText[charIndex + 1])
                } else {
                    row.mText[charIndex].code
                }
                WcWidth.width(cp)
            } else 1
            this.cursorCol = if (charWidth == 2) mCursorCol - 2 else prevCol
        }
    }

    override fun onHorizontalTab() {
        this.cursorCol = nextTabStop(1)
    }

    override fun onLinefeed() {
        doLinefeed()
    }

    override fun onCarriageReturn() {
        this.cursorCol = mLeftMargin
    }

    override fun onShiftIn() {
        mUseLineDrawingUsesG0 = true
    }

    override fun onShiftOut() {
        mUseLineDrawingUsesG0 = false
    }

    override fun onEscCommand(state: Int, command: Int) {
        val b = command
        when(state) {
            AnsiEscapeParser.ESC -> handleEscStandard(b)
            AnsiEscapeParser.ESC_POUND -> {
                if (b == '8'.code) screen.blockSet(0, 0, mColumns, mRows, 'E'.code, this.style)
            }
            AnsiEscapeParser.ESC_SELECT_LEFT_PAREN -> mUseLineDrawingG0 = (b == '0'.code)
            AnsiEscapeParser.ESC_SELECT_RIGHT_PAREN -> mUseLineDrawingG1 = (b == '0'.code)
            AnsiEscapeParser.ESC_PERCENT -> {} // 字符集选择，当前忽略
        }
    }

    private fun handleEscStandard(b: Int) {
        when (b.toChar()) {
            '6' -> if (mCursorCol > mLeftMargin) {
                this.cursorCol = mCursorCol - 1
            } else {
                val rows = mBottomMargin - mTopMargin
                screen.blockCopy(mLeftMargin, mTopMargin, mRightMargin - mLeftMargin - 1, rows, mLeftMargin + 1, mTopMargin)
                screen.blockSet(mLeftMargin, mTopMargin, 1, rows, ' '.code, TextStyle.encode(mForeColor, mBackColor, 0))
            }
            '7' -> saveCursor()
            '8' -> restoreCursor()
            '9' -> if (mCursorCol < mRightMargin - 1) {
                this.cursorCol = mCursorCol + 1
            } else {
                val rows = mBottomMargin - mTopMargin
                screen.blockCopy(mLeftMargin + 1, mTopMargin, mRightMargin - mLeftMargin - 1, rows, mLeftMargin, mTopMargin)
                screen.blockSet(mRightMargin - 1, mTopMargin, 1, rows, ' '.code, TextStyle.encode(mForeColor, mBackColor, 0))
            }
            'c' -> {
                reset()
                mMainBuffer.clearTranscript()
                blockClear(0, 0, mColumns, mRows)
                setCursorPosition(0, 0)
            }
            'D' -> doLinefeed()
            'E' -> {
                this.cursorCol = originLeft
                doLinefeed()
            }
            'F' -> setCursorRowCol(0, mBottomMargin - 1)
            'H' -> mTabStop[mCursorCol] = true
            'M' -> if (mCursorRow <= mTopMargin) {
                screen.blockCopy(mLeftMargin, mTopMargin, mRightMargin - mLeftMargin, mBottomMargin - (mTopMargin + 1), mLeftMargin, mTopMargin + 1)
                blockClear(mLeftMargin, mTopMargin, mRightMargin - mLeftMargin)
            } else {
                mCursorRow--
            }
            'N', '0' -> {} // 忽略
            '=' -> setDecsetinternalBit(DECSET_BIT_APPLICATION_KEYPAD, true)
            '>' -> setDecsetinternalBit(DECSET_BIT_APPLICATION_KEYPAD, false)
            else -> Timber.w("Unknown ESC sequence: %c", b.toChar())
        }
    }

    override fun onCsiCommand(state: Int, command: Int, args: IntArray, argCount: Int, subParams: Int) {
        val b = command
        when (state) {
            AnsiEscapeParser.ESC_CSI -> handleCsiStandard(b, args, argCount, subParams)
            AnsiEscapeParser.ESC_CSI_QUESTIONMARK -> handleCsiQuestionMark(b, args, argCount)
            AnsiEscapeParser.ESC_CSI_BIGGERTHAN -> handleCsiBiggerThan(b)
            AnsiEscapeParser.ESC_CSI_DOLLAR -> handleCsiDollar(b, args, argCount)
            AnsiEscapeParser.ESC_CSI_DOUBLE_QUOTE -> handleCsiDoubleQuote(b, args)
            AnsiEscapeParser.ESC_CSI_SINGLE_QUOTE -> handleCsiSingleQuote(b, args)
            AnsiEscapeParser.ESC_CSI_QUESTIONMARK_ARG_DOLLAR -> handleCsiQuestionMarkArgDollar(b, args)
            AnsiEscapeParser.ESC_CSI_ARGS_SPACE -> handleCsiArgsSpace(b, args)
            AnsiEscapeParser.ESC_CSI_ARGS_ASTERIX -> handleCsiArgsAsterix(b, args)
            AnsiEscapeParser.ESC_CSI_EXCLAMATION -> onSoftReset()
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
                this.cursorRow = max(originTop, mCursorRow - AnsiEscapeParser.getArg(args, 0, 1, true))
            }
            'B' -> this.cursorRow = min(mRows - 1, mCursorRow + AnsiEscapeParser.getArg(args, 0, 1, true))
            'C', 'a' -> this.cursorCol = min(mRightMargin - 1, mCursorCol + AnsiEscapeParser.getArg(args, 0, 1, true))
            'D' -> this.cursorCol = max(mLeftMargin, mCursorCol - AnsiEscapeParser.getArg(args, 0, 1, true))
            'E' -> {
                setCursorPosition(0, mCursorRow - originTop + AnsiEscapeParser.getArg(args, 0, 1, true))
            }
            'F' -> {
                setCursorPosition(0, mCursorRow - originTop - AnsiEscapeParser.getArg(args, 0, 1, true))
            }
            'G' -> this.cursorCol = min(max(1, AnsiEscapeParser.getArg(args, 0, 1, true)), mColumns) - 1
            'H', 'f' -> setCursorPosition(AnsiEscapeParser.getArg(args, 1, 1, true) - 1, AnsiEscapeParser.getArg(args, 0, 1, true) - 1)
            'I' -> this.cursorCol = nextTabStop(AnsiEscapeParser.getArg(args, 0, 1, true))
            'J' -> handleCsiJ(args)
            'K' -> handleCsiK(args)
            'L' -> handleCsiL(args)
            'M' -> handleCsiM(args)
            'P' -> handleCsiP(args)
            'S' -> {
                val linesToScroll = AnsiEscapeParser.getArg(args, 0, 1, true)
                for (i in 0 until linesToScroll) scrollDownOneLine()
            }
            'T' -> {
                val linesToScrollArg = AnsiEscapeParser.getArg(args, 0, 1, true)
                val linesBetween = mBottomMargin - mTopMargin
                val linesToScroll = min(linesBetween, linesToScrollArg)
                screen.blockCopy(mLeftMargin, mTopMargin, mRightMargin - mLeftMargin, linesBetween - linesToScroll, mLeftMargin, mTopMargin + linesToScroll)
                blockClear(mLeftMargin, mTopMargin, mRightMargin - mLeftMargin, linesToScroll)
            }
            'X' -> {
                mAboutToAutoWrap = false
                screen.blockSet(mCursorCol, mCursorRow, min(AnsiEscapeParser.getArg(args, 0, 1, true), mColumns - mCursorCol), 1, ' '.code, this.style)
            }
            'Z' -> {
                var numberOfTabs = AnsiEscapeParser.getArg(args, 0, 1, true)
                var newCol = mCursorCol
                for (i in (mCursorCol - 1) downTo 0) {
                    if (mTabStop[i]) {
                        if (--numberOfTabs == 0) {
                            newCol = max(i, mLeftMargin)
                            break
                        }
                    }
                }
                mCursorCol = newCol
            }
            '`' -> setCursorColRespectingOriginMode(AnsiEscapeParser.getArg(args, 0, 1, true) - 1)
            'b' -> {
                if (mLastEmittedCodePoint != -1) {
                    val numRepeat = AnsiEscapeParser.getArg(args, 0, 1, true)
                    for (i in 0 until numRepeat) emitCodePoint(mLastEmittedCodePoint)
                }
            }
            'c' -> if (AnsiEscapeParser.getArg(args, 0, 0, true) == 0) writeString("\u001b[?64;1;2;6;9;15;18;21;22c")
            'd' -> this.cursorRow = AnsiEscapeParser.getArg(args, 0, 1, true).coerceIn(1, mRows) - 1
            'e' -> {
                setCursorPosition(mCursorCol, mCursorRow - originTop + AnsiEscapeParser.getArg(args, 0, 1, true))
            }
            'g' -> when (AnsiEscapeParser.getArg(args, 0, 0, true)) {
                0 -> mTabStop[mCursorCol] = false
                3 -> { for (i in 0 until mColumns) mTabStop[i] = false }
            }
            'h' -> doSetMode(true, AnsiEscapeParser.getArg(args, 0, 0, true))
            'l' -> doSetMode(false, AnsiEscapeParser.getArg(args, 0, 0, true))
            'm' -> selectGraphicRendition(args, argCount, subParams)
            'n' -> when (AnsiEscapeParser.getArg(args, 0, 0, true)) {
                5 -> writeByteArray(byteArrayOf(27, '['.code.toByte(), '0'.code.toByte(), 'n'.code.toByte()))
                6 -> writeString("\u001b[${mCursorRow + 1};${mCursorCol + 1}R")
            }
            'r' -> {
                mTopMargin = max(0, min(AnsiEscapeParser.getArg(args, 0, 1, true) - 1, mRows - 2))
                mBottomMargin = max(mTopMargin + 1, min(AnsiEscapeParser.getArg(args, 1, mRows, true), mRows))
                setCursorPosition(0, 0)
            }
            's' -> if (isDecsetInternalBitSet(DECSET_BIT_LEFTRIGHT_MARGIN_MODE)) {
                mLeftMargin = min(AnsiEscapeParser.getArg(args, 0, 1, true) - 1, mColumns - 2)
                mRightMargin = max(mLeftMargin + 1, min(AnsiEscapeParser.getArg(args, 1, mColumns, true), mColumns))
                setCursorPosition(0, 0)
            } else saveCursor()
            't' -> handleCsiT(args)
            'u' -> restoreCursor()
            '@' -> {
                mAboutToAutoWrap = false
                val columnsAfterCursor = mColumns - mCursorCol
                val spacesToInsert = min(AnsiEscapeParser.getArg(args, 0, 1, true), columnsAfterCursor)
                val charsToMove = columnsAfterCursor - spacesToInsert
                screen.blockCopy(mCursorCol, mCursorRow, charsToMove, 1, mCursorCol + spacesToInsert, mCursorRow)
                blockClear(mCursorCol, mCursorRow, spacesToInsert)
            }
        }
    }

    private fun handleCsiJ(args: IntArray) {
        mAboutToAutoWrap = false
        when (AnsiEscapeParser.getArg(args, 0, 0, true)) {
            0 -> {
                blockClear(mCursorCol, mCursorRow, mRightMargin - mCursorCol)
                blockClear(mLeftMargin, mCursorRow + 1, mRightMargin - mLeftMargin, mBottomMargin - (mCursorRow + 1))
            }
            1 -> {
                blockClear(0, mTopMargin, mColumns, mCursorRow - mTopMargin)
                blockClear(0, mCursorRow, mCursorCol + 1)
            }
            2 -> blockClear(0, 0, mColumns, mRows)
            3 -> mMainBuffer.clearTranscript()
        }
    }

    private fun handleCsiK(args: IntArray) {
        mAboutToAutoWrap = false
        when (AnsiEscapeParser.getArg(args, 0, 0, true)) {
            0 -> blockClear(mCursorCol, mCursorRow, mColumns - mCursorCol)
            1 -> blockClear(0, mCursorRow, mCursorCol + 1)
            2 -> blockClear(0, mCursorRow, mColumns)
        }
    }

    private fun handleCsiL(args: IntArray) = handleCsiLineInsertDelete(args, insert = true)

    private fun handleCsiM(args: IntArray) = handleCsiLineInsertDelete(args, insert = false)

    private fun handleCsiLineInsertDelete(args: IntArray, insert: Boolean) {
        val linesAfterCursor = (mBottomMargin - mCursorRow).coerceAtLeast(0)
        val linesToChange = min(AnsiEscapeParser.getArg(args, 0, 1, true), linesAfterCursor)
        val linesToMove = linesAfterCursor - linesToChange
        if (insert) {
            screen.blockCopy(0, mCursorRow, mColumns, linesToMove, 0, mCursorRow + linesToChange)
            blockClear(0, mCursorRow, mColumns, linesToChange)
        } else {
            screen.blockCopy(0, mCursorRow + linesToChange, mColumns, linesToMove, 0, mCursorRow)
            blockClear(0, mCursorRow + linesToMove, mColumns, linesToChange)
        }
    }

    private fun handleCsiP(args: IntArray) {
        mAboutToAutoWrap = false
        val cellsAfterCursor = mColumns - mCursorCol
        val cellsToDelete = min(AnsiEscapeParser.getArg(args, 0, 1, true), cellsAfterCursor)
        val cellsToMove = cellsAfterCursor - cellsToDelete
        screen.blockCopy(mCursorCol + cellsToDelete, mCursorRow, cellsToMove, 1, mCursorCol, mCursorRow)
        blockClear(mCursorCol + cellsToMove, mCursorRow, cellsToDelete)
    }

    private fun handleCsiT(args: IntArray) {
        when (AnsiEscapeParser.getArg(args, 0, 0, true)) {
            11 -> writeString("\u001b[1t")
            13 -> writeString("\u001b[3;0;0t")
            14 -> writeString("\u001b[4;${mRows * mCellHeightPixels};${mColumns * mCellWidthPixels}t")
            16 -> writeString("\u001b[6;${mRows * mCellHeightPixels};${mColumns * mCellWidthPixels}t")
            18, 19 -> writeString("\u001b[9;${mRows};${mColumns}t") // 报告精确字符尺寸
            20 -> writeString("\u001b]LIconLabel\u001b\\")
            21 -> writeString("\u001b]l\u001b\\")
            22 -> {
                titleStack.addLast(_titleState.value)
                if (titleStack.size > 20) titleStack.removeAt(0)
            }
            23 -> if (!titleStack.isEmpty()) this._titleState.value = titleStack.removeLast()
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
                mAboutToAutoWrap = false
                val fillChar = ' '.code
                var startCol = -1; var startRow = -1; var endCol = -1; var endRow = -1
                val justRow = (b == 'K'.code)
                when (AnsiEscapeParser.getArg(args, 0, 0, true)) {
                    0 -> { startCol = mCursorCol; startRow = mCursorRow; endCol = mColumns; endRow = if (justRow) (mCursorRow + 1) else mRows }
                    1 -> { startCol = 0; startRow = if (justRow) mCursorRow else 0; endCol = mCursorCol + 1; endRow = mCursorRow + 1 }
                    2 -> { startCol = 0; startRow = if (justRow) mCursorRow else 0; endCol = mColumns; endRow = if (justRow) (mCursorRow + 1) else mRows }
                }
                val style = this.style
                for (row in startRow until endRow) {
                    for (col in startCol until endCol) {
                        if (!screen.getStyleAt(row, col).isProtected) screen.setChar(col, row, fillChar, style)
                    }
                }
            }
            'h', 'l' -> {
                for (i in 0 until argCount) doDecSetOrReset(b == 'h'.code, args[i])
            }
            'n' -> if (AnsiEscapeParser.getArg(args, 0, -1, true) == 6) writeString("\u001b[?${mCursorRow + 1};${mCursorCol + 1};1R")
            'r', 's' -> {
                for (i in 0 until argCount) {
                    val externalBit = args[i]
                    val internalBit = mapDecSetBitToInternalBit(externalBit)
                    if (internalBit != -1) {
                        if (b == 's'.code) mSavedDecSetFlags = mSavedDecSetFlags or internalBit
                        else doDecSetOrReset((mSavedDecSetFlags and internalBit) != 0, externalBit)
                    }
                }
            }
        }
    }

    private fun handleCsiBiggerThan(b: Int) {
        when (b.toChar()) {
            'c' -> writeString("\u001b[>41;320;0c")
        }
    }

    private fun handleCsiDollar(b: Int, args: IntArray, argCount: Int) {
        when (b.toChar()) {
            'v' -> {
                val topSource = min(AnsiEscapeParser.getArg(args, 0, 1, true) - 1 + originTop, mRows)
                val leftSource = min(AnsiEscapeParser.getArg(args, 1, 1, true) - 1 + originLeft, mColumns)
                val bottomSource = min(max(AnsiEscapeParser.getArg(args, 2, mRows, true) + originTop, topSource), mRows)
                val rightSource = min(max(AnsiEscapeParser.getArg(args, 3, mColumns, true) + originLeft, leftSource), mColumns)
                val destionationTop = min(AnsiEscapeParser.getArg(args, 5, 1, true) - 1 + originTop, mRows)
                val destinationLeft = min(AnsiEscapeParser.getArg(args, 6, 1, true) - 1 + originLeft, mColumns)
                val heightToCopy = min(mRows - destionationTop, bottomSource - topSource)
                val widthToCopy = min(mColumns - destinationLeft, rightSource - leftSource)
                screen.blockCopy(leftSource, topSource, widthToCopy, heightToCopy, destinationLeft, destionationTop)
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
        when(fillChar) {
            in 32..126, in 160..255 -> {
                val top = min(AnsiEscapeParser.getArg(args, argIdx++, 1, true) + originTop, originBottom + 1)
                val left = min(AnsiEscapeParser.getArg(args, argIdx++, 1, true) + originLeft, originRight + 1)
                val bottom = min(AnsiEscapeParser.getArg(args, argIdx++, mRows, true) + originTop, originBottom)
                val right = min(AnsiEscapeParser.getArg(args, argIdx, mColumns, true) + originLeft, originRight)
                val style = this.style
                for (row in (top - 1) until bottom) {
                    for (col in (left - 1) until right) {
                        if (!selective || !screen.getStyleAt(row, col).isProtected) {
                            val applyStyle = if (keepVisualAttributes) screen.getStyleAt(row, col) else style
                            val applyExt = if (keepVisualAttributes) screen.allocateFullLineIfNecessary(screen.externalToInternalRow(row)).getExtendedEffect(col) else currentExtendedEffect

                            screen.setChar(col, row, fillChar, applyStyle, applyExt)
                        }
                    }
                }
            }
        }
    }

    private fun handleCsiDollarRect(b: Int, args: IntArray, argCount: Int) {
        val reverse = b == 't'.code
        val top = min(AnsiEscapeParser.getArg(args, 0, 1, true) - 1 + originTop, originBottom)
        val left = min(AnsiEscapeParser.getArg(args, 1, 1, true) - 1 + originLeft, originRight)
        val bottom = min(AnsiEscapeParser.getArg(args, 2, mRows, true) + 1 + originTop, originBottom)
        val right = min(AnsiEscapeParser.getArg(args, 3, mColumns, true) + 1 + originLeft, originRight)
        for (i in 4 until argCount) {
            val COMBINED_ATTRS = TextStyle.CHARACTER_ATTRIBUTE_BOLD or TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE or
                TextStyle.CHARACTER_ATTRIBUTE_BLINK or TextStyle.CHARACTER_ATTRIBUTE_INVERSE
            val (bits, setOrClear) = when (AnsiEscapeParser.getArg(args, i, 0, false)) {
                0  -> COMBINED_ATTRS to reverse
                1  -> TextStyle.CHARACTER_ATTRIBUTE_BOLD to true
                4  -> TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE to true
                5  -> TextStyle.CHARACTER_ATTRIBUTE_BLINK to true
                7  -> TextStyle.CHARACTER_ATTRIBUTE_INVERSE to true
                22 -> TextStyle.CHARACTER_ATTRIBUTE_BOLD to false
                24 -> TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE to false
                25 -> TextStyle.CHARACTER_ATTRIBUTE_BLINK to false
                27 -> TextStyle.CHARACTER_ATTRIBUTE_INVERSE to false
                else -> 0 to true
            }
            if (!(reverse && !setOrClear)) {
                screen.setOrClearEffect(bits, setOrClear, reverse, isDecsetInternalBitSet(DECSET_BIT_RECTANGULAR_CHANGEATTRIBUTE), originLeft, originRight, top, left, bottom, right)
            }
        }
    }

    private fun handleCsiDoubleQuote(b: Int, args: IntArray) {
        if (b == 'q'.code) {
            when (AnsiEscapeParser.getArg(args, 0, 0, true)) {
                0, 2 -> mEffect = mEffect and TextStyle.CHARACTER_ATTRIBUTE_PROTECTED.inv()
                1 -> mEffect = mEffect or TextStyle.CHARACTER_ATTRIBUTE_PROTECTED
            }
        }
    }

    private fun handleCsiSingleQuote(b: Int, args: IntArray) {
        val columnsAfterCursor = mRightMargin - mCursorCol
        val columnsToChange = min(AnsiEscapeParser.getArg(args, 1, 1, true), columnsAfterCursor)
        val columnsToMove = columnsAfterCursor - columnsToChange
        when (b) {
            '}'.code -> {
                screen.blockCopy(mCursorCol, 0, columnsToMove, mRows, mCursorCol + columnsToChange, 0)
                blockClear(mCursorCol, 0, columnsToChange, mRows)
            }
            '~'.code -> {
                screen.blockCopy(mCursorCol + columnsToChange, 0, columnsToMove, mRows, mCursorCol, 0)
                blockClear(mCursorCol + columnsToMove, 0, columnsToChange, mRows)
            }
        }
    }

    private fun handleCsiQuestionMarkArgDollar(b: Int, args: IntArray) {
        if (b == 'p'.code) {
            val mode = AnsiEscapeParser.getArg(args, 0, 0, true)
            val value = when(mode) {
                47, 1047, 1049 -> if (isAlternateBufferActive) 1 else 2
                else -> {
                    val internalBit = mapDecSetBitToInternalBit(mode)
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
                setDecsetinternalBit(DECSET_BIT_RECTANGULAR_CHANGEATTRIBUTE, attrChangeExtent == 2)
            }
        }
    }

    override fun onOscCommand(value: Int, textParameter: String, bellOrStringTerminator: String) {
        when (value) {
            0, 1, 2 -> this._titleState.value = textParameter
            4 -> handleOscSetColor(textParameter)
            10, 11, 12 -> handleOscQuerySetColor(value, textParameter, bellOrStringTerminator)
            52 -> handleOscClipboard(textParameter)
            104 -> handleOscResetColor(textParameter)
            110, 111, 112 -> mColors.reset(TextStyle.COLOR_INDEX_FOREGROUND + (value - 110))
            119 -> {} // 忽略
        }
    }

    private fun handleOscSetColor(textParameter: String) {
        var colorIndex = -1
        var parsingPairStart = -1
        var i = 0
        while (i <= textParameter.length) {
            val endOfInput = i == textParameter.length
            val b = if (endOfInput) ';' else textParameter[i]
            if (b == ';') {
                if (parsingPairStart < 0) {
                    parsingPairStart = i + 1
                } else {
                    if (colorIndex in 0..255) {
                        mColors.tryParseColor(colorIndex, textParameter.substring(parsingPairStart, i))
                    }
                    colorIndex = -1
                    parsingPairStart = -1
                }
            } else if (parsingPairStart < 0 && b in '0'..'9') {
                colorIndex = (if (colorIndex < 0) 0 else colorIndex * 10) + (b.code - '0'.code)
            }
            if (endOfInput) break
            i++
        }
    }

    /**
     * 处理 OSC 查询/设置颜色命令 (XTOSC)。
     *
     * 查询：参数为 "?" 时返回当前颜色的 rgb 格式响应。
     * 设置：参数为颜色值时解析并应用。
     * 支持连续设置多个颜色（以分号分隔，从前景色开始递增）。
     */
    private fun handleOscQuerySetColor(value: Int, textParameter: String, bellOrStringTerminator: String) {
        var specialIndex = TextStyle.COLOR_INDEX_FOREGROUND + (value - 10)
        var lastSemiIndex = 0
        var charIndex = 0
        while (charIndex <= textParameter.length) {
            val endOfInput = charIndex == textParameter.length
            if (endOfInput || textParameter[charIndex] == ';') {
                try {
                    val colorSpec = textParameter.substring(lastSemiIndex, charIndex)
                    if ("?" == colorSpec) {
                        val rgb = mColors.mCurrentColors[specialIndex]
                        val r = ((65535 * ((rgb and 0x00FF0000) shr 16)) / 255).hex4
                        val g = ((65535 * ((rgb and 0x0000FF00) shr 8)) / 255).hex4
                        val b = ((65535 * ((rgb and 0x000000FF))) / 255).hex4
                        writeString("\u001b]$value;rgb:$r/$g/$b$bellOrStringTerminator")
                    } else {
                        mColors.tryParseColor(specialIndex, colorSpec)
                    }
                    specialIndex++
                    if (endOfInput || specialIndex > TextStyle.COLOR_INDEX_CURSOR) break
                    lastSemiIndex = charIndex + 1
                } catch (e: Exception) {}
            }
            charIndex++
        }
    }

    private fun handleOscClipboard(textParameter: String) {
        val startIndex = textParameter.indexOf(";") + 1
        try {
            val data = Base64.decode(textParameter.substring(startIndex))
            copiedText.tryEmit(data.toString(Charsets.UTF_8))
        } catch (e: Exception) {
            Timber.w("OSC Manipulate selection, invalid string '$textParameter'")
        }
    }

    private fun handleOscResetColor(textParameter: String) {
        if (textParameter.isEmpty()) mColors.reset()
        else {
            var lastIndex = 0
            for (i in 0..textParameter.length) {
                if (i == textParameter.length || textParameter[i] == ';') {
                    try {
                        val colorToReset = textParameter.substring(lastIndex, i).toInt()
                        mColors.reset(colorToReset)
                    } catch (e: NumberFormatException) {}
                    lastIndex = i + 1
                }
            }
        }
    }

    /**
     * 处理 DCS 设备控制字符串。
     *
     * 支持两种格式：
     * - $q：VT100 设备属性查询（DA1）
     * - +q：XTGETTCAP 终端能力查询（xterm 扩展）
     */
    override fun onDeviceControl(dcs: String) {
        if (dcs.startsWith("\$q")) {
            if (dcs == "\$q\"p") {
                writeString("\u001bP1\$r64;1\"p\u001b\\")
            }
        } else if (dcs.startsWith("+q")) {
            for (part in dcs.substring(2).split(";").filter { it.isNotEmpty() }) {
                if (part.length % 2 == 0) {
                    val transBuffer = StringBuilder()
                    var i = 0
                    while (i < part.length) {
                        try {
                            transBuffer.append(part.substring(i, i + 2).toInt(16).toChar())
                        } catch (e: NumberFormatException) {}
                        i += 2
                    }
                    val trans = transBuffer.toString()
                    val responseValue = when (trans) {
                        "Co", "colors" -> "256"
                        "TN", "name" -> "xterm"
                        else -> KeyHandler.getCodeFromTermcap(
                            trans,
                            isDecsetInternalBitSet(DECSET_BIT_APPLICATION_CURSOR_KEYS),
                            isDecsetInternalBitSet(DECSET_BIT_APPLICATION_KEYPAD)
                        )
                    }
                    if (responseValue == null) {
                        writeString("\u001bP0+r$part\u001b\\")
                    } else {
                        val hexEncoded = StringBuilder()
                        for (element in responseValue) hexEncoded.append("%02X".format(element.code))
                        writeString("\u001bP1+r$part=$hexEncoded\u001b\\")
                    }
                }
            }
        }
    }

    override fun onSoftReset() {
        reset()
    }

    fun sendMouseEvent(mouseButton: Int, column: Int, row: Int, pressed: Boolean) {
        var button = mouseButton
        val c = min(max(column, 1), mColumns)
        val r = min(max(row, 1), mRows)
        if (button == MOUSE_LEFT_BUTTON_MOVED && !isDecsetInternalBitSet(DECSET_BIT_MOUSE_TRACKING_BUTTON_EVENT)) {
            return
        } else if (isDecsetInternalBitSet(DECSET_BIT_MOUSE_PROTOCOL_SGR)) {
            if (pressed) writeString("\u001b[<${button};${c};${r}M")
            else writeString("\u001b[<${button};${c};${r}m")
        } else {
            button = if (pressed) button else 3
            if (!(c > 255 - 32 || r > 255 - 32)) {
                val data = byteArrayOf('\u001b'.code.toByte(), '['.code.toByte(), 'M'.code.toByte(), (32 + button).toByte(), (32 + c).toByte(), (32 + r).toByte())
                writeByteArray(data)
            }
        }
    }

    fun resize(columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int) {
        this.mCellWidthPixels = cellWidthPixels
        this.mCellHeightPixels = cellHeightPixels
        
        val sizeChanged = mColumns != columns || mRows != rows

        if (sizeChanged) {
            if (mRows != rows) {
                mRows = rows
                mTopMargin = 0
                mBottomMargin = mRows
            }
            if (mColumns != columns) {
                val oldColumns = mColumns
                mColumns = columns
                val oldTabStop = mTabStop
                mTabStop = BooleanArray(mColumns)
                setDefaultTabStops()
                oldTabStop.copyInto(mTabStop, endIndex = min(oldColumns, columns))
                mLeftMargin = 0
                mRightMargin = mColumns
            }
        }

        if (sizeChanged || screen.mColumns != mColumns || screen.mScreenRows != mRows) {
            val cursor = intArrayOf(mCursorCol, mCursorRow)
            val newTotalRows = if (isAlternateBufferActive) mRows else mMainBuffer.mTotalRows
            screen.resize(mColumns, mRows, newTotalRows, cursor, this.style, this.isAlternateBufferActive)
            mCursorCol = cursor[0]
            mCursorRow = cursor[1]
        }
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
        var cp = codePoint
        mLastEmittedCodePoint = cp
        if (if (mUseLineDrawingUsesG0) mUseLineDrawingG0 else mUseLineDrawingG1) {
            cp = when (cp.toChar()) {
                '_' -> ' '.code
                '`' -> '◆'.code
                '0' -> '█'.code
                'a' -> '▒'.code
                'b' -> '␉'.code
                'c' -> '␌'.code
                'd' -> '\r'.code
                'e' -> '␊'.code
                'f' -> '°'.code
                'g' -> '±'.code
                'h' -> '\n'.code
                'i' -> '␋'.code
                'j' -> '┘'.code
                'k' -> '┐'.code
                'l' -> '┌'.code
                'm' -> '└'.code
                'n' -> '┼'.code
                'o' -> '⎺'.code
                'p' -> '⎻'.code
                'q' -> '─'.code
                'r' -> '⎼'.code
                's' -> '⎽'.code
                't' -> '├'.code
                'u' -> '┤'.code
                'v' -> '┴'.code
                'w' -> '┬'.code
                'x' -> '│'.code
                'y' -> '≤'.code
                'z' -> '≥'.code
                '{' -> 'π'.code
                '|' -> '≠'.code
                '}' -> '£'.code
                '~' -> '·'.code
                else -> cp
            }
        }

        // 自动换行：行尾 + (预备换行 + 普通字符) 或 宽字符 → 换行
        val autoWrap = isDecsetInternalBitSet(DECSET_BIT_AUTOWRAP)
        val displayWidth = WcWidth.width(cp)
        val cursorInLastColumn = mCursorCol == mRightMargin - 1

        if (autoWrap) {
            if (cursorInLastColumn && ((mAboutToAutoWrap && displayWidth == 1) || displayWidth == 2)) {
                screen.setLineWrap(mCursorRow)
                mCursorCol = mLeftMargin
                if (mCursorRow + 1 < mBottomMargin) mCursorRow++ else scrollDownOneLine()
            }
        } else if (cursorInLastColumn && displayWidth == 2) {
            return
        }

        if (mInsertMode && displayWidth > 0) {
            val destCol = mCursorCol + displayWidth
            if (destCol < mRightMargin) screen.blockCopy(mCursorCol, mCursorRow, mRightMargin - destCol, 1, destCol, mCursorRow)
        }

        val offsetDueToCombiningChar = (if (displayWidth <= 0 && mCursorCol > 0 && !mAboutToAutoWrap) 1 else 0)
        screen.setChar(mCursorCol - offsetDueToCombiningChar, mCursorRow, cp, this.style, currentExtendedEffect)

        if (autoWrap && displayWidth > 0) mAboutToAutoWrap = (mCursorCol == mRightMargin - displayWidth)
        mCursorCol = min(mCursorCol + displayWidth, mRightMargin - 1)
    }

    private fun doLinefeed() {
        mAboutToAutoWrap = false
        if (mCursorRow >= mBottomMargin) {
            if (mCursorRow != mRows - 1) this.cursorRow = mCursorRow + 1
        } else {
            if (mCursorRow + 1 == mBottomMargin) scrollDownOneLine() else this.cursorRow = mCursorRow + 1
        }
    }

    private fun scrollDownOneLine() {
        this.scrollCounter++
        val currentStyle = this.style
        if (mLeftMargin != 0 || mRightMargin != mColumns) {
            screen.blockCopy(mLeftMargin, mTopMargin + 1, mRightMargin - mLeftMargin, mBottomMargin - mTopMargin - 1, mLeftMargin, mTopMargin)
            screen.blockSet(mLeftMargin, mBottomMargin - 1, mRightMargin - mLeftMargin, 1, ' '.code, currentStyle)
        } else {
            screen.scrollDownOneLine(mTopMargin, mBottomMargin, currentStyle)
        }
    }

    private fun doDecSetOrReset(setting: Boolean, externalBit: Int) {
        val internalBit = mapDecSetBitToInternalBit(externalBit)
        if (internalBit != -1) setDecsetinternalBit(internalBit, setting)
        when (externalBit) {
            3 -> {
                mTopMargin = 0; mLeftMargin = 0; mBottomMargin = mRows; mRightMargin = mColumns
                setDecsetinternalBit(DECSET_BIT_LEFTRIGHT_MARGIN_MODE, false)
                blockClear(0, 0, mColumns, mRows)
                setCursorRowCol(0, 0)
            }
            6 -> if (setting) setCursorPosition(0, 0)
            69 -> if (!setting) { mLeftMargin = 0; mRightMargin = mColumns }
            1048 -> if (setting) saveCursor() else restoreCursor()
            47, 1047, 1049 -> {
                val newScreen = if (setting) mAltBuffer else mMainBuffer
                if (newScreen != this.screen) {
                    val resized = !(newScreen.mColumns == mColumns && newScreen.mScreenRows == mRows)
                    if (setting) saveCursor()
                    this.screen = newScreen
                    if (!setting) {
                        val col = mSavedStateMain.mSavedCursorCol
                        val row = mSavedStateMain.mSavedCursorRow
                        restoreCursor()
                        if (resized) { mCursorCol = col; mCursorRow = row }
                    }
                    if (resized) resize(mColumns, mRows, mCellWidthPixels, mCellHeightPixels)
                    if (externalBit == 1049 && newScreen == mAltBuffer) newScreen.blockSet(0, 0, mColumns, mRows, ' '.code, this.style)
                }
            }
        }
    }

    /**
     * 处理 SGR（选择图形渲染）转义序列。
     *
     * 解析参数数组，设置前景色、背景色、效果位（粗体、下划线、闪烁等）。
     * 支持 256 色和 24-bit RGB 真彩色、子参数位集跳过等 xterm 扩展。
     */
    private fun selectGraphicRendition(args: IntArray, argCount: Int, argsSubParamsBitSet: Int) {
        var i = 0
        while (i < argCount) {
            if ((argsSubParamsBitSet and (1 shl i)) != 0) { i++; continue }
            var code = AnsiEscapeParser.getArg(args, i, 0, false)
            if (code < 0) { if (i > 0) { i++; continue } else code = 0 }
            when(code) {
                0 -> { mForeColor = TextStyle.COLOR_INDEX_FOREGROUND; mBackColor = TextStyle.COLOR_INDEX_BACKGROUND; mEffect = 0; mUnderlineStyle = TextStyle.UNDERLINE_STYLE_NONE; mUnderlineColor = TextStyle.COLOR_INDEX_FOREGROUND }
                4 -> {
                    if (i + 1 < argCount && ((argsSubParamsBitSet and (1 shl (i + 1))) != 0)) {
                        i++
                        val style = args[i]
                        if (style == 0) {
                            mEffect = mEffect and TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE.inv()
                            mUnderlineStyle = TextStyle.UNDERLINE_STYLE_NONE
                        } else {
                            mEffect = mEffect or TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE
                            mUnderlineStyle = if (style in TextStyle.UNDERLINE_STYLE_NONE..TextStyle.UNDERLINE_STYLE_DASHED) style
                                              else TextStyle.UNDERLINE_STYLE_SINGLE
                        }
                    } else {
                        mEffect = mEffect or TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE
                        mUnderlineStyle = TextStyle.UNDERLINE_STYLE_SINGLE
                    }
                }
                // SGR 24：关闭下划线时同步重置下划线样式，避免残留样式在后续开启下划线时误生效
                24 -> {
                    mEffect = mEffect and TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE.inv()
                    mUnderlineStyle = TextStyle.UNDERLINE_STYLE_NONE
                }
                in sgrEffectMap -> {
                    val attr = sgrEffectMap[code]!!
                    mEffect = if (code < 20) mEffect or attr else mEffect and attr.inv()
                }
                in 30..37 -> mForeColor = code - 30
                // 38/48/58: 前景色/背景色/下划线色的扩展格式（256色和24-bit RGB）
                38, 48, 58 -> if (i + 2 < argCount) {
                    when(args[i + 1]) {
                        2 -> {
                            if (i + 4 < argCount) {
                                val r = AnsiEscapeParser.getArg(args, i + 2, 0, false)
                                val g = AnsiEscapeParser.getArg(args, i + 3, 0, false)
                                val b = AnsiEscapeParser.getArg(args, i + 4, 0, false)
                                if (r in 0..255 && g in 0..255 && b in 0..255) {
                                    val argb = -0x1000000 or (r shl 16) or (g shl 8) or b
                                    when (code) { 38 -> mForeColor = argb; 48 -> mBackColor = argb; 58 -> mUnderlineColor = argb }
                                }
                                i += 4
                            } else {
                                i += 2
                            }
                        }
                        5 -> {
                            val color = AnsiEscapeParser.getArg(args, i + 2, 0, false)
                            i += 2
                            if (color in 0 until TextStyle.NUM_INDEXED_COLORS) {
                                when (code) { 38 -> mForeColor = color; 48 -> mBackColor = color; 58 -> mUnderlineColor = color }
                            }
                        }
                    }
                } else {
                    // 参数不足，跳过当前 SGR 代码
                    i++
                }
                39 -> mForeColor = TextStyle.COLOR_INDEX_FOREGROUND
                in 40..47 -> mBackColor = code - 40
                49 -> mBackColor = TextStyle.COLOR_INDEX_BACKGROUND
                59 -> mUnderlineColor = TextStyle.COLOR_INDEX_FOREGROUND
                in 90..97 -> mForeColor = code - 90 + 8
                in 100..107 -> mBackColor = code - 100 + 8
            }
            i++
        }
    }

    private fun doSetMode(newValue: Boolean, modeBit: Int) {
        when (modeBit) {
            4 -> mInsertMode = newValue
        }
    }

    private fun saveCursor() {
        val state = if (isAlternateBufferActive) mSavedStateAlt else mSavedStateMain
        state.mSavedCursorRow = mCursorRow; state.mSavedCursorCol = mCursorCol
        state.mSavedEffect = mEffect; state.mSavedForeColor = mForeColor; state.mSavedBackColor = mBackColor
        state.mSavedUnderlineStyle = mUnderlineStyle; state.mSavedUnderlineColor = mUnderlineColor
        state.mSavedDecFlags = mCurrentDecSetFlags
        state.mUseLineDrawingG0 = mUseLineDrawingG0; state.mUseLineDrawingG1 = mUseLineDrawingG1; state.mUseLineDrawingUsesG0 = mUseLineDrawingUsesG0
    }

    private fun restoreCursor() {
        val state = if (isAlternateBufferActive) mSavedStateAlt else mSavedStateMain
        setCursorRowCol(state.mSavedCursorRow, state.mSavedCursorCol)
        mEffect = state.mSavedEffect; mForeColor = state.mSavedForeColor; mBackColor = state.mSavedBackColor
        mUnderlineStyle = state.mSavedUnderlineStyle; mUnderlineColor = state.mSavedUnderlineColor
        val mask = DECSET_BIT_AUTOWRAP or DECSET_BIT_ORIGIN_MODE
        mCurrentDecSetFlags = (mCurrentDecSetFlags and mask.inv()) or (state.mSavedDecFlags and mask)
        mUseLineDrawingG0 = state.mUseLineDrawingG0; mUseLineDrawingG1 = state.mUseLineDrawingG1; mUseLineDrawingUsesG0 = state.mUseLineDrawingUsesG0
    }

    private fun nextTabStop(numTabs: Int): Int {
        var n = numTabs
        for (i in mCursorCol + 1 until mColumns) if (mTabStop[i] && --n == 0) return min(i, mRightMargin)
        return mRightMargin - 1
    }

    private fun setCursorPosition(x: Int, y: Int) {
        val newRow = max(originTop, min(originTop + y, originBottom - 1))
        val newCol = max(originLeft, min(originLeft + x, originRight - 1))
        setCursorRowCol(newRow, newCol)
    }
    private fun setCursorColRespectingOriginMode(col: Int) = setCursorPosition(col, mCursorRow)
    private fun setCursorRowCol(row: Int, col: Int) {
        mCursorRow = max(0, min(row, mRows - 1)); mCursorCol = max(0, min(col, mColumns - 1)); mAboutToAutoWrap = false
    }

    private fun blockClear(sx: Int, sy: Int, w: Int, h: Int = 1) = screen.blockSet(sx, sy, w, h, ' '.code, this.style)
    private val style: TextStyle get() = TextStyle.encode(mForeColor, mBackColor, mEffect)
    private val currentExtendedEffect: Long get() = TextStyle.encodeExtendedEffect(mUnderlineStyle, mUnderlineColor)
    private fun setDefaultTabStops() { for (i in 0 until mColumns) mTabStop[i] = (i and 7) == 0 && i != 0 }

    private fun isDecsetInternalBitSet(bit: Int) = (mCurrentDecSetFlags and bit) != 0
    private fun setDecsetinternalBit(internalBit: Int, set: Boolean) {
        if (set && internalBit == DECSET_BIT_MOUSE_TRACKING_PRESS_RELEASE) setDecsetinternalBit(DECSET_BIT_MOUSE_TRACKING_BUTTON_EVENT, false)
        else if (set && internalBit == DECSET_BIT_MOUSE_TRACKING_BUTTON_EVENT) setDecsetinternalBit(DECSET_BIT_MOUSE_TRACKING_PRESS_RELEASE, false)
        mCurrentDecSetFlags = if (set) mCurrentDecSetFlags or internalBit else mCurrentDecSetFlags and internalBit.inv()
    }

    fun clearScrollCounter() { this.scrollCounter = 0 }
    fun toggleAutoScrollDisabled() { this.isAutoScrollDisabled = !this.isAutoScrollDisabled }
    fun onWindowFocusChanged(hasFocus: Boolean) { if (isDecsetInternalBitSet(DECSET_BIT_SEND_FOCUS_EVENTS)) writeString(if (hasFocus) "\u001b[I" else "\u001b[O") }

    fun reset() {
        cursorStyle = Constants.defaultTerminalCursorStyle
        mInsertMode = false; mLeftMargin = 0; mTopMargin = 0; mBottomMargin = mRows; mRightMargin = mColumns; mAboutToAutoWrap = false
        mSavedStateAlt.mSavedForeColor = TextStyle.COLOR_INDEX_FOREGROUND; mSavedStateMain.mSavedForeColor = TextStyle.COLOR_INDEX_FOREGROUND
        mForeColor = TextStyle.COLOR_INDEX_FOREGROUND
        mSavedStateAlt.mSavedBackColor = TextStyle.COLOR_INDEX_BACKGROUND; mSavedStateMain.mSavedBackColor = TextStyle.COLOR_INDEX_BACKGROUND
        mBackColor = TextStyle.COLOR_INDEX_BACKGROUND
        mUnderlineStyle = TextStyle.UNDERLINE_STYLE_NONE; mUnderlineColor = TextStyle.COLOR_INDEX_FOREGROUND
        mSavedStateAlt.mSavedUnderlineStyle = TextStyle.UNDERLINE_STYLE_NONE; mSavedStateMain.mSavedUnderlineStyle = TextStyle.UNDERLINE_STYLE_NONE
        mSavedStateAlt.mSavedUnderlineColor = TextStyle.COLOR_INDEX_FOREGROUND; mSavedStateMain.mSavedUnderlineColor = TextStyle.COLOR_INDEX_FOREGROUND
        setDefaultTabStops()
        mUseLineDrawingG1 = false; mUseLineDrawingG0 = false; mUseLineDrawingUsesG0 = true
        mSavedStateMain.mSavedDecFlags = 0; mSavedStateMain.mSavedEffect = 0; mSavedStateMain.mSavedCursorCol = 0; mSavedStateMain.mSavedCursorRow = 0
        mSavedStateAlt.mSavedDecFlags = 0; mSavedStateAlt.mSavedEffect = 0; mSavedStateAlt.mSavedCursorCol = 0; mSavedStateAlt.mSavedCursorRow = 0
        mCurrentDecSetFlags = 0
        setDecsetinternalBit(DECSET_BIT_AUTOWRAP, true)
        setDecsetinternalBit(DECSET_BIT_CURSOR_ENABLED, true)
        mSavedStateAlt.mSavedDecFlags = mCurrentDecSetFlags; mSavedStateMain.mSavedDecFlags = mCurrentDecSetFlags; mSavedDecSetFlags = mCurrentDecSetFlags
        mColors.reset()
        utf8Decoder.reset()
        ansiParser.reset()
    }

    fun getSelectedText(x1: Int, y1: Int, x2: Int, y2: Int): String = screen.getSelectedText(x1, y1, x2, y2)
    fun paste(text: String) {
        val processedText = buildString(text.length) {
            var i = 0
            while (i < text.length) {
                val c = text[i]; val code = c.code
                if (code == 0x1B || code in 0x80..0x9F) { i++; continue }
                if (c == '\n' || c == '\r') {
                    append('\r'); if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                } else append(c)
                i++
            }
        }
        val bracketed = isDecsetInternalBitSet(DECSET_BIT_BRACKETED_PASTE_MODE)
        if (bracketed) writeString("\u001b[200~")
        writeString(processedText)
        if (bracketed) writeString("\u001b[201~")
    }

    internal class SavedScreenState {
        var mSavedCursorRow: Int = 0; var mSavedCursorCol: Int = 0; var mSavedEffect: Int = 0
        var mSavedForeColor: Int = 0; var mSavedBackColor: Int = 0; var mSavedDecFlags: Int = 0
        var mSavedUnderlineStyle: Int = TextStyle.UNDERLINE_STYLE_NONE; var mSavedUnderlineColor: Int = TextStyle.COLOR_INDEX_FOREGROUND
        var mUseLineDrawingG0: Boolean = false; var mUseLineDrawingG1: Boolean = false; var mUseLineDrawingUsesG0: Boolean = true
    }

    private val Int.hex4: String get() = "%04x".format(this)

    companion object {
        private const val defaultRows = 24
        private const val defaultColumns = 80
        private const val defaultCellWidthPixels = 10
        private const val defaultCellHeightPixels = 20
        
        const val MOUSE_LEFT_BUTTON: Int = 0
        const val MOUSE_LEFT_BUTTON_MOVED: Int = 32
        const val MOUSE_WHEELUP_BUTTON: Int = 64
        const val MOUSE_WHEELDOWN_BUTTON: Int = 65

        private const val DECSET_BIT_APPLICATION_CURSOR_KEYS = 1
        private const val DECSET_BIT_REVERSE_VIDEO = 1 shl 1
        private const val DECSET_BIT_ORIGIN_MODE = 1 shl 2
        private const val DECSET_BIT_AUTOWRAP = 1 shl 3
        private const val DECSET_BIT_CURSOR_ENABLED = 1 shl 4
        private const val DECSET_BIT_APPLICATION_KEYPAD = 1 shl 5
        private const val DECSET_BIT_MOUSE_TRACKING_PRESS_RELEASE = 1 shl 6
        private const val DECSET_BIT_MOUSE_TRACKING_BUTTON_EVENT = 1 shl 7
        private const val DECSET_BIT_SEND_FOCUS_EVENTS = 1 shl 8
        private const val DECSET_BIT_MOUSE_PROTOCOL_SGR = 1 shl 9
        private const val DECSET_BIT_BRACKETED_PASTE_MODE = 1 shl 10
        private const val DECSET_BIT_LEFTRIGHT_MARGIN_MODE = 1 shl 11
        private const val DECSET_BIT_RECTANGULAR_CHANGEATTRIBUTE = 1 shl 12

        private val sgrEffectMap = mapOf(
            1 to TextStyle.CHARACTER_ATTRIBUTE_BOLD,
            2 to TextStyle.CHARACTER_ATTRIBUTE_DIM,
            3 to TextStyle.CHARACTER_ATTRIBUTE_ITALIC,
            5 to TextStyle.CHARACTER_ATTRIBUTE_BLINK,
            7 to TextStyle.CHARACTER_ATTRIBUTE_INVERSE,
            8 to TextStyle.CHARACTER_ATTRIBUTE_INVISIBLE,
            9 to TextStyle.CHARACTER_ATTRIBUTE_STRIKETHROUGH,
            22 to (TextStyle.CHARACTER_ATTRIBUTE_BOLD or TextStyle.CHARACTER_ATTRIBUTE_DIM),
            23 to TextStyle.CHARACTER_ATTRIBUTE_ITALIC,
            25 to TextStyle.CHARACTER_ATTRIBUTE_BLINK,
            27 to TextStyle.CHARACTER_ATTRIBUTE_INVERSE,
            28 to TextStyle.CHARACTER_ATTRIBUTE_INVISIBLE,
            29 to TextStyle.CHARACTER_ATTRIBUTE_STRIKETHROUGH,
        )

        private fun mapDecSetBitToInternalBit(decsetBit: Int): Int = when (decsetBit) {
            1 -> DECSET_BIT_APPLICATION_CURSOR_KEYS
            5 -> DECSET_BIT_REVERSE_VIDEO
            6 -> DECSET_BIT_ORIGIN_MODE
            7 -> DECSET_BIT_AUTOWRAP
            25 -> DECSET_BIT_CURSOR_ENABLED
            66 -> DECSET_BIT_APPLICATION_KEYPAD
            69 -> DECSET_BIT_LEFTRIGHT_MARGIN_MODE
            1000 -> DECSET_BIT_MOUSE_TRACKING_PRESS_RELEASE
            1002 -> DECSET_BIT_MOUSE_TRACKING_BUTTON_EVENT
            1004 -> DECSET_BIT_SEND_FOCUS_EVENTS
            1006 -> DECSET_BIT_MOUSE_PROTOCOL_SGR
            2004 -> DECSET_BIT_BRACKETED_PASTE_MODE
            else -> -1
        }
    }
}
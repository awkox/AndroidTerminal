package com.awkoo.libterminal.view.textselection

import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.PopupWindow
import androidx.appcompat.content.res.AppCompatResources.getDrawable
import com.awkoo.libterminal.R
import com.awkoo.libterminal.view.TerminalView
import kotlin.math.roundToInt

internal class TextSelectionHandleView(
    private val terminalView: TerminalView,
    private val cursorController: CursorController,
    private val initialOrientation: Int
) : View(terminalView.context) {
    
    private var popup: PopupWindow? = null
    private lateinit var handleDrawable: Drawable

    var isDragging: Boolean = false
        private set

    val tempCoords: IntArray = IntArray(2)

    private var pointX = 0
    private var pointY = 0
    private var touchToWindowOffsetX = 0f
    private var touchToWindowOffsetY = 0f
    private var hotspotX = 0f
    private var lastParentX = 0
    private var lastParentY = 0

    var handleHeight: Int = 0
        private set
    var handleWidth: Int = 0
        private set

    private var orientation = 0

    init {
        setOrientation(initialOrientation)
    }

    private fun initHandle() {
        popup = PopupWindow(
            terminalView.context, null,
            android.R.attr.textSelectHandleWindowStyle
        ).also {
            it.isSplitTouchEnabled = true
            it.isClippingEnabled = false
            it.width = ViewGroup.LayoutParams.WRAP_CONTENT
            it.height = ViewGroup.LayoutParams.WRAP_CONTENT
            it.setBackgroundDrawable(null)
            it.animationStyle = 0
            it.windowLayoutType = WindowManager.LayoutParams.TYPE_APPLICATION_SUB_PANEL
            it.enterTransition = null
            it.exitTransition = null
            it.contentView = this
        }
    }

    fun setOrientation(orientation: Int) {
        val drawableId = when (orientation) {
            LEFT -> R.drawable.text_select_handle_left_material
            RIGHT -> R.drawable.text_select_handle_right_material
            else -> return
        }
        this@TextSelectionHandleView.orientation = orientation
        val handleDrawable = requireNotNull(getDrawable(context, drawableId))
        this@TextSelectionHandleView.handleDrawable = handleDrawable
        this.handleHeight = handleDrawable.intrinsicHeight
        this.handleWidth = handleDrawable.intrinsicWidth
        hotspotX =
            if (orientation == LEFT) (handleWidth * 3) / 4f else handleWidth / 4f
        invalidate()
    }

    fun show() {
        removeFromParent()
        initHandle()
        invalidate()

        val coords = windowCoords()
        coords[0] += pointX
        coords[1] += pointY

        popup?.showAtLocation(terminalView, 0, coords[0], coords[1])
    }

    fun hide() {
        this.isDragging = false
        popup?.let {
            it.dismiss()
            removeFromParent()
        }
        popup = null
        invalidate()
    }

    fun removeFromParent() {
        (parent as? ViewGroup)?.removeView(this)
    }

    // [新增] 统一更新布局的方法，剥离计算逻辑，由外部直接控制
    fun updateLayout(x: Int, y: Int, isVisible: Boolean, orientation: Int) {
        val oldHotspotX = hotspotX
        if (this@TextSelectionHandleView.orientation != orientation) {
            setOrientation(orientation)
        }
        
        pointX = (x - (if (this.isShowing) oldHotspotX else hotspotX)).toInt()
        pointY = y

        if (!isVisible && !isDragging) {
            hide()
            return
        }

        if (this.isShowing) {
            val coords = windowCoords()
            popup?.update(coords[0] + pointX, coords[1] + pointY, width, height)
        } else {
            show()
        }

        if (this.isDragging) {
            val coords = windowCoords()
            if (coords[0] != lastParentX || coords[1] != lastParentY) {
                touchToWindowOffsetX += (coords[0] - lastParentX).toFloat()
                touchToWindowOffsetY += (coords[1] - lastParentY).toFloat()
                lastParentX = coords[0]
                lastParentY = coords[1]
            }
        }
    }

    private fun windowCoords(): IntArray =
        tempCoords.also { terminalView.getLocationInWindow(it) }

    public override fun onDraw(c: Canvas) {
        val width = handleDrawable.intrinsicWidth
        val height = handleDrawable.intrinsicHeight
        handleDrawable.setBounds(0, 0, width, height)
        handleDrawable.draw(c)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        terminalView.updateFloatingToolbarVisibility(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val rawX = event.rawX
                val rawY = event.rawY
                touchToWindowOffsetX = rawX - pointX
                touchToWindowOffsetY = rawY - pointY
                val coords = windowCoords()
                lastParentX = coords[0]
                lastParentY = coords[1]
                this.isDragging = true
            }

            MotionEvent.ACTION_MOVE -> {
                val rawX = event.rawX
                val rawY = event.rawY
                val newPosX = rawX - touchToWindowOffsetX + hotspotX
                val newPosY = rawY - touchToWindowOffsetY

                cursorController.updatePosition(this, newPosX.roundToInt(), newPosY.roundToInt())
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> this.isDragging = false
        }
        return true
    }

    public override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(handleDrawable.intrinsicWidth, handleDrawable.intrinsicHeight)
    }

    val isShowing: Boolean get() = popup?.isShowing ?: false

    companion object {
        const val LEFT: Int = 0
        const val RIGHT: Int = 2
    }
}
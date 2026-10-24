package com.awkoo.libterminal.view.input

import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector

/** [GestureDetector] 与 [ScaleGestureDetector] 的组合封装。 */
internal class GestureAndScaleRecognizer(context: Context, val listener: Listener) {
    interface Listener {
        fun onSingleTapUp(e: MotionEvent): Boolean

        fun onDoubleTap(e: MotionEvent): Boolean

        fun onScroll(e: MotionEvent, dx: Float, dy: Float): Boolean

        fun onFling(e: MotionEvent, velocityX: Float, velocityY: Float): Boolean

        fun onScale(focusX: Float, focusY: Float, scale: Float): Boolean

        fun onDown(x: Float, y: Float): Boolean

        fun onUp(e: MotionEvent): Boolean

        fun onLongPress(e: MotionEvent)
    }

    private val gestureDetector: GestureDetector
    private val scaleDetector: ScaleGestureDetector
    var isAfterLongPress: Boolean = false

    init {
        gestureDetector =
            GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
                override fun onScroll(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    dx: Float,
                    dy: Float
                ): Boolean {
                    return listener.onScroll(e2, dx, dy)
                }

                override fun onFling(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    velocityX: Float,
                    velocityY: Float
                ): Boolean {
                    return listener.onFling(e2, velocityX, velocityY)
                }

                override fun onDown(e: MotionEvent): Boolean {
                    return listener.onDown(e.x, e.y)
                }

                override fun onLongPress(e: MotionEvent) {
                    listener.onLongPress(e)
                    isAfterLongPress = true
                }
            }, null, true /* ignoreMultitouch */)

        gestureDetector.setOnDoubleTapListener(object : GestureDetector.OnDoubleTapListener {
            override fun onSingleTapConfirmed(p0: MotionEvent): Boolean =
                isAfterLongPress || listener.onSingleTapUp(p0)

            override fun onDoubleTap(p0: MotionEvent): Boolean = listener.onDoubleTap(p0)

            override fun onDoubleTapEvent(p0: MotionEvent): Boolean = true
        })

        scaleDetector = ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                    return true
                }

                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    return listener.onScale(
                        detector.focusX,
                        detector.focusY,
                        detector.scaleFactor
                    )
                }
            })
        scaleDetector.isQuickScaleEnabled = false
    }

    fun onTouchEvent(event: MotionEvent) {
        gestureDetector.onTouchEvent(event)
        scaleDetector.onTouchEvent(event)
        when (event.action) {
            MotionEvent.ACTION_DOWN -> isAfterLongPress = false

            // 在 vim 等支持鼠标事件的场景中，长按后抬起不应移动光标
            MotionEvent.ACTION_UP if !isAfterLongPress -> listener.onUp(event)
        }
    }

    val isInProgress: Boolean
        get() = scaleDetector.isInProgress
}
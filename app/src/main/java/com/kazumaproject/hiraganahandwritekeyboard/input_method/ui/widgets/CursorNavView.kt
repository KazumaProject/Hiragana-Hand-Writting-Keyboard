// app/src/main/java/com/kazumaproject/hiraganahandwritekeyboard/input_method/ui/widgets/CursorNavView.kt
package com.kazumaproject.hiraganahandwritekeyboard.input_method.ui.widgets

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.Button
import android.widget.LinearLayout
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.max

class CursorNavView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    enum class Side { LEFT, RIGHT }

    enum class Action {
        TAP,
        FLICK_UP,
        FLICK_DOWN,
        LONG_TAP,
        LONG_FLICK_UP,
        LONG_FLICK_DOWN
    }

    interface Listener {
        fun onAction(side: Side, action: Action)
    }

    enum class UiMode { LIGHT, DARK }

    data class Style(
        @DrawableRes val backgroundResId: Int? = null,
        @ColorRes val textColorResId: Int? = null
    )

    private var listener: Listener? = null
    private var uiMode: UiMode = UiMode.LIGHT
    private var lightStyle: Style? = null
    private var darkStyle: Style? = null

    fun setListener(l: Listener?) {
        listener = l
    }

    fun setStyles(light: Style?, dark: Style?) {
        lightStyle = light
        darkStyle = dark
        applyCurrentStyle()
    }

    fun setUiMode(mode: UiMode) {
        if (uiMode == mode) return
        uiMode = mode
        applyCurrentStyle()
    }

    private val leftBtn: Button
    private val rightBtn: Button
    private var buttonHeightPx: Int = dpToPx(44f)

    init {
        orientation = HORIZONTAL

        leftBtn = Button(context).apply {
            text = "◀"
            isAllCaps = false
            setPadding(0, 0, 0, 0)
            minWidth = 0
            minimumWidth = 0
        }

        rightBtn = Button(context).apply {
            text = "▶"
            isAllCaps = false
            setPadding(0, 0, 0, 0)
            minWidth = 0
            minimumWidth = 0
        }

        applyButtonHeightInternal(buttonHeightPx)

        addView(leftBtn)
        addView(rightBtn)

        leftBtn.setOnTouchListener(NavTouchHandler(Side.LEFT))
        rightBtn.setOnTouchListener(NavTouchHandler(Side.RIGHT))
    }

    fun setButtonHeightDp(dp: Float) {
        val px = dpToPx(dp)
        applyButtonHeightInternal(px)
    }

    fun setIconTextSizeSp(sp: Float) {
        leftBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        rightBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
    }

    private fun applyButtonHeightInternal(heightPx: Int) {
        buttonHeightPx = heightPx

        val lpLeft = LayoutParams(0, buttonHeightPx).apply { weight = 1f }
        val lpRight = LayoutParams(0, buttonHeightPx).apply { weight = 1f }

        leftBtn.layoutParams = lpLeft
        rightBtn.layoutParams = lpRight

        leftBtn.minHeight = buttonHeightPx
        rightBtn.minHeight = buttonHeightPx

        requestLayout()
    }

    private fun currentStyle(): Style? {
        return when (uiMode) {
            UiMode.LIGHT -> lightStyle
            UiMode.DARK -> darkStyle
        }
    }

    private fun applyCurrentStyle() {
        val s = currentStyle() ?: return

        s.backgroundResId?.let { resId ->
            leftBtn.setBackgroundResource(resId)
            rightBtn.setBackgroundResource(resId)
        }

        // 要求：@color/clay_text を resId で適用
        s.textColorResId?.let { colorRes ->
            val csl = ContextCompat.getColorStateList(context, colorRes)
            leftBtn.setTextColor(csl)
            rightBtn.setTextColor(csl)
        }

        invalidate()
    }

    private inner class NavTouchHandler(
        private val side: Side
    ) : OnTouchListener {

        private val main = Handler(Looper.getMainLooper())

        private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        private val flickThreshold = max(touchSlop, dpToPx(18f))

        private var downX = 0f
        private var downY = 0f
        private var activePointerId = -1

        private var decidedAction: Action? = null
        private var longPressed = false

        private var repeating = false
        private var repeatingAction: Action = Action.LONG_TAP
        private val repeatIntervalMs = 60L

        private val longPressRunnable = Runnable {
            if (activePointerId == -1) return@Runnable
            longPressed = true

            val lpAction = when (decidedAction) {
                Action.FLICK_UP -> Action.LONG_FLICK_UP
                Action.FLICK_DOWN -> Action.LONG_FLICK_DOWN
                else -> Action.LONG_TAP
            }
            startRepeating(lpAction)
        }

        private val repeatRunnable = object : Runnable {
            override fun run() {
                if (!repeating) return
                listener?.onAction(side, repeatingAction)
                main.postDelayed(this, repeatIntervalMs)
            }
        }

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    activePointerId = event.getPointerId(0)
                    downX = event.x
                    downY = event.y

                    decidedAction = null
                    longPressed = false
                    repeating = false
                    repeatingAction = Action.LONG_TAP

                    main.postDelayed(longPressRunnable, longPressTimeout)
                    v.isPressed = true
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (activePointerId == -1) return false

                    val dx = event.x - downX
                    val dy = event.y - downY

                    if (!longPressed) {
                        if (abs(dy) >= flickThreshold && abs(dy) > abs(dx)) {
                            decidedAction = if (dy < 0) Action.FLICK_UP else Action.FLICK_DOWN
                        } else if (abs(dx) >= touchSlop || abs(dy) >= touchSlop) {
                            if (decidedAction == null) decidedAction = Action.TAP
                        }
                    } else {
                        if (abs(dy) >= flickThreshold && abs(dy) > abs(dx)) {
                            repeatingAction =
                                if (dy < 0) Action.LONG_FLICK_UP else Action.LONG_FLICK_DOWN
                            decidedAction = if (dy < 0) Action.FLICK_UP else Action.FLICK_DOWN
                        } else {
                            repeatingAction = Action.LONG_TAP
                            decidedAction = Action.TAP
                        }
                    }
                    return true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    main.removeCallbacks(longPressRunnable)

                    if (repeating) {
                        stopRepeating()
                    } else if (!longPressed) {
                        val act = decidedAction ?: Action.TAP
                        val fire = when (act) {
                            Action.FLICK_UP -> Action.FLICK_UP
                            Action.FLICK_DOWN -> Action.FLICK_DOWN
                            else -> Action.TAP
                        }
                        listener?.onAction(side, fire)
                    }

                    activePointerId = -1
                    decidedAction = null
                    longPressed = false
                    return true
                }
            }
            return false
        }

        private fun startRepeating(first: Action) {
            repeating = true
            repeatingAction = first
            listener?.onAction(side, repeatingAction)
            main.postDelayed(repeatRunnable, repeatIntervalMs)
        }

        private fun stopRepeating() {
            repeating = false
            main.removeCallbacks(repeatRunnable)
        }
    }

    private fun dpToPx(dp: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            resources.displayMetrics
        ).toInt()
    }
}

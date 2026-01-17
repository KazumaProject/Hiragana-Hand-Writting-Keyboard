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
import kotlin.math.abs
import kotlin.math.max

class CursorNavView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    enum class Side { LEFT, RIGHT }

    /**
     * - TAP: タップ
     * - FLICK_UP / FLICK_DOWN: フリック上下
     * - LONG_*: 長押し中のリピートイベント（View側で繰り返し発火）
     */
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

    private var listener: Listener? = null

    fun setListener(l: Listener?) {
        listener = l
    }

    private val leftBtn: Button
    private val rightBtn: Button

    init {
        orientation = HORIZONTAL

        val h = dpToPx(44f)
        val lp = LayoutParams(0, h).apply { weight = 1f }

        leftBtn = Button(context).apply {
            text = "◀"
            minHeight = h
            layoutParams = lp
        }

        rightBtn = Button(context).apply {
            text = "▶"
            minHeight = h
            layoutParams = lp
        }

        addView(leftBtn)
        addView(rightBtn)

        leftBtn.setOnTouchListener(NavTouchHandler(Side.LEFT))
        rightBtn.setOnTouchListener(NavTouchHandler(Side.RIGHT))
    }

    private inner class NavTouchHandler(
        private val side: Side
    ) : OnTouchListener {

        private val main = Handler(Looper.getMainLooper())

        private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        private val flickThreshold = max(touchSlop, dpToPx(18f)) // 上下フリック判定の閾値

        private var downX = 0f
        private var downY = 0f
        private var activePointerId = -1

        private var decidedAction: Action? = null
        private var longPressed = false

        // 長押しリピート
        private var repeating = false
        private var repeatingAction: Action = Action.LONG_TAP
        private val repeatIntervalMs = 60L

        private val longPressRunnable = Runnable {
            if (activePointerId == -1) return@Runnable
            longPressed = true

            // 長押し開始時点の方向に応じて種別決定（未決定ならタップ長押し）
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
                        // まだ長押しに入っていない段階：縦方向が明確なら決定
                        if (abs(dy) >= flickThreshold && abs(dy) > abs(dx)) {
                            decidedAction = if (dy < 0) Action.FLICK_UP else Action.FLICK_DOWN
                        } else if (abs(dx) >= touchSlop || abs(dy) >= touchSlop) {
                            // 動いたが縦フリック条件を満たさない → タップ扱いを維持
                            if (decidedAction == null) decidedAction = Action.TAP
                        }
                    } else {
                        // 長押し中：上下方向の長押し種別を動的に切替
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
                        // 単発イベント
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
            // 即時1回 → 以降リピート
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

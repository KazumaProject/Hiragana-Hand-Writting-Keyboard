package com.kazumaproject.hiraganahandwritekeyboard.input_method.ui.widgets

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import com.kazumaproject.hiraganahandwritekeyboard.input_method.ui.ImeController
import kotlin.math.abs
import kotlin.math.max

class KeyboardKeyRowsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private val buttonMap: MutableMap<String, Button> = linkedMapOf()
    private val viewMap: MutableMap<String, View> = linkedMapOf()

    init {
        orientation = VERTICAL
    }

    fun setRows(rows: List<List<KeyboardKeySpec>>, controller: ImeController) {
        removeAllViews()
        buttonMap.clear()
        viewMap.clear()

        rows.forEach { row ->
            val rowLayout = LinearLayout(context).apply {
                orientation = HORIZONTAL
                layoutParams = LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }

            row.forEach { spec ->
                val child = when (spec) {
                    is KeyboardKeySpec.ButtonKey -> {
                        Button(context).apply {
                            text = spec.text
                            isEnabled = spec.enabled
                            minHeight = dpToPx(spec.minHeightDp)
                            layoutParams = LayoutParams(
                                0,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                spec.weight
                            )

                            if (spec.enableGestures) {
                                setOnTouchListener(ButtonKeyTouchListener(controller, spec))
                            } else {
                                setOnClickListener { spec.onClick(controller) }
                            }
                        }.also { b ->
                            buttonMap[spec.keyId] = b
                            viewMap[spec.keyId] = b
                        }
                    }

                    is KeyboardKeySpec.CustomViewKey -> {
                        val v = spec.createView(context, rowLayout, controller).apply {
                            layoutParams = LayoutParams(
                                0,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                spec.weight
                            )
                            minimumHeight = dpToPx(spec.minHeightDp)
                            isEnabled = spec.enabled
                        }

                        // CustomView に RowsView 側でジェスチャーを付与したい場合のみ
                        if (spec.enableGestures && spec.onGesture != null) {
                            v.isClickable = true
                            v.setOnTouchListener(CustomViewTouchListener(controller, spec))
                        }

                        viewMap[spec.keyId] = v
                        v
                    }
                }

                rowLayout.addView(child)
            }

            addView(rowLayout)
        }
    }

    fun getButton(keyId: String): Button? = buttonMap[keyId]
    fun getView(keyId: String): View? = viewMap[keyId]

    // ---------------- Touch Listeners ----------------

    private inner class ButtonKeyTouchListener(
        private val controller: ImeController,
        private val spec: KeyboardKeySpec.ButtonKey
    ) : OnTouchListener {

        private val main = Handler(Looper.getMainLooper())
        private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        private val flickThresholdPx = max(touchSlop, dpToPx(spec.flickThresholdDp))

        private var downX = 0f
        private var downY = 0f
        private var longPressed = false
        private var repeating = false
        private var decided: KeyboardKeyGesture = KeyboardKeyGesture.TAP

        private val longPressRunnable = Runnable {
            longPressed = true
            // 長押し開始時点の上下方向で LONG_* 種別を決める（押し続け中は move で更新する）
            val g = when (decided) {
                KeyboardKeyGesture.FLICK_UP -> KeyboardKeyGesture.LONG_FLICK_UP
                KeyboardKeyGesture.FLICK_DOWN -> KeyboardKeyGesture.LONG_FLICK_DOWN
                else -> KeyboardKeyGesture.LONG_TAP
            }

            if (spec.repeatOnLongPress) {
                startRepeating(g)
            } else {
                fire(g)
            }
        }

        private val repeatRunnable = object : Runnable {
            override fun run() {
                if (!repeating) return
                fire(
                    when (decided) {
                        KeyboardKeyGesture.FLICK_UP -> KeyboardKeyGesture.LONG_FLICK_UP
                        KeyboardKeyGesture.FLICK_DOWN -> KeyboardKeyGesture.LONG_FLICK_DOWN
                        else -> KeyboardKeyGesture.LONG_TAP
                    }
                )
                main.postDelayed(this, spec.repeatIntervalMs)
            }
        }

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    longPressed = false
                    repeating = false
                    decided = KeyboardKeyGesture.TAP

                    v.isPressed = true
                    main.postDelayed(longPressRunnable, longPressTimeout)
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - downX
                    val dy = event.y - downY

                    // 上下が明確なら flick とみなす（左右優勢なら TAP 扱い）
                    decided = if (abs(dy) >= flickThresholdPx && abs(dy) > abs(dx)) {
                        if (dy < 0) KeyboardKeyGesture.FLICK_UP else KeyboardKeyGesture.FLICK_DOWN
                    } else {
                        KeyboardKeyGesture.TAP
                    }
                    return true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    main.removeCallbacks(longPressRunnable)

                    if (repeating) {
                        stopRepeating()
                    } else if (!longPressed) {
                        // 単発：UP時に gesture を確定して発火
                        fire(decided)
                    }

                    return true
                }
            }
            return false
        }

        private fun startRepeating(first: KeyboardKeyGesture) {
            repeating = true
            fire(first) // 即時1回
            main.postDelayed(repeatRunnable, spec.repeatIntervalMs)
        }

        private fun stopRepeating() {
            repeating = false
            main.removeCallbacks(repeatRunnable)
        }

        private fun fire(g: KeyboardKeyGesture) {
            when (g) {
                KeyboardKeyGesture.TAP,
                KeyboardKeyGesture.LONG_TAP -> spec.onClick(controller)

                KeyboardKeyGesture.FLICK_UP,
                KeyboardKeyGesture.LONG_FLICK_UP -> spec.onFlickUp?.invoke(controller)

                KeyboardKeyGesture.FLICK_DOWN,
                KeyboardKeyGesture.LONG_FLICK_DOWN -> spec.onFlickDown?.invoke(controller)
            }
        }
    }

    private inner class CustomViewTouchListener(
        private val controller: ImeController,
        private val spec: KeyboardKeySpec.CustomViewKey
    ) : OnTouchListener {

        private val main = Handler(Looper.getMainLooper())
        private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        private val flickThresholdPx = max(touchSlop, dpToPx(spec.flickThresholdDp))

        private var downX = 0f
        private var downY = 0f
        private var longPressed = false
        private var repeating = false
        private var decided: KeyboardKeyGesture = KeyboardKeyGesture.TAP

        private val longPressRunnable = Runnable {
            longPressed = true
            val g = when (decided) {
                KeyboardKeyGesture.FLICK_UP -> KeyboardKeyGesture.LONG_FLICK_UP
                KeyboardKeyGesture.FLICK_DOWN -> KeyboardKeyGesture.LONG_FLICK_DOWN
                else -> KeyboardKeyGesture.LONG_TAP
            }

            if (spec.repeatOnLongPress) {
                startRepeating(g)
            } else {
                spec.onGesture?.invoke(controller, g)
            }
        }

        private val repeatRunnable = object : Runnable {
            override fun run() {
                if (!repeating) return
                val g = when (decided) {
                    KeyboardKeyGesture.FLICK_UP -> KeyboardKeyGesture.LONG_FLICK_UP
                    KeyboardKeyGesture.FLICK_DOWN -> KeyboardKeyGesture.LONG_FLICK_DOWN
                    else -> KeyboardKeyGesture.LONG_TAP
                }
                spec.onGesture?.invoke(controller, g)
                main.postDelayed(this, spec.repeatIntervalMs)
            }
        }

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    longPressed = false
                    repeating = false
                    decided = KeyboardKeyGesture.TAP

                    v.isPressed = true
                    main.postDelayed(longPressRunnable, longPressTimeout)
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - downX
                    val dy = event.y - downY

                    decided = if (abs(dy) >= flickThresholdPx && abs(dy) > abs(dx)) {
                        if (dy < 0) KeyboardKeyGesture.FLICK_UP else KeyboardKeyGesture.FLICK_DOWN
                    } else {
                        KeyboardKeyGesture.TAP
                    }
                    return true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    main.removeCallbacks(longPressRunnable)

                    if (repeating) {
                        stopRepeating()
                    } else if (!longPressed) {
                        spec.onGesture?.invoke(controller, decided)
                    }
                    return true
                }
            }
            return false
        }

        private fun startRepeating(first: KeyboardKeyGesture) {
            repeating = true
            spec.onGesture?.invoke(controller, first)
            main.postDelayed(repeatRunnable, spec.repeatIntervalMs)
        }

        private fun stopRepeating() {
            repeating = false
            main.removeCallbacks(repeatRunnable)
        }
    }

    // ---------------- util ----------------

    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            resources.displayMetrics
        ).toInt()
    }
}

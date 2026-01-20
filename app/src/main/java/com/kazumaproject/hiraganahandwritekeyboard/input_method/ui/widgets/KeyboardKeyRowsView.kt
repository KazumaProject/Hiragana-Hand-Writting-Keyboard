// app/src/main/java/com/kazumaproject/hiraganahandwritekeyboard/input_method/ui/widgets/KeyboardKeyRowsView.kt
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
import androidx.core.content.ContextCompat
import com.kazumaproject.hiraganahandwritekeyboard.input_method.ui.ImeController
import kotlin.math.abs
import kotlin.math.max

class KeyboardKeyRowsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    enum class UiMode { LIGHT, DARK }

    private val buttonMap: MutableMap<String, Button> = linkedMapOf()
    private val viewMap: MutableMap<String, View> = linkedMapOf()
    private val rowLayouts: MutableList<LinearLayout> = mutableListOf()

    private var lastRows: List<List<KeyboardKeySpec>> = emptyList()
    private var lastController: ImeController? = null

    private var uiMode: UiMode = UiMode.LIGHT
    private var defaultLightStyle: KeyboardKeySpec.ButtonStyle? = null
    private var defaultDarkStyle: KeyboardKeySpec.ButtonStyle? = null

    init {
        orientation = VERTICAL
        setPadding(0, 0, 0, 0)
        clipToPadding = false
        clipChildren = false
    }

    /**
     * RowsView が生成する Button のデフォルト見た目（ライト/ダーク）を設定する。
     * setRows() の前後どちらでもOK（後から呼ぶと生成済みに再適用）
     */
    fun setDefaultButtonStyles(
        light: KeyboardKeySpec.ButtonStyle?,
        dark: KeyboardKeySpec.ButtonStyle?
    ) {
        defaultLightStyle = light
        defaultDarkStyle = dark
        applyCurrentStylesToAllButtons()
    }

    /**
     * ライト/ダーク切替（生成済みボタンにも反映）
     */
    fun setUiMode(mode: UiMode) {
        if (uiMode == mode) return
        uiMode = mode
        applyCurrentStylesToAllButtons()
    }

    fun setRows(rows: List<List<KeyboardKeySpec>>, controller: ImeController) {
        lastRows = rows
        lastController = controller

        removeAllViews()
        buttonMap.clear()
        viewMap.clear()
        rowLayouts.clear()

        setPadding(0, 0, 0, 0)

        rows.forEachIndexed { rowIndex, row ->
            val rowLayout = LinearLayout(context).apply {
                orientation = HORIZONTAL
                setPadding(0, 0, 0, 0)
                clipToPadding = false
                clipChildren = false
                layoutParams = LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                tag = "row_$rowIndex"
            }
            rowLayouts.add(rowLayout)

            row.forEach { spec ->
                val child = when (spec) {
                    is KeyboardKeySpec.ButtonKey -> {
                        Button(context).apply {
                            text = spec.text
                            isEnabled = spec.enabled

                            minHeight = 0
                            minimumHeight = 0

                            layoutParams = LayoutParams(
                                0,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                spec.weight
                            )

                            setPadding(0, 0, 0, 0)
                            gravity = android.view.Gravity.CENTER

                            // ★ここで background / textColor を resId で適用（要求対応）
                            applyStyleToButton(this, spec.style)

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
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                spec.weight
                            )
                            minimumHeight = 0
                            isEnabled = spec.enabled
                            setPadding(0, 0, 0, 0)
                        }

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

        post { distributeRowHeights() }
    }

    fun getButton(keyId: String): Button? = buttonMap[keyId]
    fun getView(keyId: String): View? = viewMap[keyId]

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (h != oldh) post { distributeRowHeights() }
    }

    private fun distributeRowHeights() {
        val totalH = height - paddingTop - paddingBottom
        if (totalH <= 0) return
        val rowsCount = rowLayouts.size
        if (rowsCount <= 0) return

        val base = totalH / rowsCount
        val rem = totalH % rowsCount

        rowLayouts.forEachIndexed { idx, rowLayout ->
            val rowH = base + if (idx < rem) 1 else 0
            val lp = rowLayout.layoutParams as LayoutParams
            lp.height = rowH
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT
            rowLayout.layoutParams = lp

            for (i in 0 until rowLayout.childCount) {
                val child = rowLayout.getChildAt(i)
                val clp = child.layoutParams as LayoutParams
                clp.height = ViewGroup.LayoutParams.MATCH_PARENT
                child.layoutParams = clp

                if (child is Button) {
                    child.minHeight = 0
                    child.minimumHeight = 0
                }
            }
        }
        requestLayout()
    }

    // ---------------- Style application (resId) ----------------

    private fun currentDefaultStyle(): KeyboardKeySpec.ButtonStyle? {
        return when (uiMode) {
            UiMode.LIGHT -> defaultLightStyle
            UiMode.DARK -> defaultDarkStyle
        }
    }

    /**
     * spec.style（キー個別）を優先、なければ RowsView のデフォルトを適用
     */
    private fun applyStyleToButton(btn: Button, override: KeyboardKeySpec.ButtonStyle?) {
        val style = override ?: currentDefaultStyle() ?: return

        style.backgroundResId?.let { resId ->
            btn.setBackgroundResource(resId)
        }

        // 要求：android:textColor="@color/clay_text" 相当を resId で適用
        style.textColorResId?.let { colorRes ->
            btn.setTextColor(ContextCompat.getColorStateList(context, colorRes))
        }
    }

    private fun applyCurrentStylesToAllButtons() {
        val rows = lastRows
        if (rows.isEmpty()) return

        rows.flatten().forEach { spec ->
            if (spec is KeyboardKeySpec.ButtonKey) {
                buttonMap[spec.keyId]?.let { b ->
                    applyStyleToButton(b, spec.style)
                }
            }
        }
        invalidate()
    }

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
                        fire(decided)
                    }
                    return true
                }
            }
            return false
        }

        private fun startRepeating(first: KeyboardKeyGesture) {
            repeating = true
            fire(first)
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

    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            resources.displayMetrics
        ).toInt()
    }
}

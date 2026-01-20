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
import androidx.core.view.setMargins
import com.kazumaproject.hiraganahandwritekeyboard.R
import com.kazumaproject.hiraganahandwritekeyboard.input_method.ui.ImeController
import kotlin.math.abs
import kotlin.math.max

class KeyboardKeyRowsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private val buttonMap: MutableMap<String, Button> = linkedMapOf()
    private val viewMap: MutableMap<String, View> = linkedMapOf()

    // 生成した rowLayout を保持（高さ再配分に使う）
    private val rowLayouts: MutableList<LinearLayout> = mutableListOf()

    // 最後にセットされた rows を保持（回転/サイズ変更などで再配分するため）
    private var lastRows: List<List<KeyboardKeySpec>> = emptyList()
    private var lastController: ImeController? = null

    init {
        orientation = VERTICAL
        // 上部に“余白があるように見える”のを抑止
        setPadding(0, 0, 0, 0)
        clipToPadding = false
        clipChildren = false
    }

    fun setRows(rows: List<List<KeyboardKeySpec>>, controller: ImeController) {
        lastRows = rows
        lastController = controller

        removeAllViews()
        buttonMap.clear()
        viewMap.clear()
        rowLayouts.clear()

        // RowsView 自体の padding が XML 等で入っても見た目が崩れないよう、ここで強制的に 0
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

                            // ここは「固定minHeight」を捨てて、親の再配分に追従させる
                            minHeight = 0
                            minimumHeight = 0

                            // 横幅は weight、縦は row の高さに合わせる（後で MATCH_PARENT に揃える）
                            layoutParams = LayoutParams(
                                0,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                spec.weight,
                            ).apply {
                                setMargins(4)
                            }

                            // 見た目の余白を減らす（上部が空いて見える原因になりやすい）
                            setPadding(0, 0, 0, 0)
                            gravity = android.view.Gravity.CENTER

                            background = ContextCompat.getDrawable(
                                context, R.drawable.clay_button_bg
                            )

                            setTextColor(
                                ContextCompat.getColor(
                                    context, R.color.clay_text
                                )
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
                            // 縦は row の高さに追従させたいので MATCH_PARENT
                            layoutParams = LayoutParams(
                                0,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                spec.weight
                            )

                            // minHeight は RowsView の配分に邪魔になるので 0
                            minimumHeight = 0
                            isEnabled = spec.enabled

                            setPadding(0, 0, 0, 0)
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

        // レイアウト確定後に、RowsView の実高さに合わせて行の高さを配分
        post { distributeRowHeights() }
    }

    fun getButton(keyId: String): Button? = buttonMap[keyId]
    fun getView(keyId: String): View? = viewMap[keyId]

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (h != oldh) {
            // パネルリサイズ等で高さが変わったら再配分
            post { distributeRowHeights() }
        }
    }

    /**
     * RowsView の実高さを rowsCount で割って、各 rowLayout の高さを再配分する。
     * - ボタン数が多くても必ず収まる（小さくはなる）
     * - 上に余白があるように見える問題も、row を上から詰めるので抑制される
     */
    private fun distributeRowHeights() {
        val totalH = height - paddingTop - paddingBottom
        if (totalH <= 0) return
        val rowsCount = rowLayouts.size
        if (rowsCount <= 0) return

        // 均等割り（余りは上から順に +1px）
        val base = totalH / rowsCount
        val rem = totalH % rowsCount

        rowLayouts.forEachIndexed { idx, rowLayout ->
            val rowH = base + if (idx < rem) 1 else 0
            val lp = rowLayout.layoutParams as LayoutParams
            lp.height = rowH
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT
            rowLayout.layoutParams = lp

            // 子は row 高さに合わせて縦に満たす
            for (i in 0 until rowLayout.childCount) {
                val child = rowLayout.getChildAt(i)
                val clp = child.layoutParams as LayoutParams
                clp.height = ViewGroup.LayoutParams.MATCH_PARENT
                child.layoutParams = clp

                // Button の minHeight が残っていると押し戻すことがあるので保険で 0
                if (child is Button) {
                    child.minHeight = 0
                    child.minimumHeight = 0
                }
            }
        }
        requestLayout()
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

    // ---------------- util ----------------

    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            resources.displayMetrics
        ).toInt()
    }
}

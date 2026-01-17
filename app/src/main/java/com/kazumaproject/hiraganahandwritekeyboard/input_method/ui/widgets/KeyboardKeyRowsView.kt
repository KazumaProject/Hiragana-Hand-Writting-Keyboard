package com.kazumaproject.hiraganahandwritekeyboard.input_method.ui.widgets

import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import com.kazumaproject.hiraganahandwritekeyboard.input_method.ui.ImeController

class KeyboardKeyRowsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private val buttonMap: MutableMap<String, Button> = linkedMapOf()
    private var boundController: ImeController? = null

    var rowGapPx: Int = 0
        set(value) {
            field = value
            requestLayout()
        }

    init {
        orientation = VERTICAL
    }

    fun setRows(rows: List<List<KeyboardKeySpec>>, controller: ImeController) {
        boundController = controller
        removeAllViews()
        buttonMap.clear()

        rows.forEachIndexed { idx, row ->
            val rowLayout = LinearLayout(context).apply {
                orientation = HORIZONTAL
                layoutParams = LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                if (idx > 0 && rowGapPx > 0) {
                    (layoutParams as LayoutParams).topMargin = rowGapPx
                }
            }

            row.forEach { spec ->
                val b = Button(context).apply {
                    text = spec.text
                    isEnabled = spec.enabled
                    minHeight = dpToPx(spec.minHeightDp)

                    layoutParams = LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        spec.weight
                    )

                    setOnClickListener {
                        boundController?.let { c -> spec.onClick(c) }
                    }
                }

                buttonMap[spec.keyId] = b
                rowLayout.addView(b)
            }

            addView(rowLayout)
        }
    }

    fun setKeyText(keyId: String, newText: String) {
        buttonMap[keyId]?.text = newText
    }

    fun setKeyEnabled(keyId: String, enabled: Boolean) {
        buttonMap[keyId]?.isEnabled = enabled
    }

    fun getButton(keyId: String): Button? = buttonMap[keyId]

    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            resources.displayMetrics
        ).toInt()
    }
}

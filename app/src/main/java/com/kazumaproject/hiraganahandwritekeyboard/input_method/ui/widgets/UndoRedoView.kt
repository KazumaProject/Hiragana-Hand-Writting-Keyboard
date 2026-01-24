package com.kazumaproject.hiraganahandwritekeyboard.input_method.ui.widgets

import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import com.kazumaproject.hiraganahandwritekeyboard.R

class UndoRedoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    interface Listener {
        fun onUndo()
        fun onRedo()
    }

    private var listener: Listener? = null

    fun setListener(l: Listener?) {
        listener = l
    }

    private val undoBtn: Button
    private val redoBtn: Button

    init {
        orientation = HORIZONTAL

        undoBtn = Button(context).apply {
            text = "↶"
            isAllCaps = false
            setPadding(0, 0, 0, 0)
            minWidth = 0
            minimumWidth = 0
            gravity = Gravity.CENTER

            background = ContextCompat.getDrawable(context, R.drawable.clay_button_bg)
            setTextColor(ContextCompat.getColor(context, R.color.clay_text))

            setOnClickListener { listener?.onUndo() }
        }

        redoBtn = Button(context).apply {
            text = "↷"
            isAllCaps = false
            setPadding(0, 0, 0, 0)
            minWidth = 0
            minimumWidth = 0
            gravity = Gravity.CENTER

            background = ContextCompat.getDrawable(context, R.drawable.clay_button_bg)
            setTextColor(ContextCompat.getColor(context, R.color.clay_text))

            setOnClickListener { listener?.onRedo() }
        }

        // RowsView 側が高さ配分するので縦は MATCH_PARENT で追従
        val lpL = LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT).apply { weight = 1f }
        val lpR = LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT).apply { weight = 1f }
        undoBtn.layoutParams = lpL
        redoBtn.layoutParams = lpR

        addView(undoBtn)
        addView(redoBtn)
    }

    fun setEnabledState(canUndo: Boolean, canRedo: Boolean) {
        undoBtn.isEnabled = canUndo
        redoBtn.isEnabled = canRedo

        // 無効時が分かりやすいように（不要なら削除可）
        undoBtn.alpha = if (canUndo) 1f else 0.45f
        redoBtn.alpha = if (canRedo) 1f else 0.45f
    }

    fun setIconTextSizeSp(sp: Float) {
        undoBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        redoBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
    }
}

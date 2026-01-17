package com.kazumaproject.hiraganahandwritekeyboard.hand_writting.ui

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.LinearLayout
import androidx.recyclerview.widget.RecyclerView
import com.kazumaproject.hiraganahandwritekeyboard.databinding.ViewDualDrawingBinding

class DualDrawingComposerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private val vb: ViewDualDrawingBinding =
        ViewDualDrawingBinding.inflate(LayoutInflater.from(context), this, true)

    val viewA: DrawingView get() = vb.drawingViewA
    val viewB: DrawingView get() = vb.drawingViewB

    // ★追加: 候補リスト
    val candidateListA: RecyclerView get() = vb.candidateListA
    val candidateListB: RecyclerView get() = vb.candidateListB

    enum class Side { A, B }

    /**
     * どちらで書き始めたか（ACTION_DOWN）
     */
    var onStrokeStarted: ((Side) -> Unit)? = null

    /**
     * どちらでストローク確定したか（ACTION_UP）
     */
    var onStrokeCommitted: ((Side) -> Unit)? = null

    init {
        orientation = VERTICAL

        vb.drawingViewA.onStrokeStarted = { onStrokeStarted?.invoke(Side.A) }
        vb.drawingViewB.onStrokeStarted = { onStrokeStarted?.invoke(Side.B) }

        vb.drawingViewA.onStrokeCommitted = { onStrokeCommitted?.invoke(Side.A) }
        vb.drawingViewB.onStrokeCommitted = { onStrokeCommitted?.invoke(Side.B) }
    }

    fun setStrokeWidthPx(px: Float) {
        vb.drawingViewA.setStrokeWidthPx(px)
        vb.drawingViewB.setStrokeWidthPx(px)
    }

    fun clearBoth() {
        vb.drawingViewA.clearCanvas()
        vb.drawingViewB.clearCanvas()
    }
}

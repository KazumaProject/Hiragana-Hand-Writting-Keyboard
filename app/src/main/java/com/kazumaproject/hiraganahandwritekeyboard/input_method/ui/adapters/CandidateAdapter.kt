package com.kazumaproject.hiraganahandwritekeyboard.input_method.ui.adapters

import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.kazumaproject.hiraganahandwritekeyboard.R
import com.kazumaproject.kana_kanji_converter.NativeCandidate
import kotlin.math.roundToInt

class CandidateAdapter(
    private val onClick: (NativeCandidate) -> Unit,
    private val onSelectedIndexChanged: ((Int) -> Unit)? = null,
) : RecyclerView.Adapter<CandidateAdapter.VH>() {

    private val items = mutableListOf<NativeCandidate>()
    private var selectedIndex: Int = -1

    /**
     * 重要：
     * - 「候補表示＝自動選択」はしない（selectedIndex は基本 -1 のまま）
     * - CandidateMode に入った瞬間にだけ setSelectedIndex(0) する
     */
    fun submit(newItems: List<NativeCandidate>) {
        val oldSelected = selectedIndex

        items.clear()
        items.addAll(newItems)

        selectedIndex = when {
            items.isEmpty() -> -1
            selectedIndex in items.indices -> selectedIndex
            else -> -1 // 自動で 0 にはしない
        }

        notifyDataSetChanged()

        // 選択が維持されている場合のみ（=-1は除外）、必要ならスクロール
        if (selectedIndex >= 0 && selectedIndex != oldSelected) {
            onSelectedIndexChanged?.invoke(selectedIndex)
        }
    }

    fun indexOfSurface(surface: String): Int = items.indexOfFirst { it.surface == surface }

    fun getSelectedIndex(): Int = selectedIndex

    fun setSelectedIndex(index: Int) {
        val oldSelected = selectedIndex
        val newIdx = if (index in items.indices) index else -1
        if (newIdx == oldSelected) return

        selectedIndex = newIdx
        notifyDataSetChanged()

        if (selectedIndex >= 0) {
            onSelectedIndexChanged?.invoke(selectedIndex)
        }
    }

    fun getSelected(): NativeCandidate? {
        return if (selectedIndex in items.indices) items[selectedIndex] else null
    }

    fun moveNextWrap(): Int {
        val oldSelected = selectedIndex

        if (items.isEmpty()) {
            selectedIndex = -1
            notifyDataSetChanged()
            return -1
        }

        val next = if (selectedIndex !in items.indices) 0 else (selectedIndex + 1) % items.size
        selectedIndex = next
        notifyDataSetChanged()

        if (selectedIndex >= 0 && selectedIndex != oldSelected) {
            onSelectedIndexChanged?.invoke(selectedIndex)
        }
        return next
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val tv = TextView(parent.context).apply {
            setPadding(
                dp(context, 10),
                dp(context, 8),
                dp(context, 10),
                dp(context, 8)
            )
            setTextColor(context.getColor(R.color.clay_text))
            textSize = 16f
        }

        val lp = RecyclerView.LayoutParams(
            RecyclerView.LayoutParams.WRAP_CONTENT,
            RecyclerView.LayoutParams.WRAP_CONTENT
        )
        lp.setMargins(
            dp(parent.context, 6),
            dp(parent.context, 6),
            dp(parent.context, 6),
            dp(parent.context, 6)
        )
        tv.layoutParams = lp

        return VH(tv, onClick)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position], isSelected = (position == selectedIndex))
    }

    override fun getItemCount(): Int = items.size

    class VH(
        private val tv: TextView,
        private val onClick: (NativeCandidate) -> Unit
    ) : RecyclerView.ViewHolder(tv) {

        fun bind(item: NativeCandidate, isSelected: Boolean) {
            tv.text = item.surface
            if (isSelected) {
                tv.setBackgroundColor(Color.argb(80, 59, 130, 246))
            } else {
                tv.setBackgroundColor(Color.argb(35, 0, 0, 0))
            }
            tv.setOnClickListener { onClick(item) }
        }
    }

    private companion object {
        fun dp(context: Context, dp: Int): Int {
            val px = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                dp.toFloat(),
                context.resources.displayMetrics
            )
            return px.roundToInt()
        }
    }
}

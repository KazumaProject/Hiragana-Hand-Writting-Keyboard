package com.kazumaproject.hiraganahandwritekeyboard.hand_writting.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.kazumaproject.hiraganahandwritekeyboard.R
import com.kazumaproject.hiraganahandwritekeyboard.hand_writting.data.CtcCandidate
import kotlin.math.roundToInt

class CtcCandidateAdapter(
    private val onClick: (CtcCandidate) -> Unit
) : ListAdapter<CtcCandidate, CtcCandidateAdapter.VH>(DIFF) {

    class VH(
        private val txt: TextView,
        private val onClick: (Int) -> Unit
    ) : RecyclerView.ViewHolder(txt) {

        init {
            txt.setOnClickListener { onClick(bindingAdapterPosition) }
        }

        fun bind(item: CtcCandidate) {
            // 例: "あ" のように表示
            val pct = item.percent.roundToInt().coerceIn(0, 100)
            txt.text = item.text
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_ctc_candidate, parent, false) as TextView
        return VH(v) { pos ->
            if (pos in 0 until itemCount) onClick(getItem(pos))
        }
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<CtcCandidate>() {
            override fun areItemsTheSame(oldItem: CtcCandidate, newItem: CtcCandidate): Boolean {
                // 同一文字を同一アイテムとして扱う（percentの変動は contents で見る）
                return oldItem.text == newItem.text
            }

            override fun areContentsTheSame(oldItem: CtcCandidate, newItem: CtcCandidate): Boolean {
                return oldItem == newItem
            }
        }
    }
}

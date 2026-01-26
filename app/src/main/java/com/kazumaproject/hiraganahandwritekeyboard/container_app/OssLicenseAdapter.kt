package com.kazumaproject.hiraganahandwritekeyboard.container_app

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.kazumaproject.hiraganahandwritekeyboard.databinding.ItemLicenseBinding

class OssLicenseAdapter(
    private val items: List<OssLicense>,
    private val onClick: (OssLicense) -> Unit
) : RecyclerView.Adapter<OssLicenseAdapter.VH>() {

    class VH(val binding: ItemLicenseBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val inflater = LayoutInflater.from(parent.context)
        return VH(ItemLicenseBinding.inflate(inflater, parent, false))
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.binding.nameText.text = item.name

        val parts = buildList {
            if (item.artifact.isNotBlank()) add(item.artifact)
            if (item.licenseName.isNotBlank()) add(item.licenseName)
        }
        holder.binding.subText.text = parts.joinToString(" • ")

        holder.binding.root.setOnClickListener { onClick(item) }
    }

    override fun getItemCount(): Int = items.size
}

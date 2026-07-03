package com.ziesche.peppolreader.creator.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.ziesche.peppolreader.creator.model.CreatorArticle
import com.ziesche.peppolreader.databinding.ItemCreatorArticleBinding
import java.text.NumberFormat

/**
 * Article-catalog tiles. Tapping a tile opens it for editing; the delete button removes it.
 */
class CreatorArticleAdapter(
    private val onClick: (CreatorArticle) -> Unit,
    private val onDelete: (CreatorArticle) -> Unit
) : ListAdapter<CreatorArticle, CreatorArticleAdapter.VH>(DIFF) {

    private val priceFormat = NumberFormat.getNumberInstance().apply {
        minimumFractionDigits = 2; maximumFractionDigits = 2
    }

    inner class VH(val binding: ItemCreatorArticleBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemCreatorArticleBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        holder.binding.textArticleName.text = item.name

        val vat = if (item.vatRate % 1.0 == 0.0) item.vatRate.toLong().toString() else item.vatRate.toString()
        val details = listOfNotNull(
            item.articleNumber?.takeIf { it.isNotBlank() },
            "${priceFormat.format(item.unitPrice)} € / ${item.unit}",
            "$vat %"
        ).joinToString(" · ")
        holder.binding.textArticleDetails.text = details
        holder.binding.textArticleDetails.visibility =
            if (details.isBlank()) View.GONE else View.VISIBLE

        holder.binding.root.setOnClickListener { onClick(item) }
        holder.binding.btnDeleteArticle.setOnClickListener { onDelete(item) }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<CreatorArticle>() {
            override fun areItemsTheSame(a: CreatorArticle, b: CreatorArticle) = a.id == b.id
            override fun areContentsTheSame(a: CreatorArticle, b: CreatorArticle) = a == b
        }
    }
}

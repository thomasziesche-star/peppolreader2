package com.ziesche.peppolreader.creator.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.ziesche.peppolreader.creator.model.CreatorCustomer
import com.ziesche.peppolreader.databinding.ItemCreatorCustomerBinding

/**
 * Customer-master tiles. Tapping a tile opens it for editing; the delete button removes it.
 */
class CreatorCustomerAdapter(
    private val onClick: (CreatorCustomer) -> Unit,
    private val onDelete: (CreatorCustomer) -> Unit
) : ListAdapter<CreatorCustomer, CreatorCustomerAdapter.VH>(DIFF) {

    inner class VH(val binding: ItemCreatorCustomerBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemCreatorCustomerBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        holder.binding.textCustomerName.text = item.name

        val address = listOfNotNull(
            item.street?.takeIf { it.isNotBlank() },
            listOfNotNull(item.zip, item.city).joinToString(" ").takeIf { it.isNotBlank() }
        ).joinToString(" · ")
        holder.binding.textCustomerAddress.text = address
        holder.binding.textCustomerAddress.visibility =
            if (address.isBlank()) View.GONE else View.VISIBLE

        val details = listOfNotNull(
            item.vatId?.takeIf { it.isNotBlank() },
            item.email?.takeIf { it.isNotBlank() }
        ).joinToString(" · ")
        holder.binding.textCustomerVat.text = details
        holder.binding.textCustomerVat.visibility = if (details.isBlank()) View.GONE else View.VISIBLE

        holder.binding.root.setOnClickListener { onClick(item) }
        holder.binding.btnDeleteCustomer.setOnClickListener { onDelete(item) }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<CreatorCustomer>() {
            override fun areItemsTheSame(a: CreatorCustomer, b: CreatorCustomer) = a.id == b.id
            override fun areContentsTheSame(a: CreatorCustomer, b: CreatorCustomer) = a == b
        }
    }
}

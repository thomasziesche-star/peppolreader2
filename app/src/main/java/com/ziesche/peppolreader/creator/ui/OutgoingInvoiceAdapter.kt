package com.ziesche.peppolreader.creator.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.ziesche.peppolreader.R
import com.ziesche.peppolreader.creator.model.OutgoingInvoice
import com.ziesche.peppolreader.databinding.ItemOutgoingInvoiceBinding
import java.time.LocalDate

/**
 * Lists invoice drafts. Tapping a row opens it for editing; the delete button removes it.
 */
class OutgoingInvoiceAdapter(
    private val onClick: (OutgoingInvoice) -> Unit,
    private val onDelete: (OutgoingInvoice) -> Unit
) : ListAdapter<OutgoingInvoice, OutgoingInvoiceAdapter.VH>(DIFF) {

    inner class VH(val binding: ItemOutgoingInvoiceBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemOutgoingInvoiceBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        val ctx = holder.binding.root.context
        holder.binding.textNumber.text = item.invoiceNumber.ifBlank { "—" }
        holder.binding.textSubtitle.text = listOf(item.buyerName, item.issueDate)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
        holder.binding.textStatus.text = ctx.getString(
            if (item.status == OutgoingInvoice.STATUS_GENERATED)
                R.string.creator_status_generated
            else
                R.string.creator_status_draft
        )

        // Document-type pill: invoice neutral, credit note in the error-container palette.
        val isCreditNote = item.documentTypeCode == "381"
        holder.binding.badgeDocType.text = ctx.getString(
            if (isCreditNote) R.string.creator_doc_type_credit_note else R.string.creator_doc_type_invoice
        )
        holder.binding.badgeDocType.setBackgroundResource(
            if (isCreditNote) R.drawable.bg_credit_note_chip else R.drawable.bg_format_badge
        )
        holder.binding.badgeDocType.setTextColor(
            if (isCreditNote)
                MaterialColors.getColor(holder.binding.badgeDocType, com.google.android.material.R.attr.colorOnErrorContainer)
            else
                ContextCompat.getColor(ctx, R.color.badge_neutral_fg)
        )
        // Payment chips: paid wins; otherwise overdue, escalating to the dunning level.
        val overdue = item.isOverdue(LocalDate.now().toString())
        val paid = item.status == OutgoingInvoice.STATUS_GENERATED && item.paidAt != null
        holder.binding.chipPaid.visibility = if (paid) View.VISIBLE else View.GONE
        holder.binding.chipOverdue.visibility =
            if (overdue && item.dunningLevel == 0) View.VISIBLE else View.GONE
        holder.binding.chipDunning.visibility =
            if (overdue && item.dunningLevel > 0) View.VISIBLE else View.GONE
        if (overdue && item.dunningLevel > 0) {
            holder.binding.chipDunning.text =
                ctx.getString(R.string.creator_badge_dunning, item.dunningLevel)
        }
        val onErrorContainer = MaterialColors.getColor(
            holder.binding.chipOverdue, com.google.android.material.R.attr.colorOnErrorContainer
        )
        holder.binding.chipOverdue.setTextColor(onErrorContainer)
        holder.binding.chipDunning.setTextColor(onErrorContainer)

        holder.binding.root.setOnClickListener { onClick(item) }
        holder.binding.btnDelete.setOnClickListener { onDelete(item) }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<OutgoingInvoice>() {
            override fun areItemsTheSame(a: OutgoingInvoice, b: OutgoingInvoice) = a.id == b.id
            override fun areContentsTheSame(a: OutgoingInvoice, b: OutgoingInvoice) = a == b
        }
    }
}

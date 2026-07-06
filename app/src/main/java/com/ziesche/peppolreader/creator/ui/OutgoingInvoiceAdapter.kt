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
import com.ziesche.peppolreader.creator.dunning.DunningTextBuilder
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

    /** invoiceId → total recorded partial payments; drives the "partially paid" chip. */
    private var paidSums: Map<Long, Double> = emptyMap()

    fun setPaidSums(sums: Map<Long, Double>) {
        paidSums = sums
        notifyDataSetChanged()
    }

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

        // Document-type pill: invoice neutral, credit note error-container, quote tertiary.
        val isCreditNote = item.documentTypeCode == OutgoingInvoice.DOC_TYPE_CREDIT_NOTE
        val isQuote = item.isQuote
        holder.binding.badgeDocType.text = ctx.getString(
            when {
                isQuote -> R.string.creator_doc_type_quote
                isCreditNote -> R.string.creator_doc_type_credit_note
                else -> R.string.creator_doc_type_invoice
            }
        )
        holder.binding.badgeDocType.setBackgroundResource(
            when {
                isQuote -> R.drawable.bg_quote_chip
                isCreditNote -> R.drawable.bg_credit_note_chip
                else -> R.drawable.bg_format_badge
            }
        )
        holder.binding.badgeDocType.setTextColor(
            when {
                isQuote -> MaterialColors.getColor(holder.binding.badgeDocType, com.google.android.material.R.attr.colorOnTertiaryContainer)
                isCreditNote -> MaterialColors.getColor(holder.binding.badgeDocType, com.google.android.material.R.attr.colorOnErrorContainer)
                else -> ContextCompat.getColor(ctx, R.color.badge_neutral_fg)
            }
        )
        // Payment chips only apply to real invoices, never to quotes.
        val overdue = !isQuote && item.isOverdue(LocalDate.now().toString())
        val paid = !isQuote && item.status == OutgoingInvoice.STATUS_GENERATED && item.paidAt != null
        holder.binding.chipPaid.visibility = if (paid) View.VISIBLE else View.GONE
        // Partially paid: generated, not fully paid, but some money recorded against it.
        val partiallyPaid = !isQuote && !paid &&
            item.status == OutgoingInvoice.STATUS_GENERATED &&
            (paidSums[item.id] ?: 0.0) > 0.0
        holder.binding.chipPartial.visibility = if (partiallyPaid) View.VISIBLE else View.GONE
        holder.binding.chipOverdue.visibility =
            if (overdue && item.dunningLevel == 0) View.VISIBLE else View.GONE
        holder.binding.chipDunning.visibility =
            if (overdue && item.dunningLevel > 0) View.VISIBLE else View.GONE
        if (overdue && item.dunningLevel > 0) {
            holder.binding.chipDunning.text =
                ctx.getString(DunningTextBuilder.badgeLabelRes(item.dunningLevel))
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

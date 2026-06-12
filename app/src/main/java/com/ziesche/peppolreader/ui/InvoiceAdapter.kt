package com.ziesche.peppolreader.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import android.content.res.ColorStateList
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.ziesche.peppolreader.data.model.DocumentType
import com.ziesche.peppolreader.data.model.Invoice
import com.ziesche.peppolreader.data.model.signedPayable
import com.ziesche.peppolreader.databinding.ItemInvoiceBinding
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.*

class InvoiceAdapter(
    private val onItemClick: (Invoice) -> Unit,
    private val onItemLongClick: (Invoice) -> Unit,
    private val onAttachmentClick: (Invoice) -> Unit
) : ListAdapter<Invoice, InvoiceAdapter.InvoiceViewHolder>(InvoiceDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): InvoiceViewHolder {
        val binding = ItemInvoiceBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return InvoiceViewHolder(binding)
    }

    override fun onBindViewHolder(holder: InvoiceViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class InvoiceViewHolder(
        private val binding: ItemInvoiceBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        
        private val dateFormat = SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY)
        private val currencyFormat = NumberFormat.getCurrencyInstance(Locale.GERMANY)

        fun bind(invoice: Invoice) {
            binding.apply {
                invoiceId.text = invoice.invoiceId
                supplierName.text = invoice.supplierName
                customerName.text = "→ ${invoice.customerName}"

                // Format badge (e.g. "Peppol", "XRechnung", "ZUGFeRD")
                val badgeStyle = FormatBadge.forLabel(invoice.formatLabel)
                if (badgeStyle != null) {
                    formatBadge.text = badgeStyle.label
                    formatBadge.backgroundTintList = ColorStateList.valueOf(
                        ContextCompat.getColor(root.context, badgeStyle.bgColorRes)
                    )
                    formatBadge.setTextColor(
                        ContextCompat.getColor(root.context, badgeStyle.fgColorRes)
                    )
                    formatBadge.visibility = android.view.View.VISIBLE
                } else {
                    formatBadge.visibility = android.view.View.GONE
                }

                // KSeF FA(3) subtype chip — only shown when invoice has a subtype other than "VAT".
                val subtype = invoice.invoiceSubtype
                if (!subtype.isNullOrBlank() && subtype != "VAT") {
                    subtypeChip.text = subtype
                    subtypeChip.backgroundTintList = ColorStateList.valueOf(
                        ContextCompat.getColor(root.context, com.ziesche.peppolreader.R.color.badge_neutral_bg)
                    )
                    subtypeChip.setTextColor(
                        ContextCompat.getColor(root.context, com.ziesche.peppolreader.R.color.badge_neutral_fg)
                    )
                    subtypeChip.visibility = android.view.View.VISIBLE
                } else {
                    subtypeChip.visibility = android.view.View.GONE
                }

                // Paid badge — only shown when invoice is settled.
                paidChip.visibility =
                    if (invoice.paidAt != null) android.view.View.VISIBLE
                    else android.view.View.GONE

                // Credit-note chip + amount sign
                val isCreditNote = DocumentType.isCreditNote(invoice.documentTypeCode)
                creditNoteChip.visibility =
                    if (isCreditNote) android.view.View.VISIBLE else android.view.View.GONE
                invoiceAmount.text = currencyFormat.format(invoice.signedPayable)
                val colorAttr =
                    if (isCreditNote) com.google.android.material.R.attr.colorError
                    else com.google.android.material.R.attr.colorPrimary
                invoiceAmount.setTextColor(
                    com.google.android.material.color.MaterialColors.getColor(
                        invoiceAmount, colorAttr
                    )
                )
                
                // Format date
                try {
                    val inputFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                    val date = inputFormat.parse(invoice.issueDate)
                    invoiceDate.text = date?.let { dateFormat.format(it) } ?: invoice.issueDate
                } catch (e: Exception) {
                    invoiceDate.text = invoice.issueDate
                }

                // Consolidated screen-reader description: one card = one spoken summary
                // instead of TalkBack reading each unlabeled chip/field separately.
                val ctx = root.context
                val statusParts = buildList {
                    if (isCreditNote) add(ctx.getString(com.ziesche.peppolreader.R.string.cd_status_credit_note))
                    if (invoice.paidAt != null) add(ctx.getString(com.ziesche.peppolreader.R.string.cd_status_paid))
                }
                root.contentDescription = buildList {
                    add(ctx.getString(com.ziesche.peppolreader.R.string.invoice_item_cd, invoice.invoiceId))
                    add(invoice.supplierName)
                    add(invoiceAmount.text.toString())
                    add(invoiceDate.text.toString())
                    addAll(statusParts)
                }.joinToString(", ")
                
                // Attachment label (XML-embedded PDF or original ZUGFeRD/Factur-X PDF)
                if (!invoice.embeddedDocumentFilename.isNullOrEmpty()) {
                    attachmentLabel.text = root.context.getString(
                        com.ziesche.peppolreader.R.string.attachment_label,
                        invoice.embeddedDocumentFilename
                    )
                    attachmentLabel.visibility = android.view.View.VISIBLE
                    attachmentLabel.setOnClickListener { onAttachmentClick(invoice) }
                } else {
                    attachmentLabel.visibility = android.view.View.GONE
                }
                
                // Click listeners
                root.setOnClickListener { onItemClick(invoice) }
                root.setOnLongClickListener { 
                    onItemLongClick(invoice)
                    true
                }
            }
        }
    }

    class InvoiceDiffCallback : DiffUtil.ItemCallback<Invoice>() {
        override fun areItemsTheSame(oldItem: Invoice, newItem: Invoice): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Invoice, newItem: Invoice): Boolean {
            return oldItem == newItem
        }
    }
}

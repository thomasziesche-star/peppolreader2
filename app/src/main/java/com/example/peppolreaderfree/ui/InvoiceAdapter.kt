package com.example.peppolreaderfree.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.peppolreaderfree.data.model.Invoice
import com.example.peppolreaderfree.databinding.ItemInvoiceBinding
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
                
                // Format amount
                invoiceAmount.text = currencyFormat.format(invoice.payableAmount)
                
                // Format date
                try {
                    val inputFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                    val date = inputFormat.parse(invoice.issueDate)
                    invoiceDate.text = date?.let { dateFormat.format(it) } ?: invoice.issueDate
                } catch (e: Exception) {
                    invoiceDate.text = invoice.issueDate
                }
                
                // Attachment label
                if (!invoice.embeddedDocumentFilename.isNullOrEmpty()) {
                    attachmentLabel.text = "attachment: ${invoice.embeddedDocumentFilename}"
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

package com.ziesche.peppolreader.creator.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A single recorded incoming payment against an [OutgoingInvoice] (partial or full). The sum of
 * all payments for an invoice drives whether it counts as fully paid: when the sum reaches the
 * invoice grand total, [OutgoingInvoice.paidAt] is set (see OutgoingInvoiceRepository), so the
 * existing overdue/dunning/dashboard logic keeps working on the binary flag while the ledger
 * carries the detail (remaining amount, tranches).
 */
@Entity(
    tableName = "outgoing_invoice_payments",
    indices = [Index(value = ["invoiceId"])]
)
data class OutgoingInvoicePayment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val invoiceId: Long,
    val amount: Double,
    val paidAtMs: Long,
    val note: String? = null
)

package com.ziesche.peppolreader.creator.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import com.ziesche.peppolreader.creator.model.OutgoingInvoicePayment

/** Aggregated paid amount per invoice (for the dashboard's remaining-amount calculation). */
data class InvoicePaidSum(val invoiceId: Long, val total: Double)

@Dao
interface OutgoingInvoicePaymentDao {

    @Query("SELECT * FROM outgoing_invoice_payments WHERE invoiceId = :invoiceId ORDER BY paidAtMs ASC")
    suspend fun getForInvoice(invoiceId: Long): List<OutgoingInvoicePayment>

    @Query("SELECT COALESCE(SUM(amount), 0) FROM outgoing_invoice_payments WHERE invoiceId = :invoiceId")
    suspend fun sumForInvoice(invoiceId: Long): Double

    @Query("SELECT invoiceId, COALESCE(SUM(amount), 0) AS total FROM outgoing_invoice_payments GROUP BY invoiceId")
    suspend fun sumsByInvoice(): List<InvoicePaidSum>

    @Insert
    suspend fun insert(payment: OutgoingInvoicePayment): Long

    @Delete
    suspend fun delete(payment: OutgoingInvoicePayment)

    @Query("DELETE FROM outgoing_invoice_payments WHERE invoiceId = :invoiceId")
    suspend fun deleteForInvoice(invoiceId: Long)
}

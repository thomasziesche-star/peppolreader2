package com.ziesche.peppolreader.creator.data

import android.content.Context
import androidx.lifecycle.LiveData
import com.ziesche.peppolreader.data.AppDatabase
import com.ziesche.peppolreader.creator.model.OutgoingInvoice
import com.ziesche.peppolreader.creator.model.OutgoingInvoicePayment
import com.ziesche.peppolreader.creator.xml.InvoiceTotalsCalculator

/**
 * Single access point to outgoing-invoice (draft) persistence. Mirrors
 * [com.ziesche.peppolreader.data.InvoiceRepository] so the creator ViewModel never touches
 * Room directly.
 */
class OutgoingInvoiceRepository(
    private val dao: OutgoingInvoiceDao,
    private val paymentDao: OutgoingInvoicePaymentDao
) {

    fun allLiveData(): LiveData<List<OutgoingInvoice>> = dao.getAllLiveData()

    suspend fun getById(id: Long): OutgoingInvoice? = dao.getById(id)

    suspend fun insert(invoice: OutgoingInvoice): Long = dao.insert(invoice)

    suspend fun update(invoice: OutgoingInvoice) = dao.update(invoice)

    suspend fun delete(invoice: OutgoingInvoice) = dao.delete(invoice)

    suspend fun deleteById(id: Long) = dao.deleteById(id)

    suspend fun setPaid(id: Long, paidAtMs: Long?) = dao.setPaid(id, paidAtMs)

    suspend fun recordDunningSent(id: Long, nowMs: Long) = dao.recordDunningSent(id, nowMs)

    suspend fun getOverdueUnnotified(todayIso: String, startOfTodayMs: Long): List<OutgoingInvoice> =
        dao.getOverdueUnnotified(todayIso, startOfTodayMs)

    suspend fun touchOverdueNotified(id: Long, timestamp: Long) =
        dao.touchOverdueNotified(id, timestamp)

    // ----- partial payments (ledger) -------------------------------------------------------

    suspend fun paymentsFor(invoiceId: Long): List<OutgoingInvoicePayment> =
        paymentDao.getForInvoice(invoiceId)

    suspend fun paidSum(invoiceId: Long): Double = paymentDao.sumForInvoice(invoiceId)

    /** invoiceId → total paid, for the dashboard's remaining-amount calculation. */
    suspend fun paidSums(): Map<Long, Double> =
        paymentDao.sumsByInvoice().associate { it.invoiceId to it.total }

    /** Books an incoming (partial) payment and re-derives the invoice's fully-paid flag. */
    suspend fun recordPayment(invoice: OutgoingInvoice, amount: Double, atMs: Long, note: String?) {
        paymentDao.insert(
            OutgoingInvoicePayment(invoiceId = invoice.id, amount = amount, paidAtMs = atMs, note = note)
        )
        refreshPaidStatus(invoice)
    }

    suspend fun deletePayment(invoice: OutgoingInvoice, payment: OutgoingInvoicePayment) {
        paymentDao.delete(payment)
        refreshPaidStatus(invoice)
    }

    /**
     * Sets [OutgoingInvoice.paidAt] to the latest payment date once the recorded payments reach
     * the invoice grand total, else clears it — so overdue/dunning/dashboard keep using the flag.
     */
    private suspend fun refreshPaidStatus(invoice: OutgoingInvoice) {
        val grandTotal = InvoiceTotalsCalculator.calculate(invoice).grandTotal
        val payments = paymentDao.getForInvoice(invoice.id)
        val sum = payments.sumOf { it.amount }
        // 0.5 cent epsilon so rounding never leaves an invoice one cent short of "paid".
        val fullyPaid = grandTotal > 0.0 && sum + 0.005 >= grandTotal
        val paidAt = if (fullyPaid) payments.maxOfOrNull { it.paidAtMs } else null
        dao.setPaid(invoice.id, paidAt)
    }

    companion object {
        fun from(context: Context): OutgoingInvoiceRepository {
            val db = AppDatabase.getDatabase(context)
            return OutgoingInvoiceRepository(db.outgoingInvoiceDao(), db.outgoingInvoicePaymentDao())
        }
    }
}

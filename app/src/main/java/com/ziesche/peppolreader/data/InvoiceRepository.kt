package com.ziesche.peppolreader.data

import android.content.Context
import androidx.lifecycle.LiveData
import com.ziesche.peppolreader.data.model.Invoice
import com.ziesche.peppolreader.data.model.MonthlyExpense
import com.ziesche.peppolreader.data.model.SupplierExpense

/**
 * Single access point to invoice persistence. Wraps [InvoiceDao] so the ViewModel and the
 * due-date worker no longer reach into Room directly — this keeps the data source swappable
 * and the business-logic callers testable against a fake repository.
 */
class InvoiceRepository(private val dao: InvoiceDao) {

    fun allInvoicesLiveData(): LiveData<List<Invoice>> = dao.getAllInvoicesLiveData()
    fun monthlyExpenses(): LiveData<List<MonthlyExpense>> = dao.getMonthlyExpenses()
    fun topSuppliers(): LiveData<List<SupplierExpense>> = dao.getTopSuppliers()

    suspend fun getById(id: Long): Invoice? = dao.getInvoiceById(id)

    suspend fun findDuplicate(invoiceId: String, supplierName: String): Invoice? =
        dao.getInvoiceByNumberAndSupplier(invoiceId, supplierName)

    suspend fun insert(invoice: Invoice): Long = dao.insertInvoice(invoice)

    suspend fun delete(invoice: Invoice) = dao.deleteInvoice(invoice)

    suspend fun setPaid(id: Long, paidAtMs: Long?) = dao.setPaid(id, paidAtMs)

    suspend fun setNoteAndCategory(id: Long, note: String?, category: String?) =
        dao.setNoteAndCategory(id, note, category)

    suspend fun getUsedCategories(): List<String> = dao.getUsedCategories()

    suspend fun getInvoicesMissingMetadata(): List<Invoice> = dao.getInvoicesMissingMetadata()

    suspend fun setDerivedMetadata(id: Long, formatLabel: String?, documentTypeCode: String?) =
        dao.setDerivedMetadata(id, formatLabel, documentTypeCode)

    suspend fun getInDateRange(fromIso: String, toIso: String): List<Invoice> =
        dao.getInvoicesInDateRange(fromIso, toIso)

    suspend fun getDueSoon(thresholdDate: String, startOfTodayMs: Long): List<Invoice> =
        dao.getDueSoon(thresholdDate, startOfTodayMs)

    suspend fun touchReminderShown(id: Long, timestamp: Long) =
        dao.touchReminderShown(id, timestamp)

    companion object {
        fun from(context: Context): InvoiceRepository =
            InvoiceRepository(AppDatabase.getDatabase(context).invoiceDao())
    }
}

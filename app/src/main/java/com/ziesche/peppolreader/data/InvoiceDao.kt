package com.ziesche.peppolreader.data

import androidx.lifecycle.LiveData
import androidx.room.*
import com.ziesche.peppolreader.data.model.Invoice
import com.ziesche.peppolreader.data.model.MonthlyExpense
import com.ziesche.peppolreader.data.model.SupplierExpense
import kotlinx.coroutines.flow.Flow

@Dao
interface InvoiceDao {
    
    @Query("SELECT * FROM invoices ORDER BY createdAt DESC")
    fun getAllInvoices(): Flow<List<Invoice>>
    
    @Query("SELECT * FROM invoices ORDER BY createdAt DESC")
    fun getAllInvoicesLiveData(): LiveData<List<Invoice>>
    
    @Query("SELECT * FROM invoices WHERE id = :id")
    suspend fun getInvoiceById(id: Long): Invoice?
    
    @Query("SELECT * FROM invoices WHERE invoiceId = :invoiceId LIMIT 1")
    suspend fun getInvoiceByInvoiceId(invoiceId: String): Invoice?

    // TRIM + NOCASE: the same invoice re-exported from another mail client often differs only
    // in stray whitespace or letter case — treat those as duplicates, not new documents.
    @Query(
        "SELECT * FROM invoices WHERE TRIM(invoiceId) = TRIM(:invoiceId) COLLATE NOCASE " +
            "AND TRIM(supplierName) = TRIM(:supplierName) COLLATE NOCASE LIMIT 1"
    )
    suspend fun getInvoiceByNumberAndSupplier(invoiceId: String, supplierName: String): Invoice?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertInvoice(invoice: Invoice): Long
    
    @Update
    suspend fun updateInvoice(invoice: Invoice)
    
    @Delete
    suspend fun deleteInvoice(invoice: Invoice)
    
    @Query("DELETE FROM invoices WHERE id = :id")
    suspend fun deleteInvoiceById(id: Long)
    
    @Query("DELETE FROM invoices")
    suspend fun deleteAllInvoices()
    
    @Query("SELECT COUNT(*) FROM invoices")
    suspend fun getInvoiceCount(): Int

    /** Snapshot of all invoices ordered by issueDate ASC – used by the CSV/ZIP exporter. */
    @Query("SELECT * FROM invoices ORDER BY issueDate ASC")
    suspend fun getAllInvoicesList(): List<Invoice>

    /**
     * Invoices whose issueDate falls into [from, to] (inclusive, ISO yyyy-MM-dd lexical compare).
     * Used by the export bottom sheet when the user picks a date range.
     */
    @Query("SELECT * FROM invoices WHERE issueDate >= :from AND issueDate <= :to ORDER BY issueDate ASC")
    suspend fun getInvoicesInDateRange(from: String, to: String): List<Invoice>

    /** Sets/clears [Invoice.paidAt]. */
    @Query("UPDATE invoices SET paidAt = :paidAtMs WHERE id = :id")
    suspend fun setPaid(id: Long, paidAtMs: Long?)

    /** Persists the user-entered bookkeeping [note] and [category] for one invoice. */
    @Query("UPDATE invoices SET note = :note, category = :category WHERE id = :id")
    suspend fun setNoteAndCategory(id: Long, note: String?, category: String?)

    /** Distinct non-empty categories already in use, for autocomplete suggestions. */
    @Query("SELECT DISTINCT category FROM invoices WHERE category IS NOT NULL AND category != '' ORDER BY category")
    suspend fun getUsedCategories(): List<String>

    /** All invoices missing a format label or document-type code — re-parsed by the backfill worker. */
    @Query("SELECT * FROM invoices WHERE formatLabel IS NULL OR documentTypeCode IS NULL")
    suspend fun getInvoicesMissingMetadata(): List<Invoice>

    /** Backfill: sets only the derived metadata columns, never touches user data. */
    @Query("UPDATE invoices SET formatLabel = :formatLabel, documentTypeCode = :documentTypeCode WHERE id = :id")
    suspend fun setDerivedMetadata(id: Long, formatLabel: String?, documentTypeCode: String?)

    /** Stamps an invoice as just-reminded so the worker doesn't notify twice on the same day. */
    @Query("UPDATE invoices SET lastReminderShownAt = :timestamp WHERE id = :id")
    suspend fun touchReminderShown(id: Long, timestamp: Long)

    /**
     * Invoices that are still unpaid, have a non-empty dueDate, will fall due on or before
     * [thresholdDate] (ISO yyyy-MM-dd lexical compare) and haven't been reminded since
     * [startOfTodayMs]. Result drives the daily due-date worker.
     */
    @Query("""
        SELECT * FROM invoices
        WHERE dueDate IS NOT NULL
          AND dueDate != ''
          AND dueDate <= :thresholdDate
          AND paidAt IS NULL
          AND (lastReminderShownAt IS NULL OR lastReminderShownAt < :startOfTodayMs)
        ORDER BY dueDate ASC
    """)
    suspend fun getDueSoon(thresholdDate: String, startOfTodayMs: Long): List<Invoice>

    // Charts
    @Query("SELECT strftime('%Y-%m', issueDate) as month, SUM(payableAmount) as total FROM invoices GROUP BY month ORDER BY month DESC LIMIT 12")
    fun getMonthlyExpenses(): LiveData<List<MonthlyExpense>>

    @Query("SELECT supplierName, SUM(payableAmount) as total, COUNT(*) as invoiceCount FROM invoices GROUP BY supplierName ORDER BY total DESC LIMIT 5")
    fun getTopSuppliers(): LiveData<List<SupplierExpense>>
}

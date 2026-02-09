package com.example.peppolreaderfree.data

import androidx.lifecycle.LiveData
import androidx.room.*
import com.example.peppolreaderfree.data.model.Invoice
import com.example.peppolreaderfree.data.model.MonthlyExpense
import com.example.peppolreaderfree.data.model.SupplierExpense
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

    @Query("SELECT * FROM invoices WHERE invoiceId = :invoiceId AND supplierName = :supplierName LIMIT 1")
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

    // Charts
    @Query("SELECT strftime('%Y-%m', issueDate) as month, SUM(payableAmount) as total FROM invoices GROUP BY month ORDER BY month DESC LIMIT 12")
    fun getMonthlyExpenses(): LiveData<List<MonthlyExpense>>

    @Query("SELECT supplierName, SUM(payableAmount) as total, COUNT(*) as invoiceCount FROM invoices GROUP BY supplierName ORDER BY total DESC LIMIT 5")
    fun getTopSuppliers(): LiveData<List<SupplierExpense>>
}

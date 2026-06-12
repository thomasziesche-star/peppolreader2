package com.ziesche.peppolreader.creator.data

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.ziesche.peppolreader.creator.model.OutgoingInvoice

@Dao
interface OutgoingInvoiceDao {

    @Query("SELECT * FROM outgoing_invoices ORDER BY updatedAt DESC")
    fun getAllLiveData(): LiveData<List<OutgoingInvoice>>

    @Query("SELECT * FROM outgoing_invoices WHERE id = :id")
    suspend fun getById(id: Long): OutgoingInvoice?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(invoice: OutgoingInvoice): Long

    @Update
    suspend fun update(invoice: OutgoingInvoice)

    @Delete
    suspend fun delete(invoice: OutgoingInvoice)

    @Query("DELETE FROM outgoing_invoices WHERE id = :id")
    suspend fun deleteById(id: Long)
}

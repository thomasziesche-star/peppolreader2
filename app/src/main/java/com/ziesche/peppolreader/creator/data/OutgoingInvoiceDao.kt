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

    /** Sets/clears [OutgoingInvoice.paidAt]. */
    @Query("UPDATE outgoing_invoices SET paidAt = :paidAtMs WHERE id = :id")
    suspend fun setPaid(id: Long, paidAtMs: Long?)

    /** Bumps the dunning level (capped at 3) and stamps the launch time. */
    @Query(
        "UPDATE outgoing_invoices SET dunningLevel = MIN(dunningLevel + 1, 3), " +
            "lastDunningAt = :nowMs, updatedAt = :nowMs WHERE id = :id"
    )
    suspend fun recordDunningSent(id: Long, nowMs: Long)

    /** Generated, unpaid, due date strictly in the past, not yet notified today. */
    @Query(
        """
        SELECT * FROM outgoing_invoices
        WHERE status = 'GENERATED'
          AND paidAt IS NULL
          AND dueDate IS NOT NULL AND dueDate != ''
          AND dueDate < :todayIso
          AND (lastOverdueNotifiedAt IS NULL OR lastOverdueNotifiedAt < :startOfTodayMs)
        ORDER BY dueDate ASC
        """
    )
    suspend fun getOverdueUnnotified(todayIso: String, startOfTodayMs: Long): List<OutgoingInvoice>

    @Query("UPDATE outgoing_invoices SET lastOverdueNotifiedAt = :timestamp WHERE id = :id")
    suspend fun touchOverdueNotified(id: Long, timestamp: Long)
}

package com.ziesche.peppolreader.creator.data

import android.content.Context
import androidx.lifecycle.LiveData
import com.ziesche.peppolreader.data.AppDatabase
import com.ziesche.peppolreader.creator.model.OutgoingInvoice

/**
 * Single access point to outgoing-invoice (draft) persistence. Mirrors
 * [com.ziesche.peppolreader.data.InvoiceRepository] so the creator ViewModel never touches
 * Room directly.
 */
class OutgoingInvoiceRepository(private val dao: OutgoingInvoiceDao) {

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

    companion object {
        fun from(context: Context): OutgoingInvoiceRepository =
            OutgoingInvoiceRepository(AppDatabase.getDatabase(context).outgoingInvoiceDao())
    }
}

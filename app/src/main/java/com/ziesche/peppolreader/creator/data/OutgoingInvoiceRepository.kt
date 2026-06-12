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

    companion object {
        fun from(context: Context): OutgoingInvoiceRepository =
            OutgoingInvoiceRepository(AppDatabase.getDatabase(context).outgoingInvoiceDao())
    }
}

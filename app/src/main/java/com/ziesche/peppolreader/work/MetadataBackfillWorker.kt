package com.ziesche.peppolreader.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.ziesche.peppolreader.data.InvoiceRepository
import com.ziesche.peppolreader.data.model.ParsedInvoice
import com.ziesche.peppolreader.parser.CiiParser
import com.ziesche.peppolreader.parser.InvoiceFormat
import com.ziesche.peppolreader.parser.KsefFa3Parser
import com.ziesche.peppolreader.parser.PeppolParser

/**
 * One-off, idempotent backfill: invoices imported by older app versions (v2.8–v3.0) have no
 * [com.ziesche.peppolreader.data.model.Invoice.formatLabel] / `documentTypeCode`, so they show
 * no format badge and credit notes aren't recognized. This re-parses only those rows and fills
 * the two derived columns — user data is never touched.
 *
 * Safe to run repeatedly: it queries only rows still missing metadata, so once they are filled
 * it becomes a no-op. Scheduled with KEEP on every app start (see PeppolReaderApplication).
 */
class MetadataBackfillWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val repository = InvoiceRepository.from(applicationContext)
        val missing = repository.getInvoicesMissingMetadata()
        if (missing.isEmpty()) return Result.success()

        for (invoice in missing) {
            runCatching {
                val parsed = parse(invoice.xmlContent) ?: return@runCatching
                // Keep any value that already exists; only fill the gaps.
                val formatLabel = invoice.formatLabel ?: parsed.formatLabel
                val documentTypeCode = invoice.documentTypeCode ?: parsed.documentTypeCode
                repository.setDerivedMetadata(invoice.id, formatLabel, documentTypeCode)
            }
        }
        return Result.success()
    }

    private fun parse(xml: String): ParsedInvoice? =
        when (InvoiceFormat.detect(xml)) {
            InvoiceFormat.UBL -> PeppolParser(xml, applicationContext).parse()
            InvoiceFormat.CII -> CiiParser(xml, applicationContext).parse()
            InvoiceFormat.KSEF_FA3 -> KsefFa3Parser(xml, applicationContext).parse()
            InvoiceFormat.UNKNOWN -> null
        }

    companion object {
        const val UNIQUE_NAME = "metadata_backfill"
    }
}

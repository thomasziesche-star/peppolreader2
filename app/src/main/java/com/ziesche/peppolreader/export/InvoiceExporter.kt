package com.ziesche.peppolreader.export

import com.ziesche.peppolreader.data.model.Invoice

/**
 * Turns a set of invoices into the bytes the user downloads — either a plain CSV or a ZIP
 * bundle (CSV + original XML/PDFs). Pure JVM logic with no Android dependencies, so it can be
 * unit-tested directly; the ViewModel only has to fetch the rows and route the result to a URI.
 */
class InvoiceExporter(
    private val headers: CsvExporter.Headers = CsvExporter.Headers()
) {

    /** A ready-to-write export file plus the metadata the host needs to offer "Save as…". */
    data class Artifact(
        val bytes: ByteArray,
        val mimeType: String,
        val fileName: String,
        val invoiceCount: Int
    )

    /**
     * Builds the export artifact for [invoices]. The date range is only used to name the file.
     * Callers must pass a non-empty list (empty/error handling stays with the caller).
     */
    fun export(
        invoices: List<Invoice>,
        fromIso: String,
        toIso: String,
        includeXmlBundle: Boolean
    ): Artifact {
        val baseName = "PeppolReader-Export-${fromIso}_${toIso}"
        val csvBytes = CsvExporter(headers = headers).toCsvBytes(invoices)

        return if (includeXmlBundle) {
            Artifact(
                bytes = ZipBundler().bundle(csvBytes, "$baseName.csv", invoices),
                mimeType = "application/zip",
                fileName = "$baseName.zip",
                invoiceCount = invoices.size
            )
        } else {
            Artifact(
                bytes = csvBytes,
                mimeType = "text/csv",
                fileName = "$baseName.csv",
                invoiceCount = invoices.size
            )
        }
    }
}

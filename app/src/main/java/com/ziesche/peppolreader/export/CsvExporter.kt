package com.ziesche.peppolreader.export

import com.ziesche.peppolreader.data.model.DocumentType
import com.ziesche.peppolreader.data.model.Invoice
import java.text.NumberFormat
import java.util.Locale

/**
 * Builds a CSV file from a list of invoices.
 *
 * Output conventions:
 *  - UTF-8 with BOM so Excel double-clicks open it cleanly even on Windows-CP1252 systems.
 *  - Semicolon-separated (German Excel default; survives Excel/Numbers/LibreOffice equally).
 *  - Decimals follow the locale (Komma in DE/AT/FR, Punkt in EN). Bookkeeping software
 *    that expects a fixed format can re-import via "Daten > Aus Text/CSV" with explicit
 *    column-type hints.
 *  - Quotes only fields that contain `"`, `;` or a newline; quotes inside fields are
 *    doubled per RFC 4180.
 */
class CsvExporter(
    private val locale: Locale = Locale.getDefault(),
    /** Localized header labels so the spreadsheet header matches the app's UI language. */
    private val headers: Headers = Headers()
) {
    data class Headers(
        val invoiceId: String = "Rechnungsnummer",
        val issueDate: String = "Belegdatum",
        val dueDate: String = "Fälligkeit",
        val supplier: String = "Lieferant",
        val supplierTaxId: String = "USt-ID Lieferant",
        val customer: String = "Kunde",
        val net: String = "Netto",
        val tax: String = "USt",
        val gross: String = "Brutto",
        val payable: String = "Zahlbar",
        val currency: String = "Währung",
        val format: String = "Format",
        val docType: String = "Typ",
        val category: String = "Kategorie",
        val note: String = "Notiz",
        val fileName: String = "Dateiname",
        val docTypeInvoice: String = "Rechnung",
        val docTypeCreditNote: String = "Gutschrift",
        val docTypeCorrected: String = "Korrektur"
    )

    private val numberFormat: NumberFormat = NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = 2
        maximumFractionDigits = 2
        isGroupingUsed = false // grouping confuses CSV imports
    }

    fun toCsvBytes(invoices: List<Invoice>): ByteArray {
        val sb = StringBuilder()
        // UTF-8 BOM so Excel picks the encoding automatically
        sb.append('﻿')

        // Header row
        appendRow(
            sb,
            headers.invoiceId,
            headers.issueDate,
            headers.dueDate,
            headers.supplier,
            headers.supplierTaxId,
            headers.customer,
            headers.net,
            headers.tax,
            headers.gross,
            headers.payable,
            headers.currency,
            headers.format,
            headers.docType,
            headers.category,
            headers.note,
            headers.fileName
        )

        // Data rows
        for (invoice in invoices) {
            val signedNet: Double
            val signedTax: Double
            val signedGross: Double
            val signedPayable: Double
            val docTypeLabel: String
            if (DocumentType.isCreditNote(invoice.documentTypeCode)) {
                signedNet = -invoice.netAmount
                signedTax = -invoice.taxAmount
                signedGross = -invoice.grossAmount
                signedPayable = -invoice.payableAmount
                docTypeLabel = headers.docTypeCreditNote
            } else {
                signedNet = invoice.netAmount
                signedTax = invoice.taxAmount
                signedGross = invoice.grossAmount
                signedPayable = invoice.payableAmount
                docTypeLabel = if (invoice.documentTypeCode == DocumentType.CORRECTED_INVOICE) {
                    headers.docTypeCorrected
                } else {
                    headers.docTypeInvoice
                }
            }

            appendRow(
                sb,
                invoice.invoiceId,
                invoice.issueDate,
                invoice.dueDate.orEmpty(),
                invoice.supplierName,
                invoice.supplierTaxId.orEmpty(),
                invoice.customerName,
                formatAmount(signedNet),
                formatAmount(signedTax),
                formatAmount(signedGross),
                formatAmount(signedPayable),
                invoice.currency,
                invoice.formatLabel.orEmpty(),
                docTypeLabel,
                invoice.category.orEmpty(),
                invoice.note.orEmpty(),
                invoice.fileName
            )
        }

        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    private fun formatAmount(value: Double): String = numberFormat.format(value)

    private fun appendRow(sb: StringBuilder, vararg fields: String) {
        for ((i, field) in fields.withIndex()) {
            if (i > 0) sb.append(';')
            sb.append(escape(field))
        }
        sb.append("\r\n") // CRLF: standard for CSV consumers on Windows/Excel
    }

    private fun escape(field: String): String {
        // Quote only when needed: contains separator, quote or newline
        val needsQuoting = field.any { it == ';' || it == '"' || it == '\n' || it == '\r' }
        return if (needsQuoting) "\"" + field.replace("\"", "\"\"") + "\"" else field
    }
}

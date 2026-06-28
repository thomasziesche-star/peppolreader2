package com.ziesche.peppolreader.export

import com.ziesche.peppolreader.data.model.DocumentType
import com.ziesche.peppolreader.data.model.Invoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Unit tests for [CsvExporter] — the file an accountant actually receives. Pure JVM logic
 * (no Android), so plain JUnit. Pins the conventions that downstream bookkeeping imports rely
 * on: UTF-8 BOM, semicolon separator, CRLF rows, RFC-4180 quoting, locale decimals and the
 * negative-amount/credit-note handling.
 */
class CsvExporterTest {

    private fun invoice(
        invoiceId: String = "INV-1",
        supplierName: String = "Supplier GmbH",
        net: Double = 100.0,
        tax: Double = 19.0,
        gross: Double = 119.0,
        payable: Double = 119.0,
        documentTypeCode: String? = DocumentType.INVOICE
    ) = Invoice(
        invoiceId = invoiceId,
        issueDate = "2026-01-15",
        dueDate = "2026-02-15",
        supplierName = supplierName,
        customerName = "Customer AG",
        netAmount = net,
        taxAmount = tax,
        grossAmount = gross,
        payableAmount = payable,
        xmlContent = "<Invoice/>",
        fileName = "$invoiceId.xml",
        documentTypeCode = documentTypeCode,
        currency = "EUR"
    )

    private fun lines(bytes: ByteArray): List<String> =
        String(bytes, Charsets.UTF_8).removePrefix("﻿").split("\r\n").filter { it.isNotEmpty() }

    @Test
    fun startsWithUtf8BomAndUsesCrlf() {
        val bytes = CsvExporter(Locale.GERMANY).toCsvBytes(listOf(invoice()))
        val text = String(bytes, Charsets.UTF_8)
        assertTrue("must start with UTF-8 BOM", text.startsWith("﻿"))
        assertTrue("rows must be CRLF-terminated", text.contains("\r\n"))
    }

    @Test
    fun headerAndRowAreSemicolonSeparated() {
        val rows = lines(CsvExporter(Locale.GERMANY).toCsvBytes(listOf(invoice())))
        assertEquals(2, rows.size) // header + 1 data row
        // 14 columns → 13 separators in the header
        assertEquals(13, rows[0].count { it == ';' })
        assertTrue(rows[1].startsWith("INV-1;2026-01-15;2026-02-15;Supplier GmbH;"))
    }

    @Test
    fun decimalsFollowLocale() {
        val de = lines(CsvExporter(Locale.GERMANY).toCsvBytes(listOf(invoice())))[1]
        assertTrue("German locale uses a decimal comma", de.contains(";100,00;19,00;119,00;119,00;"))

        val en = lines(CsvExporter(Locale.US).toCsvBytes(listOf(invoice())))[1]
        assertTrue("US locale uses a decimal point", en.contains(";100.00;19.00;119.00;119.00;"))
    }

    @Test
    fun creditNoteAmountsAreNegative() {
        val headers = CsvExporter.Headers()
        val row = lines(
            CsvExporter(Locale.GERMANY, headers)
                .toCsvBytes(listOf(invoice(documentTypeCode = DocumentType.CREDIT_NOTE)))
        )[1]
        assertTrue("credit note nets out negative", row.contains(";-100,00;-19,00;-119,00;-119,00;"))
        assertTrue("credit note is labelled as such", row.endsWith(";${headers.docTypeCreditNote};INV-1.xml"))
    }

    @Test
    fun fieldsWithSeparatorAreQuotedAndInnerQuotesDoubled() {
        val row = lines(
            CsvExporter(Locale.GERMANY).toCsvBytes(listOf(invoice(supplierName = "A;B \"X\" Ltd")))
        )[1]
        assertTrue("field containing ; or \" must be quoted", row.contains("\"A;B \"\"X\"\" Ltd\""))
    }
}

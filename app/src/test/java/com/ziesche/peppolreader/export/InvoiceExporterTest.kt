package com.ziesche.peppolreader.export

import com.ziesche.peppolreader.data.model.Invoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * Unit tests for [InvoiceExporter] — the export assembly that used to live inside the
 * (untestable) AndroidViewModel. Pins the MIME type, suggested file name and payload shape for
 * the plain-CSV vs ZIP-bundle paths.
 */
class InvoiceExporterTest {

    private fun invoice(invoiceId: String = "INV-1", id: Long = 1L) = Invoice(
        id = id,
        invoiceId = invoiceId,
        issueDate = "2026-03-10",
        supplierName = "Supplier",
        customerName = "Customer",
        xmlContent = "<Invoice>$invoiceId</Invoice>",
        fileName = "$invoiceId.xml"
    )

    @Test
    fun plainCsvExportHasCsvMimeAndName() {
        val artifact = InvoiceExporter().export(
            invoices = listOf(invoice(), invoice("INV-2", 2)),
            fromIso = "2026-01-01",
            toIso = "2026-03-31",
            includeXmlBundle = false
        )
        assertEquals("text/csv", artifact.mimeType)
        assertEquals("PeppolReader-Export-2026-01-01_2026-03-31.csv", artifact.fileName)
        assertEquals(2, artifact.invoiceCount)
        assertTrue(artifact.bytes.isNotEmpty())
    }

    @Test
    fun bundleExportIsAZipContainingTheCsv() {
        val artifact = InvoiceExporter().export(
            invoices = listOf(invoice()),
            fromIso = "2026-01-01",
            toIso = "2026-03-31",
            includeXmlBundle = true
        )
        assertEquals("application/zip", artifact.mimeType)
        assertEquals("PeppolReader-Export-2026-01-01_2026-03-31.zip", artifact.fileName)

        val names = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(artifact.bytes)).use { zis ->
            var e = zis.nextEntry
            while (e != null) { names += e.name; e = zis.nextEntry }
        }
        assertTrue("ZIP must carry the CSV under the export base name",
            names.contains("PeppolReader-Export-2026-01-01_2026-03-31.csv"))
        assertTrue("ZIP must carry the invoice XML", names.contains("xml/INV-1-1.xml"))
    }
}

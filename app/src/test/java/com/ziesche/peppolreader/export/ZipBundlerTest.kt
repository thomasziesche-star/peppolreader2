package com.ziesche.peppolreader.export

import com.ziesche.peppolreader.data.model.Invoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * Unit tests for [ZipBundler] — the one-file hand-over an accountant gets. Pure JVM logic.
 * Verifies the in-ZIP layout (CSV at root, one XML per invoice under xml/), that the embedded
 * PDF is only included when the file is actually on disk, and that hostile invoice numbers are
 * sanitised into safe entry names.
 */
class ZipBundlerTest {

    private fun invoice(invoiceId: String, id: Long, pdfPath: String? = null) = Invoice(
        id = id,
        invoiceId = invoiceId,
        issueDate = "2026-01-15",
        supplierName = "Supplier",
        customerName = "Customer",
        xmlContent = "<Invoice>$invoiceId</Invoice>",
        fileName = "$invoiceId.xml",
        embeddedDocumentPath = pdfPath
    )

    /** Reads the ZIP into a name → bytes map. */
    private fun entries(zip: ByteArray): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(zip)).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                out[e.name] = zis.readBytes()
                e = zis.nextEntry
            }
        }
        return out
    }

    @Test
    fun bundlesCsvAtRootAndOneXmlPerInvoice() {
        val zip = ZipBundler().bundle(
            csvBytes = "csv-content".toByteArray(),
            csvFileName = "export.csv",
            invoices = listOf(invoice("INV-1", 1), invoice("INV-2", 2))
        )
        val entries = entries(zip)

        assertTrue("CSV at root", entries.containsKey("export.csv"))
        assertEquals("csv-content", String(entries["export.csv"]!!))
        assertTrue(entries.containsKey("xml/INV-1-1.xml"))
        assertTrue(entries.containsKey("xml/INV-2-2.xml"))
        assertEquals("<Invoice>INV-1</Invoice>", String(entries["xml/INV-1-1.xml"]!!))
    }

    @Test
    fun skipsAttachmentWhenPdfPathMissingOrAbsent() {
        val zip = ZipBundler().bundle(
            csvBytes = ByteArray(0),
            csvFileName = "export.csv",
            invoices = listOf(
                invoice("INV-1", 1, pdfPath = null),
                invoice("INV-2", 2, pdfPath = "/does/not/exist.pdf")
            )
        )
        assertFalse(entries(zip).keys.any { it.startsWith("attachments/") })
    }

    @Test
    fun sanitisesHostileInvoiceNumbersInEntryNames() {
        val zip = ZipBundler().bundle(
            csvBytes = ByteArray(0),
            csvFileName = "export.csv",
            invoices = listOf(invoice("../../etc/passwd", 7))
        )
        val xmlEntry = entries(zip).keys.first { it.startsWith("xml/") }
        // Slashes (the actual traversal vector) are replaced with '_'; dots are harmless once
        // there is no separator left, so the entry can never escape the xml/ folder.
        assertEquals("xml/.._.._etc_passwd-7.xml", xmlEntry)
        val base = xmlEntry.removePrefix("xml/")
        assertFalse("sanitised name must contain no path separator", base.contains("/"))
    }
}

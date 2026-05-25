package com.ziesche.peppolreader.export

import com.ziesche.peppolreader.data.model.Invoice
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Packs the CSV plus the original XML (and embedded PDF, if available) per invoice
 * into a single ZIP that can be handed over to an accountant in one go.
 *
 * Layout inside the ZIP:
 *
 *   ├── export.csv
 *   ├── xml/
 *   │   ├── <invoiceId>-<dbId>.xml
 *   │   └── …
 *   └── attachments/
 *       ├── <invoiceId>-<dbId>.pdf      (only if the source was ZUGFeRD/Factur-X
 *       │                                or had an embedded PDF in the XML)
 *       └── …
 */
class ZipBundler {

    fun bundle(
        csvBytes: ByteArray,
        csvFileName: String,
        invoices: List<Invoice>
    ): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry(csvFileName))
            zos.write(csvBytes)
            zos.closeEntry()

            for (invoice in invoices) {
                val base = safeName("${invoice.invoiceId}-${invoice.id}")

                // Original XML
                zos.putNextEntry(ZipEntry("xml/$base.xml"))
                zos.write(invoice.xmlContent.toByteArray(Charsets.UTF_8))
                zos.closeEntry()

                // Embedded / original PDF (if present and still on disk)
                val pdfPath = invoice.embeddedDocumentPath
                if (!pdfPath.isNullOrEmpty()) {
                    val pdfFile = File(pdfPath)
                    if (pdfFile.exists() && pdfFile.canRead()) {
                        zos.putNextEntry(ZipEntry("attachments/$base.pdf"))
                        pdfFile.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                }
            }
        }
        return baos.toByteArray()
    }

    /** Strips anything that could trip up FAT32/exFAT or accountant scripts. */
    private fun safeName(s: String): String =
        s.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
}

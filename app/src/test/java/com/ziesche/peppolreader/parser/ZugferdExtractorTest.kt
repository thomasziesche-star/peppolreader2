package com.ziesche.peppolreader.parser

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDDocumentNameDictionary
import com.tom_roush.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification
import com.tom_roush.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Attachment-lookup contract of [ZugferdExtractor]: priority order (factur-x.xml before the
 * legacy names before any other *.xml), case-insensitive matching, and the failure results.
 * The happy path through a real generated PDF/A-3 is covered by ZugferdPdfA3WriterTest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ZugferdExtractorTest {

    private fun pdfWithAttachments(vararg attachments: Pair<String, String>): ByteArray {
        PDDocument().use { doc ->
            doc.addPage(PDPage())
            if (attachments.isNotEmpty()) {
                val specs = attachments.associate { (name, content) ->
                    val bytes = content.toByteArray(Charsets.UTF_8)
                    val fs = PDComplexFileSpecification()
                    fs.file = name
                    fs.embeddedFile = PDEmbeddedFile(doc, ByteArrayInputStream(bytes)).apply {
                        subtype = "text/xml"
                        size = bytes.size
                    }
                    name to fs
                }
                val efTree = PDEmbeddedFilesNameTreeNode()
                efTree.names = specs
                val names = PDDocumentNameDictionary(doc.documentCatalog)
                names.embeddedFiles = efTree
                doc.documentCatalog.names = names
            }
            val out = ByteArrayOutputStream()
            doc.save(out)
            return out.toByteArray()
        }
    }

    private fun extract(pdf: ByteArray) = ZugferdExtractor().extract(ByteArrayInputStream(pdf))

    @Test
    fun `prefers factur-x over other xml attachments, case-insensitive`() {
        val pdf = pdfWithAttachments(
            "aaa-first.xml" to "<other/>",
            "FACTUR-X.XML" to "<invoice/>"
        )
        val result = extract(pdf)
        assertTrue(result is ZugferdExtractor.Result.Success)
        result as ZugferdExtractor.Result.Success
        assertEquals("FACTUR-X.XML", result.embeddedName)
        assertEquals("<invoice/>", result.xml)
        assertTrue("original PDF bytes preserved", result.originalPdf.contentEquals(pdf))
    }

    @Test
    fun `legacy zugferd name wins over unknown xml`() {
        val result = extract(
            pdfWithAttachments(
                "aaa-data.xml" to "<other/>",
                "zugferd-invoice.xml" to "<legacy/>"
            )
        )
        result as ZugferdExtractor.Result.Success
        assertEquals("zugferd-invoice.xml", result.embeddedName)
        assertEquals("<legacy/>", result.xml)
    }

    @Test
    fun `falls back to any xml but never a non-xml attachment`() {
        val result = extract(
            pdfWithAttachments(
                "readme.txt" to "not xml",
                "invoice-data.XML" to "<fallback/>"
            )
        )
        result as ZugferdExtractor.Result.Success
        assertEquals("invoice-data.XML", result.embeddedName)
        assertEquals("<fallback/>", result.xml)
    }

    @Test
    fun `plain pdf without attachments reports NoEmbeddedXml`() {
        assertEquals(ZugferdExtractor.Result.NoEmbeddedXml, extract(pdfWithAttachments()))
    }

    @Test
    fun `attachments without any xml report NoEmbeddedXml`() {
        assertEquals(
            ZugferdExtractor.Result.NoEmbeddedXml,
            extract(pdfWithAttachments("readme.txt" to "not xml"))
        )
    }

    @Test
    fun `garbage bytes report Error instead of throwing`() {
        val result = extract("this is not a pdf at all".toByteArray())
        assertTrue(result is ZugferdExtractor.Result.Error)
    }
}

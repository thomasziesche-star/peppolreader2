package com.ziesche.peppolreader.creator

import com.ziesche.peppolreader.creator.model.CreatorLine
import com.ziesche.peppolreader.creator.model.OutgoingInvoice
import com.ziesche.peppolreader.creator.pdf.ZugferdPdfA3Writer
import com.ziesche.peppolreader.creator.xml.ZugferdXmlBuilder
import com.ziesche.peppolreader.parser.CiiParser
import com.ziesche.peppolreader.parser.ZugferdExtractor
import android.graphics.Bitmap
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File

/**
 * Full pipeline self-test: build XML → write hybrid PDF/A-3 → re-read the embedded XML with the
 * app's own [ZugferdExtractor] + [CiiParser]. Guards the embedding structure end-to-end.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ZugferdPdfA3WriterTest {

    private fun draft() = OutgoingInvoice(
        invoiceNumber = "RE-2026-042",
        issueDate = "2026-06-04",
        dueDate = "2026-07-04",
        documentTypeCode = "380",
        currency = "EUR",
        sellerName = "Ziesche Software",
        sellerStreet = "Hauptstr. 1",
        sellerZip = "10115",
        sellerCity = "Berlin",
        sellerCountry = "DE",
        sellerVatId = "DE123456789",
        sellerIban = "DE89370400440532013000",
        sellerBic = "COBADEFFXXX",
        buyerName = "ACME GmbH",
        buyerStreet = "Industrieweg 5",
        buyerZip = "20095",
        buyerCity = "Hamburg",
        buyerCountry = "DE",
        lineItemsJson = CreatorLine.listToJson(
            listOf(CreatorLine("Consulting", quantity = 10.0, unit = "HUR", unitPrice = 100.0, vatRate = 19.0))
        ),
        paymentTermsNote = "Payable within 30 days."
    )

    @Test
    fun generatesHybridPdfThatRoundTrips() {
        val ctx = RuntimeEnvironment.getApplication()
        val invoice = draft()
        val xml = ZugferdXmlBuilder(invoice).build()
        val pdf = ZugferdPdfA3Writer(ctx).write(invoice, xml)

        assertTrue("starts with %PDF", String(pdf, 0, 5).startsWith("%PDF"))

        val result = ZugferdExtractor().extract(ByteArrayInputStream(pdf))
        assertTrue("extractable", result is ZugferdExtractor.Result.Success)
        result as ZugferdExtractor.Result.Success
        assertEquals("factur-x.xml", result.embeddedName)

        val parsed = CiiParser(result.xml, ctx).parse()
        assertEquals("RE-2026-042", parsed.invoice.id)
        assertEquals("Ziesche Software", parsed.supplier.name)
        assertEquals(1000.0, parsed.totals.lineExtension, 0.001)
        assertEquals(190.0, parsed.totals.taxAmount, 0.001)
    }

    /**
     * Every company-profile field must be visible on the rendered invoice page, and the logo
     * must be embedded as an image. Verified against the actually extracted PDF text, not the
     * drawing code.
     */
    @Test
    fun allCompanyProfileFieldsAppearOnPdf() {
        val ctx = RuntimeEnvironment.getApplication()

        val logoFile = File(ctx.filesDir, "profile_logo.png")
        val bitmap = Bitmap.createBitmap(160, 60, Bitmap.Config.ARGB_8888)
        logoFile.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }

        val invoice = draft().copy(
            sellerTaxNumber = "30/123/45678",
            sellerEmail = "info@ziesche.example",
            sellerPhone = "+49 30 1234567"
        )
        val xml = ZugferdXmlBuilder(invoice).build()
        val pdf = ZugferdPdfA3Writer(ctx).write(invoice, xml, logoFile.absolutePath)

        PDDocument.load(pdf).use { doc ->
            val text = PDFTextStripper().getText(doc)

            // Name, address (sender line, footer)
            assertTrue("name", text.contains("Ziesche Software"))
            assertTrue("street", text.contains("Hauptstr. 1"))
            assertTrue("zip+city", text.contains("10115 Berlin"))
            // Tax identifiers (info block + footer)
            assertTrue("vatId", text.contains("DE123456789"))
            assertTrue("taxNumber", text.contains("30/123/45678"))
            // Contact (footer)
            assertTrue("email", text.contains("info@ziesche.example"))
            assertTrue("phone", text.contains("+49 30 1234567"))
            // Bank details (footer; IBAN grouped in blocks of four)
            assertTrue("iban", text.contains("DE89 3704 0044 0532 0130 00"))
            assertTrue("bic", text.contains("COBADEFFXXX"))

            // Logo embedded as an image XObject on page 1.
            val hasImage = doc.getPage(0).resources.xObjectNames.iterator().hasNext()
            assertTrue("logo image embedded", hasImage)
        }
    }

    /**
     * A fully customized theme (Modern preset: petrol, sans headings, cool paper, band table
     * header) must still produce a valid, extractable PDF whose text survives — proves all
     * font pairings encode and the BAND header doesn't swallow labels or rows.
     */
    @Test
    fun customThemeRoundTripsAndKeepsText() {
        val ctx = RuntimeEnvironment.getApplication()
        val invoice = draft()
        val xml = ZugferdXmlBuilder(invoice).build()
        val theme = com.ziesche.peppolreader.creator.model.LayoutTheme.preset(
            com.ziesche.peppolreader.creator.model.LayoutTheme.PRESET_MODERN
        )
        val pdf = ZugferdPdfA3Writer(ctx).write(invoice, xml, null, theme)

        val result = ZugferdExtractor().extract(ByteArrayInputStream(pdf))
        assertTrue("extractable", result is ZugferdExtractor.Result.Success)
        val parsed = CiiParser((result as ZugferdExtractor.Result.Success).xml, ctx).parse()
        assertEquals("RE-2026-042", parsed.invoice.id)

        PDDocument.load(pdf).use { doc ->
            val text = PDFTextStripper().getText(doc)
            assertTrue("invoice number visible", text.contains("RE-2026-042"))
            assertTrue("band header labels visible", text.contains("BESCHREIBUNG"))
            assertTrue("grand total visible", text.contains("Gesamtbetrag"))
        }
    }

    /**
     * A quote (Angebot) is produced via [ZugferdPdfA3Writer.writePlain]: a plain PDF with the
     * visible page, the "Angebot" title — but NO embedded factur-x.xml (it is not an invoice).
     */
    @Test
    fun writePlainProducesQuotePdfWithoutEmbeddedXml() {
        val ctx = RuntimeEnvironment.getApplication()
        val quote = draft().copy(
            invoiceNumber = "AN-2026-007",
            documentTypeCode = OutgoingInvoice.DOC_TYPE_QUOTE
        )
        val pdf = ZugferdPdfA3Writer(ctx).writePlain(quote)

        assertTrue("starts with %PDF", String(pdf, 0, 5).startsWith("%PDF"))

        // No embedded XML: the extractor must NOT find a factur-x attachment.
        val result = ZugferdExtractor().extract(ByteArrayInputStream(pdf))
        assertTrue("no embedded XML in a quote", result !is ZugferdExtractor.Result.Success)

        PDDocument.load(pdf).use { doc ->
            val text = PDFTextStripper().getText(doc)
            assertTrue("quote title", text.contains("Angebot"))
            assertTrue("quote number", text.contains("AN-2026-007"))
        }
    }

    /** Logo on the letterhead + enough lines to force a page break must still round-trip. */
    @Test
    fun multiPageWithLogoRoundTrips() {
        val ctx = RuntimeEnvironment.getApplication()

        val logoFile = File(ctx.filesDir, "test_logo.png")
        val bitmap = Bitmap.createBitmap(200, 80, Bitmap.Config.ARGB_8888)
        logoFile.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }

        val manyLines = (1..45).map {
            CreatorLine("Position $it mit einer etwas längeren Beschreibung, die umgebrochen werden muss", quantity = 1.0, unit = "C62", unitPrice = 10.0, vatRate = 19.0)
        }
        val invoice = draft().copy(lineItemsJson = CreatorLine.listToJson(manyLines))
        val xml = ZugferdXmlBuilder(invoice).build()
        val pdf = ZugferdPdfA3Writer(ctx).write(invoice, xml, logoFile.absolutePath)

        PDDocument.load(pdf).use { doc ->
            assertTrue("expected page break, got ${doc.numberOfPages} page(s)", doc.numberOfPages >= 2)
        }

        val result = ZugferdExtractor().extract(ByteArrayInputStream(pdf))
        assertTrue("extractable", result is ZugferdExtractor.Result.Success)
        val parsed = CiiParser((result as ZugferdExtractor.Result.Success).xml, ctx).parse()
        assertEquals("RE-2026-042", parsed.invoice.id)
        assertEquals(450.0, parsed.totals.lineExtension, 0.001)
    }
}

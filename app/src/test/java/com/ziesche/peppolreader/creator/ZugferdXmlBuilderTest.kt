package com.ziesche.peppolreader.creator

import com.ziesche.peppolreader.creator.model.CreatorLine
import com.ziesche.peppolreader.creator.model.OutgoingInvoice
import com.ziesche.peppolreader.creator.xml.InvoiceTotalsCalculator
import com.ziesche.peppolreader.creator.xml.ZugferdXmlBuilder
import com.ziesche.peppolreader.parser.CiiParser
import com.ziesche.peppolreader.parser.InvoiceFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Round-trip self-test: the CII XML produced by [ZugferdXmlBuilder] must be detected as CII and
 * parse back through [CiiParser] with the same key figures — the in-app correctness guarantee.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ZugferdXmlBuilderTest {

    private fun sampleDraft() = OutgoingInvoice(
        invoiceNumber = "RE-2026-001",
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
        sellerEmail = "info@ziesche.example",
        sellerPhone = "+49 30 1234567",
        buyerName = "ACME GmbH",
        buyerStreet = "Industrieweg 5",
        buyerZip = "20095",
        buyerCity = "Hamburg",
        buyerCountry = "DE",
        buyerVatId = "DE987654321",
        lineItemsJson = CreatorLine.listToJson(
            listOf(
                CreatorLine("Consulting", quantity = 10.0, unit = "HUR", unitPrice = 100.0, vatRate = 19.0),
                CreatorLine("Travel", quantity = 1.0, unit = "C62", unitPrice = 50.0, vatRate = 7.0)
            )
        ),
        paymentTermsNote = "Payable within 30 days."
    )

    @Test
    fun roundTripThroughCiiParser() {
        val xml = ZugferdXmlBuilder(sampleDraft()).build()

        assertEquals(InvoiceFormat.CII, InvoiceFormat.detect(xml))

        val parsed = CiiParser(xml, RuntimeEnvironment.getApplication()).parse()

        assertEquals("RE-2026-001", parsed.invoice.id)
        assertEquals("2026-06-04", parsed.invoice.issueDate)
        assertEquals("2026-07-04", parsed.invoice.dueDate)
        assertEquals("EUR", parsed.invoice.currency)
        assertEquals("Ziesche Software", parsed.supplier.name)
        assertEquals("DE123456789", parsed.supplier.taxId)
        assertEquals("ACME GmbH", parsed.customer.name)

        // 10*100 + 1*50 = 1050 net; VAT 19% on 1000 = 190, 7% on 50 = 3.50 → 193.50; gross 1243.50
        assertEquals(1050.0, parsed.totals.lineExtension, 0.001)
        assertEquals(193.50, parsed.totals.taxAmount, 0.001)
        assertEquals(1243.50, parsed.totals.grossAmount, 0.001)
        assertEquals(2, parsed.totals.taxSubtotals.size)
    }

    @Test
    fun emitsPaymentReferenceAndSellerContact() {
        val xml = ZugferdXmlBuilder(sampleDraft()).build()

        // BT-83: remittance info present when an IBAN is on file, before InvoiceCurrencyCode.
        assertTrue(xml.contains("<ram:PaymentReference>RE-2026-001</ram:PaymentReference>"))
        assertTrue(xml.indexOf("<ram:PaymentReference>") < xml.indexOf("<ram:InvoiceCurrencyCode>"))
        // BG-6: phone precedes email inside the seller contact.
        assertTrue(xml.contains("<ram:CompleteNumber>+49 30 1234567</ram:CompleteNumber>"))
        assertTrue(xml.indexOf("TelephoneUniversalCommunication") < xml.indexOf("EmailURIUniversalCommunication"))
    }

    @Test
    fun totalsCalculatorMatchesExpectedBreakdown() {
        val totals = InvoiceTotalsCalculator.calculate(
            listOf(
                CreatorLine("A", quantity = 3.0, unitPrice = 9.99, vatRate = 19.0),
                CreatorLine("B", quantity = 2.0, unitPrice = 5.0, vatRate = 19.0)
            )
        )
        // 3*9.99=29.97 + 2*5=10 → 39.97 net, all at 19% → tax 7.59, gross 47.56
        assertEquals(39.97, totals.lineTotal, 0.001)
        assertEquals(7.59, totals.taxTotal, 0.001)
        assertEquals(47.56, totals.grandTotal, 0.001)
        assertEquals(1, totals.vatBreakdown.size)
        assertTrue(totals.vatBreakdown.first().rate == 19.0)
    }
}

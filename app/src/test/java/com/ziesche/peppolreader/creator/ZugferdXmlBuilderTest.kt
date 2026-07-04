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

    // ----- tax modes (§19 exemption / reverse charge) -------------------------------------------

    @Test
    fun smallBusinessRoundTrip() {
        val draft = sampleDraft().copy(
            taxMode = OutgoingInvoice.TAX_MODE_EXEMPT,
            exemptionReason = "Gemäß § 19 UStG wird keine Umsatzsteuer berechnet."
        )
        val xml = ZugferdXmlBuilder(draft).build()

        assertTrue(xml.contains("<ram:CategoryCode>E</ram:CategoryCode>"))
        assertTrue(xml.contains("<ram:ExemptionReason>Gemäß § 19 UStG wird keine Umsatzsteuer berechnet.</ram:ExemptionReason>"))
        // CII sequence inside BG-23: ExemptionReason before BasisAmount, CategoryCode before Rate.
        assertTrue(xml.indexOf("<ram:ExemptionReason>") < xml.indexOf("<ram:BasisAmount>"))
        assertTrue(xml.indexOf("<ram:CategoryCode>E<") < xml.indexOf("<ram:RateApplicablePercent>", xml.indexOf("ApplicableHeaderTradeSettlement")))
        // No positive rate anywhere despite the 19/7 % entered on the lines.
        assertTrue(!xml.contains("<ram:RateApplicablePercent>19.00</ram:RateApplicablePercent>"))

        val parsed = CiiParser(xml, RuntimeEnvironment.getApplication()).parse()
        assertEquals(1050.0, parsed.totals.lineExtension, 0.001)
        assertEquals(0.0, parsed.totals.taxAmount, 0.001)
        assertEquals(1050.0, parsed.totals.grossAmount, 0.001)
    }

    @Test
    fun reverseChargeEmitsAeAndVatex() {
        val draft = sampleDraft().copy(
            taxMode = OutgoingInvoice.TAX_MODE_REVERSE_CHARGE,
            exemptionReason = "Steuerschuldnerschaft des Leistungsempfängers (Reverse Charge)."
        )
        val xml = ZugferdXmlBuilder(draft).build()

        assertTrue(xml.contains("<ram:CategoryCode>AE</ram:CategoryCode>"))
        assertTrue(xml.contains("<ram:ExemptionReasonCode>VATEX-EU-AE</ram:ExemptionReasonCode>"))
        // BT-121 sits between CategoryCode and RateApplicablePercent.
        val cat = xml.indexOf("<ram:CategoryCode>AE<", xml.indexOf("ApplicableHeaderTradeSettlement"))
        val code = xml.indexOf("<ram:ExemptionReasonCode>")
        val rate = xml.indexOf("<ram:RateApplicablePercent>", cat)
        assertTrue(cat in 1 until code)
        assertTrue(code < rate)

        val parsed = CiiParser(xml, RuntimeEnvironment.getApplication()).parse()
        assertEquals(0.0, parsed.totals.taxAmount, 0.001)
    }

    // ----- document-level allowances/charges (BG-20/21) -----------------------------------------

    @Test
    fun documentAllowanceChargeRoundTrip() {
        val draft = sampleDraft().copy(
            allowancesJson = com.ziesche.peppolreader.creator.model.CreatorAllowanceCharge.listToJson(
                listOf(
                    com.ziesche.peppolreader.creator.model.CreatorAllowanceCharge(
                        isCharge = false, reason = "Treuerabatt", amount = 50.0, vatRate = 19.0
                    ),
                    com.ziesche.peppolreader.creator.model.CreatorAllowanceCharge(
                        isCharge = true, reason = "Versandkosten", amount = 20.0, vatRate = 19.0
                    )
                )
            )
        )
        val xml = ZugferdXmlBuilder(draft).build()

        // Position: after the last ApplicableTradeTax, before SpecifiedTradePaymentTerms.
        val lastTax = xml.lastIndexOf("<ram:ApplicableTradeTax>")
        val allowance = xml.indexOf("<ram:SpecifiedTradeAllowanceCharge>")
        val terms = xml.indexOf("<ram:SpecifiedTradePaymentTerms>")
        assertTrue(lastTax in 1 until allowance)
        assertTrue(allowance < terms)
        // BG-22: ChargeTotalAmount before AllowanceTotalAmount (CII order, reverse of UBL).
        assertTrue(xml.indexOf("<ram:ChargeTotalAmount>") in 1 until xml.indexOf("<ram:AllowanceTotalAmount>"))
        assertTrue(xml.contains("<ram:ChargeTotalAmount>20.00</ram:ChargeTotalAmount>"))
        assertTrue(xml.contains("<ram:AllowanceTotalAmount>50.00</ram:AllowanceTotalAmount>"))

        val parsed = CiiParser(xml, RuntimeEnvironment.getApplication()).parse()
        // 1050 lines − 50 + 20 = 1020 basis; VAT: 19% group 1000−50+20=970 → 184.30, 7% on 50 → 3.50
        assertEquals(1050.0, parsed.totals.lineExtension, 0.001)
        assertEquals(1020.0, parsed.totals.netAmount, 0.001)
        assertEquals(187.80, parsed.totals.taxAmount, 0.001)
        assertEquals(1207.80, parsed.totals.grossAmount, 0.001)
        assertEquals(2, parsed.allowanceCharges.size)
        val discount = parsed.allowanceCharges.first { !it.isCharge }
        assertEquals(50.0, discount.amount, 0.001)
        assertEquals("Treuerabatt", discount.reason)
    }

    @Test
    fun calculatorAppliesAllowancesPerRateGroup() {
        val lines = listOf(
            CreatorLine("A", quantity = 1.0, unitPrice = 1000.0, vatRate = 19.0),
            CreatorLine("B", quantity = 1.0, unitPrice = 50.0, vatRate = 7.0)
        )
        val totals = InvoiceTotalsCalculator.calculate(
            lines,
            listOf(
                com.ziesche.peppolreader.creator.model.CreatorAllowanceCharge(
                    isCharge = false, reason = "Rabatt", amount = 100.0, vatRate = 19.0
                )
            )
        )
        // The discount only reduces the 19% group: basis 900 → tax 171; 7% group stays 50 → 3.50.
        assertEquals(1050.0, totals.lineTotal, 0.001)
        assertEquals(950.0, totals.taxBasisTotal, 0.001)
        assertEquals(174.50, totals.taxTotal, 0.001)
        assertEquals(1124.50, totals.grandTotal, 0.001)
        assertEquals(100.0, totals.allowanceTotal, 0.001)
        assertEquals(0.0, totals.chargeTotal, 0.001)
        val g19 = totals.vatBreakdown.first { it.rate == 19.0 }
        assertEquals(900.0, g19.basis, 0.001)
        // BR-CO-13 identity.
        assertEquals(totals.lineTotal - totals.allowanceTotal + totals.chargeTotal, totals.taxBasisTotal, 0.001)
    }

    @Test
    fun calculatorForcesZeroRatesWhenExempt() {
        val totals = InvoiceTotalsCalculator.calculate(
            listOf(CreatorLine("A", quantity = 1.0, unitPrice = 100.0, vatRate = 19.0)),
            emptyList(),
            OutgoingInvoice.TAX_MODE_EXEMPT
        )
        assertEquals(0.0, totals.taxTotal, 0.001)
        assertEquals(100.0, totals.grandTotal, 0.001)
        assertEquals(1, totals.vatBreakdown.size)
        assertEquals(0.0, totals.vatBreakdown.first().rate, 0.001)
    }
}

package com.ziesche.peppolreader.creator.xml

import com.ziesche.peppolreader.creator.model.CreatorLine
import com.ziesche.peppolreader.creator.model.OutgoingInvoice
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

/**
 * Builds a UN/CEFACT Cross Industry Invoice (CII) XML string for ZUGFeRD 2.x in the
 * **EN 16931 (Comfort)** profile from an [OutgoingInvoice] draft.
 *
 * This is the inverse of [com.ziesche.peppolreader.parser.CiiParser]: the element names and
 * paths mirror what the parser reads, which is also how the in-app round-trip self-test works.
 * Element order follows the CII sequence (the schema is sequence-validated).
 *
 * Monetary figures come from [InvoiceTotalsCalculator] so the header summation, the per-rate
 * VAT breakdown (BG-23) and the per-line amounts are mutually consistent.
 */
class ZugferdXmlBuilder(private val invoice: OutgoingInvoice) {

    private val lines: List<CreatorLine> =
        invoice.lines.filter { it.description.isNotBlank() }
    private val totals = InvoiceTotalsCalculator.calculate(lines)
    private val currency = invoice.currency.ifBlank { "EUR" }

    fun build(): String {
        val sb = StringBuilder(4096)
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append(
            "<rsm:CrossIndustryInvoice " +
                "xmlns:rsm=\"urn:un:unece:uncefact:data:standard:CrossIndustryInvoice:100\" " +
                "xmlns:ram=\"urn:un:unece:uncefact:data:standard:ReusableAggregateBusinessInformationEntity:100\" " +
                "xmlns:udt=\"urn:un:unece:uncefact:data:standard:UnqualifiedDataType:100\">\n"
        )

        // --- ExchangedDocumentContext (profile identifier) ---
        sb.append("  <rsm:ExchangedDocumentContext>\n")
        sb.append("    <ram:GuidelineSpecifiedDocumentContextParameter>\n")
        sb.append("      <ram:ID>$PROFILE_EN16931</ram:ID>\n")
        sb.append("    </ram:GuidelineSpecifiedDocumentContextParameter>\n")
        sb.append("  </rsm:ExchangedDocumentContext>\n")

        // --- ExchangedDocument (header) ---
        sb.append("  <rsm:ExchangedDocument>\n")
        sb.append("    <ram:ID>${esc(invoice.invoiceNumber)}</ram:ID>\n")
        sb.append("    <ram:TypeCode>${esc(invoice.documentTypeCode)}</ram:TypeCode>\n")
        sb.append("    <ram:IssueDateTime>\n")
        sb.append("      <udt:DateTimeString format=\"102\">${toCiiDate(invoice.issueDate)}</udt:DateTimeString>\n")
        sb.append("    </ram:IssueDateTime>\n")
        sb.append("  </rsm:ExchangedDocument>\n")

        // --- SupplyChainTradeTransaction ---
        sb.append("  <rsm:SupplyChainTradeTransaction>\n")
        appendLines(sb)
        appendAgreement(sb)
        sb.append("    <ram:ApplicableHeaderTradeDelivery/>\n")
        appendSettlement(sb)
        sb.append("  </rsm:SupplyChainTradeTransaction>\n")

        sb.append("</rsm:CrossIndustryInvoice>\n")
        return sb.toString()
    }

    // ----- line items ---------------------------------------------------------------------

    private fun appendLines(sb: StringBuilder) {
        lines.forEachIndexed { index, line ->
            val lineNet = (BigDecimal(line.quantity) * BigDecimal(line.unitPrice)).scale2()
            sb.append("    <ram:IncludedSupplyChainTradeLineItem>\n")
            sb.append("      <ram:AssociatedDocumentLineDocument>\n")
            sb.append("        <ram:LineID>${index + 1}</ram:LineID>\n")
            sb.append("      </ram:AssociatedDocumentLineDocument>\n")
            sb.append("      <ram:SpecifiedTradeProduct>\n")
            sb.append("        <ram:Name>${esc(line.description)}</ram:Name>\n")
            sb.append("      </ram:SpecifiedTradeProduct>\n")
            sb.append("      <ram:SpecifiedLineTradeAgreement>\n")
            sb.append("        <ram:NetPriceProductTradePrice>\n")
            sb.append("          <ram:ChargeAmount>${money(line.unitPrice)}</ram:ChargeAmount>\n")
            sb.append("        </ram:NetPriceProductTradePrice>\n")
            sb.append("      </ram:SpecifiedLineTradeAgreement>\n")
            sb.append("      <ram:SpecifiedLineTradeDelivery>\n")
            sb.append("        <ram:BilledQuantity unitCode=\"${esc(line.unit.ifBlank { "C62" })}\">${qty(line.quantity)}</ram:BilledQuantity>\n")
            sb.append("      </ram:SpecifiedLineTradeDelivery>\n")
            sb.append("      <ram:SpecifiedLineTradeSettlement>\n")
            sb.append("        <ram:ApplicableTradeTax>\n")
            sb.append("          <ram:TypeCode>VAT</ram:TypeCode>\n")
            sb.append("          <ram:CategoryCode>${vatCategory(line.vatRate)}</ram:CategoryCode>\n")
            sb.append("          <ram:RateApplicablePercent>${money(line.vatRate)}</ram:RateApplicablePercent>\n")
            sb.append("        </ram:ApplicableTradeTax>\n")
            sb.append("        <ram:SpecifiedTradeSettlementLineMonetarySummation>\n")
            sb.append("          <ram:LineTotalAmount>${money(lineNet.toDouble())}</ram:LineTotalAmount>\n")
            sb.append("        </ram:SpecifiedTradeSettlementLineMonetarySummation>\n")
            sb.append("      </ram:SpecifiedLineTradeSettlement>\n")
            sb.append("    </ram:IncludedSupplyChainTradeLineItem>\n")
        }
    }

    // ----- header trade agreement (parties) -----------------------------------------------

    private fun appendAgreement(sb: StringBuilder) {
        sb.append("    <ram:ApplicableHeaderTradeAgreement>\n")
        // Seller
        sb.append("      <ram:SellerTradeParty>\n")
        sb.append("        <ram:Name>${esc(invoice.sellerName)}</ram:Name>\n")
        appendAddress(sb, invoice.sellerStreet, invoice.sellerZip, invoice.sellerCity, invoice.sellerCountry)
        val phone = invoice.sellerPhone?.takeIf { it.isNotBlank() }
        val email = invoice.sellerEmail?.takeIf { it.isNotBlank() }
        if (phone != null || email != null) {
            // BG-6 seller contact; CII sequence: Telephone before EmailURI.
            sb.append("        <ram:DefinedTradeContact>\n")
            phone?.let {
                sb.append("          <ram:TelephoneUniversalCommunication>\n")
                sb.append("            <ram:CompleteNumber>${esc(it)}</ram:CompleteNumber>\n")
                sb.append("          </ram:TelephoneUniversalCommunication>\n")
            }
            email?.let {
                sb.append("          <ram:EmailURIUniversalCommunication>\n")
                sb.append("            <ram:URIID>${esc(it)}</ram:URIID>\n")
                sb.append("          </ram:EmailURIUniversalCommunication>\n")
            }
            sb.append("        </ram:DefinedTradeContact>\n")
        }
        invoice.sellerVatId?.takeIf { it.isNotBlank() }?.let {
            sb.append("        <ram:SpecifiedTaxRegistration>\n")
            sb.append("          <ram:ID schemeID=\"VA\">${esc(it)}</ram:ID>\n")
            sb.append("        </ram:SpecifiedTaxRegistration>\n")
        }
        invoice.sellerTaxNumber?.takeIf { it.isNotBlank() }?.let {
            sb.append("        <ram:SpecifiedTaxRegistration>\n")
            sb.append("          <ram:ID schemeID=\"FC\">${esc(it)}</ram:ID>\n")
            sb.append("        </ram:SpecifiedTaxRegistration>\n")
        }
        sb.append("      </ram:SellerTradeParty>\n")
        // Buyer
        sb.append("      <ram:BuyerTradeParty>\n")
        sb.append("        <ram:Name>${esc(invoice.buyerName)}</ram:Name>\n")
        appendAddress(sb, invoice.buyerStreet, invoice.buyerZip, invoice.buyerCity, invoice.buyerCountry)
        invoice.buyerVatId?.takeIf { it.isNotBlank() }?.let {
            sb.append("        <ram:SpecifiedTaxRegistration>\n")
            sb.append("          <ram:ID schemeID=\"VA\">${esc(it)}</ram:ID>\n")
            sb.append("        </ram:SpecifiedTaxRegistration>\n")
        }
        sb.append("      </ram:BuyerTradeParty>\n")
        sb.append("    </ram:ApplicableHeaderTradeAgreement>\n")
    }

    private fun appendAddress(
        sb: StringBuilder,
        street: String?,
        zip: String?,
        city: String?,
        country: String?
    ) {
        sb.append("        <ram:PostalTradeAddress>\n")
        zip?.takeIf { it.isNotBlank() }?.let { sb.append("          <ram:PostcodeCode>${esc(it)}</ram:PostcodeCode>\n") }
        street?.takeIf { it.isNotBlank() }?.let { sb.append("          <ram:LineOne>${esc(it)}</ram:LineOne>\n") }
        city?.takeIf { it.isNotBlank() }?.let { sb.append("          <ram:CityName>${esc(it)}</ram:CityName>\n") }
        sb.append("          <ram:CountryID>${esc((country ?: "DE").ifBlank { "DE" })}</ram:CountryID>\n")
        sb.append("        </ram:PostalTradeAddress>\n")
    }

    // ----- header trade settlement (taxes, payment, totals) -------------------------------

    private fun appendSettlement(sb: StringBuilder) {
        sb.append("    <ram:ApplicableHeaderTradeSettlement>\n")
        // BT-83 remittance information: lets the buyer reference the invoice in the transfer.
        // CII sequence: PaymentReference must precede InvoiceCurrencyCode.
        invoice.sellerIban?.takeIf { it.isNotBlank() }?.let {
            sb.append("      <ram:PaymentReference>${esc(invoice.invoiceNumber)}</ram:PaymentReference>\n")
        }
        sb.append("      <ram:InvoiceCurrencyCode>${esc(currency)}</ram:InvoiceCurrencyCode>\n")

        // Payment means (SEPA credit transfer) when an IBAN is on file.
        invoice.sellerIban?.takeIf { it.isNotBlank() }?.let { iban ->
            sb.append("      <ram:SpecifiedTradeSettlementPaymentMeans>\n")
            sb.append("        <ram:TypeCode>58</ram:TypeCode>\n")
            sb.append("        <ram:PayeePartyCreditorFinancialAccount>\n")
            sb.append("          <ram:IBANID>${esc(iban)}</ram:IBANID>\n")
            sb.append("        </ram:PayeePartyCreditorFinancialAccount>\n")
            invoice.sellerBic?.takeIf { it.isNotBlank() }?.let { bic ->
                sb.append("        <ram:PayeeSpecifiedCreditorFinancialInstitution>\n")
                sb.append("          <ram:BICID>${esc(bic)}</ram:BICID>\n")
                sb.append("        </ram:PayeeSpecifiedCreditorFinancialInstitution>\n")
            }
            sb.append("      </ram:SpecifiedTradeSettlementPaymentMeans>\n")
        }

        // VAT breakdown per rate (BG-23).
        totals.vatBreakdown.forEach { e ->
            sb.append("      <ram:ApplicableTradeTax>\n")
            sb.append("        <ram:CalculatedAmount>${money(e.tax)}</ram:CalculatedAmount>\n")
            sb.append("        <ram:TypeCode>VAT</ram:TypeCode>\n")
            sb.append("        <ram:BasisAmount>${money(e.basis)}</ram:BasisAmount>\n")
            sb.append("        <ram:CategoryCode>${vatCategory(e.rate)}</ram:CategoryCode>\n")
            sb.append("        <ram:RateApplicablePercent>${money(e.rate)}</ram:RateApplicablePercent>\n")
            sb.append("      </ram:ApplicableTradeTax>\n")
        }

        // Payment terms (note + due date).
        val note = invoice.paymentTermsNote?.takeIf { it.isNotBlank() }
        val due = invoice.dueDate?.takeIf { it.isNotBlank() }
        if (note != null || due != null) {
            sb.append("      <ram:SpecifiedTradePaymentTerms>\n")
            note?.let { sb.append("        <ram:Description>${esc(it)}</ram:Description>\n") }
            due?.let {
                sb.append("        <ram:DueDateDateTime>\n")
                sb.append("          <udt:DateTimeString format=\"102\">${toCiiDate(it)}</udt:DateTimeString>\n")
                sb.append("        </ram:DueDateDateTime>\n")
            }
            sb.append("      </ram:SpecifiedTradePaymentTerms>\n")
        }

        // Monetary summation (BG-22).
        sb.append("      <ram:SpecifiedTradeSettlementHeaderMonetarySummation>\n")
        sb.append("        <ram:LineTotalAmount>${money(totals.lineTotal)}</ram:LineTotalAmount>\n")
        sb.append("        <ram:TaxBasisTotalAmount>${money(totals.taxBasisTotal)}</ram:TaxBasisTotalAmount>\n")
        sb.append("        <ram:TaxTotalAmount currencyID=\"${esc(currency)}\">${money(totals.taxTotal)}</ram:TaxTotalAmount>\n")
        sb.append("        <ram:GrandTotalAmount>${money(totals.grandTotal)}</ram:GrandTotalAmount>\n")
        sb.append("        <ram:DuePayableAmount>${money(totals.payable)}</ram:DuePayableAmount>\n")
        sb.append("      </ram:SpecifiedTradeSettlementHeaderMonetarySummation>\n")

        sb.append("    </ram:ApplicableHeaderTradeSettlement>\n")
    }

    // ----- formatting helpers -------------------------------------------------------------

    private fun BigDecimal.scale2(): BigDecimal = setScale(2, RoundingMode.HALF_UP)

    private fun money(value: Double): String = String.format(Locale.US, "%.2f", value)

    /** Quantity with up to 4 decimals, trailing zeros trimmed (min "0"). */
    private fun qty(value: Double): String {
        val bd = BigDecimal(value).setScale(4, RoundingMode.HALF_UP).stripTrailingZeros()
        return if (bd.scale() < 0) bd.setScale(0).toPlainString() else bd.toPlainString()
    }

    private fun vatCategory(rate: Double): String = if (rate > 0.0) "S" else "Z"

    /** ISO yyyy-MM-dd → CII format "102" (yyyyMMdd). Pass-through if already 8 digits. */
    private fun toCiiDate(iso: String): String {
        val digits = iso.filter { it.isDigit() }
        return if (digits.length >= 8) digits.substring(0, 8) else digits
    }

    private fun esc(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    companion object {
        /** EN 16931 (Comfort) guideline identifier used by ZUGFeRD 2.x. */
        const val PROFILE_EN16931 = "urn:cen.eu:en16931:2017"
    }
}

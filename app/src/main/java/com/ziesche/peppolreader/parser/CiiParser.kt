package com.ziesche.peppolreader.parser

import android.content.Context
import com.ziesche.peppolreader.R
import com.ziesche.peppolreader.data.model.AllowanceCharge
import com.ziesche.peppolreader.data.model.InvoiceDetails
import com.ziesche.peppolreader.data.model.InvoiceLineItem
import com.ziesche.peppolreader.data.model.InvoiceTotals
import com.ziesche.peppolreader.data.model.ParsedInvoice
import com.ziesche.peppolreader.data.model.Party
import com.ziesche.peppolreader.data.model.PayeeFinancialAccount
import com.ziesche.peppolreader.data.model.PaymentMeans
import com.ziesche.peppolreader.data.model.TaxSubtotal
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

/**
 * Parser for UN/CEFACT Cross Industry Invoice (CII) — the XML schema used by
 * ZUGFeRD 1.x / 2.x, Factur-X and the XRechnung-CII profile.
 *
 * Produces the same [ParsedInvoice] shape as [PeppolParser] so the rest of the
 * app does not care which format the XML originally was in.
 */
class CiiParser(private val xmlContent: String, private val context: Context) {

    private var root: Map<String, Any> = emptyMap()

    fun parse(): ParsedInvoice {
        val pull = XmlPullParserFactory.newInstance().newPullParser()
        pull.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        pull.setInput(StringReader(xmlContent))
        root = XmlMapReader.parseDocument(pull)

        // Unwrap CrossIndustryInvoice root (always wrapped in the raw doc map)
        @Suppress("UNCHECKED_CAST")
        if (root.containsKey("CrossIndustryInvoice")) {
            root = root["CrossIndustryInvoice"] as Map<String, Any>
        }

        return ParsedInvoice(
            invoice = parseDetails(),
            supplier = parseParty("SupplyChainTradeTransaction/ApplicableHeaderTradeAgreement/SellerTradeParty"),
            customer = parseParty("SupplyChainTradeTransaction/ApplicableHeaderTradeAgreement/BuyerTradeParty"),
            items = parseLines(),
            allowanceCharges = parseAllowanceCharges(),
            totals = parseTotals(),
            paymentMeans = parsePaymentMeans(),
            paymentTermsNote = parsePaymentTermsNote(),
            embeddedDocument = null,
            formatLabel = detectFormatLabel(),
            documentTypeCode = getText(root, "ExchangedDocument/TypeCode").takeIf { it.isNotEmpty() }
                ?: "380"
        )
    }

    private fun detectFormatLabel(): String {
        val guideline = getText(
            root,
            "ExchangedDocumentContext/GuidelineSpecifiedDocumentContextParameter/ID"
        ).lowercase()
        val business = getText(
            root,
            "ExchangedDocumentContext/BusinessProcessSpecifiedDocumentContextParameter/ID"
        ).lowercase()
        val combined = "$guideline $business"
        return when {
            "factur-x" in combined || "facturx" in combined -> "Factur-X"
            "zugferd" in combined -> "ZUGFeRD"
            "xrechnung" in combined -> "XRechnung (CII)"
            else -> "ZUGFeRD / Factur-X"
        }
    }

    private fun parseDetails(): InvoiceDetails {
        val currency = getText(
            root,
            "SupplyChainTradeTransaction/ApplicableHeaderTradeSettlement/InvoiceCurrencyCode",
            "EUR"
        )
        val issueDateMap = getMap("ExchangedDocument/IssueDateTime/DateTimeString")
        val dueDateMap = getMap(
            "SupplyChainTradeTransaction/ApplicableHeaderTradeSettlement/" +
                "SpecifiedTradePaymentTerms/DueDateDateTime/DateTimeString"
        )

        return InvoiceDetails(
            id = getText(root, "ExchangedDocument/ID"),
            issueDate = formatDate(issueDateMap),
            dueDate = formatDate(dueDateMap).takeIf { it.isNotEmpty() },
            currency = currency,
            orderId = getText(
                root,
                "SupplyChainTradeTransaction/ApplicableHeaderTradeAgreement/" +
                    "BuyerOrderReferencedDocument/IssuerAssignedID"
            ).takeIf { it.isNotEmpty() },
            salesOrderId = getText(
                root,
                "SupplyChainTradeTransaction/ApplicableHeaderTradeAgreement/" +
                    "SellerOrderReferencedDocument/IssuerAssignedID"
            ).takeIf { it.isNotEmpty() }
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseParty(path: String): Party {
        val partyMap = getMap(path) ?: return Party(name = "Unknown")
        val address = partyMap["PostalTradeAddress"] as? Map<String, Any>

        val taxRegs = listOfMaps(partyMap["SpecifiedTaxRegistration"])
        val vatId = taxRegs
            .firstOrNull { (getAttribute(it["ID"] as? Map<String, Any>, "schemeID") ?: "") == "VA" }
            ?.let { getText(it, "ID") }
            ?: taxRegs.firstOrNull()?.let { getText(it, "ID") }

        val contact = partyMap["DefinedTradeContact"] as? Map<String, Any>

        return Party(
            name = getText(partyMap, "Name").ifEmpty {
                getText(partyMap, "SpecifiedLegalOrganization/TradingBusinessName")
            },
            street = (getText(address, "LineOne") + " " + getText(address, "LineTwo")).trim()
                .takeIf { it.isNotEmpty() },
            city = getText(address, "CityName").takeIf { it.isNotEmpty() },
            zip = getText(address, "PostcodeCode").takeIf { it.isNotEmpty() },
            country = getText(address, "CountryID").takeIf { it.isNotEmpty() },
            taxId = vatId?.takeIf { it.isNotEmpty() },
            contactName = contact?.let { getText(it, "PersonName") }?.takeIf { it.isNotEmpty() },
            email = contact?.let { getText(it, "EmailURIUniversalCommunication/URIID") }
                ?.takeIf { it.isNotEmpty() },
            phone = contact?.let { getText(it, "TelephoneUniversalCommunication/CompleteNumber") }
                ?.takeIf { it.isNotEmpty() }
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseLines(): List<InvoiceLineItem> {
        val lines = mutableListOf<InvoiceLineItem>()
        val lineNodes = listOfMaps(
            getMap("SupplyChainTradeTransaction")?.get("IncludedSupplyChainTradeLineItem")
        )

        for (node in lineNodes) {
            val product = node["SpecifiedTradeProduct"] as? Map<String, Any>
            val agreement = node["SpecifiedLineTradeAgreement"] as? Map<String, Any>
            val delivery = node["SpecifiedLineTradeDelivery"] as? Map<String, Any>
            val settlement = node["SpecifiedLineTradeSettlement"] as? Map<String, Any>

            val quantityMap = delivery?.get("BilledQuantity") as? Map<String, Any>
            val quantity = (quantityMap?.get("#text") as? String)?.toDoubleOrNull() ?: 0.0
            val unit = getAttribute(quantityMap, "unitCode") ?: ""

            val price = getText(agreement, "NetPriceProductTradePrice/ChargeAmount")
                .toDoubleOrNull()
                ?: getText(agreement, "GrossPriceProductTradePrice/ChargeAmount").toDoubleOrNull()
                ?: 0.0

            val lineTotal = getText(
                settlement,
                "SpecifiedTradeSettlementLineMonetarySummation/LineTotalAmount"
            ).toDoubleOrNull() ?: (quantity * price)

            lines.add(
                InvoiceLineItem(
                    id = getText(product, "SellerAssignedID"),
                    description = getText(product, "Name"),
                    quantity = quantity,
                    unit = unit,
                    price = price,
                    lineTotal = lineTotal,
                    isCharge = false
                )
            )

            // Line-level AllowanceCharge inside SpecifiedLineTradeSettlement
            val acList = listOfMaps(settlement?.get("SpecifiedTradeAllowanceCharge"))
            for (ac in acList) {
                val isCharge = getText(
                    ac,
                    "ChargeIndicator/Indicator"
                ).lowercase() == "true"
                val amount = getText(ac, "ActualAmount").toDoubleOrNull() ?: 0.0
                val reason = getText(ac, "Reason")
                val description = if (isCharge) {
                    context.getString(R.string.surcharge_label, reason)
                } else {
                    context.getString(R.string.discount_label, reason)
                }
                lines.add(
                    InvoiceLineItem(
                        id = "",
                        description = description,
                        quantity = 1.0,
                        unit = "EA",
                        price = if (isCharge) amount else -amount,
                        lineTotal = if (isCharge) amount else -amount,
                        isCharge = true
                    )
                )
            }
        }

        return lines
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseAllowanceCharges(): List<AllowanceCharge> {
        val settlement = getMap("SupplyChainTradeTransaction/ApplicableHeaderTradeSettlement")
            ?: return emptyList()
        val acList = listOfMaps(settlement["SpecifiedTradeAllowanceCharge"])
        return acList.map { ac ->
            AllowanceCharge(
                isCharge = getText(ac, "ChargeIndicator/Indicator").lowercase() == "true",
                reason = getText(ac, "Reason"),
                amount = getText(ac, "ActualAmount").toDoubleOrNull() ?: 0.0,
                taxPercent = getText(ac, "CategoryTradeTax/RateApplicablePercent")
                    .toDoubleOrNull() ?: 0.0
            )
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseTotals(): InvoiceTotals {
        val settlement = getMap("SupplyChainTradeTransaction/ApplicableHeaderTradeSettlement")
            ?: return InvoiceTotals()
        val summation = settlement["SpecifiedTradeSettlementHeaderMonetarySummation"]
            as? Map<String, Any> ?: return InvoiceTotals()

        val taxList = listOfMaps(settlement["ApplicableTradeTax"])
        val subtotals = taxList.map { tt ->
            TaxSubtotal(
                taxableAmount = getText(tt, "BasisAmount").toDoubleOrNull() ?: 0.0,
                taxAmount = getText(tt, "CalculatedAmount").toDoubleOrNull() ?: 0.0,
                percent = getText(tt, "RateApplicablePercent").toDoubleOrNull() ?: 0.0
            )
        }
        val taxAmount = subtotals.sumOf { it.taxAmount }.takeIf { it != 0.0 }
            ?: getText(summation, "TaxTotalAmount").toDoubleOrNull() ?: 0.0

        return InvoiceTotals(
            lineExtension = getText(summation, "LineTotalAmount").toDoubleOrNull() ?: 0.0,
            allowanceTotal = getText(summation, "AllowanceTotalAmount").toDoubleOrNull() ?: 0.0,
            chargeTotal = getText(summation, "ChargeTotalAmount").toDoubleOrNull() ?: 0.0,
            netAmount = getText(summation, "TaxBasisTotalAmount").toDoubleOrNull() ?: 0.0,
            taxAmount = taxAmount,
            grossAmount = getText(summation, "GrandTotalAmount").toDoubleOrNull() ?: 0.0,
            payableAmount = getText(summation, "DuePayableAmount").toDoubleOrNull()
                ?: getText(summation, "GrandTotalAmount").toDoubleOrNull() ?: 0.0,
            taxSubtotals = subtotals
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun parsePaymentMeans(): PaymentMeans? {
        val pm = getMap(
            "SupplyChainTradeTransaction/ApplicableHeaderTradeSettlement/" +
                "SpecifiedTradeSettlementPaymentMeans"
        ) ?: return null

        val code = getText(pm, "TypeCode")
        val accountMap = pm["PayeePartyCreditorFinancialAccount"] as? Map<String, Any>
        val institutionMap = pm["PayeeSpecifiedCreditorFinancialInstitution"] as? Map<String, Any>

        val account = if (accountMap != null) {
            PayeeFinancialAccount(
                id = getText(accountMap, "IBANID").ifEmpty { getText(accountMap, "ProprietaryID") },
                name = getText(accountMap, "AccountName").takeIf { it.isNotEmpty() },
                financialInstitutionBranchId = institutionMap?.let { getText(it, "BICID") }
                    ?.takeIf { it.isNotEmpty() }
            )
        } else null

        return PaymentMeans(code, account)
    }

    private fun parsePaymentTermsNote(): String? {
        val note = getText(
            root,
            "SupplyChainTradeTransaction/ApplicableHeaderTradeSettlement/" +
                "SpecifiedTradePaymentTerms/Description"
        )
        return note.takeIf { it.isNotEmpty() }
    }

    // --- date helper -----------------------------------------------------

    /**
     * CII typically uses qualified date strings: format="102" → YYYYMMDD.
     * We normalize to ISO YYYY-MM-DD so it matches the DB columns populated by [PeppolParser].
     */
    private fun formatDate(map: Map<String, Any>?): String {
        if (map == null) return ""
        val raw = (map["#text"] as? String)?.trim() ?: return ""
        val format = getAttribute(map, "format")
        return when {
            format == "102" && raw.length == 8 ->
                "${raw.substring(0, 4)}-${raw.substring(4, 6)}-${raw.substring(6, 8)}"
            raw.length == 8 && raw.all { it.isDigit() } ->
                "${raw.substring(0, 4)}-${raw.substring(4, 6)}-${raw.substring(6, 8)}"
            else -> raw
        }
    }

    // --- generic XML→Map helpers (delegated to XmlMapReader) -------------

    private fun getText(map: Map<String, Any>?, path: String, default: String = ""): String =
        XmlMapReader.getText(map, path, default)

    private fun getAttribute(map: Map<String, Any>?, name: String): String? =
        XmlMapReader.getAttribute(map, name)

    private fun getMap(path: String): Map<String, Any>? =
        XmlMapReader.getMap(root, path)

    private fun listOfMaps(value: Any?): List<Map<String, Any>> =
        XmlMapReader.listOfMaps(value)
}

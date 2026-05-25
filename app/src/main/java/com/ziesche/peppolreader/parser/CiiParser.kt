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
        root = parseDocument(pull)

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

    // --- generic XML→Map helpers (same shape as PeppolParser) ------------

    private fun parseDocument(parser: XmlPullParser): Map<String, Any> {
        val result = mutableMapOf<String, Any>()
        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG) {
                result.putAll(parseElement(parser))
                return result
            }
            eventType = parser.next()
        }
        return result
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseElement(parser: XmlPullParser): Map<String, Any> {
        val outer = mutableMapOf<String, Any>()
        val elementName = parser.name
        val inner = mutableMapOf<String, Any>()

        val attrs = mutableMapOf<String, String>()
        for (i in 0 until parser.attributeCount) {
            attrs[parser.getAttributeName(i)] = parser.getAttributeValue(i)
        }
        if (attrs.isNotEmpty()) inner["@attributes"] = attrs

        val textBuf = StringBuilder()
        var eventType = parser.next()
        while (eventType != XmlPullParser.END_TAG || parser.name != elementName) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    val childName = parser.name
                    val child = parseElement(parser)[childName] as Map<String, Any>
                    when (val existing = inner[childName]) {
                        null -> inner[childName] = child
                        is MutableList<*> -> (existing as MutableList<Map<String, Any>>).add(child)
                        is Map<*, *> -> inner[childName] = mutableListOf(existing as Map<String, Any>, child)
                    }
                }
                XmlPullParser.TEXT -> {
                    val text = parser.text?.trim()
                    if (!text.isNullOrEmpty()) textBuf.append(text)
                }
            }
            eventType = parser.next()
        }
        if (textBuf.isNotEmpty()) inner["#text"] = textBuf.toString()

        outer[elementName] = inner
        return outer
    }

    private fun getText(map: Map<String, Any>?, path: String, default: String = ""): String {
        if (map == null) return default
        var current: Any? = map
        for (part in path.split("/")) {
            current = when (current) {
                is Map<*, *> -> current[part]
                is List<*> -> (current.firstOrNull() as? Map<*, *>)?.get(part)
                else -> null
            }
            if (current == null) return default
        }
        return when (current) {
            is String -> current
            is Map<*, *> -> (current["#text"] as? String) ?: default
            else -> default
        }
    }

    private fun getAttribute(map: Map<String, Any>?, name: String): String? {
        val attrs = map?.get("@attributes") as? Map<*, *>
        return attrs?.get(name) as? String
    }

    @Suppress("UNCHECKED_CAST")
    private fun getMap(path: String): Map<String, Any>? {
        var current: Any? = root
        for (part in path.split("/")) {
            current = when (current) {
                is Map<*, *> -> current[part]
                is List<*> -> current.firstOrNull()
                else -> null
            }
            if (current == null) return null
        }
        return current as? Map<String, Any>
    }

    @Suppress("UNCHECKED_CAST")
    private fun listOfMaps(value: Any?): List<Map<String, Any>> = when (value) {
        null -> emptyList()
        is List<*> -> value as List<Map<String, Any>>
        is Map<*, *> -> listOf(value as Map<String, Any>)
        else -> emptyList()
    }
}

package com.ziesche.peppolreader.parser

import com.ziesche.peppolreader.data.model.*
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

/**
 * Parser for Peppol BIS Billing 3.0 XML invoices
 * Ported from Python xml_parser.py
 */
class PeppolParser(private val xmlContent: String, private val context: android.content.Context) {
    
    companion object {
        private const val NS_CAC = "urn:oasis:names:specification:ubl:schema:xsd:CommonAggregateComponents-2"
        private const val NS_CBC = "urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2"
        private const val NS_UBL = "urn:oasis:names:specification:ubl:schema:xsd:Invoice-2"
    }
    
    private var invoiceElement: Map<String, Any> = emptyMap()
    /** "Invoice" or "CreditNote" — captured after unwrapping any StandardBusinessDocument. */
    private var rootElementName: String = "Invoice"

    /**
     * Parse the XML content and return a complete ParsedInvoice
     */
    fun parse(): ParsedInvoice {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(StringReader(xmlContent))

        // Parse the entire document into a map structure
        invoiceElement = parseDocument(parser)

        // Handle StandardBusinessDocument wrapper
        if (invoiceElement.containsKey("Invoice")) {
            @Suppress("UNCHECKED_CAST")
            invoiceElement = invoiceElement["Invoice"] as Map<String, Any>
            rootElementName = "Invoice"
        } else if (invoiceElement.containsKey("CreditNote")) {
            @Suppress("UNCHECKED_CAST")
            invoiceElement = invoiceElement["CreditNote"] as Map<String, Any>
            rootElementName = "CreditNote"
        }
        
        return ParsedInvoice(
            invoice = parseInvoiceDetails(),
            supplier = parseParty("AccountingSupplierParty"),
            customer = parseParty("AccountingCustomerParty"),

            items = parseInvoiceLines(),

            allowanceCharges = parseAllowanceCharges(),
            totals = parseTotals(),
            paymentMeans = parsePaymentMeans(),
            paymentTermsNote = parsePaymentTerms(),
            embeddedDocument = parseEmbeddedDocument(),
            formatLabel = detectFormatLabel(),
            documentTypeCode = detectDocumentTypeCode()
        )
    }

    private fun detectDocumentTypeCode(): String {
        // Explicit element wins if present (UBL Invoice uses InvoiceTypeCode, CreditNote uses CreditNoteTypeCode).
        val explicit = getText(invoiceElement, "InvoiceTypeCode")
            .ifEmpty { getText(invoiceElement, "CreditNoteTypeCode") }
        if (explicit.isNotEmpty()) return explicit
        return if (rootElementName == "CreditNote") "381" else "380"
    }

    private fun detectFormatLabel(): String {
        val customization = getText(invoiceElement, "CustomizationID").lowercase()
        val profile = getText(invoiceElement, "ProfileID").lowercase()
        val combined = "$customization $profile"
        return when {
            "xrechnung" in combined -> "XRechnung (UBL)"
            "peppol" in combined -> "Peppol BIS 3.0"
            "en16931" in combined -> "EN 16931 (UBL)"
            else -> "UBL"
        }
    }

    private fun parseEmbeddedDocument(): EmbeddedDocument? {
        val docRefs = getList("AdditionalDocumentReference")
        for (ref in docRefs) {
            val attachment = ref["Attachment"] as? Map<String, Any>
            val binaryObject = attachment?.get("EmbeddedDocumentBinaryObject") as? Map<String, Any>
            
            if (binaryObject != null) {
                val filename = getAttribute(binaryObject, "filename")
                val mimeCode = getAttribute(binaryObject, "mimeCode")
                val content = binaryObject["#text"] as? String
                
                if (!filename.isNullOrEmpty() && !content.isNullOrEmpty() && mimeCode == "application/pdf") {
                    return EmbeddedDocument(filename, content)
                }
            }
        }
        return null
    }
    
    private fun parseDocument(parser: XmlPullParser): Map<String, Any> {
        val result = mutableMapOf<String, Any>()
        var eventType = parser.eventType
        
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    val name = parser.name
                    // Handle Invoice, CreditNote, or Wrapper
                    if (name == "Invoice" || name == "CreditNote" || name == "StandardBusinessDocument") {
                        result.putAll(parseElement(parser))
                    }
                }
            }
            eventType = parser.next()
        }
        return result
    }
    
    private fun parseElement(parser: XmlPullParser): Map<String, Any> {
        val result = mutableMapOf<String, Any>()
        val elementName = parser.name
        val attributes = mutableMapOf<String, String>()
        
        // Collect attributes
        for (i in 0 until parser.attributeCount) {
            attributes[parser.getAttributeName(i)] = parser.getAttributeValue(i)
        }
        if (attributes.isNotEmpty()) {
            result["@attributes"] = attributes
        }
        
        var eventType = parser.next()
        val textContent = StringBuilder()
        val children = mutableListOf<Map<String, Any>>()
        
        while (eventType != XmlPullParser.END_TAG || parser.name != elementName) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    val childName = parser.name
                    val childElement = parseElement(parser)
                    
                    // Handle multiple elements with same name
                    val existing = result[childName]
                    when (existing) {
                        null -> result[childName] = childElement
                        is List<*> -> {
                            @Suppress("UNCHECKED_CAST")
                            (existing as MutableList<Map<String, Any>>).add(childElement)
                        }
                        is Map<*, *> -> {
                            result[childName] = mutableListOf(existing, childElement)
                        }
                    }
                }
                XmlPullParser.TEXT -> {
                    val text = parser.text?.trim()
                    if (!text.isNullOrEmpty()) {
                        textContent.append(text)
                    }
                }
            }
            eventType = parser.next()
        }
        
        if (textContent.isNotEmpty()) {
            result["#text"] = textContent.toString()
        }
        
        return result
    }
    
    private fun getText(map: Map<String, Any>?, path: String, default: String = ""): String {
        if (map == null) return default
        
        val parts = path.split("/")
        var current: Any? = map
        
        for (part in parts) {
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
    
    private fun getAttribute(map: Map<String, Any>?, attrName: String): String? {
        val attrs = map?.get("@attributes") as? Map<*, *>
        return attrs?.get(attrName) as? String
    }
    
    @Suppress("UNCHECKED_CAST")
    private fun getMap(path: String): Map<String, Any>? {
        val parts = path.split("/")
        var current: Any? = invoiceElement
        
        for (part in parts) {
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
    private fun getList(path: String): List<Map<String, Any>> {
        val parts = path.split("/")
        var current: Any? = invoiceElement
        
        for ((index, part) in parts.withIndex()) {
            if (index == parts.lastIndex) {
                val items = (current as? Map<*, *>)?.get(part)
                return when (items) {
                    is List<*> -> items as List<Map<String, Any>>
                    is Map<*, *> -> listOf(items as Map<String, Any>)
                    else -> emptyList()
                }
            }
            current = when (current) {
                is Map<*, *> -> current[part]
                is List<*> -> current.firstOrNull()
                else -> null
            }
            if (current == null) return emptyList()
        }
        return emptyList()
    }
    
    private fun parseInvoiceDetails(): InvoiceDetails {
        return InvoiceDetails(
            id = getText(invoiceElement, "ID"),
            issueDate = getText(invoiceElement, "IssueDate"),
            dueDate = getText(invoiceElement, "DueDate").takeIf { it.isNotEmpty() },
            currency = getText(invoiceElement, "DocumentCurrencyCode", "EUR"),
            orderId = getText(invoiceElement, "OrderReference/ID").takeIf { it.isNotEmpty() },
            salesOrderId = getText(invoiceElement, "OrderReference/SalesOrderID").takeIf { it.isNotEmpty() }
        )
    }
    
    private fun parseParty(partyType: String): Party {
        val partyMap = getMap("$partyType/Party") ?: return Party(name = "Unknown")
        
        val postalAddress = partyMap["PostalAddress"] as? Map<String, Any>
        
        return Party(
            name = getText(partyMap, "PartyName/Name").ifEmpty {
                getText(partyMap, "PartyLegalEntity/RegistrationName")
            },
            street = getText(postalAddress, "StreetName").takeIf { it.isNotEmpty() },
            city = getText(postalAddress, "CityName").takeIf { it.isNotEmpty() },
            zip = getText(postalAddress, "PostalZone").takeIf { it.isNotEmpty() },
            country = getText(postalAddress, "Country/IdentificationCode").takeIf { it.isNotEmpty() },
            taxId = getText(partyMap, "PartyTaxScheme/CompanyID").takeIf { it.isNotEmpty() },
            contactName = getText(partyMap, "Contact/Name").takeIf { it.isNotEmpty() },
            email = getText(partyMap, "Contact/ElectronicMail").takeIf { it.isNotEmpty() },
            phone = getText(partyMap, "Contact/Telephone").takeIf { it.isNotEmpty() }
        )
    }
    
    @Suppress("UNCHECKED_CAST")
    private fun parseInvoiceLines(): List<InvoiceLineItem> {
        val lines = mutableListOf<InvoiceLineItem>()
        // Attempt to find InvoiceLine, fallback to CreditNoteLine
        var lineNodes = getList("InvoiceLine")
        if (lineNodes.isEmpty()) {
            lineNodes = getList("CreditNoteLine")
        }
        
        for (node in lineNodes) {
            // Check for InvoicedQuantity or CreditedQuantity
            var quantityStr = getText(node, "InvoicedQuantity")
            if (quantityStr.isEmpty()) {
                quantityStr = getText(node, "CreditedQuantity")
            }
            val quantity = quantityStr.toDoubleOrNull() ?: 0.0
            
            val price = getText(node, "Price/PriceAmount").toDoubleOrNull() ?: 0.0
            
            // Get unit code from attribute (InvoicedQuantity OR CreditedQuantity)
            val quantityMap = (node["InvoicedQuantity"] ?: node["CreditedQuantity"]) as? Map<String, Any>
            val unit = getAttribute(quantityMap, "unitCode") ?: ""
            
            val lineTotal = quantity * price
            
            lines.add(InvoiceLineItem(
                id = getText(node, "Item/SellersItemIdentification/ID"),
                description = getText(node, "Item/Name"),
                quantity = quantity,
                unit = unit,
                price = price,
                lineTotal = lineTotal,
                isCharge = false
            ))
            
            // Parse line-level AllowanceCharge
            val allowanceCharges = node["AllowanceCharge"]
            val acList = when (allowanceCharges) {
                is List<*> -> allowanceCharges as List<Map<String, Any>>
                is Map<*, *> -> listOf(allowanceCharges as Map<String, Any>)
                else -> emptyList()
            }
            
            for (ac in acList) {
                val isCharge = getText(ac, "ChargeIndicator").lowercase() == "true"
                val amount = getText(ac, "Amount").toDoubleOrNull() ?: 0.0
                var reason = getText(ac, "AllowanceChargeReason")
                
                // Map "Vracht" to localized string
                if (reason.trim() == "Vracht") {
                    reason = context.getString(com.ziesche.peppolreader.R.string.shipping_cost)
                }
                
                val description = if (isCharge) 
                    context.getString(com.ziesche.peppolreader.R.string.surcharge_label, reason)
                else 
                    context.getString(com.ziesche.peppolreader.R.string.discount_label, reason)
                
                lines.add(InvoiceLineItem(
                    id = "",
                    description = description,
                    quantity = 1.0,
                    unit = "EA",
                    price = if (isCharge) amount else -amount,
                    lineTotal = if (isCharge) amount else -amount,
                    isCharge = true
                ))
            }
        }
        
        return lines
    }
    
    private fun parseAllowanceCharges(): List<AllowanceCharge> {
        val charges = mutableListOf<AllowanceCharge>()
        val acNodes = getList("AllowanceCharge")
        
        for (node in acNodes) {
            charges.add(AllowanceCharge(
                isCharge = getText(node, "ChargeIndicator").lowercase() == "true",
                reason = getText(node, "AllowanceChargeReason"),
                amount = getText(node, "Amount").toDoubleOrNull() ?: 0.0,
                taxPercent = getText(node, "TaxCategory/Percent").toDoubleOrNull() ?: 0.0
            ))
        }
        
        return charges
    }
    
    @Suppress("UNCHECKED_CAST")
    private fun parseTotals(): InvoiceTotals {
        val monetaryTotal = getMap("LegalMonetaryTotal") ?: return InvoiceTotals()
        val taxTotalNodes = getList("TaxTotal")
        
        var taxAmount = 0.0
        val taxSubtotals = mutableListOf<TaxSubtotal>()
        
        if (taxTotalNodes.isNotEmpty()) {
            val mainTaxTotal = taxTotalNodes.first()
            taxAmount = getText(mainTaxTotal, "TaxAmount").toDoubleOrNull() ?: 0.0
            
            val subtotalNodes = mainTaxTotal["TaxSubtotal"]
            val subtotals = when (subtotalNodes) {
                is List<*> -> subtotalNodes as List<Map<String, Any>>
                is Map<*, *> -> listOf(subtotalNodes as Map<String, Any>)
                else -> emptyList()
            }
            
            for (sub in subtotals) {
                taxSubtotals.add(TaxSubtotal(
                    taxableAmount = getText(sub, "TaxableAmount").toDoubleOrNull() ?: 0.0,
                    taxAmount = getText(sub, "TaxAmount").toDoubleOrNull() ?: 0.0,
                    percent = getText(sub, "TaxCategory/Percent").toDoubleOrNull() ?: 0.0
                ))
            }
        }
        
        return InvoiceTotals(
            lineExtension = getText(monetaryTotal, "LineExtensionAmount").toDoubleOrNull() ?: 0.0,
            allowanceTotal = getText(monetaryTotal, "AllowanceTotalAmount").toDoubleOrNull() ?: 0.0,
            chargeTotal = getText(monetaryTotal, "ChargeTotalAmount").toDoubleOrNull() ?: 0.0,
            netAmount = getText(monetaryTotal, "TaxExclusiveAmount").toDoubleOrNull() ?: 0.0,
            taxAmount = taxAmount,
            grossAmount = getText(monetaryTotal, "TaxInclusiveAmount").toDoubleOrNull() ?: 0.0,
            payableAmount = getText(monetaryTotal, "PayableAmount").toDoubleOrNull() ?: 0.0,
            taxSubtotals = taxSubtotals
        )
    }

    private fun parsePaymentMeans(): PaymentMeans? {
        val paymentMeansMap = getMap("PaymentMeans") ?: return null
        val code = getText(paymentMeansMap, "PaymentMeansCode")
        
        val payeeAccountMap = paymentMeansMap["PayeeFinancialAccount"] as? Map<String, Any>
        val payeeAccount = if (payeeAccountMap != null) {
            PayeeFinancialAccount(
                id = getText(payeeAccountMap, "ID"),
                name = getText(payeeAccountMap, "Name").takeIf { it.isNotEmpty() },
                financialInstitutionBranchId = getText(payeeAccountMap, "FinancialInstitutionBranch/ID").takeIf { it.isNotEmpty() }
            )
        } else null
        
        return PaymentMeans(code, payeeAccount)
    }

    private fun parsePaymentTerms(): String? {
        val terms = getList("PaymentTerms")
        return terms.firstOrNull()?.let { getText(it, "Note") }
    }
}

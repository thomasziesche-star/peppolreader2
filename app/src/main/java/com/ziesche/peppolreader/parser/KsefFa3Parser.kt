package com.ziesche.peppolreader.parser

import android.content.Context
import com.ziesche.peppolreader.data.model.CorrectionInfo
import com.ziesche.peppolreader.data.model.DocumentType
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
 * Parser for Polish KSeF **FA(3)** e-invoices (schema namespace
 * `http://crd.gov.pl/wzor/2025/06/25/13775/`).
 *
 * FA(3) is structurally unrelated to UBL or UN/CEFACT CII — element names are
 * Polish, monetary fields use the `P_xx` coding from the printed form, and the
 * invoice subtype is carried in `RodzajFaktury` (VAT / KOR / ZAL / UPR / ROZ /
 * KOR_ZAL / KOR_ROZ). This parser maps the relevant fields into the same
 * [ParsedInvoice] shape produced by [PeppolParser] and [CiiParser]; KSeF-only
 * details (Adnotacje, Rejestry, Podmiot3, Transport) are intentionally left in
 * the raw XML and not surfaced in v1.
 */
class KsefFa3Parser(private val xmlContent: String, @Suppress("UNUSED_PARAMETER") context: Context) {

    private var root: Map<String, Any> = emptyMap()

    fun parse(): ParsedInvoice {
        val pull = XmlPullParserFactory.newInstance().newPullParser()
        pull.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        pull.setInput(StringReader(xmlContent))
        root = parseDocument(pull)

        // Unwrap the <Faktura> root.
        @Suppress("UNCHECKED_CAST")
        if (root.containsKey("Faktura")) {
            root = root["Faktura"] as Map<String, Any>
        }

        val subtype = getText(root, "Fa/RodzajFaktury").ifEmpty { "VAT" }
        val correction = parseCorrection(subtype)

        return ParsedInvoice(
            invoice = parseDetails(),
            supplier = parseParty("Podmiot1"),
            customer = parseParty("Podmiot2"),
            items = parseLines(),
            allowanceCharges = emptyList(),
            totals = parseTotals(),
            paymentMeans = parsePaymentMeans(),
            paymentTermsNote = parsePaymentTermsNote(),
            embeddedDocument = null,
            formatLabel = "KSeF FA(3)",
            documentTypeCode = mapDocumentTypeCode(subtype),
            invoiceSubtype = subtype,
            correctionInfo = correction
        )
    }

    /**
     * Maps `RodzajFaktury` to UN/EDIFACT 1001:
     * - KOR / KOR_ZAL / KOR_ROZ → "381" (credit note — triggers red chip + negative amount)
     * - everything else → "380"
     */
    private fun mapDocumentTypeCode(subtype: String): String = when (subtype) {
        "KOR", "KOR_ZAL", "KOR_ROZ" -> DocumentType.CREDIT_NOTE
        else -> DocumentType.INVOICE
    }

    private fun parseCorrection(subtype: String): CorrectionInfo? {
        if (subtype != "KOR" && subtype != "KOR_ZAL" && subtype != "KOR_ROZ") return null
        val reason = getText(root, "Fa/PrzyczynaKorekty").takeIf { it.isNotEmpty() }
        val daneMap = getMap("Fa/DaneFaKorygowanej")
        val originalNumber = daneMap?.let { getText(it, "NrFaKorygowanej") }?.takeIf { it.isNotEmpty() }
        val originalDate = daneMap?.let { getText(it, "DataWystFaKorygowanej") }?.takeIf { it.isNotEmpty() }
        val originalKsefId = daneMap?.let { getText(it, "NrKSeFFaKorygowanej") }?.takeIf { it.isNotEmpty() }
        if (reason == null && originalNumber == null && originalDate == null && originalKsefId == null) {
            return null
        }
        return CorrectionInfo(reason, originalNumber, originalDate, originalKsefId)
    }

    private fun parseDetails(): InvoiceDetails {
        val faMap = getMap("Fa")
        val currency = getText(faMap, "KodWaluty", "PLN")
        // First payment due-date if present (ROZ-type invoices can list several).
        val terminList = listOfMaps(faMap?.get("Platnosc")?.let {
            (it as? Map<*, *>)?.get("TerminPlatnosci")
        })
        val dueDate = terminList.firstOrNull()?.let { getText(it, "Termin") }
            ?.takeIf { it.isNotEmpty() }

        return InvoiceDetails(
            id = getText(faMap, "P_2"),
            issueDate = getText(faMap, "P_1"),
            dueDate = dueDate,
            currency = currency,
            orderId = null,
            salesOrderId = null
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseParty(path: String): Party {
        val partyMap = getMap(path) ?: return Party(name = "Unknown")
        val ident = partyMap["DaneIdentyfikacyjne"] as? Map<String, Any>
        val address = partyMap["Adres"] as? Map<String, Any>
        val contact = partyMap["DaneKontaktowe"] as? Map<String, Any>

        // FA(3) carries the Polish NIP, or for EU counter-parties the VAT-UE number.
        val taxId = ident?.let { getText(it, "NIP").ifEmpty { getText(it, "NrVatUE") } }
            ?.takeIf { it.isNotEmpty() }

        // AdresL1 + AdresL2 typically combine street and "zip city" — we keep the
        // structure simple: AdresL1 → street, AdresL2 → city (which often contains
        // the ZIP). FormatBadge/UI display them concatenated anyway.
        val street = address?.let { getText(it, "AdresL1") }?.takeIf { it.isNotEmpty() }
        val cityLine = address?.let { getText(it, "AdresL2") }?.takeIf { it.isNotEmpty() }

        // FA(3) "Uproszczona" (simplified) invoices may carry only a NIP for the buyer.
        // Fall back to "NIP {value}" so the row isn't empty in the list/detail view.
        val name = ident?.let { getText(it, "Nazwa") }?.takeIf { it.isNotEmpty() }
            ?: ident?.let { (getText(it, "ImiePierwsze") + " " + getText(it, "Nazwisko")).trim() }
                ?.takeIf { it.isNotEmpty() }
            ?: taxId?.let { "NIP $it" }
            ?: "Unknown"

        return Party(
            name = name,
            street = street,
            city = cityLine,
            zip = null,
            country = address?.let { getText(it, "KodKraju") }?.takeIf { it.isNotEmpty() },
            taxId = taxId,
            contactName = null,
            email = contact?.let { getText(it, "Email") }?.takeIf { it.isNotEmpty() },
            phone = contact?.let { getText(it, "Telefon") }?.takeIf { it.isNotEmpty() }
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseLines(): List<InvoiceLineItem> {
        val faMap = getMap("Fa") ?: return emptyList()
        val lineNodes = listOfMaps(faMap["FaWiersz"])
        return lineNodes.map { node ->
            val quantity = getText(node, "P_8B").toDoubleOrNull() ?: 0.0
            val unit = getText(node, "P_8A")
            val price = getText(node, "P_9A").toDoubleOrNull() ?: 0.0
            val lineTotal = getText(node, "P_11").toDoubleOrNull()
                ?: (quantity * price)
            InvoiceLineItem(
                id = getText(node, "NrWierszaFa"),
                description = getText(node, "P_7"),
                quantity = quantity,
                unit = unit,
                price = price,
                lineTotal = lineTotal,
                isCharge = false
            )
        }
    }

    private fun parseTotals(): InvoiceTotals {
        val faMap = getMap("Fa") ?: return InvoiceTotals()

        // FA(3) splits net sums per VAT rate (P_13_1 = std, P_13_2 = reduced,
        // P_13_3..5 = other reduced/0%, P_13_6_2 = WDT/EXP). Same for tax in P_14_*.
        val netParts = listOf("P_13_1", "P_13_2", "P_13_3", "P_13_4", "P_13_5", "P_13_6_2", "P_13_7")
            .mapNotNull { getText(faMap, it).toDoubleOrNull() }
        val taxParts = listOf("P_14_1", "P_14_2", "P_14_3", "P_14_4", "P_14_5", "P_14_6", "P_14_7")
            .mapNotNull { getText(faMap, it).toDoubleOrNull() }

        val net = netParts.sum()
        val tax = taxParts.sum()
        val gross = getText(faMap, "P_15").toDoubleOrNull() ?: (net + tax)

        return InvoiceTotals(
            lineExtension = net,
            allowanceTotal = 0.0,
            chargeTotal = 0.0,
            netAmount = net,
            taxAmount = tax,
            grossAmount = gross,
            payableAmount = gross,
            taxSubtotals = if (tax != 0.0) listOf(TaxSubtotal(net, tax, 0.0)) else emptyList()
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun parsePaymentMeans(): PaymentMeans? {
        val platnoscMap = getMap("Fa/Platnosc") ?: return null
        val formaCode = mapFormaPlatnosci(getText(platnoscMap, "FormaPlatnosci"))
        val rachunek = platnoscMap["RachunekBankowy"] as? Map<String, Any>

        val account = rachunek?.let {
            val iban = getText(it, "NrRB")
            val bankName = getText(it, "NazwaBanku").takeIf { s -> s.isNotEmpty() }
            val swift = getText(it, "SWIFT").takeIf { s -> s.isNotEmpty() }
            PayeeFinancialAccount(
                id = iban,
                name = bankName,
                financialInstitutionBranchId = swift
            )
        }
        return PaymentMeans(formaCode, account)
    }

    /** Maps the Polish payment-form code to the UN/EDIFACT 4461 code used elsewhere. */
    private fun mapFormaPlatnosci(code: String): String = when (code) {
        "1" -> "10"  // Gotówka / cash
        "2" -> "48"  // Karta / card
        "3" -> "1"   // Bon / unspecified
        "4" -> "20"  // Czek / cheque
        "5" -> "1"   // Kredyt / unspecified
        "6" -> "30"  // Przelew / credit transfer (most common — triggers IBAN block)
        "7" -> "68"  // Mobilna / online
        else -> "1"
    }

    /**
     * Combines bank name, currency description and SWIFT into a free-text note so it
     * shows up under "payment terms" in the rendered invoice. Returns null when there
     * is nothing useful to display.
     */
    @Suppress("UNCHECKED_CAST")
    private fun parsePaymentTermsNote(): String? {
        val rachunek = getMap("Fa/Platnosc")?.get("RachunekBankowy") as? Map<String, Any>
            ?: return null
        val parts = mutableListOf<String>()
        getText(rachunek, "NazwaBanku").takeIf { it.isNotEmpty() }?.let { parts.add(it) }
        getText(rachunek, "OpisRachunku").takeIf { it.isNotEmpty() }?.let { parts.add(it) }
        return parts.joinToString(" • ").ifEmpty { null }
    }

    // --- generic XML→Map helpers (same shape as PeppolParser/CiiParser) -----

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

package com.ziesche.peppolreader.parser

enum class InvoiceFormat {
    /** OASIS UBL 2.x (Peppol BIS / XRechnung-UBL). Parsed by [PeppolParser]. */
    UBL,
    /** UN/CEFACT Cross Industry Invoice (ZUGFeRD / Factur-X / XRechnung-CII). Parsed by [CiiParser]. */
    CII,
    /** Unknown / unsupported XML. */
    UNKNOWN;

    companion object {
        fun detect(xml: String): InvoiceFormat {
            val hasUbl = xml.contains("urn:oasis:names:specification:ubl:schema:xsd:Invoice-2") ||
                xml.contains("urn:oasis:names:specification:ubl:schema:xsd:CreditNote-2")
            if (hasUbl) return UBL

            val hasCii = xml.contains("CrossIndustryInvoice") ||
                xml.contains("urn:un:unece:uncefact:data:standard:CrossIndustryInvoice")
            if (hasCii) return CII

            return UNKNOWN
        }
    }
}

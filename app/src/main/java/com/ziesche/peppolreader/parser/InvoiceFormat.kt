package com.ziesche.peppolreader.parser

enum class InvoiceFormat {
    /** OASIS UBL 2.x (Peppol BIS / XRechnung-UBL). Parsed by [PeppolParser]. */
    UBL,
    /** UN/CEFACT Cross Industry Invoice (ZUGFeRD / Factur-X / XRechnung-CII). Parsed by [CiiParser]. */
    CII,
    /** Polish KSeF FA(3) schema (`crd.gov.pl/wzor`). Parsed by [KsefFa3Parser]. */
    KSEF_FA3,
    /** Unknown / unsupported XML. */
    UNKNOWN;

    companion object {
        fun detect(xml: String): InvoiceFormat {
            // KSeF FA(3) — checked first because its namespace and `KodFormularza`
            // marker are unique to the Polish schema and cannot collide with UBL/CII.
            val hasKsef = xml.contains("crd.gov.pl/wzor") ||
                xml.contains("kodSystemowy=\"FA (3)\"") ||
                xml.contains("kodSystemowy='FA (3)'")
            if (hasKsef) return KSEF_FA3

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

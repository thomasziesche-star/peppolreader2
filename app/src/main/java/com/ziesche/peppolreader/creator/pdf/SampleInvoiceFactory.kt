package com.ziesche.peppolreader.creator.pdf

import com.ziesche.peppolreader.creator.model.CompanyProfile
import com.ziesche.peppolreader.creator.model.CreatorAllowanceCharge
import com.ziesche.peppolreader.creator.model.CreatorLine
import com.ziesche.peppolreader.creator.model.OutgoingInvoice

/**
 * Builds the dummy invoice shown in the layout-editor preview (and reused by tests):
 * three positions with a 19 %/7 % VAT mix, a document-level discount and a payment term,
 * so every themed element (table, totals rows, allowance line, payment block) is visible.
 * Seller data comes from the real [CompanyProfile] when available, so the preview shows
 * the user's own letterhead.
 */
object SampleInvoiceFactory {

    fun build(profile: CompanyProfile): OutgoingInvoice = OutgoingInvoice(
        invoiceNumber = profile.numberPrefix.ifBlank { "RE-" } + "2026-042",
        issueDate = "2026-07-01",
        dueDate = "2026-07-15",
        documentTypeCode = "380",
        currency = "EUR",
        sellerName = profile.name.ifBlank { "Muster GmbH" },
        sellerStreet = profile.street.ifBlank { "Musterstraße 1" },
        sellerZip = profile.zip.ifBlank { "10115" },
        sellerCity = profile.city.ifBlank { "Berlin" },
        sellerCountry = profile.country.ifBlank { "DE" },
        sellerVatId = profile.vatId.ifBlank { "DE123456789" },
        sellerIban = profile.iban.ifBlank { "DE89370400440532013000" },
        sellerBic = profile.bic.ifBlank { null },
        sellerEmail = profile.email.ifBlank { null },
        sellerPhone = profile.phone.ifBlank { null },
        buyerName = "Beispiel & Söhne KG",
        buyerStreet = "Beispielweg 12",
        buyerZip = "20095",
        buyerCity = "Hamburg",
        buyerCountry = "DE",
        lineItemsJson = CreatorLine.listToJson(
            listOf(
                CreatorLine("Beratung und Konzeption", quantity = 8.0, unit = "HUR", unitPrice = 95.0, vatRate = 19.0),
                CreatorLine("Umsetzung inkl. Dokumentation der Ergebnisse", quantity = 12.0, unit = "HUR", unitPrice = 85.0, vatRate = 19.0),
                CreatorLine("Fachbuch (ermäßigter Satz)", quantity = 1.0, unit = "C62", unitPrice = 39.9, vatRate = 7.0)
            )
        ),
        allowancesJson = CreatorAllowanceCharge.listToJson(
            listOf(CreatorAllowanceCharge(isCharge = false, reason = "Treuerabatt", amount = 50.0, vatRate = 19.0))
        ),
        paymentTermsNote = "Zahlbar innerhalb von 14 Tagen ohne Abzug."
    )
}

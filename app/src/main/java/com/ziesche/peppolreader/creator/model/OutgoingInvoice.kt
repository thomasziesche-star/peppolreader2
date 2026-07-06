package com.ziesche.peppolreader.creator.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A self-issued invoice draft created in the Invoice Creator mode. Stored in its own
 * `outgoing_invoices` table, completely separate from the reader's `invoices` table.
 *
 * The seller fields are a snapshot of the [CompanyProfile] at creation time, so editing the
 * profile later never silently rewrites past drafts. Line items are persisted as a JSON
 * array (see [CreatorLine.listToJson]).
 */
@Entity(tableName = "outgoing_invoices")
data class OutgoingInvoice(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    // Header
    val invoiceNumber: String,
    val issueDate: String,                 // ISO yyyy-MM-dd
    val dueDate: String? = null,           // ISO yyyy-MM-dd
    val documentTypeCode: String = "380",  // 380 = invoice, 381 = credit note
    val currency: String = "EUR",

    // Seller snapshot
    val sellerName: String,
    val sellerStreet: String? = null,
    val sellerZip: String? = null,
    val sellerCity: String? = null,
    val sellerCountry: String? = null,
    val sellerVatId: String? = null,
    val sellerTaxNumber: String? = null,
    val sellerIban: String? = null,
    val sellerBic: String? = null,
    val sellerEmail: String? = null,
    val sellerPhone: String? = null,

    // Buyer
    val buyerName: String,
    val buyerStreet: String? = null,
    val buyerZip: String? = null,
    val buyerCity: String? = null,
    val buyerCountry: String? = null,
    val buyerVatId: String? = null,
    val buyerReference: String? = null,    // BT-10 Leitweg-ID (buyer reference), e.g. B2G routing id

    // Lines + payment
    val lineItemsJson: String = "[]",
    val paymentTermsNote: String? = null,

    // Tax handling (document-wide): STANDARD | EXEMPT (§19 small business) | REVERSE_CHARGE.
    // exemptionReason is a snapshot like the seller fields — profile edits never rewrite drafts.
    val taxMode: String = TAX_MODE_STANDARD,
    val exemptionReason: String? = null,

    // Document-level allowances/charges (BG-20/21), JSON like lineItemsJson.
    val allowancesJson: String = "[]",

    // Lifecycle
    val status: String = STATUS_DRAFT,     // DRAFT | GENERATED
    val generatedXml: String? = null,
    val pdfPath: String? = null,

    // Payment / dunning lifecycle (only meaningful once status == GENERATED)
    val paidAt: Long? = null,               // ms epoch, null = unpaid
    val dunningLevel: Int = 0,              // 0 = none, 1 = payment reminder, 2/3 = first/final dunning sent
    val lastDunningAt: Long? = null,        // ms epoch of the last dunning email launch
    val lastOverdueNotifiedAt: Long? = null, // anti-spam stamp for the overdue notification

    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    val lines: List<CreatorLine> get() = CreatorLine.listFromJson(lineItemsJson)

    val allowances: List<CreatorAllowanceCharge>
        get() = CreatorAllowanceCharge.listFromJson(allowancesJson)

    /**
     * A quote/offer (Angebot) rather than a real invoice: stored with [DOC_TYPE_QUOTE] and
     * produced as a plain PDF (no EN 16931 XML), later convertible into an invoice.
     */
    val isQuote: Boolean get() = documentTypeCode == DOC_TYPE_QUOTE

    /** Overdue = generated, unpaid, due date strictly before [todayIso] (lexical ISO compare). */
    fun isOverdue(todayIso: String): Boolean =
        status == STATUS_GENERATED && paidAt == null &&
            !dueDate.isNullOrBlank() && dueDate < todayIso

    companion object {
        const val STATUS_DRAFT = "DRAFT"
        const val STATUS_GENERATED = "GENERATED"

        /** documentTypeCode values. Invoice/credit note are the EN 16931 (UNTDID 1001) codes;
         *  quote is an app-internal marker that never reaches the CII XML (quotes are PDF-only). */
        const val DOC_TYPE_INVOICE = "380"
        const val DOC_TYPE_CREDIT_NOTE = "381"
        const val DOC_TYPE_QUOTE = "QUOTE"

        /** Regular VAT (categories S/Z derived from the per-line rate). */
        const val TAX_MODE_STANDARD = "STANDARD"
        /** Tax-exempt seller, e.g. German §19 UStG small business (category E). */
        const val TAX_MODE_EXEMPT = "EXEMPT"
        /** Reverse charge — buyer owes the VAT (category AE). */
        const val TAX_MODE_REVERSE_CHARGE = "REVERSE_CHARGE"
    }
}

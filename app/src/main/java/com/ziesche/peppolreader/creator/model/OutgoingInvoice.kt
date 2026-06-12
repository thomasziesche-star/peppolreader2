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

    // Lines + payment
    val lineItemsJson: String = "[]",
    val paymentTermsNote: String? = null,

    // Lifecycle
    val status: String = STATUS_DRAFT,     // DRAFT | GENERATED
    val generatedXml: String? = null,
    val pdfPath: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    val lines: List<CreatorLine> get() = CreatorLine.listFromJson(lineItemsJson)

    companion object {
        const val STATUS_DRAFT = "DRAFT"
        const val STATUS_GENERATED = "GENERATED"
    }
}

package com.ziesche.peppolreader.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room Entity for storing parsed invoice summaries
 */
@Entity(tableName = "invoices")
data class Invoice(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    
    // Invoice identifiers
    val invoiceId: String,
    val issueDate: String,
    val dueDate: String? = null,
    val currency: String = "EUR",
    
    // Parties
    val supplierName: String,
    val supplierStreet: String? = null,
    val supplierCity: String? = null,
    val supplierZip: String? = null,
    val supplierCountry: String? = null,
    val supplierTaxId: String? = null,
    
    val customerName: String,
    val customerStreet: String? = null,
    val customerCity: String? = null,
    val customerZip: String? = null,
    val customerCountry: String? = null,
    val customerTaxId: String? = null,
    
    // Totals
    val netAmount: Double = 0.0,
    val taxAmount: Double = 0.0,
    val grossAmount: Double = 0.0,
    val payableAmount: Double = 0.0,
    
    // Original XML content for re-parsing
    val xmlContent: String,
    
    // Metadata
    val fileName: String,
    val embeddedDocumentFilename: String? = null,
    val embeddedDocumentPath: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    /** UN/EDIFACT 1001 type code: "380"=invoice, "381"=credit note, "384"=corrected, … */
    val documentTypeCode: String? = null,
    /** Human-readable source-format profile, e.g. "Peppol BIS 3.0", "XRechnung (UBL)", "ZUGFeRD". */
    val formatLabel: String? = null,
    /** Epoch millis when the user marked the invoice as paid. Null = still open. */
    val paidAt: Long? = null,
    /** Epoch millis of the most recent due-date reminder shown – prevents notification spam. */
    val lastReminderShownAt: Long? = null,
    /** KSeF FA(3) only: invoice subtype (VAT/KOR/ZAL/UPR/ROZ/KOR_ZAL/KOR_ROZ). */
    val invoiceSubtype: String? = null,
    /** KSeF FA(3) only: JSON-serialised [com.ziesche.peppolreader.data.model.CorrectionInfo]. */
    val correctionInfoJson: String? = null,
    /** Free-text note the user adds for bookkeeping context (not from the XML). */
    val note: String? = null,
    /** Optional bookkeeping category/account, e.g. "Software", "Reise" — surfaced in the CSV export. */
    val category: String? = null
)

package com.example.peppolreaderfree.data.model

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
    val createdAt: Long = System.currentTimeMillis()
)

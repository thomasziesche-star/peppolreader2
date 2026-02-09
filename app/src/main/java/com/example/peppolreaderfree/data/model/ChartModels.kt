package com.example.peppolreaderfree.data.model

/**
 * Data class for monthly expenses query result
 */
data class MonthlyExpense(
    val month: String, // Format: YYYY-MM
    val total: Double
)

/**
 * Data class for supplier expenses query result
 */
data class SupplierExpense(
    val supplierName: String,
    val total: Double,
    val invoiceCount: Int
)

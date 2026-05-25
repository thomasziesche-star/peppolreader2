package com.ziesche.peppolreader.data.model

/**
 * Data class for invoice line items
 */
/**
 * Data class for embedded documents
 */
data class EmbeddedDocument(
    val filename: String,
    val base64Data: String
)

/**
 * Data class for invoice line items
 */
data class InvoiceLineItem(
    val id: String,
    val description: String,
    val quantity: Double,
    val unit: String,
    val price: Double,
    val lineTotal: Double,
    val isCharge: Boolean = false
)

/**
 * Data class for party information (supplier/customer)
 */
data class Party(
    val name: String,
    val street: String? = null,
    val city: String? = null,
    val zip: String? = null,
    val country: String? = null,
    val taxId: String? = null,
    val contactName: String? = null,
    val email: String? = null,
    val phone: String? = null
)

/**
 * Data class for tax subtotals
 */
data class TaxSubtotal(
    val taxableAmount: Double,
    val taxAmount: Double,
    val percent: Double
)

/**
 * Data class for monetary totals
 */
data class InvoiceTotals(
    val lineExtension: Double = 0.0,
    val allowanceTotal: Double = 0.0,
    val chargeTotal: Double = 0.0,
    val netAmount: Double = 0.0,
    val taxAmount: Double = 0.0,
    val grossAmount: Double = 0.0,
    val payableAmount: Double = 0.0,
    val taxSubtotals: List<TaxSubtotal> = emptyList()
)

/**
 * Data class for invoice details (ID, dates, etc.)
 */
data class InvoiceDetails(
    val id: String,
    val issueDate: String,
    val dueDate: String? = null,
    val currency: String = "EUR",
    val orderId: String? = null,
    val salesOrderId: String? = null
)

/**
 * Complete parsed invoice data structure
 */
data class AllowanceCharge(
    val isCharge: Boolean,
    val reason: String,
    val amount: Double,
    val taxPercent: Double
)

/**
 * Data class for payment means (how the invoice is paid)
 */
data class PaymentMeans(
    val code: String,
    val payeeFinancialAccount: PayeeFinancialAccount?
)

/**
 * Data class for payee financial account (IBAN, BIC, etc.)
 */
data class PayeeFinancialAccount(
    val id: String, // e.g. IBAN
    val name: String?, // Account Name
    val financialInstitutionBranchId: String? // e.g. BIC
)

/**
 * KSeF FA(3) only: reference to the invoice this one corrects, plus the textual reason.
 * Populated by `KsefFa3Parser` for `RodzajFaktury` ∈ {KOR, KOR_ZAL, KOR_ROZ}.
 */
data class CorrectionInfo(
    val reason: String?,
    val originalInvoiceNumber: String?,
    val originalIssueDate: String?,
    val originalKsefId: String?
) {
    companion object {
        /** Inverse of `InvoiceViewModel.serializeCorrection`. Returns null on any parse error. */
        fun parse(json: String?): CorrectionInfo? {
            if (json.isNullOrBlank()) return null
            return try {
                val obj = org.json.JSONObject(json)
                CorrectionInfo(
                    reason = obj.optString("reason").takeIf { it.isNotEmpty() },
                    originalInvoiceNumber = obj.optString("originalInvoiceNumber").takeIf { it.isNotEmpty() },
                    originalIssueDate = obj.optString("originalIssueDate").takeIf { it.isNotEmpty() },
                    originalKsefId = obj.optString("originalKsefId").takeIf { it.isNotEmpty() }
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}

/**
 * Complete parsed invoice data structure
 */
data class ParsedInvoice(
    val invoice: InvoiceDetails,
    val supplier: Party,
    val customer: Party,
    val items: List<InvoiceLineItem>,

    val allowanceCharges: List<AllowanceCharge>,
    val totals: InvoiceTotals,
    val paymentMeans: PaymentMeans? = null,
    val paymentTermsNote: String? = null,
    val embeddedDocument: EmbeddedDocument? = null,
    /**
     * Human-readable name of the source schema/profile, e.g. "Peppol BIS 3.0",
     * "XRechnung (UBL)", "ZUGFeRD / Factur-X". Set by the responsible parser.
     */
    val formatLabel: String? = null,
    /**
     * UN/EDIFACT document type code: "380" = commercial invoice, "381" = credit note,
     * "384" = corrected invoice, "386" = prepayment invoice, "389" = self-billed invoice.
     * See [com.ziesche.peppolreader.data.model.DocumentType].
     */
    val documentTypeCode: String? = null,
    /**
     * KSeF FA(3) only: invoice subtype from `RodzajFaktury` (VAT/KOR/ZAL/UPR/ROZ/KOR_ZAL/KOR_ROZ).
     * Null for UBL/CII invoices.
     */
    val invoiceSubtype: String? = null,
    /** KSeF FA(3) only: present when this is a correction of a prior invoice. */
    val correctionInfo: CorrectionInfo? = null
)

/** Helpers around the UN/EDIFACT 1001 document type code. */
object DocumentType {
    const val INVOICE = "380"
    const val CREDIT_NOTE = "381"
    const val DEBIT_NOTE = "383"
    const val CORRECTED_INVOICE = "384"
    const val PREPAYMENT_INVOICE = "386"
    const val SELF_BILLED_INVOICE = "389"

    fun isCreditNote(code: String?): Boolean = code == CREDIT_NOTE
}

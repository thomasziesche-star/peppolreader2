package com.ziesche.peppolreader.data.model

/**
 * Signed monetary views on an [Invoice]: credit notes (UN/EDIFACT 1001 = "381") store positive
 * amounts but reduce a balance, so they count negatively in any aggregation. Centralising the sign
 * here keeps the list, detail view and dashboard consistent — e.g. invoice 1000 + credit note 400
 * for one supplier nets to 600.
 */
val Invoice.signedPayable: Double
    get() = if (DocumentType.isCreditNote(documentTypeCode)) -payableAmount else payableAmount

val Invoice.signedNet: Double
    get() = if (DocumentType.isCreditNote(documentTypeCode)) -netAmount else netAmount

val Invoice.signedTax: Double
    get() = if (DocumentType.isCreditNote(documentTypeCode)) -taxAmount else taxAmount

val Invoice.signedGross: Double
    get() = if (DocumentType.isCreditNote(documentTypeCode)) -grossAmount else grossAmount

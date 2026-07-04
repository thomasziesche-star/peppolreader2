package com.ziesche.peppolreader.creator.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * A single invoice line entered by the user. Unlike the reader-side
 * [com.ziesche.peppolreader.data.model.InvoiceLineItem] this carries the per-line VAT rate,
 * which EN 16931 requires on every line (BG-30 / BT-152).
 *
 * [unit] is a UN/ECE Rec 20 code; "C62" (= one / piece) is the sensible default.
 */
data class CreatorLine(
    val description: String = "",
    val quantity: Double = 1.0,
    val unit: String = "C62",
    val unitPrice: Double = 0.0,
    val vatRate: Double = 19.0
) {
    /** Net line amount (BT-131). */
    val lineNet: Double get() = quantity * unitPrice

    fun toJson(): JSONObject = JSONObject().apply {
        put(KEY_DESCRIPTION, description)
        put(KEY_QUANTITY, quantity)
        put(KEY_UNIT, unit)
        put(KEY_UNIT_PRICE, unitPrice)
        put(KEY_VAT_RATE, vatRate)
    }

    companion object {
        private const val KEY_DESCRIPTION = "description"
        private const val KEY_QUANTITY = "quantity"
        private const val KEY_UNIT = "unit"
        private const val KEY_UNIT_PRICE = "unitPrice"
        private const val KEY_VAT_RATE = "vatRate"

        fun fromJson(o: JSONObject): CreatorLine = CreatorLine(
            description = o.optString(KEY_DESCRIPTION),
            quantity = o.optDouble(KEY_QUANTITY, 1.0),
            unit = o.optString(KEY_UNIT, "C62"),
            unitPrice = o.optDouble(KEY_UNIT_PRICE, 0.0),
            vatRate = o.optDouble(KEY_VAT_RATE, 0.0)
        )

        /** Serializes a list of lines to the JSON string stored in [OutgoingInvoice.lineItemsJson]. */
        fun listToJson(lines: List<CreatorLine>): String {
            val arr = JSONArray()
            lines.forEach { arr.put(it.toJson()) }
            return arr.toString()
        }

        /** Inverse of [listToJson]; returns an empty list on any parse error. */
        fun listFromJson(json: String?): List<CreatorLine> {
            if (json.isNullOrBlank()) return emptyList()
            return runCatching {
                val arr = JSONArray(json)
                (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
            }.getOrDefault(emptyList())
        }
    }
}

/**
 * A document-level allowance (discount) or charge (surcharge), EN 16931 BG-20/BG-21.
 * [amount] is always the positive magnitude; [isCharge] decides the direction.
 * Serialized to [OutgoingInvoice.allowancesJson] the same way [CreatorLine] is.
 */
data class CreatorAllowanceCharge(
    val isCharge: Boolean = false,
    val reason: String = "",
    val amount: Double = 0.0,
    val vatRate: Double = 19.0
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put(KEY_IS_CHARGE, isCharge)
        put(KEY_REASON, reason)
        put(KEY_AMOUNT, amount)
        put(KEY_VAT_RATE, vatRate)
    }

    companion object {
        private const val KEY_IS_CHARGE = "isCharge"
        private const val KEY_REASON = "reason"
        private const val KEY_AMOUNT = "amount"
        private const val KEY_VAT_RATE = "vatRate"

        fun fromJson(o: JSONObject): CreatorAllowanceCharge = CreatorAllowanceCharge(
            isCharge = o.optBoolean(KEY_IS_CHARGE, false),
            reason = o.optString(KEY_REASON),
            amount = o.optDouble(KEY_AMOUNT, 0.0),
            vatRate = o.optDouble(KEY_VAT_RATE, 0.0)
        )

        fun listToJson(entries: List<CreatorAllowanceCharge>): String {
            val arr = JSONArray()
            entries.forEach { arr.put(it.toJson()) }
            return arr.toString()
        }

        /** Inverse of [listToJson]; returns an empty list on any parse error. */
        fun listFromJson(json: String?): List<CreatorAllowanceCharge> {
            if (json.isNullOrBlank()) return emptyList()
            return runCatching {
                val arr = JSONArray(json)
                (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
            }.getOrDefault(emptyList())
        }
    }
}

/** One VAT-rate group of the breakdown EN 16931 requires (BG-23). */
data class VatBreakdownEntry(
    val rate: Double,
    val basis: Double,
    val tax: Double
)

/** Computed monetary summation for an invoice draft (BG-22 / BT-106…117). */
data class CreatorTotals(
    val lineTotal: Double,
    val taxBasisTotal: Double,
    val taxTotal: Double,
    val grandTotal: Double,
    val payable: Double,
    val vatBreakdown: List<VatBreakdownEntry>,
    /** Sum of document-level allowances (BT-107) and charges (BT-108); 0.0 when unused. */
    val allowanceTotal: Double = 0.0,
    val chargeTotal: Double = 0.0
)

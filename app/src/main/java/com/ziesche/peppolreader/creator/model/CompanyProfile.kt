package com.ziesche.peppolreader.creator.model

import org.json.JSONObject

/**
 * Sender (seller) master data for the Invoice Creator. Persisted as a single JSON object
 * via [com.ziesche.peppolreader.creator.data.CompanyProfileStore] and used to pre-fill the
 * seller on every new draft.
 *
 * Besides the postal/tax data this also carries the creator-mode preferences: the export
 * location for generated PDFs ([storageMode]/[storageTreeUri]) and the invoice-number
 * sequence ([autoNumbering]/[numberPrefix]/[nextNumber]).
 *
 * For EN 16931 the seller must carry at least a VAT ID *or* a tax number, plus a postal
 * address — see [isComplete].
 */
data class CompanyProfile(
    val name: String = "",
    val street: String = "",
    val zip: String = "",
    val city: String = "",
    val country: String = "DE",
    val vatId: String = "",
    val taxNumber: String = "",
    val iban: String = "",
    val bic: String = "",
    val email: String = "",
    val phone: String = "",
    /** Absolute path of the imported logo image inside filesDir, or empty when none is set. */
    val logoPath: String = "",
    /** Where generated PDFs are exported to: [STORAGE_DOWNLOADS] (default) or [STORAGE_CUSTOM]. */
    val storageMode: String = STORAGE_DOWNLOADS,
    /** Persisted SAF tree URI when [storageMode] == [STORAGE_CUSTOM]. */
    val storageTreeUri: String = "",
    /** Pre-fill new drafts with the next number from the sequence (field stays editable). */
    val autoNumbering: Boolean = true,
    /** Free prefix of the sequence, e.g. "RE-2026-". */
    val numberPrefix: String = "RE-",
    /** Next running number; incremented when a suggested number is actually used. */
    val nextNumber: Int = 1,
    /** Default payment term in days: new drafts suggest due date = issue date + this. 0 = off. */
    val defaultPaymentDays: Int = 14,
    /** German §19 UStG small business: all invoices are VAT-exempt (category E, 0 %). */
    val smallBusiness: Boolean = false,
    /** Exemption note printed on the invoice and carried as BT-120; blank = localized default. */
    val exemptionText: String = ""
) {
    /** Minimal set of fields a valid EN 16931 seller needs to be expressible. */
    fun isComplete(): Boolean =
        name.isNotBlank() &&
            street.isNotBlank() &&
            zip.isNotBlank() &&
            city.isNotBlank() &&
            country.isNotBlank() &&
            (vatId.isNotBlank() || taxNumber.isNotBlank())

    /** The invoice number the sequence would assign next, e.g. "RE-2026-007". */
    fun suggestedNumber(): String = numberPrefix + nextNumber.toString().padStart(3, '0')

    fun toJson(): JSONObject = JSONObject().apply {
        put(KEY_NAME, name)
        put(KEY_STREET, street)
        put(KEY_ZIP, zip)
        put(KEY_CITY, city)
        put(KEY_COUNTRY, country)
        put(KEY_VAT_ID, vatId)
        put(KEY_TAX_NUMBER, taxNumber)
        put(KEY_IBAN, iban)
        put(KEY_BIC, bic)
        put(KEY_EMAIL, email)
        put(KEY_PHONE, phone)
        put(KEY_LOGO_PATH, logoPath)
        put(KEY_STORAGE_MODE, storageMode)
        put(KEY_STORAGE_TREE_URI, storageTreeUri)
        put(KEY_AUTO_NUMBERING, autoNumbering)
        put(KEY_NUMBER_PREFIX, numberPrefix)
        put(KEY_NEXT_NUMBER, nextNumber)
        put(KEY_PAYMENT_DAYS, defaultPaymentDays)
        put(KEY_SMALL_BUSINESS, smallBusiness)
        put(KEY_EXEMPTION_TEXT, exemptionText)
    }

    companion object {
        const val STORAGE_DOWNLOADS = "DOWNLOADS"
        const val STORAGE_CUSTOM = "CUSTOM"

        private const val KEY_NAME = "name"
        private const val KEY_STREET = "street"
        private const val KEY_ZIP = "zip"
        private const val KEY_CITY = "city"
        private const val KEY_COUNTRY = "country"
        private const val KEY_VAT_ID = "vatId"
        private const val KEY_TAX_NUMBER = "taxNumber"
        private const val KEY_IBAN = "iban"
        private const val KEY_BIC = "bic"
        private const val KEY_EMAIL = "email"
        private const val KEY_PHONE = "phone"
        private const val KEY_LOGO_PATH = "logoPath"
        private const val KEY_STORAGE_MODE = "storageMode"
        private const val KEY_STORAGE_TREE_URI = "storageTreeUri"
        private const val KEY_AUTO_NUMBERING = "autoNumbering"
        private const val KEY_NUMBER_PREFIX = "numberPrefix"
        private const val KEY_NEXT_NUMBER = "nextNumber"
        private const val KEY_PAYMENT_DAYS = "defaultPaymentDays"
        private const val KEY_SMALL_BUSINESS = "smallBusiness"
        private const val KEY_EXEMPTION_TEXT = "exemptionText"

        fun fromJson(o: JSONObject): CompanyProfile = CompanyProfile(
            name = o.optString(KEY_NAME),
            street = o.optString(KEY_STREET),
            zip = o.optString(KEY_ZIP),
            city = o.optString(KEY_CITY),
            country = o.optString(KEY_COUNTRY, "DE"),
            vatId = o.optString(KEY_VAT_ID),
            taxNumber = o.optString(KEY_TAX_NUMBER),
            iban = o.optString(KEY_IBAN),
            bic = o.optString(KEY_BIC),
            email = o.optString(KEY_EMAIL),
            phone = o.optString(KEY_PHONE),
            logoPath = o.optString(KEY_LOGO_PATH),
            storageMode = o.optString(KEY_STORAGE_MODE, STORAGE_DOWNLOADS),
            storageTreeUri = o.optString(KEY_STORAGE_TREE_URI),
            autoNumbering = o.optBoolean(KEY_AUTO_NUMBERING, true),
            numberPrefix = o.optString(KEY_NUMBER_PREFIX, "RE-"),
            nextNumber = o.optInt(KEY_NEXT_NUMBER, 1),
            defaultPaymentDays = o.optInt(KEY_PAYMENT_DAYS, 14),
            smallBusiness = o.optBoolean(KEY_SMALL_BUSINESS, false),
            exemptionText = o.optString(KEY_EXEMPTION_TEXT)
        )
    }
}

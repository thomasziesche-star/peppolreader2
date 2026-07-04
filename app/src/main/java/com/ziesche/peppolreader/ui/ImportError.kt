package com.ziesche.peppolreader.ui

import android.content.res.Resources
import androidx.annotation.StringRes
import com.ziesche.peppolreader.R
import org.xmlpull.v1.XmlPullParserException
import java.io.IOException

/**
 * The reasons a single file can fail to import, each mapped to a localized message.
 * Replaces the former free-text error strings ("Unknown error"), which were never
 * shown to the user and lost the failure category for the batch summary.
 */
enum class ImportError(@StringRes val messageRes: Int) {
    /** PDF opened fine but carries no embedded invoice XML (not a hybrid PDF). */
    PDF_NO_XML(R.string.error_pdf_no_xml),

    /** Password-protected PDF. */
    PDF_ENCRYPTED(R.string.error_pdf_encrypted),

    /** XML parsed but matches none of the supported invoice formats. */
    UNKNOWN_XML_FORMAT(R.string.error_unknown_format),

    /** File claims to be XML but is not well-formed. */
    XML_MALFORMED(R.string.error_xml_malformed),

    /** The content could not be read at all (IO, empty stream, broken PDF). */
    FILE_READ(R.string.error_file_read),

    /** Parsing succeeded but persisting the invoice/attachment failed. */
    STORAGE(R.string.error_import_general);

    companion object {
        /** Categorizes an exception thrown while parsing/persisting one invoice. */
        fun from(e: Exception): ImportError = when (e) {
            is XmlPullParserException -> XML_MALFORMED
            is IOException -> FILE_READ
            else -> STORAGE
        }
    }
}

/**
 * A file picked for import: either the extracted [InvoiceViewModel.ImportItem] or the
 * categorized reason it was dropped before reaching the parser. Keeps pick-time failures
 * (unreadable file, PDF without XML) countable in the batch summary instead of silently
 * vanishing or racing each other as individual snackbars.
 */
sealed class PickResult {
    data class Ok(val item: InvoiceViewModel.ImportItem) : PickResult()
    data class Failed(val error: ImportError) : PickResult()
}

/**
 * Builds the one-line result snackbar for a multi-file import. Pure string assembly so the
 * singular/plural handling (incl. Polish few/many) is unit-testable.
 */
object ImportSummaryFormatter {

    /**
     * @param singleError when exactly one file failed, its category — the localized cause is
     *        appended so the user learns *why* without digging.
     */
    fun format(
        res: Resources,
        successCount: Int,
        total: Int,
        duplicateCount: Int,
        errorCount: Int,
        singleError: ImportError? = null
    ): String = buildString {
        append(res.getString(R.string.import_batch_summary, successCount, total))
        if (duplicateCount > 0) {
            append(res.getQuantityString(R.plurals.import_batch_duplicates, duplicateCount, duplicateCount))
        }
        if (errorCount > 0) {
            append(res.getQuantityString(R.plurals.import_batch_errors, errorCount, errorCount))
        }
        if (errorCount == 1 && singleError != null) {
            append(" (").append(res.getString(singleError.messageRes)).append(")")
        }
    }
}

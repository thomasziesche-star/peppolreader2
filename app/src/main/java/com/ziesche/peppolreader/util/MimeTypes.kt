package com.ziesche.peppolreader.util

/**
 * Centralised MIME-type constants used across pickers, intents, FileProvider sharing
 * and exports. Keep this in sync with `acceptedMimeTypes` checks and any intent filters
 * declared in AndroidManifest.xml.
 */
object MimeTypes {
    const val PDF = "application/pdf"
    const val XML = "application/xml"
    const val TEXT_XML = "text/xml"
    const val TEXT_HTML = "text/html"
    const val CSV = "text/csv"
    const val ZIP = "application/zip"
}

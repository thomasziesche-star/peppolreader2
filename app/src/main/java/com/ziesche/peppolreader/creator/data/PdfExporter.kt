package com.ziesche.peppolreader.creator.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.ziesche.peppolreader.creator.model.CompanyProfile

/**
 * Copies a generated invoice PDF to the user-chosen export location. The canonical copy always
 * stays in app storage (`filesDir/created_invoices`) — this export is the convenience copy the
 * user can find in their file manager.
 *
 * - [CompanyProfile.STORAGE_DOWNLOADS] (default): public Downloads via MediaStore (API 29+;
 *   on Android 9 there is no MediaStore.Downloads and no permission is requested, so the
 *   export is skipped and only the internal copy exists).
 * - [CompanyProfile.STORAGE_CUSTOM]: a SAF tree the user picked; we hold a persisted
 *   read/write permission for it.
 *
 * Returns a human-readable destination label for the snackbar, or null when nothing was
 * exported (the caller then reports the internal save only).
 */
object PdfExporter {

    fun export(context: Context, pdf: ByteArray, displayName: String, profile: CompanyProfile): String? =
        when (profile.storageMode) {
            CompanyProfile.STORAGE_CUSTOM -> exportToTree(context, pdf, displayName, profile.storageTreeUri)
                ?: exportToDownloads(context, pdf, displayName) // fall back if the folder vanished
            else -> exportToDownloads(context, pdf, displayName)
        }

    private fun exportToDownloads(context: Context, pdf: ByteArray, displayName: String): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return runCatching {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return@runCatching null
            resolver.openOutputStream(uri)?.use { it.write(pdf) } ?: return@runCatching null
            "Downloads"
        }.getOrNull()
    }

    private fun exportToTree(context: Context, pdf: ByteArray, displayName: String, treeUriString: String): String? {
        if (treeUriString.isBlank()) return null
        return runCatching {
            val treeUri = Uri.parse(treeUriString)
            val dirUri = DocumentsContract.buildDocumentUriUsingTree(
                treeUri, DocumentsContract.getTreeDocumentId(treeUri)
            )
            val fileUri = DocumentsContract.createDocument(
                context.contentResolver, dirUri, "application/pdf", displayName
            ) ?: return@runCatching null
            context.contentResolver.openOutputStream(fileUri)?.use { it.write(pdf) }
                ?: return@runCatching null
            treeLabel(treeUri)
        }.getOrNull()
    }

    /** Human-readable folder name from a tree URI, e.g. "primary:Invoices" → "Invoices". */
    fun treeLabel(treeUri: Uri): String {
        val id = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: return "…"
        return id.substringAfterLast(':').substringAfterLast('/').ifBlank { id }
    }
}

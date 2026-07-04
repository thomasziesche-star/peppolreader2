package com.ziesche.peppolreader.ui.main

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.ziesche.peppolreader.R
import com.ziesche.peppolreader.export.CsvExporter
import com.ziesche.peppolreader.ui.InvoiceViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * CSV/ZIP export pipeline: builds the payload via the ViewModel, drives the
 * ACTION_CREATE_DOCUMENT picker, writes the bytes and offers a share action.
 *
 * MUST be constructed as a property initializer of the activity (launcher registration order —
 * see [BackupRestoreCoordinator]). [viewModel] and [root] are lambdas because both the
 * `by viewModels()` delegate and the view binding resolve later than this constructor runs.
 */
class ExportCoordinator(
    private val activity: AppCompatActivity,
    private val viewModel: () -> InvoiceViewModel,
    private val root: () -> View
) {

    /** Drives the ACTION_CREATE_DOCUMENT picker for the CSV/ZIP export. */
    private val createDocumentLauncher = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val payload = viewModel().pendingExportPayload
        viewModel().pendingExportPayload = null
        if (result.resultCode != Activity.RESULT_OK || payload == null) return@registerForActivityResult
        val uri = result.data?.data ?: return@registerForActivityResult
        writeExportPayload(uri, payload)
    }

    /** Localized CSV column headers, resolved at export time so they match the app language. */
    private fun buildHeaders() = CsvExporter.Headers(
        invoiceId = activity.getString(R.string.csv_invoice_id),
        issueDate = activity.getString(R.string.csv_issue_date),
        dueDate = activity.getString(R.string.csv_due_date),
        supplier = activity.getString(R.string.csv_supplier),
        supplierTaxId = activity.getString(R.string.csv_supplier_taxid),
        customer = activity.getString(R.string.csv_customer),
        net = activity.getString(R.string.csv_net),
        tax = activity.getString(R.string.csv_tax),
        gross = activity.getString(R.string.csv_gross),
        payable = activity.getString(R.string.csv_payable),
        currency = activity.getString(R.string.csv_currency),
        format = activity.getString(R.string.csv_format),
        docType = activity.getString(R.string.csv_doc_type),
        category = activity.getString(R.string.csv_category),
        note = activity.getString(R.string.csv_note),
        fileName = activity.getString(R.string.csv_file_name),
        docTypeInvoice = activity.getString(R.string.csv_doc_type_invoice),
        docTypeCreditNote = activity.getString(R.string.csv_doc_type_credit_note),
        docTypeCorrected = activity.getString(R.string.csv_doc_type_corrected)
    )

    /** Delegate target for [com.ziesche.peppolreader.ui.ExportBottomSheetFragment.Listener]. */
    fun onExportRequested(fromIso: String, toIso: String, includeXmlBundle: Boolean) {
        activity.lifecycleScope.launch {
            val vm = viewModel()
            when (val payload = vm.buildExportPayload(fromIso, toIso, includeXmlBundle, buildHeaders())) {
                InvoiceViewModel.ExportPayload.Empty ->
                    Snackbar.make(root(), R.string.export_no_invoices, Snackbar.LENGTH_LONG).show()
                is InvoiceViewModel.ExportPayload.Error ->
                    Snackbar.make(
                        root(),
                        activity.getString(R.string.export_error, payload.message),
                        Snackbar.LENGTH_LONG
                    ).show()
                is InvoiceViewModel.ExportPayload.Success -> {
                    vm.pendingExportPayload = payload
                    val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType(payload.mimeType)
                        .putExtra(Intent.EXTRA_TITLE, payload.suggestedFileName)
                    createDocumentLauncher.launch(intent)
                }
            }
        }
    }

    private fun writeExportPayload(uri: Uri, payload: InvoiceViewModel.ExportPayload.Success) {
        activity.lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    activity.contentResolver.openOutputStream(uri)?.use { it.write(payload.bytes) }
                    true
                }.getOrElse { false }
            }
            if (!ok) {
                Snackbar.make(
                    root(),
                    activity.getString(R.string.export_error, "I/O"),
                    Snackbar.LENGTH_LONG
                ).show()
                return@launch
            }
            val msg = activity.resources.getQuantityString(
                R.plurals.export_success, payload.invoiceCount, payload.invoiceCount
            )
            Snackbar.make(root(), msg, Snackbar.LENGTH_LONG)
                .setAction(R.string.export_share) { shareExportedFile(uri, payload.mimeType) }
                .show()
        }
    }

    private fun shareExportedFile(uri: Uri, mimeType: String) {
        val share = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        activity.startActivity(Intent.createChooser(share, activity.getString(R.string.export_share)))
    }
}

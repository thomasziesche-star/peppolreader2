package com.ziesche.peppolreader.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.ziesche.peppolreader.R
import com.ziesche.peppolreader.data.model.Invoice
import com.ziesche.peppolreader.databinding.FragmentInvoiceListBinding
import com.ziesche.peppolreader.parser.ZugferdExtractor
import com.ziesche.peppolreader.util.MimeTypes
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class InvoiceListFragment : Fragment() {

    private var _binding: FragmentInvoiceListBinding? = null
    private val binding get() = _binding!!
    
    private val viewModel: InvoiceViewModel by activityViewModels()
    private lateinit var adapter: InvoiceAdapter
    
    /**
     * MIME types accepted by the picker. The DocumentsUI / Storage Access Framework picker
     * uses this list to filter out everything else (JPEGs, PNGs, etc.) before the user sees it.
     */
    private val acceptedMimeTypes = arrayOf(
        MimeTypes.PDF,
        MimeTypes.XML,
        MimeTypes.TEXT_XML
    )

    /**
     * Modern multi-document picker. Replaces the old ACTION_OPEN_DOCUMENT + EXTRA_MIME_TYPES
     * intent, which some file managers ignored and ended up showing every file.
     *
     * Some OEM pickers (Samsung Files, Xiaomi Mi File Manager, …) ignore the MIME filter
     * altogether and surface everything. To keep the UX clean, we apply a second filter on
     * the returned URIs and inform the user via a Snackbar how many files were skipped.
     */
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        val filesToImport = mutableListOf<InvoiceViewModel.ImportItem>()
        val pickErrors = mutableListOf<ImportError>()
        var skipped = 0
        try {
            uris.forEach { uri ->
                if (!isAcceptedFile(uri)) {
                    skipped++
                    return@forEach
                }
                when (val picked = processUri(uri)) {
                    is PickResult.Ok -> filesToImport.add(picked.item)
                    is PickResult.Failed -> pickErrors.add(picked.error)
                }
            }
            if (filesToImport.isNotEmpty() || pickErrors.isNotEmpty()) {
                viewModel.importInvoices(filesToImport, pickErrors)
            }
            if (skipped > 0) {
                val msg = resources.getQuantityString(
                    R.plurals.import_skipped_files, skipped, skipped
                )
                Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Snackbar.make(binding.root, R.string.error_parsing, Snackbar.LENGTH_LONG).show()
        }
    }

    /**
     * Returns true if [uri] looks like a PDF or XML — checked via MIME type first, with a
     * file-extension fallback for content providers that don't report a meaningful MIME.
     */
    private fun isAcceptedFile(uri: android.net.Uri): Boolean {
        val mime = requireContext().contentResolver.getType(uri)
        if (mime in acceptedMimeTypes) return true
        val name = getFileName(uri)
        return name.endsWith(".pdf", ignoreCase = true) ||
            name.endsWith(".xml", ignoreCase = true)
    }

    private fun processUri(uri: android.net.Uri): PickResult {
        return try {
            val fileName = getFileName(uri)
            if (isPdf(uri, fileName)) {
                processPdfUri(uri, fileName)
            } else {
                val inputStream = requireContext().contentResolver.openInputStream(uri)
                val xmlContent = inputStream?.bufferedReader()?.use { it.readText() } ?: ""
                if (xmlContent.isNotEmpty()) {
                    PickResult.Ok(InvoiceViewModel.ImportItem(fileName, xmlContent))
                } else {
                    PickResult.Failed(ImportError.FILE_READ)
                }
            }
        } catch (e: Exception) {
            PickResult.Failed(ImportError.FILE_READ)
        }
    }

    private fun isPdf(uri: android.net.Uri, fileName: String): Boolean {
        val mime = requireContext().contentResolver.getType(uri)
        if (mime == MimeTypes.PDF) return true
        return fileName.endsWith(".pdf", ignoreCase = true)
    }

    private fun processPdfUri(uri: android.net.Uri, fileName: String): PickResult {
        val stream = requireContext().contentResolver.openInputStream(uri)
            ?: return PickResult.Failed(ImportError.FILE_READ)
        return stream.use { input ->
            when (val result = ZugferdExtractor().extract(input)) {
                is ZugferdExtractor.Result.Success ->
                    PickResult.Ok(InvoiceViewModel.ImportItem(fileName, result.xml, result.originalPdf))
                ZugferdExtractor.Result.Encrypted -> PickResult.Failed(ImportError.PDF_ENCRYPTED)
                ZugferdExtractor.Result.NoEmbeddedXml -> PickResult.Failed(ImportError.PDF_NO_XML)
                is ZugferdExtractor.Result.Error -> PickResult.Failed(ImportError.FILE_READ)
            }
        }
    }

    /**
     * Entry point for URIs delivered via external Intents (ACTION_VIEW / ACTION_SEND).
     */
    fun importExternalUri(uri: android.net.Uri) {
        when (val picked = processUri(uri)) {
            is PickResult.Ok -> viewModel.importInvoices(listOf(picked.item))
            is PickResult.Failed -> viewModel.importInvoices(emptyList(), listOf(picked.error))
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentInvoiceListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView()
        observeViewModel()

        // Import button in empty state
        binding.btnImportEmpty.setOnClickListener {
            openFilePicker()
        }

        // Drain any URI that arrived via ACTION_VIEW / ACTION_SEND before this fragment was ready
        (activity as? com.ziesche.peppolreader.MainActivity)?.consumePendingImportUri()?.let { uri ->
            importExternalUri(uri)
        }
    }
    
    private fun setupRecyclerView() {
        adapter = InvoiceAdapter(
            onItemClick = { invoice -> navigateToDetail(invoice) },
            onItemLongClick = { invoice -> showDeleteDialog(invoice) },
            onAttachmentClick = { invoice -> viewModel.openAttachment(invoice, requireContext()) }
        )
        binding.invoiceRecyclerView.adapter = adapter
        
        // Setup Search
        binding.searchView.setOnQueryTextListener(object : androidx.appcompat.widget.SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                return false
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                viewModel.setSearchQuery(newText ?: "")
                return true
            }
        })

        // Status filter chips. `selectionRequired=true` in XML guarantees one chip is always checked.
        // The date-range chip is non-checkable (Assist style) — `checkedIds` ignores it.
        binding.filterChips.setOnCheckedStateChangeListener { _, checkedIds ->
            val filter = when (checkedIds.firstOrNull()) {
                R.id.chip_filter_unpaid -> InvoiceViewModel.StatusFilter.UNPAID
                R.id.chip_filter_overdue -> InvoiceViewModel.StatusFilter.OVERDUE
                else -> InvoiceViewModel.StatusFilter.ALL
            }
            viewModel.setStatusFilter(filter)
        }

        // Date-range chip — tap to open picker, close-icon clears the active range.
        binding.chipFilterDaterange.setOnClickListener { showDateRangePicker() }
        binding.chipFilterDaterange.setOnCloseIconClickListener { viewModel.setDateRange(null) }
        // Sync chip UI with the (possibly retained) ViewModel state after rotation/return.
        viewModel.statusFilter.value?.let { current ->
            val targetId = when (current) {
                InvoiceViewModel.StatusFilter.UNPAID -> R.id.chip_filter_unpaid
                InvoiceViewModel.StatusFilter.OVERDUE -> R.id.chip_filter_overdue
                InvoiceViewModel.StatusFilter.ALL -> R.id.chip_filter_all
            }
            if (binding.filterChips.checkedChipId != targetId) {
                binding.filterChips.check(targetId)
            }
        }
    }
    
    private fun observeViewModel() {
        viewModel.allInvoices.observe(viewLifecycleOwner) { invoices ->
            adapter.submitList(invoices)
            updateEmptyState(invoices.isEmpty())
            binding.invoiceCounter.text = invoices.size.toString()
            binding.invoiceCounter.contentDescription =
                getString(R.string.cd_invoice_count, invoices.size)
        }

        viewModel.dateRange.observe(viewLifecycleOwner) { range ->
            updateDateRangeChip(range)
        }
        
        viewModel.error.observe(viewLifecycleOwner) { error ->
            error?.let {
                Snackbar.make(binding.root, it, Snackbar.LENGTH_LONG).show()
                viewModel.clearError()
            }
        }
        
        viewModel.message.observe(viewLifecycleOwner) { message ->
            message?.let {
                // LONG: batch summaries carry counts + a possible cause and need reading time.
                Snackbar.make(binding.root, it, Snackbar.LENGTH_LONG).show()
                viewModel.clearMessage()
            }
        }

        // One-shot event from the ViewModel: after a successful single import (or duplicate
        // hit), navigate to the detail screen. Locale-safe replacement for the old string-
        // match approach.
        viewModel.importNavigationId.observe(viewLifecycleOwner) { id ->
            if (id != null) {
                findNavController().navigate(R.id.action_invoiceList_to_invoiceDetail)
                viewModel.consumedImportNavigation()
            }
        }
    }
    
    private fun updateEmptyState(isEmpty: Boolean) {
        binding.emptyState.visibility = if (isEmpty) View.VISIBLE else View.GONE
        binding.invoiceRecyclerView.visibility = if (isEmpty) View.GONE else View.VISIBLE
    }
    
    private fun navigateToDetail(invoice: Invoice) {
        viewModel.selectInvoice(invoice)
        findNavController().navigate(R.id.action_invoiceList_to_invoiceDetail)
    }
    
    private fun showDeleteDialog(invoice: Invoice) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete)
            .setMessage(R.string.confirm_delete)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                viewModel.deleteInvoice(invoice)
            }
            .show()
    }
    
    /**
     * Called from MainActivity to open file picker
     */
    fun openFilePicker() {
        filePickerLauncher.launch(acceptedMimeTypes)
    }

    private fun getFileName(uri: android.net.Uri): String {
        var result: String? = null
        if (uri.scheme == "content") {
            try {
                requireContext().contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (index != -1) {
                            result = cursor.getString(index)
                        }
                    }
                }
            } catch (e: Exception) {
                // Ignore
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/') ?: -1
            if (cut != -1) {
                result = result?.substring(cut + 1)
            }
        }
        return result ?: "unknown.xml"
    }

    /**
     * Opens MaterialDatePicker in range-mode. If a range is already active, the picker
     * opens pre-selected on those bounds — otherwise no defaults, the user picks freely.
     * UTC-based, as MaterialDatePicker requires its selection in UTC millis.
     */
    private fun showDateRangePicker() {
        val builder = MaterialDatePicker.Builder.dateRangePicker()
            .setTitleText(R.string.list_filter_daterange_title)

        viewModel.dateRange.value?.let { range ->
            val isoUtc = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val from = isoUtc.parse(range.fromIso)?.time
            val to = isoUtc.parse(range.toIso)?.time
            if (from != null && to != null) {
                builder.setSelection(androidx.core.util.Pair(from, to))
            }
        }

        val picker = builder.build()
        picker.addOnPositiveButtonClickListener { selection ->
            val isoUtc = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val fromIso = isoUtc.format(java.util.Date(selection.first))
            val toIso = isoUtc.format(java.util.Date(selection.second))
            viewModel.setDateRange(InvoiceViewModel.DateRange(fromIso, toIso))
        }
        picker.show(parentFragmentManager, "daterange_picker")
    }

    /** Toggles the chip between "Zeitraum" hint and "01.01.2024 – 31.03.2024" with close icon. */
    private fun updateDateRangeChip(range: InvoiceViewModel.DateRange?) {
        val chip = binding.chipFilterDaterange
        if (range == null) {
            chip.text = getString(R.string.list_filter_daterange)
            chip.isCloseIconVisible = false
            chip.isChecked = false
        } else {
            val isoUtc = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val display = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
            val from = isoUtc.parse(range.fromIso)?.let { display.format(it) } ?: range.fromIso
            val to = isoUtc.parse(range.toIso)?.let { display.format(it) } ?: range.toIso
            chip.text = "$from – $to"
            chip.isCloseIconVisible = true
            chip.isChecked = true
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

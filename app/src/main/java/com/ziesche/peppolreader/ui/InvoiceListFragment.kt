package com.ziesche.peppolreader.ui

import android.app.Activity
import android.content.Intent
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar

class InvoiceListFragment : Fragment() {

    private var _binding: FragmentInvoiceListBinding? = null
    private val binding get() = _binding!!
    
    private val viewModel: InvoiceViewModel by activityViewModels()
    private lateinit var adapter: InvoiceAdapter
    
    // File picker launcher
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data
            val filesToImport = mutableListOf<Pair<String, String>>()
            
            try {
                // Check for multiple selection
                if (data?.clipData != null) {
                    val count = data.clipData!!.itemCount
                    for (i in 0 until count) {
                        val uri = data.clipData!!.getItemAt(i).uri
                        processUri(uri)?.let { filesToImport.add(it) }
                    }
                } else if (data?.data != null) {
                    // Single selection
                    processUri(data.data!!)?.let { filesToImport.add(it) }
                }
                
                if (filesToImport.isNotEmpty()) {
                    viewModel.importInvoices(filesToImport)
                }
            } catch (e: Exception) {
                Snackbar.make(binding.root, R.string.error_parsing, Snackbar.LENGTH_LONG).show()
            }
        }
    }
    
    private fun processUri(uri: android.net.Uri): Pair<String, String>? {
        return try {
            val fileName = getFileName(uri)
            if (isPdf(uri, fileName)) {
                processPdfUri(uri, fileName)
            } else {
                val inputStream = requireContext().contentResolver.openInputStream(uri)
                val xmlContent = inputStream?.bufferedReader()?.use { it.readText() } ?: ""
                if (xmlContent.isNotEmpty()) Pair(fileName, xmlContent) else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun isPdf(uri: android.net.Uri, fileName: String): Boolean {
        val mime = requireContext().contentResolver.getType(uri)
        if (mime == "application/pdf") return true
        return fileName.endsWith(".pdf", ignoreCase = true)
    }

    private fun processPdfUri(uri: android.net.Uri, fileName: String): Pair<String, String>? {
        val stream = requireContext().contentResolver.openInputStream(uri) ?: return null
        return stream.use { input ->
            when (val result = ZugferdExtractor().extract(input)) {
                is ZugferdExtractor.Result.Success -> Pair(fileName, result.xml)
                ZugferdExtractor.Result.Encrypted -> {
                    showSnackbar(getString(R.string.error_pdf_encrypted))
                    null
                }
                ZugferdExtractor.Result.NoEmbeddedXml -> {
                    showSnackbar(getString(R.string.error_pdf_no_xml))
                    null
                }
                is ZugferdExtractor.Result.Error -> {
                    showSnackbar(getString(R.string.error_pdf_read, result.message))
                    null
                }
            }
        }
    }

    private fun showSnackbar(text: String) {
        Snackbar.make(binding.root, text, Snackbar.LENGTH_LONG).show()
    }

    /**
     * Entry point for URIs delivered via external Intents (ACTION_VIEW / ACTION_SEND).
     */
    fun importExternalUri(uri: android.net.Uri) {
        processUri(uri)?.let { viewModel.importInvoices(listOf(it)) }
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
    }
    
    private fun observeViewModel() {
        viewModel.allInvoices.observe(viewLifecycleOwner) { invoices ->
            adapter.submitList(invoices)
            updateEmptyState(invoices.isEmpty())
            binding.invoiceCounter.text = invoices.size.toString()
        }
        
        viewModel.error.observe(viewLifecycleOwner) { error ->
            error?.let {
                Snackbar.make(binding.root, it, Snackbar.LENGTH_LONG).show()
                viewModel.clearError()
            }
        }
        
        viewModel.message.observe(viewLifecycleOwner) { message ->
            message?.let {
                Snackbar.make(binding.root, it, Snackbar.LENGTH_SHORT).show()
                // Check if we should navigate (hacky but simple based on message content or just add a proper event)
                // Better: The ViewModel selects the invoice. 
                // We can observe 'selectedInvoice' here? 
                // But selectedInvoice is also set when clicking an item.
                // If we observe selectedInvoice and it changes, should we navigate?
                // The current navigateToDetail ALREADY calls selectInvoice.
                // If we observe it here, we might loop or double navigate if we are not careful.
                // navigateToDetail pushes to backstack. 
                // Let's add a one-shot event or just check if the message implies navigation.
                
                if (it.contains("Rechnung gespeichert") || it.contains("Rechnung ist bereits vorhanden")) { 
                    // Only navigate if it's a save/import action.
                    // "Rechnung gelöscht" should NOT trigger navigation.
                     viewModel.selectedInvoice.value?.let { invoice ->
                         // Prevent double navigation if already there? 
                         // We are in ListFragment.
                         findNavController().navigate(R.id.action_invoiceList_to_invoiceDetail)
                     }
                }
                
                viewModel.clearMessage()
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
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"  // Accept any file type, we'll check for XML/PDF
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/xml", "application/xml", "application/pdf"))
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        filePickerLauncher.launch(intent)
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

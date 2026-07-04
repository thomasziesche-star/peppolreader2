package com.ziesche.peppolreader.ui

import android.content.ContentValues
import android.content.Intent
import android.content.res.ColorStateList
import androidx.core.content.ContextCompat
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.print.PrintAttributes
import android.print.PrintManager
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import com.ziesche.peppolreader.R
import com.ziesche.peppolreader.data.model.CorrectionInfo
import com.ziesche.peppolreader.databinding.FragmentInvoiceDetailBinding
import com.ziesche.peppolreader.pdf.PdfGenerator
import com.ziesche.peppolreader.pdf.XmlPrettyPrinter
import java.io.File
import java.io.FileOutputStream
import org.json.JSONObject

class InvoiceDetailFragment : Fragment() {

    private var _binding: FragmentInvoiceDetailBinding? = null
    private val binding get() = _binding!!
    
    private val viewModel: InvoiceViewModel by activityViewModels()
    private lateinit var pdfGenerator: PdfGenerator
    private val xmlPrettyPrinter = XmlPrettyPrinter()
    private var currentHtml: String = ""
    /** True once the raw-XML WebView has finished loading its page. */
    private var rawXmlReady: Boolean = false
    /** Last search query; applied after the page finishes loading. */
    private var pendingRawXmlQuery: String = ""

    // Original-PDF tab state. PdfRenderer requires both descriptor and renderer to be
    // closed together; we keep both nullable and re-init when the invoice changes.
    private var pdfRenderer: PdfRenderer? = null
    private var pdfFileDescriptor: ParcelFileDescriptor? = null
    private var currentPdfPath: String? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentInvoiceDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        pdfGenerator = PdfGenerator(requireContext())

        setupWebView()
        setupRawXmlWebView()
        setupTabs()
        setupRawSearch()
        setupButtons()
        observeViewModel()
    }
    
    private fun setupWebView() {
        binding.invoiceWebview.apply {
            settings.apply {
                javaScriptEnabled = false
                loadWithOverviewMode = true
                useWideViewPort = true
                builtInZoomControls = true
                displayZoomControls = false
            }
            
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val url = request?.url?.toString() ?: return true
                    
                    if (url.startsWith("mailto:") || url.startsWith("tel:")) {
                        try {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            startActivity(intent)
                        } catch (e: Exception) {
                            // Ignore if no app found
                        }
                        return true
                    }
                    
                    return true // Don't allow navigation
                }
                
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    binding.loadingIndicator.visibility = View.GONE
                }
            }
            
            setBackgroundColor(resources.getColor(android.R.color.transparent, null))
        }
    }

    private fun setupRawXmlWebView() {
        binding.rawXmlWebview.apply {
            settings.apply {
                @android.annotation.SuppressLint("SetJavaScriptEnabled")
                javaScriptEnabled = true // Needed for the in-page live filter
                loadWithOverviewMode = true
                useWideViewPort = false
                builtInZoomControls = true
                displayZoomControls = false
            }
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    rawXmlReady = true
                    val currentQuery = binding.rawSearchInput.text?.toString().orEmpty()
                    if (currentQuery.isNotEmpty()) applyRawSearch(currentQuery)
                }
            }
            setBackgroundColor(resources.getColor(android.R.color.transparent, null))
        }
    }

    private fun setupTabs() {
        binding.detailTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                val position = tab?.position ?: 0
                binding.invoiceWebview.visibility = if (position == 0) View.VISIBLE else View.GONE
                binding.rawXmlContainer.visibility = if (position == 1) View.VISIBLE else View.GONE
                binding.pdfContainer.visibility = if (position == 2) View.VISIBLE else View.GONE
                // Attachment label only makes sense on the rendered tab
                if (position == 0) updateAttachmentLabel() else binding.detailAttachmentLabel.visibility = View.GONE

                if (position == 2) loadPdfTab()
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    /**
     * Lazily opens the PDF attached to the current invoice and binds it to the RecyclerView.
     * Called when the PDF tab is selected. Re-uses an already-open renderer when the
     * attachment path hasn't changed.
     */
    private fun loadPdfTab() {
        val invoice = viewModel.selectedInvoice.value
        val path = invoice?.embeddedDocumentPath
        val isPdf = path != null && path.endsWith(".pdf", ignoreCase = true)

        if (path == null || !isPdf) {
            showPdfEmptyState(getString(R.string.pdf_none_attached))
            return
        }
        if (currentPdfPath == path && pdfRenderer != null) {
            // Already loaded — just make sure the recycler is visible.
            binding.pdfEmptyState.visibility = View.GONE
            binding.pdfRecyclerView.visibility = View.VISIBLE
            return
        }
        // New file — close any previous renderer and open the new one.
        closePdfRenderer()
        val file = File(path)
        if (!file.exists()) {
            showPdfEmptyState(getString(R.string.pdf_none_attached))
            return
        }
        try {
            val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(fd)
            pdfFileDescriptor = fd
            pdfRenderer = renderer
            currentPdfPath = path
            val width = binding.pdfRecyclerView.width.takeIf { it > 0 }
                ?: resources.displayMetrics.widthPixels
            binding.pdfRecyclerView.adapter = PdfPageAdapter(renderer, width)
            binding.pdfEmptyState.visibility = View.GONE
            binding.pdfRecyclerView.visibility = View.VISIBLE
        } catch (e: Exception) {
            showPdfEmptyState(getString(R.string.pdf_render_error))
        }
    }

    private fun showPdfEmptyState(message: String) {
        binding.pdfEmptyState.text = message
        binding.pdfEmptyState.visibility = View.VISIBLE
        binding.pdfRecyclerView.visibility = View.GONE
        binding.pdfRecyclerView.adapter = null
        closePdfRenderer()
    }

    private fun closePdfRenderer() {
        binding.pdfRecyclerView.adapter = null
        try { pdfRenderer?.close() } catch (_: Exception) {}
        try { pdfFileDescriptor?.close() } catch (_: Exception) {}
        pdfRenderer = null
        pdfFileDescriptor = null
        currentPdfPath = null
    }

    private fun setupRawSearch() {
        binding.rawSearchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString().orEmpty()
                pendingRawXmlQuery = query
                if (rawXmlReady) applyRawSearch(query)
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    /** Calls the in-page JS filter with the current query. JSONObject.quote escapes safely. */
    private fun applyRawSearch(query: String) {
        val js = "applyFilter(${JSONObject.quote(query)});"
        binding.rawXmlWebview.evaluateJavascript(js, null)
    }

    private fun setupButtons() {
        binding.btnDownloadPdf.setOnClickListener {
            savePdf()
        }

        binding.btnShare.setOnClickListener {
            sharePdf()
        }

        binding.btnMarkPaid.setOnClickListener {
            val invoice = viewModel.selectedInvoice.value ?: return@setOnClickListener
            playPaidBounce()
            viewModel.togglePaid(invoice)
        }

        binding.fabAskAi.setOnClickListener {
            if (viewModel.selectedInvoice.value == null) return@setOnClickListener
            AiChatBottomSheet().show(childFragmentManager, AiChatBottomSheet.TAG)
        }

        binding.detailNoteLabel.setOnClickListener { showNoteDialog() }

        viewModel.selectedInvoice.observe(viewLifecycleOwner) { invoice ->
            updatePaidButtonStyle(invoice?.paidAt != null)
            updatePdfTabEnabledLook(invoice)
            updateCorrectionBlock(invoice)
            updateNoteLabel(invoice)
        }
    }

    /** Shows the current note/category, or a faint prompt to add one. Always tappable. */
    private fun updateNoteLabel(invoice: com.ziesche.peppolreader.data.model.Invoice?) {
        val category = invoice?.category?.takeIf { it.isNotBlank() }
        val note = invoice?.note?.takeIf { it.isNotBlank() }
        binding.detailNoteLabel.text = when {
            category != null && note != null -> getString(R.string.note_label_both, category, note)
            category != null -> getString(R.string.note_label_category, category)
            note != null -> getString(R.string.note_label_note, note)
            else -> getString(R.string.note_add_prompt)
        }
    }

    /** Edit dialog for the bookkeeping note + category, with suggestions from used categories. */
    private fun showNoteDialog() {
        val invoice = viewModel.selectedInvoice.value ?: return
        val view = layoutInflater.inflate(R.layout.dialog_invoice_note, null)
        val categoryInput = view.findViewById<android.widget.AutoCompleteTextView>(R.id.input_category)
        val noteInput = view.findViewById<android.widget.EditText>(R.id.input_note)
        categoryInput.setText(invoice.category.orEmpty())
        noteInput.setText(invoice.note.orEmpty())

        // Populate category autocomplete from already-used categories.
        viewLifecycleOwner.lifecycleScope.launch {
            val used = viewModel.usedCategories()
            if (used.isNotEmpty()) {
                categoryInput.setAdapter(
                    android.widget.ArrayAdapter(
                        requireContext(), android.R.layout.simple_list_item_1, used
                    )
                )
            }
        }

        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.note_dialog_title)
            .setView(view)
            .setPositiveButton(R.string.save) { _, _ ->
                viewModel.saveNoteAndCategory(
                    invoice,
                    noteInput.text?.toString(),
                    categoryInput.text?.toString()
                )
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Reflects paid/unpaid in the toggle button: muted-green tonal fill when paid, neutral
     * surface-variant when open. Theme attributes are resolved via [MaterialColors] so the
     * "open" state tracks light/dark mode correctly.
     */
    private fun updatePaidButtonStyle(paid: Boolean) {
        val ctx = requireContext()
        binding.btnMarkPaid.apply {
            text = getString(if (paid) R.string.status_paid else R.string.status_open)
            // Visible label shows the state; the spoken label states the action this toggle performs.
            contentDescription = getString(if (paid) R.string.cd_mark_open else R.string.cd_mark_paid)
            val bgColor = if (paid) {
                ContextCompat.getColor(ctx, R.color.anthropic_green)
            } else {
                com.google.android.material.color.MaterialColors.getColor(
                    this, com.google.android.material.R.attr.colorSurfaceVariant
                )
            }
            val fgColor = if (paid) {
                ContextCompat.getColor(ctx, R.color.white)
            } else {
                com.google.android.material.color.MaterialColors.getColor(
                    this, com.google.android.material.R.attr.colorOnSurfaceVariant
                )
            }
            backgroundTintList = ColorStateList.valueOf(bgColor)
            setTextColor(fgColor)
            iconTint = ColorStateList.valueOf(fgColor)
            // Icon only when paid — emphasizes the "done" state without cluttering the open state.
            icon = if (paid) ContextCompat.getDrawable(ctx, R.drawable.ic_check) else null
        }
    }

    /** Small scale bounce to acknowledge the click before the DB write returns. */
    private fun playPaidBounce() {
        binding.btnMarkPaid.animate()
            .scaleX(1.10f).scaleY(1.10f)
            .setDuration(110)
            .withEndAction {
                binding.btnMarkPaid.animate()
                    .scaleX(1f).scaleY(1f)
                    .setDuration(110)
                    .start()
            }
            .start()
    }

    /**
     * Shows the KSeF FA(3) correction block when the invoice is a correction (KOR / KOR_ZAL /
     * KOR_ROZ) and has a stored `correctionInfoJson`. Hidden otherwise, so non-Polish
     * invoices are entirely unaffected.
     */
    private fun updateCorrectionBlock(invoice: com.ziesche.peppolreader.data.model.Invoice?) {
        val subtype = invoice?.invoiceSubtype
        val isCorrection = subtype == "KOR" || subtype == "KOR_ZAL" || subtype == "KOR_ROZ"
        val info = if (isCorrection) CorrectionInfo.parse(invoice?.correctionInfoJson) else null
        if (info == null) {
            binding.correctionInfoLayout.visibility = View.GONE
            return
        }
        binding.correctionInfoLayout.visibility = View.VISIBLE
        binding.correctionOriginal.text = getString(
            R.string.correction_original_invoice,
            info.originalInvoiceNumber.orEmpty(),
            info.originalIssueDate.orEmpty()
        )
        if (info.originalKsefId != null) {
            binding.correctionKsefId.text = getString(R.string.correction_ksef_id, info.originalKsefId)
            binding.correctionKsefId.visibility = View.VISIBLE
        } else {
            binding.correctionKsefId.visibility = View.GONE
        }
        if (info.reason != null) {
            binding.correctionReason.text = getString(R.string.correction_reason, info.reason)
            binding.correctionReason.visibility = View.VISIBLE
        } else {
            binding.correctionReason.visibility = View.GONE
        }
    }

    /**
     * Greys out the PDF tab label when the current invoice has no PDF attachment, so users
     * see at a glance that the tab will only show a placeholder.
     */
    private fun updatePdfTabEnabledLook(invoice: com.ziesche.peppolreader.data.model.Invoice?) {
        val path = invoice?.embeddedDocumentPath
        val hasPdf = path != null && path.endsWith(".pdf", ignoreCase = true) && File(path).exists()
        binding.detailTabs.getTabAt(2)?.view?.alpha = if (hasPdf) 1f else 0.4f
    }
    
    private fun observeViewModel() {
        viewModel.parsedInvoice.observe(viewLifecycleOwner) { invoice ->
            invoice?.let {
                binding.loadingIndicator.visibility = View.VISIBLE
                val fileName = viewModel.selectedInvoice.value?.fileName ?: ""

                // Detect Dark Mode
                val currentNightMode = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
                val isDarkMode = currentNightMode == android.content.res.Configuration.UI_MODE_NIGHT_YES

                currentHtml = pdfGenerator.generateHtml(it, fileName, isDarkMode)
                binding.invoiceWebview.loadDataWithBaseURL(
                    null,
                    currentHtml,
                    "text/html",
                    "UTF-8",
                    null
                )

                // Raw XML tab
                val rawXml = viewModel.selectedInvoice.value?.xmlContent.orEmpty()
                rawXmlReady = false
                val rawHtml = xmlPrettyPrinter.toHtml(
                    rawXml,
                    isDarkMode,
                    getString(R.string.search_no_matches)
                )
                binding.rawXmlWebview.loadDataWithBaseURL(
                    null, rawHtml, "text/html", "UTF-8", null
                )

                // Invalidate any open PDF — loadPdfTab() will reopen when the user opens the tab.
                closePdfRenderer()

                updateAttachmentLabel()
            }
        }

        viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            binding.loadingIndicator.visibility = if (isLoading) View.VISIBLE else View.GONE
        }
    }

    /**
     * Renders the attachment label. Source of truth is the [Invoice] entity stored in the
     * DB — the ViewModel populates `embeddedDocumentFilename` / `embeddedDocumentPath`
     * for both XML-embedded PDFs (UBL AdditionalDocumentReference) and original
     * ZUGFeRD/Factur-X PDFs at import time. The list adapter reads the same fields, so
     * what the user sees in the overview and detail view stays consistent.
     * The label only shows on the rendered-invoice tab.
     */
    private fun updateAttachmentLabel() {
        if (binding.detailTabs.selectedTabPosition == 1) {
            binding.detailAttachmentLabel.visibility = View.GONE
            return
        }
        val selected = viewModel.selectedInvoice.value
        val attachmentName = selected?.embeddedDocumentFilename
        if (!attachmentName.isNullOrEmpty()) {
            binding.detailAttachmentLabel.text =
                getString(R.string.attachment_label, attachmentName)
            binding.detailAttachmentLabel.visibility = View.VISIBLE
            binding.detailAttachmentLabel.setOnClickListener {
                viewModel.openAttachment(selected, requireContext())
            }
        } else {
            binding.detailAttachmentLabel.visibility = View.GONE
        }
    }
    
    private fun savePdf() {
        val invoice = viewModel.parsedInvoice.value ?: return
        val invoiceId = invoice.invoice.id
        
        try {
            // Use Print Manager for proper PDF creation
            val printManager = requireContext().getSystemService(PrintManager::class.java)
            val printAdapter = binding.invoiceWebview.createPrintDocumentAdapter(invoiceId)
            
            // Create print job
            val attributes = PrintAttributes.Builder()
                .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                .setResolution(PrintAttributes.Resolution("pdf", "pdf", 300, 300))
                .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                .build()
            
            printManager.print(getString(R.string.pdf_export_name, invoiceId), printAdapter, attributes)
            
        } catch (e: Exception) {
            Snackbar.make(binding.root, R.string.error_pdf, Snackbar.LENGTH_LONG).show()
        }
    }
    
    private fun sharePdf() {
        val invoice = viewModel.parsedInvoice.value ?: return
        val invoiceId = invoice.invoice.id
        
        try {
            // Create a temporary HTML file
            val htmlFile = File(requireContext().cacheDir, "${getString(R.string.pdf_export_name, invoiceId)}.html")
            htmlFile.writeText(currentHtml)
            
            // Create content URI using FileProvider
            val contentUri = FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.fileprovider",
                htmlFile
            )
            
            // Create share intent
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = com.ziesche.peppolreader.util.MimeTypes.TEXT_HTML
                putExtra(Intent.EXTRA_STREAM, contentUri)
                putExtra(Intent.EXTRA_SUBJECT, getString(R.string.share_subject, invoiceId))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            val chooserIntent = Intent.createChooser(shareIntent, getString(R.string.share))
            startActivity(chooserIntent)
            
        } catch (e: Exception) {
            Snackbar.make(binding.root, getString(R.string.error_share, e.message ?: ""), Snackbar.LENGTH_LONG).show()
        }
    }

    override fun onDestroyView() {
        closePdfRenderer()
        super.onDestroyView()
        _binding = null
    }
}

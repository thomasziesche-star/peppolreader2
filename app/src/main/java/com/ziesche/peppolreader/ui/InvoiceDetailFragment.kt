package com.ziesche.peppolreader.ui

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
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
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import com.ziesche.peppolreader.R
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
                val rawSelected = tab?.position == 1
                binding.invoiceWebview.visibility = if (rawSelected) View.GONE else View.VISIBLE
                binding.rawXmlContainer.visibility = if (rawSelected) View.VISIBLE else View.GONE
                // Attachment label only makes sense in the rendered view
                if (rawSelected) {
                    binding.detailAttachmentLabel.visibility = View.GONE
                } else {
                    updateAttachmentLabel()
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
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
            viewModel.selectedInvoice.value?.let { viewModel.togglePaid(it) }
        }

        viewModel.selectedInvoice.observe(viewLifecycleOwner) { invoice ->
            binding.btnMarkPaid.text = if (invoice?.paidAt != null) {
                getString(R.string.mark_unpaid)
            } else {
                getString(R.string.mark_paid)
            }
        }
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
            
            printManager.print("Rechnung_$invoiceId", printAdapter, attributes)
            
        } catch (e: Exception) {
            Snackbar.make(binding.root, R.string.error_pdf, Snackbar.LENGTH_LONG).show()
        }
    }
    
    private fun sharePdf() {
        val invoice = viewModel.parsedInvoice.value ?: return
        val invoiceId = invoice.invoice.id
        
        try {
            // Create a temporary HTML file
            val htmlFile = File(requireContext().cacheDir, "Rechnung_$invoiceId.html")
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
                putExtra(Intent.EXTRA_SUBJECT, "Rechnung $invoiceId")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            val chooserIntent = Intent.createChooser(shareIntent, getString(R.string.share))
            startActivity(chooserIntent)
            
        } catch (e: Exception) {
            Snackbar.make(binding.root, "Fehler beim Teilen: ${e.message}", Snackbar.LENGTH_LONG).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

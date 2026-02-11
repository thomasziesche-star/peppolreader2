package com.example.peppolreaderfree.ui

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
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.example.peppolreaderfree.R
import com.example.peppolreaderfree.databinding.FragmentInvoiceDetailBinding
import com.example.peppolreaderfree.pdf.PdfGenerator
import com.google.android.material.snackbar.Snackbar
import java.io.File
import java.io.FileOutputStream

class InvoiceDetailFragment : Fragment() {

    private var _binding: FragmentInvoiceDetailBinding? = null
    private val binding get() = _binding!!
    
    private val viewModel: InvoiceViewModel by activityViewModels()
    private lateinit var pdfGenerator: PdfGenerator
    private var currentHtml: String = ""

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
    
    private fun setupButtons() {
        binding.btnDownloadPdf.setOnClickListener {
            savePdf()
        }
        
        binding.btnShare.setOnClickListener {
            sharePdf()
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
                
                // Show attachment label if present
                if (!invoice.embeddedDocument?.filename.isNullOrEmpty()) {
                    binding.detailAttachmentLabel.text = "attachment: ${invoice.embeddedDocument?.filename}"
                    binding.detailAttachmentLabel.visibility = View.VISIBLE
                    binding.detailAttachmentLabel.setOnClickListener {
                        viewModel.selectedInvoice.value?.let { selectedInvoice ->
                            viewModel.openAttachment(selectedInvoice, requireContext())
                        }
                    }
                } else {
                    binding.detailAttachmentLabel.visibility = View.GONE
                }
            }
        }
        
        viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            binding.loadingIndicator.visibility = if (isLoading) View.VISIBLE else View.GONE
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
                type = "text/html"
                putExtra(Intent.EXTRA_STREAM, contentUri)
                putExtra(Intent.EXTRA_SUBJECT, "Rechnung $invoiceId")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            
            // Alternatively, use Print for PDF sharing
            val printIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Rechnung $invoiceId")
                putExtra(Intent.EXTRA_TEXT, "Rechnung $invoiceId von ${invoice.supplier.name}")
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

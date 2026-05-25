package com.ziesche.peppolreader.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.ziesche.peppolreader.data.AppDatabase
import java.io.File
import java.io.FileOutputStream
import android.util.Base64
import androidx.core.content.FileProvider
import android.content.Intent
import android.content.Context
import com.ziesche.peppolreader.data.model.Invoice
import com.ziesche.peppolreader.data.model.ParsedInvoice
import com.ziesche.peppolreader.parser.CiiParser
import com.ziesche.peppolreader.parser.InvoiceFormat
import com.ziesche.peppolreader.parser.PeppolParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class InvoiceViewModel(application: Application) : AndroidViewModel(application) {
    
    private val database = AppDatabase.getDatabase(application)
    private val invoiceDao = database.invoiceDao()
    
    // All invoices as LiveData
    // All invoices from DB
    private val allInvoicesFromDb: LiveData<List<Invoice>> = invoiceDao.getAllInvoicesLiveData()
    
    // Search Query
    private val _searchQuery = MutableLiveData("")

    // Chart Data
    val monthlyExpenses = invoiceDao.getMonthlyExpenses()
    val topSuppliers = invoiceDao.getTopSuppliers()
    
    // Filtered Invoices (using MediatorLiveData to combine DB results and Search Query)
    val allInvoices = androidx.lifecycle.MediatorLiveData<List<Invoice>>().apply {
        addSource(allInvoicesFromDb) { invoices ->
            value = filterInvoices(invoices, _searchQuery.value)
        }
        addSource(_searchQuery) { query ->
            value = filterInvoices(allInvoicesFromDb.value, query)
        }
    }
    
    private fun filterInvoices(invoices: List<Invoice>?, query: String?): List<Invoice> {
        val list = invoices ?: emptyList()
        if (query.isNullOrBlank()) return list
        
        val q = query.trim().lowercase()
        return list.filter { 
            it.supplierName.lowercase().contains(q) || 
            it.invoiceId.lowercase().contains(q) 
        }
    }
    
    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }
    
    // Currently selected invoice
    private val _selectedInvoice = MutableLiveData<Invoice?>()
    val selectedInvoice: LiveData<Invoice?> = _selectedInvoice
    
    // Parsed invoice data
    private val _parsedInvoice = MutableLiveData<ParsedInvoice?>()
    val parsedInvoice: LiveData<ParsedInvoice?> = _parsedInvoice
    
    // Loading state
    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading
    
    // Error state
    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> = _error
    
    // Success message
    private val _message = MutableLiveData<String?>()
    val message: LiveData<String?> = _message
    
    /**
     * One file selected for import. [originalPdfBytes] is set when the file was a
     * ZUGFeRD / Factur-X hybrid PDF — those bytes are stored as the human-readable
     * attachment alongside the parsed invoice data.
     */
    data class ImportItem(
        val fileName: String,
        val xmlContent: String,
        val originalPdfBytes: ByteArray? = null
    )

    /**
     * Import multiple invoices
     */
    fun importInvoices(files: List<ImportItem>) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null

            var successCount = 0
            var duplicateCount = 0
            var errorCount = 0

            val total = files.size

            // If only one file, we want compatible behavior (open it)
            // If multiple, just summary.

            val results = withContext(Dispatchers.IO) {
                files.map { item -> processSingleInvoice(item) }
            }
            
            var lastSuccessInvoice: Invoice? = null
            var lastDuplicateInvoice: Invoice? = null

            results.forEach { result ->
                when (result) {
                    is ImportResult.Success -> {
                        successCount++
                        lastSuccessInvoice = result.invoice
                    }
                    is ImportResult.Duplicate -> {
                        duplicateCount++
                        lastDuplicateInvoice = result.invoice
                    }
                    is ImportResult.Error -> {
                        errorCount++
                    }
                }
            }
            
            _isLoading.value = false
            
            if (total == 1) {
                // Single file behavior
                if (successCount == 1) {
                    selectInvoice(lastSuccessInvoice!!)
                    _message.value = "Rechnung gespeichert"
                } else if (duplicateCount == 1) {
                    selectInvoice(lastDuplicateInvoice!!)
                    _message.value = "Rechnung ist bereits vorhanden. Öffne Details..."
                } else {
                    _error.value = "Fehler beim Import"
                }
            } else {
                // Batch summary
                val sb = StringBuilder()
                sb.append("$successCount von $total Rechnungen importiert.")
                if (duplicateCount > 0) {
                    sb.append(" $duplicateCount Duplikate.")
                }
                if (errorCount > 0) {
                    sb.append(" $errorCount Fehler.")
                }
                _message.value = sb.toString()
                
                // If we imported at least one, maybe we don't select anything or just stay on list?
                // Staying on list is better for batch.
            }
        }
    }

    private sealed class ImportResult {
        data class Success(val invoice: Invoice) : ImportResult()
        data class Duplicate(val invoice: Invoice) : ImportResult()
        data class Error(val message: String) : ImportResult()
    }

    private suspend fun processSingleInvoice(item: ImportItem): ImportResult {
        val xmlContent = item.xmlContent
        val fileName = item.fileName
        return try {
            val parsed = parseByFormat(xmlContent)
                ?: return ImportResult.Error("Unknown invoice format")

            // Check for duplicate
            val existing = invoiceDao.getInvoiceByNumberAndSupplier(parsed.invoice.id, parsed.supplier.name)
            if (existing != null) {
                return ImportResult.Duplicate(existing)
            }

            // Prefer an XML-embedded PDF (UBL AdditionalDocumentReference). When the source
            // was a ZUGFeRD/Factur-X hybrid PDF and no XML-embedded PDF is present, store the
            // original PDF itself as the readable attachment.
            val xmlEmbeddedPath = saveEmbeddedDocument(parsed, getApplication())
            val attachmentFilename: String?
            val attachmentPath: String?
            if (xmlEmbeddedPath != null) {
                attachmentFilename = parsed.embeddedDocument?.filename
                attachmentPath = xmlEmbeddedPath
            } else if (item.originalPdfBytes != null) {
                val saved = saveOriginalPdf(
                    fileName = fileName,
                    invoiceId = parsed.invoice.id,
                    pdfBytes = item.originalPdfBytes,
                    context = getApplication()
                )
                attachmentFilename = saved?.first
                attachmentPath = saved?.second
            } else {
                attachmentFilename = null
                attachmentPath = null
            }

            // Create Invoice entity
            val invoice = Invoice(
                invoiceId = parsed.invoice.id,
                issueDate = parsed.invoice.issueDate,
                dueDate = parsed.invoice.dueDate,
                currency = parsed.invoice.currency,
                supplierName = parsed.supplier.name,
                supplierStreet = parsed.supplier.street,
                supplierCity = parsed.supplier.city,
                supplierZip = parsed.supplier.zip,
                supplierCountry = parsed.supplier.country,
                supplierTaxId = parsed.supplier.taxId,
                customerName = parsed.customer.name,
                customerStreet = parsed.customer.street,
                customerCity = parsed.customer.city,
                customerZip = parsed.customer.zip,
                customerCountry = parsed.customer.country,
                customerTaxId = parsed.customer.taxId,
                netAmount = parsed.totals.netAmount,
                taxAmount = parsed.totals.taxAmount,
                grossAmount = parsed.totals.grossAmount,
                payableAmount = parsed.totals.payableAmount,
                xmlContent = xmlContent,
                fileName = fileName,
                embeddedDocumentFilename = attachmentFilename,
                embeddedDocumentPath = attachmentPath,
                documentTypeCode = parsed.documentTypeCode,
                formatLabel = parsed.formatLabel
            )

            invoiceDao.insertInvoice(invoice)
            ImportResult.Success(invoice)

        } catch (e: Exception) {
            ImportResult.Error(e.message ?: "Unknown error")
        }
    }

    /**
     * Persists the bytes of an original ZUGFeRD/Factur-X PDF into the app's attachments dir.
     * @return (displayedFilename, absoluteOnDiskPath) on success, null on failure.
     */
    private fun saveOriginalPdf(
        fileName: String,
        invoiceId: String,
        pdfBytes: ByteArray,
        context: Context
    ): Pair<String, String>? {
        return try {
            val attachmentsDir = File(context.filesDir, "attachments")
            if (!attachmentsDir.exists()) attachmentsDir.mkdirs()

            val displayName = if (fileName.endsWith(".pdf", ignoreCase = true)) {
                fileName
            } else {
                "$fileName.pdf"
            }
            val safeOnDisk = "original-${
                invoiceId.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            }-${System.currentTimeMillis()}.pdf"
            val outFile = File(attachmentsDir, safeOnDisk)
            FileOutputStream(outFile).use { it.write(pdfBytes) }
            displayName to outFile.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Legacy wrapper for single file if needed, but importInvoices handles it.
     * Keeping API specifically for single file calls if any, but mapping to new logic.
     */
    fun parseAndSaveInvoice(xmlContent: String, fileName: String) {
        importInvoices(listOf(ImportItem(fileName, xmlContent)))
    }
    
    /**
     * Select an invoice and parse its XML content
     */
    fun selectInvoice(invoice: Invoice) {
        _selectedInvoice.value = invoice

        viewModelScope.launch {
            _isLoading.value = true
            try {
                val parsed = withContext(Dispatchers.IO) {
                    parseByFormat(invoice.xmlContent)
                }
                if (parsed != null) {
                    _parsedInvoice.value = parsed
                } else {
                    _error.value = "Unbekanntes Rechnungsformat"
                }
            } catch (e: Exception) {
                _error.value = "Fehler beim Parsen: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Routes XML content to either [PeppolParser] (UBL) or [CiiParser] (UN/CEFACT CII)
     * based on detected namespaces. Returns null if neither format is recognized.
     */
    private fun parseByFormat(xmlContent: String): ParsedInvoice? =
        when (InvoiceFormat.detect(xmlContent)) {
            InvoiceFormat.UBL -> PeppolParser(xmlContent, getApplication()).parse()
            InvoiceFormat.CII -> CiiParser(xmlContent, getApplication()).parse()
            InvoiceFormat.UNKNOWN -> null
        }
    
    /**
     * Select an invoice by database ID
     */
    fun selectInvoiceById(id: Long) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val invoice = withContext(Dispatchers.IO) {
                    invoiceDao.getInvoiceById(id)
                }
                if (invoice != null) {
                    selectInvoice(invoice)
                } else {
                    _error.value = "Rechnung nicht gefunden"
                }
            } catch (e: Exception) {
                _error.value = "Fehler: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }
    
    /**
     * Delete an invoice
     */
    fun deleteInvoice(invoice: Invoice) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                invoiceDao.deleteInvoice(invoice)
            }
            _message.value = "Rechnung gelöscht"
        }
    }
    
    /**
     * Clear error state
     */
    fun clearError() {
        _error.value = null
    }
    
    /**
     * Clear message state
     */
    fun clearMessage() {
        _message.value = null
    }
    
    /**
     * Clear selection
     */
    fun clearSelection() {
        _selectedInvoice.value = null
        _parsedInvoice.value = null
    }

    private fun saveEmbeddedDocument(parsed: ParsedInvoice, context: Context): String? {
        val embedded = parsed.embeddedDocument ?: return null
        
        return try {
            val attachmentsDir = File(context.filesDir, "attachments")
            if (!attachmentsDir.exists()) {
                attachmentsDir.mkdirs()
            }
            
            val file = File(attachmentsDir, embedded.filename)
            val pdfBytes = Base64.decode(embedded.base64Data, Base64.DEFAULT)
            
            FileOutputStream(file).use { stream ->
                stream.write(pdfBytes)
            }
            
            file.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    
    fun openAttachment(invoice: Invoice, context: Context) {
        val path = invoice.embeddedDocumentPath ?: return
        val file = File(path)
        
        if (file.exists()) {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/pdf")
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            
            try {
                context.startActivity(intent)
            } catch (e: Exception) {
                e.printStackTrace()
                // Handle case where no PDF viewer is installed
            }
        }
    }
}

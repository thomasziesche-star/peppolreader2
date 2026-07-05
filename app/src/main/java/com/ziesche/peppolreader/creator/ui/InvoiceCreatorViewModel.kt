package com.ziesche.peppolreader.creator.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import com.ziesche.peppolreader.R
import com.ziesche.peppolreader.creator.data.CompanyProfileStore
import com.ziesche.peppolreader.creator.data.LayoutThemeStore
import com.ziesche.peppolreader.creator.data.OutgoingInvoiceRepository
import com.ziesche.peppolreader.creator.data.PdfExporter
import com.ziesche.peppolreader.creator.model.CompanyProfile
import com.ziesche.peppolreader.creator.model.CreatorAllowanceCharge
import com.ziesche.peppolreader.creator.model.CreatorArticle
import com.ziesche.peppolreader.creator.model.CreatorCustomer
import com.ziesche.peppolreader.creator.model.CreatorLine
import com.ziesche.peppolreader.creator.model.CreatorTotals
import com.ziesche.peppolreader.creator.model.OutgoingInvoice
import com.ziesche.peppolreader.creator.pdf.ZugferdPdfA3Writer
import com.ziesche.peppolreader.creator.xml.InvoiceTotalsCalculator
import com.ziesche.peppolreader.creator.xml.ZugferdXmlBuilder
import com.ziesche.peppolreader.data.AppDatabase
import com.ziesche.peppolreader.parser.CiiParser
import com.ziesche.peppolreader.parser.ZugferdExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File

/**
 * Holds the editable line-item list and the derived live totals for the draft editor, and
 * wraps draft persistence + company-profile access. Header and buyer fields are owned by the
 * fragment and read at save time.
 */
class InvoiceCreatorViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = OutgoingInvoiceRepository.from(app)
    private val profileStore = CompanyProfileStore(app)
    private val customerDao = AppDatabase.getDatabase(app).creatorCustomerDao()
    private val articleDao = AppDatabase.getDatabase(app).creatorArticleDao()

    /** Id of the draft being edited, or null for a brand-new draft. */
    var editingId: Long? = null
        private set

    /** Status of the loaded draft; generated invoices are immutable. */
    private var loadedStatus: String = OutgoingInvoice.STATUS_DRAFT

    private val _lines = MutableLiveData<List<CreatorLine>>(emptyList())
    val lines: LiveData<List<CreatorLine>> = _lines

    private val _allowances = MutableLiveData<List<CreatorAllowanceCharge>>(emptyList())
    val allowances: LiveData<List<CreatorAllowanceCharge>> = _allowances

    private val _taxMode = MutableLiveData(OutgoingInvoice.TAX_MODE_STANDARD)

    /** Live totals over lines + document-level allowances/charges + the tax mode. */
    val totals: LiveData<CreatorTotals> = MediatorLiveData<CreatorTotals>().apply {
        fun recompute() {
            value = InvoiceTotalsCalculator.calculate(
                _lines.value ?: emptyList(),
                _allowances.value ?: emptyList(),
                _taxMode.value ?: OutgoingInvoice.TAX_MODE_STANDARD
            )
        }
        addSource(_lines) { recompute() }
        addSource(_allowances) { recompute() }
        addSource(_taxMode) { recompute() }
    }

    fun profile(): CompanyProfile = profileStore.load()

    /** Customer master, alphabetically sorted. */
    suspend fun customers(): List<CreatorCustomer> = withContext(Dispatchers.IO) {
        customerDao.getAll()
    }

    /** Article/service catalog, alphabetically sorted. */
    suspend fun articles(): List<CreatorArticle> = withContext(Dispatchers.IO) {
        articleDao.getAll()
    }

    // ----- line editing -------------------------------------------------------------------

    fun setLines(lines: List<CreatorLine>) { _lines.value = lines }

    fun setAllowances(entries: List<CreatorAllowanceCharge>) { _allowances.value = entries }

    /** One of the [OutgoingInvoice] TAX_MODE_* constants; drives the live totals. */
    fun setTaxMode(mode: String) {
        if (_taxMode.value != mode) _taxMode.value = mode
    }

    fun addLine() {
        _lines.value = (_lines.value ?: emptyList()) + CreatorLine()
    }

    fun updateLine(index: Int, line: CreatorLine) {
        val current = _lines.value?.toMutableList() ?: return
        if (index in current.indices) {
            current[index] = line
            _lines.value = current
        }
    }

    fun removeLine(index: Int) {
        val current = _lines.value?.toMutableList() ?: return
        if (index in current.indices) {
            current.removeAt(index)
            _lines.value = current
        }
    }

    // ----- persistence --------------------------------------------------------------------

    /** Loads an existing draft for editing; returns it so the fragment can fill the form. */
    suspend fun loadDraft(id: Long): OutgoingInvoice? {
        val draft = repository.getById(id) ?: return null
        editingId = draft.id
        loadedStatus = draft.status
        _lines.value = draft.lines
        _allowances.value = draft.allowances
        _taxMode.value = draft.taxMode
        return draft
    }

    /** Inserts or updates [invoice], reusing [editingId] when editing. Returns the row id. */
    suspend fun saveDraft(invoice: OutgoingInvoice): Long {
        val toSave = invoice.copy(
            id = editingId ?: 0,
            lineItemsJson = CreatorLine.listToJson(_lines.value ?: emptyList()),
            allowancesJson = CreatorAllowanceCharge.listToJson(_allowances.value ?: emptyList()),
            updatedAt = System.currentTimeMillis()
        )
        val id = if (editingId != null) {
            repository.update(toSave); toSave.id
        } else {
            repository.insert(toSave)
        }
        editingId = id
        return id
    }

    sealed class GenerateResult {
        /**
         * [roundTripOk] = the produced PDF was re-read by the app's own extractor + CII parser.
         * [exportedTo] = human-readable export destination ("Downloads", folder name), or null
         * when only the internal copy exists.
         */
        data class Success(val file: File, val roundTripOk: Boolean, val exportedTo: String?) : GenerateResult()
        data class Error(val message: String) : GenerateResult()
    }

    /**
     * Generates the EN-16931 CII XML and the hybrid PDF/A-3 for [draft], persists the file and the
     * draft (status GENERATED), exports a copy to the configured location, advances the invoice
     * number sequence and stores the buyer in the customer master. Generated invoices are final:
     * re-generating an already generated draft is refused. Heavy work runs off the main thread.
     */
    suspend fun generate(draft: OutgoingInvoice): GenerateResult = withContext(Dispatchers.IO) {
        if (loadedStatus == OutgoingInvoice.STATUS_GENERATED) {
            return@withContext GenerateResult.Error(
                getApplication<Application>().getString(R.string.creator_generated_locked)
            )
        }
        runCatching {
            val app = getApplication<Application>()
            val withLines = draft.copy(
                lineItemsJson = CreatorLine.listToJson(_lines.value ?: emptyList()),
                allowancesJson = CreatorAllowanceCharge.listToJson(_allowances.value ?: emptyList())
            )
            val xml = ZugferdXmlBuilder(withLines).build()
            val profile = profileStore.load()
            val pdfBytes = ZugferdPdfA3Writer(app).write(
                withLines, xml, profile.logoPath.takeIf { it.isNotBlank() },
                LayoutThemeStore(app).load()
            )

            val dir = File(app.filesDir, "created_invoices").apply { mkdirs() }
            val safeName = withLines.invoiceNumber.replace(Regex("[^A-Za-z0-9-_]"), "_")
            val fileName = "ZUGFeRD_$safeName.pdf"
            val file = File(dir, fileName)
            file.writeBytes(pdfBytes)

            val roundTripOk = verifyRoundTrip(pdfBytes, withLines.invoiceNumber)

            saveDraft(
                withLines.copy(
                    status = OutgoingInvoice.STATUS_GENERATED,
                    generatedXml = xml,
                    pdfPath = file.absolutePath
                )
            )
            loadedStatus = OutgoingInvoice.STATUS_GENERATED

            val exportedTo = PdfExporter.export(app, pdfBytes, fileName, profile)
            advanceNumberSequence(profile, withLines.invoiceNumber)
            rememberCustomer(withLines)

            GenerateResult.Success(file, roundTripOk, exportedTo)
        }.getOrElse { e ->
            GenerateResult.Error(e.message ?: e.javaClass.simpleName)
        }
    }

    /** Counts the sequence up when the suggested number was actually used. */
    private fun advanceNumberSequence(profile: CompanyProfile, usedNumber: String) {
        if (profile.autoNumbering && usedNumber == profile.suggestedNumber()) {
            profileStore.save(profile.copy(nextNumber = profile.nextNumber + 1))
        }
    }

    /** Adds/refreshes the buyer in the customer master so they can be re-picked next time. */
    private suspend fun rememberCustomer(invoice: OutgoingInvoice) {
        if (invoice.buyerName.isBlank()) return
        runCatching {
            // The upsert REPLACEs the row on a name match, so master-data-only fields the
            // invoice does not carry (email) must be copied over from the existing entry.
            val existing = customerDao.getAll().firstOrNull { it.name == invoice.buyerName }
            customerDao.upsert(
                CreatorCustomer(
                    name = invoice.buyerName,
                    street = invoice.buyerStreet,
                    zip = invoice.buyerZip,
                    city = invoice.buyerCity,
                    country = invoice.buyerCountry,
                    vatId = invoice.buyerVatId,
                    email = existing?.email
                )
            )
        }
    }

    /** Re-reads the freshly produced PDF the same way the reader does, as a correctness check. */
    private fun verifyRoundTrip(pdfBytes: ByteArray, expectedNumber: String): Boolean = runCatching {
        val extracted = ZugferdExtractor().extract(ByteArrayInputStream(pdfBytes))
        if (extracted !is ZugferdExtractor.Result.Success) return false
        val parsed = CiiParser(extracted.xml, getApplication()).parse()
        parsed.invoice.id == expectedNumber
    }.getOrDefault(false)
}

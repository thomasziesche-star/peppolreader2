package com.ziesche.peppolreader.creator.ui

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.ziesche.peppolreader.R
import kotlinx.coroutines.launch
import java.io.File
import com.ziesche.peppolreader.creator.model.CompanyProfile
import com.ziesche.peppolreader.creator.model.CreatorLine
import com.ziesche.peppolreader.creator.model.CreatorTotals
import com.ziesche.peppolreader.creator.model.OutgoingInvoice
import com.ziesche.peppolreader.creator.xml.CreatorValidator
import com.ziesche.peppolreader.databinding.FragmentInvoiceCreatorEditBinding
import com.ziesche.peppolreader.databinding.ItemCreatorLineBinding
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Invoice draft editor. Header + buyer fields live in the views; the dynamic line list and the
 * derived totals are held in [InvoiceCreatorViewModel]. Builds a ZUGFeRD hybrid PDF/A-3 on demand.
 */
class InvoiceCreatorEditFragment : Fragment() {

    private var _binding: FragmentInvoiceCreatorEditBinding? = null
    private val binding get() = _binding!!

    private val viewModel: InvoiceCreatorViewModel by viewModels()
    private lateinit var profile: CompanyProfile

    /** Parallel to the line cards currently shown; index = position. */
    private val rows = mutableListOf<ItemCreatorLineBinding>()
    /** Guards against TextWatcher feedback while we populate fields programmatically. */
    private var building = false

    private val currencyFormat = NumberFormat.getNumberInstance().apply {
        minimumFractionDigits = 2; maximumFractionDigits = 2
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentInvoiceCreatorEditBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        profile = viewModel.profile()
        renderSellerSummary()

        // Default header values (only when not restored / not editing).
        if (binding.inputIssueDate.text.isNullOrEmpty()) {
            binding.inputIssueDate.setText(LocalDate.now().toString())
        }
        // Auto-numbering: suggest the next number on a fresh draft; the field stays editable.
        val isNewDraft = (arguments?.getLong(ARG_DRAFT_ID, -1L) ?: -1L) < 0
        if (isNewDraft && profile.autoNumbering && binding.inputInvoiceNumber.text.isNullOrEmpty()) {
            binding.inputInvoiceNumber.setText(profile.suggestedNumber())
        }
        // Default payment term: suggest due date = issue date + N days; stays editable.
        if (isNewDraft && profile.defaultPaymentDays > 0 && binding.inputDueDate.text.isNullOrEmpty()) {
            val issue = runCatching { LocalDate.parse(binding.inputIssueDate.text.str()) }
                .getOrDefault(LocalDate.now())
            binding.inputDueDate.setText(issue.plusDays(profile.defaultPaymentDays.toLong()).toString())
        }
        if (binding.toggleDocType.checkedButtonId == View.NO_ID) {
            binding.toggleDocType.check(R.id.btn_type_invoice)
        }

        binding.inputIssueDate.setOnClickListener { pickDate(binding.inputIssueDate.text?.toString()) { binding.inputIssueDate.setText(it) } }
        binding.inputDueDate.setOnClickListener { pickDate(binding.inputDueDate.text?.toString()) { binding.inputDueDate.setText(it) } }

        binding.btnAddLine.setOnClickListener {
            addRow(CreatorLine())
            syncLinesToViewModel()
        }

        binding.btnPickCustomer.setOnClickListener { showCustomerPicker() }

        binding.btnGenerate.setOnClickListener { onGenerateClicked() }

        viewModel.totals.observe(viewLifecycleOwner) { renderTotals(it) }

        val draftId = arguments?.getLong(ARG_DRAFT_ID, -1L)?.takeIf { it >= 0 }
        val freshLoad = savedInstanceState == null && viewModel.editingId == null
        if (freshLoad && draftId != null) {
            // Load an existing draft once, then build the rows from it.
            binding.btnGenerate.isEnabled = false
            viewLifecycleOwner.lifecycleScope.launch {
                val draft = viewModel.loadDraft(draftId)
                if (draft?.status == OutgoingInvoice.STATUS_GENERATED) {
                    // Generated invoices are final — never re-open them for editing.
                    android.widget.Toast.makeText(
                        requireContext(), R.string.creator_generated_locked, android.widget.Toast.LENGTH_LONG
                    ).show()
                    findNavController().popBackStack()
                    return@launch
                }
                draft?.let { bindDraft(it) }
                buildRowsFromViewModel()
                binding.btnGenerate.isEnabled = true
            }
        } else {
            buildRowsFromViewModel()
        }
    }

    /** Rebuilds the line cards from the ViewModel (one empty line for a fresh draft). */
    private fun buildRowsFromViewModel() {
        val initial = viewModel.lines.value?.takeIf { it.isNotEmpty() } ?: listOf(CreatorLine())
        building = true
        initial.forEach { addRow(it) }
        building = false
        syncLinesToViewModel()
    }

    /** Fills the header + buyer fields from a loaded draft (lines come via the ViewModel). */
    private fun bindDraft(draft: OutgoingInvoice) = with(binding) {
        inputInvoiceNumber.setText(draft.invoiceNumber)
        inputIssueDate.setText(draft.issueDate)
        inputDueDate.setText(draft.dueDate.orEmpty())
        toggleDocType.check(if (draft.documentTypeCode == "381") R.id.btn_type_credit_note else R.id.btn_type_invoice)
        inputBuyerName.setText(draft.buyerName)
        inputBuyerStreet.setText(draft.buyerStreet.orEmpty())
        inputBuyerZip.setText(draft.buyerZip.orEmpty())
        inputBuyerCity.setText(draft.buyerCity.orEmpty())
        inputBuyerCountry.setText(draft.buyerCountry.orEmpty())
        inputBuyerVatId.setText(draft.buyerVatId.orEmpty())
        inputPaymentNote.setText(draft.paymentTermsNote.orEmpty())
    }

    // ----- seller / totals rendering ------------------------------------------------------

    private fun renderSellerSummary() {
        binding.textSellerSummary.text = if (profile.isComplete()) {
            buildString {
                append(profile.name)
                append("\n")
                append(listOfNotNull(profile.zip, profile.city).joinToString(" "))
                append(" · ").append(profile.country)
            }
        } else {
            getString(R.string.creator_error_profile_required)
        }
    }

    private fun renderTotals(t: CreatorTotals) {
        binding.textTotals.text = buildString {
            append(getString(R.string.creator_net)).append("\t").append(currencyFormat.format(t.lineTotal)).append('\n')
            append(getString(R.string.creator_vat)).append("\t").append(currencyFormat.format(t.taxTotal)).append('\n')
            append(getString(R.string.creator_gross)).append("\t").append(currencyFormat.format(t.grandTotal))
        }
    }

    // ----- dynamic line rows --------------------------------------------------------------

    private fun addRow(line: CreatorLine) {
        val row = ItemCreatorLineBinding.inflate(layoutInflater, binding.containerLines, false)
        row.inputDescription.setText(line.description)
        row.inputQuantity.setText(if (line.quantity == 0.0) "" else trimNumber(line.quantity))
        row.inputUnit.setText(line.unit)
        row.inputPrice.setText(if (line.unitPrice == 0.0) "" else trimNumber(line.unitPrice))
        row.inputVat.setText(trimNumber(line.vatRate))

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { if (!building) syncLinesToViewModel() }
        }
        listOf(row.inputDescription, row.inputQuantity, row.inputUnit, row.inputPrice, row.inputVat)
            .forEach { it.addTextChangedListener(watcher) }

        row.btnRemoveLine.setOnClickListener {
            val idx = rows.indexOf(row)
            if (idx >= 0) {
                rows.removeAt(idx)
                binding.containerLines.removeView(row.root)
                syncLinesToViewModel()
            }
        }

        rows.add(row)
        binding.containerLines.addView(row.root)
    }

    /** Reads every visible row into the ViewModel so the totals stay live. */
    private fun syncLinesToViewModel() {
        val lines = rows.map { row ->
            CreatorLine(
                description = row.inputDescription.text.str(),
                quantity = parseDecimal(row.inputQuantity.text.str()),
                unit = row.inputUnit.text.str().ifBlank { "C62" },
                unitPrice = parseDecimal(row.inputPrice.text.str()),
                vatRate = parseDecimal(row.inputVat.text.str())
            )
        }
        viewModel.setLines(lines)
    }

    // ----- generate -----------------------------------------------------------------------

    private fun onGenerateClicked() {
        syncLinesToViewModel()
        val draft = buildDraft()

        // EN 16931 pre-flight: errors block generation, warnings let the user proceed knowingly.
        val issues = CreatorValidator.validate(draft, profile)
        val errors = issues.filter { it.severity == CreatorValidator.Severity.ERROR }
        if (errors.isNotEmpty()) {
            showIssuesDialog(R.string.creator_validation_errors_title, errors, proceedDraft = null)
            return
        }
        val warnings = issues.filter { it.severity == CreatorValidator.Severity.WARNING }
        if (warnings.isNotEmpty()) {
            showIssuesDialog(R.string.creator_validation_warnings_title, warnings, proceedDraft = draft)
            return
        }
        runGenerate(draft)
    }

    /**
     * Shows the pre-flight findings. When [proceedDraft] is non-null (warnings only) the dialog
     * offers a "generate anyway" action; for hard errors it is purely informational.
     */
    private fun showIssuesDialog(
        titleRes: Int,
        issues: List<CreatorValidator.Issue>,
        proceedDraft: OutgoingInvoice?
    ) {
        val message = issues.joinToString("\n") { "•  " + issueMessage(it) }
        val builder = MaterialAlertDialogBuilder(requireContext())
            .setTitle(titleRes)
            .setMessage(message)
        if (proceedDraft != null) {
            builder.setPositiveButton(R.string.creator_generate_anyway) { _, _ -> runGenerate(proceedDraft) }
            builder.setNegativeButton(android.R.string.cancel, null)
        } else {
            builder.setPositiveButton(android.R.string.ok, null)
        }
        builder.show()
    }

    /** Maps a stable validator [CreatorValidator.Code] to a localized, actionable message. */
    private fun issueMessage(issue: CreatorValidator.Issue): String {
        val line = (issue.lineIndex ?: 0) + 1
        return when (issue.code) {
            CreatorValidator.Code.SELLER_PROFILE_INCOMPLETE -> getString(R.string.creator_validate_seller_incomplete)
            CreatorValidator.Code.INVOICE_NUMBER_MISSING -> getString(R.string.creator_validate_number_missing)
            CreatorValidator.Code.ISSUE_DATE_INVALID -> getString(R.string.creator_validate_date_invalid)
            CreatorValidator.Code.BUYER_NAME_MISSING -> getString(R.string.creator_validate_buyer_missing)
            CreatorValidator.Code.NO_LINES -> getString(R.string.creator_validate_no_lines)
            CreatorValidator.Code.LINE_NO_DESCRIPTION -> getString(R.string.creator_validate_line_no_desc, line)
            CreatorValidator.Code.LINE_NON_POSITIVE_QTY -> getString(R.string.creator_validate_line_qty, line)
            CreatorValidator.Code.DUE_BEFORE_ISSUE -> getString(R.string.creator_validate_due_before_issue)
            CreatorValidator.Code.IBAN_MISSING -> getString(R.string.creator_validate_iban_missing)
            CreatorValidator.Code.IBAN_INVALID -> getString(R.string.creator_validate_iban_invalid)
        }
    }

    private fun runGenerate(draft: OutgoingInvoice) {
        binding.btnGenerate.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            when (val result = viewModel.generate(draft)) {
                is InvoiceCreatorViewModel.GenerateResult.Success -> {
                    val base = result.exportedTo
                        ?.let { getString(R.string.creator_pdf_created_at, it) }
                        ?: getString(R.string.creator_pdf_created)
                    // Surface the in-app EN 16931 self-check that already runs during generate().
                    val message = if (result.roundTripOk) {
                        base + "\n" + getString(R.string.creator_validation_passed)
                    } else base
                    Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG)
                        .setAction(R.string.creator_share) { sharePdf(result.file) }
                        .show()
                    // The invoice is final now — no further edits or re-generation.
                    binding.btnGenerate.isEnabled = false
                }
                is InvoiceCreatorViewModel.GenerateResult.Error -> {
                    Snackbar.make(
                        binding.root,
                        getString(R.string.creator_error_generate, result.message),
                        Snackbar.LENGTH_LONG
                    ).show()
                    binding.btnGenerate.isEnabled = true
                }
            }
        }
    }

    /** Lets the user pick a buyer from the customer master and fills the buyer fields. */
    private fun showCustomerPicker() {
        viewLifecycleOwner.lifecycleScope.launch {
            val customers = viewModel.customers()
            if (customers.isEmpty()) {
                Snackbar.make(binding.root, R.string.creator_no_customers, Snackbar.LENGTH_LONG).show()
                return@launch
            }
            val labels = customers.map { c ->
                listOfNotNull(c.name, listOfNotNull(c.zip, c.city).joinToString(" ").takeIf { it.isNotBlank() })
                    .joinToString(" · ")
            }.toTypedArray()
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.creator_pick_customer)
                .setItems(labels) { _, which ->
                    val c = customers[which]
                    binding.inputBuyerName.setText(c.name)
                    binding.inputBuyerStreet.setText(c.street.orEmpty())
                    binding.inputBuyerZip.setText(c.zip.orEmpty())
                    binding.inputBuyerCity.setText(c.city.orEmpty())
                    binding.inputBuyerCountry.setText(c.country.orEmpty())
                    binding.inputBuyerVatId.setText(c.vatId.orEmpty())
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun sharePdf(file: File) {
        val uri = FileProvider.getUriForFile(
            requireContext(),
            "${requireContext().packageName}.fileprovider",
            file
        )
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(share, getString(R.string.creator_share)))
    }

    /** Assembles the [OutgoingInvoice] from the form + the seller profile snapshot. */
    private fun buildDraft(): OutgoingInvoice {
        val docType = if (binding.toggleDocType.checkedButtonId == R.id.btn_type_credit_note) "381" else "380"
        return OutgoingInvoice(
            invoiceNumber = binding.inputInvoiceNumber.text.str(),
            issueDate = binding.inputIssueDate.text.str(),
            dueDate = binding.inputDueDate.text.str().takeIf { it.isNotBlank() },
            documentTypeCode = docType,
            currency = "EUR",
            sellerName = profile.name,
            sellerStreet = profile.street,
            sellerZip = profile.zip,
            sellerCity = profile.city,
            sellerCountry = profile.country,
            sellerVatId = profile.vatId.ifBlank { null },
            sellerTaxNumber = profile.taxNumber.ifBlank { null },
            sellerIban = profile.iban.ifBlank { null },
            sellerBic = profile.bic.ifBlank { null },
            sellerEmail = profile.email.ifBlank { null },
            sellerPhone = profile.phone.ifBlank { null },
            buyerName = binding.inputBuyerName.text.str(),
            buyerStreet = binding.inputBuyerStreet.text.str().ifBlank { null },
            buyerZip = binding.inputBuyerZip.text.str().ifBlank { null },
            buyerCity = binding.inputBuyerCity.text.str().ifBlank { null },
            buyerCountry = binding.inputBuyerCountry.text.str().ifBlank { null }?.uppercase(),
            buyerVatId = binding.inputBuyerVatId.text.str().ifBlank { null },
            lineItemsJson = CreatorLine.listToJson(viewModel.lines.value.orEmpty()),
            paymentTermsNote = binding.inputPaymentNote.text.str().ifBlank { null }
        )
    }

    // ----- helpers ------------------------------------------------------------------------

    private fun pickDate(current: String?, onPicked: (String) -> Unit) {
        val selection = current
            ?.let { runCatching { LocalDate.parse(it).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }.getOrNull() }
            ?: MaterialDatePicker.todayInUtcMilliseconds()
        val picker = MaterialDatePicker.Builder.datePicker()
            .setSelection(selection)
            .build()
        picker.addOnPositiveButtonClickListener { millis ->
            val date = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
            onPicked(date.toString())
        }
        picker.show(childFragmentManager, "date_picker")
    }

    private fun trimNumber(value: Double): String =
        if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()

    private fun parseDecimal(text: String): Double =
        text.trim().replace(',', '.').toDoubleOrNull() ?: 0.0

    private fun CharSequence?.str(): String = this?.toString()?.trim().orEmpty()

    override fun onDestroyView() {
        super.onDestroyView()
        rows.clear()
        _binding = null
    }

    companion object {
        /** Navigation argument: id of an existing draft to edit (absent = new draft). */
        const val ARG_DRAFT_ID = "draftId"
    }
}

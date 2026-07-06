package com.ziesche.peppolreader.creator.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.print.PrintManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.FileProvider
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.ziesche.peppolreader.R
import com.ziesche.peppolreader.creator.data.CompanyProfileStore
import com.ziesche.peppolreader.creator.data.OutgoingInvoiceRepository
import com.ziesche.peppolreader.creator.dunning.DunningTextBuilder
import com.ziesche.peppolreader.creator.model.OutgoingInvoice
import com.ziesche.peppolreader.creator.pdf.PdfPrintDocumentAdapter
import com.ziesche.peppolreader.creator.xml.InvoiceTotalsCalculator
import com.ziesche.peppolreader.data.AppDatabase
import com.ziesche.peppolreader.databinding.DialogRecordPaymentBinding
import com.ziesche.peppolreader.databinding.FragmentInvoiceCreatorListBinding
import kotlinx.coroutines.launch
import java.io.File
import java.text.NumberFormat
import java.time.LocalDate
import java.util.Currency
import java.util.Locale

/**
 * Entry point of the separate "Invoice Creator" mode. Lists saved drafts and offers actions to
 * create a new draft or edit the company (sender) profile.
 *
 * Drafts open the editor; **generated invoices are final** and instead offer view/share of the
 * produced ZUGFeRD PDF.
 *
 * The whole mode is gated behind [com.ziesche.peppolreader.BuildConfig.ENABLE_INVOICE_CREATOR];
 * the reader is unaffected when the flag is off.
 */
class InvoiceCreatorListFragment : Fragment() {

    private var _binding: FragmentInvoiceCreatorListBinding? = null
    private val binding get() = _binding!!

    private lateinit var repository: OutgoingInvoiceRepository

    /** Unfiltered drafts from the database; [render] applies the current search + type filter. */
    private var allDrafts: List<OutgoingInvoice> = emptyList()
    private var query: String = ""

    private enum class TypeFilter { ALL, INVOICES, QUOTES }
    private var typeFilter: TypeFilter = TypeFilter.ALL

    private val adapter by lazy {
        OutgoingInvoiceAdapter(
            onClick = { draft ->
                if (draft.status == OutgoingInvoice.STATUS_GENERATED) {
                    showGeneratedOptions(draft)
                } else {
                    openDraft(draft.id)
                }
            },
            onDelete = { confirmDelete(it) }
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentInvoiceCreatorListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        repository = OutgoingInvoiceRepository.from(requireContext())

        binding.draftsRecycler.layoutManager = LinearLayoutManager(requireContext())
        binding.draftsRecycler.adapter = adapter

        binding.btnCustomers.setOnClickListener {
            findNavController().navigate(R.id.action_creatorList_to_customers)
        }
        binding.btnArticles.setOnClickListener {
            findNavController().navigate(R.id.action_creatorList_to_articles)
        }
        binding.btnCompanyProfile.setOnClickListener {
            findNavController().navigate(R.id.action_creatorList_to_companyProfile)
        }
        binding.btnNewDraft.setOnClickListener { openDraft(null) }

        binding.toggleTypeFilter.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            typeFilter = when (checkedId) {
                R.id.btn_filter_invoices -> TypeFilter.INVOICES
                R.id.btn_filter_quotes -> TypeFilter.QUOTES
                else -> TypeFilter.ALL
            }
            render()
        }

        binding.searchView.setOnQueryTextListener(object : androidx.appcompat.widget.SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(text: String?): Boolean = true
            override fun onQueryTextChange(text: String?): Boolean {
                query = text.orEmpty().trim()
                render()
                return true
            }
        })

        repository.allLiveData().observe(viewLifecycleOwner) { drafts ->
            allDrafts = drafts
            render()
            reloadPaidSums()
        }
    }

    /** Applies the type filter + quick-search query (number, buyer, dates) and updates the list. */
    private fun render() {
        val byType = when (typeFilter) {
            TypeFilter.INVOICES -> allDrafts.filter { !it.isQuote }
            TypeFilter.QUOTES -> allDrafts.filter { it.isQuote }
            TypeFilter.ALL -> allDrafts
        }
        val filtered = if (query.isBlank()) byType else byType.filter { d ->
            listOfNotNull(d.invoiceNumber, d.buyerName, d.issueDate, d.dueDate)
                .any { it.contains(query, ignoreCase = true) }
        }
        adapter.submitList(filtered)
        val empty = filtered.isEmpty()
        binding.emptyState.visibility = if (empty) View.VISIBLE else View.GONE
        binding.draftsRecycler.visibility = if (empty) View.GONE else View.VISIBLE
    }

    private fun openDraft(id: Long?) {
        val args = if (id != null) bundleOf(InvoiceCreatorEditFragment.ARG_DRAFT_ID to id) else null
        findNavController().navigate(R.id.action_creatorList_to_creatorEdit, args)
    }

    // ----- generated invoices: view / share ------------------------------------------------

    private fun showGeneratedOptions(draft: OutgoingInvoice) {
        if (draft.isQuote) {
            showGeneratedQuoteOptions(draft)
            return
        }
        val isPaid = draft.paidAt != null
        val items = buildList {
            add(getString(R.string.creator_view_pdf))
            add(getString(R.string.creator_share))
            add(getString(R.string.creator_print))
            add(getString(R.string.creator_duplicate))
            add(getString(R.string.creator_payments))
            if (!isPaid) add(getString(R.string.creator_option_send_dunning))
        }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(draft.invoiceNumber.ifBlank { getString(R.string.creator_status_generated) })
            .setItems(items) { _, which ->
                when (which) {
                    0 -> viewPdf(draft)
                    1 -> sharePdf(draft)
                    2 -> printPdf(draft)
                    3 -> duplicateAsDraft(draft)
                    4 -> showPayments(draft)
                    5 -> startDunning(draft)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Generated quote actions: view / share the PDF, or turn the quote into an invoice draft. */
    private fun showGeneratedQuoteOptions(quote: OutgoingInvoice) {
        val items = arrayOf(
            getString(R.string.creator_view_pdf),
            getString(R.string.creator_share),
            getString(R.string.creator_print),
            getString(R.string.creator_convert_to_invoice)
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(quote.invoiceNumber.ifBlank { getString(R.string.creator_doc_type_quote) })
            .setItems(items) { _, which ->
                when (which) {
                    0 -> viewPdf(quote)
                    1 -> sharePdf(quote)
                    2 -> printPdf(quote)
                    3 -> convertToInvoice(quote)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Turns a (final) quote into a fresh, editable invoice draft: same buyer and line items, but a
     * new invoice number from the invoice sequence, document type invoice and today's dates. The
     * original quote is untouched.
     */
    private fun convertToInvoice(quote: OutgoingInvoice) {
        viewLifecycleOwner.lifecycleScope.launch {
            val profile = CompanyProfileStore(requireContext()).load()
            val today = LocalDate.now()
            val now = System.currentTimeMillis()
            val invoice = quote.copy(
                id = 0,
                documentTypeCode = OutgoingInvoice.DOC_TYPE_INVOICE,
                invoiceNumber = if (profile.autoNumbering) profile.suggestedNumber() else "",
                issueDate = today.toString(),
                dueDate = if (profile.defaultPaymentDays > 0) {
                    today.plusDays(profile.defaultPaymentDays.toLong()).toString()
                } else null,
                status = OutgoingInvoice.STATUS_DRAFT,
                generatedXml = null,
                pdfPath = null,
                paidAt = null,
                dunningLevel = 0,
                lastDunningAt = null,
                lastOverdueNotifiedAt = null,
                createdAt = now,
                updatedAt = now
            )
            val newId = repository.insert(invoice)
            openDraft(newId)
        }
    }

    // ----- partial payments -----------------------------------------------------------------

    /** Shows the payment ledger: recorded payments, remaining amount, add/delete + mark fully paid. */
    private fun showPayments(invoice: OutgoingInvoice) {
        viewLifecycleOwner.lifecycleScope.launch {
            val payments = repository.paymentsFor(invoice.id)
            val grand = InvoiceTotalsCalculator.calculate(invoice).grandTotal
            val paid = payments.sumOf { it.amount }
            val remaining = (grand - paid).coerceAtLeast(0.0)
            val nf = currencyFormat(invoice.currency)

            val labels = payments.map { p ->
                buildString {
                    append(nf.format(p.amount)).append(" · ").append(isoDate(p.paidAtMs))
                    p.note?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
                }
            }.toTypedArray()

            val builder = MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.creator_payments_title, nf.format(remaining)))
            if (labels.isNotEmpty()) {
                builder.setItems(labels) { _, which -> confirmDeletePayment(invoice, payments[which]) }
            } else {
                builder.setMessage(getString(R.string.creator_payments_none, nf.format(grand)))
            }
            builder.setPositiveButton(R.string.creator_payment_record) { _, _ ->
                showRecordPayment(invoice, remaining)
            }
            builder.setNegativeButton(android.R.string.cancel, null)
            builder.show()
        }
    }

    /** Amount + note entry for a single incoming payment; amount pre-filled with the remainder. */
    private fun showRecordPayment(invoice: OutgoingInvoice, remaining: Double) {
        val dialogBinding = DialogRecordPaymentBinding.inflate(layoutInflater)
        if (remaining > 0.0) {
            dialogBinding.inputPaymentAmount.setText(String.format(Locale.US, "%.2f", remaining))
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.creator_payment_record)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.creator_save) { _, _ ->
                val amount = dialogBinding.inputPaymentAmount.text?.toString()
                    ?.trim()?.replace(',', '.')?.toDoubleOrNull() ?: 0.0
                if (amount <= 0.0) {
                    Snackbar.make(binding.root, R.string.creator_error_invalid, Snackbar.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                val note = dialogBinding.inputPaymentNote.text?.toString()?.trim()?.ifBlank { null }
                viewLifecycleOwner.lifecycleScope.launch {
                    repository.recordPayment(invoice, amount, System.currentTimeMillis(), note)
                    reloadPaidSums()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDeletePayment(invoice: OutgoingInvoice, payment: com.ziesche.peppolreader.creator.model.OutgoingInvoicePayment) {
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(R.string.creator_payment_delete_confirm)
            .setPositiveButton(R.string.creator_delete) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    repository.deletePayment(invoice, payment)
                    reloadPaidSums()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Refreshes the per-invoice paid totals feeding the list's "partially paid" chips. */
    private fun reloadPaidSums() {
        viewLifecycleOwner.lifecycleScope.launch {
            adapter.setPaidSums(repository.paidSums())
        }
    }

    private fun currencyFormat(currency: String): NumberFormat =
        NumberFormat.getCurrencyInstance(Locale.getDefault()).apply {
            runCatching { this.currency = Currency.getInstance(currency) }
        }

    private fun isoDate(ms: Long): String =
        java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(java.util.Date(ms))

    // ----- dunning --------------------------------------------------------------------------

    /**
     * Prepares a dunning email: looks up the customer's current email address, shows a
     * confirmation with recipient + new deadline, then opens the mail client with subject,
     * escalating body text and the invoice PDF attached. The dunning level is recorded when
     * the intent launches — the actual send can't be observed, and a mistaken bump is
     * self-healing (capped at 3, user can simply send again).
     */
    private fun startDunning(draft: OutgoingInvoice) {
        val file = pdfFile(draft) ?: run { showMissing(); return }
        viewLifecycleOwner.lifecycleScope.launch {
            val email = AppDatabase.getDatabase(requireContext())
                .creatorCustomerDao().getByName(draft.buyerName)?.email

            val amount = NumberFormat.getCurrencyInstance(Locale.getDefault()).apply {
                runCatching { currency = Currency.getInstance(draft.currency) }
            }.format(InvoiceTotalsCalculator.calculate(draft).grandTotal)
            val mail = DunningTextBuilder.build(resources, draft, amount)

            val recipientInfo = email
                ?.let { getString(R.string.dunning_confirm_message, it, mail.newDeadlineIso) }
                ?: (getString(R.string.dunning_no_email_warning) + "\n\n" +
                    getString(R.string.dunning_confirm_message, draft.buyerName, mail.newDeadlineIso))

            MaterialAlertDialogBuilder(requireContext())
                .setTitle(
                    getString(
                        R.string.dunning_confirm_title,
                        getString(DunningTextBuilder.levelLabelRes(mail.level))
                    )
                )
                .setMessage(recipientInfo)
                .setPositiveButton(R.string.creator_option_send_dunning) { _, _ ->
                    launchDunningIntent(draft, file, email, mail)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun launchDunningIntent(
        draft: OutgoingInvoice,
        file: File,
        email: String?,
        mail: DunningTextBuilder.DunningMail
    ) {
        fun intentWithType(type: String) = Intent(Intent.ACTION_SEND).apply {
            this.type = type
            email?.let { putExtra(Intent.EXTRA_EMAIL, arrayOf(it)) }
            putExtra(Intent.EXTRA_SUBJECT, mail.subject)
            putExtra(Intent.EXTRA_TEXT, mail.body)
            putExtra(Intent.EXTRA_STREAM, pdfUri(file))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val launched = runCatching {
            // message/rfc822 filters the chooser to mail apps and carries text + attachment.
            startActivity(Intent.createChooser(
                intentWithType("message/rfc822"), getString(R.string.creator_option_send_dunning)
            ))
        }.recoverCatching {
            startActivity(Intent.createChooser(
                intentWithType("application/pdf"), getString(R.string.creator_option_send_dunning)
            ))
        }.isSuccess
        if (launched) {
            viewLifecycleOwner.lifecycleScope.launch {
                repository.recordDunningSent(draft.id, System.currentTimeMillis())
            }
        } else {
            Snackbar.make(binding.root, R.string.dunning_no_mail_app, Snackbar.LENGTH_LONG).show()
        }
    }

    /**
     * Recurring-invoice helper: clones a (final) generated invoice into a fresh editable draft —
     * same buyer, lines and payment note, but a new number from the sequence and today's dates.
     * The original stays untouched.
     */
    private fun duplicateAsDraft(source: OutgoingInvoice) {
        viewLifecycleOwner.lifecycleScope.launch {
            val profile = CompanyProfileStore(requireContext()).load()
            val today = LocalDate.now()
            val now = System.currentTimeMillis()
            val copy = source.copy(
                id = 0,
                invoiceNumber = if (profile.autoNumbering) profile.suggestedNumber() else "",
                issueDate = today.toString(),
                dueDate = if (profile.defaultPaymentDays > 0) {
                    today.plusDays(profile.defaultPaymentDays.toLong()).toString()
                } else null,
                status = OutgoingInvoice.STATUS_DRAFT,
                generatedXml = null,
                pdfPath = null,
                // Payment/dunning history belongs to the source invoice, not the clone.
                paidAt = null,
                dunningLevel = 0,
                lastDunningAt = null,
                lastOverdueNotifiedAt = null,
                createdAt = now,
                updatedAt = now
            )
            val newId = repository.insert(copy)
            openDraft(newId)
        }
    }

    private fun pdfFile(draft: OutgoingInvoice): File? =
        draft.pdfPath?.let { File(it) }?.takeIf { it.exists() }

    private fun pdfUri(file: File) = FileProvider.getUriForFile(
        requireContext(), "${requireContext().packageName}.fileprovider", file
    )

    private fun viewPdf(draft: OutgoingInvoice) {
        val file = pdfFile(draft) ?: run { showMissing(); return }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(pdfUri(file), "application/pdf")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Snackbar.make(binding.root, R.string.creator_view_error, Snackbar.LENGTH_LONG).show()
        }
    }

    private fun printPdf(draft: OutgoingInvoice) {
        val file = pdfFile(draft) ?: run { showMissing(); return }
        val printManager = requireContext().getSystemService(Context.PRINT_SERVICE) as PrintManager
        val jobName = getString(R.string.app_name) + " " + draft.invoiceNumber
        printManager.print(jobName, PdfPrintDocumentAdapter(file), null)
    }

    private fun sharePdf(draft: OutgoingInvoice) {
        val file = pdfFile(draft) ?: run { showMissing(); return }
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, pdfUri(file))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(share, getString(R.string.creator_share)))
    }

    private fun showMissing() {
        Snackbar.make(binding.root, R.string.creator_pdf_missing, Snackbar.LENGTH_LONG).show()
    }

    private fun confirmDelete(draft: OutgoingInvoice) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(draft.invoiceNumber.ifBlank { getString(R.string.creator_new_draft) })
            .setMessage(R.string.creator_delete_confirm)
            .setPositiveButton(R.string.creator_delete) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch { repository.delete(draft) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding.draftsRecycler.adapter = null
        _binding = null
    }
}

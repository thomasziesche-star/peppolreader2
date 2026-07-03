package com.ziesche.peppolreader.creator.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
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
import com.ziesche.peppolreader.creator.xml.InvoiceTotalsCalculator
import com.ziesche.peppolreader.data.AppDatabase
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

    /** Unfiltered drafts from the database; [render] applies the current search query. */
    private var allDrafts: List<OutgoingInvoice> = emptyList()
    private var query: String = ""

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
        }
    }

    /** Applies the quick-search query (number, buyer, dates) and updates the list/empty state. */
    private fun render() {
        val filtered = if (query.isBlank()) allDrafts else allDrafts.filter { d ->
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
        val isPaid = draft.paidAt != null
        val items = buildList {
            add(getString(R.string.creator_view_pdf))
            add(getString(R.string.creator_share))
            add(getString(R.string.creator_duplicate))
            add(getString(if (isPaid) R.string.creator_option_mark_unpaid else R.string.creator_option_mark_paid))
            if (!isPaid) add(getString(R.string.creator_option_send_dunning))
        }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(draft.invoiceNumber.ifBlank { getString(R.string.creator_status_generated) })
            .setItems(items) { _, which ->
                when (which) {
                    0 -> viewPdf(draft)
                    1 -> sharePdf(draft)
                    2 -> duplicateAsDraft(draft)
                    3 -> togglePaid(draft)
                    4 -> startDunning(draft)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun togglePaid(draft: OutgoingInvoice) {
        viewLifecycleOwner.lifecycleScope.launch {
            repository.setPaid(draft.id, if (draft.paidAt == null) System.currentTimeMillis() else null)
        }
    }

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
            }.format(InvoiceTotalsCalculator.calculate(draft.lines).grandTotal)
            val mail = DunningTextBuilder.build(resources, draft, amount)

            val recipientInfo = email
                ?.let { getString(R.string.dunning_confirm_message, it, mail.newDeadlineIso) }
                ?: (getString(R.string.dunning_no_email_warning) + "\n\n" +
                    getString(R.string.dunning_confirm_message, draft.buyerName, mail.newDeadlineIso))

            MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.dunning_confirm_title, mail.level))
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

package com.ziesche.peppolreader.ui

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.navigation.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.ziesche.peppolreader.R
import com.ziesche.peppolreader.ai.AiCredential
import com.ziesche.peppolreader.ai.AiCredentialStore
import com.ziesche.peppolreader.databinding.BottomSheetAiChatBinding

/**
 * Chat sheet for asking AI questions about the currently selected invoice. The raw XML is the
 * only context sent (see [AiChatViewModel]). Guards for the "no default configured" case; the
 * data-sharing notice is shown once when a credential is saved (see [AiCredentialBottomSheet]).
 *
 * Uses a dedicated bottom-sheet theme (adjustResize, edge-to-edge off) so the input row stays
 * visible above the on-screen keyboard.
 */
class AiChatBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetAiChatBinding? = null
    private val binding get() = _binding!!

    private val invoiceViewModel: InvoiceViewModel by activityViewModels()
    private val chatViewModel: AiChatViewModel by viewModels()
    private val adapter = AiChatAdapter()

    override fun getTheme(): Int = R.style.Theme_Peppol_BottomSheet

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState) as BottomSheetDialog
        dialog.behavior.apply {
            skipCollapsed = true
            state = BottomSheetBehavior.STATE_EXPANDED
        }
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetAiChatBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.chatRecycler.layoutManager =
            LinearLayoutManager(requireContext()).apply { stackFromEnd = true }
        binding.chatRecycler.adapter = adapter

        val credential = AiCredentialStore(requireContext()).getDefault()
        if (credential == null) {
            showNoDefaultState()
            return
        }

        val xml = invoiceViewModel.selectedInvoice.value?.xmlContent.orEmpty()
        startChat(credential, xml)
    }

    private fun showNoDefaultState() {
        binding.chatRecycler.visibility = View.GONE
        binding.stateContainer.visibility = View.VISIBLE
        binding.stateMessage.text = getString(R.string.ai_no_default)
        binding.btnStateAction.text = getString(R.string.ai_go_to_settings)
        binding.btnStateAction.setOnClickListener {
            dismiss()
            requireActivity()
                .findNavController(R.id.nav_host_fragment_content_main)
                .navigate(R.id.aiSettingsFragment)
        }
    }

    private fun startChat(credential: AiCredential, xml: String) {
        chatViewModel.configure(credential, xml)

        binding.stateContainer.visibility = View.GONE
        binding.chatRecycler.visibility = View.VISIBLE
        binding.inputRow.visibility = View.VISIBLE
        binding.suggestionsScroll.visibility = View.VISIBLE
        binding.providerLabel.visibility = View.VISIBLE
        binding.providerLabel.text =
            getString(R.string.ai_chat_using, credential.label, credential.model)

        chatViewModel.messages.observe(viewLifecycleOwner) { messages ->
            adapter.submitList(messages)
            if (messages.isNotEmpty()) binding.chatRecycler.scrollToPosition(messages.size - 1)
        }
        chatViewModel.isSending.observe(viewLifecycleOwner) { sending ->
            binding.sendProgress.visibility = if (sending) View.VISIBLE else View.GONE
            binding.btnSend.isEnabled = !sending
        }

        binding.btnSend.setOnClickListener { sendCurrentInput() }
        binding.chatInput.setOnEditorActionListener { _, _, _ ->
            sendCurrentInput()
            true
        }

        binding.chipQ1.setOnClickListener { ask(getString(R.string.ai_suggest_due)) }
        binding.chipQ2.setOnClickListener { ask(getString(R.string.ai_suggest_vat)) }
        binding.chipQ3.setOnClickListener { ask(getString(R.string.ai_suggest_summary)) }
    }

    private fun sendCurrentInput() {
        val text = binding.chatInput.text?.toString().orEmpty()
        if (text.isBlank()) return
        binding.chatInput.text?.clear()
        chatViewModel.send(text)
    }

    private fun ask(question: String) {
        chatViewModel.send(question)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "AiChat"
    }
}

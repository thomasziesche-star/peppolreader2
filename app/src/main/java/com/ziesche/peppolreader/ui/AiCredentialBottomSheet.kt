package com.ziesche.peppolreader.ui

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.snackbar.Snackbar
import com.ziesche.peppolreader.R
import com.ziesche.peppolreader.ai.AiCredential
import com.ziesche.peppolreader.ai.AiCredentialStore
import com.ziesche.peppolreader.ai.LlmClient
import com.ziesche.peppolreader.ai.LlmProvider
import com.ziesche.peppolreader.databinding.BottomSheetAiCredentialBinding
import kotlinx.coroutines.launch

/**
 * Add / edit form for a single AI credential. Persists directly via [AiCredentialStore] and
 * notifies the host through [Listener.onCredentialSaved] so the list refreshes.
 */
class AiCredentialBottomSheet : BottomSheetDialogFragment() {

    interface Listener {
        fun onCredentialSaved()
    }

    private var _binding: BottomSheetAiCredentialBinding? = null
    private val binding get() = _binding!!

    private lateinit var store: AiCredentialStore
    private val client = LlmClient()
    private var editingId: String? = null
    private var selectedProvider: LlmProvider = LlmProvider.OPENAI

    /** Provider|key|baseUrl combination we already auto-fetched models for (fetch once each). */
    private var autoLoadSignature: String? = null

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
        _binding = BottomSheetAiCredentialBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        store = AiCredentialStore(requireContext())
        editingId = arguments?.getString(ARG_ID)
        val existing = editingId?.let { id -> store.list().firstOrNull { it.id == id } }

        setupProviderDropdown(existing?.provider ?: LlmProvider.OPENAI)

        existing?.let {
            binding.inputLabel.setText(it.label)
            binding.inputApiKey.setText(it.apiKey)
            binding.inputModel.setText(it.model, false)
            if (it.baseUrl.isNotBlank()) binding.inputBaseUrl.setText(it.baseUrl)
        }

        binding.btnLoadModels.setOnClickListener { loadModels() }
        binding.btnSave.setOnClickListener { save() }
        binding.btnCancel.setOnClickListener { dismiss() }

        // Auto-fetch models once when the user finishes entering the API key.
        binding.inputApiKey.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) maybeAutoLoadModels()
        }
    }

    /** Fetches models automatically the first time a (provider, key, baseUrl) combo is complete. */
    private fun maybeAutoLoadModels() {
        val b = _binding ?: return
        val key = b.inputApiKey.text?.toString()?.trim().orEmpty()
        if (key.isEmpty()) return
        val baseUrl = b.inputBaseUrl.text?.toString()?.trim().orEmpty()
        if (selectedProvider.baseUrlEditable && baseUrl.isEmpty()) return
        val signature = "${selectedProvider.name}|$key|$baseUrl"
        if (signature == autoLoadSignature) return
        autoLoadSignature = signature
        loadModels()
    }

    /** Fetches the available models from the provider API using the values typed so far. */
    private fun loadModels() {
        val apiKey = binding.inputApiKey.text?.toString()?.trim().orEmpty()
        val baseUrl = binding.inputBaseUrl.text?.toString()?.trim().orEmpty()

        binding.apiKeyLayout.error = null
        binding.baseUrlLayout.error = null
        if (apiKey.isEmpty()) {
            binding.apiKeyLayout.error = getString(R.string.ai_error_key_required)
            return
        }
        if (selectedProvider.baseUrlEditable && baseUrl.isEmpty()) {
            binding.baseUrlLayout.error = getString(R.string.ai_error_baseurl_required)
            return
        }

        binding.modelsProgress.visibility = View.VISIBLE
        binding.btnLoadModels.isEnabled = false
        val provider = selectedProvider
        viewLifecycleOwner.lifecycleScope.launch {
            val result = runCatching { client.listModels(provider, apiKey, baseUrl) }
            binding.modelsProgress.visibility = View.GONE
            binding.btnLoadModels.isEnabled = true
            result
                .onSuccess { models ->
                    if (models.isEmpty()) {
                        Snackbar.make(binding.root, R.string.ai_models_none, Snackbar.LENGTH_LONG)
                            .show()
                    } else {
                        binding.inputModel.setAdapter(
                            ArrayAdapter(
                                requireContext(),
                                android.R.layout.simple_dropdown_item_1line,
                                models
                            )
                        )
                        if (binding.inputModel.text.isNullOrBlank()) {
                            binding.inputModel.setText(models.first(), false)
                        }
                        binding.inputModel.showDropDown()
                    }
                }
                .onFailure { e ->
                    Snackbar.make(binding.root, mapError(e), Snackbar.LENGTH_LONG).show()
                }
        }
    }

    private fun mapError(t: Throwable): String = when (t) {
        is LlmClient.LlmException.Auth -> getString(R.string.ai_error_auth)
        is LlmClient.LlmException.RateLimit -> getString(R.string.ai_error_rate_limit)
        is LlmClient.LlmException.Network -> getString(R.string.ai_error_network)
        else -> getString(R.string.ai_error_generic)
    }

    private fun setupProviderDropdown(initial: LlmProvider) {
        val providers = LlmProvider.values().toList()
        val names = providers.map { it.displayName }
        binding.inputProvider.setAdapter(
            ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, names)
        )
        binding.inputProvider.setText(initial.displayName, false)
        applyProvider(initial)
        binding.inputProvider.setOnItemClickListener { _, _, position, _ ->
            val provider = providers[position]
            applyProvider(provider)
            // Auto-fill the name with the chosen provider (a default the user can still edit).
            binding.inputLabel.setText(provider.displayName)
            // If a key is already present, fetch the new provider's models right away.
            maybeAutoLoadModels()
        }
    }

    private fun applyProvider(provider: LlmProvider) {
        selectedProvider = provider

        // Model stays empty until the user loads it from the API (or types one). Offer the
        // curated list only as dropdown suggestions / offline fallback.
        binding.inputModel.setText("", false)
        binding.inputModel.setAdapter(
            ArrayAdapter(
                requireContext(),
                android.R.layout.simple_dropdown_item_1line,
                provider.models
            )
        )

        if (provider.baseUrlEditable) {
            binding.baseUrlLayout.visibility = View.VISIBLE
            // Prefill a sensible default (e.g. Langdock) the user can still edit; GENERIC stays blank.
            if (binding.inputBaseUrl.text.isNullOrBlank() && provider.defaultBaseUrl.isNotBlank()) {
                binding.inputBaseUrl.setText(provider.defaultBaseUrl)
            }
        } else {
            binding.baseUrlLayout.visibility = View.GONE
        }
    }

    private fun save() {
        val apiKey = binding.inputApiKey.text?.toString()?.trim().orEmpty()
        val model = binding.inputModel.text?.toString()?.trim().orEmpty()
        val baseUrl = binding.inputBaseUrl.text?.toString()?.trim().orEmpty()
        val label = binding.inputLabel.text?.toString()?.trim()
            ?.ifEmpty { selectedProvider.displayName } ?: selectedProvider.displayName

        binding.apiKeyLayout.error = null
        binding.modelLayout.error = null
        binding.baseUrlLayout.error = null

        if (apiKey.isEmpty()) {
            binding.apiKeyLayout.error = getString(R.string.ai_error_key_required)
            return
        }
        if (model.isEmpty()) {
            binding.modelLayout.error = getString(R.string.ai_error_model_required)
            return
        }
        if (selectedProvider.baseUrlEditable && baseUrl.isEmpty()) {
            binding.baseUrlLayout.error = getString(R.string.ai_error_baseurl_required)
            return
        }

        val credential = AiCredential(
            id = editingId ?: "",
            provider = selectedProvider,
            label = label,
            apiKey = apiKey,
            model = model,
            baseUrl = baseUrl
        )
        if (editingId == null) store.add(credential) else store.update(credential)

        // One-time-style privacy notice on save (replaces the old blocking consent dialog):
        // invoice data is sent to this provider when the user asks AI about an invoice.
        Toast.makeText(requireContext(), R.string.ai_privacy_notice, Toast.LENGTH_LONG).show()

        (parentFragment as? Listener ?: activity as? Listener)?.onCredentialSaved()
        dismiss()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "AiCredentialEditor"
        private const val ARG_ID = "id"

        fun newInstance(id: String?) = AiCredentialBottomSheet().apply {
            arguments = Bundle().apply { putString(ARG_ID, id) }
        }
    }
}

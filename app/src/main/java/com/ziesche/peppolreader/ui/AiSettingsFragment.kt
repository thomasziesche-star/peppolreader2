package com.ziesche.peppolreader.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ziesche.peppolreader.R
import com.ziesche.peppolreader.ai.AiCredential
import com.ziesche.peppolreader.ai.AiCredentialStore
import com.ziesche.peppolreader.databinding.FragmentAiSettingsBinding

/**
 * Manages the list of saved LLM credentials: add / edit (via [AiCredentialBottomSheet]),
 * delete, and choosing the default provider used for invoice questions.
 */
class AiSettingsFragment : Fragment(), AiCredentialBottomSheet.Listener {

    private var _binding: FragmentAiSettingsBinding? = null
    private val binding get() = _binding!!

    private lateinit var store: AiCredentialStore

    private val adapter by lazy {
        AiCredentialAdapter(
            onClick = { showEditor(it.id) },
            onDelete = { confirmDelete(it) },
            onSetDefault = {
                store.setDefault(it.id)
                refresh()
            }
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAiSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        store = AiCredentialStore(requireContext())

        binding.credentialsRecycler.layoutManager = LinearLayoutManager(requireContext())
        binding.credentialsRecycler.adapter = adapter

        binding.btnAdd.setOnClickListener { showEditor(null) }

        refresh()
    }

    private fun refresh() {
        val items = store.list()
        adapter.setDefaultId(store.defaultId)
        adapter.submitList(items)
        // Force a rebind so the radios reflect the (possibly changed) default id.
        adapter.notifyDataSetChanged()

        val empty = items.isEmpty()
        binding.emptyState.visibility = if (empty) View.VISIBLE else View.GONE
        binding.credentialsRecycler.visibility = if (empty) View.GONE else View.VISIBLE
    }

    private fun showEditor(id: String?) {
        AiCredentialBottomSheet.newInstance(id)
            .show(childFragmentManager, AiCredentialBottomSheet.TAG)
    }

    private fun confirmDelete(credential: AiCredential) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(credential.label)
            .setMessage(R.string.ai_delete_confirm)
            .setPositiveButton(R.string.ai_delete) { _, _ ->
                store.delete(credential.id)
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onCredentialSaved() {
        refresh()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

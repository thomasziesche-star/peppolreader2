package com.ziesche.peppolreader.creator.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.ziesche.peppolreader.R
import com.ziesche.peppolreader.creator.model.CreatorCustomer
import com.ziesche.peppolreader.data.AppDatabase
import com.ziesche.peppolreader.databinding.DialogCreatorCustomerBinding
import com.ziesche.peppolreader.databinding.FragmentCreatorCustomerListBinding
import kotlinx.coroutines.launch

/**
 * Customer master ("Kundenstamm"): lists all saved buyers as tiles and lets the user create,
 * edit and delete them independently of any invoice. Customers picked or entered during
 * invoice creation land here automatically as well.
 */
class CreatorCustomerListFragment : Fragment() {

    private var _binding: FragmentCreatorCustomerListBinding? = null
    private val binding get() = _binding!!

    private val dao by lazy { AppDatabase.getDatabase(requireContext()).creatorCustomerDao() }

    private val adapter by lazy {
        CreatorCustomerAdapter(
            onClick = { showEditDialog(it) },
            onDelete = { confirmDelete(it) }
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCreatorCustomerListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.customersRecycler.layoutManager = LinearLayoutManager(requireContext())
        binding.customersRecycler.adapter = adapter

        binding.btnNewCustomer.setOnClickListener { showEditDialog(null) }

        dao.getAllLiveData().observe(viewLifecycleOwner) { customers ->
            adapter.submitList(customers)
            val empty = customers.isEmpty()
            binding.emptyState.visibility = if (empty) View.VISIBLE else View.GONE
            binding.customersRecycler.visibility = if (empty) View.GONE else View.VISIBLE
        }
    }

    /** Create ([existing] == null) or edit a customer in a form dialog. */
    private fun showEditDialog(existing: CreatorCustomer?) {
        val dialogBinding = DialogCreatorCustomerBinding.inflate(layoutInflater)
        existing?.let { c ->
            dialogBinding.inputCustomerName.setText(c.name)
            dialogBinding.inputCustomerStreet.setText(c.street.orEmpty())
            dialogBinding.inputCustomerZip.setText(c.zip.orEmpty())
            dialogBinding.inputCustomerCity.setText(c.city.orEmpty())
            dialogBinding.inputCustomerCountry.setText(c.country.orEmpty())
            dialogBinding.inputCustomerVatId.setText(c.vatId.orEmpty())
            dialogBinding.inputCustomerEmail.setText(c.email.orEmpty())
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(existing?.name ?: getString(R.string.creator_new_customer))
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.creator_save) { _, _ ->
                val name = dialogBinding.inputCustomerName.text.str()
                if (name.isBlank()) {
                    Snackbar.make(binding.root, R.string.creator_error_invalid, Snackbar.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                val customer = CreatorCustomer(
                    id = existing?.id ?: 0,
                    name = name,
                    street = dialogBinding.inputCustomerStreet.text.str().ifBlank { null },
                    zip = dialogBinding.inputCustomerZip.text.str().ifBlank { null },
                    city = dialogBinding.inputCustomerCity.text.str().ifBlank { null },
                    country = dialogBinding.inputCustomerCountry.text.str().ifBlank { null }?.uppercase(),
                    vatId = dialogBinding.inputCustomerVatId.text.str().ifBlank { null },
                    email = dialogBinding.inputCustomerEmail.text.str().ifBlank { null }
                )
                viewLifecycleOwner.lifecycleScope.launch {
                    dao.upsert(customer)
                    Snackbar.make(binding.root, R.string.creator_saved, Snackbar.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDelete(customer: CreatorCustomer) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(customer.name)
            .setMessage(R.string.creator_delete_customer_confirm)
            .setPositiveButton(R.string.creator_delete) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch { dao.delete(customer) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun CharSequence?.str(): String = this?.toString()?.trim().orEmpty()

    override fun onDestroyView() {
        super.onDestroyView()
        binding.customersRecycler.adapter = null
        _binding = null
    }
}

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
import com.ziesche.peppolreader.creator.model.CreatorArticle
import com.ziesche.peppolreader.data.AppDatabase
import com.ziesche.peppolreader.databinding.DialogCreatorArticleBinding
import com.ziesche.peppolreader.databinding.FragmentCreatorArticleListBinding
import kotlinx.coroutines.launch

/**
 * Article/service catalog ("Artikelstamm"): lists all saved articles as tiles and lets the user
 * create, edit and delete them. Articles can be picked in the invoice editor to pre-fill a line.
 */
class CreatorArticleListFragment : Fragment() {

    private var _binding: FragmentCreatorArticleListBinding? = null
    private val binding get() = _binding!!

    private val dao by lazy { AppDatabase.getDatabase(requireContext()).creatorArticleDao() }

    private val adapter by lazy {
        CreatorArticleAdapter(
            onClick = { showEditDialog(it) },
            onDelete = { confirmDelete(it) }
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCreatorArticleListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.articlesRecycler.layoutManager = LinearLayoutManager(requireContext())
        binding.articlesRecycler.adapter = adapter

        binding.btnNewArticle.setOnClickListener { showEditDialog(null) }

        dao.getAllLiveData().observe(viewLifecycleOwner) { articles ->
            adapter.submitList(articles)
            val empty = articles.isEmpty()
            binding.emptyState.visibility = if (empty) View.VISIBLE else View.GONE
            binding.articlesRecycler.visibility = if (empty) View.GONE else View.VISIBLE
        }
    }

    /** Create ([existing] == null) or edit an article in a form dialog. */
    private fun showEditDialog(existing: CreatorArticle?) {
        val dialogBinding = DialogCreatorArticleBinding.inflate(layoutInflater)
        existing?.let { a ->
            dialogBinding.inputArticleName.setText(a.name)
            dialogBinding.inputArticleNumber.setText(a.articleNumber.orEmpty())
            dialogBinding.inputArticleUnit.setText(a.unit)
            dialogBinding.inputArticlePrice.setText(trimNumber(a.unitPrice))
            dialogBinding.inputArticleVat.setText(trimNumber(a.vatRate))
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(existing?.name ?: getString(R.string.creator_new_article))
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.creator_save) { _, _ ->
                val name = dialogBinding.inputArticleName.text.str()
                if (name.isBlank()) {
                    Snackbar.make(binding.root, R.string.creator_error_invalid, Snackbar.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                val article = CreatorArticle(
                    id = existing?.id ?: 0,
                    name = name,
                    articleNumber = dialogBinding.inputArticleNumber.text.str().ifBlank { null },
                    unit = dialogBinding.inputArticleUnit.text.str().ifBlank { "C62" },
                    unitPrice = parseDecimal(dialogBinding.inputArticlePrice.text.str()),
                    vatRate = parseDecimal(dialogBinding.inputArticleVat.text.str())
                )
                viewLifecycleOwner.lifecycleScope.launch {
                    dao.upsert(article)
                    Snackbar.make(binding.root, R.string.creator_saved, Snackbar.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDelete(article: CreatorArticle) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(article.name)
            .setMessage(R.string.creator_delete_article_confirm)
            .setPositiveButton(R.string.creator_delete) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch { dao.delete(article) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun trimNumber(value: Double): String =
        if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()

    private fun parseDecimal(text: String): Double =
        text.trim().replace(',', '.').toDoubleOrNull() ?: 0.0

    private fun CharSequence?.str(): String = this?.toString()?.trim().orEmpty()

    override fun onDestroyView() {
        super.onDestroyView()
        binding.articlesRecycler.adapter = null
        _binding = null
    }
}

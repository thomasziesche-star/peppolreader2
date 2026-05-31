package com.ziesche.peppolreader.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.ziesche.peppolreader.ai.AiCredential
import com.ziesche.peppolreader.databinding.ItemAiCredentialBinding

/**
 * Lists saved AI credentials. The radio reflects/sets the current default; row click edits,
 * the trash icon deletes. [defaultId] is supplied by the fragment and refreshed on every
 * submitList so the radios stay in sync.
 */
class AiCredentialAdapter(
    private val onClick: (AiCredential) -> Unit,
    private val onDelete: (AiCredential) -> Unit,
    private val onSetDefault: (AiCredential) -> Unit
) : ListAdapter<AiCredential, AiCredentialAdapter.ViewHolder>(DIFF) {

    private var defaultId: String? = null

    fun setDefaultId(id: String?) {
        defaultId = id
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemAiCredentialBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemAiCredentialBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(credential: AiCredential) {
            binding.credentialLabel.text = credential.label
            binding.credentialSubtitle.text =
                "${credential.provider.displayName} · ${credential.model}"
            binding.radioDefault.isChecked = credential.id == defaultId

            binding.radioDefault.setOnClickListener { onSetDefault(credential) }
            binding.root.setOnClickListener { onClick(credential) }
            binding.btnDelete.setOnClickListener { onDelete(credential) }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<AiCredential>() {
            override fun areItemsTheSame(a: AiCredential, b: AiCredential) = a.id == b.id
            override fun areContentsTheSame(a: AiCredential, b: AiCredential) = a == b
        }
    }
}

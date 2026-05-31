package com.ziesche.peppolreader.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.ziesche.peppolreader.databinding.ItemAiChatAssistantBinding
import com.ziesche.peppolreader.databinding.ItemAiChatUserBinding

/** Renders chat messages: user bubbles (right) and assistant/error bubbles (left). */
class AiChatAdapter :
    ListAdapter<AiChatViewModel.Message, RecyclerView.ViewHolder>(DIFF) {

    override fun getItemViewType(position: Int): Int =
        if (getItem(position).role == AiChatViewModel.Role.USER) TYPE_USER else TYPE_ASSISTANT

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_USER) {
            UserViewHolder(ItemAiChatUserBinding.inflate(inflater, parent, false))
        } else {
            AssistantViewHolder(ItemAiChatAssistantBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val msg = getItem(position)
        when (holder) {
            is UserViewHolder -> holder.binding.messageText.text = msg.text
            is AssistantViewHolder -> {
                holder.binding.messageText.text = msg.text
                val attr = if (msg.role == AiChatViewModel.Role.ERROR) {
                    com.google.android.material.R.attr.colorError
                } else {
                    com.google.android.material.R.attr.colorOnSurface
                }
                holder.binding.messageText.setTextColor(
                    MaterialColors.getColor(holder.binding.messageText, attr)
                )
            }
        }
    }

    class UserViewHolder(val binding: ItemAiChatUserBinding) :
        RecyclerView.ViewHolder(binding.root)

    class AssistantViewHolder(val binding: ItemAiChatAssistantBinding) :
        RecyclerView.ViewHolder(binding.root)

    companion object {
        private const val TYPE_USER = 0
        private const val TYPE_ASSISTANT = 1

        private val DIFF = object : DiffUtil.ItemCallback<AiChatViewModel.Message>() {
            override fun areItemsTheSame(
                a: AiChatViewModel.Message,
                b: AiChatViewModel.Message
            ) = a === b

            override fun areContentsTheSame(
                a: AiChatViewModel.Message,
                b: AiChatViewModel.Message
            ) = a == b
        }
    }
}

package com.ziesche.peppolreader.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.ziesche.peppolreader.R
import com.ziesche.peppolreader.ai.AiCredential
import com.ziesche.peppolreader.ai.LlmClient
import kotlinx.coroutines.launch

/**
 * Holds the (in-memory) conversation for the AI chat sheet and drives the LLM calls.
 * Scoped to the chat BottomSheet, so the history survives configuration changes but is
 * not persisted across sessions.
 */
class AiChatViewModel(app: Application) : AndroidViewModel(app) {

    enum class Role { USER, ASSISTANT, ERROR }
    data class Message(val role: Role, val text: String)

    private val client = LlmClient()

    private val _messages = MutableLiveData<List<Message>>(emptyList())
    val messages: LiveData<List<Message>> = _messages

    private val _isSending = MutableLiveData(false)
    val isSending: LiveData<Boolean> = _isSending

    private var xmlContext: String = ""
    private var credential: AiCredential? = null

    fun configure(credential: AiCredential?, xml: String) {
        this.credential = credential
        this.xmlContext = xml
    }

    fun send(question: String) {
        val cred = credential ?: return
        val trimmed = question.trim()
        if (trimmed.isEmpty() || _isSending.value == true) return

        append(Message(Role.USER, trimmed))
        _isSending.value = true
        viewModelScope.launch {
            val result = runCatching {
                client.ask(cred, buildSystemPrompt(xmlContext), trimmed)
            }
            _isSending.value = false
            result
                .onSuccess { append(Message(Role.ASSISTANT, it.trim())) }
                .onFailure { append(Message(Role.ERROR, mapError(it))) }
        }
    }

    private fun append(m: Message) {
        _messages.value = (_messages.value ?: emptyList()) + m
    }

    private fun buildSystemPrompt(xml: String): String {
        val capped =
            if (xml.length > MAX_XML_CHARS) xml.substring(0, MAX_XML_CHARS) + "\n…[truncated]"
            else xml
        return getApplication<Application>().getString(R.string.ai_system_prompt, capped)
    }

    private fun mapError(t: Throwable): String {
        val ctx = getApplication<Application>()
        return when (t) {
            is LlmClient.LlmException.Auth -> ctx.getString(R.string.ai_error_auth)
            is LlmClient.LlmException.RateLimit -> ctx.getString(R.string.ai_error_rate_limit)
            is LlmClient.LlmException.Network -> ctx.getString(R.string.ai_error_network)
            else -> ctx.getString(R.string.ai_error_generic)
        }
    }

    companion object {
        private const val MAX_XML_CHARS = 120_000
    }
}

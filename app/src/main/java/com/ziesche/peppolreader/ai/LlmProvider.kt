package com.ziesche.peppolreader.ai

/**
 * Supported LLM providers and their per-provider metadata.
 *
 * Model IDs are kept here (not in strings.xml) because they are technical identifiers,
 * not user-facing translatable text. The curated lists feed the model dropdown; users can
 * still type any custom model ID (free-text override) in the editor.
 */
enum class LlmProvider(
    val displayName: String,
    val defaultBaseUrl: String,
    val baseUrlEditable: Boolean,
    val models: List<String>
) {
    OPENAI(
        displayName = "OpenAI",
        defaultBaseUrl = "https://api.openai.com/v1",
        baseUrlEditable = false,
        models = listOf("gpt-4o-mini", "gpt-4o", "gpt-4.1-mini", "gpt-4.1", "o4-mini")
    ),
    GEMINI(
        displayName = "Google Gemini",
        defaultBaseUrl = "https://generativelanguage.googleapis.com",
        baseUrlEditable = false,
        models = listOf("gemini-1.5-flash", "gemini-1.5-pro", "gemini-2.0-flash")
    ),
    CLAUDE(
        displayName = "Anthropic Claude",
        defaultBaseUrl = "https://api.anthropic.com",
        baseUrlEditable = false,
        models = listOf("claude-3-5-haiku-latest", "claude-3-5-sonnet-latest", "claude-3-7-sonnet-latest")
    ),
    MISTRAL(
        displayName = "Mistral",
        defaultBaseUrl = "https://api.mistral.ai/v1",
        baseUrlEditable = false,
        models = listOf("mistral-large-latest", "mistral-small-latest", "open-mistral-nemo", "pixtral-large-latest")
    ),
    OPENROUTER(
        // OpenAI-compatible aggregator; model IDs are namespaced "vendor/model".
        displayName = "OpenRouter",
        defaultBaseUrl = "https://openrouter.ai/api/v1",
        baseUrlEditable = false,
        models = listOf(
            "openai/gpt-4o-mini",
            "anthropic/claude-3.5-sonnet",
            "google/gemini-2.0-flash-001",
            "meta-llama/llama-3.3-70b-instruct"
        )
    ),
    LANGDOCK(
        // OpenAI-compatible; base URL is region-specific (EU/US), so it stays editable.
        displayName = "Langdock",
        defaultBaseUrl = "https://api.langdock.com/openai/v1",
        baseUrlEditable = true,
        models = emptyList()
    ),
    GENERIC(
        displayName = "Generic (OpenAI-compatible)",
        defaultBaseUrl = "",
        baseUrlEditable = true,
        models = emptyList()
    );

    companion object {
        fun fromName(name: String?): LlmProvider =
            values().firstOrNull { it.name == name } ?: OPENAI
    }
}

package com.ziesche.peppolreader.ai

import org.json.JSONObject

/**
 * A single saved LLM access configuration. Persisted as JSON via [AiCredentialStore].
 *
 * [baseUrl] is normally blank and falls back to the provider default; only the GENERIC
 * provider requires an explicit, user-supplied base URL.
 */
data class AiCredential(
    val id: String,
    val provider: LlmProvider,
    val label: String,
    val apiKey: String,
    val model: String,
    val baseUrl: String
) {
    /** Explicit override if set, otherwise the provider default. Trailing slash stripped. */
    val effectiveBaseUrl: String
        get() = baseUrl.ifBlank { provider.defaultBaseUrl }.trimEnd('/')

    fun toJson(): JSONObject = JSONObject().apply {
        put(KEY_ID, id)
        put(KEY_PROVIDER, provider.name)
        put(KEY_LABEL, label)
        put(KEY_API_KEY, apiKey)
        put(KEY_MODEL, model)
        put(KEY_BASE_URL, baseUrl)
    }

    companion object {
        private const val KEY_ID = "id"
        private const val KEY_PROVIDER = "provider"
        private const val KEY_LABEL = "label"
        private const val KEY_API_KEY = "apiKey"
        private const val KEY_MODEL = "model"
        private const val KEY_BASE_URL = "baseUrl"

        fun fromJson(o: JSONObject): AiCredential = AiCredential(
            id = o.optString(KEY_ID),
            provider = LlmProvider.fromName(o.optString(KEY_PROVIDER)),
            label = o.optString(KEY_LABEL),
            apiKey = o.optString(KEY_API_KEY),
            model = o.optString(KEY_MODEL),
            baseUrl = o.optString(KEY_BASE_URL)
        )
    }
}

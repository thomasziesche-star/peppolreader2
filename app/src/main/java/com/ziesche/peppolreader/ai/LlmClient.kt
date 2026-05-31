package com.ziesche.peppolreader.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal LLM client built on HttpURLConnection + org.json — no extra dependencies.
 * A single suspend entry point ([ask]); the per-provider request/response shapes are
 * encapsulated here. All work runs on Dispatchers.IO.
 */
class LlmClient {

    /** Typed failures so the UI can map them to localized messages. */
    sealed class LlmException(message: String) : Exception(message) {
        class Auth : LlmException("auth")
        class RateLimit : LlmException("rate_limit")
        class Network(msg: String) : LlmException(msg)
        class BadResponse(msg: String) : LlmException(msg)
    }

    suspend fun ask(
        credential: AiCredential,
        systemPrompt: String,
        question: String
    ): String = withContext(Dispatchers.IO) {
        when (credential.provider) {
            LlmProvider.OPENAI, LlmProvider.GENERIC,
            LlmProvider.MISTRAL, LlmProvider.LANGDOCK ->
                askOpenAi(credential, systemPrompt, question)
            LlmProvider.GEMINI -> askGemini(credential, systemPrompt, question)
            LlmProvider.CLAUDE -> askClaude(credential, systemPrompt, question)
        }
    }

    // OpenAI Chat Completions — also used for the generic OpenAI-compatible endpoint.
    private fun askOpenAi(c: AiCredential, system: String, question: String): String {
        val url = "${c.effectiveBaseUrl}/chat/completions"
        val body = JSONObject().apply {
            put("model", c.model)
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", system))
                put(JSONObject().put("role", "user").put("content", question))
            })
        }
        val resp = post(url, body, mapOf("Authorization" to "Bearer ${c.apiKey}"))
        return resp.getJSONArray("choices")
            .getJSONObject(0).getJSONObject("message").getString("content")
    }

    // Google Gemini generateContent — API key is a query parameter.
    private fun askGemini(c: AiCredential, system: String, question: String): String {
        val url = "${c.effectiveBaseUrl}/v1beta/models/${c.model}:generateContent?key=${c.apiKey}"
        val body = JSONObject().apply {
            put(
                "systemInstruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system)))
            )
            put(
                "contents",
                JSONArray().put(
                    JSONObject().put("role", "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", question)))
                )
            )
        }
        val resp = post(url, body, emptyMap())
        return resp.getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts")
            .getJSONObject(0).getString("text")
    }

    // Anthropic Messages API.
    private fun askClaude(c: AiCredential, system: String, question: String): String {
        val url = "${c.effectiveBaseUrl}/v1/messages"
        val body = JSONObject().apply {
            put("model", c.model)
            put("max_tokens", 1024)
            put("system", system)
            put(
                "messages",
                JSONArray().put(JSONObject().put("role", "user").put("content", question))
            )
        }
        val resp = post(
            url, body,
            mapOf("x-api-key" to c.apiKey, "anthropic-version" to "2023-06-01")
        )
        return resp.getJSONArray("content").getJSONObject(0).getString("text")
    }

    /**
     * Fetches the available models for the given provider straight from its REST API and
     * restricts the result to models released within the last [FIVE_MONTHS_SECONDS] (when the
     * API exposes a creation timestamp) so the dropdown stays short. Newest first.
     *
     * Called from the editor with the keys/URL currently typed in (before saving), so it takes
     * the raw provider/apiKey/baseUrl rather than a persisted [AiCredential].
     */
    suspend fun listModels(
        provider: LlmProvider,
        apiKey: String,
        baseUrl: String
    ): List<String> = withContext(Dispatchers.IO) {
        val base = baseUrl.ifBlank { provider.defaultBaseUrl }.trimEnd('/')
        val cutoff = (System.currentTimeMillis() / 1000L) - FIVE_MONTHS_SECONDS
        when (provider) {
            LlmProvider.OPENAI, LlmProvider.GENERIC,
            LlmProvider.MISTRAL, LlmProvider.LANGDOCK ->
                parseOpenAiModels(
                    get("$base/models", mapOf("Authorization" to "Bearer $apiKey")),
                    cutoff
                )
            LlmProvider.GEMINI ->
                parseGeminiModels(get("$base/v1beta/models?key=$apiKey", emptyMap()))
            LlmProvider.CLAUDE ->
                parseClaudeModels(
                    get(
                        "$base/v1/models",
                        mapOf("x-api-key" to apiKey, "anthropic-version" to "2023-06-01")
                    ),
                    cutoff
                )
        }
    }

    // OpenAI / OpenAI-compatible: data[].id + created (unix seconds). Drops obvious non-chat
    // models and entries older than the cutoff; falls back to the full list if nothing remains.
    private fun parseOpenAiModels(resp: JSONObject, cutoff: Long): List<String> {
        val data = resp.optJSONArray("data") ?: return emptyList()
        val all = (0 until data.length()).mapNotNull { i ->
            val o = data.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id")
            if (id.isBlank() || isNonChatModel(id)) null else id to o.optLong("created", 0L)
        }
        val recent = all.filter { it.second == 0L || it.second >= cutoff }
        val chosen = recent.ifEmpty { all }.sortedByDescending { it.second }
        return chosen.map { it.first }.distinct()
    }

    // Anthropic: data[].id + created_at (ISO-8601).
    private fun parseClaudeModels(resp: JSONObject, cutoff: Long): List<String> {
        val data = resp.optJSONArray("data") ?: return emptyList()
        val all = (0 until data.length()).mapNotNull { i ->
            val o = data.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id")
            if (id.isBlank()) null else id to parseIsoToEpoch(o.optString("created_at"))
        }
        val recent = all.filter { it.second == null || it.second!! >= cutoff }
        val chosen = recent.ifEmpty { all }.sortedByDescending { it.second ?: 0L }
        return chosen.map { it.first }.distinct()
    }

    // Gemini: models[].name ("models/<id>"); no creation date, so the age filter cannot apply.
    // Limited to models that support generateContent. The list is short, so that is fine.
    private fun parseGeminiModels(resp: JSONObject): List<String> {
        val models = resp.optJSONArray("models") ?: return emptyList()
        return (0 until models.length()).mapNotNull { i ->
            val o = models.optJSONObject(i) ?: return@mapNotNull null
            val methods = o.optJSONArray("supportedGenerationMethods")
            val supportsGenerate = methods != null &&
                (0 until methods.length()).any { methods.optString(it) == "generateContent" }
            if (!supportsGenerate) null
            else o.optString("name").removePrefix("models/").ifBlank { null }
        }.distinct().sortedDescending()
    }

    private fun isNonChatModel(id: String): Boolean {
        val l = id.lowercase()
        return NON_CHAT_MARKERS.any { l.contains(it) }
    }

    private fun parseIsoToEpoch(s: String?): Long? =
        if (s.isNullOrBlank()) null
        else runCatching { java.time.Instant.parse(s).epochSecond }.getOrNull()

    private fun get(urlStr: String, headers: Map<String, String>): JSONObject {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 20_000
            readTimeout = 30_000
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            when {
                code == 401 || code == 403 -> throw LlmException.Auth()
                code == 429 -> throw LlmException.RateLimit()
                code !in 200..299 -> throw LlmException.BadResponse("HTTP $code: ${text.take(300)}")
            }
            return runCatching { JSONObject(text) }
                .getOrElse { throw LlmException.BadResponse("Invalid JSON response") }
        } catch (e: LlmException) {
            throw e
        } catch (e: Exception) {
            throw LlmException.Network(e.message ?: "network error")
        } finally {
            conn.disconnect()
        }
    }

    private fun post(urlStr: String, body: JSONObject, headers: Map<String, String>): JSONObject {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 60_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            when {
                code == 401 || code == 403 -> throw LlmException.Auth()
                code == 429 -> throw LlmException.RateLimit()
                code !in 200..299 -> throw LlmException.BadResponse("HTTP $code: ${text.take(300)}")
            }
            return runCatching { JSONObject(text) }
                .getOrElse { throw LlmException.BadResponse("Invalid JSON response") }
        } catch (e: LlmException) {
            throw e
        } catch (e: Exception) {
            throw LlmException.Network(e.message ?: "network error")
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        /** Roughly five months in seconds — the max age for models shown in the picker. */
        private const val FIVE_MONTHS_SECONDS = 150L * 24 * 60 * 60

        /** Substrings that mark a model as non-chat (filtered out for OpenAI-style providers). */
        private val NON_CHAT_MARKERS = listOf(
            "embedding", "whisper", "tts", "dall-e", "audio", "realtime",
            "moderation", "image", "transcribe", "search", "babbage", "davinci", "computer-use"
        )
    }
}

package com.ziesche.peppolreader.ai

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import java.util.UUID

/**
 * Lightweight SharedPreferences wrapper for AI credentials (mirrors ReminderPrefs pattern).
 * Credentials are stored as a JSON array; a separate key holds the chosen default id and the
 * one-time data-sharing consent flag. Uses MODE_PRIVATE — keys are the user's own keys on
 * their own device.
 */
class AiCredentialStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun list(): List<AiCredential> {
        val raw = prefs.getString(KEY_CREDENTIALS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { AiCredential.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    private fun save(items: List<AiCredential>) {
        val arr = JSONArray()
        items.forEach { arr.put(it.toJson()) }
        prefs.edit { putString(KEY_CREDENTIALS, arr.toString()) }
    }

    /** Adds a new credential (generating an id when missing); the first entry becomes default. */
    fun add(credential: AiCredential): AiCredential {
        val withId =
            if (credential.id.isBlank()) credential.copy(id = UUID.randomUUID().toString())
            else credential
        save(list() + withId)
        if (defaultId == null) defaultId = withId.id
        return withId
    }

    fun update(credential: AiCredential) {
        save(list().map { if (it.id == credential.id) credential else it })
    }

    fun delete(id: String) {
        val remaining = list().filterNot { it.id == id }
        save(remaining)
        if (defaultId == id) defaultId = remaining.firstOrNull()?.id
    }

    var defaultId: String?
        get() = prefs.getString(KEY_DEFAULT_ID, null)
        set(value) = prefs.edit { putString(KEY_DEFAULT_ID, value) }

    /** The configured default, falling back to the first entry if the stored id is stale. */
    fun getDefault(): AiCredential? {
        val items = list()
        return items.firstOrNull { it.id == defaultId } ?: items.firstOrNull()
    }

    fun setDefault(id: String) { defaultId = id }

    companion object {
        private const val NAME = "ai_prefs"
        private const val KEY_CREDENTIALS = "credentials"
        private const val KEY_DEFAULT_ID = "default_id"
    }
}

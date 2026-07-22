package com.ziesche.peppolreader.creator.data

import android.content.Context
import androidx.core.content.edit
import com.ziesche.peppolreader.creator.model.LayoutTheme
import org.json.JSONObject

/**
 * SharedPreferences wrapper for the single [LayoutTheme]. Deliberately stored in the same
 * `creator_prefs` file as the company profile: the BackupManager prefs whitelist already
 * covers it, so the layout survives backup/restore without further changes.
 */
class LayoutThemeStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /** Returns the saved theme, or the shipped "Warm Editorial" default. */
    fun load(): LayoutTheme {
        val raw = prefs.getString(KEY_THEME, null) ?: return LayoutTheme()
        return runCatching { LayoutTheme.fromJson(JSONObject(raw)) }
            .getOrDefault(LayoutTheme())
    }

    fun save(theme: LayoutTheme) {
        prefs.edit { putString(KEY_THEME, theme.toJson().toString()) }
    }

    companion object {
        private const val NAME = "creator_prefs"
        private const val KEY_THEME = "layout_theme"
    }
}

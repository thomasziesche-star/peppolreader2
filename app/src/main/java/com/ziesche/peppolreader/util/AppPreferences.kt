package com.ziesche.peppolreader.util

import android.content.Context
import android.content.SharedPreferences

/**
 * Single source of truth for the app's main SharedPreferences keys.
 * Feature-specific prefs (e.g. [com.ziesche.peppolreader.notifications.ReminderPrefs])
 * keep their own files; this object covers MainActivity's general UI prefs.
 */
object AppPreferences {
    const val FILE_NAME = "app_preferences"
    const val KEY_THEME_MODE = "theme_mode"
    const val KEY_LOCALE_INITIALIZED = "locale_initialized"

    fun get(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
}

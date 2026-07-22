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

    /**
     * When true the intro onboarding is suppressed on launch. It stays false (so the intro
     * shows on every start) until the user ticks "don't show again" and leaves the onboarding.
     */
    const val KEY_ONBOARDING_HIDDEN = "onboarding_hidden"

    /** Persisted SAF tree URI for automatic backups (can be a Google-Drive-synced folder). */
    const val KEY_BACKUP_TREE_URI = "backup_tree_uri"

    /** Whether the periodic auto-backup worker is active. */
    const val KEY_AUTO_BACKUP_ENABLED = "auto_backup_enabled"

    fun get(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
}

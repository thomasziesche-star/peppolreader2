package com.ziesche.peppolreader.notifications

import android.content.Context
import androidx.core.content.edit

/**
 * Lightweight wrapper around SharedPreferences for the due-date reminder feature.
 * Centralised so the worker, scheduler and UI all read / write the same keys.
 */
class ReminderPrefs(context: Context) {

    private val prefs = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit { putBoolean(KEY_ENABLED, value) }

    /** How many days before the due date the user wants to be reminded (0 = on the day). */
    var daysBefore: Int
        get() = prefs.getInt(KEY_DAYS_BEFORE, DEFAULT_DAYS_BEFORE)
        set(value) = prefs.edit { putInt(KEY_DAYS_BEFORE, value) }

    companion object {
        private const val NAME = "reminder_prefs"
        private const val KEY_ENABLED = "notify_enabled"
        private const val KEY_DAYS_BEFORE = "notify_days_before"
        const val DEFAULT_DAYS_BEFORE = 3
        val ALLOWED_DAYS_BEFORE = listOf(0, 3, 7, 14)
    }
}

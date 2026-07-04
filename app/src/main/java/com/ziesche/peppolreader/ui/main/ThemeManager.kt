package com.ziesche.peppolreader.ui.main

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.ColorMatrixColorFilter
import android.view.MenuItem
import android.widget.ImageView
import androidx.appcompat.app.AppCompatDelegate
import com.ziesche.peppolreader.R
import com.ziesche.peppolreader.util.AppPreferences

/**
 * Light/dark theme handling: persisted preference, the toolbar toggle item and the
 * logo colour filter (the logo asset is black-on-white and gets tinted per mode).
 */
class ThemeManager(private val context: Context) {

    /** Must run in onCreate BEFORE setContentView, else the first frame flashes and re-creates. */
    fun applySavedTheme() {
        val isDarkMode = AppPreferences.get(context)
            .getBoolean(AppPreferences.KEY_THEME_MODE, false) // Default to light mode (false)
        AppCompatDelegate.setDefaultNightMode(
            if (isDarkMode) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )
    }

    fun isNightMode(resources: Resources): Boolean {
        val currentNightMode =
            resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return currentNightMode == Configuration.UI_MODE_NIGHT_YES
    }

    /** Persists the flipped mode and applies it (triggers an activity re-creation). */
    fun toggle(resources: Resources) {
        val isNight = isNightMode(resources)
        with(AppPreferences.get(context).edit()) {
            putBoolean(AppPreferences.KEY_THEME_MODE, !isNight)
            apply()
        }
        AppCompatDelegate.setDefaultNightMode(
            if (isNight) AppCompatDelegate.MODE_NIGHT_NO else AppCompatDelegate.MODE_NIGHT_YES
        )
    }

    /** Icon + title of the toggle entry reflect the mode the tap would switch TO. */
    fun updateToggleMenuItem(item: MenuItem?, resources: Resources) {
        if (isNightMode(resources)) {
            item?.setIcon(R.drawable.ic_mode_day) // Show Sun icon to switch to Light
            item?.title = "Light Mode"
        } else {
            item?.setIcon(R.drawable.ic_mode_night) // Show Moon icon to switch to Dark
            item?.title = "Dark Mode"
        }
    }

    /**
     * Adjust logo color and remove background (make white transparent).
     * Source asset is black lines on white; alpha = inverse of brightness.
     */
    fun applyLogoFilter(logo: ImageView, resources: Resources) {
        val matrix = if (isNightMode(resources)) {
            // Dark Mode: white lines, transparent background.
            floatArrayOf(
                0f, 0f, 0f, 0f, 255f, // R = 255 (White)
                0f, 0f, 0f, 0f, 255f, // G = 255
                0f, 0f, 0f, 0f, 255f, // B = 255
                -0.33f, -0.33f, -0.33f, 0f, 255f // Alpha = Inverse of brightness
            )
        } else {
            // Light Mode: black lines, transparent background.
            floatArrayOf(
                0f, 0f, 0f, 0f, 0f,   // R = 0 (Black)
                0f, 0f, 0f, 0f, 0f,   // G = 0
                0f, 0f, 0f, 0f, 0f,   // B = 0
                -0.33f, -0.33f, -0.33f, 0f, 255f // Alpha = Inverse of brightness
            )
        }
        logo.colorFilter = ColorMatrixColorFilter(matrix)
    }
}

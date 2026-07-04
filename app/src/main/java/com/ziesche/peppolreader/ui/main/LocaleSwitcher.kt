package com.ziesche.peppolreader.ui.main

import android.content.Context
import android.view.Menu
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.ziesche.peppolreader.R
import com.ziesche.peppolreader.util.AppPreferences

/**
 * Per-app language handling: the first-run default (DE), the language sub-menu state
 * (flag icon + checked entry) and the menu-click → locale mapping. Pure delegate — the
 * activity forwards its menu callbacks here.
 */
object LocaleSwitcher {

    private val menuItemToTag = mapOf(
        R.id.action_lang_en to "en",
        R.id.action_lang_de to "de",
        R.id.action_lang_nl to "nl",
        R.id.action_lang_fr to "fr",
        R.id.action_lang_pl to "pl"
    )

    /** Sets German as the app language on the very first start (matches the target audience). */
    fun ensureDefaultLocale(context: Context) {
        val sharedPref = AppPreferences.get(context)
        if (!sharedPref.contains(AppPreferences.KEY_LOCALE_INITIALIZED)) {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("de"))
            with(sharedPref.edit()) {
                putBoolean(AppPreferences.KEY_LOCALE_INITIALIZED, true)
                apply()
            }
        }
    }

    /** The active app language tag, "en" when the system default is in effect. */
    fun currentLanguage(): String {
        val currentLocales = AppCompatDelegate.getApplicationLocales()
        return if (!currentLocales.isEmpty) currentLocales.get(0)?.language ?: "en" else "en"
    }

    /** Returns true when [itemId] was one of the language entries (and the locale was applied). */
    fun handleMenuSelection(itemId: Int): Boolean {
        val tag = menuItemToTag[itemId] ?: return false
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
        return true
    }

    /** Applies the active flag icon and check mark to the language sub-menu. */
    fun updateMenu(menu: Menu) {
        val currentLang = currentLanguage()

        // Show the currently active flag as the toolbar icon
        val flagIcon = when (currentLang) {
            "de" -> R.drawable.ic_flag_de
            "nl" -> R.drawable.ic_flag_nl
            "fr" -> R.drawable.ic_flag_fr
            "pl" -> R.drawable.ic_flag_pl
            else -> R.drawable.ic_flag_en
        }
        menu.findItem(R.id.action_language)?.setIcon(flagIcon)

        // Mark the active language inside the sub-menu. The group uses
        // checkableBehavior="single"; for an exclusive group MenuItem.setChecked()
        // IGNORES its boolean argument and always selects the item it is called on.
        // Calling it on every entry therefore left the LAST one (Polski) checked,
        // regardless of the active locale. Only touch the active entry and let the
        // single-choice group clear the others.
        val activeLangItemId = when (currentLang) {
            "de" -> R.id.action_lang_de
            "nl" -> R.id.action_lang_nl
            "fr" -> R.id.action_lang_fr
            "pl" -> R.id.action_lang_pl
            else -> R.id.action_lang_en
        }
        menu.findItem(activeLangItemId)?.isChecked = true
    }
}

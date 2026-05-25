package com.ziesche.peppolreader

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.navigation.findNavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import com.ziesche.peppolreader.databinding.ActivityMainBinding
import com.ziesche.peppolreader.ui.InvoiceListFragment
import com.ziesche.peppolreader.ui.InvoiceViewModel
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.text.Html

class MainActivity : AppCompatActivity() {

    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var binding: ActivityMainBinding
    private val viewModel: InvoiceViewModel by viewModels()
    private val PREF_NAME = "app_preferences"
    private val KEY_THEME_MODE = "theme_mode"

    /**
     * URI handed in via ACTION_VIEW / ACTION_SEND before the list fragment is ready.
     * Consumed by InvoiceListFragment.onViewCreated, then cleared.
     */
    var pendingImportUri: Uri? = null
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Load saved theme preference
        val sharedPref = getSharedPreferences(PREF_NAME, MODE_PRIVATE)
        val isDarkMode = sharedPref.getBoolean(KEY_THEME_MODE, false) // Default to light mode (false)
        if (isDarkMode) {
            androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES)
        } else {
            androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO)
        }

        // Set default locale to DE on first run
        val KEY_LOCALE_INITIALIZED = "locale_initialized"
        if (!sharedPref.contains(KEY_LOCALE_INITIALIZED)) {
            val appLocale = androidx.core.os.LocaleListCompat.forLanguageTags("de")
            androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(appLocale)
            with(sharedPref.edit()) {
                putBoolean(KEY_LOCALE_INITIALIZED, true)
                apply()
            }
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.fab) { view, insets ->
            val navInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            view.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = (16 * resources.displayMetrics.density).toInt() + navInsets.bottom
            }
            insets
        }

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)

        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment_content_main) as NavHostFragment
        val navController = navHostFragment.navController
        
        appBarConfiguration = AppBarConfiguration(navController.graph)
        setupActionBarWithNavController(navController, appBarConfiguration)

        // FAB click - open file picker
        binding.fab.setOnClickListener {
            openFilePicker()
        }
        
        // Update FAB icon and ensure content description is localized
        binding.fab.setImageResource(R.drawable.ic_file_upload)
        binding.fab.contentDescription = getString(R.string.import_file)
        
        // Hide FAB on detail screen/settings
        navController.addOnDestinationChangedListener { _, destination, _ ->
            when (destination.id) {
                R.id.invoiceListFragment -> binding.fab.show()
                else -> binding.fab.hide() // Hide on detail and settings
            }
        }
        
        // Adjust logo color and remove background (make white transparent)
        val currentNightMode = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        val isNight = currentNightMode == android.content.res.Configuration.UI_MODE_NIGHT_YES
        
        if (isNight) {
            // Dark Mode: White lines, Transparent background
            // Source: Black on White.
            // Target: White lines (from black Source), Transparent (from white Source).
            // A = 255 - average(RGB)
            val matrix = floatArrayOf(
                0f, 0f, 0f, 0f, 255f, // R = 255 (White)
                0f, 0f, 0f, 0f, 255f, // G = 255
                0f, 0f, 0f, 0f, 255f, // B = 255
                -0.33f, -0.33f, -0.33f, 0f, 255f // Alpha = Inverse of brightness
            )
            binding.logo.colorFilter = android.graphics.ColorMatrixColorFilter(matrix)
        } else {
            // Light Mode: Black lines, Transparent background
            // Source: Black on White.
            // Target: Black lines, Transparent background.
            // A = 255 - average(RGB)
            val matrix = floatArrayOf(
                0f, 0f, 0f, 0f, 0f,   // R = 0 (Black)
                0f, 0f, 0f, 0f, 0f,   // G = 0
                0f, 0f, 0f, 0f, 0f,   // B = 0
                -0.33f, -0.33f, -0.33f, 0f, 255f // Alpha = Inverse of brightness
            )
            binding.logo.colorFilter = android.graphics.ColorMatrixColorFilter(matrix)
        }

        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        val uri: Uri = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data ?: return
            Intent.ACTION_SEND -> intent.extraStreamCompat() ?: return
            else -> return
        }

        val current = currentListFragment()
        if (current != null) {
            current.importExternalUri(uri)
        } else {
            pendingImportUri = uri
        }
    }

    /**
     * Called by InvoiceListFragment after it is ready, to drain any pending import URI.
     */
    fun consumePendingImportUri(): Uri? {
        val uri = pendingImportUri
        pendingImportUri = null
        return uri
    }

    private fun currentListFragment(): InvoiceListFragment? {
        val navHost = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment_content_main) as? NavHostFragment ?: return null
        return navHost.childFragmentManager.fragments.firstOrNull() as? InvoiceListFragment
    }

    @Suppress("DEPRECATION")
    private fun Intent.extraStreamCompat(): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
        }

    private fun openFilePicker() {
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment_content_main) as NavHostFragment
        val currentFragment = navHostFragment.childFragmentManager.fragments.firstOrNull()
        
        if (currentFragment is InvoiceListFragment) {
            currentFragment.openFilePicker()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val currentLocales = androidx.appcompat.app.AppCompatDelegate.getApplicationLocales()
        val currentLang = if (!currentLocales.isEmpty) currentLocales.get(0)?.language ?: "en" else "en"

        // Show the currently active flag as the toolbar icon
        val flagIcon = when (currentLang) {
            "de" -> R.drawable.ic_flag_de
            "nl" -> R.drawable.ic_flag_nl
            "fr" -> R.drawable.ic_flag_fr
            else -> R.drawable.ic_flag_en
        }
        menu.findItem(R.id.action_language)?.setIcon(flagIcon)

        // Mark the active language inside the sub-menu (checkableBehavior="single")
        menu.findItem(R.id.action_lang_en)?.isChecked = currentLang == "en"
        menu.findItem(R.id.action_lang_de)?.isChecked = currentLang == "de"
        menu.findItem(R.id.action_lang_nl)?.isChecked = currentLang == "nl"
        menu.findItem(R.id.action_lang_fr)?.isChecked = currentLang == "fr"
        
        // Update theme toggle icon based on current mode
        val currentNightMode = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        val isNightMode = currentNightMode == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val toggleItem = menu.findItem(R.id.action_theme_toggle)
        
        if (isNightMode) {
            toggleItem?.setIcon(R.drawable.ic_mode_day) // Show Sun icon to switch to Light
            toggleItem?.title = "Light Mode"
        } else {
            toggleItem?.setIcon(R.drawable.ic_mode_night) // Show Moon icon to switch to Dark
            toggleItem?.title = "Dark Mode"
        }
        
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_lang_en -> {
                val appLocale = androidx.core.os.LocaleListCompat.forLanguageTags("en")
                androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(appLocale)
                true
            }
            R.id.action_lang_de -> {
                val appLocale = androidx.core.os.LocaleListCompat.forLanguageTags("de")
                androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(appLocale)
                true
            }
            R.id.action_lang_nl -> {
                val appLocale = androidx.core.os.LocaleListCompat.forLanguageTags("nl")
                androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(appLocale)
                true
            }
            R.id.action_lang_fr -> {
                val appLocale = androidx.core.os.LocaleListCompat.forLanguageTags("fr")
                androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(appLocale)
                true
            }
            R.id.action_theme_toggle -> {
                val sharedPref = getSharedPreferences(PREF_NAME, MODE_PRIVATE)
                val currentNightMode = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
                val isNightMode = currentNightMode == android.content.res.Configuration.UI_MODE_NIGHT_YES
                
                val newMode = if (isNightMode) {
                    androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
                } else {
                    androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
                }
                
                // Save preference
                with (sharedPref.edit()) {
                    putBoolean(KEY_THEME_MODE, !isNightMode) // Save the *new* state (if we were night, we are now day/false)
                    apply()
                }

                androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(newMode)
                true
            }
            R.id.action_dashboard -> {
                val navController = findNavController(R.id.nav_host_fragment_content_main)
                navController.navigate(R.id.dashboardFragment)
                true
            }
            R.id.action_info -> {
                showInfoDialog()
                true
            }
            R.id.action_help -> {
                showHelpDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        val navController = findNavController(R.id.nav_host_fragment_content_main)
        return navController.navigateUp(appBarConfiguration)
                || super.onSupportNavigateUp()
    }

    private fun showHelpDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.help_title)
            .setMessage(withVersionFooter(getText(R.string.help_message)))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showInfoDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.info_title)
            .setMessage(withVersionFooter(getText(R.string.info_message)))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /**
     * Appends "Version <name>" to a dialog body, preserving any HTML styling
     * already present in the resource string (which is why we use getText + Spannable).
     */
    private fun withVersionFooter(body: CharSequence): CharSequence {
        val versionName = try {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (e: PackageManager.NameNotFoundException) {
            ""
        }
        return SpannableStringBuilder(body)
            .append("\n\n")
            .append(getString(R.string.version_label, versionName))
    }
}

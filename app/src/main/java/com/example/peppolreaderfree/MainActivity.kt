package com.example.peppolreaderfree

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.findNavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import com.example.peppolreaderfree.databinding.ActivityMainBinding
import com.example.peppolreaderfree.ui.InvoiceListFragment
import com.example.peppolreaderfree.ui.InvoiceViewModel

class MainActivity : AppCompatActivity() {

    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var binding: ActivityMainBinding
    private val viewModel: InvoiceViewModel by viewModels()
    private val PREF_NAME = "app_preferences"
    private val KEY_THEME_MODE = "theme_mode"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Load saved theme preference
        val sharedPref = getSharedPreferences(PREF_NAME, MODE_PRIVATE)
        val isDarkMode = sharedPref.getBoolean(KEY_THEME_MODE, false) // Default to light mode (false)
        if (isDarkMode) {
            androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES)
        } else {
            androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO)
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

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
        
        // Adjust logo color for Dark Mode (Invert colors)
        val currentNightMode = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        val isNight = currentNightMode == android.content.res.Configuration.UI_MODE_NIGHT_YES
        if (isNight) {
            val matrix = floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f
            )
            binding.logo.colorFilter = android.graphics.ColorMatrixColorFilter(matrix)
        } else {
            binding.logo.colorFilter = null
        }
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
        
        val alphaActive = 255
        val alphaInactive = 100 // Semi-transparent for inactive

        menu.findItem(R.id.action_lang_en)?.icon?.alpha = if (currentLang == "en") alphaActive else alphaInactive
        menu.findItem(R.id.action_lang_de)?.icon?.alpha = if (currentLang == "de") alphaActive else alphaInactive
        
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
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        val navController = findNavController(R.id.nav_host_fragment_content_main)
        return navController.navigateUp(appBarConfiguration)
                || super.onSupportNavigateUp()
    }
}
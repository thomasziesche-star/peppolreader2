package com.ziesche.peppolreader

import android.app.Activity
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.navigation.findNavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import com.ziesche.peppolreader.databinding.ActivityMainBinding
import com.ziesche.peppolreader.export.CsvExporter
import com.ziesche.peppolreader.ui.ExportBottomSheetFragment
import com.ziesche.peppolreader.ui.InvoiceListFragment
import com.ziesche.peppolreader.ui.InvoiceViewModel
import com.ziesche.peppolreader.ui.ReminderSettingsBottomSheet
import com.ziesche.peppolreader.util.AppPreferences
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import android.text.Html

class MainActivity : AppCompatActivity(),
    ExportBottomSheetFragment.Listener,
    ReminderSettingsBottomSheet.Listener {

    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var binding: ActivityMainBinding
    private val viewModel: InvoiceViewModel by viewModels()
    // SharedPreferences keys are defined centrally in [AppPreferences].

    /**
     * URI handed in via ACTION_VIEW / ACTION_SEND before the list fragment is ready.
     * Consumed by InvoiceListFragment.onViewCreated, then cleared.
     */
    var pendingImportUri: Uri? = null
        private set

    /** Holds the BottomSheet that requested POST_NOTIFICATIONS so we can call back when granted. */
    private var pendingReminderSheet: ReminderSettingsBottomSheet? = null

    /** Asks for POST_NOTIFICATIONS on Android 13+ when the user flips reminders on. */
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val sheet = pendingReminderSheet
        pendingReminderSheet = null
        if (sheet == null) return@registerForActivityResult
        if (granted) sheet.confirmEnabled() else sheet.denyEnabled()
    }

    /** Drives the ACTION_CREATE_DOCUMENT picker for the CSV/ZIP export. */
    private val createDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val payload = viewModel.pendingExportPayload
        viewModel.pendingExportPayload = null
        if (result.resultCode != Activity.RESULT_OK || payload == null) return@registerForActivityResult
        val uri = result.data?.data ?: return@registerForActivityResult
        writeExportPayload(uri, payload)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Load saved theme preference
        val sharedPref = AppPreferences.get(this)
        val isDarkMode = sharedPref.getBoolean(AppPreferences.KEY_THEME_MODE, false) // Default to light mode (false)
        if (isDarkMode) {
            androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES)
        } else {
            androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO)
        }

        // Set default locale to DE on first run
        if (!sharedPref.contains(AppPreferences.KEY_LOCALE_INITIALIZED)) {
            val appLocale = androidx.core.os.LocaleListCompat.forLanguageTags("de")
            androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(appLocale)
            with(sharedPref.edit()) {
                putBoolean(AppPreferences.KEY_LOCALE_INITIALIZED, true)
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

        // Notification deep-link
        val deepLinkId = intent.getLongExtra(EXTRA_OPEN_INVOICE_ID, -1L)
        if (deepLinkId != -1L) {
            // Clear the extra so re-rotation doesn't re-trigger
            intent.removeExtra(EXTRA_OPEN_INVOICE_ID)
            openInvoiceFromDeepLink(deepLinkId)
            return
        }

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

    private fun openInvoiceFromDeepLink(invoiceId: Long) {
        viewModel.selectInvoiceById(invoiceId)
        val navController = findNavController(R.id.nav_host_fragment_content_main)
        // Already on detail? Don't push again.
        if (navController.currentDestination?.id != R.id.invoiceDetailFragment) {
            navController.navigate(R.id.action_invoiceList_to_invoiceDetail)
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
                val sharedPref = AppPreferences.get(this)
                val currentNightMode = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
                val isNightMode = currentNightMode == android.content.res.Configuration.UI_MODE_NIGHT_YES

                val newMode = if (isNightMode) {
                    androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
                } else {
                    androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
                }

                // Save preference
                with(sharedPref.edit()) {
                    putBoolean(AppPreferences.KEY_THEME_MODE, !isNightMode)
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
            R.id.action_export -> {
                ExportBottomSheetFragment().show(
                    supportFragmentManager,
                    ExportBottomSheetFragment.TAG
                )
                true
            }
            R.id.action_reminders -> {
                ReminderSettingsBottomSheet().show(
                    supportFragmentManager,
                    ReminderSettingsBottomSheet.TAG
                )
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

    // ----- Export pipeline -------------------------------------------------------------

    override fun onExportRequested(
        fromIso: String,
        toIso: String,
        includeXmlBundle: Boolean
    ) {
        val headers = CsvExporter.Headers(
            invoiceId = getString(R.string.csv_invoice_id),
            issueDate = getString(R.string.csv_issue_date),
            dueDate = getString(R.string.csv_due_date),
            supplier = getString(R.string.csv_supplier),
            supplierTaxId = getString(R.string.csv_supplier_taxid),
            customer = getString(R.string.csv_customer),
            net = getString(R.string.csv_net),
            tax = getString(R.string.csv_tax),
            gross = getString(R.string.csv_gross),
            payable = getString(R.string.csv_payable),
            currency = getString(R.string.csv_currency),
            format = getString(R.string.csv_format),
            docType = getString(R.string.csv_doc_type),
            fileName = getString(R.string.csv_file_name),
            docTypeInvoice = getString(R.string.csv_doc_type_invoice),
            docTypeCreditNote = getString(R.string.csv_doc_type_credit_note),
            docTypeCorrected = getString(R.string.csv_doc_type_corrected)
        )
        lifecycleScope.launch {
            when (val payload = viewModel.buildExportPayload(fromIso, toIso, includeXmlBundle, headers)) {
                InvoiceViewModel.ExportPayload.Empty ->
                    Snackbar.make(binding.root, R.string.export_no_invoices, Snackbar.LENGTH_LONG).show()
                is InvoiceViewModel.ExportPayload.Error ->
                    Snackbar.make(
                        binding.root,
                        getString(R.string.export_error, payload.message),
                        Snackbar.LENGTH_LONG
                    ).show()
                is InvoiceViewModel.ExportPayload.Success -> {
                    viewModel.pendingExportPayload = payload
                    val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType(payload.mimeType)
                        .putExtra(Intent.EXTRA_TITLE, payload.suggestedFileName)
                    createDocumentLauncher.launch(intent)
                }
            }
        }
    }

    private fun writeExportPayload(uri: Uri, payload: InvoiceViewModel.ExportPayload.Success) {
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openOutputStream(uri)?.use { it.write(payload.bytes) }
                    true
                }.getOrElse { false }
            }
            if (!ok) {
                Snackbar.make(
                    binding.root,
                    getString(R.string.export_error, "I/O"),
                    Snackbar.LENGTH_LONG
                ).show()
                return@launch
            }
            val msg = resources.getQuantityString(
                R.plurals.export_success, payload.invoiceCount, payload.invoiceCount
            )
            Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG)
                .setAction(R.string.export_share) { shareExportedFile(uri, payload.mimeType) }
                .show()
        }
    }

    // ----- Reminder pipeline -----------------------------------------------------------

    override fun onEnableRequested(sheet: ReminderSettingsBottomSheet) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED
            ) {
                sheet.confirmEnabled()
            } else {
                pendingReminderSheet = sheet
                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            // Pre-Tiramisu: permission is granted at install time, no runtime prompt needed.
            sheet.confirmEnabled()
        }
    }

    private fun shareExportedFile(uri: Uri, mimeType: String) {
        val share = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(share, getString(R.string.export_share)))
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

    companion object {
        /** Intent extra used by [com.ziesche.peppolreader.notifications.DueDateWorker] for the deep-link tap. */
        const val EXTRA_OPEN_INVOICE_ID = "com.ziesche.peppolreader.OPEN_INVOICE_ID"
    }
}

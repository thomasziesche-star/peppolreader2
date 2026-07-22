package com.ziesche.peppolreader

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.navigation.findNavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import com.ziesche.peppolreader.databinding.ActivityMainBinding
import com.ziesche.peppolreader.ui.ExportBottomSheetFragment
import com.ziesche.peppolreader.ui.InvoiceListFragment
import com.ziesche.peppolreader.ui.InvoiceViewModel
import com.ziesche.peppolreader.ui.ReminderSettingsBottomSheet
import com.ziesche.peppolreader.ui.main.BackupRestoreCoordinator
import com.ziesche.peppolreader.ui.main.ExportCoordinator
import com.ziesche.peppolreader.ui.main.ImportIntentHandler
import com.ziesche.peppolreader.ui.main.LocaleSwitcher
import com.ziesche.peppolreader.ui.main.MainDestinations
import com.ziesche.peppolreader.ui.main.MainMenuHandler
import com.ziesche.peppolreader.ui.main.ThemeManager
import com.ziesche.peppolreader.ui.onboarding.OnboardingActivity
import com.ziesche.peppolreader.util.AppPreferences

/**
 * Slim host activity: toolbar/nav/bottom-nav wiring plus the notification permission.
 * Everything else is delegated — theme/locale to [ThemeManager]/[LocaleSwitcher], the options
 * menu to [MainMenuHandler], intents/deep-links to [ImportIntentHandler], CSV/ZIP export to
 * [ExportCoordinator] and backup/restore to [BackupRestoreCoordinator].
 */
class MainActivity : AppCompatActivity(),
    ExportBottomSheetFragment.Listener,
    ReminderSettingsBottomSheet.Listener {

    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var binding: ActivityMainBinding
    private val viewModel: InvoiceViewModel by viewModels()

    private val themeManager = ThemeManager(this)

    // The coordinators register ActivityResult launchers in their init blocks, so they MUST be
    // property initializers: registration happens before STARTED and in a stable order.
    private val backupRestore = BackupRestoreCoordinator(this) { binding.root }
    private val exportCoordinator = ExportCoordinator(this, { viewModel }, { binding.root })

    private val importIntents = ImportIntentHandler(
        nav = { findNavController(R.id.nav_host_fragment_content_main) },
        bottomNav = { binding.bottomNav },
        viewModel = { viewModel },
        listFragment = { currentListFragment() }
    )

    private val menuHandler = MainMenuHandler(
        activity = this,
        themeManager = themeManager,
        nav = { findNavController(R.id.nav_host_fragment_content_main) },
        actions = object : MainMenuHandler.Actions {
            override fun onBackup() = backupRestore.showBackupOptions()
            override fun onRestore() = backupRestore.launchOpenBackup()
        }
    )

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Theme before setContentView, else the first frame flashes and re-creates.
        themeManager.applySavedTheme()
        LocaleSwitcher.ensureDefaultLocale(this)

        maybeShowOnboarding()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.fab) { view, insets ->
            // With the bottom nav visible the CoordinatorLayout already ends above
            // the navigation bar, so the FAB only needs its fixed margin then.
            val navInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val extraBottom = if (BuildConfig.ENABLE_INVOICE_CREATOR) 0 else navInsets.bottom
            view.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = (16 * resources.displayMetrics.density).toInt() + extraBottom
            }
            insets
        }

        // Belt-and-suspenders for Android 15 edge-to-edge: the bottom nav sits outside the
        // fitsSystemWindows CoordinatorLayout, so lift it above the gesture/navigation bar
        // ourselves. This replaces Material's own inset listener (the bar carries no XML padding,
        // so the raw inset is the correct padding — no double counting).
        ViewCompat.setOnApplyWindowInsetsListener(binding.bottomNav) { view, insets ->
            view.updatePadding(bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom)
            insets
        }

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)

        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment_content_main) as NavHostFragment
        val navController = navHostFragment.navController

        appBarConfiguration = AppBarConfiguration(
            setOf(R.id.invoiceListFragment, R.id.invoiceCreatorListFragment)
        )
        setupActionBarWithNavController(navController, appBarConfiguration)

        // Reader/Creator mode switch. The menu item ids match the destination ids,
        // so NavigationUI keeps a saved back stack per tab.
        binding.bottomNav.setupWithNavController(navController)
        binding.bottomNav.setOnItemReselectedListener { item ->
            navController.popBackStack(item.itemId, false)
        }
        if (!BuildConfig.ENABLE_INVOICE_CREATOR) {
            binding.bottomNav.visibility = View.GONE
        }

        // FAB click - open file picker
        binding.fab.setOnClickListener {
            openFilePicker()
        }

        // Update FAB icon and ensure content description is localized
        binding.fab.setImageResource(R.drawable.ic_file_upload)
        binding.fab.contentDescription = getString(R.string.import_file)

        navController.addOnDestinationChangedListener { _, destination, _ ->
            when (destination.id) {
                R.id.invoiceListFragment -> binding.fab.show()
                else -> binding.fab.hide() // Hide on detail and settings
            }
            binding.toolbarTitle.text = getString(
                if (destination.id in MainDestinations.creator) R.string.app_title_creator else R.string.app_title
            )
        }

        themeManager.applyLogoFilter(binding.logo, resources)

        importIntents.handle(intent)
    }

    /**
     * Launches the intro on top of the main screen unless the user has hidden it. Guarded by a
     * process-wide flag so a locale change (which recreates this activity) does not stack a second
     * copy — the already-visible OnboardingActivity recreates itself in the new language instead.
     */
    private fun maybeShowOnboarding() {
        if (onboardingLaunchedThisProcess) return
        val hidden = AppPreferences.get(this).getBoolean(AppPreferences.KEY_ONBOARDING_HIDDEN, false)
        if (hidden) return
        onboardingLaunchedThisProcess = true
        startActivity(Intent(this, OnboardingActivity::class.java))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        importIntents.handle(intent)
    }

    /** Called by InvoiceListFragment after it is ready, to drain any pending import URI. */
    fun consumePendingImportUri(): Uri? = importIntents.consumePendingImportUri()

    private fun currentListFragment(): InvoiceListFragment? {
        val navHost = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment_content_main) as? NavHostFragment ?: return null
        return navHost.childFragmentManager.fragments.firstOrNull() as? InvoiceListFragment
    }

    private fun openFilePicker() {
        currentListFragment()?.openFilePicker()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menuHandler.prepareMenu(menu)
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean =
        menuHandler.onItemSelected(item) || super.onOptionsItemSelected(item)

    override fun onSupportNavigateUp(): Boolean {
        val navController = findNavController(R.id.nav_host_fragment_content_main)
        return navController.navigateUp(appBarConfiguration)
                || super.onSupportNavigateUp()
    }

    // ----- Export pipeline (ExportBottomSheetFragment.Listener) -------------------------

    override fun onExportRequested(
        fromIso: String,
        toIso: String,
        includeXmlBundle: Boolean
    ) = exportCoordinator.onExportRequested(fromIso, toIso, includeXmlBundle)

    // ----- Reminder pipeline (ReminderSettingsBottomSheet.Listener) ---------------------

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

    companion object {
        /**
         * True once the intro has been launched in this process. Survives the activity recreation
         * triggered by an in-onboarding language change, so the intro is not stacked twice.
         */
        @Volatile
        private var onboardingLaunchedThisProcess = false

        /** Intent extra used by [com.ziesche.peppolreader.notifications.DueDateWorker] for the deep-link tap. */
        const val EXTRA_OPEN_INVOICE_ID = ImportIntentHandler.EXTRA_OPEN_INVOICE_ID

        /** Intent extra (Boolean): overdue outgoing-invoice notification → open the Create tab. */
        const val EXTRA_OPEN_CREATOR = ImportIntentHandler.EXTRA_OPEN_CREATOR
    }
}

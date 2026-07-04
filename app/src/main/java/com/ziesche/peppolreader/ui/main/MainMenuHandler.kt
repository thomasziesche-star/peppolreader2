package com.ziesche.peppolreader.ui.main

import android.content.pm.PackageManager
import android.text.SpannableStringBuilder
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.NavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ziesche.peppolreader.R
import com.ziesche.peppolreader.ui.ExportBottomSheetFragment
import com.ziesche.peppolreader.ui.ReminderSettingsBottomSheet

/**
 * Owns the toolbar options menu: language sub-menu, theme toggle, dashboard routing and the
 * dialog/bottom-sheet actions. Backup/restore are forwarded through [Actions] so this class
 * doesn't need to know the coordinators.
 */
class MainMenuHandler(
    private val activity: AppCompatActivity,
    private val themeManager: ThemeManager,
    private val nav: () -> NavController,
    private val actions: Actions
) {

    /** The menu entries whose implementation lives elsewhere in the activity. */
    interface Actions {
        fun onBackup()
        fun onRestore()
    }

    /** Delegate target for Activity.onPrepareOptionsMenu. */
    fun prepareMenu(menu: Menu) {
        LocaleSwitcher.updateMenu(menu)
        themeManager.updateToggleMenuItem(
            menu.findItem(R.id.action_theme_toggle), activity.resources
        )
    }

    /** Delegate target for Activity.onOptionsItemSelected; false = not handled here. */
    fun onItemSelected(item: MenuItem): Boolean {
        if (LocaleSwitcher.handleMenuSelection(item.itemId)) return true
        return when (item.itemId) {
            R.id.action_theme_toggle -> {
                themeManager.toggle(activity.resources)
                true
            }
            R.id.action_dashboard -> {
                // "Dashboard" means the dashboard of the mode the user is in: the revenue
                // dashboard on Create-tab screens, the expense dashboard everywhere else.
                val navController = nav()
                val target = if (navController.currentDestination?.id in MainDestinations.creator) {
                    R.id.creatorDashboardFragment
                } else {
                    R.id.dashboardFragment
                }
                navController.navigate(target)
                true
            }
            R.id.action_export -> {
                ExportBottomSheetFragment().show(
                    activity.supportFragmentManager,
                    ExportBottomSheetFragment.TAG
                )
                true
            }
            R.id.action_reminders -> {
                ReminderSettingsBottomSheet().show(
                    activity.supportFragmentManager,
                    ReminderSettingsBottomSheet.TAG
                )
                true
            }
            R.id.action_ai_settings -> {
                nav().navigate(R.id.aiSettingsFragment)
                true
            }
            R.id.action_backup -> {
                actions.onBackup()
                true
            }
            R.id.action_restore -> {
                actions.onRestore()
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
            else -> false
        }
    }

    /**
     * Context-aware help: shows guidance for the screen the user is currently on. The home list
     * keeps the full overview; every other destination has its own focused help text.
     */
    private fun showHelpDialog() {
        val destinationId = nav().currentDestination?.id
        val messageRes = when (destinationId) {
            R.id.invoiceDetailFragment -> R.string.help_detail
            R.id.dashboardFragment -> R.string.help_dashboard
            R.id.aiSettingsFragment -> R.string.help_ai
            R.id.invoiceCreatorListFragment -> R.string.help_creator_list
            R.id.invoiceCreatorEditFragment -> R.string.help_creator_edit
            R.id.companyProfileFragment -> R.string.help_company_profile
            R.id.creatorCustomerListFragment -> R.string.help_creator_customers
            R.id.creatorArticleListFragment -> R.string.help_creator_articles
            R.id.creatorDashboardFragment -> R.string.help_creator_dashboard
            else -> R.string.help_message
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.help_title)
            .setMessage(withVersionFooter(activity.getText(messageRes)))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showInfoDialog() {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.info_title)
            .setMessage(withVersionFooter(activity.getText(R.string.info_message)))
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
            activity.packageManager.getPackageInfo(activity.packageName, 0).versionName ?: ""
        } catch (e: PackageManager.NameNotFoundException) {
            ""
        }
        return SpannableStringBuilder(body)
            .append("\n\n")
            .append(activity.getString(R.string.version_label, versionName))
    }
}

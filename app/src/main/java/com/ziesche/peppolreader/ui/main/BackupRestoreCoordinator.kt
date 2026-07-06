package com.ziesche.peppolreader.ui.main

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.ziesche.peppolreader.R
import com.ziesche.peppolreader.creator.data.PdfExporter
import com.ziesche.peppolreader.data.BackupManager
import com.ziesche.peppolreader.data.BackupScheduler
import com.ziesche.peppolreader.util.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Full backup/restore via SAF: create-document picker for the ZIP export, open-document
 * picker + confirmation dialog + hard process restart for the restore.
 *
 * MUST be constructed as a property initializer of the activity: the two launchers register
 * during `init`, and `registerForActivityResult` requires registration before STARTED and a
 * stable, unconditional registration order across re-creations.
 *
 * [root] is a lambda because the coordinator is built before setContentView.
 */
class BackupRestoreCoordinator(
    private val activity: AppCompatActivity,
    private val root: () -> View
) {

    /** SAF picker for writing the full backup ZIP. */
    private val createBackupLauncher = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val uri = result.data?.data ?: return@registerForActivityResult
        performBackup(uri)
    }

    /** SAF picker for choosing a backup ZIP to restore. */
    private val openBackupLauncher = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val uri = result.data?.data ?: return@registerForActivityResult
        confirmAndRestore(uri)
    }

    /** SAF tree picker for the automatic-backup destination folder (may be a Drive folder). */
    private val pickFolderLauncher = activity.registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            activity.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        enableAutoBackup(uri)
    }

    /**
     * Backup hub: one-off save (SAF), share (ACTION_SEND → Drive/Gmail/…), and the
     * automatic-backup-to-folder toggle. Restore stays its own menu action.
     */
    fun showBackupOptions() {
        val autoEnabled = AppPreferences.get(activity)
            .getBoolean(AppPreferences.KEY_AUTO_BACKUP_ENABLED, false)
        val items = arrayOf(
            activity.getString(R.string.backup_save_now),
            activity.getString(R.string.backup_share),
            activity.getString(if (autoEnabled) R.string.backup_auto_disable else R.string.backup_auto_setup)
        )
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.backup_options_title)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> launchCreateBackup()
                    1 -> shareBackup()
                    2 -> if (autoEnabled) disableAutoBackup() else pickFolderLauncher.launch(null)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun launchCreateBackup() {
        val stamp = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/zip")
            .putExtra(Intent.EXTRA_TITLE, "PeppolReader-Backup-$stamp.peppolbackup.zip")
        createBackupLauncher.launch(intent)
    }

    fun launchOpenBackup() {
        // Filter to ZIP files. Deliberately WITHOUT application/octet-stream: nearly every
        // binary reports that type, so listing it made the picker show "everything" and the
        // narrowing appeared broken. Providers that misreport ZIPs lose out — acceptable,
        // our own backups are written as application/zip.
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf("application/zip", "application/x-zip-compressed")
            )
        openBackupLauncher.launch(intent)
    }

    private fun performBackup(uri: Uri) {
        activity.lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    activity.contentResolver.openOutputStream(uri)?.use {
                        BackupManager.export(activity, it)
                    } ?: throw IOException("no output stream")
                    true
                }.getOrElse { false }
            }
            Snackbar.make(
                root(),
                if (ok) R.string.backup_success else R.string.backup_error,
                Snackbar.LENGTH_LONG
            ).show()
        }
    }

    /** Exports a backup to cache and hands it to any app (Google Drive, Gmail, …) via ACTION_SEND. */
    private fun shareBackup() {
        activity.lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) { BackupManager.exportToCacheForShare(activity) }
            if (file == null) {
                Snackbar.make(root(), R.string.backup_error, Snackbar.LENGTH_LONG).show()
                return@launch
            }
            val uri = FileProvider.getUriForFile(
                activity, "${activity.packageName}.fileprovider", file
            )
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(
                Intent.createChooser(share, activity.getString(R.string.backup_share))
            )
        }
    }

    /** Persists the chosen folder, schedules the periodic worker and runs one backup immediately. */
    private fun enableAutoBackup(treeUri: Uri) {
        AppPreferences.get(activity).edit {
            putString(AppPreferences.KEY_BACKUP_TREE_URI, treeUri.toString())
            putBoolean(AppPreferences.KEY_AUTO_BACKUP_ENABLED, true)
        }
        BackupScheduler.enable(activity)
        activity.lifecycleScope.launch {
            withContext(Dispatchers.IO) { runCatching { BackupManager.exportToTree(activity, treeUri) } }
            Snackbar.make(
                root(),
                activity.getString(R.string.backup_auto_enabled, PdfExporter.treeLabel(treeUri)),
                Snackbar.LENGTH_LONG
            ).show()
        }
    }

    private fun disableAutoBackup() {
        AppPreferences.get(activity).edit { putBoolean(AppPreferences.KEY_AUTO_BACKUP_ENABLED, false) }
        BackupScheduler.disable(activity)
        Snackbar.make(root(), R.string.backup_auto_disabled, Snackbar.LENGTH_LONG).show()
    }

    private fun confirmAndRestore(uri: Uri) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.backup_restore)
            .setMessage(R.string.backup_restore_warning)
            .setPositiveButton(R.string.backup_restore_confirm) { _, _ -> performRestore(uri) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun performRestore(uri: Uri) {
        activity.lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    activity.contentResolver.openInputStream(uri)?.use {
                        BackupManager.restore(activity, it)
                    } ?: false
                }.getOrElse { false }
            }
            if (ok) restartApp() else {
                Snackbar.make(root(), R.string.backup_restore_error, Snackbar.LENGTH_LONG).show()
            }
        }
    }

    /** Hard process restart so the freshly restored database is reopened cleanly. */
    private fun restartApp() {
        val intent = activity.packageManager.getLaunchIntentForPackage(activity.packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        activity.startActivity(intent)
        Runtime.getRuntime().exit(0)
    }
}

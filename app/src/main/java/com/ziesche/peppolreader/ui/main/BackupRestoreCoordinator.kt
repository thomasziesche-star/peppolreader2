package com.ziesche.peppolreader.ui.main

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.ziesche.peppolreader.R
import com.ziesche.peppolreader.data.BackupManager
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

    fun launchCreateBackup() {
        val stamp = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/zip")
            .putExtra(Intent.EXTRA_TITLE, "PeppolReader-Backup-$stamp.peppolbackup.zip")
        createBackupLauncher.launch(intent)
    }

    fun launchOpenBackup() {
        // Filter to ZIP-ish files. Providers report ZIPs under various MIME types, so keep the
        // broad base type and narrow via EXTRA_MIME_TYPES (octet-stream covers generic providers).
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")
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

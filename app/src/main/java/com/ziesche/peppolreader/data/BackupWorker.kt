package com.ziesche.peppolreader.data

import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.ziesche.peppolreader.util.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Periodic auto-backup: writes a timestamped ZIP into the user-chosen SAF folder (which may be a
 * Google-Drive-synced folder). No Drive API/account — the worker only writes into the granted
 * folder via SAF. No-op when no folder is configured.
 */
class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val prefs = AppPreferences.get(applicationContext)
        val uriString = prefs.getString(AppPreferences.KEY_BACKUP_TREE_URI, null)
            ?: return Result.success() // nothing configured → nothing to do
        val treeUri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return Result.success()

        return withContext(Dispatchers.IO) {
            val name = runCatching { BackupManager.exportToTree(applicationContext, treeUri) }.getOrNull()
            if (name != null) Result.success() else Result.retry()
        }
    }

    companion object {
        const val UNIQUE_NAME = "auto_backup_periodic"
    }
}

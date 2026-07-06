package com.ziesche.peppolreader.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Schedules the periodic [BackupWorker]. Mirrors NotificationScheduler: one unique periodic work,
 * replaced on re-enable. WorkManager persists the schedule across reboots on its own.
 */
object BackupScheduler {

    /** Enables a daily auto-backup (6h flex window); requires battery not low. */
    fun enable(context: Context, intervalDays: Long = 1) {
        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .build()

        val request = PeriodicWorkRequestBuilder<BackupWorker>(
            intervalDays, TimeUnit.DAYS,
            6, TimeUnit.HOURS
        )
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            BackupWorker.UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    fun disable(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(BackupWorker.UNIQUE_NAME)
    }
}

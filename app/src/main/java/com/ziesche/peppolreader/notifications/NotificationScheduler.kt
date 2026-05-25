package com.ziesche.peppolreader.notifications

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Thin facade around WorkManager for the due-date reminder feature.
 * UI toggles the periodic worker via [enable] / [disable] and can ask for an immediate run
 * via [triggerNow] when the user hits "Check now" in the settings sheet.
 */
object NotificationScheduler {

    private const val PERIODIC_WORK_NAME = "due_date_periodic"
    private const val ONE_SHOT_WORK_NAME = "due_date_now"

    /** Schedules a daily check (6h flex window). Replaces any existing work. */
    fun enable(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
            .build()

        val request = PeriodicWorkRequestBuilder<DueDateWorker>(
            1, TimeUnit.DAYS,
            6, TimeUnit.HOURS
        )
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    fun disable(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK_NAME)
    }

    /** One-shot run for the "Check now" button in the settings sheet. */
    fun triggerNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<DueDateWorker>().build()
        WorkManager.getInstance(context).enqueue(request)
    }
}

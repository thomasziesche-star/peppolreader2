package com.ziesche.peppolreader

import android.app.Application
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.ziesche.peppolreader.notifications.NotificationChannels
import com.ziesche.peppolreader.work.MetadataBackfillWorker

class PeppolReaderApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(applicationContext)
        NotificationChannels.ensureCreated(applicationContext)
        scheduleMetadataBackfill()
    }

    /**
     * Fills format/credit-note metadata for invoices imported by older versions. The worker is
     * idempotent (no-op once everything is backfilled), so KEEP-scheduling it on every start is
     * safe and needs no one-shot flag.
     */
    private fun scheduleMetadataBackfill() {
        // WorkManager is initialized via androidx.startup before onCreate in production, but not
        // under Robolectric — guard so unit tests that instantiate this Application don't crash.
        if (!WorkManager.isInitialized()) return
        runCatching {
            WorkManager.getInstance(this).enqueueUniqueWork(
                MetadataBackfillWorker.UNIQUE_NAME,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<MetadataBackfillWorker>().build()
            )
        }
    }
}

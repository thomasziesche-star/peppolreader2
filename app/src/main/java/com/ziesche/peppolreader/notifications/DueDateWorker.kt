package com.ziesche.peppolreader.notifications

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.ziesche.peppolreader.MainActivity
import com.ziesche.peppolreader.R
import com.ziesche.peppolreader.data.AppDatabase
import com.ziesche.peppolreader.data.model.Invoice
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Runs once per day. Picks invoices that will fall due within the user's chosen lead time,
 * are still unpaid and haven't been reminded today, then posts one notification per invoice
 * (Deep-link tap opens MainActivity with EXTRA_OPEN_INVOICE_ID).
 *
 * Anti-spam: each notification stamps `lastReminderShownAt = now`, the DAO query filters
 * those out for the rest of the day.
 *
 * Permission: posting is silently skipped when POST_NOTIFICATIONS hasn't been granted —
 * the user toggles the feature on from the settings sheet which then asks for the
 * permission, so this is just defence in depth.
 */
class DueDateWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val prefs = ReminderPrefs(ctx)
        if (!prefs.enabled) return Result.success()

        val notifManager = NotificationManagerCompat.from(ctx)
        if (!notifManager.areNotificationsEnabled()) return Result.success()

        NotificationChannels.ensureCreated(ctx)

        val daysBefore = prefs.daysBefore.coerceIn(0, 30)
        val now = Calendar.getInstance()
        val startOfToday = (now.clone() as Calendar).apply { atStartOfDay() }
        val thresholdCal = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, daysBefore) }
        val thresholdIso = ISO.format(thresholdCal.time)

        val dao = AppDatabase.getDatabase(ctx).invoiceDao()
        val due = dao.getDueSoon(thresholdIso, startOfToday.timeInMillis)

        if (due.isEmpty()) return Result.success()

        val currency = NumberFormat.getCurrencyInstance(Locale.getDefault())
        due.forEach { invoice ->
            postNotification(ctx, invoice, currency)
            dao.touchReminderShown(invoice.id, System.currentTimeMillis())
        }
        return Result.success()
    }

    private fun postNotification(
        ctx: Context,
        invoice: Invoice,
        currency: NumberFormat
    ) {
        val intent = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_OPEN_INVOICE_ID, invoice.id)
        }
        val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pending = PendingIntent.getActivity(
            ctx,
            invoice.id.toInt(), // unique request code per invoice
            intent,
            pendingFlags
        )

        val title = ctx.getString(R.string.notif_due_title)
        val text = ctx.getString(
            R.string.notif_due_text,
            invoice.supplierName,
            invoice.dueDate.orEmpty(),
            currency.format(invoice.payableAmount)
        )

        val builder = NotificationCompat.Builder(ctx, NotificationChannels.DUE_INVOICES)
            .setSmallIcon(R.drawable.ic_info) // existing in res; safe fallback
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pending)
            .setAutoCancel(true)

        try {
            NotificationManagerCompat.from(ctx).notify(NOTIF_BASE_ID + invoice.id.toInt(), builder.build())
        } catch (e: SecurityException) {
            // Android 13+ may throw if POST_NOTIFICATIONS got revoked mid-flight; ignore.
        }
    }

    private fun Calendar.atStartOfDay() {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }

    companion object {
        private val ISO = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        private const val NOTIF_BASE_ID = 1000
    }
}

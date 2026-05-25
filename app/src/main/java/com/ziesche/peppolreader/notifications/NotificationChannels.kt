package com.ziesche.peppolreader.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.content.getSystemService
import com.ziesche.peppolreader.R

object NotificationChannels {

    const val DUE_INVOICES = "due_invoices"

    /** Idempotent – safe to call from Application.onCreate() on every start. */
    fun ensureCreated(context: Context) {
        val manager = context.getSystemService<NotificationManager>() ?: return
        if (manager.getNotificationChannel(DUE_INVOICES) != null) return
        val channel = NotificationChannel(
            DUE_INVOICES,
            context.getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = context.getString(R.string.notif_channel_desc)
        }
        manager.createNotificationChannel(channel)
    }
}

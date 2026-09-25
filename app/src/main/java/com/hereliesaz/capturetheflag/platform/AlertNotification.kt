package com.hereliesaz.capturetheflag.platform

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.hereliesaz.capturetheflag.MainActivity
import java.util.concurrent.atomic.AtomicInteger

/** Urgent game alerts: sound, heads-up, one notification each. Separate from the quiet radio. */
object AlertNotification {
    private const val CHANNEL = "alerts"
    private val ids = AtomicInteger(1000)

    fun post(c: Context, title: String, body: String) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Alerts", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(
            c, 1, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(c, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setCategory(Notification.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        runCatching { nm.notify(ids.incrementAndGet(), n) }
    }
}

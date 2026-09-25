package com.hereliesaz.capturetheflag.platform

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.hereliesaz.capturetheflag.MainActivity

/**
 * The ongoing notification: the live broadcast, newest line on top. Shared with
 * [TrackingService], which uses it as its foreground notification so there is only ever one.
 */
object RadioNotification {
    const val ID = 7
    private const val CHANNEL = "live-game"

    /** Newest first. Kept so the tracking service can show the current broadcast when it starts. */
    @Volatile var lines: List<String> = emptyList()
        private set

    fun build(c: Context): Notification {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Live game", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            c, 0, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val head = lines.firstOrNull() ?: "You are on the map. So is everyone else."
        return Notification.Builder(c, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("ON AIR")
            .setContentText(head)
            .setStyle(Notification.BigTextStyle().bigText(lines.ifEmpty { listOf(head) }.joinToString("\n\n")))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .build()
    }

    fun post(c: Context, newestFirst: List<String>) {
        lines = newestFirst
        val nm = c.getSystemService(NotificationManager::class.java)
        if (newestFirst.isEmpty()) nm.cancel(ID) else runCatching { nm.notify(ID, build(c)) }
    }
}

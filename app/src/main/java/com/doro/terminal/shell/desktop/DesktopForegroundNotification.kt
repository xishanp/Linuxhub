package com.doro.terminal.shell.desktop

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.doro.terminal.shell.R
import com.java.myapplication.MainActivity

/** Shared foreground notification for the two processes that carry a desktop session. */
object DesktopForegroundNotification {
    const val CHANNEL_ID = "linuxhub_desktop_session"
    const val NOTIFICATION_ID = 4101

    fun startForeground(service: Service) {
        ensureChannel(service)
        service.startForeground(NOTIFICATION_ID, build(service))
    }

    fun dismiss(service: Service) {
        val manager = service.getSystemService(NotificationManager::class.java)
        manager.cancel(NOTIFICATION_ID)
    }

    private fun build(context: Context): Notification {
        val openApp = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            openApp,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val forceStop = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val forceStopIntent = PendingIntent.getActivity(
            context,
            1,
            forceStop,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_linuxhub)
            .setContentTitle(context.getString(R.string.desktop_session_notification_title))
            .setContentText(context.getString(R.string.desktop_session_notification_text))
            .setContentIntent(contentIntent)
            .addAction(
                R.drawable.ic_stat_linuxhub,
                context.getString(R.string.desktop_session_notification_force_stop),
                forceStopIntent,
            )
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .build()
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.desktop_session_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.desktop_session_notification_text)
                setShowBadge(false)
            },
        )
    }
}
